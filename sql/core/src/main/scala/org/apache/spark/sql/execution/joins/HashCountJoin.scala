/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.joins

import scala.collection.mutable

import org.apache.spark.sql.catalyst.{InternalRow, SQLConfHelper}
import org.apache.spark.sql.catalyst.analysis.CastSupport
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.BindReferences.bindReferences
import org.apache.spark.sql.catalyst.expressions.aggregate.{AggregateExpression, AggregateFunction, Complete, DeclarativeAggregate, Final, NoOp, Partial, PartialMerge}
import org.apache.spark.sql.catalyst.expressions.codegen._
import org.apache.spark.sql.catalyst.optimizer.{BuildLeft, BuildRight, BuildSide}
import org.apache.spark.sql.catalyst.plans._
import org.apache.spark.sql.catalyst.plans.physical.Partitioning
import org.apache.spark.sql.catalyst.types.DataTypeUtils
import org.apache.spark.sql.execution.{CodegenSupport, ExplainUtils, RowIterator}
import org.apache.spark.sql.execution.metric.SQLMetric
import org.apache.spark.sql.types.{BooleanType, IntegralType, LongType, StructField, StructType}

/**
 * @param relationTerm variable name for HashedRelation
 * @param keyIsUnique  indicate whether keys of HashedRelation known to be unique in code-gen time
 * @param isEmpty indicate whether it known to be EmptyHashedRelation in code-gen time
 */
// private[joins] case class HashedRelationInfo(
//    relationTerm: String,
//    keyIsUnique: Boolean,
//    isEmpty: Boolean)

trait HashCountJoin extends JoinCodegenSupport {
  // Toggle to enable detailed debug logging for CountJoin operations
  private val DEBUG_COUNTJOIN = false

  // Unique ID for this operator instance (for debugging)
  private lazy val opId: String = ExplainUtils.getOpId(this)

  private def dbg(msg: => String): Unit = {
    if (DEBUG_COUNTJOIN) logWarning(s"[Op$opId] $msg")
  }

  def buildSide: BuildSide

  // Constructor params of the concrete execs (Broadcast/ShuffledHashCountJoinExec), exposed as
  // trait members so the codegen path below can reach them.
  def countLeft: Option[Expression]
  def countRight: Option[NamedExpression]
  def aggregatesRight: Seq[AggregateExpression]
  def groupRight: Seq[NamedExpression]

  override def simpleStringWithNodeId(): String = {
    val opId = ExplainUtils.getOpId(this)
    s"$nodeName $joinType ${buildSide} ($opId)".trim
  }

  // Gets overridden in BroadcastHashCountJoinExec, etc.
  override def output: Seq[Attribute] = {
    joinType match {
      case _: InnerLike =>
        left.output ++ right.output
      case LeftOuter =>
        left.output ++ right.output.map(_.withNullability(true))
      case RightOuter =>
        left.output.map(_.withNullability(true)) ++ right.output
      case j: ExistenceJoin =>
        left.output :+ j.exists
      case LeftExistence(_) =>
        left.output
      case x =>
        throw new IllegalArgumentException(s"HashJoin should not take $x as the JoinType")
    }
  }

  // Whole-stage codegen for the inner count-join: the non-grouping path uses a single fixed
  // aggregate buffer; the grouping path uses a per-stream-row group map driving a per-task
  // GroupedCountAggregator. Both require carried DeclarativeAggregates and a right build side.
  override def supportCodegen: Boolean =
    joinType.isInstanceOf[InnerLike] && buildSide == BuildRight &&
      aggregatesRight.forall(_.aggregateFunction.isInstanceOf[DeclarativeAggregate])

  /** Per-task helper used by the grouped count-join codegen path. */
  def createGroupedAggregator(): GroupedCountAggregator =
    new GroupedCountAggregator(aggregatesRight, groupRight, right.output)

  override def outputPartitioning: Partitioning = buildSide match {
    case BuildLeft =>
      joinType match {
        case _: InnerLike | RightOuter => right.outputPartitioning
        case x =>
          throw new IllegalArgumentException(
            s"HashJoin should not take $x as the JoinType with building left side")
      }
    case BuildRight =>
      joinType match {
        case _: InnerLike | LeftOuter | LeftSemi | LeftAnti | _: ExistenceJoin =>
          left.outputPartitioning
        case x =>
          throw new IllegalArgumentException(
            s"HashJoin should not take $x as the JoinType with building right side")
      }
  }

  override def outputOrdering: Seq[SortOrder] = buildSide match {
    case BuildLeft =>
      joinType match {
        case _: InnerLike | RightOuter => right.outputOrdering
        case x =>
          throw new IllegalArgumentException(
            s"HashJoin should not take $x as the JoinType with building left side")
      }
    case BuildRight =>
      joinType match {
        case _: InnerLike | LeftOuter | LeftSemi | LeftAnti | _: ExistenceJoin =>
          left.outputOrdering
        case x =>
          throw new IllegalArgumentException(
            s"HashJoin should not take $x as the JoinType with building right side")
      }
  }

  protected lazy val (buildPlan, streamedPlan) = buildSide match {
    case BuildLeft => (left, right)
    case BuildRight => (right, left)
  }

  protected lazy val (buildKeys, streamedKeys) = {
    require(leftKeys.length == rightKeys.length &&
      leftKeys.map(_.dataType)
        .zip(rightKeys.map(_.dataType))
        .forall(types => DataTypeUtils.sameType(types._1, types._2)),
      "Join keys from two sides should have same length and types")
    buildSide match {
      case BuildLeft => (leftKeys, rightKeys)
      case BuildRight => (rightKeys, leftKeys)
    }
  }

  @transient protected lazy val (buildOutput, streamedOutput) = {
    buildSide match {
      case BuildLeft => (left.output, right.output)
      case BuildRight => (right.output, left.output)
    }
  }

  @transient protected lazy val buildBoundKeys =
    bindReferences(HashJoin.rewriteKeyExpr(buildKeys), buildOutput)

  @transient protected lazy val streamedBoundKeys =
    bindReferences(HashJoin.rewriteKeyExpr(streamedKeys), streamedOutput)

  protected def buildSideKeyGenerator(): UnsafeProjection =
    UnsafeProjection.create(buildBoundKeys)

  protected def streamSideKeyGenerator(): UnsafeProjection =
    UnsafeProjection.create(streamedBoundKeys)

  @transient protected[this] lazy val boundCondition = if (condition.isDefined) {
    if (joinType == FullOuter && buildSide == BuildLeft) {
      // Put join left side before right side. This is to be consistent with
      // `ShuffledHashJoinExec.fullOuterJoin`.
      Predicate.create(condition.get, buildPlan.output ++ streamedPlan.output).eval _
    } else {
      Predicate.create(condition.get, streamedPlan.output ++ buildPlan.output).eval _
    }
  } else {
    (r: InternalRow) => true
  }

