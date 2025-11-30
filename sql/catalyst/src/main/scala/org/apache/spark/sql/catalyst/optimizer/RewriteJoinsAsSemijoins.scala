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

package org.apache.spark.sql.catalyst.optimizer

import scala.collection.mutable

import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.dsl.expressions.DslExpression
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.aggregate._
import org.apache.spark.sql.catalyst.plans.{Inner, InnerLike, LeftSemi}
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.catalyst.trees.TreePattern
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types._
import org.apache.spark.sql.types.DecimalType.DoubleDecimal

object RewriteJoinsAsSemijoins extends Rule[LogicalPlan] with PredicateHelper {
  // Set to true to enable debug logging for the GroupAggJoin optimization
  val DEBUG_LOGGING = true

  private def debugLog(msg: => String): Unit = {
    if (DEBUG_LOGGING) logWarning(msg)
  }

  /** As in [[PhysicalAggregation]] the aggregate  expressions are extracted from the
   * outputExpressions ([[NamedExpression]]. Then these are replaced in the output expressions
   * by new references.
   * */
  def rewritePlan(agg: Aggregate, unnamedGroupingExpressions: Seq[Expression],
                  resultExpressions: Seq[NamedExpression], projectList: Seq[NamedExpression],
                  join: Join, keyRefs: Seq[Seq[Expression]],
                  uniqueConstraints: Seq[Seq[Expression]]) : LogicalPlan = {
    val startTime = System.nanoTime()
    debugLog("applying rewriting to join: " + agg)
    // Extract the join items (including any filters, etc.)
    val (items, conditions) = extractInnerJoins(join)

    val equivalentAggregateExpressions = new EquivalentExpressions
    // Extract the AggregateExpressions from the result expressions
    // e.g., SUM(x) from SUM(x) + 1
    val aggregateExpressions = resultExpressions.flatMap { expr =>
      expr.collect {
        // addExpr() always returns false for non-deterministic expressions and do not add them.
        case a: AggregateExpression if !equivalentAggregateExpressions.addExpr(a) =>
          a
      }
    }

    // Maps the resultAttribute of the first AggregateExpression to the resulAttribute of
    // the last one. This is needed for constructing the final rewritten result exprs
    val lastAggMap = new mutable.HashMap[Attribute, AggregateExpression]()
    // We store the last sum, when a sum aggregate is propagated upwards
    val lastSumMap = new mutable.HashMap[Attribute, Attribute]()
    // Pull the multiplication of the sum by the count into the next sum aggregate
    val nextMultiplicationMap = new mutable.HashMap[Attribute, Expression]()
    // Track which lastSumMap entries are raw product expressions (not yet aggregated)
    // These need to be SUMmed at the next join, unlike already-aggregated values
    val pendingProductSumSet = new mutable.HashSet[Attribute]()
    // Track the grouping attributes each pending product was created with.
    // When propagating, we need to ensure count aggregations respect this grouping.
    val pendingProductGrouping = new mutable.HashMap[Attribute, Seq[NamedExpression]]()

    val namedGroupingExpressions = unnamedGroupingExpressions.map {
      case ne: NamedExpression => ne -> ne
      // If the expression is not a NamedExpressions, we add an alias.
      // So, when we generate the result of the operator, the Aggregate Operator
      // can directly get the Seq of attributes representing the grouping expressions.
      case other =>
        val withAlias = Alias(other, other.toString)()
        other -> withAlias
    }
    val groupingExpressions = namedGroupingExpressions.map(_._2)
    val groupExpressionMap = namedGroupingExpressions.toMap

    val aliasProjections = projectList.filter {
      case Alias(child, name) => true
      case _ => false
    }

    val groupAliasProjections = aliasProjections.filter {
      expr => groupingExpressions.exists(ge => ge.references.contains(expr.toAttribute))
    }
    val groupAliasAttributes = groupAliasProjections.flatMap(expr => expr.references)
    val groupAliasMap =
      groupAliasProjections.map(a => a.asInstanceOf[Alias])
        .map(a => (a.toAttribute.exprId, a.child))
        .toMap

    val aggAliasProjections = aliasProjections.filter {
      expr => aggregateExpressions.exists(ae => ae.references.contains(expr.toAttribute))
    }
    val aggAliasMap
    = aggAliasProjections.map(a => a.asInstanceOf[Alias])
      .map(a => (a.toAttribute.exprId, a.child))
      .toMap

    val aggregateExpressionsWithAliasesReplaced = aggregateExpressions
      .map(ae => ae.transformDown {
        case expr: Attribute =>
          if (aggAliasMap.get(expr.exprId).nonEmpty) {
            aggAliasMap(expr.exprId)
         }
          else {
            expr
          }
      }.asInstanceOf[AggregateExpression])

    val resultExpressionsWithAliasesReplaced = resultExpressions
      .map(ne => ne.transformDown {
        case expr: Attribute =>
          if (aggAliasMap.contains(expr.exprId)) {
            aggAliasMap(expr.exprId)
          }
          else {
            expr
          }
      }.asInstanceOf[NamedExpression])

    val aggregateAttributes = resultExpressionsWithAliasesReplaced.map(expr => expr.references)
      .reduce((a1, a2) => a1 ++ a2)
    val groupAttributes = if (groupingExpressions.isEmpty) {
      AttributeSet.empty
    }
    else {
      // Get the directly referenced attributes and the attributes indirectly referenced
      // by the projection before the aggregation
      groupingExpressions
        .map(g => g.references)
        .map(atts => {
          AttributeSet(atts.toSeq.map(att => {
            if (groupAliasMap.contains(att.exprId)) {
              groupAliasMap(att.exprId)
            }
            else {
              att
            }
          }))
        })
        .reduce((g1, g2) => g1 ++ g2)
    }

    debugLog("groupAttributes: " + groupAttributes)
    debugLog("aggregateAttributes: " + aggregateAttributes)
    debugLog("groupAliasProjections: " + groupAliasProjections)
    debugLog("groupAliasAttributes: " + groupAliasAttributes)
    debugLog("aggAliasProjections: " + aggAliasProjections)
    debugLog("alias map: " + aggAliasMap)
    debugLog("aggregate exprs with aliases replaced: " + aggregateExpressionsWithAliasesReplaced)
    debugLog("result exprs with aliases replaced: " + resultExpressionsWithAliasesReplaced)

    // 0MA queries can be evaluated purely by bottom-up semi joins
    // Currently, they are limited to Min and Max queries
    // For all aggregates (0MA or counting-based), check if there are no references to attributes
    // (e.g., COUNT(1)) or the references are not part of the grouping attributes
    // TODO remove duplicated code. Use enum for representing query types?
    val zeroMAAggregates = resultExpressions
      .filter(agg => agg.references.isEmpty || !(agg.references subsetOf groupAttributes))
      .filter(agg => is0MA(agg))
    val percentileAggregates = resultExpressions
      .filter(agg => agg.references.isEmpty || !(agg.references subsetOf groupAttributes))
      .filter(agg => isPercentile(agg))
    val countingAggregates = resultExpressions
      .filter(agg => agg.references.isEmpty || !(agg.references subsetOf groupAttributes))
      .filter(agg => isCounting(agg))
    val sumAggregates = resultExpressions
      .filter(agg => agg.references.isEmpty || !(agg.references subsetOf groupAttributes))
      .filter(agg => isSum(agg))
    val averageAggregates = resultExpressions
      .filter(agg => agg.references.isEmpty || !(agg.references subsetOf groupAttributes))
      .filter(agg => isAverage(agg))

    if (zeroMAAggregates.isEmpty
      && percentileAggregates.isEmpty
      && countingAggregates.isEmpty
      && sumAggregates.isEmpty
      && averageAggregates.isEmpty) {
      debugLog("query is not applicable (0MA, counting, percentile, sum)")
      agg
    }
    else {
      val hg = new Hypergraph(items, conditions)
      val jointree = hg.flatGYO

      if (jointree == null) {
        debugLog("join is cyclic")
        debugLog("time difference: " + (System.nanoTime() - startTime))
        agg
      }
      else {
        debugLog("join tree: \n" + jointree)
        // First check if there is a single tree node, i.e., relation that contains all attributes
        // contained in the GROUP BY clause and agg expressions
        val nodeContainingAllAttributes = jointree
          .findNodeContainingAttributes(aggregateAttributes ++ groupAttributes)
        if (nodeContainingAllAttributes == null) {
          // The query is not guarded according to the original definition
          // (one node contains all attributes in the query)
          debugLog("not guarded! there is no node containing all agg and group attributes")
          debugLog("time difference: " + (System.nanoTime() - startTime))

          val nodeContainingGroupAttributes = jointree.findNodeContainingAttributes(groupAttributes)
          var root = jointree

          val unguardedAggAttributes = aggregateExpressionsWithAliasesReplaced.map(expr => {
            val containingNode = jointree.findNodeContainingAttributes(expr.references)
            if (containingNode == null) {
              expr.references
            }
            else {
              AttributeSet.empty
            }
          }).reduce((a1, a2) => a1 ++ a2)

          debugLog("unguardedAggAttributes: " + unguardedAggAttributes)

          var piecewiseGuarded = false
          if (nodeContainingGroupAttributes != null && unguardedAggAttributes.isEmpty) {
            piecewiseGuarded = true
            debugLog("piecewise-guarded!")
            root = nodeContainingGroupAttributes.reroot
            // Choose the root containing the group attributes, if one exists
            // If none contains all of them, choose any join tree
          }
          else {
            if (!conf.yannakakisUnguardedEnabled) {
              debugLog("unguarded. plan is not changed")
              return agg
            }
            // Check if there are cross-relation filters AND cross-relation product aggregates.
            // This combination is problematic because the counts computed at early joins
            // don't account for the cross-relation filter. Fall back to non-optimized execution.
            if (hg.crossRelationFilters.nonEmpty && unguardedAggAttributes.nonEmpty) {
              debugLog("unguarded with cross-relation filters. plan is not changed")
              return agg
            }
          }
          debugLog("applicable query (joins=" + (items.size - 1) + ")")

          val (yannakakisJoins, countingAttribute, _, _) =
            root.buildBottomUpJoinsCounting(aggregateAttributes,
              groupingExpressions ++ groupAliasAttributes,
              aggregateExpressionsWithAliasesReplaced,
              lastAggMap, lastSumMap, nextMultiplicationMap, pendingProductSumSet,
              pendingProductGrouping, keyRefs, uniqueConstraints,
              conf.yannakakisCountGroupInLeavesEnabled,
              usePhysicalCountJoin = conf.yannakakisPhysicalCountEnabled,
              crossRelationFilters = mutable.Set(hg.crossRelationFilters: _*))

          debugLog("lastAggMap: " + lastAggMap)
          debugLog("lastSumMap: " + lastSumMap)
          debugLog("resultExpressionsWithAliasesReplaced: " +
            resultExpressionsWithAliasesReplaced)

          // Adapt the result expressions to make use of the frequency attribute
          val rewrittenResultExpressions = resultExpressionsWithAliasesReplaced.map {
            expr =>
              expr.transformDown {
                case ae: AggregateExpression =>
                  debugLog("aggregate expression: " + ae)
                  val resultAtt = equivalentAggregateExpressions.getExprState(ae).map(_.expr)
                    .getOrElse(ae).asInstanceOf[AggregateExpression].resultAttribute
                  debugLog("resultAtt: " + resultAtt)
                  ae.aggregateFunction match {
                    case a: Count =>
                      // TODO temp change
//                      if (lastSumMap.contains(resultAtt)) {
//                        val lastSumAtt = lastSumMap(resultAtt)
//                        Sum(lastSumAtt).toAggregateExpression()
//                      }
//                      else {
                        Sum(Multiply(
                          a.children.head, Cast(countingAttribute, a.children.head.dataType)))
                          .toAggregateExpression()
//                      }
                    case _ =>
                      // The final aggregation buffer's attributes will be
                      // `finalAggregationAttributes`,
                      // so replace each aggregate expression by its corresponding
                      // attribute in the set:
                      ae.transformDown {
                        case a: AggregateFunction =>
                          a match {
                            // TODO this could be simplified by merging Sum and Count cases
                            case Sum(_, _) =>
                              if (lastSumMap.contains(resultAtt)) {
                                val lastSumAtt = lastSumMap(resultAtt)
                                //       val lastMultiplyExpr = nextMultiplicationMap(resultAtt)
                                a.withNewChildren(Seq(lastSumAtt))
                              }
                              else {
                                a.withNewChildren(
                                  Seq(Multiply(a.children.head,
                                    Cast(countingAttribute, a.children.head.dataType))))
                              }
                            case _ =>
                              // MIN, MAX
                              if (lastAggMap.contains(resultAtt)) {
                                val lastResultAtt = lastAggMap(resultAtt).resultAttribute

                                a.withNewChildren(
                                  Seq(lastResultAtt))

                              }
                              else {
                                // If the att is not in the lastAggMap, it means that the attribute
                                // occurred in the root of the tree - and only gets propagated
                                // up on the left.
                                //  Therefore, there is no intermediate aggregation function
                                //  in-between and we can directly access the attribute.
                                a
                              }
                          }
                      }
                  }

              case expression if !expression.foldable =>
                // Since we're using `namedGroupingAttributes` to extract the grouping key
                // columns, we need to replace grouping key expressions with their corresponding
                // attributes. We do not rely on the equality check at here since attributes may
                // differ cosmetically. Instead, we use semanticEquals.
                groupExpressionMap.collectFirst {
                  case (expr, ne) if expr semanticEquals expression => ne.toAttribute
                }.getOrElse(expression)
            }.asInstanceOf[NamedExpression]
          }
          debugLog("rewrittenResultExpressions: " + rewrittenResultExpressions)

          val newAgg = Aggregate(groupingExpressions,
            rewrittenResultExpressions,
            Project(yannakakisJoins.output ++ groupAliasProjections, yannakakisJoins))
          val queryClass = if (piecewiseGuarded) "piecewise-guarded" else "unguarded"
          logWarning(f"new aggregate ($queryClass): " + newAgg)
          debugLog("time difference: " + (System.nanoTime() - startTime))
          newAgg
        }
        else {
          // The query is guarded
          val root = nodeContainingAllAttributes.reroot
          debugLog("applicable query (joins=" + (items.size - 1) + ")")

          if (countingAggregates.isEmpty
            && percentileAggregates.isEmpty
            && sumAggregates.isEmpty
            && averageAggregates.isEmpty) {
            // If the query is a 0MA query, only perform bottom-up semijoins
            val yannakakisJoins = root.buildBottomUpJoins

            val newAgg = Aggregate(groupingExpressions, resultExpressions,
              yannakakisJoins)
            logWarning("new aggregate (0MA): " + newAgg)
            debugLog("time difference: " + (System.nanoTime() - startTime))
            newAgg
          }
          else {
            // Guarded but not 0MA
            val (yannakakisJoins, countingAttribute, _, _) =
              root.buildBottomUpJoinsCounting(aggregateAttributes,
                groupingExpressions,
                aggregateExpressions, lastAggMap, lastSumMap, nextMultiplicationMap,
                pendingProductSumSet, pendingProductGrouping, keyRefs, uniqueConstraints,
                conf.yannakakisCountGroupInLeavesEnabled,
                usePhysicalCountJoin = conf.yannakakisPhysicalCountEnabled,
                crossRelationFilters = mutable.Set(hg.crossRelationFilters: _*))

            val rewrittenResultExpressions = resultExpressions.map {
              expr =>
                expr.transformDown {
                  case aggExpr @ AggregateExpression(aggFn, mode, isDistinct, filter, resultId) =>
                    aggFn match {
                      case a: Count =>
                        AggregateExpression(
                        Sum(countingAttribute), mode, isDistinct, filter, resultId)

                      case Percentile(c, percExp, freqExp, mutableAggBufferOffset,
                      inputAggBufferOffset, reverse) =>
                        val freqExpr = countingAttribute
                        AggregateExpression(
                          Percentile(c, percExp, freqExpr, mutableAggBufferOffset,
                            inputAggBufferOffset, reverse), mode, isDistinct, filter, resultId)

                      case Average(_, _) =>
                        val aggAttribute = aggFn.references.head
                        val sumAggregateExpr = aggFn.transformUp {
                          case a@Average(c, evalMode) =>
                            Sum(c.transformUp {
                              case att: Attribute => Multiply(att,
                                Cast(countingAttribute, att.dataType), evalMode)
                            }, evalMode)
                        }.asInstanceOf[AggregateFunction].toAggregateExpression()

                        val countAggregateExpr = Sum(
                          If(aggAttribute.isNull,
                            Literal(0L, LongType), countingAttribute))
                          .toAggregateExpression()
                        Cast(
                          if (DoubleType.acceptsType(sumAggregateExpr.dataType) &&
                            DoubleType.acceptsType(countAggregateExpr.dataType)) {
                            Divide(sumAggregateExpr, countAggregateExpr)
                          } else {
                            // TODO check if there is a better way than casting to DoubleDecimal?
                            Divide(Cast(sumAggregateExpr, DoubleDecimal),
                              Cast(countAggregateExpr, DoubleDecimal))
                          }, aggExpr.dataType)

                      case Sum(_, _) =>
                        AggregateExpression(aggFn.transformUp {
                          case s @ Sum(c, evalMode) =>
                            Sum(c.transformUp {
                              case att: Attribute => Multiply(att,
                                Cast(countingAttribute, att.dataType), evalMode)
                            }, evalMode)
                        }.asInstanceOf[AggregateFunction], mode, isDistinct, filter, resultId)
                    }
                }.asInstanceOf[NamedExpression]
            }

            val newAgg = Aggregate(groupingExpressions,
              rewrittenResultExpressions, yannakakisJoins)

            logWarning("new aggregate (guarded): " + newAgg)
            debugLog("time difference: " + (System.nanoTime() - startTime))
            newAgg
          }
        }
      }
    }
  }
  override def apply(plan: LogicalPlan): LogicalPlan = {
    if (!conf.yannakakisEnabled) {
      plan
    }
    else {
      plan.transformDownWithPruning(_.containsPattern(TreePattern.AGGREGATE), ruleId) {
        case agg@Aggregate(groupingExpressions, aggExpressions,
        join@Join(_, _, Inner, _, _)) => agg
        case agg@Aggregate(groupingExpressions, aggExpressions,
        filter@Filter(filterConds,
        join@Join(_, _, Inner, _, _))) => agg
        case agg@Aggregate(groupingExpressions, aggExpressions,
        project@Project(projectList,
        join@Join(_, _, Inner, _, _))) =>
          rewritePlan(agg, groupingExpressions, aggExpressions, projectList,
            join, keyRefs = Seq(), uniqueConstraints = Seq())
          // FK/PK optimizations (to be removed at some point)
        case agg@Aggregate(groupingExpressions, aggExpressions,
        project@Project(projectList,
        FKHint(join@Join(_, _, Inner, _, _), keyRefs, uniqueConstraints))) =>
          rewritePlan(agg, groupingExpressions, aggExpressions, projectList,
            join, keyRefs, uniqueConstraints)
        case agg@Aggregate(groupingExpressions, aggExpressions,
        project@Project(projectList,
        FKHint(
        project2@Project(projectList2,
        join@Join(_, _, Inner, _, _)), keyRefs, uniqueConstraints))) =>
          rewritePlan(agg, groupingExpressions, aggExpressions, projectList,
            join, keyRefs, uniqueConstraints)
        case agg@Aggregate(_, _, _) =>
          debugLog("not applicable to aggregate: " + agg)
          agg
      }
    }
  }
  def is0MA(expr: Expression): Boolean = {
    expr match {
      case Alias(child, name) => is0MA(child)
      case ToPrettyString(child, tz) => is0MA(child)
      case AggregateExpression(aggFn, mode, isDistinct, filter, resultId) => aggFn match {
        case Min(c) => true
        case Max(c) => true
        case _ => false
      }
      case _: Attribute => true
      case _ => false
    }
  }
  def isCounting(expr: Expression): Boolean = {
    expr match {
      case Alias(child, name) => isCounting(child)
      case ToPrettyString(child, tz) => isCounting(child)
      case AggregateExpression(aggFn, mode, isDistinct, filter, resultId) => aggFn match {
        case Count(s) => true
        case _ => false
      }
      case _ => false
    }
  }

