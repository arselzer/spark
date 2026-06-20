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
 * REDUCTION CRITERION (NDV-free). Runtime stage stats carry rowCount and sizeInBytes but NO column
 * NDV (ShuffleExchangeExec.runtimeStatistics omits attributeStats), so the decision uses only the
 * two materialized row counts reachable: the count-join's build (right) and probe (left) inputs.
 * A count-join EARNS its build cost by collapsing build-side fan-out: the verified wins build a
 * small dimension against a large fact probe (build << probe), so they reduce and are kept. A
 * count-join is judged non-reducing - and reverted - only when its materialized build is large
 * (>= the runtimeRevertMinBuildRows floor) AND did not collapse fan-out relative to the probe, i.e.
 * buildRows >= probeRows * runtimeRevertReductionFactor. That is the q2/q34 pathology (a big build
 * the rewrite was chosen for on a stale estimate). Missing row counts (stage not yet materialized)
 * never revert - the opportunity recurs on the next re-optimization once the build materializes.
 *
 * NOTE: the factor is a heuristic still needing empirical calibration against the benchmark before
 * enabling in production; it is default-off (Long.MaxValue floor). A secondary guard keyed on the
 * planner's own estimate being falsified (materialized build >> stashed static estimate) is a
 * documented refinement if the build-vs-probe ratio proves insufficient on real data.
 */
object DemoteNonReducingCountJoin extends Rule[LogicalPlan] {

  override def apply(plan: LogicalPlan): LogicalPlan = {
    if (!conf.yannakakisRuntimeRevertEnabled) {
      return plan
    }
    val minBuildRows = BigInt(conf.yannakakisRuntimeRevertMinBuildRows)
    val reductionFactor = conf.yannakakisRuntimeRevertReductionFactor
    plan.transformDown {
      case p if p.getTagValue(RewriteJoinsAsSemijoins.ORIGINAL_PLAN_TAG).isDefined &&
          isNonReducing(p, minBuildRows, reductionFactor) =>
        val original = p.getTagValue(RewriteJoinsAsSemijoins.ORIGINAL_PLAN_TAG).get
        logInfo(log"Reverting non-reducing count-join rewrite to the original plan")
        original
    }
  }

  /**
   * True if any count-join in the tagged subtree is non-reducing by materialized row counts: its
   * build (right) is at least `minBuildRows` AND did not collapse fan-out relative to its probe
   * (left), i.e. buildRows >= probeRows * `reductionFactor`. Uses only rowCount (no NDV). Returns
   * false when either row count is missing (not yet materialized) - conservative, never revert on
   * an unmaterialized estimate.
   */
  private def isNonReducing(
      plan: LogicalPlan, minBuildRows: BigInt, reductionFactor: Double): Boolean = {
    plan.collectFirst {
      case cj: CountJoin
        if cj.right.stats.rowCount.exists { buildRows =>
          buildRows >= minBuildRows &&
            cj.left.stats.rowCount.exists { probeRows =>
              buildRows.toDouble >= probeRows.toDouble * reductionFactor
            }
        } => cj
    }.isDefined
  }
}
