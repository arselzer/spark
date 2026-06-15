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

import org.apache.spark.TaskContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.BindReferences.bindReferences
import org.apache.spark.sql.catalyst.expressions.aggregate.{AggregateExpression, Complete, DeclarativeAggregate, Partial}
import org.apache.spark.sql.catalyst.expressions.codegen._
import org.apache.spark.sql.catalyst.plans._
import org.apache.spark.sql.catalyst.plans.physical.Partitioning
import org.apache.spark.sql.execution._
import org.apache.spark.sql.execution.metric.SQLMetrics
import org.apache.spark.sql.types.LongType

/**
 * Performs a sort merge join of two child relations.
 */
case class SortMergeCountJoinExec(
    leftKeys: Seq[Expression],
    rightKeys: Seq[Expression],
    joinType: JoinType,
    condition: Option[Expression],
    left: SparkPlan,
    right: SparkPlan,
    countLeft: Option[Expression],
    countRight: Option[NamedExpression],
    aggregatesRight: Seq[AggregateExpression],
    groupRight: Seq[NamedExpression],
    isSkewJoin: Boolean = false) extends ShuffledJoin {

  // The rewrite only ever emits inner count joins; the non-inner branches of the SMJ evaluator
  // delegate to stock iterators that drop the carried count. Fail loudly on any non-inner type.
  require(joinType.isInstanceOf[InnerLike],
    s"CountJoin only supports inner joins, got $joinType")

  override lazy val metrics = Map(
    "numOutputRows" -> SQLMetrics.createMetric(sparkContext, "number of output rows"),
    "spillSize" -> SQLMetrics.createSizeMetric(sparkContext, "spill size"))

  override def output: Seq[Attribute] = left.output ++ Seq(countRight.get.toAttribute) ++
    aggregatesRight.map(_.resultAttribute) ++ groupRight.map(_.toAttribute)

  override def outputPartitioning: Partitioning = left.outputPartitioning
  override def outputOrdering: Seq[SortOrder] = joinType match {
    // For inner join, orders of both sides keys should be kept.
    case _: InnerLike =>
      val leftKeyOrdering = getKeyOrdering(leftKeys, left.outputOrdering)
      val rightKeyOrdering = getKeyOrdering(rightKeys, right.outputOrdering)
      leftKeyOrdering.zip(rightKeyOrdering).map { case (lKey, rKey) =>
        // Also add expressions from right side sort order
        val sameOrderExpressions = ExpressionSet(lKey.sameOrderExpressions ++ rKey.children)
        SortOrder(lKey.child, Ascending, sameOrderExpressions.toSeq)
      }
    // For left and right outer joins, the output is ordered by the streamed input's join keys.
    case LeftOuter => getKeyOrdering(leftKeys, left.outputOrdering)
    case RightOuter => getKeyOrdering(rightKeys, right.outputOrdering)
    // There are null rows in both streams, so there is no order.
    case FullOuter => Nil
    case LeftExistence(_) => getKeyOrdering(leftKeys, left.outputOrdering)
    case x =>
      throw new IllegalArgumentException(
        s"${getClass.getSimpleName} should not take $x as the JoinType")
  }

  /**
   * The utility method to get output ordering for left or right side of the join.
   *
   * Returns the required ordering for left or right child if childOutputOrdering does not
   * satisfy the required ordering; otherwise, which means the child does not need to be sorted
   * again, returns the required ordering for this child with extra "sameOrderExpressions" from
   * the child's outputOrdering.
   */
  private def getKeyOrdering(keys: Seq[Expression], childOutputOrdering: Seq[SortOrder])
    : Seq[SortOrder] = {
    val requiredOrdering = requiredOrders(keys)
    if (SortOrder.orderingSatisfies(childOutputOrdering, requiredOrdering)) {
      keys.zip(childOutputOrdering).map { case (key, childOrder) =>
        val sameOrderExpressionsSet = ExpressionSet(childOrder.children) - key
        SortOrder(key, Ascending, sameOrderExpressionsSet.toSeq)
      }
    } else {
      requiredOrdering
    }
  }

  override def requiredChildOrdering: Seq[Seq[SortOrder]] =
    requiredOrders(leftKeys) :: requiredOrders(rightKeys) :: Nil

  private def requiredOrders(keys: Seq[Expression]): Seq[SortOrder] = {
    // This must be ascending in order to agree with the `keyOrdering` defined in `doExecute()`.
    keys.map(SortOrder(_, Ascending))
  }

  private def getSpillThreshold: Int = {
    conf.sortMergeJoinExecBufferSpillThreshold
  }

  // Flag to only buffer first matched row, to avoid buffering unnecessary rows.
  private val onlyBufferFirstMatchedRow = (joinType, condition) match {
    case (LeftExistence(_), None) => true
    case _ => false
  }

  private def getInMemoryThreshold: Int = {
    if (onlyBufferFirstMatchedRow) {
      1
    } else {
      conf.sortMergeJoinExecBufferInMemoryThreshold
    }
  }

  private def getSizeInBytesSpillThreshold: Long = {
    conf.sortMergeJoinExecBufferSpillSizeThreshold
  }

  protected override def doExecute(): RDD[InternalRow] = {
    val numOutputRows = longMetric("numOutputRows")
    val spillSize = longMetric("spillSize")
    val spillThreshold = getSpillThreshold
    val sizeInBytesSpillThreshold = getSizeInBytesSpillThreshold
    val inMemoryThreshold = getInMemoryThreshold
    val opId = ExplainUtils.getOpId(this)
    val evaluatorFactory = new SortMergeCountJoinEvaluatorFactory(
      leftKeys,
      rightKeys,
      joinType,
      condition,
      left,
      right,
      countLeft: Option[Expression],
      countRight: Option[NamedExpression],
      aggregatesRight: Seq[AggregateExpression],
      groupRight: Seq[NamedExpression],
      output,
      inMemoryThreshold,
      spillThreshold,
      sizeInBytesSpillThreshold,
      numOutputRows,
      spillSize,
      onlyBufferFirstMatchedRow,
      opId
    )
    if (conf.usePartitionEvaluator) {
      left.execute().zipPartitionsWithEvaluator(right.execute(), evaluatorFactory)
    } else {
      left.execute().zipPartitions(right.execute()) { (leftIter, rightIter) =>
        val evaluator = evaluatorFactory.createEvaluator()
        evaluator.eval(0, leftIter, rightIter)
      }
    }
  }

  private lazy val ((streamedPlan, streamedKeys), (bufferedPlan, bufferedKeys)) = joinType match {
    case _: InnerLike | LeftOuter | FullOuter | LeftExistence(_) =>
      ((left, leftKeys), (right, rightKeys))
    case RightOuter => ((right, rightKeys), (left, leftKeys))
    case x =>
      throw new IllegalArgumentException(
        s"SortMergeJoin.streamedPlan/bufferedPlan should not take $x as the JoinType")
  }

  private lazy val streamedOutput = streamedPlan.output
  private lazy val bufferedOutput = bufferedPlan.output

  // Only the inner count-join path is codegen'd (the rewrite only ever emits inner). Both the
  // grouping and non-grouping inner paths are supported; non-declarative aggregates fall back to
  // the interpreted evaluator (supportCodegen = false routes doExecute through the factory).
  override def supportCodegen: Boolean =
    joinType.isInstanceOf[InnerLike] &&
      aggregatesRight.forall(_.aggregateFunction.isInstanceOf[DeclarativeAggregate])

  // Per-task grouped aggregator (group-key projection + buffer init/eval), built from generated
  // code via the plan reference. Build side is always the right child for an inner count join.
  def createGroupedAggregator(): GroupedCountAggregator =
    new GroupedCountAggregator(aggregatesRight, groupRight, right.output)

  override def inputRDDs(): Seq[RDD[InternalRow]] = {
    streamedPlan.execute() :: bufferedPlan.execute() :: Nil
  }

  private def createJoinKey(
      ctx: CodegenContext,
      row: String,
      keys: Seq[Expression],
      input: Seq[Attribute]): Seq[ExprCode] = {
    ctx.INPUT_ROW = row
    ctx.currentVars = null
    bindReferences(keys, input).map(_.genCode(ctx))
  }

  private def copyKeys(ctx: CodegenContext, vars: Seq[ExprCode]): Seq[ExprCode] = {
    vars.zipWithIndex.map { case (ev, i) =>
      ctx.addBufferedState(leftKeys(i).dataType, "value", ev.value)
    }
  }

  private def genComparison(ctx: CodegenContext, a: Seq[ExprCode], b: Seq[ExprCode]): String = {
    val comparisons = a.zip(b).zipWithIndex.map { case ((l, r), i) =>
      s"""
         |if (comp == 0) {
         |  comp = ${ctx.genComp(leftKeys(i).dataType, l.value, r.value)};
         |}
       """.stripMargin.trim
    }
    s"""
       |comp = 0;
       |${comparisons.mkString("\n")}
     """.stripMargin
  }

  /**
   * Generate a function to scan both sides to find a match, returns:
   * 1. the function name
   * 2. the term for matched one row from streamed side
   * 3. the term for buffered rows from buffered side
   */
  private def genScanner(ctx: CodegenContext): (String, String, String) = {
    // Create class member for next row from both sides.
    // Inline mutable state since not many join operations in a task
    val streamedRow = ctx.addMutableState("InternalRow", "streamedRow", forceInline = true)
    val bufferedRow = ctx.addMutableState("InternalRow", "bufferedRow", forceInline = true)

    // Create variables for join keys from both sides.
    val streamedKeyVars = createJoinKey(ctx, streamedRow, streamedKeys, streamedOutput)
    val streamedAnyNull = streamedKeyVars.map(_.isNull).mkString(" || ")
    val bufferedKeyTmpVars = createJoinKey(ctx, bufferedRow, bufferedKeys, bufferedOutput)
    val bufferedAnyNull = bufferedKeyTmpVars.map(_.isNull).mkString(" || ")
    // Copy the buffered key as class members so they could be used in next function call.
    val bufferedKeyVars = copyKeys(ctx, bufferedKeyTmpVars)

    // A list to hold all matched rows from buffered side.
    val clsName = classOf[ExternalAppendOnlyUnsafeRowArray].getName

    val spillThreshold = getSpillThreshold
    val sizeInBytesSpillThreshold = getSizeInBytesSpillThreshold
    val inMemoryThreshold = getInMemoryThreshold

    // Inline mutable state since not many join operations in a task
    val matches = ctx.addMutableState(clsName, "matches",
      v => s"$v = new $clsName($inMemoryThreshold, ${sizeInBytesSpillThreshold}L, " +
        s"$spillThreshold, ${sizeInBytesSpillThreshold}L);", forceInline = true)
    // Copy the streamed keys as class members so they could be used in next function call.
    val matchedKeyVars = copyKeys(ctx, streamedKeyVars)

    // Handle the case when streamed rows has any NULL keys.
    val handleStreamedAnyNull = joinType match {
      case _: InnerLike | LeftSemi =>
        // Skip streamed row.
        s"""
           |$streamedRow = null;
           |continue;
         """.stripMargin
      case LeftOuter | RightOuter | LeftAnti | ExistenceJoin(_) =>
        // Eagerly return streamed row. Only call `matches.clear()` when `matches.isEmpty()` is
        // false, to reduce unnecessary computation.
        s"""
           |if (!$matches.isEmpty()) {
           |  $matches.clear();
           |}
           |return false;
         """.stripMargin
      case x =>
        throw new IllegalArgumentException(
          s"SortMergeJoin.genScanner should not take $x as the JoinType")
    }

    // Handle the case when streamed keys has no match with buffered side.
    val handleStreamedWithoutMatch = joinType match {
      case _: InnerLike | LeftSemi =>
        // Skip streamed row.
        s"$streamedRow = null;"
      case LeftOuter | RightOuter | LeftAnti | ExistenceJoin(_) =>
        // Eagerly return with streamed row.
        "return false;"
      case x =>
        throw new IllegalArgumentException(
          s"SortMergeJoin.genScanner should not take $x as the JoinType")
    }

    val addRowToBuffer =
      if (onlyBufferFirstMatchedRow) {
        s"""
           |if ($matches.isEmpty()) {
           |  $matches.add((UnsafeRow) $bufferedRow);
           |}
         """.stripMargin
      } else {
        s"$matches.add((UnsafeRow) $bufferedRow);"
      }

    // Generate a function to scan both streamed and buffered sides to find a match.
    // Return whether a match is found.
    //
    // `streamedIter`: the iterator for streamed side.
    // `bufferedIter`: the iterator for buffered side.
    // `streamedRow`: the current row from streamed side.
    //                When `streamedIter` is empty, `streamedRow` is null.
    // `matches`: the rows from buffered side already matched with `streamedRow`.
    //            `matches` is buffered and reused for all `streamedRow`s having same join keys.
    //            If there is no match with `streamedRow`, `matches` is empty.
    // `bufferedRow`: the current matched row from buffered side.
    //
    // The function has the following step:
    //  - Step 1: Find the next `streamedRow` with non-null join keys.
    //            For `streamedRow` with null join keys (`handleStreamedAnyNull`):
    //            1. Inner and Left Semi join: skip the row. `matches` will be cleared later when
    //                                         hitting the next `streamedRow` with non-null join
    //                                         keys.
    //            2. Left/Right Outer, Left Anti and Existence join: clear the previous `matches`
    //                                                               if needed, keep the row, and
    //                                                               return false.
    //
    //  - Step 2: Find the `matches` from buffered side having same join keys with `streamedRow`.
    //            Clear `matches` if we hit a new `streamedRow`, as we need to find new matches.
    //            Use `bufferedRow` to iterate buffered side to put all matched rows into
    //            `matches` (`addRowToBuffer`). Return true when getting all matched rows.
    //            For `streamedRow` without `matches` (`handleStreamedWithoutMatch`):
    //            1. Inner and Left Semi join: skip the row.
    //            2. Left/Right Outer, Left Anti and Existence join: keep the row and return false
    //                                                               (with `matches` being empty).
    val findNextJoinRowsFuncName = ctx.freshName("findNextJoinRows")
    ctx.addNewFunction(findNextJoinRowsFuncName,
      s"""
         |private boolean $findNextJoinRowsFuncName(
         |    scala.collection.Iterator streamedIter,
         |    scala.collection.Iterator bufferedIter) {
         |  $streamedRow = null;
         |  int comp = 0;
         |  while ($streamedRow == null) {
         |    if (!streamedIter.hasNext()) return false;
         |    $streamedRow = (InternalRow) streamedIter.next();
         |    ${streamedKeyVars.map(_.code).mkString("\n")}
         |    if ($streamedAnyNull) {
         |      $handleStreamedAnyNull
         |    }
         |    if (!$matches.isEmpty()) {
         |      ${genComparison(ctx, streamedKeyVars, matchedKeyVars)}
         |      if (comp == 0) {
         |        return true;
         |      }
         |      $matches.clear();
         |    }
         |
         |    do {
         |      if ($bufferedRow == null) {
         |        if (!bufferedIter.hasNext()) {
         |          ${matchedKeyVars.map(_.code).mkString("\n")}
         |          return !$matches.isEmpty();
         |        }
         |        $bufferedRow = (InternalRow) bufferedIter.next();
         |        ${bufferedKeyTmpVars.map(_.code).mkString("\n")}
         |        if ($bufferedAnyNull) {
         |          $bufferedRow = null;
         |          continue;
         |        }
         |        ${bufferedKeyVars.map(_.code).mkString("\n")}
         |      }
         |      ${genComparison(ctx, streamedKeyVars, bufferedKeyVars)}
         |      if (comp > 0) {
         |        $bufferedRow = null;
         |      } else if (comp < 0) {
         |        if (!$matches.isEmpty()) {
         |          ${matchedKeyVars.map(_.code).mkString("\n")}
         |          return true;
         |        } else {
         |          $handleStreamedWithoutMatch
         |        }
         |      } else {
         |        $addRowToBuffer
         |        $bufferedRow = null;
         |      }
         |    } while ($streamedRow != null);
         |  }
         |  return false; // unreachable
         |}
       """.stripMargin, inlineToOuterClass = true)

    (findNextJoinRowsFuncName, streamedRow, matches)
  }

  override def needCopyResult: Boolean = true

  /**
   * This is called by generated Java class, should be public.
   */
  def getTaskContext(): TaskContext = {
    TaskContext.get()
  }

  override def doProduce(ctx: CodegenContext): String = {
    if (groupRight.isEmpty) produceCountInner(ctx) else produceCountGroupedInner(ctx)
  }

  // Non-grouping inner count join. Mirrors HashCountJoin.codegenCountInner: per streamed (left)
  // row, fold the count-multiplied matches into a single aggregate buffer and emit one row (left
  // cols ++ count ++ aggregate results), or nothing when no match passes the residual condition
  // (the phantom-count-0 case). The only structural difference from the hash path is the match
  // source - the sort-merge scanner's buffered `matches` array rather than a HashedRelation probe.
  private def produceCountInner(ctx: CodegenContext): String = {
    val streamedInput = ctx.addMutableState("scala.collection.Iterator", "streamedInput",
      v => s"$v = inputs[0];", forceInline = true)
    val bufferedInput = ctx.addMutableState("scala.collection.Iterator", "bufferedInput",
      v => s"$v = inputs[1];", forceInline = true)

    val (findNextJoinRowsFuncName, streamedRow, matches) = genScanner(ctx)
    val findNextJoinRows = s"$findNextJoinRowsFuncName($streamedInput, $bufferedInput)"
    val thisPlan = ctx.addReferenceObj("plan", this)
    val eagerCleanup = s"$thisPlan.cleanupResources();"
    val numOutput = metricTerm(ctx, "numOutputRows")

    // Streamed (left) columns, evaluated once per streamed row. The buffered (right) columns and
    // the residual-condition check come from getJoinCondition, bound to the current match row.
    // getJoinCondition copies streamedVars internally, so evaluating the originals here is safe.
    val streamedVars = genOneSideJoinVars(ctx, streamedRow, streamedPlan, setDefaultValue = false)
    // Evaluate the streamed (left) columns once per streamed row BEFORE getJoinCondition copies
    // them for the residual condition. Otherwise the condition re-declares the same variables in
    // the inner match scope (Java forbids local-variable shadowing) and the generated code fails
    // to compile. After this, getJoinCondition copies already-blanked vars and references values.
    val streamedEval = evaluateVariables(streamedVars)
    val bufferedRow = ctx.freshName("bufferedRow")
    val (_, checkCondition, bufferedVars) =
      getJoinCondition(ctx, streamedVars, streamedPlan, bufferedPlan, Some(bufferedRow))

    // Count ordinals index streamedOutput (= left) and bufferedOutput (= right).
    val leftCountOrdinal = countLeft.filter(_.references.nonEmpty)
      .map(c => streamedOutput.indexWhere(_.exprId == c.references.head.exprId)).getOrElse(-1)
    val rightCountOrdinal = countRight.filter(_.references.nonEmpty)
      .map(c => bufferedOutput.indexWhere(_.exprId == c.references.head.exprId)).getOrElse(-1)

    val rightCountSum = ctx.freshName("rightCountSum")
    val leftCount = ctx.freshName("leftCount")
    val countOut = ctx.freshName("countOut")

    // Single aggregate buffer (no grouping): mutable-state slot vars, reset per streamed row.
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
    val bufferReset = bufVarsAndInit.flatten.map(_._2).mkString("\n")

    val updateExprs = aggregatesRight.map { e =>
      e.mode match {
        case Partial | Complete =>
          e.aggregateFunction.asInstanceOf[DeclarativeAggregate].updateExpressions
        case _ =>
          e.aggregateFunction.asInstanceOf[DeclarativeAggregate].mergeExpressions
      }
    }
    val neededBuildRefs = AttributeSet(
      countRight.toSeq.flatMap(_.references) ++ updateExprs.flatten.flatMap(_.references))
    val buildEval = evaluateRequiredVariables(bufferedPlan.output, bufferedVars, neededBuildRefs)
    val rightCountAccum = if (rightCountOrdinal != -1) {
      s"$rightCountSum += ${bufferedVars(rightCountOrdinal).value};"
    } else {
      s"$rightCountSum += 1L;"
    }
    ctx.currentVars = flatBufVars ++ bufferedVars
    val bufferEvals = updateExprs.map(u =>
      bindReferences(u, bufferSchema ++ bufferedPlan.output).map(_.genCode(ctx)))
    val updateCode = bufferEvals.zipWithIndex.map { case (evalsForFn, i) =>
      val writes = evalsForFn.zip(bufVars(i)).map { case (ev, bv) =>
        s"${bv.isNull} = ${ev.isNull};\n${bv.value} = ${ev.value};"
      }
      s"${evaluateVariables(evalsForFn)}\n${writes.mkString("\n")}"
    }.mkString("\n")
    val matchBody = s"$buildEval\n$rightCountAccum\n$updateCode"

    // Post-loop: evaluate aggregate results from the buffer and emit.
    ctx.currentVars = flatBufVars
    val aggResultVars =
      bindReferences(aggFns.map(_.evaluateExpression), bufferSchema).map(_.genCode(ctx))
    val aggResultEval = evaluateVariables(aggResultVars)
    val countEv = ExprCode(EmptyBlock, FalseLiteral, JavaCode.variable(countOut, LongType))
    val resultVars = streamedVars ++ Seq(countEv) ++ aggResultVars

    val leftCountSetup = if (leftCountOrdinal != -1) {
      s"long $leftCount = ${streamedVars(leftCountOrdinal).value};"
    } else {
      s"long $leftCount = 1L;"
    }
    val iterator = ctx.freshName("iterator")

    val initJoin = ctx.addMutableState(CodeGenerator.JAVA_BOOLEAN, "initJoin")
    val addHookToRecordMetrics =
      s"""
         |$thisPlan.getTaskContext().addTaskCompletionListener(
         |  new org.apache.spark.util.TaskCompletionListener() {
         |    @Override
         |    public void onTaskCompletion(org.apache.spark.TaskContext context) {
         |      ${metricTerm(ctx, "spillSize")}.add($matches.spillSize());
         |    }
         |});
       """.stripMargin

    s"""
       |if (!$initJoin) {
       |  $initJoin = true;
       |  $addHookToRecordMetrics
       |}
       |while ($findNextJoinRows) {
       |  $streamedEval
       |  $leftCountSetup
       |  $bufferReset
       |  long $rightCountSum = 0L;
       |  scala.collection.Iterator<UnsafeRow> $iterator = $matches.generateIterator();
       |  while ($iterator.hasNext()) {
       |    InternalRow $bufferedRow = (InternalRow) $iterator.next();
       |    $checkCondition {
       |      $matchBody
       |    }
       |  }
       |  if ($rightCountSum != 0L) {
       |    long $countOut = $rightCountSum * $leftCount;
       |    $aggResultEval
       |    $numOutput.add(1);
       |    ${consume(ctx, resultVars)}
       |  }
       |  if (shouldStop()) return;
       |}
       |$eagerCleanup
     """.stripMargin
  }

  // Grouping inner count join. Mirrors HashCountJoin.codegenCountGroupedInner: per streamed (left)
  // row, group the passing matches by groupRight into a reused per-row map (group key -> aggregate
  // buffer, with the fan-out count folded into the buffer's trailing slot), then emit one row per
  // group (left cols ++ count-multiplied ++ aggregate results ++ group key). The per-match buffer
  // update is inlined (no GroupedCountAggregator.update virtual call). Match source is the merge
  // scanner's buffered `matches` array rather than a HashedRelation probe.
  private def produceCountGroupedInner(ctx: CodegenContext): String = {
    val streamedInput = ctx.addMutableState("scala.collection.Iterator", "streamedInput",
      v => s"$v = inputs[0];", forceInline = true)
    val bufferedInput = ctx.addMutableState("scala.collection.Iterator", "bufferedInput",
      v => s"$v = inputs[1];", forceInline = true)

    val (findNextJoinRowsFuncName, streamedRow, matches) = genScanner(ctx)
    val findNextJoinRows = s"$findNextJoinRowsFuncName($streamedInput, $bufferedInput)"
    val thisPlan = ctx.addReferenceObj("plan", this)
    val eagerCleanup = s"$thisPlan.cleanupResources();"
    val numOutput = metricTerm(ctx, "numOutputRows")

    val streamedVars = genOneSideJoinVars(ctx, streamedRow, streamedPlan, setDefaultValue = false)
    // Evaluate the streamed (left) columns once per streamed row BEFORE getJoinCondition copies
    // them for the residual condition. Otherwise the condition re-declares the same variables in
    // the inner match scope (Java forbids local-variable shadowing) and the generated code fails
    // to compile. After this, getJoinCondition copies already-blanked vars and references values.
    val streamedEval = evaluateVariables(streamedVars)
    val bufferedRow = ctx.freshName("bufferedRow")
    val (_, checkCondition, bufferedVars) =
      getJoinCondition(ctx, streamedVars, streamedPlan, bufferedPlan, Some(bufferedRow))

    val leftCountOrdinal = countLeft.filter(_.references.nonEmpty)
      .map(c => streamedOutput.indexWhere(_.exprId == c.references.head.exprId)).getOrElse(-1)
    val rightCountOrdinal = countRight.filter(_.references.nonEmpty)
      .map(c => bufferedOutput.indexWhere(_.exprId == c.references.head.exprId)).getOrElse(-1)
    val leftCount = ctx.freshName("leftCount")
    val leftCountSetup = if (leftCountOrdinal != -1) {
      s"long $leftCount = ${streamedVars(leftCountOrdinal).value};"
    } else {
      s"long $leftCount = 1L;"
    }

    // Per-task grouped aggregator + one reused per-stream-row group map (cleared per streamed row,
    // fully drained by the emit loop). The fan-out count lives in the buffer's trailing slot.
    val aggClass = classOf[GroupedCountAggregator].getName
    val aggTerm = ctx.addMutableState(aggClass, "groupedAgg",
      v => s"$v = $thisPlan.createGroupedAggregator();", forceInline = true)
    val rowCls = classOf[InternalRow].getName
    val mapCls = "java.util.LinkedHashMap"
    val bufMap = ctx.addMutableState(s"$mapCls<UnsafeRow, $rowCls>", "cjBufMap",
      v => s"$v = new $mapCls<UnsafeRow, $rowCls>();", forceInline = true)
    val rightCount = ctx.freshName("rightCount")
    val gkey = ctx.freshName("gkey")
    val buf = ctx.freshName("buf")
    val countOrd = aggregatesRight.map(_.aggregateFunction).flatMap(_.aggBufferAttributes).length
    val rightCountExpr =
      if (rightCountOrdinal != -1) s"$bufferedRow.getLong($rightCountOrdinal)" else "1L"

    // Inline the per-match buffer update: write into the per-group buffer ROW via updateColumn,
    // buffer slots read from `buf` (INPUT_ROW), build columns from the lazy bufferedVars.
    val aggFns = aggregatesRight.map(_.aggregateFunction.asInstanceOf[DeclarativeAggregate])
    val bufferSchema = aggFns.flatMap(_.aggBufferAttributes)
    val updateExprs = aggregatesRight.map { e =>
      e.mode match {
        case Partial | Complete =>
          e.aggregateFunction.asInstanceOf[DeclarativeAggregate].updateExpressions
        case _ =>
          e.aggregateFunction.asInstanceOf[DeclarativeAggregate].mergeExpressions
      }
    }
    val bufferStartOffsets = aggFns.map(_.aggBufferAttributes.length).scanLeft(0)(_ + _)
    val buildUpdateEval = evaluateRequiredVariables(
      bufferedPlan.output, bufferedVars, AttributeSet(updateExprs.flatten.flatMap(_.references)))
    ctx.INPUT_ROW = buf
    ctx.currentVars = (Array.fill[ExprCode](bufferSchema.length)(null) ++ bufferedVars).toSeq
    val bufferEvals = updateExprs.map(u =>
      bindReferences(u, bufferSchema ++ bufferedPlan.output).map(_.genCode(ctx)))
    ctx.INPUT_ROW = null
    val updateCode = bufferEvals.zipWithIndex.map { case (evals, i) =>
      val base = bufferStartOffsets(i)
      val writes = evals.zipWithIndex.map { case (ev, j) =>
        val attr = aggFns(i).aggBufferAttributes(j)
        CodeGenerator.updateColumn(buf, attr.dataType, base + j, ev, attr.nullable)
      }
      s"${evaluateVariables(evals)}\n${writes.mkString("\n")}"
    }.mkString("\n")

    val matchBody =
      s"""
         |long $rightCount = $rightCountExpr;
         |UnsafeRow $gkey = $aggTerm.groupKey($bufferedRow);
         |$rowCls $buf = ($rowCls) $bufMap.get($gkey);
         |if ($buf == null) {
         |  $buf = $aggTerm.newBuffer();
         |  $bufMap.put($gkey.copy(), $buf);
         |}
         |$buf.setLong($countOrd, $buf.getLong($countOrd) + $rightCount);
         |$buildUpdateEval
         |$updateCode
       """.stripMargin

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
    val cntEv = ExprCode(EmptyBlock, FalseLiteral, JavaCode.variable(cnt, LongType))
    val resultVars = streamedVars ++ Seq(cntEv) ++ aggReads.map(_._2) ++ groupReads.map(_._2)
    val iter = ctx.freshName("groupIter")
    val entry = ctx.freshName("groupEntry")
    val matchIterator = ctx.freshName("iterator")

    val initJoin = ctx.addMutableState(CodeGenerator.JAVA_BOOLEAN, "initJoin")
    val addHookToRecordMetrics =
      s"""
         |$thisPlan.getTaskContext().addTaskCompletionListener(
         |  new org.apache.spark.util.TaskCompletionListener() {
         |    @Override
         |    public void onTaskCompletion(org.apache.spark.TaskContext context) {
         |      ${metricTerm(ctx, "spillSize")}.add($matches.spillSize());
         |    }
         |});
       """.stripMargin

    s"""
       |if (!$initJoin) {
       |  $initJoin = true;
       |  $addHookToRecordMetrics
       |}
       |while ($findNextJoinRows) {
       |  $streamedEval
       |  $leftCountSetup
       |  $bufMap.clear();
       |  scala.collection.Iterator<UnsafeRow> $matchIterator = $matches.generateIterator();
       |  while ($matchIterator.hasNext()) {
       |    InternalRow $bufferedRow = (InternalRow) $matchIterator.next();
       |    $checkCondition {
       |      $matchBody
       |    }
       |  }
       |  java.util.Iterator $iter = $bufMap.entrySet().iterator();
       |  while ($iter.hasNext()) {
       |    java.util.Map.Entry $entry = (java.util.Map.Entry) $iter.next();
       |    UnsafeRow $gkeyOut = (UnsafeRow) $entry.getKey();
       |    $rowCls $bufOut = ($rowCls) $entry.getValue();
       |    long $cnt = $bufOut.getLong($countOrd) * $leftCount;
       |    $rowCls $aggResRow = $aggTerm.eval($bufOut);
       |    ${aggReads.map(_._1).mkString("\n")}
       |    ${groupReads.map(_._1).mkString("\n")}
       |    $numOutput.add(1);
       |    ${consume(ctx, resultVars)}
       |  }
       |  if (shouldStop()) return;
       |}
       |$eagerCleanup
     """.stripMargin
  }

  override protected def withNewChildrenInternal(
      newLeft: SparkPlan, newRight: SparkPlan): SortMergeCountJoinExec =
    copy(left = newLeft, right = newRight)
}
