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
import org.apache.spark.sql.catalyst.plans.{Inner, InnerLike, LeftAnti, LeftOuter, LeftSemi}
import org.apache.spark.sql.catalyst.plans.{FullOuter, RightOuter}
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.catalyst.trees.TreePattern
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types._
import org.apache.spark.sql.types.DecimalType.DoubleDecimal

object RewriteJoinsAsSemijoins extends Rule[LogicalPlan]
  with PredicateHelper with JoinSelectionHelper {
  // Set to true to enable debug logging for the GroupAggJoin optimization
  val DEBUG_LOGGING = false

  private def debugLog(msg: => String): Unit = {
    if (DEBUG_LOGGING) logWarning(msg)
  }

  /**
   * Cost gate for the count-join rewrite. Returns true when vanilla Spark would broadcast
   * every base relation except the single largest - a broadcast-friendly star schema where
   * the baseline is already near-optimal and the (interpreted, codegen-disabled) count-join
   * only adds per-row cost with no reduction benefit. In that regime the rewrite should not
   * fire. Conservative: with no usable size stats, sizeInBytes defaults are large so the
   * gate does not trigger and the rewrite proceeds as before.
   */
  private def baselineBroadcastsAllButLargest(items: Seq[LogicalPlan]): Boolean = {
    if (!conf.yannakakisCostGateEnabled) {
      false
    } else if (items.size <= 1) {
      true
    } else {
      val bySize = items.sortBy(_.stats.sizeInBytes)
      bySize.dropRight(1).forall(p => canBroadcastBySize(p, conf))
    }
  }

  /**
   * Cost-gate decision: skip the rewrite (keep vanilla) only when vanilla is already near-optimal.
   * The broadcast-size signal ALONE is wrong for fan-out blow-ups: relations can be small (all
   * broadcast-eligible) yet a multi-way star join over them explodes the intermediate - on
   * STATS-CEB the size gate skipped all 146 queries, throwing away 7 where vanilla does-not-finish
   * while the count-join is >5-60x. A join key shared by >=3 relations (max hypergraph vertex
   * degree) signals exactly that multiplicative fan-out, so we keep the rewrite for those. The
   * asymmetry favours keeping: wrongly applying the count-join costs a little overhead, wrongly
   * skipping a fan-out query can be catastrophic.
   */
  private def costGateSkips(items: Seq[LogicalPlan], hg: Hypergraph): Boolean =
    baselineBroadcastsAllButLargest(items) && hg.maxKeyDegree < 3

  // A "product aggregate" is a SUM over 2+ non-count attributes from different relations (e.g.
  // SUM(a*b)); a "cross-relation filter" is a non-equi predicate spanning relations. Both are
  // extracted as DeferredComputations so the rewrite can detect product conflicts: when 2+
  // products are present they all defer to the final aggregate (conflictingProductAttrs), because
  // computing one early regroups the count and would corrupt the others.
  sealed trait DeferredComputationType
  case object ProductAggregate extends DeferredComputationType
  case object CrossRelationFilter extends DeferredComputationType

  /**
   * DeferredComputation: A unified abstraction for computations that need attributes
   * from multiple relations before they can be evaluated.
   *
   * This unifies two concepts:
   * 1. Product aggregates: SUM(A*B) where A and B come from different relations
   * 2. Cross-relation filters: predicates like (R.x + S.y > 10) spanning multiple relations
   *
   * Both use the same strategy:
   * - Carry required attributes through grouping at each join
   * - Compute/apply when all required attributes become available
   * - For products: early computation with count propagation
   * - For filters: apply at the join where all attrs are available
   *
   * @param attrs         Attributes required for this computation (product factors or filter refs)
   * @param expr          The expression to evaluate (product expression or filter predicate)
   * @param computationType Whether this is a ProductAggregate or CrossRelationFilter
   * @param resultAttr    For products: the result attribute of the aggregate expression
   */
  case class DeferredComputation(
    attrs: Set[Attribute],
    expr: Expression,
    computationType: DeferredComputationType,
    resultAttr: Option[Attribute] = None
  ) {
    /**
     * Check if this computation can be evaluated given the currently available attributes.
     * A computation is evaluable when all its required attributes are in scope.
     */
    def canEvaluate(availableAttrs: AttributeSet): Boolean =
      attrs.forall(a => availableAttrs.contains(a))

    /**
     * Check if two deferred computations CONFLICT.
     *
     * Conflict occurs when they share some attributes but neither is contained in the other.
     * This means their count tracks are incompatible - using grouping for one affects
     * the counts for the other.
     *
     * Example conflicts:
     *   {a, b} conflicts with {b, c} - share b but neither contains the other
     *   {a, b} conflicts with {a, c} - share a but neither contains the other
     *
     * Example non-conflicts:
     *   {a, b} and {c, d} - disjoint, independent
     *   {a} and {a, b} - containment, {a} subset {a, b}
     *   {a, b} and {a, b} - identical
     */
    def conflictsWith(other: DeferredComputation): Boolean = {
      val overlap = attrs.intersect(other.attrs)
      overlap.nonEmpty && !attrs.subsetOf(other.attrs) && !other.attrs.subsetOf(attrs)
    }

    /**
     * Check if this computation's attrs are strictly contained in another's.
     * Used for hierarchical count track derivation.
     *
     * When P1.containedIn(P2):
     *   - P2 needs finer-grained counts (grouped by more attributes)
     *   - P1's counts can be derived from P2's by aggregation
     */
    def containedIn(other: DeferredComputation): Boolean =
      attrs.subsetOf(other.attrs) && attrs != other.attrs

    /**
     * Check if this computation's attrs strictly contain another's.
     */
    def contains(other: DeferredComputation): Boolean =
      other.containedIn(this)

    /**
     * Check if this computation is independent from another (no shared attributes).
     * Independent computations can use completely separate count tracks.
     */
    def independentFrom(other: DeferredComputation): Boolean =
      attrs.intersect(other.attrs).isEmpty
  }

  /**
   * Extracts deferred computations from aggregate expressions and cross-relation filters.
   *
   * This function identifies:
   * 1. Product aggregates: SUM expressions with 2+ non-count attributes
   * 2. Cross-relation filters: Predicates that span multiple relations
   *
   * Product detection rules:
   * - Must be a SUM aggregate
   * - Must reference 2+ attributes (excluding count-like attributes)
   * - Count-like attributes are filtered out: names starting with "c#" or exactly "c"
   *
   * @param aggExpressions       The aggregate expressions from the query
   * @param crossRelationFilters Non-equality predicates spanning multiple relations
   * @return Sequence of DeferredComputation objects for analysis and optimization
   */
  def extractDeferredComputations(
    aggExpressions: Seq[AggregateExpression],
    crossRelationFilters: Seq[Expression]
  ): Seq[DeferredComputation] = {
    // Extract product aggregates
    val products = aggExpressions.flatMap { agg =>
      agg.aggregateFunction match {
        case Sum(child, _) if child.references.nonEmpty =>
          // Filter out count-like attributes to identify true product attributes
          // Count attrs typically have names like "c#123" or synthetic long IDs
          val refs = child.references.filter(a =>
            !a.name.startsWith("c#")).toSet
          if (refs.size >= 2) {
            Some(DeferredComputation(refs, child, ProductAggregate, Some(agg.resultAttribute)))
          } else None
        case _ => None
      }
    }

    // Extract cross-relation filters
    val filters = crossRelationFilters.map { filter =>
      DeferredComputation(filter.references.toSet, filter, CrossRelationFilter)
    }

    products ++ filters
  }

  /** As in [[PhysicalAggregation]] the aggregate  expressions are extracted from the
   * outputExpressions ([[NamedExpression]]. Then these are replaced in the output expressions
   * by new references.
   * */
  def rewritePlan(agg: Aggregate, unnamedGroupingExpressions: Seq[Expression],
                  origResultExpressions: Seq[NamedExpression],
                  projectList: Seq[NamedExpression],
                  join: Join, keyRefs: Seq[Seq[Expression]],
                  uniqueConstraints: Seq[Seq[Expression]]) : LogicalPlan = {
    val startTime = System.nanoTime()
    debugLog("applying rewriting to join: " + agg)
    // Desugar FILTER (WHERE ...) aggregates into CASE inputs up-front (whitelisted,
    // NULL-ignoring functions only) so all downstream logic sees plain aggregates.
    // Bail-outs below return the original `agg`, so this is observable only when the
    // rewrite actually applies.
    val resultExpressions = desugarFilteredAggregates(origResultExpressions)
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
    // For each pending product, track which attributes' counts have already been multiplied.
    // This prevents double-counting when the product flows through subsequent joins.
    // Key: product's result attribute, Value: set of attributes whose counts are accounted for
    val pendingProductAccountedAttrs = new mutable.HashMap[Attribute, AttributeSet]()
    // Track the original attributes that each pending product aggregates over.
    // This helps determine if a right subtree is relevant to this product - we only
    // multiply by right count if the right subtree contains tables for this product.
    // Key: product's result attribute, Value: product's original attribute references
    val pendingProductOriginalAttrs = new mutable.HashMap[Attribute, AttributeSet]()

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

    // FILTER aggregates that survived desugaring belong to functions that do not ignore
    // NULL inputs (first/last/UDAFs) - those cannot be rewritten safely.
    val hasFilteredAggregate = resultExpressions.exists(_.exists {
      case ae: AggregateExpression => ae.filter.isDefined
      case _ => false
    })
    if (hasFilteredAggregate) {
      debugLog("query contains unsupported FILTER aggregates - not applicable")
      return agg
    }
    // DISTINCT aggregates cannot be decomposed via count multiplication (the duplicates
    // introduced by the join are exactly what DISTINCT removes). They are
    // duplicate-insensitive, so guarded queries whose aggregates are ALL
    // duplicate-insensitive take the pure-semijoin (0MA) path below; any other
    // distinct-containing query bails out at its branch.
    val hasDistinctAggregate = resultExpressions.exists(_.exists {
      case ae: AggregateExpression => ae.isDistinct
      case _ => false
    })

    // 0MA queries can be evaluated purely by bottom-up semi joins
    // Currently, they are limited to Min and Max queries
    // For all aggregates (0MA or counting-based), check if there are no references to attributes
    // (e.g., COUNT(1)) or the references are not part of the grouping attributes
    // TODO remove duplicated code. Use enum for representing query types?
    // The "references not a subset of grouping" filter is ONLY sound for duplicate-insensitive
    // aggregates (min/max/distinct) and percentiles: over a grouping-key argument those are
    // constant per group and need no fan-out counting. Count/Sum/Average are fan-out-SENSITIVE
    // even when their only argument is a grouping key (e.g. count(k)/sum(k) grouped by k still
    // count the join fan-out), so they must NOT be filtered out - otherwise the query is
    // misclassified as 0MA and a semijoin reduction silently drops the fan-out. (Found by
    // YannakakisFuzzSuite: count(k) + max(v) grouped by the join key k returned count=1, not 2.)
    val zeroMAAggregates = resultExpressions
      .filter(agg => agg.references.isEmpty || !(agg.references subsetOf groupAttributes))
      .filter(agg => isDuplicateInsensitive(agg))
    val percentileAggregates = resultExpressions
      .filter(agg => agg.references.isEmpty || !(agg.references subsetOf groupAttributes))
      .filter(agg => isPercentile(agg))
    val countingAggregates = resultExpressions.filter(agg => isCounting(agg))
    val sumAggregates = resultExpressions.filter(agg => isSum(agg))
    val averageAggregates = resultExpressions.filter(agg => isAverage(agg))
    // VAR/STDDEV are fan-out-sensitive (like sum/avg): a count-weighted moment. Handled on the
    // guarded counting path via count-weighted power sums (see rewriteGuardedAggregate).
    val momentAggregates = resultExpressions.filter(agg => isMoment(agg))

    if (zeroMAAggregates.isEmpty
      && percentileAggregates.isEmpty
      && countingAggregates.isEmpty
      && sumAggregates.isEmpty
      && averageAggregates.isEmpty
      && momentAggregates.isEmpty) {
      debugLog("query is not applicable (0MA, counting, percentile, sum, moment)")
      agg
    }
    else {
      val hg = new Hypergraph(items, conditions)
      // For acyclic queries flatGYO returns the join tree directly (unchanged behaviour). When
      // it returns null the query is cyclic: if cyclic-bag decomposition is enabled, try to
      // materialize the cyclic component as a bag and produce an (acyclic) tree-of-bags; the
      // rest of the count-join machinery then runs over it unchanged. A null from either path
      // falls back to the original plan.
      val jointree = {
        val acyclicTree = hg.flatGYO
        if (acyclicTree != null) {
          acyclicTree
        } else if (conf.yannakakisCyclicBagsEnabled) {
          debugLog("join is cyclic - attempting bag decomposition")
          hg.flatGYOWithBags
        } else {
          null
        }
      }

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

          // Non-guarded DUPLICATE-INSENSITIVE aggregates (DISTINCT count/sum/avg, collect_set,
          // approx_count_distinct, bit_and/bit_or): count multiplication is invalid, but a
          // single bottom-up carry-reduced join is correct - carry the group attrs and the
          // aggregate argument(s) to a common node by inner-joining the connecting subtrees
          // (semijoin-reducing the rest), then apply the ORIGINAL aggregate. No count
          // multiplication. Pure min/max keep the existing path below.
          val allDuplicateInsensitive = aggregateExpressions.nonEmpty &&
            aggregateExpressions.forall(ae => isDuplicateInsensitive(ae))
          val hasNonMinMaxDupInsensitive = aggregateExpressions.exists {
            case AggregateExpression(_: Min, _, _, _, _) => false
            case AggregateExpression(_: Max, _, _, _, _) => false
            case ae => isDuplicateInsensitive(ae)
          }
          if (allDuplicateInsensitive && hasNonMinMaxDupInsensitive) {
            if (conf.yannakakisDistinctEnabled) {
              // Carry the filter attributes through the reduction too, then apply the filters once
              // all their attributes are available (mirrors the guarded 0MA path). 0MA aggregates
              // are duplicate-insensitive, so the inner-join carries and the dedup are lossless.
              val filterRefs = AttributeSet(hg.crossRelationFilters.flatMap(_.references))
              val needed = groupAttributes ++ aggregateAttributes ++ filterRefs
              val distinctRoot =
                Option(jointree.findNodeContainingAttributesEquiv(aggregateAttributes))
                  .orElse(Option(jointree.findNodeContainingAttributesEquiv(groupAttributes)))
                  .map(_.reroot)
                  .getOrElse(jointree)
              val reducedJoin = distinctRoot.buildBottomUpDistinctJoin(needed, isTop = true)
              if (hg.crossRelationFilters.nonEmpty && !filterRefs.subsetOf(reducedJoin.outputSet)) {
                // A filter references an attribute the reduction could not carry to a common node
                // (it spans relations never inner-joined together) - fall back rather than apply a
                // filter over missing attributes.
                debugLog("non-guarded 0MA: cross-relation filter refs not all carried - keeping " +
                  "original plan")
                return agg
              }
              val filtered = hg.crossRelationFilters.foldLeft[LogicalPlan](reducedJoin)(
                (p, f) => Filter(f, p))
              val newAgg = Aggregate(groupingExpressions, resultExpressions, filtered)
              logInfo("new aggregate (distinct-reduced)")
              debugLog("time difference: " + (System.nanoTime() - startTime))
              return newAgg
            }
            // distinct path disabled: not supported.
            debugLog("duplicate-insensitive non-guarded aggregates not supported here")
            return agg
          }
          if (hasDistinctAggregate) {
            // DISTINCT mixed with additive (non-duplicate-insensitive) aggregates: the
            // additive part needs count multiplication, the distinct part forbids it.
            debugLog("DISTINCT mixed with additive aggregates - not supported")
            return agg
          }

          val nodeContainingGroupAttributes =
            jointree.findNodeContainingAttributesEquiv(groupAttributes)
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
          }

          // The unguarded/piecewise counting rewrite below handles Count and Sum and treats every
          // other aggregate as MIN/MAX. Only Min/Max are actually correct under that default;
          // Average, Percentile, stddev, etc. would be emitted WITHOUT count multiplication and
          // silently return wrong results. Restrict to the functions this path rewrites correctly.
          if (!aggregateExpressions.forall(ae => ae.aggregateFunction match {
            case _: Count | _: Sum | _: Min | _: Max => true
            case _ => false
          })) {
            debugLog("unguarded: unsupported aggregate function present - keeping original plan")
            return agg
          }

          // Cross-relation filters are folded into the CountJoin condition; rows whose matches
          // all fail the filter are now correctly dropped by the operator (the non-grouping
          // path emits nothing when rightCountSum == 0, and the grouping path produces no group),
          // so the unguarded count-join path no longer bails on them.

          // Phase 1: Extract and analyze deferred computations using unified framework
          // This includes both product aggregates and cross-relation filters
          val deferredComputations = extractDeferredComputations(
            aggregateExpressionsWithAliasesReplaced,
            hg.crossRelationFilters
          )

          // Check if there are cross-relation filters AND cross-relation product aggregates.
          // The key insight: filters are added to the CountJoin condition (lines 939-944),
          // so they're evaluated BEFORE count aggregation. This means:
          // - At the join where filter becomes applicable, rows are filtered first
          // - Counts are computed only on filtered rows
          // - Products computed at that join use correct filtered counts
          //
          // HOWEVER, there's a subtle issue with 3+ table joins:
          // If filter spans {a,c} and product spans {a,b} with order t3->t2->t1,
          // the count at t3 join t2 is computed before the filter can be applied.
          //
          // Safe case: Filter and product have the SAME attribute set, or filter attrs
          // are a superset of product attrs. Then filter is applied at same/earlier join.
          //
          // For now, only allow if filter attrs contain all product attrs (containment).
          if (hg.crossRelationFilters.nonEmpty && unguardedAggAttributes.nonEmpty) {
            val filterAttrs = hg.crossRelationFilters.flatMap(_.references).toSet
            val productAttrSets = deferredComputations
              .filter(_.computationType == ProductAggregate)
              .map(_.attrs)

            val allProductsContainedInFilter = productAttrSets.forall { prodAttrs =>
              prodAttrs.subsetOf(filterAttrs)
            }

            if (!allProductsContainedInFilter) {
              debugLog("unguarded with cross-relation filters (non-contained). " +
                "plan is not changed")
              debugLog(s"  filter attrs: $filterAttrs")
              debugLog(s"  product attr sets: $productAttrSets")
              return agg
            }

            debugLog("Filter+product optimization: products contained in filter attrs")
          }

          // =====================================================================
          // MULTI-COUNT OPTIMIZATION ANALYSIS
          // =====================================================================
          // Separate products from filters for analysis
          val productComputations = deferredComputations.filter(
            _.computationType == ProductAggregate)
          val filterComputations = deferredComputations.filter(
            _.computationType == CrossRelationFilter)

          // =====================================================================
          // CONSERVATIVE CONFLICT DETECTION WITH ONE-WINNER FALLBACK
          // =====================================================================
          // When there are 2+ products, we use a conservative strategy:
          // - All products are marked as conflicting (deferred) by default
          // - Strategy 3 fallback: Pick ONE product to compute early (the "winner")
          //   - Winner is selected based on position in join tree (highest = fewest ancestors)
          //   - Other products defer to final aggregate
          // Strategy for multiple products:
          // When there are 2+ products, ALL must defer to final aggregate.
          //
          // Why even "independent" products (no shared attrs) can't compute early together:
          // - When P1 computes early, its attrs are added to grouping
          // - The count flowing through the tree becomes grouped by P1's attrs
          // - When P2 computes at a later join, it sees this grouped count
          // - P2 multiplies by the grouped count instead of the total count
          // - This causes incorrect results
          //
          // The fundamental issue is that count propagation is affected by ALL
          // product groupings, not just products that share attributes.
          // Even independent products affect each other's counts because:
          // - When P1 computes, its attrs are added to grouping
          // - This grouping affects how counts are aggregated
          // - When P2 computes, it sees counts grouped by P1's attrs (wrong!)
          //
          // SOLUTION: Defer ALL products when there are 2+, unless we implement
          // per-product count columns (which would be a larger change).
          val conflictingProductAttrs =
            if (productComputations.size >= 2) {
              // ALL products are conflicting when there are 2+ products
              val products = productComputations.toSeq
              for (prod <- products) {
                val attrsStr = prod.attrs.map(_.name).mkString(",")
                debugLog(s"  Product {$attrsStr}: CONFLICT (2+ products) -> DEFER")
              }
              products.flatMap(_.resultAttr).toSet
            } else {
              Set.empty[Attribute]
            }

          // Log cross-relation filters (handled uniformly with products)
          if (filterComputations.nonEmpty) {
            debugLog(s"Cross-relation filters (${filterComputations.size}): " +
              filterComputations.map(f => s"${f.expr}[${f.attrs.map(_.name).mkString(",")}]"))
          }

          debugLog("applicable query (joins=" + (items.size - 1) + ")")

          val (yannakakisJoins, countingAttribute, _, _) =
            root.buildBottomUpJoinsCounting(aggregateAttributes,
              groupingExpressions ++ groupAliasAttributes,
              aggregateExpressionsWithAliasesReplaced,
              lastAggMap, lastSumMap, nextMultiplicationMap, pendingProductSumSet,
              pendingProductAccountedAttrs, pendingProductOriginalAttrs,
              keyRefs, uniqueConstraints,
              conf.yannakakisCountGroupInLeavesEnabled,
              usePhysicalCountJoin = conf.yannakakisPhysicalCountEnabled,
              crossRelationFilters = mutable.Set(hg.crossRelationFilters: _*),
              conflictingProductAttrs = conflictingProductAttrs)

          debugLog("lastAggMap: " + lastAggMap)
          debugLog("lastSumMap: " + lastSumMap)
          debugLog("resultExpressionsWithAliasesReplaced: " +
            resultExpressionsWithAliasesReplaced)

          // Adapt the result expressions to make use of the frequency attribute
          // Rewrite each aggregate / grouping expression. Recurse MANUALLY and stop at each
          // rewritten aggregate rather than using transformDown, which re-descends into the
          // replacement (TreeNode.transformDownWithPruning line 506) - that would let the
          // cast-back below re-match and multiply the count in a second time (cf. the guarded
          // path). Original SQL aggregates never nest, so each is rewritten exactly once.
          def rewriteUnguardedExpr(e: Expression): Expression = e match {
            case ae: AggregateExpression =>
              val resultAtt = equivalentAggregateExpressions.getExprState(ae).map(_.expr)
                .getOrElse(ae).asInstanceOf[AggregateExpression].resultAttribute
              val rewritten = ae.aggregateFunction match {
                case a: Count =>
                  // count(x) adds the row's count when x is non-NULL and skips the
                  // row otherwise; the old Multiply(children.head, count) form was
                  // only correct for count(1)-style children
                  val nullableInputs = a.children.filter(_.nullable)
                  val countInput: Expression = if (nullableInputs.isEmpty) {
                    countingAttribute
                  } else {
                    If(nullableInputs.map(IsNull(_): Expression).reduce(Or),
                      Literal(0L, LongType), countingAttribute)
                  }
                  Sum(countInput).toAggregateExpression()
                case _ =>
                  // Replace each aggregate function by its count-multiplied form.
                  ae.transformDown {
                    case a: AggregateFunction =>
                      a match {
                        // TODO this could be simplified by merging Sum and Count cases
                        case Sum(_, _) =>
                          if (lastSumMap.contains(resultAtt)) {
                            val lastSumAtt = lastSumMap(resultAtt)
                            a.withNewChildren(Seq(lastSumAtt))
                          }
                          else {
                            // Multiply in the type SUM(c) would use so the count multiplication
                            // does not overflow c's narrow type (e.g. Int) where vanilla's
                            // promoted Sum accumulator would not (cf. the guarded Sum case).
                            val c = a.children.head
                            val wideType = Sum(c).dataType
                            a.withNewChildren(
                              Seq(Multiply(Cast(c, wideType),
                                Cast(countingAttribute, wideType))))
                          }
                        case _ =>
                          // MIN, MAX
                          if (lastAggMap.contains(resultAtt)) {
                            val lastResultAtt = lastAggMap(resultAtt).resultAttribute
                            a.withNewChildren(Seq(lastResultAtt))
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
              // Cast back to the original aggregate result type: the wide count-multiply can
              // re-clamp a decimal's precision/scale (e.g. DECIMAL(28,4) -> DECIMAL(38,6)), which
              // would change the output schema and fail plan validation. Cast-back keeps the
              // schema identical to vanilla (mirrors the guarded Sum branch); a no-op otherwise.
              Cast(rewritten, ae.dataType)
            case expr if !expr.foldable =>
              // Replace grouping key expressions with their corresponding attributes. Attributes
              // may differ cosmetically, so match via semanticEquals rather than equality.
              groupExpressionMap.collectFirst {
                case (g, ne) if g semanticEquals expr => ne.toAttribute
              }.getOrElse(expr.mapChildren(rewriteUnguardedExpr))
            case other => other
          }
          val rewrittenResultExpressions = resultExpressionsWithAliasesReplaced.map(e =>
            rewriteUnguardedExpr(e).asInstanceOf[NamedExpression])
          debugLog("rewrittenResultExpressions: " + rewrittenResultExpressions)

          // Prune columns: only include columns that are needed by the aggregate
          val neededAttrs = AttributeSet(
            rewrittenResultExpressions.flatMap(_.references) ++
            groupingExpressions.flatMap(_.references)
          )
          val allOutputs = yannakakisJoins.output ++ groupAliasProjections
          val prunedOutput = allOutputs.filter(attr => neededAttrs.contains(attr))

          // When piecewise-guardedness was established via join equivalences, the final
          // aggregate may reference attributes (e.g. l_orderkey) that the rewritten join
          // only provides through an equivalent attribute (e.g. o_orderkey). Re-expose
          // them under their original ExprIds.
          val presentIds = prunedOutput.map(_.exprId).toSet
          val equivalenceAliases = neededAttrs.toSeq
            .filterNot(att => presentIds.contains(att.exprId))
            .flatMap(att => hg.getAttributeToVertex.get(att.exprId).flatMap(v =>
              yannakakisJoins.output.find(out =>
                hg.getAttributeToVertex.get(out.exprId).contains(v))
                .map(out => Alias(out, att.name)(exprId = att.exprId))))

          if (costGateSkips(items, hg)) {
            debugLog("cost gate: vanilla near-optimal (broadcast star, low fan-out) - " +
              "keeping original plan (count-join would only add cost)")
            return agg
          }
          val newAgg = Aggregate(groupingExpressions,
            rewrittenResultExpressions,
            Project(prunedOutput ++ equivalenceAliases, yannakakisJoins))
          val queryClass = if (piecewiseGuarded) "piecewise-guarded" else "unguarded"
          logInfo(f"new aggregate ($queryClass)")
          debugLog("time difference: " + (System.nanoTime() - startTime))
          newAgg
        }
        else {
          // The query is guarded
          val root = nodeContainingAllAttributes.reroot
          debugLog("applicable query (joins=" + (items.size - 1) + ")")

          // Degenerate single-node tree: the whole query reduced to one (possibly bag) leaf with
          // no children. This only arises when a cyclic component is materialized as a bag that
          // IS the entire query (e.g. a bare triangle) - an acyclic >= 2-relation join always
          // leaves the root with >= 1 child. There is no fan-out beyond the bag's own rows, so
          // the count-join machinery (which needs a join to seed/carry the count column) has
          // nothing to do; applying the ORIGINAL aggregate directly to the materialized leaf is
          // exactly correct (the bag's rows already equal the cyclic sub-query result).
          if (root.children.isEmpty && root.edges.size == 1) {
            if (baselineBroadcastsAllButLargest(items)) {
              debugLog("cost gate: keeping original plan")
              return agg
            }
            val newAgg = Aggregate(groupingExpressions, resultExpressions,
              root.edges.head.planReference)
            logWarning("new aggregate (guarded single-node bag)")
            debugLog("time difference: " + (System.nanoTime() - startTime))
            return newAgg
          }

          if (countingAggregates.isEmpty
            && percentileAggregates.isEmpty
            && sumAggregates.isEmpty
            && averageAggregates.isEmpty
            && momentAggregates.isEmpty) {
            // 0MA query: all aggregates are duplicate-insensitive (min/max and DISTINCT
            // count/sum/avg), so a bottom-up semijoin reduction suffices. (VAR/STDDEV are
            // fan-out-SENSITIVE, so a moment-only query must take the counting path below.)
            val newAgg = if (hg.crossRelationFilters.nonEmpty) {
              // buildBottomUpJoins (pure LeftSemi reduction) cannot apply a non-equi predicate
              // spanning relations. Instead carry the filter's referenced attributes to the top
              // by inner-joining the connecting subtrees (semijoin-reducing the rest and deduping
              // intermediates), then apply the filters there. 0MA aggregates are
              // duplicate-insensitive, so the inner carries and the dedup are lossless.
              val filterRefs = AttributeSet(hg.crossRelationFilters.flatMap(_.references))
              val needed = groupAttributes ++ aggregateAttributes ++ filterRefs
              val reducedJoin = root.buildBottomUpDistinctJoin(needed, isTop = true)
              if (!filterRefs.subsetOf(reducedJoin.outputSet)) {
                // A filter references an attribute that could not be carried to a common node
                // (e.g. it spans relations the reduction never inner-joins together) - fall back.
                debugLog("0MA: cross-relation filter refs not all carried - keeping original plan")
                return agg
              }
              val filtered = hg.crossRelationFilters.foldLeft[LogicalPlan](reducedJoin)(
                (p, f) => Filter(f, p))
              Aggregate(groupingExpressions, resultExpressions, filtered)
            } else {
              val yannakakisJoins = root.buildBottomUpJoins
              Aggregate(groupingExpressions, resultExpressions, yannakakisJoins)
            }
            logInfo("new aggregate (0MA)")
            debugLog("time difference: " + (System.nanoTime() - startTime))
            newAgg
          }
          else {
            // Guarded but not 0MA
            if (hasDistinctAggregate) {
              // Mixed DISTINCT + plain aggregates: the plain part needs counting, the
              // distinct part must not be count-multiplied. Not supported (phase 2).
              debugLog("mixed DISTINCT and plain aggregates - not applicable")
              return agg
            }
            // The guarded counting rewrite below only knows Count/Percentile/Average/Sum. A Min/Max
            // reaching here (it is mixed with a counting aggregate; pure min/max takes the 0MA
            // branch above) or any other function (stddev, variance, collect_*, first/last, ...)
            // has no case and would MatchError mid-optimization. Fall back instead of crashing.
            if (!aggregateExpressions.forall(ae => ae.aggregateFunction match {
              case _: Count | _: Percentile | _: Average | _: Sum => true
              case _: VariancePop | _: VarianceSamp | _: StddevPop | _: StddevSamp => true
              case _: Skewness | _: Kurtosis => true
              case _: CovPopulation | _: CovSample | _: Corr => true
              case _: RegrSlope | _: RegrIntercept | _: RegrR2 | _: RegrSXY => true
              case _ => false
            })) {
              debugLog("guarded: unsupported aggregate function present - keeping original plan")
              return agg
            }
            // Cross-relation filters are folded into the CountJoin condition and rows whose
            // matches all fail them are dropped by the operator (see the unguarded path); the
            // guarded counting path no longer bails on them.
            // Detect product aggregates for one-winner conflict detection
            val guardedDeferredComputations = extractDeferredComputations(
              aggregateExpressions, hg.crossRelationFilters)
            val guardedProductComputations = guardedDeferredComputations.filter(
              _.computationType == ProductAggregate)

            // When there are 2+ products, defer ALL to final aggregate for correctness
            val guardedConflictingAttrs =
              if (guardedProductComputations.size >= 2) {
                val allProductAttrs = guardedProductComputations.flatMap(_.resultAttr).toSet
                debugLog(s"Guarded: Multiple products - deferring all")
                allProductAttrs
              } else {
                Set.empty[Attribute]
              }

            val (yannakakisJoins, countingAttribute, _, _) =
              root.buildBottomUpJoinsCounting(aggregateAttributes,
                groupingExpressions,
                aggregateExpressions, lastAggMap, lastSumMap, nextMultiplicationMap,
                pendingProductSumSet, pendingProductAccountedAttrs, pendingProductOriginalAttrs,
                keyRefs, uniqueConstraints,
                conf.yannakakisCountGroupInLeavesEnabled,
                usePhysicalCountJoin = conf.yannakakisPhysicalCountEnabled,
                crossRelationFilters = mutable.Set(hg.crossRelationFilters: _*),
                conflictingProductAttrs = guardedConflictingAttrs)

            // Rewrite each aggregate in the result expressions. IMPORTANT: this recurses manually
            // and stops at every rewritten aggregate instead of using transformDown, which
            // re-descends into the *replacement* subtree (TreeNode.transformDownWithPruning line
            // 506). A rewrite that introduces nested aggregates - Average -> SUM(x*c)/SUM(c) -
            // would otherwise have the count multiplied a second time, producing SUM(x*c*c)/
            // SUM(c*c): silently wrong whenever the per-row counts are not all equal (the square
            // cancels only when every row's count is identical). Original SQL aggregates never
            // nest, so each is rewritten exactly once.
            def rewriteGuardedAggregate(e: Expression): Expression = e match {
              case aggExpr @ AggregateExpression(aggFn, mode, isDistinct, filter, resultId) =>
                aggFn match {
                  case a: Count =>
                    // count must skip rows whose input is NULL: count(x) with
                    // nullable x, and the CASE-desugared count(...) FILTER form
                    val nullableInputs = a.children.filter(_.nullable)
                    val countInput: Expression = if (nullableInputs.isEmpty) {
                      countingAttribute
                    } else {
                      If(nullableInputs.map(IsNull(_): Expression).reduce(Or),
                        Literal(0L, LongType), countingAttribute)
                    }
                    AggregateExpression(
                    Sum(countInput), mode, isDistinct, filter, resultId)

                  case Percentile(c, percExp, freqExp, mutableAggBufferOffset,
                  inputAggBufferOffset, reverse) =>
                    val freqExpr = countingAttribute
                    AggregateExpression(
                      Percentile(c, percExp, freqExpr, mutableAggBufferOffset,
                        inputAggBufferOffset, reverse), mode, isDistinct, filter, resultId)

                  case Average(avgInput, _) =>
                    // Multiply the whole input by the count exactly once. A
                    // per-attribute multiplication would corrupt CASE predicates
                    // and square the count for products of two attributes.
                    val sumAggregateExpr = aggFn.transformUp {
                      case a@Average(c, evalMode) =>
                        // Multiply the numerator in SUM(c)'s type to avoid overflow.
                        val wideType = Sum(c).dataType
                        Sum(Multiply(Cast(c, wideType), Cast(countingAttribute, wideType),
                          NumericEvalContext(evalMode)), NumericEvalContext(evalMode))
                    }.asInstanceOf[AggregateFunction].toAggregateExpression()

                    val countAggregateExpr = Sum(
                      If(avgInput.isNull,
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
                    // Multiply the whole input by the count exactly once (see the
                    // Average case above).
                    val widenedSum = AggregateExpression(aggFn.transformUp {
                      case s @ Sum(c, evalMode) =>
                        // Multiply in the type SUM(c) would use so the count multiplication
                        // does not overflow c's narrow type (e.g. Int) where vanilla's
                        // promoted Sum accumulator would not.
                        val wideType = Sum(c).dataType
                        Sum(Multiply(Cast(c, wideType), Cast(countingAttribute, wideType),
                          evalMode), evalMode)
                    }.asInstanceOf[AggregateFunction], mode, isDistinct, filter, resultId)
                    // The wide multiply can change the static type (a high-scale decimal clamps to
                    // DECIMAL(38,6)); cast back so the rewritten output schema matches vanilla.
                    Cast(widenedSum, aggExpr.dataType)

                  case _: VariancePop | _: VarianceSamp | _: StddevPop | _: StddevSamp
                     | _: Skewness | _: Kurtosis =>
                    // A central moment over a fan-out join: each reduced row carries value x with
                    // multiplicity `count`, so this is a COUNT-WEIGHTED moment. Compute it from
                    // count-weighted, null-x-guarded power sums n=SUM(c), Sx=SUM(x*c),
                    // Sxx=SUM(x^2*c) (+ Sxxx/Sxxxx for 3rd/4th), form the central sums m2/m3/m4 (=
                    // Welford's in exact arithmetic), then reproduce Spark's CentralMomentAgg
                    // evaluateExpression EXACTLY - same n==0 / n==1 / m2==0 guards and the
                    // divide-by-zero result. NULL x excluded. (Sxxx/Sxxxx are referenced only by
                    // skew/kurtosis, so var/stddev plans are unchanged.)
                    val x = aggFn.asInstanceOf[CentralMomentAgg].child
                    val xD = Cast(x, DoubleType)
                    val cD = Cast(countingAttribute, DoubleType)
                    def gsum(e: Expression): Expression =
                      Sum(If(IsNull(x), Literal(0.0, DoubleType), e)).toAggregateExpression()
                    val nAgg: Expression = Cast(Sum(If(IsNull(x), Literal(0L, LongType),
                      countingAttribute)).toAggregateExpression(), DoubleType)
                    val sx = gsum(Multiply(xD, cD))
                    val sxx = gsum(Multiply(Multiply(xD, xD), cD))
                    val m2 = Subtract(sxx, Divide(Multiply(sx, sx), nAgg))
                    val zero = Literal(0.0, DoubleType)
                    val one = Literal(1.0, DoubleType)
                    val three = Literal(3.0, DoubleType)
                    val nullD = Literal.create(null, DoubleType)
                    def dz(nodz: Boolean): Expression =
                      if (nodz) nullD else Literal(Double.NaN, DoubleType)
                    // central sums m3/m4 from raw power sums (used only by skew/kurtosis)
                    lazy val mean = Divide(sx, nAgg)
                    lazy val sxxx = gsum(Multiply(Multiply(Multiply(xD, xD), xD), cD))
                    lazy val sxxxx =
                      gsum(Multiply(Multiply(Multiply(Multiply(xD, xD), xD), xD), cD))
                    lazy val m3 = Subtract(Add(sxxx, Multiply(Literal(2.0, DoubleType),
                      Multiply(Multiply(mean, mean), sx))), Multiply(three, Multiply(mean, sxx)))
                    lazy val m4 = Add(Subtract(Subtract(sxxxx,
                      Multiply(Literal(4.0, DoubleType), Multiply(mean, sxxx))),
                      Multiply(three, Multiply(Multiply(mean, mean), Multiply(mean, sx)))),
                      Multiply(Literal(6.0, DoubleType), Multiply(Multiply(mean, mean), sxx)))
                    val res = aggFn match {
                      case _: VariancePop => If(EqualTo(nAgg, zero), nullD, Divide(m2, nAgg))
                      case _: StddevPop => If(EqualTo(nAgg, zero), nullD, Sqrt(Divide(m2, nAgg)))
                      case v: VarianceSamp => If(EqualTo(nAgg, zero), nullD,
                        If(EqualTo(nAgg, one), dz(v.nullOnDivideByZero),
                          Divide(m2, Subtract(nAgg, one))))
                      case s: StddevSamp => If(EqualTo(nAgg, zero), nullD,
                        If(EqualTo(nAgg, one), dz(s.nullOnDivideByZero),
                          Sqrt(Divide(m2, Subtract(nAgg, one)))))
                      case sk: Skewness => If(EqualTo(nAgg, zero), nullD,
                        If(EqualTo(m2, zero), dz(sk.nullOnDivideByZero),
                          Divide(Multiply(Sqrt(nAgg), m3), Sqrt(Multiply(Multiply(m2, m2), m2)))))
                      case k: Kurtosis => If(EqualTo(nAgg, zero), nullD,
                        If(EqualTo(m2, zero), dz(k.nullOnDivideByZero),
                          Subtract(Divide(Multiply(nAgg, m4), Multiply(m2, m2)), three)))
                      case _ => nullD // unreachable: outer match restricts to the six above
                    }
                    Cast(res, aggExpr.dataType)

                  case _: CovPopulation | _: CovSample | _: Corr
                     | _: RegrSlope | _: RegrIntercept | _: RegrR2 | _: RegrSXY =>
                    // Two-column 2nd moment over a fan-out join, COUNT-WEIGHTED. A row contributes
                    // only when BOTH x and y are non-null (matching Spark). From count-weighted
                    // power sums n, Sx, Sy, Sxx, Syy, Sxy form ck = Sxy - Sx*Sy/n (= n*covar_pop),
                    // xMk = Sxx - Sx^2/n, yMk = Syy - Sy^2/n, then reproduce Spark's
                    // Covariance/Corr/regression evaluateExpression EXACTLY. For the regr_* family
                    // cx is the INDEPENDENT variable (so xMk = var of the regressor) and cy the
                    // dependent one; regr children are (y, x) = (dependent, independent).
                    val (cx, cy, nodz) = aggFn match {
                      case c: CovPopulation => (c.left, c.right, c.nullOnDivideByZero)
                      case c: CovSample => (c.left, c.right, c.nullOnDivideByZero)
                      case c: Corr => (c.x, c.y, c.nullOnDivideByZero)
                      case r: RegrSlope => (r.right, r.left, true)
                      case r: RegrIntercept => (r.right, r.left, true)
                      case r: RegrR2 => (r.x, r.y, true)
                      case r: RegrSXY => (r.x, r.y, true)
                      case _ => (aggFn.children.head, aggFn.children(1), true)
                    }
                    val xD = Cast(cx, DoubleType)
                    val yD = Cast(cy, DoubleType)
                    val cD = Cast(countingAttribute, DoubleType)
                    val nullPair = Or(IsNull(cx), IsNull(cy))
                    def gsum2(e: Expression): Expression =
                      Sum(If(nullPair, Literal(0.0, DoubleType), e)).toAggregateExpression()
                    val nAgg2: Expression = Cast(Sum(If(nullPair, Literal(0L, LongType),
                      countingAttribute)).toAggregateExpression(), DoubleType)
                    val sX = gsum2(Multiply(xD, cD))
                    val sY = gsum2(Multiply(yD, cD))
                    val sXX = gsum2(Multiply(Multiply(xD, xD), cD))
                    val sYY = gsum2(Multiply(Multiply(yD, yD), cD))
                    val sXY = gsum2(Multiply(Multiply(xD, yD), cD))
                    val ck = Subtract(sXY, Divide(Multiply(sX, sY), nAgg2))
                    val xMk = Subtract(sXX, Divide(Multiply(sX, sX), nAgg2))
                    val yMk = Subtract(sYY, Divide(Multiply(sY, sY), nAgg2))
                    val zero2 = Literal(0.0, DoubleType)
                    val one2 = Literal(1.0, DoubleType)
                    val nullD2 = Literal.create(null, DoubleType)
                    val dz2: Expression =
                      if (nodz) nullD2 else Literal(Double.NaN, DoubleType)
                    // regr_* use xMk = var(independent), yMk = var(dependent); slope = ck/xMk,
                    // intercept = yAvg - slope*xAvg, r2 = ck^2/(xMk*yMk), sxy = ck. The n==0 guard
                    // also covers the m2-buffer-is-0 case Spark returns null/1.0 on.
                    val res2 = aggFn match {
                      case _: CovPopulation => If(EqualTo(nAgg2, zero2), nullD2, Divide(ck, nAgg2))
                      case _: CovSample => If(EqualTo(nAgg2, zero2), nullD2,
                        If(EqualTo(nAgg2, one2), dz2, Divide(ck, Subtract(nAgg2, one2))))
                      case _: Corr => If(EqualTo(nAgg2, zero2), nullD2,
                        If(EqualTo(nAgg2, one2), dz2, Divide(ck, Sqrt(Multiply(xMk, yMk)))))
                      case _: RegrSlope => If(EqualTo(nAgg2, zero2), nullD2,
                        If(EqualTo(xMk, zero2), nullD2, Divide(ck, xMk)))
                      case _: RegrIntercept => If(EqualTo(nAgg2, zero2), nullD2,
                        If(EqualTo(xMk, zero2), nullD2, Subtract(Divide(sY, nAgg2),
                          Multiply(Divide(ck, xMk), Divide(sX, nAgg2)))))
                      case _: RegrR2 => If(EqualTo(nAgg2, zero2), nullD2,
                        If(EqualTo(xMk, zero2), nullD2, If(EqualTo(yMk, zero2), one2,
                          Divide(Multiply(ck, ck), Multiply(xMk, yMk)))))
                      case _: RegrSXY => If(EqualTo(nAgg2, zero2), nullD2, ck)
                      case _ => nullD2 // unreachable
                    }
                    Cast(res2, aggExpr.dataType)
                }
              case other => other.mapChildren(rewriteGuardedAggregate)
            }
            val rewrittenResultExpressions = resultExpressions.map(e =>
              rewriteGuardedAggregate(e).asInstanceOf[NamedExpression])

            if (costGateSkips(items, hg)) {
              debugLog("cost gate: vanilla near-optimal (broadcast star, low fan-out) - " +
                "keeping original plan (count-join would only add cost)")
              return agg
            }
            val newAgg = Aggregate(groupingExpressions,
              rewrittenResultExpressions, yannakakisJoins)

            logInfo("new aggregate (guarded)")
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
        project@Project(projectList,
        join@Join(_, _, LeftOuter | RightOuter | FullOuter, _, _)), _) =>
          // LEFT/RIGHT/FULL OUTER: split into a matched (inner) half plus one anti half per
          // null-extended side, and merge per group. Falls back to `agg` on any unsupported shape.
          tryRewriteOuter(agg, groupingExpressions, aggExpressions, projectList, join)
            .getOrElse(agg)
        case agg@Aggregate(groupingExpressions, aggExpressions,
        join@Join(_, _, LeftOuter | RightOuter | FullOuter, _, _), _) =>
          tryRewriteOuter(agg, groupingExpressions, aggExpressions, join.output, join)
            .getOrElse(agg)
        case agg@Aggregate(groupingExpressions, aggExpressions,
        project@Project(projectList,
        join@Join(_, _, _: InnerLike, _, _)), _) =>
          // InnerLike also matches Cross: a cross join whose join predicates were normalised into
          // its condition (or that has none) is semantically an inner join here.
          rewriteOrFallback(agg, groupingExpressions, aggExpressions, projectList, join)
        case agg@Aggregate(groupingExpressions, aggExpressions,
        join@Join(_, _, _: InnerLike, _, _), _) =>
          // No Project wrapper (e.g. column pruning removed a redundant one): the aggregate
          // references the join columns directly, so pass an identity projectList. Note: a
          // residual Filter ABOVE the join is intentionally NOT handled - by the time this rule
          // runs, predicate pushdown has folded join predicates into the join condition and
          // pushed single-relation filters below it, so folding a surviving Filter back into the
          // join condition would risk dropping a single-relation predicate (the hypergraph only
          // models equi-edges and multi-relation filters), a silent wrong result.
          rewriteOrFallback(agg, groupingExpressions, aggExpressions, join.output, join)
        case agg@Aggregate(_, _, _, _) =>
          debugLog("not applicable to aggregate: " + agg)
          agg
      }
    }
  }

  /**
   * Runs the rewrite for one Aggregate-over-(inner-)join shape and degrades to the original plan
   * on any failure. Defense-in-depth: the rewrite explicitly falls back on shapes it cannot
   * handle, but any unforeseen unhandled case (e.g. an aggregate function with no rewrite branch)
   * must not fail the whole query.
   */
  private def rewriteOrFallback(
      agg: Aggregate,
      groupingExpressions: Seq[Expression],
      aggExpressions: Seq[NamedExpression],
      projectList: Seq[NamedExpression],
      join: Join): LogicalPlan = {
    trySplitMixedDistinct(agg, groupingExpressions, aggExpressions, projectList, join).getOrElse {
      try {
        validateOrFallback(agg,
          rewritePlan(agg, groupingExpressions, aggExpressions, projectList,
            join, keyRefs = Seq(), uniqueConstraints = Seq()))
      } catch {
        case scala.util.control.NonFatal(e) =>
          logWarning("yannakakis rewrite failed; falling back to the original plan: " +
            e.getMessage)
          agg
      }
    }
  }

  /**
   * Handles aggregates that mix duplicate-insensitive aggregates (min/max, DISTINCT count/sum/avg,
   * collect_set, ...) with counting aggregates (plain count/sum/avg/percentile). These cannot share
   * one count-join pipeline: the counting aggregates need the join fan-out multiplied in, while the
   * duplicate-insensitive ones need it ignored. Split the aggregate into two halves over the SAME
   * join - a duplicate-insensitive half (rewritten via the 0MA/semijoin path) and a counting half
   * (rewritten via the counting path) - then rejoin the per-group results on the grouping keys.
   *
   * Each half is rewritten via the normal entry path (so neither needs new aggregation logic). The
   * split is only committed when BOTH halves are accelerated; otherwise it would replace one join
   * with two (one possibly un-reduced), a pessimization, so we fall back to the original plan.
   * Returns None when the aggregate is not mixed or cannot be cleanly split.
   */
  private def trySplitMixedDistinct(
      agg: Aggregate,
      grouping: Seq[Expression],
      aggExpressions: Seq[NamedExpression],
      projectList: Seq[NamedExpression],
      join: Join): Option[LogicalPlan] = {
    val aggs = aggExpressions.flatMap(_.collect { case ae: AggregateExpression => ae })
    val (dupInsensitive, counting) = aggs.partition(isDuplicateInsensitive)
    if (dupInsensitive.isEmpty || counting.isEmpty) return None  // not mixed

    // Route each result expression to exactly one half: a grouping-derived expression (no
    // aggregate; code 0), or an Alias over a single aggregate (duplicate-insensitive = 1, counting
    // = 2). A bare aggregate not under an Alias, or an expression mixing aggregate classes, cannot
    // be split cleanly -> bail (None).
    val classified = aggExpressions.map { ne =>
      val contained = ne.collect { case ae: AggregateExpression => ae }
      if (contained.isEmpty) Some((ne, 0))
      else ne match {
        case Alias(ae: AggregateExpression, _) =>
          Some((ne, if (isDuplicateInsensitive(ae)) 1 else 2))
        case _ => None
      }
    }
    if (classified.exists(_.isEmpty)) return None
    val cats = classified.flatten
    val groupResultExprs = cats.collect { case (ne, 0) => ne }
    val distinctResultExprs = cats.collect { case (ne, 1) => ne }
    val additiveResultExprs = cats.collect { case (ne, 2) => ne }
    if (distinctResultExprs.isEmpty || additiveResultExprs.isEmpty) return None

    // Fresh per-half group-key aliases, used only for the recombine join (null-safe so NULL group
    // keys match like GROUP BY). The distinct half also carries the original grouping-derived
    // result expressions (original exprIds) for the final projection.
    val distinctJoinKeys = grouping.map(g => Alias(g, "cjsplit_gk")())
    val additiveJoinKeys = grouping.map(g => Alias(g, "cjsplit_gk")())

    val distinctHalf =
      Aggregate(grouping, distinctJoinKeys ++ groupResultExprs ++ distinctResultExprs, agg.child)
    val additiveHalf = Aggregate(grouping, additiveJoinKeys ++ additiveResultExprs, agg.child)

    val distinctRewritten = rewriteOrFallback(
      distinctHalf, grouping, distinctHalf.aggregateExpressions, projectList, join)
    val additiveRewritten = rewriteOrFallback(
      additiveHalf, grouping, additiveHalf.aggregateExpressions, projectList, join)
    if ((distinctRewritten eq distinctHalf) || (additiveRewritten eq additiveHalf)) {
      // At least one half was not accelerated - do not double the join.
      return None
    }

    val joinCond = distinctJoinKeys.zip(additiveJoinKeys).map {
      case (l, r) => EqualNullSafe(l.toAttribute, r.toAttribute): Expression
    }.reduceOption(And)
    val recombined = Join(distinctRewritten, additiveRewritten, Inner, joinCond, JoinHint.NONE)
    // Project the original aggregate output (same exprIds, original order), dropping the helper
    // join-key columns from each half.
    logInfo("new aggregate (mixed-distinct split)")
    Some(Project(aggExpressions.map(_.toAttribute), recombined))
  }

  /**
   * One mergeable aggregate slot of a LEFT OUTER split: the original aggregate result expression
   * (an Alias over a single count/sum/min/max) plus how its per-half partial results combine in
   * the final merge aggregate (count(*)/count(x)/sum -> SUM, min -> MIN, max -> MAX).
   */
  private sealed trait LoMerge
  private case object LoSum extends LoMerge
  private case object LoMin extends LoMerge
  private case object LoMax extends LoMerge
  // AVG is mergeable as two partials [sum(x), count(x)] per half, recombined as
  // SUM(sums)/SUM(counts) in the merge. Only used when the average's output is DoubleType (byte/
  // short/int/long/float/double inputs), where Average == Divide(sum.cast, count.cast) exactly;
  // DECIMAL/interval averages keep the safe fallback (no exact precision parity).
  private case object LoAvg extends LoMerge

  /**
   * Rewrites `Aggregate(g, aggs, A OUTER JOIN B)` (LEFT/RIGHT/FULL) using the decomposition
   *
   *   A LEFT  JOIN B  ==  (A INNER JOIN B)  U  (A LEFT ANTI JOIN B, B-cols -> typed NULL)
   *   A FULL  JOIN B  ==  (A INNER JOIN B)  U  (A LEFT ANTI JOIN B, B-cols -> typed NULL)
   *                                         U  (B LEFT ANTI JOIN A, A-cols -> typed NULL)
   *
   * so the aggregate splits into a matched half over the inner join (which reuses the existing
   * count-join rewrite via [[rewriteOrFallback]]) and one unmatched half per null-extended side
   * over an anti join (a plain aggregate - the anti join has no fan-out). The per-group partial
   * results are UNION-ed and re-aggregated per group, merging count(*)/count(x)/sum with SUM, min
   * with MIN and max with MAX. RIGHT OUTER is normalised to LEFT OUTER by swapping the sides; FULL
   * OUTER adds the symmetric third (B-only) half.
   *
   * Correctness rests on three points:
   *  - the anti join(s) keep exactly the rows that get NULL-extended by the outer join, so the
   *    halves partition the outer join's rows (LEFT: matched + A-only; FULL: + B-only);
   *  - in each unmatched half every column of the null-extended side (in BOTH grouping keys and
   *    aggregate arguments) is projected to a typed NULL literal, so count(x)=0, sum/min/max(x)=
   *    NULL, count(*) still counts the row, and such a grouping key collapses to the NULL group -
   *    exactly the outer join's NULL extension; the preserved side's aggregates see real values;
   *  - the merge re-aggregates the union of the partials and casts each result back to the
   *    original aggregate's type, preserving both the output exprIds and the output schema.
   *
   * AVG is supported when its output is DoubleType (non-decimal, non-interval inputs): it is
   * carried as two partials [sum(x), count(x)] per half and recombined as SUM(sums)/SUM(counts).
   * Returns None (fall back to the original plan) for a missing join condition, any non-mergeable
   * aggregate (DECIMAL/interval AVG, DISTINCT/percentile/stddev/FILTER, nested/mixed aggregates, a
   * bare un-aliased aggregate), or when the matched half does not accelerate (so the split would
   * only multiply the join with no benefit), and whenever the merged plan drops a required input.
   */
  private def tryRewriteOuter(
      agg: Aggregate,
      grouping: Seq[Expression],
      aggExpressions: Seq[NamedExpression],
      projectList: Seq[NamedExpression],
      join: Join): Option[LogicalPlan] = {
    if (!conf.yannakakisEnabled) return None
    // Normalise RIGHT OUTER to LEFT OUTER by swapping the join sides (always sound). FULL OUTER is
    // kept as-is and handled with an extra (B-only) anti half below. A conditionless (cartesian)
    // outer join is not handled.
    val (normJoin, isFullOuter) = join.joinType match {
      case LeftOuter => (join, false)
      case RightOuter =>
        (Join(join.right, join.left, LeftOuter, join.condition,
          JoinHint(join.hint.rightHint, join.hint.leftHint)), false)
      case FullOuter => (join, true)
      case _ => return None
    }
    if (normJoin.condition.isEmpty) return None
    val left = normJoin.left
    val right = normJoin.right
    val cond = normJoin.condition
    val hint = normJoin.hint
    val bOutputSet = right.outputSet

    // Classify each result expression: a grouping-derived expression (no aggregate), or an Alias
    // over a single mergeable aggregate (count/sum/min/max, or avg with DoubleType output).
    // Anything else (DECIMAL/interval avg, DISTINCT/percentile/stddev, FILTER, or an expression
    // mixing/nesting aggregates) makes the split bail.
    def mergeKindOf(ae: AggregateExpression): Option[LoMerge] =
      if (ae.isDistinct || ae.filter.isDefined) None
      else ae.aggregateFunction match {
        case _: Count => Some(LoSum)
        case _: Sum => Some(LoSum)
        case _: Min => Some(LoMin)
        case _: Max => Some(LoMax)
        // AVG only when its output is DoubleType (non-decimal, non-interval inputs); see [[LoAvg]].
        case avg: Average if avg.dataType == DoubleType => Some(LoAvg)
        case _ => None
      }
    val classified: Seq[Option[(NamedExpression, Option[LoMerge])]] = aggExpressions.map { ne =>
      val contained = ne.collect { case ae: AggregateExpression => ae }
      if (contained.isEmpty) Some((ne, None)) // grouping-derived
      else ne match {
        case Alias(ae: AggregateExpression, _) if contained.size == 1 =>
          mergeKindOf(ae).map(k => (ne, Some(k)))
        case _ => None
      }
    }
    if (classified.exists(_.isEmpty)) return None
    val cats = classified.flatten
    // Aggregate slots (Alias over a mergeable aggregate), in result order.
    val aggSlots: Seq[(NamedExpression, LoMerge)] =
      cats.collect { case (ne, Some(k)) => (ne, k) }
    if (aggSlots.isEmpty) return None // no aggregate to merge - nothing to do

    // A slot contributes `slotWidth` partial columns to each half: 1 for sum/min/max, 2 =
    // [sum(x), count(x)] for avg. `remap` is toInner for the matched half (routed through the
    // count-join, which fan-out-weights both sum and count), identity for the anti halves.
    def slotWidth(kind: LoMerge): Int = kind match { case LoAvg => 2; case _ => 1 }
    def slotPartials(ne: NamedExpression, kind: LoMerge,
                     remap: Expression => Expression): Seq[Expression] = {
      val child = ne.asInstanceOf[Alias].child
      kind match {
        case LoAvg =>
          val x = remap(child.asInstanceOf[AggregateExpression]
            .aggregateFunction.asInstanceOf[Average].child)
          Seq(Sum(x).toAggregateExpression(), Count(x :: Nil).toAggregateExpression())
        case _ => Seq(remap(child))
      }
    }

    val innerJoin = Join(left, right, Inner, cond, hint)
    // Re-point every reference to the INNER join's output attributes. The LEFT join marks B's
    // columns nullable; the inner half must see them with their real (non-nullable) nullability,
    // or e.g. count(b.x) would compile to a NULL-skipping form that references raw b.x and the
    // count-join (which folds sum/count of b.x into its right aggregate) would then have dropped
    // it. Attribute exprIds are preserved across join types, so a by-exprId remap suffices.
    val innerAttrs = innerJoin.output.map(a => a.exprId -> a).toMap
    def toInner[T <: Expression](e: T): T = e.transformUp {
      case a: Attribute => innerAttrs.getOrElse(a.exprId, a)
    }.asInstanceOf[T]
    val innerProjectList = projectList.map(toInner)
    val innerGrouping = grouping.map(toInner)

    // Explicit per-half group-key columns (so the union/merge can group even when a key is not in
    // the SELECT list). Fresh exprIds; null-safe handling happens via GROUP BY in the merge.
    val gkMatched = innerGrouping.map(g => Alias(g, "lojoin_gk")())
    // Fresh exprIds for the matched aggregate slots too, so the union's output exprIds are all
    // fresh and never collide with the original output exprIds re-used by the final merge.
    val matchedAggExprs = aggSlots.flatMap { case (ne, k) =>
      slotPartials(ne, k, e => toInner(e)).map(p => Alias(p, "lojoin_agg")())
    }
    val matchedResultExprs = gkMatched ++ matchedAggExprs
    val matchedHalf =
      Aggregate(innerGrouping, matchedResultExprs, Project(innerProjectList, innerJoin))
    val matchedRewritten =
      rewriteOrFallback(matchedHalf, innerGrouping, matchedResultExprs, innerProjectList, innerJoin)
    if (matchedRewritten eq matchedHalf) {
      // The inner half was not accelerated: do not replace one join with two (matched inner +
      // unmatched anti) for no benefit.
      return None
    }

    // Unmatched half: project B's columns to typed NULL in the project list, keeping each
    // attribute's exprId so the grouping/aggregate expressions still resolve.
    val antiProjectList: Seq[NamedExpression] = projectList.map {
      case attr: Attribute if bOutputSet.contains(attr) =>
        Alias(Literal(null, attr.dataType), attr.name)(exprId = attr.exprId)
      case ne =>
        ne.transformDown {
          case a: Attribute if bOutputSet.contains(a) => Literal(null, a.dataType)
        }.asInstanceOf[NamedExpression]
    }
    val antiJoin = Join(left, right, LeftAnti, cond, hint)
    val antiChild = Project(antiProjectList, antiJoin)
    val gkAnti = grouping.map(g => Alias(g, "lojoin_gk")())
    // Rebuild each aggregate slot's partials with fresh exprIds (the union branches must be
    // disjoint). The anti join has no fan-out, so the partials are plain aggregates.
    val antiAggExprs = aggSlots.flatMap { case (ne, k) =>
      slotPartials(ne, k, identity).map(p => Alias(p, "lojoin_agg")())
    }
    val antiResultExprs = gkAnti ++ antiAggExprs
    val antiHalf = Aggregate(grouping, antiResultExprs, antiChild)

    // FULL OUTER only: the symmetric B-only half - B rows with no A match, A columns -> typed NULL.
    // `Join(right, left, LeftAnti, cond)` keeps exactly the B rows the FULL join NULL-extends on A.
    val rightAntiHalf: Option[LogicalPlan] = if (isFullOuter) {
      val aOutputSet = left.outputSet
      val rightAntiProjectList: Seq[NamedExpression] = projectList.map {
        case attr: Attribute if aOutputSet.contains(attr) =>
          Alias(Literal(null, attr.dataType), attr.name)(exprId = attr.exprId)
        case ne =>
          ne.transformDown {
            case a: Attribute if aOutputSet.contains(a) => Literal(null, a.dataType)
          }.asInstanceOf[NamedExpression]
      }
      val rightAntiJoin = Join(right, left, LeftAnti, cond,
        JoinHint(hint.rightHint, hint.leftHint))
      val rightAntiChild = Project(rightAntiProjectList, rightAntiJoin)
      val gkRightAnti = grouping.map(g => Alias(g, "lojoin_gk")())
      val rightAntiAggExprs = aggSlots.flatMap { case (ne, k) =>
        slotPartials(ne, k, identity).map(p => Alias(p, "lojoin_agg")())
      }
      Some(Aggregate(grouping, gkRightAnti ++ rightAntiAggExprs, rightAntiChild))
    } else None

    val union = Union(matchedRewritten :: antiHalf :: rightAntiHalf.toList)
    val unionGk = union.output.take(grouping.size)
    val unionAggParts = union.output.drop(grouping.size)

    // Final merge: group by the union's group-key columns; re-aggregate the partials and cast
    // each back to the original aggregate's type. Group-derived result expressions are rebuilt
    // over the union's group keys. All outputs keep the ORIGINAL aggregate's exprIds.
    var aggIdx = 0
    val mergeResultExprs: Seq[NamedExpression] = cats.map {
      case (ne, None) =>
        var rewritten: Expression = ne
        grouping.zip(unionGk).foreach { case (g, uk) =>
          rewritten = rewritten.transformDown { case e if e.semanticEquals(g) => uk }
        }
        val out = ne.toAttribute
        Alias(rewritten match {
          case Alias(child, _) => child
          case other => other
        }, out.name)(exprId = out.exprId)
      case (ne, Some(kind)) =>
        val parts = (0 until slotWidth(kind)).map(i => unionAggParts(aggIdx + i))
        aggIdx += slotWidth(kind)
        val merged: Expression = kind match {
          case LoSum => Sum(parts(0)).toAggregateExpression()
          case LoMin => Min(parts(0)).toAggregateExpression()
          case LoMax => Max(parts(0)).toAggregateExpression()
          case LoAvg =>
            // Recombine across halves: SUM(per-half sums) / SUM(per-half counts). Mirrors the
            // count-join path's Average reconstruction (Divide, casting to DoubleDecimal unless
            // both operands already accept DoubleType); the outer Cast yields the avg's type.
            val totalSum = Sum(parts(0)).toAggregateExpression()
            val totalCnt = Sum(parts(1)).toAggregateExpression()
            if (DoubleType.acceptsType(totalSum.dataType) &&
              DoubleType.acceptsType(totalCnt.dataType)) {
              Divide(totalSum, totalCnt)
            } else {
              Divide(Cast(totalSum, DoubleDecimal), Cast(totalCnt, DoubleDecimal))
            }
        }
        val out = ne.toAttribute
        Alias(Cast(merged, out.dataType), out.name)(exprId = out.exprId)
    }
    val merged = Aggregate(unionGk, mergeResultExprs, union)

    // Guard against the split dropping any required input (mirrors validateOrFallback).
    if (merged.collectFirst { case p if p.missingInput.nonEmpty => p }.nonEmpty) {
      logWarning("yannakakis OUTER split dropped required attributes; falling back")
      return None
    }
    logInfo(s"new aggregate (${if (isFullOuter) "full" else "left"}-outer split)")
    Some(merged)
  }

  /**
   * The rewrite assumes every attribute referenced by the new aggregate is still produced
   * by the rewritten join tree. If that assumption is violated (e.g. an attribute was
   * consumed by a count join without being carried), the plan would fail at physical
   * planning with "Couldn't find <attr>". Fall back to the original plan instead.
   */
  private def validateOrFallback(original: Aggregate, rewritten: LogicalPlan): LogicalPlan = {
    if (rewritten eq original) {
      original
    } else {
      val invalidNode = rewritten.collectFirst {
        case p if p.missingInput.nonEmpty => p
      }
      invalidNode match {
        case Some(p) =>
          logWarning("yannakakis rewrite dropped attributes " + p.missingInput +
            " required by " + p.nodeName + "; falling back to the original plan")
          original
        case None => rewritten
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

  /**
   * Duplicate-insensitive aggregates produce identical results over the semijoin-reduced
   * join: the duplicates introduced by join fan-out are exactly what they ignore. This
   * covers min/max (the 0MA class) and DISTINCT count/sum/average. Queries whose
   * aggregates are all duplicate-insensitive can use the pure-semijoin path and never
   * need count multiplication.
   */
  def isDuplicateInsensitive(expr: Expression): Boolean = {
    expr match {
      case Alias(child, _) => isDuplicateInsensitive(child)
      case ToPrettyString(child, _) => isDuplicateInsensitive(child)
      case AggregateExpression(aggFn, _, isDistinct, _, _) => aggFn match {
        case Min(_) | Max(_) => true
        case Count(_) => isDistinct
        case Sum(_, _) => isDistinct
        case Average(_, _) => isDistinct
        // Inherently duplicate-insensitive regardless of DISTINCT: set collection,
        // distinct-count sketch, and idempotent bitwise reductions. (collect_list and
        // bit_xor are duplicate-SENSITIVE and intentionally excluded.)
        case _: CollectSet | _: HyperLogLogPlusPlus | _: BitAndAgg | _: BitOrAgg => true
        case _ => false
      }
      case _: Attribute => true
      case _ => false
    }
  }

  /**
   * Desugars FILTER-clause aggregates into equivalent CASE WHEN inputs so the rest of the
   * rule never needs to reason about filters:
   *   sum(x) FILTER (WHERE p)    => sum(CASE WHEN p THEN x END)
   *   count(xs) FILTER (WHERE p) => count(CASE WHEN p AND xs not null THEN 1 END)
   * Only NULL-ignoring aggregate functions are desugared (the 1-valued CASE form for
   * count keeps the count rewrites, which consume the count input, correct on all paths).
   * Filtered aggregates of any other function keep their filter and are rejected by the
   * bail-out in rewritePlan.
   */
  def desugarFilteredAggregates(
      resultExpressions: Seq[NamedExpression]): Seq[NamedExpression] = {
    def filteredInput(pred: Expression, value: Expression): Expression =
      CaseWhen(Seq((pred, value)), None)
    resultExpressions.map(_.transformDown {
      case ae @ AggregateExpression(aggFn, _, false, Some(pred), _) =>
        aggFn match {
          case Sum(child, evalMode) =>
            ae.copy(aggregateFunction = Sum(filteredInput(pred, child), evalMode),
              filter = None)
          case Min(child) =>
            ae.copy(aggregateFunction = Min(filteredInput(pred, child)), filter = None)
          case Max(child) =>
            ae.copy(aggregateFunction = Max(filteredInput(pred, child)), filter = None)
          case Average(child, evalMode) =>
            ae.copy(aggregateFunction = Average(filteredInput(pred, child), evalMode),
              filter = None)
          case cnt: Count =>
            val notNullPred = (pred +: cnt.children.filter(_.nullable)
              .map(IsNotNull(_): Expression)).reduce(And)
            ae.copy(aggregateFunction = Count(filteredInput(notNullPred, Literal(1L))),
              filter = None)
          case _ => ae
        }
    }.asInstanceOf[NamedExpression])
  }
  def isCounting(expr: Expression): Boolean = {
    expr match {
      case Alias(child, name) => isCounting(child)
      case ToPrettyString(child, tz) => isCounting(child)
      case AggregateExpression(aggFn, mode, isDistinct, filter, resultId) => aggFn match {
        case Count(s) => !isDistinct
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
        case Percentile(_, _, _, _, _, _) => !isDistinct
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
        case Sum(_, _) => !isDistinct
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
        case Average(_, _) => !isDistinct
        case _ => false
      }
      case _ => false
    }
  }

  // VAR_POP/VAR_SAMP/STDDEV_POP/STDDEV_SAMP - 2nd central moments, fan-out-sensitive, handled via
  // count-weighted power sums (see rewriteGuardedAggregate). Higher moments (skew/kurtosis) and
  // covariance/corr/regression are not (yet) handled and fall back.
  def isMoment(expr: Expression): Boolean = {
    expr match {
      case Alias(child, _) => isMoment(child)
      case ToPrettyString(child, _) => isMoment(child)
      case Multiply(l, r, _) => isMoment(l) || isMoment(r)
      case Divide(l, r, _) => isMoment(l) || isMoment(r)
      case Add(l, r, _) => isMoment(l) || isMoment(r)
      case Subtract(l, r, _) => isMoment(l) || isMoment(r)
      case AggregateExpression(aggFn, _, isDistinct, _, _) => aggFn match {
        case _: VariancePop | _: VarianceSamp | _: StddevPop | _: StddevSamp => !isDistinct
        case _: Skewness | _: Kurtosis => !isDistinct
        case _: CovPopulation | _: CovSample | _: Corr => !isDistinct
        case _: RegrSlope | _: RegrIntercept | _: RegrR2 | _: RegrSXY => !isDistinct
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
      // Anything that is not an inner-join (or attribute-only projection over one) is
      // intentionally treated as an opaque leaf relation. In particular a LeftSemi/LeftAnti
      // join, or a not-yet-decorrelated IN/EXISTS Filter subquery (TPC-H Q16's
      // `ps_suppkey NOT IN (...)`), is kept whole as this leaf's planReference - the rewrite
      // never reaches inside it, which preserves its semantics.
      case _ =>
        (Seq(plan), ExpressionSet())
    }
  }
}

class HGEdge(val vertices: Set[String], val name: String, val planReference: LogicalPlan,
             val attributeToVertex: mutable.Map[ExprId, String]) {
  // Sort the output before toMap so a vertex with several output attributes (e.g. a bag whose
  // materialized join exposes r_a AND u_a, both mapped to vertex a) resolves to a DETERMINISTIC
  // attribute. outputSet is hash-ordered, so without this toMap would keep whichever attribute
  // came last per JVM run, producing run-to-run-varying (and occasionally wrong) cyclic plans.
  // For a normal single-relation edge there is one attribute per vertex, so this is a no-op.
  val vertexToAttribute: Map[String, Attribute] = planReference.outputSet.toSeq
    .sortBy(_.exprId.id)
    .map(att => (attributeToVertex.getOrElse(att.exprId, null), att))
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

  // Equi-join key between two vertex attributes. Only cast the right side when the types
  // genuinely differ: a no-op Cast (e.g. cast(int as int)) is never semanticEqual to its bare
  // child, so it defeats HashPartitioning.satisfies and makes EnsureRequirements insert a
  // redundant shuffle even when the child is already partitioned on that key.
  private def equiKey(left: Expression, right: Expression): Expression =
    if (right.dataType == left.dataType) EqualTo(left, right)
    else EqualTo(left, Cast(right, left.dataType))

  // HTNode and HGEdge use identity hashCodes, so Set iteration order varies between JVM
  // runs, which makes join order (and thus plans and results) nondeterministic. Always
  // iterate children in a stable order based on the edge names (E1, E2, ...).
  private def orderedChildren: Seq[HTNode] = {
    children.toSeq.sortBy(c => {
      val name = c.edges.map(_.name).min
      (name.length, name)
    })
  }

  def buildBottomUpJoins: LogicalPlan = {
    val edge = edges.head
    val scanPlan = edge.planReference
    val vertices = edge.vertices
    var prevJoin: LogicalPlan = scanPlan
    for (c <- orderedChildren) {
      val childEdge = c.edges.head
      val childVertices = childEdge.vertices
      val overlappingVertices = vertices intersect childVertices
      val joinConditions = overlappingVertices
        .map(vertex => (edge.vertexToAttribute(vertex), childEdge.vertexToAttribute(vertex)))
        .map(atts => equiKey(atts._1, atts._2))
        .reduceLeft((e1, e2) => And(e1, e2).asInstanceOf[Expression])
      val semijoin = Join(prevJoin, c.buildBottomUpJoins,
        LeftSemi, Option(joinConditions), JoinHint(Option.empty, Option.empty))
      prevJoin = semijoin
    }
    prevJoin
  }

  /** All attributes produced by this node's subtree. */
  def subtreeOutputSet: AttributeSet =
    children.foldLeft(edges.map(_.planReference.outputSet).reduce(_ ++ _))(
      (acc, c) => acc ++ c.subtreeOutputSet)

  /**
   * Single bottom-up pass for non-guarded duplicate-insensitive aggregates
   * (DISTINCT count/sum/avg, min/max). The attributes in `needed` (the group attrs and the
   * distinct/aggregate arguments) are carried to this node by INNER-joining every child
   * subtree that contains one of them, while subtrees that contain none are LEFT-SEMI joined
   * (pure dangling-tuple reduction). After the carry, the result is de-duplicated on the
   * carried attributes plus this node's own join keys: because the aggregate is
   * duplicate-insensitive, this DISTINCT is lossless and bounds intermediate size to the
   * distinct (needed, join-key) projection. No count multiplication is performed - the
   * original aggregate runs over the carried (group, distinct-arg) pairs at the top.
   */
  def buildBottomUpDistinctJoin(needed: AttributeSet, isTop: Boolean = false): LogicalPlan = {
    val edge = edges.head
    val vertices = edge.vertices
    val nodeKeyAttrs = AttributeSet(vertices.map(v => edge.vertexToAttribute(v)))
    var plan: LogicalPlan = edge.planReference
    var carried = false
    for (c <- orderedChildren) {
      val childEdge = c.edges.head
      val overlappingVertices = vertices intersect childEdge.vertices
      val joinConditions = overlappingVertices
        .map(vertex => (edge.vertexToAttribute(vertex), childEdge.vertexToAttribute(vertex)))
        .map(atts => equiKey(atts._1, atts._2))
        .reduceLeft((e1, e2) => And(e1, e2).asInstanceOf[Expression])
      if ((needed intersect c.subtreeOutputSet).nonEmpty) {
        // child carries a needed attribute: inner-join to bring it up
        plan = Join(plan, c.buildBottomUpDistinctJoin(needed),
          Inner, Option(joinConditions), JoinHint(Option.empty, Option.empty))
        carried = true
      } else {
        // off-path subtree: reduce dangling tuples only
        plan = Join(plan, c.buildBottomUpJoins,
          LeftSemi, Option(joinConditions), JoinHint(Option.empty, Option.empty))
      }
    }
    // De-duplicate on the needed attributes plus this node's join keys (so the parent can
    // join), bounding intermediate size to the distinct projection. Lossless for
    // duplicate-insensitive aggregates. Skipped at the top node: the caller's final
    // Aggregate (a duplicate-insensitive count/sum/avg DISTINCT grouped by G) already
    // dedups the same columns, so the top DISTINCT would be a redundant extra shuffle.
    if (carried && !isTop) {
      val keep = plan.output.filter(a => needed.contains(a) || nodeKeyAttrs.contains(a))
      if (keep.nonEmpty) {
        plan = Aggregate(keep, keep, plan)
      }
    }
    plan
  }

  // scalastyle:off argcount
  def buildBottomUpJoinsCounting(aggregateAttributes: AttributeSet,
                                 groupingExpressions: Seq[NamedExpression],
                                 aggExpressions: Seq[AggregateExpression],
                                 lastAggMap: mutable.HashMap[Attribute, AggregateExpression],
                                 lastSumMap: mutable.HashMap[Attribute, Attribute],
                                 nextMultiplicationMap: mutable.HashMap[Attribute, Expression],
                                 pendingProductSumSet: mutable.HashSet[Attribute],
                                 pendingProductAccountedAttrs: mutable.HashMap[Attribute,
                                   AttributeSet],
                                 pendingProductOriginalAttrs: mutable.HashMap[Attribute,
                                   AttributeSet],
                                 keyRefs: Seq[Seq[Expression]],
                                 uniqueConstraints: Seq[Seq[Expression]], groupInLeaves: Boolean,
                                 usePhysicalCountJoin: Boolean = false,
                                 crossRelationFilters: mutable.Set[Expression] =
                                   mutable.Set.empty,
                                 conflictingProductAttrs: Set[Attribute] = Set.empty):
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
    for (c <- orderedChildren) {
      val childEdge = c.edges.head
      val childVertices = childEdge.vertices
      val overlappingVertices = vertices intersect childVertices
      val (bottomUpJoins, childCountExpr, rightPlanIsLeaf, childWasSemijoined) =
        c.buildBottomUpJoinsCounting(aggregateAttributes,
          groupingExpressions, aggExpressions, lastAggMap, lastSumMap,
          nextMultiplicationMap, pendingProductSumSet, pendingProductAccountedAttrs,
          pendingProductOriginalAttrs, keyRefs, uniqueConstraints,
          groupInLeaves, usePhysicalCountJoin = usePhysicalCountJoin,
          crossRelationFilters = crossRelationFilters,
          conflictingProductAttrs = conflictingProductAttrs)

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
        .map(atts => equiKey(atts._1, atts._2))
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

      // The genuine GROUP BY keys present on the current right plan.
      val realGroupKeys = groupingExpressions.filter(
        groupExpr => {groupExpr.references.subsetOf(rightPlan.outputSet)}
      )

      // Right-side attributes carried through this join only to keep product-aggregate and
      // cross-relation-filter references available higher up the tree - NOT real grouping keys.
      // The grouping passed to the CountJoin below is realGroupKeys ++ carriedAttributes.
      var carriedAttributes = Seq.empty[NamedExpression]

      // For product aggregates (SUM(A*B) where A and B are from different relations),
      // we need to carry attributes through the tree until both are available.
      // Add right-side product attributes to grouping if the product can't be computed yet,
      // or if this specific product is marked as conflicting.
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

            // Check if this is a product aggregate (multiple non-count attrs)
            val productAttrs = aggRefs.filter(a =>
              !a.name.startsWith("c#") && a.name != "c")
            val isProductAgg = productAttrs.size >= 2

            // Check if THIS SPECIFIC product is conflicting (Phase 2: per-product check)
            val isConflictingProduct = conflictingProductAttrs.contains(agg.resultAttribute)

            // Carry through if: product not yet computable OR this product conflicts with others
            val needsCarryThrough = (!isProductAggHere && refsNotYetAvailable.nonEmpty) ||
              (isConflictingProduct && isProductAgg && refsOnRight.nonEmpty)

            if (needsCarryThrough) {
              // Add right-side refs to grouping to carry them through
              refsOnRight.foreach(att => {
                val alreadyGrouped = (realGroupKeys ++ carriedAttributes).exists(
                  g => g.references.contains(att))
                if (!alreadyGrouped) {
                  val namedAtt = att.asInstanceOf[NamedExpression]
                  carriedAttributes = carriedAttributes :+ namedAtt
                  dbg(s"Added $att to grouping for product agg (carry through)")
                }
              })
            } else if (isProductAggHere && !aggRefs.exists(a =>
                groupingExpressions.exists(g => g.references.contains(a)))) {
              // Product can be computed here, but refs are NOT in global GROUP BY
              // We need to add right-side refs to grouping so the product expression
              // can reference them in the CountJoin aggregate.
              // We also need to ensure left-side refs are in grouping to prevent
              // collapsing of rows that have different left-side values.
              refsOnRight.foreach(att => {
                val alreadyGrouped = (realGroupKeys ++ carriedAttributes).exists(
                  g => g.references.contains(att))
                if (!alreadyGrouped) {
                  val namedAtt = att.asInstanceOf[NamedExpression]
                  carriedAttributes = carriedAttributes :+ namedAtt
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
            val alreadyGrouped = (realGroupKeys ++ carriedAttributes).exists(
              g => g.references.contains(att))
            if (!alreadyGrouped) {
              val namedAtt = att.asInstanceOf[NamedExpression]
              carriedAttributes = carriedAttributes :+ namedAtt
              dbg(s"Added $att to grouping for cross-relation filter (carry through)")
            }
          })
        }
      })

      // The grouping the CountJoin uses: the real GROUP BY keys plus the attributes carried for
      // products/filters. Byte-identical to the value the single mutable var accumulated before.
      val applicableGroupAttributes = realGroupKeys ++ carriedAttributes

      val join = if (usePhysicalCountJoin) {
        var applicableAggExpressions = Seq.empty[AggregateExpression]
        var multiplySumExpressions = Seq.empty[NamedExpression]

        def createMultiplication(a: Expression, b: Expression): Expression = {
          // `a` (a value, partial sum, or product) is multiplied by a Long count `b` to account
          // for join fan-out; the result feeds a SUM. It must be computed in the type SUM(a)
          // would use - otherwise a narrow type (e.g. Int) overflows where vanilla Spark's
          // promoted Sum accumulator would not (a wrong, wrapped result with ANSI off; a thrown
          // overflow with ANSI on). This mirrors vanilla SUM semantics: summing `a` exactly
          // `count` times. DecimalType keeps its existing handling.
          a.dataType match {
            case _: DecimalType =>
              val multiplication = if (a.dataType.acceptsType(b.dataType)) {
                Multiply(a, b)
              } else {
                Multiply(a, Cast(b, DecimalType(20, 0)))
              }
              if (multiplication.dataType == a.dataType) multiplication
              else Cast(multiplication, a.dataType)
            case _ =>
              val wideType = Sum(a).dataType
              Multiply(Cast(a, wideType), Cast(b, wideType))
          }
        }

        def sumOrCountCase(agg: AggregateExpression) = {
          dbg(s"sumOrCountCase agg=${agg.aggregateFunction}")
          dbg(s"  resultAttr=${agg.resultAttribute}")
          val keyStr = lastSumMap.keys.map(k =>
            s"${k.name}#${k.exprId.id}").mkString(",")
          dbg(s"  lastSumMap.keys=$keyStr")
          val pendStr = pendingProductSumSet.map(k =>
            s"${k.name}#${k.exprId.id}").mkString(",")
          dbg(s"  pendingProductSumSet=$pendStr")
          if (lastSumMap.contains(agg.resultAttribute)) {
            dbg("lastSumMap contains " + agg.resultAttribute)
            val lastSumAtt = lastSumMap(agg.resultAttribute)
            dbg(s"  lastSumAtt=$lastSumAtt")

            val rOut = rightPlan.outputSet.map(a =>
              s"${a.name}#${a.exprId.id}").mkString(",")
            dbg(s"  rightOut=$rOut")
            val lOut = leftPlan.outputSet.map(a =>
              s"${a.name}#${a.exprId.id}").mkString(",")
            dbg(s"  leftOut=$lOut")
            val inRight = rightPlan.outputSet.contains(lastSumAtt)
            val inLeft = leftPlan.outputSet.contains(lastSumAtt)
            dbg(s"  inRight=$inRight inLeft=$inLeft")

            // Check pending status ONCE before RIGHT/LEFT propagation modifies the set.
            // This avoids the bug where RIGHT removes from set, then LEFT misses it.
            val isPendingProduct = pendingProductSumSet.contains(agg.resultAttribute)
            dbg(s"isPendingProduct=$isPendingProduct for ${agg.resultAttribute}")

            if (rightPlan.outputSet.contains(lastSumAtt)) {
              //         |
              //       Project(ac<-a*c)
              //         |
              //       Y, a<-SUM(a)
              //      /   \
              //    Y(c)      Z(a)
              //
              dbg(s"RIGHT propagation isPending=$isPendingProduct")
              val hasLeftCount = leftPlan.outputSet.contains(leftCountAttribute)
              dbg(s"  leftCount=$leftCountAttribute hasLeftCount=$hasLeftCount")

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

              // When a pending product is SUMmed on the right side, it's aggregated
              // and no longer pending (final aggregate will just SUM these sums)
              if (isPendingProduct) {
                pendingProductSumSet.remove(agg.resultAttribute)
                dbg(s"SUMmed pending product on right: $lastSumAtt")
              }
            }

            if (leftPlan.outputSet.contains(lastSumAtt)) {
              //         |
              //       Project(ac<-a*(sc/Y.c))
              //         |
              //       Y, sc<-SUM(Z.c)*Y.c
              //      /     \
              //    Y(a,c)     Z(c)

              // Use isPendingProduct checked earlier (before RIGHT modified the set)
              dbg(s"LEFT propagation isPending=$isPendingProduct for $lastSumAtt")

              // For pending products: skip multiplication if right subtree contains
              // any of the product's original attributes. This means the right subtree
              // is "relevant" to this product - its rows contribute to the product's SUM.
              // We use exprId matching which is stable (unlike outputSet comparison).
              val productOriginalAttrs = pendingProductOriginalAttrs.get(agg.resultAttribute)
              val rightContainsProductAttr = productOriginalAttrs.exists { origAttrs =>
                val productExprIds = origAttrs.map(_.exprId).toSet
                rightPlan.outputSet.exists(a => productExprIds.contains(a.exprId))
              }
              dbg(s"  productOriginalAttrs=$productOriginalAttrs " +
                s"rightContainsProductAttr=$rightContainsProductAttr")

              val skipMultiplication = isPendingProduct && rightContainsProductAttr
              if (skipMultiplication) {
                // Skip: right-side tables are already in the pending product's count
                dbg(s"LEFT propagation: SKIPPING (right already accounted) $lastSumAtt")
                lastSumMap.put(agg.resultAttribute, lastSumAtt)
              } else {
                // Multiply by right count (new tables not yet accounted for)
                val countRightAgg = if (rightPlanIsLeaf) {
                  dbg(s"LEFT propagation: using COUNT(1) for leaf right")
                  Count(Literal(1L)).toAggregateExpression()
                } else {
                  dbg(s"LEFT propagation: using SUM($rightCountAttribute) for non-leaf right")
                  Sum(rightCountAttribute).toAggregateExpression()
                }
                applicableAggExpressions = applicableAggExpressions :+ countRightAgg

                val newSum = Alias(createMultiplication(lastSumAtt,
                  countRightAgg.resultAttribute), "sum")()

                multiplySumExpressions = multiplySumExpressions :+ newSum
                dbg(s"LEFT propagation: $lastSumAtt * count -> $newSum")
                lastSumMap.put(agg.resultAttribute, newSum.toAttribute)
                dbg(s"  updated lastSumMap -> ${newSum.toAttribute}")
                // Note: We no longer update pendingProductAccountedAttrs here.
                // The exprId-based relevance check doesn't need tracking of accounted tables.
              }
            }
          }
          else {
            dbg("lastSumMap does not contain " + agg.resultAttribute)
            // SUM/COUNT aggregate has not yet occurred somewhere in the tree -
            // check if it starts here
            if (agg.references.subsetOf(rightPlan.outputSet)) {
              dbg("agg.references.subsetOf(rightPlan.outputSet)")
              dbg(s"  Single-column SUM on right: ${agg.aggregateFunction.children.head}")
              dbg(s"  agg.references: ${agg.references}")
              dbg(s"  rightPlanIsLeaf: $rightPlanIsLeaf")

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

              // Check if the SUM's child is entirely within the grouping attributes.
              // If so, SUM(x) grouped by x = x, which is wrong - we'd lose the aggregation.
              // In this case, defer to final aggregate where proper count multiplication
              // will be applied.
              val sumChild = agg.aggregateFunction.children.head
              val sumChildRefs = sumChild.references
              val sumChildInGrouping = sumChildRefs.nonEmpty && sumChildRefs.forall(r =>
                applicableGroupAttributes.exists(g => g.references.contains(r)))

              // Check if this is a product aggregate (SUM with multiple non-count attrs)
              val productAttrsInAgg = agg.references.filter(a =>
                !a.name.startsWith("c#") && a.name != "c")
              val isProductAgg = productAttrsInAgg.size >= 2

              // Check if THIS SPECIFIC product is conflicting (Phase 2: per-product check)
              val isConflictingProduct = conflictingProductAttrs.contains(agg.resultAttribute)

              if (sumChildInGrouping) {
                dbg(s"Skipping SUM at CountJoin - child $sumChild is in grouping, would be trivial")
                // Don't add to applicableAggExpressions - it will be computed at final aggregate
                // with count multiplication
              } else if (isConflictingProduct && isProductAgg) {
                // This specific product conflicts with others -
                // skip computing it here, will be handled at final aggregate
                dbg(s"Skipping product SUM due to conflict with other products: $agg")
              } else {
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
              // Cases to avoid: any product attr NOT in grouping (uncovered) which
              // means the attr varies within groups causing incorrect products.
              // This applies to BOTH left-side and right-side attributes.
              val rightAttrsNotInGrouping = refsOnRight.filterNot(a =>
                applicableGroupAttributes.exists(g => g.references.contains(a)))
              val hasUncoveredRightAttr = rightAttrsNotInGrouping.nonEmpty

              // Also check left-side product attributes
              val leftAttrsNotInGrouping = refsOnLeft.filterNot(a =>
                applicableGroupAttributes.exists(g => g.references.contains(a)))
              val hasUncoveredLeftAttr = leftAttrsNotInGrouping.nonEmpty

              if (hasUncoveredLeftAttr) {
                dbg(s"Left attrs not in grouping: $leftAttrsNotInGrouping")
              }

              // Left-side attrs don't need to be in grouping because CountJoin processes
              // each left row individually, so left-side values are naturally preserved.
              // Only right-side attrs need to be covered by grouping.
              //
              // GLOBAL CHECK: If there are multiple product aggregates with non-overlapping
              // attributes, there's potential for cross-branch grouping conflicts.
              // For example:
              // - sum(production_year * role_id) has attrs {production_year, role_id}
              // - sum((company_type_id * role_id) * kind_id) has
              //   {company_type_id, role_id, kind_id}
              //
              // When company_type_id is used for grouping (for the second product), it
              // affects the counts for the first product (which doesn't include company_type_id).
              // The join tree ordering is non-deterministic, so the grouping might be
              // introduced at different points. We must defer products when this conflict exists.
              val productAttrsSet = refsOnLeft ++ refsOnRight

              // Check if other products will add conflicting grouping at THIS or FUTURE joins.
              // Key insight: if there are OTHER uncommitted products that still need
              // attributes not yet available (not in combinedOutputSet), those products
              // will add grouping at future joins that could affect our count.
              //
              // Safe to compute when: all other uncommitted products have ALL their attrs
              // already in the combined output (so they won't add new grouping later).
              //
              // ANY uncommitted products with unseen attrs cause conflicts.
              // Even products with disjoint attrs affect count semantics because
              // they may add grouping at different join points depending on join order.
              val otherProductsHaveUnseenAttrs = aggExpressions.exists { otherAgg =>
                otherAgg.aggregateFunction match {
                  case Sum(child, _) if otherAgg != agg =>
                    val otherRefs = child.references.filter(a =>
                      !a.name.startsWith("c#") && a.name != "c")
                    // Is this other product already computed?
                    val otherAlreadyComputed = lastSumMap.contains(otherAgg.resultAttribute)
                    if (otherAlreadyComputed) {
                      false // Already computed, won't add new grouping
                    } else {
                      // Does this uncommitted product have attrs not yet available?
                      // If so, it will add grouping at a future join, affecting counts.
                      otherRefs.exists(a => !combinedOutputSet.contains(a))
                    }
                  case _ => false
                }
              }

              // Also check current grouping for foreign attributes
              val hasForeignGrouping = applicableGroupAttributes.exists { grp =>
                !grp.references.subsetOf(productAttrsSet)
              }

              // Note: We do NOT bypass foreign grouping check for synthetic superset products.
              // Even though synthetic superset intentionally groups by all product attrs,
              // the foreign grouping check is essential for correct count multiplication.
              // Products will be computed at the final aggregate where counts are correct.

              val hasConflict = hasForeignGrouping || otherProductsHaveUnseenAttrs

              // Phase 1-2: Per-product conflict check using conflict graph
              // The winner product (not in conflictingProductAttrs) is allowed to compute early
              // even if there are other products with unseen attrs - that's the whole point
              // of one-winner selection.
              val isConflictingProduct = conflictingProductAttrs.contains(agg.resultAttribute)

              // For independent products (not in conflictingProductAttrs):
              // - Only hasForeignGrouping matters
              // - otherProductsHaveUnseenAttrs doesn't affect them (disjoint attrs)
              // For conflicting products:
              // - Full hasConflict check applies
              val mustDeferForGrouping = if (isConflictingProduct) {
                // Conflicting product: full check
                hasConflict && !isLeafNode
              } else {
                // Independent product: only foreign grouping blocks it
                hasForeignGrouping && !isLeafNode
              }

              dbg(s"Conflict check for ${agg}: hasForeign=$hasForeignGrouping " +
                s"otherUnseen=$otherProductsHaveUnseenAttrs isLeaf=$isLeafNode " +
                s"mustDefer=$mustDeferForGrouping isConflicting=$isConflictingProduct")

              if (!SQLConf.get.yannakakisDeferProductsEnabled && !hasUncoveredRightAttr &&
                  !mustDeferForGrouping && !isConflictingProduct) {
                // Compute product early at this join (works for any number of attributes)
                dbg(s"Computing product early ($numProductAttrs attrs): ${agg}")
                dbg(s"  refsOnLeft=$refsOnLeft refsOnRight=$refsOnRight")
                dbg(s"  rightPlanIsLeaf=$rightPlanIsLeaf isLeafNode=$isLeafNode")
                dbg(s"  applicableGroupAttributes=${applicableGroupAttributes.map(_.toString)}")

                // Extract the inner expression of the Sum (e.g., role_id * info_type_id)
                val sumChild = agg.aggregateFunction.children.head

                // Check if right-side product attributes are grouped at THIS join
                val rightRefsGroupedHere = refsOnRight.exists(a =>
                  applicableGroupAttributes.exists(g => g.references.contains(a)))


                var productExpr: Expression = sumChild

                // Multiply by right count (handling grouping appropriately)
                if (rightPlanIsLeaf) {
                  // Right is a leaf: count rows from right side for fan-out
                  // Only needed if right side has product attributes (refsOnRight)
                  if (refsOnRight.nonEmpty) {
                    val countRightLeafAgg = Count(Literal(1L)).toAggregateExpression()
                    applicableAggExpressions = applicableAggExpressions :+ countRightLeafAgg
                    productExpr = createMultiplication(productExpr,
                      countRightLeafAgg.resultAttribute)
                  }
                } else {
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
                // Track this product's original attribute references for relevance check.
                // Note: We no longer track accountedAttrs - the exprId-based relevance check
                // uses the product's original attrs to determine if right subtree is relevant.
                val productOriginalRefs = agg.references.filter(a =>
                  !a.name.startsWith("c#") && a.name != "c")
                val origRefSet = AttributeSet(productOriginalRefs)
                pendingProductOriginalAttrs.put(agg.resultAttribute, origRefSet)
                dbg(s"Added pending product ($numProductAttrs attrs): $productExpr")
                dbg(s"  productAlias=${productAlias.toAttribute}")
                dbg(s"  originalRefs=${productOriginalRefs.map(_.name)}")
              } else {
                // Defer to final aggregate: uncovered right attr (right attr not in grouping)
                // or foreign grouping that needs proper count aggregation
                if (mustDeferForGrouping) {
                  dbg(s"Deferring product (foreign grouping, left has counts): ${agg}")
                  dbg(s"  groupAttrs=$applicableGroupAttributes productAttrs=$productAttrsSet")
                } else {
                  dbg(s"Deferring product (uncovered right attr: $rightAttrsNotInGrouping): ${agg}")
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
        dbg(s"leftPlan.output: ${leftPlan.output.map(a => s"${a.name}#${a.exprId.id}")}")
        dbg(s"right.output: ${right.output.map(a => s"${a.name}#${a.exprId.id}")}")
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
      for (c <- orderedChildren) {
        val node = c.findNodeContainingAttributes(aggAttributes)
        if (node != null) {
          return node
        }
      }
      null
    }
  }

  /**
   * Like findNodeContainingAttributes, but an attribute also counts as contained when the
   * node has a join-equivalent attribute (same hypergraph vertex). E.g. for TPC-H Q3 the
   * orders relation contains l_orderkey via the join equivalence l_orderkey = o_orderkey.
   */
  def findNodeContainingAttributesEquiv(aggAttributes: AttributeSet): HTNode = {
    val nodeAttributes = edges
      .map(e => e.planReference.outputSet)
      .reduce((e1, e2) => e1 ++ e2)
    val attributeToVertex = edges.head.attributeToVertex
    val nodeVertices = edges.flatMap(e => e.vertices)
    val covered = aggAttributes.forall(att =>
      nodeAttributes.contains(att) ||
        attributeToVertex.get(att.exprId).exists(v => nodeVertices.contains(v)))
    if (covered) {
      this
    } else {
      for (c <- orderedChildren) {
        val node = c.findNodeContainingAttributesEquiv(aggAttributes)
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

  def getAttributeToVertex: mutable.Map[ExprId, String] = attributeToVertex

  // Max number of relations sharing a single join-key equivalence class (a hypergraph vertex's
  // degree). >= 3 means a multi-way star on one key (e.g. 5 relations joined on UserId) - the
  // regime where the join intermediate explodes multiplicatively and the count-join wins big. Read
  // from the original edges (GYO works on copies), so it reflects the query's join structure.
  def maxKeyDegree: Int =
    edges.toSeq.flatMap(_.vertices).groupBy(identity).values.map(_.size).reduceOption(_ max _)
      .getOrElse(0)

  private var equivalenceClasses: Set[Set[Attribute]] = Set.empty

  // Track non-equality conditions that span multiple relations (cross-relation filters)
  // These need to be applied at the appropriate join point
  var crossRelationFilters: Seq[Expression] = Seq.empty

  // An equi-join edge is only valid when each side is a single attribute (possibly wrapped in
  // casts). For a compound/expression key (e.g. a + b = c, or substr(x) = y), .references.head
  // would pick an arbitrary attribute and build a wrong equivalence class, so route those to
  // the cross-relation-filter branch instead.
  def isSingleAttributeKey(e: Expression): Boolean = e match {
    case _: Attribute => true
    case c: Cast => isSingleAttributeKey(c.child)
    case _ => false
  }

  for (cond <- conditions) {
    if (RewriteJoinsAsSemijoins.DEBUG_LOGGING) {
      logWarning("condition: " + cond + ", refs: " + cond.references)
    }
    cond match {
      case EqualTo(lhs, rhs) if isSingleAttributeKey(lhs) && isSingleAttributeKey(rhs) =>
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

  // Iterate edges in a stable order (E1, E2, ...): HGEdge uses identity hashCodes, so raw Set
  // iteration order varies between JVM runs and would make the join tree shape nondeterministic.
  private def orderedEdges(es: mutable.Set[HGEdge]): Seq[HGEdge] =
    es.toSeq.sortBy(e => (e.name.length, e.name))

  // One round of GYO ear-removal over `gyoEdges`, attaching contained edges as children in
  // `treeNodes`. Returns (root, progress): `progress` is false when no ear could be removed
  // (the remaining edges form a mutually-irreducible cyclic component). This is the exact
  // loop body of flatGYO, factored out so the cyclic path can reuse it verbatim; flatGYO
  // itself is left untouched so the ACYCLIC behaviour is provably unchanged.
  private def gyoReduce(gyoEdges: mutable.Set[HGEdge],
                        treeNodes: mutable.Map[String, HTNode]): (HTNode, Boolean) = {
    var root: HTNode = null
    var progress = true
    while (gyoEdges.size > 1 && progress) {
      for (e <- orderedEdges(gyoEdges)) {
        val allOtherVertices = gyoEdges.diff(Set(e)).map(o => o.vertices)
          .reduce((o1, o2) => o1 union o2)
        val singleNodeVertices = e.vertices -- allOtherVertices
        val eNew = e.copy(newVertices = e.vertices -- singleNodeVertices)
        gyoEdges -= e
        gyoEdges += eNew
      }

      var nodeAdded = false
      for (e <- orderedEdges(gyoEdges) if gyoEdges.contains(e)) {
        val supersets = gyoEdges.filter(o => o containsNotEqual e)
        if (supersets.isEmpty) {
          val containedEdges = gyoEdges.filter(o => (e contains o) && (e.name != o.name))
          val parentNode = treeNodes.getOrElse(e.name, new HTNode(Set(e), Set(), null))
          val childNodes = containedEdges
            .map(c => treeNodes.getOrElse(c.name, new HTNode(Set(c), Set(), null)))
            .toSet
          parentNode.children ++= childNodes
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
    (root, progress)
  }

  /**
   * Cyclic-query decomposition (generalized hypertree decomposition, simplest correct first
   * cut). Runs GYO ear-removal; when GYO STALLS with a mutually-irreducible cyclic component
   * of >= 2 edges remaining (the residual that makes `flatGYO` return null), it materializes
   * that residual as a single BAG and continues:
   *
   *  1. The residual `gyoEdges` IS the cyclic component (all ears already stripped).
   *  2. Materialize the bag as an ordinary inner-join of the residual relations' planReferences,
   *     applying every bag-internal equi-join predicate (derived from shared hypergraph vertices)
   *     AND every cross-relation filter whose attributes lie entirely within the bag.
   *  3. Replace the residual edges with ONE new HGEdge whose planReference is the materialized
   *     join and whose vertices are the union of the residual edges' vertices (minus vertices
   *     internal to the bag, which no longer connect to the outside) - so the bag connects to
   *     the rest of the query exactly as the cyclic component did.
   *  4. Continue GYO with the bag edge folded in. The result is an HTNode tree where one leaf is
   *     the materialized bag; `buildBottomUpJoinsCounting` then runs over it UNCHANGED - the bag
   *     is just a derived relation whose rows already equal the cyclic sub-query.
   *
   * Returns null (-> caller keeps the original plan) on shapes this first cut cannot decompose:
   * an empty residual, a residual whose edges do not all share at least one vertex with the rest
   * (disconnected), or when a second stall occurs after one bag was formed.
   */
  def flatGYOWithBags: HTNode = {
    val gyoEdges: mutable.Set[HGEdge] = mutable.Set.empty
    for (edge <- edges) {
      gyoEdges.add(edge.copy())
    }
    val treeNodes: mutable.Map[String, HTNode] = mutable.Map.empty

    var bagsFormed = 0
    var (root, progress) = gyoReduce(gyoEdges, treeNodes)

    // GYO stalled on a cyclic residual: collapse it into a single materialized bag, then
    // continue. Only one bag is formed in this first cut; a second stall falls back to null.
    while (gyoEdges.size > 1 && !progress) {
      if (bagsFormed >= 1) {
        if (RewriteJoinsAsSemijoins.DEBUG_LOGGING) {
          logWarning("cyclic decomposition: more than one cyclic component, falling back")
        }
        return null
      }
      val residual = orderedEdges(gyoEdges)
      val bagEdge = materializeBag(residual)
      if (bagEdge == null) {
        if (RewriteJoinsAsSemijoins.DEBUG_LOGGING) {
          logWarning("cyclic decomposition: could not materialize bag, falling back")
        }
        return null
      }
      // Ears removed in earlier GYO rounds may already be attached as children of a residual
      // edge's tree node. Collapsing the residual into one bag must NOT orphan them: collect
      // every such child whose own edge is outside the residual and re-attach it to the bag
      // node. (Children that ARE residual edges are subsumed by the materialized join.)
      val residualNames = residual.map(_.name).toSet
      val orphanChildren: Set[HTNode] = residual.flatMap { e =>
        treeNodes.get(e.name).toSeq.flatMap(_.children)
      }.filterNot(c => residualNames.contains(c.edges.head.name)).toSet
      // Every rescued orphan child must connect to the bag via one of the bag's (external)
      // vertices. If an orphan shared only a vertex now INTERNAL to the bag, the tree-of-bags is
      // not join-connected: the reduction could not form a join condition for it (empty
      // overlapping vertices -> empty.reduceLeft). Decline to decompose this shape (fall back).
      if (orphanChildren.exists(c => (c.edges.head.vertices intersect bagEdge.vertices).isEmpty)) {
        if (RewriteJoinsAsSemijoins.DEBUG_LOGGING) {
          logWarning("cyclic decomposition: orphan child not connected to bag, falling back")
        }
        return null
      }
      // Replace the residual edges with the single bag edge. The bag edge participates in GYO
      // exactly like a base relation; its tree node carries the materialized join as its
      // planReference, with the rescued ear subtrees as children.
      gyoEdges --= residual
      gyoEdges += bagEdge
      residual.foreach(e => treeNodes.remove(e.name))
      val bagNode = new HTNode(Set(bagEdge), orphanChildren, null)
      bagNode.setParentReferences
      treeNodes.put(bagEdge.name, bagNode)
      bagsFormed += 1
      val (newRoot, newProgress) = gyoReduce(gyoEdges, treeNodes)
      root = newRoot
      progress = newProgress
    }

    if (gyoEdges.size > 1) {
      return null
    }
    // Exactly one edge remains: it is the root of the join tree. Resolve it from treeNodes
    // (the loop builds the tree there, keyed by edge name). If the single remaining edge is
    // the bag itself with no surrounding relations (the whole query was one cyclic component),
    // treeNodes already holds its node from materialization; otherwise build a leaf node.
    val onlyEdge = gyoEdges.head
    root = treeNodes.getOrElse(onlyEdge.name, new HTNode(Set(onlyEdge), Set(), null))
    root.parent = null
    root.setParentReferences
    root
  }

  /**
   * Materialize the bag of the given residual edges as an ordinary inner-join LogicalPlan and
   * wrap it in a new HGEdge. Returns null if the bag cannot be built (single edge, or a
   * disconnected residual that this first cut declines to handle).
   *
   * Join conditions: for each pair of relations already in the running join and the relation
   * being added, an equi-join is emitted on every shared hypergraph vertex (the attributes in
   * that vertex's equivalence class that each side actually outputs). Cross-relation filters
   * whose referenced attributes lie entirely inside the bag's output are applied as a Filter on
   * top of the join, so the bag's rows equal the corresponding cyclic sub-query.
   */
  private def materializeBag(residual: Seq[HGEdge]): HGEdge = {
    if (residual.size < 2) {
      return null
    }

    // Cost gate (when enabled): the bag is materialized EAGERLY with ordinary inner joins, before
    // any semijoin reduction. Only do so when that join is bounded - every residual relation
    // except the largest must be broadcast-eligible by size, so the bag is a chain of broadcast
    // joins and cannot blow up. A bag with two large relations risks a cyclic-join explosion that
    // vanilla's join reordering might avoid, so decline and fall back. (Off in the test suites via
    // yannakakisCostGateEnabled=false, so the cyclic tests still exercise the path.)
    val sqlConf = SQLConf.get
    if (sqlConf.yannakakisCostGateEnabled) {
      val thr = BigInt(sqlConf.autoBroadcastJoinThreshold)
      val sizes = residual.map(_.planReference.stats.sizeInBytes).sorted
      if (thr < 0 || sizes.dropRight(1).exists(_ > thr)) {
        if (RewriteJoinsAsSemijoins.DEBUG_LOGGING) {
          logWarning("cyclic decomposition: bag not broadcast-bounded, falling back")
        }
        return null
      }
    }

    // Equi-join condition between two relations on every hypergraph vertex they share. For a
    // vertex v, both sides may output several attributes of v's equivalence class; pick one
    // attribute per side (any pair is equal under the equivalence) and AND the equalities.
    def joinCondition(leftOut: AttributeSet, rightVertices: Set[String],
                      rightOut: AttributeSet): Option[Expression] = {
      val conds: Seq[Expression] = rightVertices.toSeq.sorted.flatMap { v =>
        val classAttrs = vertexToAttributes.getOrElse(v, Set.empty)
        val lOpt = classAttrs.find(a => leftOut.contains(a))
        val rOpt = classAttrs.find(a => rightOut.contains(a))
        for (l <- lOpt; r <- rOpt) yield
          if (l.dataType == r.dataType) EqualTo(l, r)
          else EqualTo(l, Cast(r, l.dataType))
      }
      conds.reduceOption((c1, c2) => And(c1, c2))
    }

    // Chain the residual relations into an inner join. Each newly added relation is joined on
    // the vertices it shares with the already-accumulated relations.
    var plan: LogicalPlan = residual.head.planReference
    var accumulatedVertices: Set[String] = residual.head.vertices
    for (e <- residual.tail) {
      val shared = accumulatedVertices intersect e.vertices
      val cond = joinCondition(plan.outputSet, shared, e.planReference.outputSet)
      if (cond.isEmpty) {
        // The relation does not connect (via a shared vertex) to what we have so far. A
        // genuine cyclic component is connected; a disconnected residual is a shape this
        // first cut does not handle, so fall back.
        return null
      }
      plan = Join(plan, e.planReference, Inner, cond, JoinHint(Option.empty, Option.empty))
      accumulatedVertices = accumulatedVertices union e.vertices
    }

    // Apply cross-relation filters that lie entirely within the bag (e.g. an inequality between
    // two of the bag's relations). Filters spanning attributes outside the bag are left for the
    // surrounding query and must NOT be applied here.
    val bagOutput = plan.outputSet
    val bagFilters = crossRelationFilters.filter(f => f.references.subsetOf(bagOutput))
    bagFilters.reduceOption((f1, f2) => And(f1, f2)).foreach { f =>
      plan = Filter(f, plan)
    }

    // The bag's vertices: every vertex of a residual edge that ALSO appears in some edge
    // outside the residual (i.e. still connects the bag to the rest of the query). Vertices
    // internal to the bag are dropped - they have been consumed by the materialized join and
    // no longer participate in the outer decomposition. (If the bag is the whole query there
    // are no outside edges and this set is empty, which is correct: nothing left to join.)
    val residualNames = residual.map(_.name).toSet
    val outsideVertices: Set[String] = edges.filterNot(e => residualNames.contains(e.name))
      .flatMap(_.vertices).toSet
    val bagVertices = residual.flatMap(_.vertices).toSet intersect outsideVertices

    new HGEdge(bagVertices, s"BAG_${residualNames.toSeq.sorted.mkString("_")}",
      plan, attributeToVertex)
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

    // Iterate edges in a stable order (E1, E2, ...): HGEdge uses identity hashCodes, so
    // raw Set iteration order varies between JVM runs and would make the join tree shape
    // and root nondeterministic.
    def ordered(es: mutable.Set[HGEdge]): Seq[HGEdge] =
      es.toSeq.sortBy(e => (e.name.length, e.name))

    var progress = true
    while (gyoEdges.size > 1 && progress) {
      for (e <- ordered(gyoEdges)) {
        // logWarning("gyo edge: " + e)
        // Remove vertices that only occur in this edge
        val allOtherVertices = gyoEdges.diff(Set(e)).map(o => o.vertices)
          .reduce((o1, o2) => o1 union o2)
        val singleNodeVertices = e.vertices -- allOtherVertices

        // logWarning("single vertices: " + singleNodeVertices)

        val eNew = e.copy(newVertices = e.vertices -- singleNodeVertices)
        gyoEdges = gyoEdges.diff(Set(e)) ++ Set(eNew)

        // logWarning("removed single vertices: " + gyoEdges)
      }

      var nodeAdded = false
      for (e <- ordered(gyoEdges) if gyoEdges.contains(e)) {
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

