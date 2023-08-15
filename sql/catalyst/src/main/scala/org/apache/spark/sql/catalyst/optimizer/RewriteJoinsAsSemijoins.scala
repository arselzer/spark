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
import org.apache.spark.sql.catalyst.expressions.{Attribute, AttributeSet, EqualTo, ExpressionSet, ExprId, PredicateHelper}
import org.apache.spark.sql.catalyst.plans.InnerLike
import org.apache.spark.sql.catalyst.plans.logical.{Aggregate, Filter, Join, JoinHint, LogicalPlan, Project}
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.catalyst.trees.TreePattern

object RewriteJoinsAsSemijoins extends Rule[LogicalPlan] with PredicateHelper {
  override def apply(plan: LogicalPlan): LogicalPlan = {
    if (!conf.yannakakisEnabled) {
      plan
    }
    else {
      plan.transformDownWithPruning(_.containsPattern(TreePattern.AGGREGATE), ruleId) {
        case agg @ Aggregate(groupingExpressions, aggExpressions,
          join @ Join(_, _, _, _, _)) =>
          val (items, conditions) = extractInnerJoins(join)
          logWarning("agg(join)")
          logWarning("items: " + items.toString())
          logWarning("conditions: " + conditions)
          for (cond <- conditions) {
            logWarning("condition: " + cond)
          }
          val agg2 = agg.copy(groupingExpressions)
          agg
        case agg@Aggregate(groupingExpressions, aggExpressions,
          filter@Filter(filterConds,
            join@Join(_, _, _, _, _))) =>
          val (items, conditions) = extractInnerJoins(join)
          logWarning("agg(filter(join))")
          logWarning("items: " + items.toString())
          logWarning("conditions: " + conditions)
          for (cond <- conditions) {
            logWarning("condition: " + cond)
          }
          val agg2 = agg.copy(groupingExpressions)
          agg
        case agg@Aggregate(groupingExpressions, aggExpressions,
        project@Project(projectList,
        join@Join(_, _, _, _, _))) =>
          val (items, conditions) = extractInnerJoins(join)
          logWarning("agg(project(join))")
          logWarning("items: " + items.toString())
          logWarning("conditions: " + conditions)

          val aggregateAttributes = aggExpressions.map(expr => expr.references)
            .reduce((a1, a2) => a1 ++ a2)
          logWarning("aggregate attributes: " + aggregateAttributes)
          val groupAttributes = groupingExpressions
            .map (g => g.references)
            .reduce((g1, g2) => g1 ++ g2)
          logWarning("group attributes: " + groupAttributes)
          val aggAttributes = aggregateAttributes ++ groupAttributes


          val hg = new Hypergraph(items, conditions)

          val jointree = hg.flatGYO

          logWarning("join tree: \n" + jointree)

          val nodeContainingAttributes = jointree.findNodeContainingAttributes(aggAttributes)
          if (nodeContainingAttributes != null) {
            val root = nodeContainingAttributes.reroot
            logWarning("rerooted: \n" + root)

            val yannakakisJoins = buildBottomUpJoins(root)

            val newAgg = Aggregate(groupingExpressions, aggExpressions,
              Project(projectList, yannakakisJoins))

            // newAgg
            agg
          }
          else {
            logWarning("query is not 0MA")
            agg
          }
      }
    }
  }

  private def buildBottomUpJoins(joinTree: HTNode): LogicalPlan = {
    return null
  }

  /**
   * Extracts items of consecutive inner joins and join conditions.
   * This method works for bushy trees and left/right deep trees.
   */
  private def extractInnerJoins(plan: LogicalPlan): (Seq[LogicalPlan], ExpressionSet) = {
    plan match {
      // replace innerlike by more general join type?
      case Join(left, right, _: InnerLike, Some(cond), JoinHint.NONE) =>
        val (leftPlans, leftConditions) = extractInnerJoins(left)
        val (rightPlans, rightConditions) = extractInnerJoins(right)
        (leftPlans ++ rightPlans, leftConditions ++ rightConditions ++
          splitConjunctivePredicates(cond))
      case Project(projectList, j@Join(_, _, _: InnerLike, Some(cond), JoinHint.NONE))
        if projectList.forall(_.isInstanceOf[Attribute]) =>
        extractInnerJoins(j)
      case _ =>
        (Seq(plan), ExpressionSet())
    }
  }
}

