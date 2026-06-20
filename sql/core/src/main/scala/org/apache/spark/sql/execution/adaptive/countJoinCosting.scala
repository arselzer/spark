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

package org.apache.spark.sql.execution.adaptive

import org.apache.spark.sql.errors.QueryExecutionErrors
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.sql.execution.exchange.ShuffleExchangeLike
import org.apache.spark.sql.execution.joins.{HashCountJoin, ShuffledJoin, SortMergeCountJoinExec}

/**
 * Cost for [[CountJoinAwareCostEvaluator]]: a lexicographic (skewJoins, countJoins, shuffles)
 * tuple. Skew dominates (more skew joins = lower cost, matching SimpleCostEvaluator); then
 * count-joins (fewer = lower, so a reverted count-join-free plan beats the rewritten one); then
 * shuffles (fewer = lower). The count-join tier sits ABOVE shuffles, so a revert that does not
 * reduce shuffle count (or increases it) is still adopted. It only breaks comparisons where the
 * count-join COUNT differs - a runtime revert - leaving skew/coalesce decisions (which never
 * change count-join count) identical to SimpleCostEvaluator.
 */
case class CountJoinAwareCost(numSkewJoins: Int, numCountJoins: Int, numShuffles: Int)
  extends Cost {
  override def compare(that: Cost): Int = that match {
    case CountJoinAwareCost(thatSkew, thatCountJoins, thatShuffles) =>
      if (numSkewJoins != thatSkew) {
        // more skew joins => lower cost (mirrors SimpleCostEvaluator packing -numSkewJoins high)
        thatSkew.compare(numSkewJoins)
      } else if (numCountJoins != thatCountJoins) {
        numCountJoins.compare(thatCountJoins)
      } else {
        numShuffles.compare(thatShuffles)
      }
    case _ =>
      throw QueryExecutionErrors.cannotCompareCostWithTargetCostError(that.toString)
  }
}

/**
 * AQE cost evaluator used ONLY when spark.sql.yannakakis.runtimeRevertEnabled is on. It adds a
 * count-join tier to [[SimpleCostEvaluator]]'s shuffle/skew cost so AQE adopts a runtime revert of
 * a non-reducing count-join ([[DemoteNonReducingCountJoin]]) even though the reverted plan does not
 * reduce - and usually increases - shuffle count (reverting collapses the count-join chain into a
 * multi-way join). Because the count-join tier only decides comparisons where two plans differ in
 * count-join count, and the only AQE transform that changes it is the revert (skew splitting and
 * partition coalescing are count-join-neutral), every other AQE decision is identical to
 * SimpleCostEvaluator. Default-safe: never installed unless the revert feature is enabled.
 */
case class CountJoinAwareCostEvaluator(forceOptimizeSkewedJoin: Boolean) extends CostEvaluator {
  override def evaluateCost(plan: SparkPlan): Cost = {
    val numShuffles = plan.collect { case s: ShuffleExchangeLike => s }.size
    val numSkewJoins = if (forceOptimizeSkewedJoin) {
      plan.collect { case j: ShuffledJoin if j.isSkewJoin => j }.size
    } else {
      0
    }
    val numCountJoins = plan.collect {
      case _: HashCountJoin => ()
      case _: SortMergeCountJoinExec => ()
    }.size
    CountJoinAwareCost(numSkewJoins, numCountJoins, numShuffles)
  }
}