  def isPercentile(expr: Expression): Boolean = {
    expr match {
      case Alias(child, name) => isPercentile(child)
      case ToPrettyString(child, tz) => isPercentile(child)
      case Multiply(l, r, _) => isPercentile(l) || isPercentile(r)
      case Divide(l, r, _) => isPercentile(l) || isPercentile(r)
      case Add(l, r, _) => isPercentile(l) || isPercentile(r)
      case Subtract(l, r, _) => isPercentile(l) || isPercentile(r)
      case AggregateExpression(aggFn, mode, isDistinct, filter, resultId) => aggFn match {
        case Percentile(_, _, _, _, _, _) => true
        case _ => false
      }
      case _ => false
    }
  }

  def isSum(expr: Expression): Boolean = {
    expr match {
      case Alias(child, name) => isSum(child)
      case ToPrettyString(child, tz) => isSum(child)
      case Multiply(l, r, _) => isSum(l) || isSum(r)
      case Divide(l, r, _) => isSum(l) || isSum(r)
      case Add(l, r, _ ) => isSum(l) || isSum(r)
      case Subtract(l, r, _) => isSum(l) || isSum(r)
      case AggregateExpression(aggFn, mode, isDistinct, filter, resultId) => aggFn match {
        case Sum(_, _) => true
        case _ => false
      }
      case _ => false
    }
  }