  protected def createResultProjection(): (InternalRow) => InternalRow = joinType match {
    case LeftExistence(_) =>
      UnsafeProjection.create(output, output)
    case _ =>
      // Always put the stream side on left to simplify implementation
      // both of left and right side could be null
      UnsafeProjection.create(
        output, (streamedPlan.output ++ buildPlan.output).map(_.withNullability(true)))
  }

  private def innerJoin(
      streamIter: Iterator[InternalRow],
      hashedRelation: HashedRelation): Iterator[InternalRow] = {
    val joinRow = new JoinedRow
    val joinKeys = streamSideKeyGenerator()

    if (hashedRelation == EmptyHashedRelation) {
      Iterator.empty
    } else if (hashedRelation.keyIsUnique) {
      streamIter.flatMap { srow =>
        joinRow.withLeft(srow)
        val matched = hashedRelation.getValue(joinKeys(srow))
        if (matched != null) {
          Some(joinRow.withRight(matched)).filter(boundCondition)
        } else {
          None
        }
      }
    } else {
      streamIter.flatMap { srow =>
        joinRow.withLeft(srow)
        val matches = hashedRelation.get(joinKeys(srow))
        if (matches != null) {
          matches.map(joinRow.withRight).filter(boundCondition)
        } else {
          Seq.empty
        }
      }
    }
  }

  private def outerJoin(
      streamedIter: Iterator[InternalRow],
      hashedRelation: HashedRelation): Iterator[InternalRow] = {
    val joinedRow = new JoinedRow()
    val keyGenerator = streamSideKeyGenerator()
    val nullRow = new GenericInternalRow(buildPlan.output.length)

    if (hashedRelation.keyIsUnique) {
      streamedIter.map { currentRow =>
        val rowKey = keyGenerator(currentRow)
        joinedRow.withLeft(currentRow)
        val matched = hashedRelation.getValue(rowKey)
        if (matched != null && boundCondition(joinedRow.withRight(matched))) {
          joinedRow
        } else {
          joinedRow.withRight(nullRow)
        }
      }
    } else {
      streamedIter.flatMap { currentRow =>
        val rowKey = keyGenerator(currentRow)
        joinedRow.withLeft(currentRow)
        val buildIter = hashedRelation.get(rowKey)
        new RowIterator {
          private var found = false
          override def advanceNext(): Boolean = {
            while (buildIter != null && buildIter.hasNext) {
              val nextBuildRow = buildIter.next()
              if (boundCondition(joinedRow.withRight(nextBuildRow))) {
                found = true
                return true
              }
            }
            if (!found) {
              joinedRow.withRight(nullRow)
              found = true
              return true
            }
            false
          }
          override def getRow: InternalRow = joinedRow
        }.toScala
      }
    }
  }

  private def semiJoin(
      streamIter: Iterator[InternalRow],
      hashedRelation: HashedRelation): Iterator[InternalRow] = {
    val joinKeys = streamSideKeyGenerator()
    val joinedRow = new JoinedRow

    if (hashedRelation == EmptyHashedRelation) {
      Iterator.empty
    } else if (hashedRelation.keyIsUnique) {
      streamIter.filter { current =>
        val key = joinKeys(current)
        lazy val matched = hashedRelation.getValue(key)
        !key.anyNull && matched != null &&
          (condition.isEmpty || boundCondition(joinedRow(current, matched)))
      }
    } else {
      streamIter.filter { current =>
        val key = joinKeys(current)
        lazy val buildIter = hashedRelation.get(key)
        !key.anyNull && buildIter != null && (condition.isEmpty || buildIter.exists {
          (row: InternalRow) => boundCondition(joinedRow(current, row))
        })
      }
    }
  }

  private def existenceJoin(
      streamIter: Iterator[InternalRow],
      hashedRelation: HashedRelation): Iterator[InternalRow] = {
    val joinKeys = streamSideKeyGenerator()
    val result = new GenericInternalRow(Array[Any](null))
    val joinedRow = new JoinedRow

    if (hashedRelation.keyIsUnique) {
      streamIter.map { current =>
        val key = joinKeys(current)
        lazy val matched = hashedRelation.getValue(key)
        val exists = !key.anyNull && matched != null &&
          (condition.isEmpty || boundCondition(joinedRow(current, matched)))
        result.setBoolean(0, exists)
        joinedRow(current, result)
      }
    } else {
      streamIter.map { current =>
        val key = joinKeys(current)
        lazy val buildIter = hashedRelation.get(key)
        val exists = !key.anyNull && buildIter != null && (condition.isEmpty || buildIter.exists {
          (row: InternalRow) => boundCondition(joinedRow(current, row))
        })
        result.setBoolean(0, exists)
        joinedRow(current, result)
      }
    }
  }

  private def antiJoin(
      streamIter: Iterator[InternalRow],
      hashedRelation: HashedRelation): Iterator[InternalRow] = {
    // If the right side is empty, AntiJoin simply returns the left side.
    if (hashedRelation == EmptyHashedRelation) {
      return streamIter
    }

    val joinKeys = streamSideKeyGenerator()
    val joinedRow = new JoinedRow

    if (hashedRelation.keyIsUnique) {
      streamIter.filter { current =>
        val key = joinKeys(current)
        lazy val matched = hashedRelation.getValue(key)
        key.anyNull || matched == null ||
          (condition.isDefined && !boundCondition(joinedRow(current, matched)))
      }
    } else {
      streamIter.filter { current =>
        val key = joinKeys(current)
        lazy val buildIter = hashedRelation.get(key)
        key.anyNull || buildIter == null || (condition.isDefined && !buildIter.exists {
          row => boundCondition(joinedRow(current, row))
        })
      }
    }
  }

