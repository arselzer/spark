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

import org.apache.spark.sql.catalyst.optimizer.RewriteJoinsAsSemijoins
import org.apache.spark.sql.catalyst.plans.logical.{CountJoin, LogicalPlan}
import org.apache.spark.sql.catalyst.rules.Rule

/**
 * PROTOTYPE - AQE runtime keep-or-revert for the count-join (Yannakakis) rewrite.
 *
 * The rewrite stashes the original Aggregate-over-join subtree on the rewritten subtree's root
 * (RewriteJoinsAsSemijoins.ORIGINAL_PLAN_TAG). This rule runs inside AQE re-optimization, where the
 * count-join's build side has materialized and its real row count is known. When that materialized
 * build is non-reducing, the rule swaps the whole rewritten subtree back to the stashed original,
 * so AQE re-plans it as a normal join (also recovering runtime filters / DPP / skew handling that
 * the rewrite otherwise loses). Reverting the whole subtree to the retained original avoids
 * reconstructing a join from the (possibly chained) CountJoin carrier fields.
 *
 * AQE is the only layer that sees TRUE materialized cardinality - the exact signal planning-time
 * statistics cannot provide and the reason static cost gates could not separate reducing from
 * non-reducing rewrites.
 *
 * DIVERGENCE CRITERION (NDV-free). Runtime stage stats carry rowCount/sizeInBytes but NO column NDV
 * (ShuffleExchangeExec.runtimeStatistics omits attributeStats). The decision keys on whether the
 * planning-time estimate the rewrite was chosen on held up: at rewrite time each CountJoin is
 * tagged with its STATIC build estimate (RewriteJoinsAsSemijoins.BUILD_ROWCOUNT_ESTIMATE_TAG); at
 * AQE re-optimization the build has materialized and its real row count is known. A count-join is
 * reverted only when its materialized build is large (>= the runtimeRevertMinBuildRows floor) AND
 * exceeds its static estimate by at least runtimeRevertDivergenceFactor - i.e. the estimate was
 * falsified upward, the q2/q34 pathology. This deliberately does NOT use build-vs-probe size: a
 * reducing win can have a large build that collapses high fan-out (its build can exceed the
 * probe), and a vs-probe ratio was measured to revert q25, a verified win. Keying on estimate
 * divergence keeps such wins (their estimate held) while still catching rewrites chosen on a stale
 * estimate. Missing materialized rowCount or estimate tag never reverts - conservative; the chance
 * recurs on the next re-optimization once the build materializes.
 *
 * NOTE: the factor is a heuristic still needing empirical calibration against the benchmark before
 * enabling in production; it is default-off (Long.MaxValue floor).
 */
object DemoteNonReducingCountJoin extends Rule[LogicalPlan] {

  override def apply(plan: LogicalPlan): LogicalPlan = {
    if (!conf.yannakakisRuntimeRevertEnabled) {
      return plan
    }
    val minBuildRows = BigInt(conf.yannakakisRuntimeRevertMinBuildRows)
    val divergenceFactor = conf.yannakakisRuntimeRevertDivergenceFactor
    plan.transformDown {
      case p if p.getTagValue(RewriteJoinsAsSemijoins.ORIGINAL_PLAN_TAG).isDefined &&
          isNonReducing(p, minBuildRows, divergenceFactor) =>
        val original = p.getTagValue(RewriteJoinsAsSemijoins.ORIGINAL_PLAN_TAG).get
        logInfo(log"Reverting non-reducing count-join rewrite to the original plan")
        original
    }
  }

  /**
   * True if any count-join in the tagged subtree is non-reducing by estimate divergence: its
   * materialized build (right) row count is at least `minBuildRows` AND at least `divergenceFactor`
   * times the static build estimate stashed at rewrite time. Uses only rowCount (no NDV). Returns
   * false when the materialized rowCount or the static-estimate tag is missing - conservative:
   * never revert on an unmaterialized build or an un-stashed estimate.
   */
  private def isNonReducing(
      plan: LogicalPlan, minBuildRows: BigInt, divergenceFactor: Double): Boolean = {
    plan.collectFirst {
      case cj: CountJoin
        if cj.right.stats.rowCount.exists { buildRows =>
          buildRows >= minBuildRows &&
            cj.getTagValue(RewriteJoinsAsSemijoins.BUILD_ROWCOUNT_ESTIMATE_TAG).exists { estimate =>
              buildRows.toDouble >= estimate.toDouble * divergenceFactor
            }
        } => cj
    }.isDefined
  }
}
