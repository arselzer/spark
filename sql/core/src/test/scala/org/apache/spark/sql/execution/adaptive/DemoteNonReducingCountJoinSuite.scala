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

  /** A standard inner CountJoin (BuildRight): `probeRows` left and `buildRows` right input. */
  private def countJoin(probeRows: Long, buildRows: Long): CountJoin = {
    val l = AttributeReference("l", IntegerType)()
    val r = AttributeReference("r", IntegerType)()
    CountJoin(
      left = StatStub(probeRows, Seq(l)),
      right = StatStub(buildRows, Seq(r)),
      joinType = Inner,
      condition = Some(EqualTo(l, r)),
      countLeft = None,
      countRight = Some(Alias(Literal(1L), "cnt")()),
      aggregatesRight = Nil,
      groupRight = Nil,
      hint = JoinHint.NONE)
  }

  private def tagged(cj: CountJoin, original: LogicalPlan): CountJoin = {
    cj.setTagValue(RewriteJoinsAsSemijoins.ORIGINAL_PLAN_TAG, original)
    cj
  }

  private val revertConf = Seq(
    SQLConf.YANNAKAKIS_RUNTIME_REVERT_ENABLED.key -> "true",
    SQLConf.YANNAKAKIS_RUNTIME_REVERT_MIN_BUILD_ROWS.key -> "1000",
    SQLConf.YANNAKAKIS_RUNTIME_REVERT_REDUCTION_FACTOR.key -> "1.0",
    // rowCount only survives stats estimation under CBO (size-only mode strips it).
    SQLConf.CBO_ENABLED.key -> "true")

  test("reverts a non-reducing count-join (large build, no fan-out reduction)") {
    val original: LogicalPlan = StatStub(1, Seq(AttributeReference("o", IntegerType)()))
    val cj = tagged(countJoin(probeRows = 10000, buildRows = 10800000), original)
    withSQLConf(revertConf: _*) {
      assert(DemoteNonReducingCountJoin(cj) eq original)
    }
  }

  test("keeps a reducing count-join (small build vs large fact probe = the 7-wins shape)") {
    val original: LogicalPlan = StatStub(1, Seq(AttributeReference("o", IntegerType)()))
    val cj = tagged(countJoin(probeRows = 10000000, buildRows = 5000), original)
    withSQLConf(revertConf: _*) {
      assert(DemoteNonReducingCountJoin(cj) eq cj)
    }
  }

  test("keeps a count-join whose build is below the floor") {
    val original: LogicalPlan = StatStub(1, Seq(AttributeReference("o", IntegerType)()))
    // build (500) > probe (10) so the reduction test alone would revert, but it is below the floor.
    val cj = tagged(countJoin(probeRows = 10, buildRows = 500), original)
    withSQLConf(revertConf: _*) {
      assert(DemoteNonReducingCountJoin(cj) eq cj)
    }
  }

  test("no-op when runtime revert is disabled") {
    val original: LogicalPlan = StatStub(1, Seq(AttributeReference("o", IntegerType)()))
    val cj = tagged(countJoin(probeRows = 10000, buildRows = 10800000), original)
    withSQLConf(
      SQLConf.YANNAKAKIS_RUNTIME_REVERT_ENABLED.key -> "false",
      SQLConf.YANNAKAKIS_RUNTIME_REVERT_MIN_BUILD_ROWS.key -> "1000",
      SQLConf.CBO_ENABLED.key -> "true") {
      assert(DemoteNonReducingCountJoin(cj) eq cj)
    }
  }
}