  private def countJoin(
                        streamIter: Iterator[InternalRow],
                        hashedRelation: HashedRelation,
                        countLeft: Option[Expression],
                        countRight: Option[NamedExpression],
                        aggregatesRight: Seq[AggregateExpression],
                        groupRight: Seq[NamedExpression]): Iterator[InternalRow] = {
    val joinKeys = streamSideKeyGenerator()
    val joinedRow = new JoinedRow

    val leftCountOrdinal = if (countLeft.get.references.nonEmpty) {
      AttributeSeq(streamedOutput)
        .indexOf(countLeft.get.references.head.exprId)
    }
    else {
      -1
    }
    val rightCountOrdinal = if (countRight.get.references.nonEmpty) {
      AttributeSeq(buildOutput)
        .indexOf(countRight.get.references.head.exprId)
    }
    else {
      -1
    }

    val doAggregation = aggregatesRight.nonEmpty
    val doGrouping = groupRight.nonEmpty

    val aggregateFunctions = aggregatesRight.map(_.aggregateFunction).toIndexedSeq

    // Aggregate functions can only be DeclarativeAggregates (such as Sum, Min, Max)
    val expressionAggInitialProjection = {
      val initExpressions = aggregateFunctions.flatMap {
        case ae: DeclarativeAggregate => ae.initialValues
      }
      MutableProjection.create(initExpressions, Nil)
    }

    val bufferSchema = aggregateFunctions.flatMap(_.aggBufferAttributes)

    val useUnsafeBuffer = bufferSchema
      .map(_.dataType).forall(UnsafeRow.isMutable)
    val unsafeProjection =
      UnsafeProjection.create(bufferSchema.map(_.dataType).toArray)

    def newBuffer(): InternalRow = {
      val bufferRow = new SpecificInternalRow(bufferSchema.map(_.dataType))
      if (useUnsafeBuffer) {
        // UnsafeProjection reuses its output row, so the result MUST be copied: the
        // grouped path stores one buffer per grouping key in bufferMap, and without the
        // copy every group would share (and overwrite) the same physical row.
        unsafeProjection.apply(bufferRow).copy()
      } else {
        bufferRow
      }
    }


    val evalExpressions = aggregateFunctions.map {
      case ae: DeclarativeAggregate => ae.evaluateExpression
      case agg: AggregateFunction => NoOp
    }

    val aggregateResult = new SpecificInternalRow(aggregatesRight.map(_.dataType))
    val expressionAggEvalProjection = MutableProjection.create(evalExpressions, bufferSchema)
    expressionAggEvalProjection.target(aggregateResult)

    val mergeExpressions =
      aggregateFunctions.zip(
        aggregatesRight.map(ae => (ae.mode, ae.isDistinct, ae.filter))).flatMap {
        case (ae: DeclarativeAggregate, (mode, isDistinct, filter)) =>
          mode match {
            case Partial | Complete =>
              if (filter.isDefined) {
                ae.updateExpressions.zip(ae.aggBufferAttributes).map {
                  case (updateExpr, attr) => If(filter.get, updateExpr, attr)
                }
              } else {
                ae.updateExpressions
              }
            case PartialMerge | Final => ae.mergeExpressions
          }
        case (agg: AggregateFunction, _) => Seq.fill(agg.aggBufferAttributes.length)(NoOp)
      }

    val updateProjection =
      MutableProjection.create(mergeExpressions, bufferSchema
        ++ right.output)

    val joinedRow2 = new JoinedRow
    val joinedRow3 = new JoinedRow
    val aggRow = new JoinedRow

    val sumRowSchema = StructType(StructField("c", LongType) :: Nil)
    val groupingProjection: UnsafeProjection =
      UnsafeProjection.create(groupRight, right.output)

    val aggResultAttributes = aggregatesRight.map(_.resultAttribute)

    val aggProjection = UnsafeProjection.create(
      aggResultAttributes ++ groupRight.map(_.toAttribute),
      aggResultAttributes ++ groupRight.map(_.toAttribute))

    val countAggGroupProjection = UnsafeProjection.create(
      Seq(countRight.get.toAttribute) ++ aggResultAttributes ++ groupRight.map(_.toAttribute),
      Seq(countRight.get.toAttribute)
        ++ aggResultAttributes ++ groupRight.map(_.toAttribute))

    val resultProjection = UnsafeProjection.create(
      left.output ++ Seq(countRight.get.toAttribute) ++ aggResultAttributes
        ++ groupRight.map(_.toAttribute),
      left.output ++ Seq(countRight.get.toAttribute)
        ++ aggResultAttributes ++ groupRight.map(_.toAttribute))

//    logWarning("agg buffer atts: " + bufferSchema.mkString("Array(", ", ", ")"))
//    logWarning("agg results: " + aggResultAttributes)
//    logWarning("evaluate expressions: " + evalExpressions.mkString("Array(", ", ", ")"))
    dbg(s"countJoin started: leftCountOrd=$leftCountOrdinal rightCountOrd=$rightCountOrdinal " +
      s"doAgg=$doAggregation doGroup=$doGrouping")

    if (hashedRelation == EmptyHashedRelation) {
      Iterator.empty
    } else {
      streamIter.flatMap { srow =>
        joinedRow.withLeft(srow)
        val joinKey = joinKeys(srow)
        val matches = hashedRelation.get(joinKey)

        // Only the grouping path uses these per-group maps; skip the allocation otherwise
        // (the non-grouped count/SUM path - the common case - never touches them).
        val sumMap = if (doGrouping) new mutable.LinkedHashMap[UnsafeRow, Long] else null
        val bufferMap =
          if (doGrouping) new mutable.LinkedHashMap[UnsafeRow, InternalRow] else null
        var buffer: InternalRow = null

        if (matches != null) {
          val leftCount = if (leftCountOrdinal != -1) {
            srow.getLong(leftCountOrdinal)
          }
          else {
            1
          }
          if (!doGrouping) {
            // In case we do not group, create one buffer and use it for all right matches
            buffer = newBuffer()
            expressionAggInitialProjection.target(buffer)(EmptyRow)
          }
          var matchCount = 0
          val rightCountSum = matches.map(joinedRow.withRight)
            .filter(boundCondition)
            .map(row => {
              // If the count attribute is not found in the child
              // plan (as is the case in the leaves),
              // assume count 1
              val rightCount = if (rightCountOrdinal != -1) {
                row.getRight.getLong(rightCountOrdinal)
              }
              else {
                1
              }

              matchCount += 1

              if (doAggregation || doGrouping) {
                if (doGrouping) {
                  val groupingKey = groupingProjection(row.getRight).copy()
                  var sum: Long = 0
                  if (bufferMap.contains(groupingKey)) {
                    buffer = bufferMap(groupingKey)
                    sum = sumMap(groupingKey)
                    sum += rightCount
                    sumMap.put(groupingKey, sum)
                  }
                  else {
                    buffer = newBuffer()
                    expressionAggInitialProjection.target(buffer)(EmptyRow)
                    bufferMap.put(groupingKey, buffer)
                    sum = rightCount
                    sumMap.put(groupingKey, sum)
                  }
                }

                aggRow(buffer, row.getRight)
                updateProjection.target(buffer)(aggRow)
              }
              // Return right count
              rightCount
            }
          ).sum

          dbg(s"  leftCount=$leftCount, rightCountSum=$rightCountSum, matchCount=$matchCount, " +
            s"product=${rightCountSum * leftCount}")
//          logWarning("buffermap after: " + bufferMap)
          if (doGrouping) {
            (bufferMap map {
            case (groupingKey: UnsafeRow, buf: InternalRow) =>
              val sumRow = new SpecificInternalRow(sumRowSchema)
              sumRow.setLong(0, sumMap(groupingKey) * leftCount)
              expressionAggEvalProjection(buf)

              val aggResult = aggProjection(joinedRow3(aggregateResult, groupingKey))
              joinedRow.withRight(countAggGroupProjection(joinedRow2(sumRow, aggResult)))
              // CRITICAL: UnsafeProjection reuses its output row, so we must copy
              // when producing multiple rows for the same left input
              resultProjection(joinedRow).copy()
            }).toSeq
          }
          else if (rightCountSum == 0) {
            // Every key-matching build row failed the residual (non-equi) condition, so this
            // stream row has no real match: emit nothing rather than a phantom count-0 row.
            // Carried counts start at 1 and only sum upward, so rightCountSum == 0 can only mean
            // "all matches filtered out", never a genuine zero-count group. Correct-by-construction
            // - the rewrite currently bails on cross-relation filters so this is not yet reachable
            // from SQL, but it makes the operator safe for future residual-filter support.
            Seq.empty
          }
          else {
            val sumRow = new SpecificInternalRow(sumRowSchema)
            sumRow.setLong(0, rightCountSum * leftCount)
            // withRight replaces the right part - now we have the left row + count
            if (doAggregation) {
              expressionAggEvalProjection(buffer)
              joinedRow.withRight(countAggGroupProjection(joinedRow2(sumRow, aggregateResult)))
              dbg(s"  Output (with agg): count=${rightCountSum * leftCount}, " +
                s"aggResult=${aggregateResult}")
            }
            else {
              joinedRow.withRight(sumRow)
              dbg(s"  Output (no agg): count=${rightCountSum * leftCount}")
            }
            val result = resultProjection(joinedRow)
            dbg(s"  Final result row: $result")
            Seq(result)
          }
        } else {
          Seq.empty
        }
      }
    }
  }

