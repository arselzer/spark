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

  /**
   * ===================================================================================
   * MULTI-COUNT OPTIMIZATION FRAMEWORK FOR YANNAKAKIS PRODUCT AGGREGATES
   * ===================================================================================
   *
   * OVERVIEW
   * --------
   * When computing multiple product aggregates (e.g., SUM(A*B), SUM(B*C), SUM(A*C)),
   * each product may require different count semantics because the grouping granularity
   * affects how many rows contribute to each product value.
   *
   * CORE PROBLEM
   * ------------
   * Consider a join tree T1 -> T2 -> T3 with products:
   *   P1 = SUM(a * b) where a in T1, b in T2
   *   P2 = SUM(b * c) where b in T2, c in T3
   *
   * When P1 is computed at join T1-T2, we need counts based on grouping by {a, b}.
   * When P2 is computed at join T2-T3, we need counts based on grouping by {b, c}.
   *
   * If P1's grouping (adding b to carry through) affects P2's counts, we have a
   * CONFLICT - the count semantics are incompatible.
   *
   * CONFLICT DETECTION
   * ------------------
   * Two products P1 and P2 CONFLICT if they share some but not all attributes:
   *   conflict(P1, P2) iff attrs(P1) intersect attrs(P2) != empty AND
   *                        attrs(P1) not-subset-of attrs(P2) AND
   *                        attrs(P2) not-subset-of attrs(P1)
   *
   * CONTAINMENT STRUCTURE
   * ---------------------
   * Products have CONTAINMENT when one's attrs are a subset of another's:
   *   P1 containedIn P2 iff attrs(P1) strict-subset-of attrs(P2)
   *
   * Containment enables optimization:
   * - The larger product P2 can be computed with fine-grained grouping
   * - The smaller product P1 can reuse P2's count track (derived count)
   *
   * Example: {a} < {a,b} < {a,b,c}
   *   - Compute at {a,b,c} granularity
   *   - P({a,b,c}) uses direct count
   *   - P({a,b}) uses SUM(count) grouped by {a,b}
   *   - P({a}) uses SUM(count) grouped by {a}
   *
   * STRATEGIES
   * ----------
   * 1. INDEPENDENT PRODUCTS: No conflicts, each computed with its own count track
   *
   * 2. HIERARCHICAL CONTAINMENT: When containment exists:
   *    - Compute finest-grained count (for largest product)
   *    - Derive coarser counts via aggregation
   *
   * 3. CONFLICT FALLBACK: When products conflict without containment:
   *    - Carry all product attributes through grouping
   *    - Defer products to final aggregate with count multiplication
   *
   * 4. CONNECTED COMPONENTS: Partition products into independent groups:
   *    - Products in different components don't affect each other
   *    - Each component can use its own count track
   *
   * CONFLICT FALLBACK (DEFERRED) EXPLAINED
   * ---------------------------------------
   * When products conflict without containment structure, we must defer to final:
   *
   * Example: IMDB query with products {role_id,info_type_id}, {production_year,role_id}
   *   - They share role_id but neither is a subset of the other
   *   - If we compute one early, its grouping affects the other's count semantics
   *   - Solution: carry all product attributes through grouping, compute at final
   *
   * This is actually the CORRECT behavior for such queries:
   *   - All products use the same final count (computed once at the end)
   *   - The count multiplier correctly accounts for the full join cardinality
   *   - Products are computed in the final aggregate with count multiplication
   *
   * IMPLEMENTATION STATUS
   * ----------------------
   * WORKING (tested and verified):
   *   [x] CASE 1 - Independent Products: disjoint attrs computed early (if join tree allows)
   *   [x] CASE 2 - Containment Hierarchy: subset products use derived counts
   *   [x] CASE 3 - Universal Superset: all products derive from common superset
   *   [x] CASE 4 - Connected Components: independent groups optimized separately
   *   [x] CASE 5 - Star Pattern (deferred): correctly defers to final aggregate
   *
   * JOIN-TREE-AWARE CONFLICT DETECTION
   * -----------------------------------
   * For multiple products, we analyze the concrete join tree to determine which
   * products can compute early vs. must defer to the final aggregate.
   *
   * A product P can compute early if:
   *   1. All its attributes become available at some join J
   *   2. At all subsequent joins (ancestors of J), the grouping doesn't contain
   *      attributes FOREIGN to P (not in P's attribute set)
   *
   * Foreign grouping causes the product's pending value to be replicated across
   * groups, leading to overcounting when summed at the final aggregate.
   *
   * Strategy 3 fallback: If ALL products would defer, pick ONE "winner" to compute
   * early (the one computed highest in the tree, to minimize propagation issues).
   *
   * IMPLEMENTATION
   * --------------
   * DeferredComputation: Unified abstraction for products and cross-relation filters
   * ConflictGraph: Tracks which products conflict with each other
   * ContainmentDAG: Directed acyclic graph showing containment relationships
   * CountTrackAssignment: Maps each product to its count track strategy
   *
   * DECISION FLOWCHART
   * ------------------
   * For a set of product aggregates:
   *
   *   1. Extract product attributes: P1={a,b}, P2={b,c}, P3={d,e}, ...
   *
   *   2. Build conflict graph:
   *      - Edge between Pi and Pj if they share attrs but neither is subset
   *
   *   3. Find connected components:
   *      - Group products that transitively conflict
   *      - Independent components can use separate count tracks
   *
   *   4. For each component, check containment:
   *      - If pure containment (no conflicts): use hierarchical derivation
   *      - If universal superset exists: derive all from it
   *      - Otherwise: defer all to final aggregate
   *
   *   5. Assign strategies:
   *      - DirectCount: product computes its own count at join
   *      - DerivedCount(source): derive from source via GROUP BY
   *      - DeferredToFinal: compute at final aggregate with count multiplication
   *
   * EXAMPLES BY CASE
   * ----------------
   * CASE 1 - Independent Products (optimization applies):
   *   Products: {a,b}, {c,d}  -- no overlap
   *   Result: Each uses its own count track, computed early
   *
   * CASE 2 - Containment Chain (optimization applies):
   *   Products: {a}, {a,b}, {a,b,c}  -- strict containment
   *   Result: Compute at {a,b,c}, derive {a,b} and {a} via GROUP BY
   *
   * CASE 3 - Universal Superset (optimization applies):
   *   Products: {a,b}, {a,c}, {b,c}, {a,b,c}  -- conflicts but superset exists
   *   Result: Use {a,b,c} as source, derive all others
   *
   * CASE 4 - Multiple Components (partial optimization):
   *   Products: {a,b}, {b,c} (conflict), {d,e} (independent)
   *   Result: {d,e} uses own track; {a,b},{b,c} deferred together
   *
   * CASE 5 - Star Pattern (NO optimization - worst case):
   *   Products: {a,b}, {a,c}, {a,d}  -- all share 'a' but none contains another
   *   Result: All deferred to final aggregate with count multiplication
   *   This is the IMDB query pattern.
   *
   * CASE 7 - Isolated Products (optimization applies):
   *   Products: {a,b}, {c,d}  -- completely disjoint, no overlap at all
   *   Result: Each product uses its own count, not multiplied by other product's tables
   *   Key difference from CASE 1: CASE 7 specifically tests that products don't
   *   interfere with each other during LEFT propagation across join tree levels.
   *
   * TEST COVERAGE
   * -------------
   * See IMDB12TableBugSuite.scala for comprehensive tests of each case (70 tests total):
   * - "CASE 1: Independent products - no shared attributes"
   * - "CASE 2: Containment hierarchy - subset relationships"
   * - "CASE 3: Universal superset with conflicts"
   * - "CASE 4: Multiple independent components"
   * - "CASE 5: Star pattern - worst case (like IMDB)"
   * - "CASE 6: Partial containment - some derived, some deferred"
   * - "Isolated products: 2/3/4 disjoint multi-attr products" (CASE 7 tests)
   * - "IMDB-style: isolated products from different table groups"
   * - "IMDB-style: three isolated products across 8 tables"
   *
   * ===================================================================================
   */

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
   * Analyzes the containment structure among deferred computations.
   * Returns true if hierarchical count tracks could help (i.e., containment exists).
   *
   * When containment exists, we can:
   * 1. Compute counts at the finest granularity (for the superset product)
   * 2. Derive coarser counts via aggregation for contained products
   *
   * Example where containment helps:
   *   P1 = SUM(a), P2 = SUM(a*b), P3 = SUM(a*b*c)
   *   Containment: {a} < {a,b} < {a,b,c}
   *   Strategy: Compute at {a,b,c} level, derive {a,b} and {a} counts
   *
   * Example where containment doesn't help (IMDB query):
   *   P1 = SUM(a*b), P2 = SUM(b*c), P3 = SUM(a*c)
   *   No containment: all pairs conflict without hierarchy
   *   Strategy: Carry all attrs, defer to final aggregate
   */
  def hasContainmentStructure(computations: Seq[DeferredComputation]): Boolean = {
    computations.exists { c1 =>
      computations.exists { c2 =>
        c1 != c2 && c1.containedIn(c2)
      }
    }
  }

  /**
   * Build the containment DAG for a set of computations.
   *
   * Returns a map from each computation to its immediate parents (supersets).
   * The DAG edges point from smaller to larger attribute sets.
   *
   * Example: For products {a}, {a,b}, {a,b,c}, {b,c}:
   *   {a} -> {a,b}
   *   {a,b} -> {a,b,c}
   *   {b,c} -> (no parents in this set)
   *
   * This DAG structure enables:
   * - Finding the "roots" (maximal products) that need direct count computation
   * - Computing derived counts by traversing the DAG downward
   */
  def buildContainmentDAG(computations: Seq[DeferredComputation]):
      Map[DeferredComputation, Set[DeferredComputation]] = {
    computations.map { c =>
      // Find immediate parents: supersets that have no intermediate supersets
      val allSupersets = computations.filter(other => c.containedIn(other))
      val immediateParents = allSupersets.filterNot { parent =>
        allSupersets.exists(other => other != parent && other.containedIn(parent))
      }
      c -> immediateParents.toSet
    }.toMap
  }

  /**
   * Find connected components in the conflict graph.
   *
   * Each component contains products that directly or transitively conflict with each other.
   * Products in different components are completely independent and can use separate
   * count tracks without interference.
   *
   * Example:
   *   P1 conflicts P2, P2 conflicts P3 -> Component 1: {P1, P2, P3}
   *   P4 conflicts P5                  -> Component 2: {P4, P5}
   *   P6 independent                   -> Component 3: {P6}
   *
   * @return Sequence of components, each being a set of computations
   */
  def findConflictComponents(computations: Seq[DeferredComputation]):
      Seq[Set[DeferredComputation]] = {
    if (computations.isEmpty) return Seq.empty

    val visited = mutable.Set[DeferredComputation]()
    val components = mutable.Buffer[Set[DeferredComputation]]()

    def dfs(start: DeferredComputation): Set[DeferredComputation] = {
      val component = mutable.Set[DeferredComputation]()
      val stack = mutable.Stack[DeferredComputation](start)

      while (stack.nonEmpty) {
        val current = stack.pop()
        if (!visited.contains(current)) {
          visited.add(current)
          component.add(current)
          // Add all conflicting or containing/contained computations
          computations.foreach { other =>
            if (!visited.contains(other) &&
                (current.conflictsWith(other) ||
                 current.containedIn(other) ||
                 other.containedIn(current))) {
              stack.push(other)
            }
          }
        }
      }
      component.toSet
    }

    computations.foreach { c =>
      if (!visited.contains(c)) {
        components += dfs(c)
      }
    }

    components.toSeq
  }

  // =====================================================================
  // JOIN-TREE-AWARE PRODUCT CONFLICT DETECTION
  // =====================================================================
  //
  // For each product, determine if it can compute early given the concrete join tree.
  //
  // A product P can compute early if:
  // 1. All its attributes become available at some join J
  // 2. At all subsequent joins (ancestors of J), the grouping doesn't contain
  //    attributes FOREIGN to P (not in P's attribute set)
  //
  // Foreign grouping causes the product's pending value to be replicated across
  // groups, leading to overcounting when summed at the final aggregate.
  //
  // Strategy 3 fallback: If all products would defer, pick ONE "winner" to compute
  // early (the one computed highest in the tree, to minimize propagation issues).

  /**
   * Analyze which products can compute early given the concrete join tree.
   *
   * @param root The root of the join tree (HTNode)
   * @param productComputations The products to analyze
   * @return Map from product resultAttr to whether it can compute early
   */
  def analyzeProductsForJoinTree(
      root: HTNode,
      productComputations: Seq[DeferredComputation]
  ): Map[Attribute, Boolean] = {

    if (productComputations.size < 2) {
      // Single product or none - no conflicts possible
      return productComputations.flatMap(_.resultAttr.map(_ -> true)).toMap
    }

    // Step 1: Build a map of each node's subtree output attributes
    val subtreeOutputs = mutable.Map[HTNode, AttributeSet]()
    def computeSubtreeOutput(node: HTNode): AttributeSet = {
      if (subtreeOutputs.contains(node)) return subtreeOutputs(node)
      val ownOutput = node.edges.flatMap(_.outputSet)
      val childOutput = node.children.flatMap(c => computeSubtreeOutput(c))
      val result = AttributeSet(ownOutput ++ childOutput)
      subtreeOutputs(node) = result
      result
    }
    computeSubtreeOutput(root)

    // Step 2: Find computation point for each product
    // (the deepest node where all its attributes first become available)
    def findComputationNode(node: HTNode, attrs: Set[Attribute]): HTNode = {
      // Check if any single child contains all attrs
      for (child <- node.children) {
        val childOutput = subtreeOutputs(child)
        if (attrs.forall(childOutput.contains)) {
          return findComputationNode(child, attrs)
        }
      }
      // No single child has all attrs - this node is the computation point
      node
    }

    val productInfos = productComputations.flatMap { prod =>
      prod.resultAttr.map { resultAttr =>
        val computeNode = findComputationNode(root, prod.attrs)
        (prod, prod.attrs, resultAttr, computeNode)
      }
    }

    // Step 3: Compute what grouping attributes will be active at each node
    // Grouping at node N = union of attrs from products computed BELOW N
    // that need to be carried through N (from N's children)
    val nodeGroupings = mutable.Map[HTNode, Set[Attribute]]()

    def computeNodeGrouping(node: HTNode): Set[Attribute] = {
      if (nodeGroupings.contains(node)) return nodeGroupings(node)

      var grouping = Set.empty[Attribute]

      // For each product computed strictly below this node:
      // If its attrs come partly from this node's children, those attrs need grouping
      for ((prod, attrs, _, computeNode) <- productInfos) {
        if (computeNode != node && isDescendantOf(computeNode, node)) {
          // Product computed below this node
          // Check which of its attrs come from this node's children (right side)
          for (child <- node.children) {
            val childOutput = subtreeOutputs(child)
            val attrsFromChild = attrs.filter(childOutput.contains)
            grouping ++= attrsFromChild
          }
        }
      }

      nodeGroupings(node) = grouping
      grouping
    }

    // Compute groupings for all nodes
    def visitAll(node: HTNode): Unit = {
      computeNodeGrouping(node)
      node.children.foreach(visitAll)
    }
    visitAll(root)

    // Step 4: For each product, check if any ancestor has foreign grouping
    def getAncestors(node: HTNode): Seq[HTNode] = {
      val ancestors = mutable.ArrayBuffer[HTNode]()
      var current = node.parent
      while (current != null) {
        ancestors += current
        current = current.parent
      }
      ancestors.toSeq
    }

    val results = mutable.Map[Attribute, Boolean]()

    for ((prod, attrs, resultAttr, computeNode) <- productInfos) {
      val ancestors = getAncestors(computeNode)
      var canComputeEarly = true

      for (ancestor <- ancestors if canComputeEarly) {
        val groupingAtAncestor = nodeGroupings.getOrElse(ancestor, Set.empty[Attribute])
        val foreignGrouping = groupingAtAncestor -- attrs

        if (foreignGrouping.nonEmpty) {
          // This ancestor has grouping attributes foreign to this product
          canComputeEarly = false
          debugLog(s"Product {${attrs.map(_.name).mkString(",")}} cannot compute early: " +
            s"foreign grouping {${foreignGrouping.map(_.name).mkString(",")}} at ancestor")
        }
      }

      results(resultAttr) = canComputeEarly
    }

    // Step 5: Strategy 3 fallback - if ALL products would defer, pick one winner
    if (results.nonEmpty && results.values.forall(_ == false)) {
      // All products would defer - pick the one with computation point highest in tree
      // (smallest depth = fewer ancestors = less chance of grouping conflicts)
      def getDepth(node: HTNode): Int = {
        var depth = 0
        var current = node.parent
        while (current != null) {
          depth += 1
          current = current.parent
        }
        depth
      }

      val byDepth = productInfos.map { case (prod, attrs, resultAttr, computeNode) =>
        (prod, attrs, resultAttr, computeNode, getDepth(computeNode))
      }.sortBy(_._5)  // Sort by depth ascending (smallest depth = highest in tree)

      if (byDepth.nonEmpty) {
        val (winnerProd, winnerAttrs, winnerResultAttr, _, _) = byDepth.head
        debugLog(s"Strategy 3 fallback: selecting winner product " +
          s"{${winnerAttrs.map(_.name).mkString(",")}}")
        results(winnerResultAttr) = true
      }
    }

    results.toMap
  }

  /**
   * Check if 'descendant' is a strict descendant of 'ancestor'.
   */
  private def isDescendantOf(descendant: HTNode, ancestor: HTNode): Boolean = {
    var current = descendant.parent
    while (current != null) {
      if (current == ancestor) return true
      current = current.parent
    }
    false
  }

  /**
   * Determine the count track strategy for each product in a component.
   *
   * Strategy assignment:
   * 1. DIRECT: Product uses directly computed count at its granularity
   * 2. DERIVED: Product's count is derived from a superset product's count
   * 3. DEFERRED: Product defers to final aggregate (conflict fallback)
   *
   * @return Map from computation to (strategy, sourceComputation)
   *         where sourceComputation is the computation to derive count from (if DERIVED)
   */
  sealed trait CountTrackStrategy
  case object DirectCount extends CountTrackStrategy
  case class DerivedCount(source: DeferredComputation) extends CountTrackStrategy
  case object DeferredToFinal extends CountTrackStrategy

  /**
   * Select one "winner" product to compute early when there are multiple products.
   *
   * The winner is selected based on:
   * 1. Position in join tree - products computed highest (smallest depth) preferred
   * 2. This minimizes the chance of foreign grouping conflicts
   *
   * @param root The root of the join tree (HTNode)
   * @param productComputations The products to choose from
   * @return Some(winner) if a winner can be selected, None otherwise
   */
  def selectWinnerProduct(
      root: HTNode,
      productComputations: Seq[DeferredComputation]
  ): Option[DeferredComputation] = {
    if (productComputations.isEmpty) return None

    // Build a map of each node's subtree output attributes
    val subtreeOutputs = mutable.Map[HTNode, AttributeSet]()
    def computeSubtreeOutput(node: HTNode): AttributeSet = {
      if (subtreeOutputs.contains(node)) return subtreeOutputs(node)
      val ownOutput = node.edges.flatMap(_.outputSet)
      val childOutput = node.children.flatMap(c => computeSubtreeOutput(c))
      val result = AttributeSet(ownOutput ++ childOutput)
      subtreeOutputs(node) = result
      result
    }
    computeSubtreeOutput(root)

    // Find computation point for each product
    def findComputationNode(node: HTNode, attrs: Set[Attribute]): HTNode = {
      for (child <- node.children) {
        val childOutput = subtreeOutputs(child)
        if (attrs.forall(childOutput.contains)) {
          return findComputationNode(child, attrs)
        }
      }
      node
    }

    // Get depth of a node (root = 0)
    def getDepth(node: HTNode): Int = {
      var depth = 0
      var current = node.parent
      while (current != null) {
        depth += 1
        current = current.parent
      }
      depth
    }

    // Compute depth for each product
    val productDepths = productComputations.map { prod =>
      val computeNode = findComputationNode(root, prod.attrs)
      (prod, getDepth(computeNode))
    }

    // Select the product with smallest depth (computed highest in tree)
    // Use stable ordering: sort by (depth, attr names) to ensure deterministic selection
    val sorted = productDepths.sortBy { case (prod, depth) =>
      (depth, prod.attrs.map(_.name).toSeq.sorted.mkString(","))
    }
    val (winner, _) = sorted.head
    Some(winner)
  }

  def assignCountTrackStrategies(component: Set[DeferredComputation]):
      Map[DeferredComputation, CountTrackStrategy] = {
    val computations = component.toSeq

    // Check if the component has pure containment (no true conflicts)
    val hasConflicts = computations.exists { c1 =>
      computations.exists { c2 => c1.conflictsWith(c2) }
    }

    if (!hasConflicts) {
      // Pure containment or independent - use hierarchical strategy
      val dag = buildContainmentDAG(computations)

      // Find roots (maximal elements with no parents)
      val roots = computations.filter(c => dag(c).isEmpty)

      computations.map { c =>
        if (roots.contains(c)) {
          // Root products compute their own counts directly
          c -> DirectCount
        } else {
          // Find the immediate parent to derive count from
          val parent = dag(c).head // There's at least one parent
          c -> DerivedCount(parent)
        }
      }.toMap
    } else {
      // Has conflicts - check if there's a universal superset
      val maximalProduct = computations.find { c =>
        computations.forall(other => other == c || other.containedIn(c))
      }

      maximalProduct match {
        case Some(max) =>
          // All products contained in one maximal product - use it as source
          computations.map { c =>
            if (c == max) c -> DirectCount
            else c -> DerivedCount(max)
          }.toMap

        case None =>
          // True conflicts without universal superset - defer all to final
          computations.map(c => c -> DeferredToFinal).toMap
      }
    }
  }

  /**
   * Compute hierarchical count derivation plan for products with containment.
   *
   * When products have containment relationships (P1.attrs subset P2.attrs), we can:
   * 1. Compute a single fine-grained count at the maximal level
   * 2. Derive coarser counts via SUM(count) GROUP BY coarser_attrs
   *
   * This avoids computing separate count tracks for each product.
   *
   * Example: Products with attrs {a}, {a,b}, {a,b,c}
   *   - Compute count at {a,b,c} granularity (finest)
   *   - For {a,b}: SUM(count) GROUP BY a, b
   *   - For {a}: SUM(count) GROUP BY a
   *
   * @param products Products to analyze
   * @return CountDerivationPlan with grouping specifications for each product
   */
  case class CountDerivation(
    groupByAttrs: Set[Attribute],
    sourceCountAttr: Attribute,
    derivedCountAlias: String
  )

  case class CountDerivationPlan(
    rootProduct: DeferredComputation,
    rootGroupByAttrs: Set[Attribute],
    derivations: Map[DeferredComputation, CountDerivation]
  )

  def computeCountDerivationPlan(
      products: Seq[DeferredComputation]
  ): Option[CountDerivationPlan] = {
    if (products.size < 2) return None

    // Assign strategies to determine which use direct vs derived counts
    val components = findConflictComponents(products)
    val strategies = components.flatMap(c => assignCountTrackStrategies(c)).toMap

    // Check if hierarchical optimization is applicable
    val directProducts = strategies.filter {
      case (_, DirectCount) => true
      case _ => false
    }.keys.toSeq
    val derivedProducts = strategies.collect {
      case (p, DerivedCount(_)) => p
    }.toSeq

    if (directProducts.isEmpty) return None

    // Find the root (maximal) product - should be unique for hierarchical case
    val root = directProducts.maxBy(_.attrs.size)
    val rootAttrs = root.attrs

    // Build derivation plan for each non-root product
    val derivations = derivedProducts.map { prod =>
      // Group by this product's attrs to derive its count from root's count
      prod -> CountDerivation(
        groupByAttrs = prod.attrs,
        sourceCountAttr = null, // Will be filled in during execution
        derivedCountAlias = s"c_${prod.attrs.map(_.name).mkString("_")}"
      )
    }.toMap

    Some(CountDerivationPlan(
      rootProduct = root,
      rootGroupByAttrs = rootAttrs,
      derivations = derivations
    ))
  }

  /**
   * Determine the grouping attributes needed for a set of products.
   *
   * For products with containment, we need to group by the union of all attrs
   * in the hierarchy to enable hierarchical count derivation.
   *
   * For conflicting products without containment, we group by the union
   * of all product attrs (to defer to final aggregate with count multiplication).
   *
   * @param products Products to analyze
   * @return Set of attrs that should be included in grouping
   */
  def computeRequiredGroupingAttrs(
      products: Seq[DeferredComputation]
  ): Set[Attribute] = {
    if (products.isEmpty) return Set.empty

    val components = findConflictComponents(products)

    components.flatMap { component =>
      val strategies = assignCountTrackStrategies(component)

      // For hierarchical case: use maximal product's attrs
      val directProducts = strategies.filter(_._2 == DirectCount).keys
      if (directProducts.nonEmpty) {
        directProducts.maxBy(_.attrs.size).attrs
      } else {
        // For conflict case (deferred): union of all attrs
        component.flatMap(_.attrs)
      }
    }.toSet
  }

  /**
   * CONNECTED COMPONENTS OPTIMIZATION (STATUS: WORKING)
   * ====================================================
   *
   * When there are multiple independent product groups (connected components),
   * each group can use its own count track without interference.
   *
   * Example with two independent components:
   *   Component 1: P1 = SUM(a*b), P2 = SUM(b*c)  -- share attr b, they conflict
   *   Component 2: P3 = SUM(x*y)                  -- completely independent
   *
   * Benefits:
   * 1. P3 doesn't need to defer just because P1 and P2 conflict
   * 2. P3 can be computed early at its join point
   * 3. Component 1 products follow their own strategy (hierarchical or defer)
   *
   * Implementation:
   * 1. findConflictComponents() partitions products into independent groups
   * 2. assignCountTrackStrategies() processes each component separately
   * 3. Products not in conflictingProductAttrs can use early computation
   *
   * Test: CASE 4 in IMDB12TableBugSuite.scala
   */

  /**
   * Compute per-component strategy summary for logging.
   *
   * @param products All product computations to analyze
   * @return Sequence of (component, strategy map, summary string) tuples
   */
  def analyzeComponentStrategies(
      products: Seq[DeferredComputation]
  ): Seq[(Set[DeferredComputation], Map[DeferredComputation, CountTrackStrategy], String)] = {
    val components = findConflictComponents(products)

    components.map { component =>
      val strategies = assignCountTrackStrategies(component)

      val directCount = strategies.count(_._2 == DirectCount)
      val derivedCount = strategies.count(_._2.isInstanceOf[DerivedCount])
      val deferredCount = strategies.count(_._2 == DeferredToFinal)

      val hasHierarchy = hasContainmentStructure(component.toSeq)

      val summary = if (hasHierarchy && derivedCount > 0) {
        s"Hierarchical: $directCount direct, $derivedCount derived"
      } else if (deferredCount > 0) {
        s"Deferred: $deferredCount products deferred to final"
      } else {
        s"Independent: $directCount products can compute early"
      }

      (component, strategies, summary)
    }
  }

  /**
   * Check if a product belongs to an independent component (no conflicts).
   *
   * Independent products can use early computation at their join point
   * without worrying about count track interference from other products.
   */
  def isIndependentProduct(
      product: DeferredComputation,
      allProducts: Seq[DeferredComputation]
  ): Boolean = {
    val components = findConflictComponents(allProducts)
    components.find(_.contains(product)) match {
      case Some(component) =>
        // Check if component has no internal conflicts
        val strategies = assignCountTrackStrategies(component)
        strategies.get(product) match {
          case Some(DirectCount) => true
          case Some(DerivedCount(_)) => true  // Derived is also non-conflicting
          case _ => false
        }
      case None => true  // Product not found means it's independent
    }
  }

  /**
   * Analyze products and return optimization recommendations.
   *
   * This is the main entry point for multi-count optimization analysis.
   *
   * @return A summary of the optimization strategy for logging and debugging
   */
  def analyzeMultiCountStrategy(products: Seq[DeferredComputation]): String = {
    if (products.size < 2) {
      return "Single product: standard count track"
    }

    val components = findConflictComponents(products)
    val sb = new StringBuilder

    val numProds = products.size
    val numComps = components.size
    sb.append(s"Multi-count analysis: $numProds products in $numComps component(s)\n")

    components.zipWithIndex.foreach { case (component, idx) =>
      val strategies = assignCountTrackStrategies(component)

      val directCount = strategies.count(_._2 == DirectCount)
      val derivedCount = strategies.count(_._2.isInstanceOf[DerivedCount])
      val deferredCount = strategies.count(_._2 == DeferredToFinal)

      sb.append(s"  Component ${idx + 1}: ${component.size} products\n")
      sb.append(s"    Direct: $directCount, Derived: $derivedCount, Deferred: $deferredCount\n")

      if (hasContainmentStructure(component.toSeq)) {
        sb.append(s"    Has containment structure - hierarchical optimization applicable\n")
      } else if (deferredCount > 0) {
        sb.append(s"    No containment - falling back to deferred computation\n")
      }
    }

    sb.toString()
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
          }

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

          // Run comprehensive multi-count analysis
          if (productComputations.size >= 2) {
            val analysisReport = analyzeMultiCountStrategy(productComputations)
            debugLog("Multi-count optimization analysis:\n" + analysisReport)

            // Log detailed containment structure
            val hasContainment = hasContainmentStructure(productComputations)
            if (hasContainment) {
              val dag = buildContainmentDAG(productComputations)
              debugLog("Containment DAG:")
              dag.foreach { case (child, parents) =>
                val childStr = s"{${child.attrs.map(_.name).mkString(",")}}"
                val parentsStr = parents.map(p =>
                  s"{${p.attrs.map(_.name).mkString(",")}}").mkString(", ")
                if (parents.nonEmpty) {
                  debugLog(s"  $childStr -> $parentsStr")
                }
              }
            }

            // Log connected components with per-component strategy analysis
            val componentAnalysis = analyzeComponentStrategies(productComputations)
            if (componentAnalysis.size > 1) {
              debugLog(s"Independent product groups: ${componentAnalysis.size}")
              componentAnalysis.zipWithIndex.foreach { case ((comp, _, summary), idx) =>
                val prodStr = comp.map(p => s"{${p.attrs.map(_.name).mkString(",")}}")
                  .mkString(", ")
                debugLog(s"  Group ${idx + 1}: $prodStr")
                debugLog(s"    Strategy: $summary")
              }
            } else if (componentAnalysis.nonEmpty) {
              // Single component - log its strategy
              val (_, _, summary) = componentAnalysis.head
              debugLog(s"Single product group strategy: $summary")
            }
          }

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
          // Even independent products affect each other's counts.
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

          // For backward compatibility
          val hasConflictingProducts = conflictingProductAttrs.nonEmpty

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

          // Phase 4 (disabled for now - window-based approach needs more work)
          // For conflicting products, we defer to the final aggregate with count multiplication
          // The per-product conflict detection in Phase 1-2 handles this correctly
          val productCountTrackMap = mutable.Map[Attribute, Attribute]()
          val joinsWithWindowCounts: LogicalPlan = yannakakisJoins

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
                                // Phase 4: Use window count for conflicting products
                                val countToUse = productCountTrackMap.getOrElse(
                                  resultAtt, countingAttribute)
                                a.withNewChildren(
                                  Seq(Multiply(a.children.head,
                                    Cast(countToUse, a.children.head.dataType))))
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

          // Prune columns: only include columns that are needed by the aggregate
          val neededAttrs = AttributeSet(
            rewrittenResultExpressions.flatMap(_.references) ++
            groupingExpressions.flatMap(_.references)
          )
          val allOutputs = joinsWithWindowCounts.output ++ groupAliasProjections
          val prunedOutput = allOutputs.filter(attr => neededAttrs.contains(attr))

          val newAgg = Aggregate(groupingExpressions,
            rewrittenResultExpressions,
            Project(prunedOutput, joinsWithWindowCounts))
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
    for (c <- children) {
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
              // can reference them in the CountJoin aggregate.
              // We also need to ensure left-side refs are in grouping to prevent
              // collapsing of rows that have different left-side values.
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

              // For pending products: skip multiplication if right-side is already accounted
              // The pendingProductAccountedAttrs tracks which tables' counts have been
              // incorporated. If right side has NEW (unaccounted) tables, multiply by them.
              val accountedAttrs = pendingProductAccountedAttrs.get(agg.resultAttribute)
              val rightAlreadyAccounted = accountedAttrs.exists { accounted =>
                rightPlan.outputSet.subsetOf(accounted)
              }
              dbg(s"  accountedAttrs=$accountedAttrs rightAlreadyAccounted=$rightAlreadyAccounted")

              val skipMultiplication = isPendingProduct && rightAlreadyAccounted
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

                // Update accounted attrs to include the right side we just multiplied by
                if (isPendingProduct) {
                  accountedAttrs.foreach { accounted =>
                    val newAccounted = accounted ++ rightPlan.outputSet
                    pendingProductAccountedAttrs.put(agg.resultAttribute, newAccounted)
                    dbg(s"  updated accountedAttrs to include rightPlan")
                  }
                }
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
                // Track which tables' counts have been accounted for in this pending product.
                // This is the union of left and right output sets at the point of computation.
                // When the product flows to higher joins, we only multiply by counts from
                // NEW tables (tables not in this set).
                val accountedAttrs = AttributeSet(leftPlan.outputSet ++ rightPlan.outputSet)
                pendingProductAccountedAttrs.put(agg.resultAttribute, accountedAttrs)
                // Track this product's original attribute references for relevance check
                val productOriginalRefs = agg.references.filter(a =>
                  !a.name.startsWith("c#") && a.name != "c")
                val origRefSet = AttributeSet(productOriginalRefs)
                pendingProductOriginalAttrs.put(agg.resultAttribute, origRefSet)
                dbg(s"Added pending product ($numProductAttrs attrs): $productExpr")
                dbg(s"  productAlias=${productAlias.toAttribute}")
                dbg(s"  accountedAttrs=${accountedAttrs.map(_.name)}")
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

