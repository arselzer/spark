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

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.{Alias, AttributeReference, EqualTo, Literal}
import org.apache.spark.sql.catalyst.optimizer.RewriteJoinsAsSemijoins
import org.apache.spark.sql.catalyst.plans.{Inner, SQLHelper}
import org.apache.spark.sql.catalyst.plans.logical.{CountJoin, JoinHint, LeafNode, LogicalPlan, Statistics}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.IntegerType

/**
 * Deterministic unit tests for the DemoteNonReducingCountJoin revert CRITERION, exercised directly
 * on hand-built CountJoin nodes with stubbed materialized row counts - no SparkSession, AQE or
 * shuffle, so it is the load-bearing proof that the criterion reverts non-reducing rewrites and
 * (crucially) never reverts the reducing ones (the 7 verified wins, which build a small dimension
 * against a large fact probe). The no-NDV property is built in: the stubs carry only rowCount,
 * matching what AQE runtime stage statistics provide.
 */
class DemoteNonReducingCountJoinSuite extends SparkFunSuite with SQLHelper {

  /** A leaf with a fixed materialized row count and nothing else (no NDV), like a runtime stage. */
  private case class StatStub(rows: Long, output: Seq[AttributeReference]) extends LeafNode {
    override def computeStats(): Statistics =
      Statistics(sizeInBytes = math.max(1L, rows) * 8, rowCount = Some(BigInt(rows)))
  }

  /**
   * A standard inner CountJoin (BuildRight) whose right input materializes to `buildRows`, tagged
   * with a static build estimate of `buildEstimate` (as the rewrite would stash). The probe is
   * fixed and irrelevant to the divergence criterion.
   */
  private def countJoin(buildRows: Long, buildEstimate: Long): CountJoin = {
    val l = AttributeReference("l", IntegerType)()
    val r = AttributeReference("r", IntegerType)()
    val cj = CountJoin(
      left = StatStub(1000, Seq(l)),
      right = StatStub(buildRows, Seq(r)),
      joinType = Inner,
      condition = Some(EqualTo(l, r)),
      countLeft = None,
      countRight = Some(Alias(Literal(1L), "cnt")()),
      aggregatesRight = Nil,
      groupRight = Nil,
      hint = JoinHint.NONE)
    cj.setTagValue(RewriteJoinsAsSemijoins.BUILD_ROWCOUNT_ESTIMATE_TAG, BigInt(buildEstimate))
    cj
  }

  private def tagged(cj: CountJoin, original: LogicalPlan): CountJoin = {
    cj.setTagValue(RewriteJoinsAsSemijoins.ORIGINAL_PLAN_TAG, original)
    cj
  }

  private val revertConf = Seq(
    SQLConf.YANNAKAKIS_RUNTIME_REVERT_ENABLED.key -> "true",
    SQLConf.YANNAKAKIS_RUNTIME_REVERT_MIN_BUILD_ROWS.key -> "1000",
    SQLConf.YANNAKAKIS_RUNTIME_REVERT_DIVERGENCE_FACTOR.key -> "4.0",
    // rowCount only survives stats estimation under CBO (size-only mode strips it).
    SQLConf.CBO_ENABLED.key -> "true")

  test("reverts a count-join whose materialized build diverges far above its estimate") {
    val original: LogicalPlan = StatStub(1, Seq(AttributeReference("o", IntegerType)()))
    // materialized 10.8M vs estimate 1000 = 10800x divergence (the q2/q34 pathology).
    val cj = tagged(countJoin(buildRows = 10800000, buildEstimate = 1000), original)
    withSQLConf(revertConf: _*) {
      assert(DemoteNonReducingCountJoin(cj) eq original)
    }
  }

  test("keeps a large build whose estimate HELD (the q25 reducing-win shape)") {
    val original: LogicalPlan = StatStub(1, Seq(AttributeReference("o", IntegerType)()))
    // materialized 5M, estimate 5M: a big build, but the estimate was accurate, so it is kept.
    // (A build-vs-probe ratio would have wrongly reverted this - the calibration finding.)
    val cj = tagged(countJoin(buildRows = 5000000, buildEstimate = 5000000), original)
    withSQLConf(revertConf: _*) {
      assert(DemoteNonReducingCountJoin(cj) eq cj)
    }
  }

  test("keeps a count-join whose build is below the floor") {
    val original: LogicalPlan = StatStub(1, Seq(AttributeReference("o", IntegerType)()))
    // 500 diverges 500x above the estimate (1) but is below the 1000-row floor, so it is kept.
    val cj = tagged(countJoin(buildRows = 500, buildEstimate = 1), original)
    withSQLConf(revertConf: _*) {
      assert(DemoteNonReducingCountJoin(cj) eq cj)
    }
  }

  test("no-op when runtime revert is disabled") {
    val original: LogicalPlan = StatStub(1, Seq(AttributeReference("o", IntegerType)()))
    val cj = tagged(countJoin(buildRows = 10800000, buildEstimate = 1000), original)
    withSQLConf(
      SQLConf.YANNAKAKIS_RUNTIME_REVERT_ENABLED.key -> "false",
      SQLConf.YANNAKAKIS_RUNTIME_REVERT_MIN_BUILD_ROWS.key -> "1000",
      SQLConf.CBO_ENABLED.key -> "true") {
      assert(DemoteNonReducingCountJoin(cj) eq cj)
    }
  }
}