  protected def join(
      streamedIter: Iterator[InternalRow],
      hashed: HashedRelation,
      numOutputRows: SQLMetric,
      countLeft: Option[Expression],
      countRight: Option[NamedExpression],
      aggregatesRight: Seq[AggregateExpression],
      groupRight: Seq[NamedExpression]): Iterator[InternalRow] = {

    val joinedIter = countJoin(streamedIter, hashed, countLeft, countRight,
      aggregatesRight, groupRight)

//    val output = left.output ++ Seq(countRight.get.toAttribute) ++
//      aggregatesRight.map(_.resultAttribute)

    // countJoin already emits fully-formed UnsafeRows (the grouped path copies per group,
    // the non-grouped path returns one reused row per stream row), so re-projecting here
    // with an identical input/output schema was a redundant per-output-row copy.
    joinedIter.map { r =>
      numOutputRows += 1
      r
    }
  }

  override def doProduce(ctx: CodegenContext): String = {
    streamedPlan.asInstanceOf[CodegenSupport].produce(ctx, this)
  }

  override def doConsume(ctx: CodegenContext, input: Seq[ExprCode], row: ExprCode): String = {
    joinType match {
      case _: InnerLike => codegenCountInner(ctx, input)
      case LeftOuter | RightOuter => codegenOuter(ctx, input)
      case LeftSemi => codegenSemi(ctx, input)
      case LeftAnti => codegenAnti(ctx, input)
      case _: ExistenceJoin => codegenExistence(ctx, input)
      case x =>
        throw new IllegalArgumentException(
          s"HashJoin should not take $x as the JoinType")
    }
  }

  /**
   * Returns the code for generating join key for stream side, and expression of whether the key
   * has any null in it or not.
   */
  protected def genStreamSideJoinKey(
      ctx: CodegenContext,
      input: Seq[ExprCode]): (ExprCode, String) = {
    ctx.currentVars = input
    if (streamedBoundKeys.length == 1 && streamedBoundKeys.head.dataType == LongType) {
      // generate the join key as Long
      val ev = streamedBoundKeys.head.genCode(ctx)
      (ev, ev.isNull)
    } else {
      // generate the join key as UnsafeRow
      val ev = GenerateUnsafeProjection.createCode(ctx, streamedBoundKeys)
      (ev, s"${ev.value}.anyNull()")
    }
  }

  /**
   * Generates the code for Inner join.
   */
  protected def codegenInner(ctx: CodegenContext, input: Seq[ExprCode]): String = {
    val HashedRelationInfo(relationTerm, keyIsUnique, isEmptyHashedRelation) = prepareRelation(ctx)
    val (keyEv, anyNull) = genStreamSideJoinKey(ctx, input)
    val (matched, checkCondition, buildVars) = getJoinCondition(ctx, input, streamedPlan, buildPlan)
    val numOutput = metricTerm(ctx, "numOutputRows")

    val resultVars = buildSide match {
      case BuildLeft => buildVars ++ input
      case BuildRight => input ++ buildVars
    }

    if (isEmptyHashedRelation) {
      """
        |// If HashedRelation is empty, hash inner join simply returns nothing.
      """.stripMargin
    } else if (keyIsUnique) {
      s"""
         |// generate join key for stream side
         |${keyEv.code}
         |// find matches from HashedRelation
         |UnsafeRow $matched = $anyNull ? null: (UnsafeRow)$relationTerm.getValue(${keyEv.value});
         |if ($matched != null) {
         |  $checkCondition {
         |    $numOutput.add(1);
         |    ${consume(ctx, resultVars)}
         |  }
         |}
       """.stripMargin
    } else {
      val matches = ctx.freshName("matches")
      val iteratorCls = classOf[Iterator[UnsafeRow]].getName

      s"""
         |// generate join key for stream side
         |${keyEv.code}
         |// find matches from HashRelation
         |$iteratorCls $matches = $anyNull ?
         |  null : ($iteratorCls)$relationTerm.get(${keyEv.value});
         |if ($matches != null) {
         |  while ($matches.hasNext()) {
         |    UnsafeRow $matched = (UnsafeRow) $matches.next();
         |    $checkCondition {
         |      $numOutput.add(1);
         |      ${consume(ctx, resultVars)}
         |    }
         |  }
         |}
       """.stripMargin
    }
  }