class HGEdge(val vertices: Set[String], val name: String, val planReference: LogicalPlan) {
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
    new HGEdge(newVertices, newName, newPlanReference)
  override def toString: String = s"""${name}(${vertices.mkString(", ")})"""
}
class HTNode(val edges: Set[HGEdge], var children: Set[HTNode], var parent: HTNode)
  extends Logging {
  def reroot: HTNode = {
    if (parent == null) {
      this
    }
    else {
      logWarning("parent: " + parent)
      var current = this
      var newCurrent = this.copy(newParent = null)
      val root = newCurrent
      while (current.parent != null) {
        val p = current.parent
        logWarning("p: " + p)
        val newChild = p.copy(newChildren = p.children - current, newParent = null)
        logWarning("new child: " + newChild)
        newCurrent.children += newChild
        logWarning("c: " + current)
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
    logWarning("aggAttributes: " + aggAttributes)
    logWarning("nodeAttributes: " + nodeAttributes)
    if (aggAttributes subsetOf nodeAttributes) {
      logWarning("found subset in:\n" + this)
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

  for (cond <- conditions) {
    // logWarning("condition: " + cond)
    cond match {
      case EqualTo(lhs, rhs) =>
        // logWarning("equality condition: " + lhs.references + " , " + rhs.references)
        val lAtt = lhs.references.head
        val rAtt = rhs.references.head
        equivalenceClasses += Set(lAtt, rAtt)
      case other =>
        logWarning("other")
    }
  }

  // Compute the equivalence classes
  while (combineEquivalenceClasses) {

  }

  // logWarning("equivalence classes: " + equivalenceClasses)

  for (equivalenceClass <- equivalenceClasses) {
    val attName = equivalenceClass.head.name
    vertices.add(attName)
    vertexToAttributes.put(attName, equivalenceClass)
    for (equivAtt <- equivalenceClass) {
      attributeToVertex.put(equivAtt.exprId, attName)
    }
  }

  logWarning("vertex to attribute mapping: " + vertexToAttributes)
  logWarning("attribute to vertex mapping: " + attributeToVertex)

  var tableIndex = 1
  for (item <- items) {
    logWarning("join item: " + item)

    val projectAttributes = item.outputSet
    val hyperedgeVertices = projectAttributes
      .map(att => attributeToVertex.getOrElse(att.exprId, ""))
      .filterNot(att => att.equals("")).toSet

    item match {
      // TODO Extract table name?
      case Project(_, Filter(_, rel)) =>
        logWarning("relation: " + rel)
    }

    val hyperedge = new HGEdge(hyperedgeVertices, s"E${tableIndex}", item)
    tableIndex += 1
    edges.add(hyperedge)
  }

  logWarning("hyperedges: " + edges)

  private def combineEquivalenceClasses: Boolean = {
    for (set <- equivalenceClasses) {
      for (otherSet <- (equivalenceClasses - set)) {
        if ((set intersect otherSet).nonEmpty) {
          equivalenceClasses += (set union otherSet)
          equivalenceClasses -= set
          equivalenceClasses -= otherSet
          true
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
        val supersets = gyoEdges.filter(o => o containsNotEqual e)
        // logWarning("supersets: " + supersets)

        // For each edge e, check if it is not contained in another edge
        if (supersets.isEmpty) {
          // Append the contained edges as children in the tree
          val containedEdges = gyoEdges.filter(o => (e contains o) && (e.name != o.name))
          // logWarning("edge: " + e)
          // logWarning("subsets: " + childNodes)
          val parentNode = treeNodes.getOrElse(e.name, new HTNode(Set(e), Set(), null))
          val childNodes = containedEdges
            .map(c => treeNodes.getOrElse(c.name, new HTNode(Set(c), Set(), null)))
            .toSet
          parentNode.children ++= childNodes

          treeNodes.put(e.name, parentNode)
          childNodes.foreach(c => treeNodes.put(c.edges.head.name, c))
          root = parentNode
          root.setParentReferences
          gyoEdges --= containedEdges
          nodeAdded = true
        }
      }
      if (!nodeAdded) progress = false
    }

    if (gyoEdges.size > 1) {
      return null
    }

    root
  }
}