  def isAverage(expr: Expression): Boolean = {
    expr match {
      case Alias(child, name) => isAverage(child)
      case ToPrettyString(child, tz) => isAverage(child)
      case Multiply(l, r, _) => isAverage(l) || isAverage(r)
      case Divide(l, r, _) => isAverage(l) || isAverage(r)
      case Add(l, r, _) => isAverage(l) || isAverage(r)
      case Subtract(l, r, _) => isAverage(l) || isAverage(r)
      case AggregateExpression(aggFn, mode, isDistinct, filter, resultId) => aggFn match {
        case Average(_, _) => true
        case _ => false
      }
      case _ => false
    }
  }

  def isNonAgg(expr: Expression): Boolean = {
    expr match {
      case Alias(child, name) => isNonAgg(child)
      case ToPrettyString(child, tz) => isNonAgg(child)
      case Multiply(l, r, _) => isNonAgg(l) && isNonAgg(r)
      case Divide(l, r, _) => isNonAgg(l) && isNonAgg(r)
      case Add(l, r, _) => isNonAgg(l) && isNonAgg(r)
      case Subtract(l, r, _) => isNonAgg(l) && isNonAgg(r)
      case _: Attribute => true
      case _: Literal => true
      case _ => false
    }
  }

  /**
   * Extracts items of consecutive inner joins and join conditions.
   * This method works for bushy trees and left/right deep trees.
   */
  private def extractInnerJoins(plan: LogicalPlan): (Seq[LogicalPlan], ExpressionSet) = {
    plan match {
      // replace innerlike by more general join type?
      case Join(left, right, _: InnerLike, Some(cond), _) =>
        val (leftPlans, leftConditions) = extractInnerJoins(left)
        val (rightPlans, rightConditions) = extractInnerJoins(right)
        (leftPlans ++ rightPlans, leftConditions ++ rightConditions ++
          splitConjunctivePredicates(cond))
      case Project(projectList, j@Join(_, _, _: InnerLike, Some(cond), _))
        if projectList.forall(_.isInstanceOf[Attribute]) =>
        extractInnerJoins(j)
      case _ =>
        (Seq(plan), ExpressionSet())
    }
  }
}