  /**
   * Inner-join codegen for the non-grouping, pure-count path: per stream row, accumulate the
   * (count-multiplied) number of build matches that pass the residual condition and emit ONE row
   * (left cols ++ count), or nothing when no match passes (the phantom-count-0 case).
   */
  protected def codegenCountInner(ctx: CodegenContext, input: Seq[ExprCode]): String = {
    if (groupRight.nonEmpty) {
      return codegenCountGroupedInner(ctx, input)
    }
    assert(buildSide == BuildRight, "count join must build the right side")
    val HashedRelationInfo(relationTerm, keyIsUnique, isEmptyHashedRelation) = prepareRelation(ctx)
    if (isEmptyHashedRelation) {
      return "// empty HashedRelation: count inner join returns nothing"
    }
    val (keyEv, anyNull) = genStreamSideJoinKey(ctx, input)
    val (matched, checkCondition, buildVars) = getJoinCondition(ctx, input, streamedPlan, buildPlan)
    val numOutput = metricTerm(ctx, "numOutputRows")

    // Ordinals index streamedOutput (= left, the stream side) and buildOutput (= right).
    val leftCountOrdinal = countLeft.filter(_.references.nonEmpty)
      .map(c => streamedOutput.indexWhere(_.exprId == c.references.head.exprId)).getOrElse(-1)
    val rightCountOrdinal = countRight.filter(_.references.nonEmpty)
      .map(c => buildOutput.indexWhere(_.exprId == c.references.head.exprId)).getOrElse(-1)

    val rightCountSum = ctx.freshName("rightCountSum")
    val leftCount = ctx.freshName("leftCount")
    val countOut = ctx.freshName("countOut")

    // leftCount: the stream-side carried count (or 1 at a leaf). Force-evaluate the stream var and
    // blank its ExprCode so the later consume(input) does not re-declare it.
    val leftCountSetup = if (leftCountOrdinal != -1) {
      val eval = evaluateRequiredVariables(
        streamedPlan.output, input, AttributeSet(countLeft.get.references))
      s"$eval\nlong $leftCount = ${input(leftCountOrdinal).value};"
    } else {
      s"long $leftCount = 1L;"
    }

    // Aggregate buffer (single buffer, no grouping): reset per stream row, updated per match.
    val aggFns = aggregatesRight.map(_.aggregateFunction.asInstanceOf[DeclarativeAggregate])
    val bufferSchema = aggFns.flatMap(_.aggBufferAttributes)
    val bufVarsAndInit = aggFns.map(_.initialValues).map { exprs =>
      exprs.map { e =>
        val isNull = ctx.addMutableState(CodeGenerator.JAVA_BOOLEAN, "cjBufIsNull")
        val value = ctx.addMutableState(CodeGenerator.javaType(e.dataType), "cjBufValue")
        val ev = e.genCode(ctx)
        val initStr = s"${ev.code}\n$isNull = ${ev.isNull};\n$value = ${ev.value};"
        (ExprCode(EmptyBlock, JavaCode.isNullGlobal(isNull), JavaCode.global(value, e.dataType)),
          initStr)
      }
    }
    val bufVars = bufVarsAndInit.map(_.map(_._1))
    val flatBufVars = bufVars.flatten
    // Per-stream-row reset = re-run the buffer-init statements (captured as strings so we never
    // re-emit consumed ExprCodes, which would bleed the previous row's buffer).
    val bufferReset = bufVarsAndInit.flatten.map(_._2).mkString("\n")

    val updateExprs = aggregatesRight.map { e =>
      e.mode match {
        case Partial | Complete =>
          e.aggregateFunction.asInstanceOf[DeclarativeAggregate].updateExpressions
        case _ =>
          e.aggregateFunction.asInstanceOf[DeclarativeAggregate].mergeExpressions
      }
    }
    // Force-evaluate (once per match) the build vars referenced by the count column and by the
    // aggregate update; buildVars are otherwise lazy and would be uninitialised when read.
    val neededBuildRefs = AttributeSet(
      countRight.toSeq.flatMap(_.references) ++ updateExprs.flatten.flatMap(_.references))
    val buildEval = evaluateRequiredVariables(buildPlan.output, buildVars, neededBuildRefs)
    val rightCountAccum = if (rightCountOrdinal != -1) {
      s"$rightCountSum += ${buildVars(rightCountOrdinal).value};"
    } else {
      s"$rightCountSum += 1L;"
    }
    // Per-match update: read-before-write into the buffer.
    ctx.currentVars = flatBufVars ++ buildVars
    val bufferEvals = updateExprs.map(u =>
      bindReferences(u, bufferSchema ++ buildPlan.output).map(_.genCode(ctx)))
    val updateCode = bufferEvals.zipWithIndex.map { case (evalsForFn, i) =>
      val writes = evalsForFn.zip(bufVars(i)).map { case (ev, bv) =>
        s"${bv.isNull} = ${ev.isNull};\n${bv.value} = ${ev.value};"
      }
      s"${evaluateVariables(evalsForFn)}\n${writes.mkString("\n")}"
    }.mkString("\n")

    val matchBody = s"$buildEval\n$rightCountAccum\n$updateCode"
    val matchLoop = if (keyIsUnique) {
      s"""
         |UnsafeRow $matched = $anyNull ? null : (UnsafeRow)$relationTerm.getValue(${keyEv.value});
         |if ($matched != null) {
         |  $checkCondition {
         |    $matchBody
         |  }
         |}
       """.stripMargin
    } else {
      val matches = ctx.freshName("matches")
      val iteratorCls = classOf[Iterator[UnsafeRow]].getName
      s"""
         |$iteratorCls $matches = $anyNull ? null : ($iteratorCls)$relationTerm.get(${keyEv.value});
         |if ($matches != null) {
         |  while ($matches.hasNext()) {
         |    UnsafeRow $matched = (UnsafeRow) $matches.next();
         |    $checkCondition {
         |      $matchBody
         |    }
         |  }
         |}
       """.stripMargin
    }

    // Post-loop: evaluate the aggregate results from the buffer.
    ctx.currentVars = flatBufVars
    val aggResultVars =
      bindReferences(aggFns.map(_.evaluateExpression), bufferSchema).map(_.genCode(ctx))
    val aggResultEval = evaluateVariables(aggResultVars)

    val countEv = ExprCode(EmptyBlock, FalseLiteral, JavaCode.variable(countOut, LongType))
    val resultVars = input ++ Seq(countEv) ++ aggResultVars

    s"""
       |${keyEv.code}
       |long $rightCountSum = 0L;
       |$leftCountSetup
       |$bufferReset
       |$matchLoop
       |if ($rightCountSum != 0L) {
       |  long $countOut = $rightCountSum * $leftCount;
       |  $aggResultEval
       |  $numOutput.add(1);
       |  ${consume(ctx, resultVars)}
       |}
     """.stripMargin
  }

