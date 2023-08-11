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
import org.apache.spark.sql.catalyst.expressions.{Attribute, EqualTo, ExpressionSet, ExprId, PredicateHelper}
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


          val hypergraph = new Hypergraph(items, conditions)


          val agg2 = agg.copy(groupingExpressions)
          agg
      }
    }
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

class HGEdge(vertices: Set[String], planReference: LogicalPlan) {

  override def toString: String = vertices.toString()
}
class Hypergraph (private val items: Seq[LogicalPlan],
                  private val conditions: ExpressionSet) extends Logging {

  private var vertices: mutable.Set[String] = mutable.Set.empty
  private var edges: mutable.Set[HGEdge] = mutable.Set.empty
  private var vertexToAttributes: mutable.Map[String, Set[Attribute]] = mutable.Map.empty
  private var attributeToVertex: mutable.Map[ExprId, String] = mutable.Map.empty

  private var equivalenceClasses: Set[Set[Attribute]] = Set.empty

  for (cond <- conditions) {
    logWarning("condition: " + cond)
    cond match {
      case EqualTo(lhs, rhs) =>
        logWarning("equality condition: " + lhs.references + " , " + rhs.references)
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

  logWarning("equivalence classes: " + equivalenceClasses)

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

  for (item <- items) {
    logWarning("join item: " + item)

    val projectAttributes = item.outputSet
    val hyperedgeVertices = projectAttributes
      .map(att => attributeToVertex.getOrElse(att.exprId, ""))
      .filterNot(att => att.equals("")).toSet

    val hyperedge = new HGEdge(hyperedgeVertices, item)
    edges.add(hyperedge)
  }

  logWarning("hyperedges: " + edges)

  def combineEquivalenceClasses: Boolean = {
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

}
