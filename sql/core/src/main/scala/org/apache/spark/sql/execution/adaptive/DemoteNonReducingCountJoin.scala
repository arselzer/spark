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
 * CRITERION IS A PLACEHOLDER. It currently reverts when any count-join in the tagged subtree has a
 * materialized build-side row count >= spark.sql.yannakakis.runtimeRevertMinBuildRows (default
 * Long.MaxValue, i.e. never). Build size alone is NOT the right signal: the verified wins have
 * large but REDUCING builds, and reverting them would lose real speedups. The production criterion
 * must be reduction-aware - compare the materialized build (and where available output) row counts
 * against the rewrite break-even. The threshold knob lets tests exercise the swap directly.
 */
object DemoteNonReducingCountJoin extends Rule[LogicalPlan] {

  override def apply(plan: LogicalPlan): LogicalPlan = {
    if (!conf.yannakakisRuntimeRevertEnabled) {
      return plan
    }
    val minBuildRows = conf.yannakakisRuntimeRevertMinBuildRows
    plan.transformDown {
      case p if p.getTagValue(RewriteJoinsAsSemijoins.ORIGINAL_PLAN_TAG).isDefined &&
          isNonReducing(p, minBuildRows) =>
        val original = p.getTagValue(RewriteJoinsAsSemijoins.ORIGINAL_PLAN_TAG).get
        logInfo(log"Reverting non-reducing count-join rewrite to the original plan")
        original
    }
  }

  /**
   * True if any count-join in the tagged subtree has a materialized build (right) side whose row
   * count is at least `minBuildRows`. Stops descending into a nested tagged subtree so each rewrite
   * unit is judged on its own count-joins.
   */
  private def isNonReducing(plan: LogicalPlan, minBuildRows: Long): Boolean = {
    val threshold = BigInt(minBuildRows)
    plan.collectFirst {
      case cj: CountJoin if cj.right.stats.rowCount.exists(_ >= threshold) => cj
    }.isDefined
  }
}