  /**
   * Inner-join codegen for the GROUPING path: per stream row, group the passing build matches by
   * groupRight into per-row maps (group key -> aggregate buffer, and -> summed count), then emit
   * one row per group (left cols ++ count-multiplied sum ++ aggregate results ++ group key). The
   * aggregate buffer math is delegated to a per-task GroupedCountAggregator; this code owns the
   * match loop, the residual condition, the maps, the count multiplication and the emission.
   */
  protected def codegenCountGroupedInner(ctx: CodegenContext, input: Seq[ExprCode]): String = {
    assert(buildSide == BuildRight, "count join must build the right side")
    val HashedRelationInfo(relationTerm, keyIsUnique, isEmptyHashedRelation) = prepareRelation(ctx)
    if (isEmptyHashedRelation) {
      return "// empty HashedRelation: count inner join returns nothing"
    }
    val (keyEv, anyNull) = genStreamSideJoinKey(ctx, input)
    val (matched, checkCondition, _) = getJoinCondition(ctx, input, streamedPlan, buildPlan)
    val numOutput = metricTerm(ctx, "numOutputRows")

    // Ordinals index streamedOutput (= left) and buildOutput (= right), as in the non-grouped path.
    val leftCountOrdinal = countLeft.filter(_.references.nonEmpty)
      .map(c => streamedOutput.indexWhere(_.exprId == c.references.head.exprId)).getOrElse(-1)
    val rightCountOrdinal = countRight.filter(_.references.nonEmpty)
      .map(c => buildOutput.indexWhere(_.exprId == c.references.head.exprId)).getOrElse(-1)

    val leftCount = ctx.freshName("leftCount")
    val leftCountSetup = if (leftCountOrdinal != -1) {
      val eval = evaluateRequiredVariables(
        streamedPlan.output, input, AttributeSet(countLeft.get.references))
      s"$eval\nlong $leftCount = ${input(leftCountOrdinal).value};"
    } else {
      s"long $leftCount = 1L;"
    }

    // Per-task grouped aggregator (group-key projection + buffer init/update/eval).
    val thisPlan = ctx.addReferenceObj("plan", this)
    val aggClass = classOf[GroupedCountAggregator].getName
    val aggTerm = ctx.addMutableState(aggClass, "groupedAgg",
      v => s"$v = $thisPlan.createGroupedAggregator();", forceInline = true)

    val rowCls = classOf[InternalRow].getName
    val mapCls = "java.util.LinkedHashMap"
    val bufMap = ctx.freshName("bufMap")
    val sumMap = ctx.freshName("sumMap")
    val rightCount = ctx.freshName("rightCount")
    val gkey = ctx.freshName("gkey")
    val buf = ctx.freshName("buf")
    val gkeyCopy = ctx.freshName("gkeyCopy")

    val rightCountExpr =
      if (rightCountOrdinal != -1) s"$matched.getLong($rightCountOrdinal)" else "1L"

    val matchBody =
      s"""
         |long $rightCount = $rightCountExpr;
         |UnsafeRow $gkey = $aggTerm.groupKey($matched);
         |$rowCls $buf = ($rowCls) $bufMap.get($gkey);
         |if ($buf == null) {
         |  UnsafeRow $gkeyCopy = $gkey.copy();
         |  $buf = $aggTerm.newBuffer();
         |  $bufMap.put($gkeyCopy, $buf);
         |  $sumMap.put($gkeyCopy, Long.valueOf($rightCount));
         |} else {
         |  $sumMap.put($gkey,
         |    Long.valueOf(((Long) $sumMap.get($gkey)).longValue() + $rightCount));
         |}
         |$aggTerm.update($buf, $matched);
       """.stripMargin

    val matchLoop = if (keyIsUnique) {
      s"""
         |UnsafeRow $matched = $anyNull ? null : (UnsafeRow)$relationTerm.getValue(${keyEv.value});
         |if ($matched != null) {
         |  $checkCondition {
         |    $matchBody
         |  }
         |}
       """.stripMargin
    } else {
      val matches = ctx.freshName("matches")
      val iteratorCls = classOf[Iterator[UnsafeRow]].getName
      s"""
         |$iteratorCls $matches = $anyNull ? null : ($iteratorCls)$relationTerm.get(${keyEv.value});
         |if ($matches != null) {
         |  while ($matches.hasNext()) {
         |    UnsafeRow $matched = (UnsafeRow) $matches.next();
         |    $checkCondition {
         |      $matchBody
         |    }
         |  }
         |}
       """.stripMargin
    }

    // Per-group emit: read the aggregate-result and group-key fields into locals, then consume.
    val aggResultAttributes = aggregatesRight.map(_.resultAttribute)
    val groupAttributes = groupRight.map(_.toAttribute)
    val aggResRow = ctx.freshName("aggRes")
    val cnt = ctx.freshName("cnt")
    val gkeyOut = ctx.freshName("gkeyOut")
    val bufOut = ctx.freshName("bufOut")

    def readField(row: String, attr: Attribute, i: Int): (String, ExprCode) = {
      val v = ctx.freshName("fval")
      val isNull = ctx.freshName("fIsNull")
      val jt = CodeGenerator.javaType(attr.dataType)
      val getter = CodeGenerator.getValue(row, attr.dataType, i.toString)
      val stmt =
        s"""boolean $isNull = $row.isNullAt($i);
           |$jt $v = $isNull ? ${CodeGenerator.defaultValue(attr.dataType)} : $getter;"""
          .stripMargin
      (stmt, ExprCode(EmptyBlock, JavaCode.isNullVariable(isNull),
        JavaCode.variable(v, attr.dataType)))
    }

    val aggReads =
      aggResultAttributes.zipWithIndex.map { case (a, i) => readField(aggResRow, a, i) }
    val groupReads = groupAttributes.zipWithIndex.map { case (a, i) => readField(gkeyOut, a, i) }
    val countEv = ExprCode(EmptyBlock, FalseLiteral, JavaCode.variable(cnt, LongType))
    val resultVars = input ++ Seq(countEv) ++ aggReads.map(_._2) ++ groupReads.map(_._2)
    val inputEval = evaluateVariables(input)
    val iter = ctx.freshName("groupIter")
    val entry = ctx.freshName("groupEntry")

    s"""
       |${keyEv.code}
       |$leftCountSetup
       |$mapCls<UnsafeRow, $rowCls> $bufMap = new $mapCls<UnsafeRow, $rowCls>();
       |$mapCls<UnsafeRow, Long> $sumMap = new $mapCls<UnsafeRow, Long>();
       |$matchLoop
       |$inputEval
       |java.util.Iterator $iter = $bufMap.entrySet().iterator();
       |while ($iter.hasNext()) {
       |  java.util.Map.Entry $entry = (java.util.Map.Entry) $iter.next();
       |  UnsafeRow $gkeyOut = (UnsafeRow) $entry.getKey();
       |  $rowCls $bufOut = ($rowCls) $entry.getValue();
       |  long $cnt = ((Long) $sumMap.get($gkeyOut)).longValue() * $leftCount;
       |  $rowCls $aggResRow = $aggTerm.eval($bufOut);
       |  ${aggReads.map(_._1).mkString("\n")}
       |  ${groupReads.map(_._1).mkString("\n")}
       |  $numOutput.add(1);
       |  ${consume(ctx, resultVars)}
       |}
     """.stripMargin
  }

  /**
   * Generates the code for left or right outer join.
   */
  protected def codegenOuter(ctx: CodegenContext, input: Seq[ExprCode]): String = {
    val HashedRelationInfo(relationTerm, keyIsUnique, _) = prepareRelation(ctx)
    val (keyEv, anyNull) = genStreamSideJoinKey(ctx, input)
    val matched = ctx.freshName("matched")
    val buildVars = genOneSideJoinVars(ctx, matched, buildPlan, setDefaultValue = true)
    val numOutput = metricTerm(ctx, "numOutputRows")

    // filter the output via condition
    val conditionPassed = ctx.freshName("conditionPassed")
    val checkCondition = if (condition.isDefined) {
      val expr = condition.get
      // evaluate the variables from build side that used by condition
      val eval = evaluateRequiredVariables(buildPlan.output, buildVars, expr.references)
      ctx.currentVars = input ++ buildVars
      val ev =
        BindReferences.bindReference(expr, streamedPlan.output ++ buildPlan.output).genCode(ctx)
      s"""
         |boolean $conditionPassed = true;
         |${eval.trim}
         |if ($matched != null) {
         |  ${ev.code}
         |  $conditionPassed = !${ev.isNull} && ${ev.value};
         |}
       """.stripMargin
    } else {
      s"final boolean $conditionPassed = true;"
    }

    val resultVars = buildSide match {
      case BuildLeft => buildVars ++ input
      case BuildRight => input ++ buildVars
    }

    if (keyIsUnique) {
      s"""
         |// generate join key for stream side
         |${keyEv.code}
         |// find matches from HashedRelation
         |UnsafeRow $matched = $anyNull ? null: (UnsafeRow)$relationTerm.getValue(${keyEv.value});
         |${checkCondition.trim}
         |if (!$conditionPassed) {
         |  $matched = null;
         |  // reset the variables those are already evaluated.
         |  ${buildVars.filter(_.code.isEmpty).map(v => s"${v.isNull} = true;").mkString("\n")}
         |}
         |$numOutput.add(1);
         |${consume(ctx, resultVars)}
       """.stripMargin
    } else {
      val matches = ctx.freshName("matches")
      val iteratorCls = classOf[Iterator[UnsafeRow]].getName
      val found = ctx.freshName("found")

      s"""
         |// generate join key for stream side
         |${keyEv.code}
         |// find matches from HashRelation
         |$iteratorCls $matches = $anyNull ? null : ($iteratorCls)$relationTerm.get(${keyEv.value});
         |boolean $found = false;
         |// the last iteration of this loop is to emit an empty row if there is no matched rows.
         |while ($matches != null && $matches.hasNext() || !$found) {
         |  UnsafeRow $matched = $matches != null && $matches.hasNext() ?
         |    (UnsafeRow) $matches.next() : null;
         |  ${checkCondition.trim}
         |  if ($conditionPassed) {
         |    $found = true;
         |    $numOutput.add(1);
         |    ${consume(ctx, resultVars)}
         |  }
         |}
       """.stripMargin
    }
  }