class HGEdge(val vertices: Set[String], val name: String, val planReference: LogicalPlan,
             val attributeToVertex: mutable.Map[ExprId, String]) {
  val vertexToAttribute: Map[String, Attribute] = planReference.outputSet.map(
      att => (attributeToVertex.getOrElse(att.exprId, null), att))
    .toMap
  def attributes: AttributeSet = {
    planReference.outputSet
  }
  def contains(other: HGEdge): Boolean = {
    other.vertices subsetOf vertices
  }
  def containsNotEqual (other: HGEdge): Boolean = {
    contains(other) && !(vertices subsetOf other.vertices)
  }
  def outputSet: AttributeSet = planReference.outputSet
  def copy(newVertices: Set[String] = vertices,
           newName: String = name,
           newPlanReference: LogicalPlan = planReference): HGEdge =
    new HGEdge(newVertices, newName, newPlanReference, attributeToVertex)
  override def toString: String = s"""${name}(${vertices.mkString(", ")})"""
}
class HTNode(val edges: Set[HGEdge], var children: Set[HTNode], var parent: HTNode)
  extends Logging {
  // Helper for debug logging
  private def dbg(msg: => String): Unit = {
    if (RewriteJoinsAsSemijoins.DEBUG_LOGGING) logWarning(msg)
  }

  def buildBottomUpJoins: LogicalPlan = {
    val edge = edges.head
    val scanPlan = edge.planReference
    val vertices = edge.vertices
    var prevJoin: LogicalPlan = scanPlan
    for (c <- children) {
      val childEdge = c.edges.head
      val childVertices = childEdge.vertices
      val overlappingVertices = vertices intersect childVertices
      val joinConditions = overlappingVertices
        .map(vertex => (edge.vertexToAttribute(vertex), childEdge.vertexToAttribute(vertex)))
        .map(atts => EqualTo(atts._1, Cast(atts._2, atts._1.dataType)).asInstanceOf[Expression])
        .reduceLeft((e1, e2) => And(e1, e2).asInstanceOf[Expression])
      val semijoin = Join(prevJoin, c.buildBottomUpJoins,
        LeftSemi, Option(joinConditions), JoinHint(Option.empty, Option.empty))
      prevJoin = semijoin
    }
    prevJoin
  }

  // scalastyle:off argcount
  def buildBottomUpJoinsCounting(aggregateAttributes: AttributeSet,
                                 groupingExpressions: Seq[NamedExpression],
                                 aggExpressions: Seq[AggregateExpression],
                                 lastAggMap: mutable.HashMap[Attribute, AggregateExpression],
                                 lastSumMap: mutable.HashMap[Attribute, Attribute],
                                 nextMultiplicationMap: mutable.HashMap[Attribute, Expression],
                                 pendingProductSumSet: mutable.HashSet[Attribute],
                                 pendingProductGrouping: mutable.HashMap[Attribute,
                                   Seq[NamedExpression]],
                                 keyRefs: Seq[Seq[Expression]],
                                 uniqueConstraints: Seq[Seq[Expression]], groupInLeaves: Boolean,
                                 usePhysicalCountJoin: Boolean = false,
                                 crossRelationFilters: mutable.Set[Expression] =
                                   mutable.Set.empty):
  (LogicalPlan, NamedExpression, Boolean, Boolean) = {
    // scalastyle:on argcount

    val edge = edges.head
    val scanPlan = edge.planReference
    val vertices = edge.vertices
    val primaryKeys = AttributeSet(keyRefs.map(ref => ref.last.references.head))
    val uniqueSets = uniqueConstraints.map(constraint => AttributeSet(constraint))
    // Get the attributes as part of the join tree
    val nodeAttributes = AttributeSet(vertices.map(v => edge.vertexToAttribute(v)))

    // Check if grouping in leaves is enabled, and no primary keys are part of the leaf
    // Also avoid grouping when the leaves contain output atts, since they are most likely
    // randomly distributed and would lead to a high selectivity
    val groupHere = groupInLeaves &&
      ! nodeAttributes.exists(att => primaryKeys contains att) &&
      ! scanPlan.output.exists(att => aggregateAttributes contains att) &&
      ! uniqueSets.exists(uniqueSet => uniqueSet subsetOf scanPlan.outputSet)
//    logWarning("group here: " + groupHere)

    var prevCountExpr: NamedExpression = if (groupHere) {
      Alias(Count(Literal(1L, LongType)).toAggregateExpression(), "c")()
    }
    else {
      Alias(Literal(1L, LongType), "c")()
    }

    // Only group counts in leaves if it is explicitly enabled and there are no known
    // primary keys in the leaf
    val outputAttributes = scanPlan.output.filter(att => (nodeAttributes contains att)
      || (aggregateAttributes contains att) || (groupingExpressions contains att))
    var prevPlan: LogicalPlan = if (groupHere) {
      Aggregate(outputAttributes, Seq(prevCountExpr) ++
        outputAttributes, scanPlan)
    }
    else {
      // Make sure to project the output only to the attributes as part of the join tree
      // This can occur when we have a FKHint before the join nodes which leads to
      // Spark SQL not projecting away the attributes mentioned in the hints

//      Project(
//        outputAttributes ++ Seq(prevCountExpr), scanPlan)

      scanPlan
    }
    var isLeafNode = true
    var prevSemijoined = false

    var prevChildEdge: HGEdge = edge
    for (c <- children) {
      val childEdge = c.edges.head
      val childVertices = childEdge.vertices
      val overlappingVertices = vertices intersect childVertices
      val (bottomUpJoins, childCountExpr, rightPlanIsLeaf, childWasSemijoined) =
        c.buildBottomUpJoinsCounting(aggregateAttributes,
          groupingExpressions, aggExpressions, lastAggMap, lastSumMap,
          nextMultiplicationMap, pendingProductSumSet, pendingProductGrouping,
          keyRefs, uniqueConstraints,
          groupInLeaves, usePhysicalCountJoin = usePhysicalCountJoin,
          crossRelationFilters = crossRelationFilters)

      val countExpressionLeft = Alias(Sum(prevCountExpr.toAttribute).toAggregateExpression(), "c")()
      val countExpressionRight = Alias(
        Sum(childCountExpr.toAttribute).toAggregateExpression(), "c")()

      val countGroupLeft = vertices.map(v => edge.vertexToAttribute(v)).toSeq
      val countGroupRight = overlappingVertices.map(v => childEdge.vertexToAttribute(v)).toSeq

      // Grouping directly after each leaf node results in bad performance.
      // Possible solution: make use of primary keys to determine if grouping is necessary
      // Construct the left subplan
      val (leftPlan, leftCountAttribute) = if (isLeafNode) {
        (prevPlan, prevCountExpr.toAttribute)
      }
      else {
        dbg("prevPlan: " + prevPlan)
        dbg("output: " + prevPlan.output)
        val outputAggregateAttributes = prevPlan.outputSet intersect aggregateAttributes
        val groupAttributes = countGroupLeft ++ outputAggregateAttributes
        val prevChildAttributes = AttributeSet(
          prevChildEdge.vertices.map(v => prevChildEdge.vertexToAttribute(v)))
        // Check if the grouping attributes contain a primary key.
        // In this case, grouping would not remove any tuples, hence do not aggregate.

        // Don't perform aggregation afterwards if a countjoin was performed
        if (usePhysicalCountJoin
          || (prevSemijoined && primaryKeys.exists(att => nodeAttributes contains att))
          || uniqueSets.exists(uniqueSet => AttributeSet(groupAttributes) subsetOf uniqueSet )) {
          (prevPlan, prevCountExpr.toAttribute)
        }
        else {
          (Aggregate(groupAttributes,
            Seq(countExpressionLeft) ++ groupAttributes, prevPlan),
            countExpressionLeft.toAttribute)
        }
      }

      // Construct the right subplan
      val (rightPlan, rightCountAttribute) = if (rightPlanIsLeaf) {
        (bottomUpJoins, childCountExpr.toAttribute)
      }
      else {
        if (usePhysicalCountJoin
          || countGroupRight.forall(att => primaryKeys contains att)
          || uniqueSets.exists(uniqueSet => AttributeSet(countGroupRight) subsetOf uniqueSet )) {
          (bottomUpJoins, childCountExpr.toAttribute)
        }
        else {
          (Aggregate(countGroupRight,
            Seq(countExpressionRight) ++ countGroupRight, bottomUpJoins),
            countExpressionRight.toAttribute)
        }
      }

      val equalityConditions = overlappingVertices
        .map(vertex => (edge.vertexToAttribute(vertex), childEdge.vertexToAttribute(vertex)))
        .map(atts => EqualTo(atts._1, Cast(atts._2, atts._1.dataType)).asInstanceOf[Expression])
        .reduceLeft((e1, e2) => And(e1, e2).asInstanceOf[Expression])

      // Check for cross-relation filters that can be applied at this join
      // A filter is applicable when all its referenced attributes are available
      val joinOutputSet = leftPlan.outputSet ++ rightPlan.outputSet
      val applicableFilters = crossRelationFilters.filter(f =>
        f.references.subsetOf(joinOutputSet))
      // Remove applied filters so they're not applied again at higher levels
      crossRelationFilters --= applicableFilters

      val joinConditions = if (applicableFilters.nonEmpty) {
        dbg(s"Applying cross-relation filters at this join: $applicableFilters")
        applicableFilters.foldLeft(equalityConditions)((cond, filter) => And(cond, filter))
      } else {
        equalityConditions
      }

      // Currently unused (can be used to e.g., force hash/merge joins)
      val joinHint = JoinHint(Option.empty, Option.empty)

      val newRightCount = Alias(Literal(1L, LongType), "c")()

      var applicableGroupAttributes = groupingExpressions.filter(
        groupExpr => {groupExpr.references.subsetOf(rightPlan.outputSet)}
      )

      // Capture the grouping state BEFORE adding attributes for new products.
      // This is used for pending product propagation - pending products should use
      // count aggregations based on grouping that existed BEFORE this join adds
      // new grouping for products being created here.
      val groupingBeforeNewProducts = applicableGroupAttributes.toSeq

      // For product aggregates (SUM(A*B) where A and B are from different relations),
      // we need to carry attributes through the tree until both are available.
      // Add right-side product attributes to grouping if the product can't be computed yet.
      val combinedOutputSet = leftPlan.outputSet ++ rightPlan.outputSet
      aggExpressions.foreach(agg => {
        agg.aggregateFunction match {
          case Sum(_, _) =>
            val aggRefs = agg.references
            // Check if this is a product aggregate spanning multiple relations
            val refsOnLeft = aggRefs.filter(a => leftPlan.outputSet.contains(a))
            val refsOnRight = aggRefs.filter(a => rightPlan.outputSet.contains(a))
            val isProductAggHere = refsOnLeft.nonEmpty && refsOnRight.nonEmpty &&
              aggRefs.subsetOf(combinedOutputSet)
            val refsNotYetAvailable = aggRefs.filter(a => !combinedOutputSet.contains(a))

            if (!isProductAggHere && refsNotYetAvailable.nonEmpty) {
              // Not all refs available yet - add right-side refs to grouping
              // to carry them through for a product computed later
              refsOnRight.foreach(att => {
                val alreadyGrouped = applicableGroupAttributes.exists(
                  g => g.references.contains(att))
                if (!alreadyGrouped) {
                  val namedAtt = att.asInstanceOf[NamedExpression]
                  applicableGroupAttributes = applicableGroupAttributes :+ namedAtt
                  dbg(s"Added $att to grouping for product agg (carry through)")
                }
              })
            } else if (isProductAggHere && !aggRefs.exists(a =>
                groupingExpressions.exists(g => g.references.contains(a)))) {
              // Product can be computed here, but refs are NOT in global GROUP BY
              // We need to add right-side refs to grouping so the product expression
              // can reference them in the CountJoin aggregate
              refsOnRight.foreach(att => {
                val alreadyGrouped = applicableGroupAttributes.exists(
                  g => g.references.contains(att))
                if (!alreadyGrouped) {
                  val namedAtt = att.asInstanceOf[NamedExpression]
                  applicableGroupAttributes = applicableGroupAttributes :+ namedAtt
                  dbg(s"Added $att to grouping for product agg (for aggregate)")
                }
              })
            }
          case _ =>
        }
      })

      // For cross-relation filters, we need to carry the referenced attributes through
      // the tree until all refs are available and the filter can be applied.
      // Similar to product aggregates, add right-side filter refs to grouping.
      crossRelationFilters.foreach(filter => {
        val filterRefs = filter.references
        val refsOnRight = filterRefs.filter(a => rightPlan.outputSet.contains(a))
        val refsNotYetAvailable = filterRefs.filter(a => !combinedOutputSet.contains(a))

        // If not all refs are available yet, carry the right-side refs through
        if (refsNotYetAvailable.nonEmpty) {
          refsOnRight.foreach(att => {
            val alreadyGrouped = applicableGroupAttributes.exists(
              g => g.references.contains(att))
            if (!alreadyGrouped) {
              val namedAtt = att.asInstanceOf[NamedExpression]
              applicableGroupAttributes = applicableGroupAttributes :+ namedAtt
              dbg(s"Added $att to grouping for cross-relation filter (carry through)")
            }
          })
        }
      })

      val join = if (usePhysicalCountJoin) {
        var applicableAggExpressions = {
          new mutable.MutableList[AggregateExpression]()
        }
        var multiplySumExpressions = new mutable.MutableList[NamedExpression]()

        def createMultiplication(a: Expression, b: Expression): Expression = {
          val multiplication = if (a.dataType.acceptsType(b.dataType)) {
            Multiply(a, b)
          }
          else {
            a.dataType match {
              case _: DecimalType => Multiply(a, Cast(b, DecimalType(20, 0)))
              case _ => Multiply(a, Cast(b, a.dataType))
            }
          }

          if (multiplication.dataType == a.dataType) {
            multiplication
          }
          else {
            Cast(multiplication, a.dataType)
          }
        }

        def sumOrCountCase(agg: AggregateExpression) = {
          dbg("sumOrCountCase")
          if (lastSumMap.contains(agg.resultAttribute)) {
            dbg("lastSumMap contains " + agg.resultAttribute)
            val lastSumAtt = lastSumMap(agg.resultAttribute)

            if (rightPlan.outputSet.contains(lastSumAtt)) {
              //         |
              //       Project(ac<-a*c)
              //         |
              //       Y, a<-SUM(a)
              //      /   \
              //    Y(c)      Z(a)
              //
              val isPendingProduct = pendingProductSumSet.contains(agg.resultAttribute)

              // Check if current grouping differs from when product was created
              // This affects how we handle the multiplication by leftCount
              val originalGrouping = if (isPendingProduct) {
                pendingProductGrouping.getOrElse(agg.resultAttribute, Seq.empty)
              } else Seq.empty
              val originalGroupingSet = originalGrouping.flatMap(_.references).toSet
              val currentGroupingSet = applicableGroupAttributes.flatMap(_.references).toSet
              val hasExtraGrouping = isPendingProduct &&
                (currentGroupingSet != originalGroupingSet)

              dbg(s"RIGHT propagation: agg=$agg isPending=$isPendingProduct")
              dbg(s"  originalGrouping=$originalGroupingSet currentGrouping=$currentGroupingSet")
              dbg(s"  hasExtraGrouping=$hasExtraGrouping")

              // SUM then multiply by left count
              val newAgg = Sum(lastSumAtt).toAggregateExpression()
              applicableAggExpressions = applicableAggExpressions :+ newAgg

              if (leftPlan.outputSet.contains(leftCountAttribute)) {
                val newSum = Alias(createMultiplication(newAgg.resultAttribute,
                  leftCountAttribute), "sum")()

                multiplySumExpressions = multiplySumExpressions :+ newSum
                lastSumMap.put(agg.resultAttribute, newSum.toAttribute)
              }
              else {
                lastSumMap.put(agg.resultAttribute, newAgg.resultAttribute)
              }

              // If this was a pending product with extra grouping, keep it pending
              // so the final SUM aggregates the per-group values correctly.
              // If no extra grouping, it's been fully SUMmed and is no longer pending.
              if (isPendingProduct) {
                if (hasExtraGrouping) {
                  dbg(s"Pending product (extra grouping) on right, keeping pending: $lastSumAtt")
                  // Don't remove from pendingProductSumSet - final SUM will aggregate
                } else {
                  pendingProductSumSet.remove(agg.resultAttribute)
                  dbg(s"SUMmed pending product on right: $lastSumAtt")
                }
              }
            }

            if (leftPlan.outputSet.contains(lastSumAtt)) {
              //         |
              //       Project(ac<-a*(sc/Y.c))
              //         |
              //       Y, sc<-SUM(Z.c)*Y.c
              //      /     \
              //    Y(a,c)     Z(c)

              val isPendingProduct = pendingProductSumSet.contains(agg.resultAttribute)

              // Check if current grouping is compatible with when product was created
              // For safe propagation, current grouping must be a SUPERSET of original grouping.
              // - If superset: counts are per finer groups, summing still works
              // - If NOT superset: counts are grouped by DIFFERENT attributes, math breaks
              val originalGrouping = if (isPendingProduct) {
                pendingProductGrouping.getOrElse(agg.resultAttribute, Seq.empty)
              } else Seq.empty
              val originalGroupingSet = originalGrouping.flatMap(_.references).toSet
              val currentGroupingSet = applicableGroupAttributes.flatMap(_.references).toSet
              // hasIncompatibleGrouping: current grouping has DIFFERENT attrs than original.
              // - If currentGrouping is empty: compatible (total count, no splitting)
              // - If currentGrouping is superset of original: compatible (finer grouping)
              // - If currentGrouping has different attrs: INCOMPATIBLE
              val hasIncompatibleGrouping = isPendingProduct &&
                currentGroupingSet.nonEmpty &&
                !originalGroupingSet.subsetOf(currentGroupingSet)

              dbg(s"LEFT propagation: agg=$agg isPending=$isPendingProduct")
              dbg(s"  originalGrouping=$originalGroupingSet currentGrouping=$currentGroupingSet")
              dbg(s"  hasIncompatibleGrouping=$hasIncompatibleGrouping")

              if (isPendingProduct) {
                if (hasIncompatibleGrouping) {
                  // Current grouping is incompatible (doesn't contain original grouping).
                  // Counts are grouped by DIFFERENT attrs than the product was computed with.
                  // E.g., product per (role_id, imdb_id) but counts per (company_type_id).
                  // We CANNOT safely multiply product * count - they don't align.
                  //
                  // Strategy: REMOVE from lastSumMap so the final aggregate will treat
                  // this as a raw expression and multiply by final count:
                  // SUM(product * final_count).
                  dbg(s"Pending product (incompatible grouping) removing: $lastSumAtt")
                  dbg(s"  Will defer to final aggregate with count multiplication")
                  lastSumMap.remove(agg.resultAttribute)
                  pendingProductSumSet.remove(agg.resultAttribute)
                  pendingProductGrouping.remove(agg.resultAttribute)
                } else {
                  // No extra grouping - normal propagation
                  val countRightAgg = if (rightPlanIsLeaf) {
                    Count(Literal(1L)).toAggregateExpression()
                  } else {
                    Sum(rightCountAttribute).toAggregateExpression()
                  }
                  applicableAggExpressions = applicableAggExpressions :+ countRightAgg

                  val newSum = Alias(createMultiplication(lastSumAtt,
                    countRightAgg.resultAttribute), "sum")()

                  multiplySumExpressions = multiplySumExpressions :+ newSum
                  lastSumMap.put(agg.resultAttribute, newSum.toAttribute)
                  dbg(s"Pending product propagating on left: $lastSumAtt")
                  // Keep it as pending - it still needs final aggregation
                }
              } else {
                // Already aggregated sum - just multiply by right count
                val countRightAgg = if (rightPlanIsLeaf) {
                  Count(Literal(1L)).toAggregateExpression()
                }
                else {
                  Sum(rightCountAttribute).toAggregateExpression()
                }
                applicableAggExpressions = applicableAggExpressions :+ countRightAgg

                val newSum = Alias(createMultiplication(lastSumAtt,
                  countRightAgg.resultAttribute), "sum")()

                multiplySumExpressions = multiplySumExpressions :+ newSum
                lastSumMap.put(agg.resultAttribute, newSum.toAttribute)
              }
            }
          }
          else {
            dbg("lastSumMap does not contain " + agg.resultAttribute)
            // SUM/COUNT aggregate has not yet occurred somewhere in the tree -
            // check if it starts here
            if (agg.references.subsetOf(rightPlan.outputSet)) {
              dbg("agg.references.subsetOf(rightPlan.outputSet)")

              // logWarning("is subset")
              //         |
              //       Project(ac<-ac*Y.c)
              //         |
              //       Y, ac <- SUM(a*Z.c)
              //        /   \
              //       /     \
              //    Y(c)     Z(a,c)
              //              /  \
              //             /    \
              //           R(a)   S(c)

              val newAgg = if (rightPlanIsLeaf) {
                agg
              } else {
                agg.transformUp {
                  case a: AggregateFunction =>
                    a.withNewChildren(Seq(createMultiplication(a.children.head,
                      rightCountAttribute)))
                }.asInstanceOf[AggregateExpression]
              }

              applicableAggExpressions = applicableAggExpressions :+ newAgg

              // Left plan is not a leaf
              if (leftPlan.outputSet.contains(leftCountAttribute)) {
                val newSum = Alias(createMultiplication(newAgg.resultAttribute,
                  leftCountAttribute), "sum")()
                multiplySumExpressions = multiplySumExpressions :+ newSum

                lastSumMap.put(agg.resultAttribute, newSum.toAttribute)
              }
              else {
                lastSumMap.put(agg.resultAttribute, newAgg.resultAttribute)
              }
            }
            // Check if the aggregate references span both left and right plans
            // This handles SUM(A*B) where A is from left and B is from right
            else if (agg.references.subsetOf(leftPlan.outputSet ++ rightPlan.outputSet)
              && agg.references.exists(a => leftPlan.outputSet.contains(a))
              && agg.references.exists(a => rightPlan.outputSet.contains(a))) {

              val refsOnLeft = agg.references.filter(a => leftPlan.outputSet.contains(a))
              val refsOnRight = agg.references.filter(a => rightPlan.outputSet.contains(a))

              dbg("agg.references span both left and right plans (product aggregate)")
              dbg(s"refsOnLeft: $refsOnLeft, refsOnRight: $refsOnRight")

              // Count how many attributes are in the product (excluding count attributes)
              // 2-attribute products can be computed early
              // 3+ attribute products are deferred to avoid grouping interference
              val productAttrs = agg.references.filter(a =>
                !a.name.startsWith("c#") && a.name != "c")
              val numProductAttrs = productAttrs.size

              dbg(s"Product has $numProductAttrs attributes: $productAttrs")

              // Products can be computed early if the grouping is ONLY for this product's attrs.
              // If there's "foreign" grouping (attrs for OTHER products), the counts become
              // incompatible and we need to handle it during propagation.
              //
              // But we CAN still compute early - the propagation logic will detect
              // incompatible grouping and defer multiplication appropriately.
              //
              // The only case to avoid: right-side attr NOT in grouping (uncovered) which
              // means the attr varies within groups causing incorrect products.
              val rightAttrsNotInGrouping = refsOnRight.filterNot(a =>
                applicableGroupAttributes.exists(g => g.references.contains(a)))
              val hasUncoveredRightAttr = rightAttrsNotInGrouping.nonEmpty

              if (!SQLConf.get.yannakakisDeferProductsEnabled && numProductAttrs <= 2
                && !hasUncoveredRightAttr) {
                // 2-attribute product: compute early at this join
                dbg(s"Computing 2-attr product early: ${agg}")

                // Extract the inner expression of the Sum (e.g., role_id * info_type_id)
                val sumChild = agg.aggregateFunction.children.head

                // Check if right-side product attributes are grouped at THIS join
                val rightRefsGroupedHere = refsOnRight.exists(a =>
                  applicableGroupAttributes.exists(g => g.references.contains(a)))

                var productExpr: Expression = sumChild

                // Multiply by right count (handling grouping appropriately)
                if (!rightPlanIsLeaf) {
                  if (rightRefsGroupedHere) {
                    // Right attrs are grouped: use SUM(rightCount) to aggregate
                    val sumRightCountAgg = Sum(rightCountAttribute).toAggregateExpression()
                    applicableAggExpressions = applicableAggExpressions :+ sumRightCountAgg
                    productExpr = createMultiplication(productExpr,
                      sumRightCountAgg.resultAttribute)
                  } else {
                    // Right attrs not grouped: use rightCount directly
                    productExpr = createMultiplication(productExpr, rightCountAttribute)
                  }
                }

                // Multiply by left count if the left plan has accumulated counts
                if (!isLeafNode && leftPlan.outputSet.contains(leftCountAttribute)) {
                  productExpr = createMultiplication(productExpr, leftCountAttribute)
                }

                val productAlias = Alias(productExpr, "sum")()
                multiplySumExpressions = multiplySumExpressions :+ productAlias

                lastSumMap.put(agg.resultAttribute, productAlias.toAttribute)
                pendingProductSumSet.add(agg.resultAttribute)
                pendingProductGrouping.put(agg.resultAttribute, applicableGroupAttributes.toSeq)
                dbg(s"Added pending 2-attr product: $productExpr")
              } else {
                // Defer to final aggregate: either 3+ attrs or uncovered right attr
                if (hasUncoveredRightAttr) {
                  dbg(s"Deferring product (uncovered right attr: $rightAttrsNotInGrouping): ${agg}")
                } else {
                  dbg(s"Deferring 3+ attr product to final aggregate: ${agg}")
                }
                // Don't add to lastSumMap - will be handled at final aggregate
              }
            }
          }
        }

        dbg("lastAggMap: " + lastAggMap)
        aggExpressions.foreach(agg => {
          dbg("aggExpression: " + agg)
          agg.aggregateFunction match {
            case Sum(_, _) =>
              sumOrCountCase(agg)
            case Count(_) =>
              sumOrCountCase(agg)
              // Non-SUM aggregates (MIN/MAX)
            case _ =>
              if (lastAggMap.contains(agg.resultAttribute)) {
                // The aggregate has already been applied further down the tree
                // and we have to aggregate over it again
                val lastAgg = lastAggMap(agg.resultAttribute)

                // Check if the output of the last aggregate is in the right output
                if (rightPlan.outputSet.contains(lastAgg.resultAttribute)) {
                  val newAgg = lastAgg.transformDown {
                    case a: AggregateFunction => a.withNewChildren(Seq(lastAgg.resultAttribute))
                  }.asInstanceOf[AggregateExpression]
                  lastAggMap.put(agg.resultAttribute, newAgg)

                  applicableAggExpressions = applicableAggExpressions :+ newAgg
                }
              }
              else {
                // First occurrence of the aggregate
                // First check if it is NOT the case that we are only joining without aggregation
                // if (!(applicableGroupAttributes.nonEmpty && parent == null)) {
                  // This is the first time the aggregate function is applied.
                  // Therefore, store the first aggregation in the map
                  if (agg.references.subsetOf(rightPlan.outputSet)) {
                    lastAggMap.put(agg.resultAttribute, agg)
                    applicableAggExpressions = applicableAggExpressions :+ agg
                  }
                // }
              }
          }
        })
        dbg("lastAggMap: " + lastAggMap)

        val right = rightPlan

        dbg("leftPlan.output: " + leftPlan.output)
        dbg("applicableAggExpressions: " + applicableAggExpressions)
        dbg("applicableGroupAttributes: " + applicableGroupAttributes)
        dbg("isLeafNode: " + isLeafNode + ", rightPlanIsLeaf: " + rightPlanIsLeaf)
        dbg("prevCountExpr: " + prevCountExpr)
        dbg("leftCountAttribute: " + leftCountAttribute)
        dbg("newRightCount: " + newRightCount)
        dbg("rightCountAttribute: " + rightCountAttribute)
        val countJoin = // if (applicableGroupAttributes.isEmpty) {
          // No grouping
          CountJoin(leftPlan, right,
            Inner, Option(joinConditions),
            Option(if (isLeafNode) prevCountExpr else leftCountAttribute),
            Option(if (rightPlanIsLeaf) newRightCount else rightCountAttribute),
            applicableAggExpressions, applicableGroupAttributes, joinHint)
//        }
//        else {
//          // Grouping
//          if (parent != null) {
//            Aggregate(leftPlan.output ++ applicableGroupAttributes,
//              applicableAggExpressions.map(ae => Alias(ae, "agg")()) ++ leftPlan.output
//                ++ applicableGroupAttributes,
//              Join(leftPlan, right, Inner, Option(joinConditions), joinHint))
//          }
//          else {
//            // At the root of the tree, do not aggregate because it would be redundant
//            Join(leftPlan, right, Inner, Option(joinConditions), joinHint)
//          }
//        }
        dbg("countJoin: " + countJoin)

        if (multiplySumExpressions.isEmpty) {
          countJoin
        }
        else {
          Project(countJoin.output ++ multiplySumExpressions, countJoin)
        }
      } else {
        Join(leftPlan, rightPlan,
          Inner, Option(joinConditions), joinHint)
      }
//      logWarning("join output: " + join.output)
      val finalCountExpr = if (usePhysicalCountJoin) {
          // The summed-up and multiplied result is already in the right count attribute
          if (rightPlanIsLeaf) {
            newRightCount
          }
          else {
            rightCountAttribute
          }
        }
        else {
          // Multiply the left count with the right count
          Alias(Multiply(
            Cast(leftCountAttribute, rightCountAttribute.dataType),
            rightCountAttribute), "c")()
        }
      dbg("finalCountExpr: " + finalCountExpr)
//      logWarning("join output: " + join.output)
      val finalProjection = join

      prevPlan = finalProjection
      prevCountExpr = finalCountExpr
      isLeafNode = false
      prevChildEdge = childEdge
      prevSemijoined = false
    }
    (prevPlan, prevCountExpr.toAttribute, isLeafNode, prevSemijoined)
  }
  def reroot: HTNode = {
    if (parent == null) {
      this
    }
    else {
//      logWarning("parent: " + parent)
      var current = this
      var newCurrent = this.copy(newParent = null)
      val root = newCurrent
      while (current.parent != null) {
        val p = current.parent
        dbg("p: " + p)
        val newChild = p.copy(newChildren = p.children - current, newParent = null)
//        logWarning("new child: " + newChild)
        newCurrent.children += newChild
//        logWarning("c: " + current)
        current = p
        newCurrent = newChild
      }
      root.setParentReferences
      root
    }
  }
  def findNodeContainingAttributes(aggAttributes: AttributeSet): HTNode = {
    val nodeAttributes = edges
      .map(e => e.planReference.outputSet)
      .reduce((e1, e2) => e1 ++ e2)
    if (aggAttributes subsetOf nodeAttributes) {
      this
    } else {
      for (c <- children) {
        val node = c.findNodeContainingAttributes(aggAttributes)
        if (node != null) {
          return node
        }
      }
      null
    }
  }

  def setParentReferences: Unit = {
    for (c <- children) {
      c.parent = this
      c.setParentReferences
    }
  }
  def copy(newEdges: Set[HGEdge] = edges, newChildren: Set[HTNode] = children,
           newParent: HTNode = parent): HTNode =
    new HTNode(newEdges, newChildren, newParent)
  private def toString(level: Int = 0): String =
    s"""${"-- ".repeat(level)}TreeNode(${edges})""" +
      s"""[${edges.map(e => e.planReference.outputSet)}] [[parent: ${parent != null}]]
         |${children.map(c => c.toString(level + 1)).mkString("\n")}""".stripMargin
  override def toString: String = toString(0)
}
class Hypergraph (private val items: Seq[LogicalPlan],
                  private val conditions: ExpressionSet) extends Logging {

  private var vertices: mutable.Set[String] = mutable.Set.empty
  private var edges: mutable.Set[HGEdge] = mutable.Set.empty
  private var vertexToAttributes: mutable.Map[String, Set[Attribute]] = mutable.Map.empty
  private var attributeToVertex: mutable.Map[ExprId, String] = mutable.Map.empty

  private var equivalenceClasses: Set[Set[Attribute]] = Set.empty

  // Track non-equality conditions that span multiple relations (cross-relation filters)
  // These need to be applied at the appropriate join point
  var crossRelationFilters: Seq[Expression] = Seq.empty

  for (cond <- conditions) {
    if (RewriteJoinsAsSemijoins.DEBUG_LOGGING) {
      logWarning("condition: " + cond + ", refs: " + cond.references)
    }
    cond match {
      case EqualTo(lhs, rhs) =>
        // logWarning("equality condition: " + lhs.references + " , " + rhs.references)
        val lAtt = lhs.references.head
        val rAtt = rhs.references.head
        equivalenceClasses += Set(lAtt, rAtt)
      case other =>
        // Non-equality conditions that reference multiple attributes from different relations
        // need to be tracked and applied at the appropriate join point
        if (RewriteJoinsAsSemijoins.DEBUG_LOGGING) {
          logWarning(s"Non-equality condition: $other, refs size: ${other.references.size}")
        }
        if (other.references.size > 1) {
          crossRelationFilters = crossRelationFilters :+ other
          if (RewriteJoinsAsSemijoins.DEBUG_LOGGING) {
            logWarning(s"Added cross-relation filter: $other")
          }
        }
    }
  }
  if (RewriteJoinsAsSemijoins.DEBUG_LOGGING && crossRelationFilters.nonEmpty) {
    logWarning(s"Total cross-relation filters: $crossRelationFilters")
  }

  // Compute the equivalence classes
  while (combineEquivalenceClasses) {

  }

//  logWarning("equivalence classes: " + equivalenceClasses)

  for (equivalenceClass <- equivalenceClasses) {
    val attName = equivalenceClass.head.toString
    vertices.add(attName)
    vertexToAttributes.put(attName, equivalenceClass)
    for (equivAtt <- equivalenceClass) {
      attributeToVertex.put(equivAtt.exprId, attName)
    }
  }

//  logWarning("vertex to attribute mapping: " + vertexToAttributes)
//  logWarning("attribute to vertex mapping: " + attributeToVertex)

  var tableIndex = 1
  for (item <- items) {
//    logWarning("join item: " + item)

    val projectAttributes = item.outputSet
    val hyperedgeVertices = projectAttributes
      .map(att => attributeToVertex.getOrElse(att.exprId, ""))
      .filterNot(att => att.equals("")).toSet

    val hyperedge = new HGEdge(hyperedgeVertices, s"E${tableIndex}", item, attributeToVertex)
    tableIndex += 1
    edges.add(hyperedge)
  }

//  logWarning("hyperedges: " + edges)

  private def combineEquivalenceClasses: Boolean = {
    for (set <- equivalenceClasses) {
      for (otherSet <- (equivalenceClasses - set)) {
        if ((set intersect otherSet).nonEmpty) {
          val unionSet = (set union otherSet)
          equivalenceClasses -= set
          equivalenceClasses -= otherSet
          equivalenceClasses += unionSet
          return true
        }
      }
    }
    false
  }

  def isAcyclic: Boolean = {
    flatGYO == null
  }

  def flatGYO: HTNode = {
    var gyoEdges: mutable.Set[HGEdge] = mutable.Set.empty
    var mapping: mutable.Map[String, HGEdge] = mutable.Map.empty
    var root: HTNode = null
    var treeNodes: mutable.Map[String, HTNode] = mutable.Map.empty

    for (edge <- edges) {
      mapping.put(edge.name, edge)
      gyoEdges.add(edge.copy())
    }

    var progress = true
    while (gyoEdges.size > 1 && progress) {
      for (e <- gyoEdges) {
        // logWarning("gyo edge: " + e)
        // Remove vertices that only occur in this edge
        val allOtherVertices = (gyoEdges - e).map(o => o.vertices)
          .reduce((o1, o2) => o1 union o2)
        val singleNodeVertices = e.vertices -- allOtherVertices

        // logWarning("single vertices: " + singleNodeVertices)

        val eNew = e.copy(newVertices = e.vertices -- singleNodeVertices)
        gyoEdges = (gyoEdges - e) + eNew

        // logWarning("removed single vertices: " + gyoEdges)
      }

      var nodeAdded = false
      for (e <- gyoEdges) {
//        logWarning("gyo edge: " + e)
        val supersets = gyoEdges.filter(o => o containsNotEqual e)
//        logWarning("supersets: " + supersets)

        // For each edge e, check if it is not contained in another edge
        if (supersets.isEmpty) {
          // Append the contained edges as children in the tree
          val containedEdges = gyoEdges.filter(o => (e contains o) && (e.name != o.name))
          val parentNode = treeNodes.getOrElse(e.name, new HTNode(Set(e), Set(), null))
          val childNodes = containedEdges
            .map(c => treeNodes.getOrElse(c.name, new HTNode(Set(c), Set(), null)))
            .toSet
//          logWarning("parentNode: " + parentNode)
          parentNode.children ++= childNodes
//          logWarning("subsets: " + childNodes)
          if (childNodes.nonEmpty) {
            nodeAdded = true
          }

          treeNodes.put(e.name, parentNode)
          childNodes.foreach(c => treeNodes.put(c.edges.head.name, c))
          root = parentNode
          root.setParentReferences
          gyoEdges --= containedEdges
        }
      }
      if (!nodeAdded) progress = false
    }

    if (gyoEdges.size > 1) {
      return null
    }

    root
  }
  override def toString: String = {
    edges.map(edge => s"""${edge.name}(${edge.vertices.map(v => v.replace("#", "_"))
      .mkString(",")})""").mkString(",\n")
  }
}

