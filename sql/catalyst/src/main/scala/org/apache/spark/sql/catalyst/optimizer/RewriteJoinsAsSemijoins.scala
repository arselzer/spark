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

import org.apache.spark.sql.catalyst.expressions.{Attribute, ExpressionSet, PredicateHelper}
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
          logWarning("items: " + items.toString() + ", conditions: " + conditions)
          print(items)
          val agg2 = agg.copy(groupingExpressions)
          agg
        case agg@Aggregate(groupingExpressions, aggExpressions,
          filter@Filter(filterConds,
            join@Join(_, _, _, _, _))) =>
          val (items, conditions) = extractInnerJoins(join)
          logWarning("items: " + items.toString() + ", conditions: " + conditions)
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