  /**
   * Generates the code for left semi join.
   */
  protected def codegenSemi(ctx: CodegenContext, input: Seq[ExprCode]): String = {
    val HashedRelationInfo(relationTerm, keyIsUnique, isEmptyHashedRelation) = prepareRelation(ctx)
    val (keyEv, anyNull) = genStreamSideJoinKey(ctx, input)
    val (matched, checkCondition, _) = getJoinCondition(ctx, input, streamedPlan, buildPlan)
    val numOutput = metricTerm(ctx, "numOutputRows")

    if (isEmptyHashedRelation) {
      """
        |// If HashedRelation is empty, hash semi join simply returns nothing.
      """.stripMargin
    } else if (keyIsUnique) {
      s"""
         |// generate join key for stream side
         |${keyEv.code}
         |// find matches from HashedRelation
         |UnsafeRow $matched = $anyNull ? null: (UnsafeRow)$relationTerm.getValue(${keyEv.value});
         |if ($matched != null) {
         |  $checkCondition {
         |    $numOutput.add(1);
         |    ${consume(ctx, input)}
         |  }
         |}
       """.stripMargin
    } else {
      val matches = ctx.freshName("matches")
      val iteratorCls = classOf[Iterator[UnsafeRow]].getName
      val found = ctx.freshName("found")

      s"""
         |// generate join key for stream side
         |${keyEv.code}
         |// find matches from HashRelation
         |$iteratorCls $matches = $anyNull ? null : ($iteratorCls)$relationTerm.get(${keyEv.value});
         |if ($matches != null) {
         |  boolean $found = false;
         |  while (!$found && $matches.hasNext()) {
         |    UnsafeRow $matched = (UnsafeRow) $matches.next();
         |    $checkCondition {
         |      $found = true;
         |    }
         |  }
         |  if ($found) {
         |    $numOutput.add(1);
         |    ${consume(ctx, input)}
         |  }
         |}
       """.stripMargin
    }
  }

  /**
   * Generates the code for anti join.
   */
  protected def codegenAnti(ctx: CodegenContext, input: Seq[ExprCode]): String = {
    val HashedRelationInfo(relationTerm, keyIsUnique, isEmptyHashedRelation) = prepareRelation(ctx)
    val numOutput = metricTerm(ctx, "numOutputRows")
    if (isEmptyHashedRelation) {
      return s"""
                |// If HashedRelation is empty, hash anti join simply returns the stream side.
                |$numOutput.add(1);
                |${consume(ctx, input)}
              """.stripMargin
    }

    val (keyEv, anyNull) = genStreamSideJoinKey(ctx, input)
    val (matched, checkCondition, _) = getJoinCondition(ctx, input, streamedPlan, buildPlan)

    if (keyIsUnique) {
      val found = ctx.freshName("found")
      s"""
         |boolean $found = false;
         |// generate join key for stream side
         |${keyEv.code}
         |// Check if the key has nulls.
         |if (!($anyNull)) {
         |  // Check if the HashedRelation exists.
         |  UnsafeRow $matched = (UnsafeRow)$relationTerm.getValue(${keyEv.value});
         |  if ($matched != null) {
         |    // Evaluate the condition.
         |    $checkCondition {
         |      $found = true;
         |    }
         |  }
         |}
         |if (!$found) {
         |  $numOutput.add(1);
         |  ${consume(ctx, input)}
         |}
       """.stripMargin
    } else {
      val matches = ctx.freshName("matches")
      val iteratorCls = classOf[Iterator[UnsafeRow]].getName
      val found = ctx.freshName("found")
      s"""
         |boolean $found = false;
         |// generate join key for stream side
         |${keyEv.code}
         |// Check if the key has nulls.
         |if (!($anyNull)) {
         |  // Check if the HashedRelation exists.
         |  $iteratorCls $matches = ($iteratorCls)$relationTerm.get(${keyEv.value});
         |  if ($matches != null) {
         |    // Evaluate the condition.
         |    while (!$found && $matches.hasNext()) {
         |      UnsafeRow $matched = (UnsafeRow) $matches.next();
         |      $checkCondition {
         |        $found = true;
         |      }
         |    }
         |  }
         |}
         |if (!$found) {
         |  $numOutput.add(1);
         |  ${consume(ctx, input)}
         |}
       """.stripMargin
    }
  }

  /**
   * Generates the code for existence join.
   */
  protected def codegenExistence(ctx: CodegenContext, input: Seq[ExprCode]): String = {
    val HashedRelationInfo(relationTerm, keyIsUnique, _) = prepareRelation(ctx)
    val (keyEv, anyNull) = genStreamSideJoinKey(ctx, input)
    val numOutput = metricTerm(ctx, "numOutputRows")
    val existsVar = ctx.freshName("exists")

    val matched = ctx.freshName("matched")
    val buildVars = genOneSideJoinVars(ctx, matched, buildPlan, setDefaultValue = false)
    val checkCondition = if (condition.isDefined) {
      val expr = condition.get
      // evaluate the variables from build side that used by condition
      val eval = evaluateRequiredVariables(buildPlan.output, buildVars, expr.references)
      // filter the output via condition
      ctx.currentVars = input ++ buildVars
      val ev =
        BindReferences.bindReference(expr, streamedPlan.output ++ buildPlan.output).genCode(ctx)
      s"""
         |$eval
         |${ev.code}
         |$existsVar = !${ev.isNull} && ${ev.value};
       """.stripMargin
    } else {
      s"$existsVar = true;"
    }

    val resultVar = input ++ Seq(ExprCode.forNonNullValue(
      JavaCode.variable(existsVar, BooleanType)))

    if (keyIsUnique) {
      s"""
         |// generate join key for stream side
         |${keyEv.code}
         |// find matches from HashedRelation
         |UnsafeRow $matched = $anyNull ? null: (UnsafeRow)$relationTerm.getValue(${keyEv.value});
         |boolean $existsVar = false;
         |if ($matched != null) {
         |  $checkCondition
         |}
         |$numOutput.add(1);
         |${consume(ctx, resultVar)}
       """.stripMargin
    } else {
      val matches = ctx.freshName("matches")
      val iteratorCls = classOf[Iterator[UnsafeRow]].getName
      s"""
         |// generate join key for stream side
         |${keyEv.code}
         |// find matches from HashRelation
         |$iteratorCls $matches = $anyNull ? null : ($iteratorCls)$relationTerm.get(${keyEv.value});
         |boolean $existsVar = false;
         |if ($matches != null) {
         |  while (!$existsVar && $matches.hasNext()) {
         |    UnsafeRow $matched = (UnsafeRow) $matches.next();
         |    $checkCondition
         |  }
         |}
         |$numOutput.add(1);
         |${consume(ctx, resultVar)}
       """.stripMargin
    }
  }

  protected def prepareRelation(ctx: CodegenContext): HashedRelationInfo
}

object HashCountJoin extends CastSupport with SQLConfHelper {

  private def canRewriteAsLongType(keys: Seq[Expression]): Boolean = {
    // TODO: support BooleanType, DateType and TimestampType
    keys.forall(_.dataType.isInstanceOf[IntegralType]) &&
      keys.map(_.dataType.defaultSize).sum <= 8
  }

  /**
   * Try to rewrite the key as LongType so we can use getLong(), if they key can fit with a long.
   *
   * If not, returns the original expressions.
   */
  def rewriteKeyExpr(keys: Seq[Expression]): Seq[Expression] = {
    assert(keys.nonEmpty)
    if (!canRewriteAsLongType(keys)) {
      return keys
    }

    var keyExpr: Expression = if (keys.head.dataType != LongType) {
      cast(keys.head, LongType)
    } else {
      keys.head
    }
    keys.tail.foreach { e =>
      val bits = e.dataType.defaultSize * 8
      keyExpr = BitwiseOr(ShiftLeft(keyExpr, Literal(bits)),
        BitwiseAnd(cast(e, LongType), Literal((1L << bits) - 1)))
    }
    keyExpr :: Nil
  }

  /**
   * Extract a given key which was previously packed in a long value using its index to
   * determine the number of bits to shift
   */
  def extractKeyExprAt(keys: Seq[Expression], index: Int): Expression = {
    assert(canRewriteAsLongType(keys))
    // jump over keys that have a higher index value than the required key
    if (keys.size == 1) {
      assert(index == 0)
      Cast(
        child = BoundReference(0, LongType, nullable = false),
        dataType = keys(index).dataType,
        timeZoneId = Option(conf.sessionLocalTimeZone),
        ansiEnabled = false)
    } else {
      val shiftedBits =
        keys.slice(index + 1, keys.size).map(_.dataType.defaultSize * 8).sum
      val mask = (1L << (keys(index).dataType.defaultSize * 8)) - 1
      // build the schema for unpacking the required key
      val castChild = BitwiseAnd(
        ShiftRightUnsigned(BoundReference(0, LongType, nullable = false), Literal(shiftedBits)),
        Literal(mask))
      Cast(
        child = castChild,
        dataType = keys(index).dataType,
        timeZoneId = Option(conf.sessionLocalTimeZone),
        ansiEnabled = false)
    }
  }
}

/**
 * Per-task helper for the GROUPED count-join codegen path. It bundles the same projections the
 * interpreted grouping path builds (group-key projection, buffer init/update/eval) so the
 * generated code can own the hot loop - matching, the residual condition, the per-stream-row group
 * map and the count multiplication - while delegating the aggregate buffer math to these proven,
 * MutableProjection-based helpers. The projections re-target their output row on each call, so an
 * instance is stateful and MUST NOT be shared across tasks/threads: the generated iterator creates
 * one per task in init().
 */
class GroupedCountAggregator(
    aggregatesRight: Seq[AggregateExpression],
    groupRight: Seq[NamedExpression],
    rightOutput: Seq[Attribute]) {

  private val aggregateFunctions = aggregatesRight.map(_.aggregateFunction).toIndexedSeq
  private val bufferSchema = aggregateFunctions.flatMap(_.aggBufferAttributes)
  private val useUnsafeBuffer = bufferSchema.map(_.dataType).forall(UnsafeRow.isMutable)
  private val unsafeProjection = UnsafeProjection.create(bufferSchema.map(_.dataType).toArray)

  private val initProjection = {
    val initExpressions = aggregateFunctions.flatMap {
      case ae: DeclarativeAggregate => ae.initialValues
    }
    MutableProjection.create(initExpressions, Nil)
  }

  private val mergeExpressions =
    aggregateFunctions.zip(
      aggregatesRight.map(ae => (ae.mode, ae.isDistinct, ae.filter))).flatMap {
      case (ae: DeclarativeAggregate, (mode, _, filter)) =>
        mode match {
          case Partial | Complete =>
            if (filter.isDefined) {
              ae.updateExpressions.zip(ae.aggBufferAttributes).map {
                case (updateExpr, attr) => If(filter.get, updateExpr, attr)
              }
            } else {
              ae.updateExpressions
            }
          case _ => ae.mergeExpressions
        }
      case (agg: AggregateFunction, _) => Seq.fill(agg.aggBufferAttributes.length)(NoOp)
    }
  private val updateProjection =
    MutableProjection.create(mergeExpressions, bufferSchema ++ rightOutput)

  private val evalExpressions = aggregateFunctions.map {
    case ae: DeclarativeAggregate => ae.evaluateExpression
    case _: AggregateFunction => NoOp
  }
  private val aggregateResult = new SpecificInternalRow(aggregatesRight.map(_.dataType))
  private val evalProjection = {
    val p = MutableProjection.create(evalExpressions, bufferSchema)
    p.target(aggregateResult)
    p
  }

  private val groupingProjection = UnsafeProjection.create(groupRight, rightOutput)
  private val aggRow = new JoinedRow

  /** Group key for a build row. The returned UnsafeRow is REUSED - copy before using as a key. */
  def groupKey(buildRow: InternalRow): UnsafeRow = groupingProjection(buildRow)

  /** A fresh, initialised aggregate buffer for a new group. */
  def newBuffer(): InternalRow = {
    val bufferRow = new SpecificInternalRow(bufferSchema.map(_.dataType))
    val buffer = if (useUnsafeBuffer) unsafeProjection.apply(bufferRow).copy() else bufferRow
    initProjection.target(buffer)(EmptyRow)
    buffer
  }

  /** Folds one build row into a group's buffer. */
  def update(buffer: InternalRow, buildRow: InternalRow): Unit = {
    aggRow(buffer, buildRow)
    updateProjection.target(buffer)(aggRow)
  }

  /** Evaluates a group's buffer into the aggregate result row (REUSED - read immediately). */
  def eval(buffer: InternalRow): InternalRow = {
    evalProjection(buffer)
    aggregateResult
  }
}
