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

package org.apache.spark.sql

import java.sql.Date

import org.apache.spark.sql.execution.joins.{HashCountJoin, SortMergeCountJoinExec}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession

/**
 * Correctness tests for the Yannakakis/CountJoin rewrite:
 *  - distinct aggregates must never be decomposed via count multiplication
 *  - structurally invalid rewrites must fall back to the original plan
 *  - piecewise-guarded detection must account for join equivalences
 *  - unguarded rewrites must preserve per-group results (TPC-H Q7/Q9 shapes)
 */
class YannakakisCorrectnessSuite extends QueryTest with SharedSparkSession {

  // These tests force the rewrite at small scale; disable the broadcast cost gate (which
  // would otherwise keep the original plan because tiny relations are all broadcast-eligible).
  override protected def sparkConf: org.apache.spark.SparkConf =
    super.sparkConf.set(SQLConf.YANNAKAKIS_COST_GATE_ENABLED.key, "false")

  import testImplicits._

  private def d(s: String): Date = Date.valueOf(s)

  private val yannakakisOn = Seq(
    SQLConf.YANNAKAKIS_ENABLED.key -> "true",
    SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true")

  private val cyclicBagsOn =
    yannakakisOn :+ (SQLConf.YANNAKAKIS_CYCLIC_BAGS_ENABLED.key -> "true")

  private def cellsMatch(a: Any, b: Any): Boolean = (a, b) match {
    case (null, null) => true
    case (x: Double, y: Double) =>
      (x.isNaN && y.isNaN) || math.abs(x - y) <= 1e-6 * math.max(1.0, math.abs(x))
    case (x, y) => x == y
  }

  private def assertSameResults(query: String, hint: String): Unit = {
    var expected: Seq[Row] = null
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      expected = sql(query).collect().toSeq.sortBy(_.toString)
    }
    withSQLConf(yannakakisOn: _*) {
      val df = sql(query)
      val actual = df.collect().toSeq.sortBy(_.toString)
      val ok = expected.size == actual.size &&
        expected.zip(actual).forall { case (e, a) =>
          e.size == a.size && (0 until e.size).forall(i => cellsMatch(e.get(i), a.get(i)))
        }
      assert(ok,
        s"""$hint
           |expected: ${expected.mkString(" | ")}
           |actual  : ${actual.mkString(" | ")}
           |optimized plan:
           |${df.queryExecution.optimizedPlan}
           |executed plan:
           |${df.queryExecution.executedPlan}""".stripMargin)
    }
  }

  test("fan-out cost gate: skips a broadcast-friendly 2-table join, keeps a multi-way star") {
    Seq((1, 10.0), (1, 20.0), (2, 30.0)).toDF("k", "v").createOrReplaceTempView("g_fact")
    Seq(1, 1, 2).toDF("k").createOrReplaceTempView("g_d1")
    Seq(1, 2, 2).toDF("k").createOrReplaceTempView("g_d2")
    // degree-2 (fact-d1): broadcast-friendly + low fan-out -> the gate SKIPS (count-join overhead
    // would not pay). degree-3 star (fact-d1-d2 all on k): multiplicative fan-out -> gate KEEPS it.
    val q2 = "select sum(f.v) as s from g_fact f join g_d1 d1 on f.k = d1.k"
    val q3 = "select sum(f.v) as s from g_fact f join g_d1 d1 on f.k = d1.k " +
      "join g_d2 d2 on f.k = d2.k"
    var e2: Seq[Row] = null
    var e3: Seq[Row] = null
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      e2 = sql(q2).collect().toSeq; e3 = sql(q3).collect().toSeq
    }
    val gateOn = yannakakisOn :+ (SQLConf.YANNAKAKIS_COST_GATE_ENABLED.key -> "true")
    withSQLConf(gateOn: _*) {
      val df2 = sql(q2)
      checkAnswer(df2, e2)
      assert(!df2.queryExecution.optimizedPlan.toString.contains("CountJoin"),
        "gate should SKIP the broadcast-friendly degree-2 join")
      val df3 = sql(q3)
      checkAnswer(df3, e3)
      assert(df3.queryExecution.optimizedPlan.toString.contains("CountJoin"),
        "gate should KEEP the multi-way (degree-3) fan-out star")
    }
  }

  test("PROTOTYPE: AQE runtime revert swaps a non-reducing count-join back to the original") {
    // Multi-way star so the rewrite fires (gate off in this suite); counting aggregates.
    Seq((1, 10.0), (1, 20.0), (2, 30.0)).toDF("k", "v").createOrReplaceTempView("rv_fact")
    Seq(1, 1, 2).toDF("k").createOrReplaceTempView("rv_d1")
    Seq(1, 2, 2).toDF("k").createOrReplaceTempView("rv_d2")
    val q = "select f.k, sum(f.v) as s, count(*) as c from rv_fact f " +
      "join rv_d1 d1 on f.k = d1.k join rv_d2 d2 on f.k = d2.k group by f.k"
    var expected: Seq[Row] = null
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      expected = sql(q).collect().toSeq
    }
    // Precondition: the rewrite fires (count-join present) when revert is not engaged.
    withSQLConf(yannakakisOn: _*) {
      assert(sql(q).queryExecution.optimizedPlan.toString.contains("CountJoin"),
        "precondition: the rewrite should fire on this star")
    }
    // With AQE + revert + threshold 0, the materialized build triggers a swap back to the original.
    // autoBroadcast off so the count-join builds via a shuffle stage (materialized, stats present).
    // End-to-end SMOKE test of the swap mechanism (the reduction criterion itself is proven
    // deterministically in DemoteNonReducingCountJoinSuite). reductionFactor=0.0 + floor=0 make the
    // revert fire on this tiny star regardless of its (non-)reduction. Adoption uses the PRODUCTION
    // path: enabling runtimeRevert auto-installs CountJoinAwareCostEvaluator, whose count-join tier
    // makes the reverted (count-join-free) plan win even though it is not shuffle-cheaper. No
    // custom
    // cost-evaluator class is set here.
    val revertOn = yannakakisOn ++ Seq(
      SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
      SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1",
      SQLConf.YANNAKAKIS_RUNTIME_REVERT_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_RUNTIME_REVERT_MIN_BUILD_ROWS.key -> "0",
      SQLConf.YANNAKAKIS_RUNTIME_REVERT_DIVERGENCE_FACTOR.key -> "0.0")
    withSQLConf(revertOn: _*) {
      val df = sql(q)
      checkAnswer(df, expected) // correctness preserved across the swap
      // Inspect the FINAL adaptive plan only (executedPlan.collect descends into the current/final
      // physical plan, not the retained "== Initial Plan ==" display string).
      val countJoinsInFinal = df.queryExecution.executedPlan.collect {
        case p if p.nodeName.contains("CountJoin") => p.nodeName
      }
      assert(countJoinsInFinal.isEmpty,
        s"revert should have removed the count-join from the final plan, found: " +
          s"$countJoinsInFinal\n${df.queryExecution.executedPlan}")
    }
  }

  /** Higher-moment aggregate over a fan-out join: matches vanilla AND the count-join fired. */
  private def assertMomentAccelerated(query: String, hint: String): Unit = {
    assertSameResults(query, hint)
    withSQLConf(yannakakisOn: _*) {
      assert(sql(query).queryExecution.optimizedPlan.toString.contains("CountJoin"),
        s"$hint: expected the count-join to fire for the moment aggregate")
    }
  }

  test("VARIANCE/STDDEV over a fan-out join match vanilla and accelerate") {
    Seq((1, 10.0), (1, 20.0), (2, 30.0)).toDF("k", "v").createOrReplaceTempView("mv_fact")
    Seq(1, 1, 1, 2).toDF("k").createOrReplaceTempView("mv_dim") // k=1 fan-out 3, k=2 fan-out 1
    assertMomentAccelerated(
      """select var_samp(f.v) as vs, var_pop(f.v) as vp,
                stddev_samp(f.v) as ss, stddev_pop(f.v) as sp
         from mv_fact f join mv_dim d on f.k = d.k""",
      "variance/stddev over a fan-out join")
  }

  test("COVAR/CORR over a fan-out join match vanilla and accelerate") {
    Seq((1, 10.0, 1.0), (1, 20.0, 4.0), (2, 30.0, 9.0))
      .toDF("k", "x", "y").createOrReplaceTempView("mc_fact")
    Seq(1, 1, 1, 2).toDF("k").createOrReplaceTempView("mc_dim") // k=1 fan-out 3
    assertMomentAccelerated(
      """select covar_pop(x, y) as cp, covar_samp(x, y) as cs, corr(x, y) as cr
         from mc_fact f join mc_dim d on f.k = d.k""",
      "covar_pop/covar_samp/corr over a fan-out join")
  }

  test("SKEWNESS/KURTOSIS over a fan-out join match vanilla and accelerate") {
    Seq((1, 10.0), (1, 20.0), (1, 35.0), (2, 30.0))
      .toDF("k", "v").createOrReplaceTempView("mk_fact")
    Seq(1, 1, 2).toDF("k").createOrReplaceTempView("mk_dim") // k=1 fan-out 2
    assertMomentAccelerated(
      "select skewness(v) as sk, kurtosis(v) as ku from mk_fact f join mk_dim d on f.k = d.k",
      "skewness/kurtosis over a fan-out join")
  }

  test("REGR_SLOPE/INTERCEPT/R2/SXY over a fan-out join match vanilla and accelerate") {
    Seq((1, 10.0, 1.0), (1, 20.0, 4.0), (2, 30.0, 9.0))
      .toDF("k", "x", "y").createOrReplaceTempView("mg_fact")
    Seq(1, 1, 1, 2).toDF("k").createOrReplaceTempView("mg_dim") // k=1 fan-out 3
    assertMomentAccelerated(
      """select regr_slope(y, x) as sl, regr_intercept(y, x) as ic,
                regr_r2(y, x) as r2, regr_sxy(y, x) as sxy
         from mg_fact f join mg_dim d on f.k = d.k""",
      "regr_slope/intercept/r2/sxy over a fan-out join")
  }

  test("regr_* (runtime-replaceable) over a fan-out join match vanilla") {
    Seq((1, 10.0, 1.0), (1, 20.0, 4.0), (2, 30.0, 9.0))
      .toDF("k", "x", "y").createOrReplaceTempView("mr_fact")
    Seq(1, 1, 1, 2).toDF("k").createOrReplaceTempView("mr_dim")
    // regr_count/avgx/avgy/sxx/syy are RuntimeReplaceableAggregate -> Count/Average/var, which the
    // count-join already handles. Just assert correctness (these expand before the rule sees them).
    assertSameResults(
      """select regr_count(y, x) as rc, regr_avgx(y, x) as rax, regr_avgy(y, x) as ray
         from mr_fact f join mr_dim d on f.k = d.k""",
      "regr_count/avgx/avgy over a fan-out join")
  }

  test("VARIANCE grouped, integer measure, with count(*) matches vanilla") {
    Seq((1, "A", 5), (1, "A", 15), (2, "B", 30), (3, "B", 30))
      .toDF("k", "g", "v").createOrReplaceTempView("mv2_fact")
    Seq(1, 1, 2, 3, 3).toDF("k").createOrReplaceTempView("mv2_dim")
    assertMomentAccelerated(
      "select g, var_samp(v) as vs, stddev_pop(v) as sp, count(*) as c " +
        "from mv2_fact f join mv2_dim d on f.k = d.k group by g",
      "grouped variance with an integer measure")
  }

  /**
   * With the rewrite on, asserts the count-join is whole-stage-codegen'd and that running the
   * query with whole-stage codegen ON produces identical rows to running it with codegen OFF
   * (the interpreted oracle). `extraConf` lets a caller force the shuffled path etc.
   */
  private def assertCountJoinCodegenMatches(
      query: String, hint: String, extraConf: (String, String)*): Unit = {
    val physicalConf = if (extraConf.exists(_._1 == SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key) &&
        !extraConf.exists(_._1 == SQLConf.PREFER_SORTMERGEJOIN.key)) {
      extraConf :+ (SQLConf.PREFER_SORTMERGEJOIN.key -> "false")
    } else {
      extraConf
    }
    withSQLConf((yannakakisOn ++ physicalConf): _*) {
      // AQE off so the WholeStageCodegen `*(n)` markers are present in the static executedPlan
      // (under AQE they only appear after the plan is finalized at run time).
      val onPlan = withSQLConf(
        SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "true",
        SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "false") {
        sql(query).queryExecution.executedPlan.toString
      }
      assert(onPlan.linesIterator.exists(_.matches(".*\\*\\(\\d+\\).*HashCountJoin.*")),
        s"$hint: the count-join should be whole-stage-codegen'd:\n$onPlan")
      val on = withSQLConf(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "true") {
        sql(query).collect().toSeq.map(_.toString).sorted
      }
      val off = withSQLConf(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "false") {
        sql(query).collect().toSeq.map(_.toString).sorted
      }
      assert(on == off, s"$hint: codegen result $on != interpreted $off")
    }
    assertSameResults(query, hint)
  }

  /**
   * Runs `query` with the rewrite on, asserts the results equal the baseline (rewrite off)
   * AND that the non-guarded distinct path actually fired (logs "distinct-reduced").
   */
  private def assertDistinctReducedAndCorrect(query: String, hint: String): Unit = {
    var expected: Seq[Row] = null
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      expected = sql(query).collect().toSeq
    }
    val appender = new LogAppender("distinct-reduced rewrite")
    withLogAppender(appender) {
      withSQLConf(yannakakisOn: _*) {
        checkAnswer(sql(query), expected)
      }
    }
    val fired = appender.loggingEvents.exists(
      _.getMessage.getFormattedMessage.contains("new aggregate (distinct-reduced)"))
    assert(fired, s"$hint: expected the non-guarded distinct-reduced path to fire")
  }

  private def assertDecoratingDimensionPreAggAndCorrect(query: String, hint: String): Unit = {
    var expected: Seq[Row] = null
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      expected = sql(query).collect().toSeq
    }

    val appender = new LogAppender("decorating-dimension pre-aggregate rewrite")
    withLogAppender(appender) {
      withSQLConf(yannakakisOn: _*) {
        checkAnswer(sql(query), expected)
      }
    }
    val fired = appender.loggingEvents.exists(
      _.getMessage.getFormattedMessage.contains(
        "new aggregate (decorating-dimension pre-aggregate)"))
    assert(fired, s"$hint: expected the decorating-dimension pre-aggregate rewrite to fire")
  }

  private def assertNotRewrittenButCorrect(query: String, hint: String): Unit = {
    var expected: Seq[Row] = null
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      expected = sql(query).collect().toSeq
    }
    withSQLConf(yannakakisOn: _*) {
      val df = sql(query)
      checkAnswer(df, expected)
      val plan = df.queryExecution.optimizedPlan.toString
      assert(!plan.contains("CountJoin"),
        s"$hint: expected NOT to be rewritten:\n$plan")
    }
  }

  test("non-guarded count(distinct) over a 3-relation path is rewritten and correct") {
    Seq((1, 10)).toDF("g", "k").createOrReplaceTempView("nd_r1")
    Seq((10, 100), (10, 200)).toDF("k", "m").createOrReplaceTempView("nd_r2")
    Seq((100, 7), (200, 7), (200, 8)).toDF("m", "x").createOrReplaceTempView("nd_r3")
    // g in r1, x in r3 -> non-guarded; distinct x per g = {7,8} = 2
    assertDistinctReducedAndCorrect("""
      select g, count(distinct x) as c
      from nd_r1, nd_r2, nd_r3
      where nd_r1.k = nd_r2.k and nd_r2.m = nd_r3.m
      group by g""",
      "non-guarded count(distinct) 3-relation path")
  }

  test("non-guarded 0MA with a cross-relation filter is distinct-reduced and correct") {
    // Closes the limitation: a non-guarded duplicate-insensitive query (count(distinct x); g in r1,
    // x in r3) with a cross-relation filter (r1.a < r3.x, spanning the chain ENDS) used to bail
    // because the non-guarded distinct path didn't handle filters. It now carries the filter's
    // attributes through the reduction (inner-joining the connecting subtrees r1-r2-r3) and applies
    // the filter, exactly like the guarded 0MA path. That the filter spans the chain ends also
    // exercises the attribute-carry across distant relations (the subsetOf guard must NOT fire).
    Seq(("A", 1, 5), ("A", 2, 15)).toDF("g", "k", "a").createOrReplaceTempView("nf_r1")
    Seq((1, 10), (2, 20)).toDF("k", "m").createOrReplaceTempView("nf_r2")
    Seq((10, 7), (10, 8), (20, 12)).toDF("m", "x").createOrReplaceTempView("nf_r3")
    assertDistinctReducedAndCorrect("""
      select g, count(distinct x) as c
      from nf_r1, nf_r2, nf_r3
      where nf_r1.k = nf_r2.k and nf_r2.m = nf_r3.m and nf_r1.a < nf_r3.x
      group by g""",
      "non-guarded 0MA + cross-relation filter")
  }

  test("non-guarded sum(distinct) over a 3-relation path is rewritten and correct") {
    Seq((1, 10)).toDF("g", "k").createOrReplaceTempView("sd_r1")
    Seq((10, 100), (10, 200)).toDF("k", "m").createOrReplaceTempView("sd_r2")
    Seq((100, 7), (200, 7), (200, 8)).toDF("m", "x").createOrReplaceTempView("sd_r3")
    // distinct x per g = {7,8}; sum(distinct) = 15
    assertDistinctReducedAndCorrect("""
      select g, sum(distinct x) as s
      from sd_r1, sd_r2, sd_r3
      where sd_r1.k = sd_r2.k and sd_r2.m = sd_r3.m
      group by g""",
      "non-guarded sum(distinct) 3-relation path")
  }

  test("non-guarded count(distinct) with high-fanout middle relation stays correct") {
    Seq((1, 10), (2, 20)).toDF("g", "k").createOrReplaceTempView("hf_r1")
    Seq((10, 1), (10, 2), (10, 3), (20, 4)).toDF("k", "m").createOrReplaceTempView("hf_r2")
    Seq((1, 7), (2, 7), (3, 8), (4, 9)).toDF("m", "x").createOrReplaceTempView("hf_r3")
    // g=1: x over m{1,2,3} = {7,7,8} distinct = {7,8} = 2 ; g=2: m{4} = {9} = 1
    assertDistinctReducedAndCorrect("""
      select g, count(distinct x) as c
      from hf_r1, hf_r2, hf_r3
      where hf_r1.k = hf_r2.k and hf_r2.m = hf_r3.m
      group by g""",
      "non-guarded count(distinct) high fan-out")
  }

  test("non-guarded count(distinct) with NULL distinct values stays correct") {
    Seq((1, 10)).toDF("g", "k").createOrReplaceTempView("nn_r1")
    Seq((10, 100), (10, 200), (10, 300)).toDF("k", "m").createOrReplaceTempView("nn_r2")
    Seq((100, Some(7)), (200, None), (300, Some(8)))
      .toDF("m", "x").createOrReplaceTempView("nn_r3")
    // count(distinct x) ignores NULL -> {7,8} = 2
    assertDistinctReducedAndCorrect("""
      select g, count(distinct x) as c
      from nn_r1, nn_r2, nn_r3
      where nn_r1.k = nn_r2.k and nn_r2.m = nn_r3.m
      group by g""",
      "non-guarded count(distinct) with NULLs")
  }

  test("cross-relation filter eliminating a group leaves no phantom count-0 group") {
    Seq((1, 10, 100), (2, 20, 5)).toDF("g", "k", "a").createOrReplaceTempView("pz1")
    Seq((10, 50), (20, 99)).toDF("k", "b").createOrReplaceTempView("pz2")
    // a < b: g=1 (100<50 false) is eliminated; g=2 (5<99 true) kept. g=1 must NOT appear.
    assertSameResults(
      "select g, count(*) as c from pz1 join pz2 on pz1.k = pz2.k where a < b group by g",
      "cross-relation filter eliminating a group (phantom count-0)")
  }

  test("cross-relation filter eliminating a group leaves no spurious sum group") {
    Seq((1, 10, 100, 7.0), (2, 20, 5, 3.0)).toDF("g", "k", "a", "v")
      .createOrReplaceTempView("ps1")
    Seq((10, 50), (20, 99)).toDF("k", "b").createOrReplaceTempView("ps2")
    assertSameResults(
      "select g, sum(v) as s from ps1 join ps2 on ps1.k = ps2.k where a < b group by g",
      "cross-relation filter eliminating a group (spurious sum)")
  }

  test("countGroupInLeaves preserves grouping expression references") {
    Seq((1, "a"), (2, "b")).toDF("c_sk", "c_id")
      .createOrReplaceTempView("lg_customer")
    Seq((1, 10, 5), (1, 20, 7), (2, 10, 11)).toDF("c_sk", "d_sk", "v")
      .createOrReplaceTempView("lg_sales")
    Seq((10, 2001), (20, 2002)).toDF("d_sk", "yr")
      .createOrReplaceTempView("lg_date")

    val query =
      "select c_id, yr + 0 as gy, sum(v) as s " +
        "from lg_customer c, lg_sales s, lg_date d " +
        "where c.c_sk = s.c_sk and s.d_sk = d.d_sk " +
        "group by c_id, yr + 0"
    var expected: Seq[Row] = null
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      expected = sql(query).collect().toSeq
    }
    withSQLConf((yannakakisOn :+ (SQLConf.YANNAKAKIS_COUNT_GROUP_LEAVES.key -> "true")): _*) {
      val df = sql(query)
      checkAnswer(df, expected)
      assert(df.queryExecution.optimizedPlan.toString.contains("CountJoin"),
        s"expected CountJoin with leaf grouping enabled:\n${df.queryExecution.optimizedPlan}")
    }
  }

  test("count(*) over a fan-out join is correct via the count-join codegen path") {
    Seq((1, "x"), (2, "y"), (3, "z")).toDF("k", "v").createOrReplaceTempView("cc1")
    // fan-out + a dangling probe key (4) with no match on cc1
    Seq(1, 1, 2, 2, 2, 3, 4).toDF("k").createOrReplaceTempView("cc2")
    assertSameResults(
      "select count(*) as c from cc1 join cc2 on cc1.k = cc2.k",
      "count(*) over join (codegen count path)")
  }

  test("count(*) over a join with a cross-relation filter is correct (codegen path)") {
    Seq((1, 10), (2, 20), (3, 30)).toDF("k", "a").createOrReplaceTempView("cf_a")
    Seq((1, 5), (1, 25), (2, 15), (3, 35)).toDF("k", "b").createOrReplaceTempView("cf_b")
    // a < b filters some matches entirely (k=1 row a=10: only b=25 passes; k=3: none passes)
    assertSameResults(
      "select count(*) as c from cf_a join cf_b on cf_a.k = cf_b.k where a < b",
      "count(*) with cross-relation filter (codegen path)")
  }

  test("broadcast cost gate suppresses the count-join rewrite, allows it when no broadcast") {
    Seq((1, "a", 10.0), (2, "a", 20.0), (3, "b", 30.0))
      .toDF("k", "g", "b").createOrReplaceTempView("cg1")
    Seq(1, 1, 2, 3).toDF("k").createOrReplaceTempView("cg2")
    // guarded sum over a fan-out join -> count-join path (subject to the gate)
    val q = "select g, sum(b) as s from cg1 join cg2 on cg1.k = cg2.k group by g"
    var expected: Seq[Row] = null
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      expected = sql(q).collect().toSeq
    }
    // gate ON + tiny (broadcast-eligible) relations -> rewrite suppressed, still correct
    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_COST_GATE_ENABLED.key -> "true") {
      val df = sql(q)
      checkAnswer(df, expected)
      assert(!df.queryExecution.optimizedPlan.toString.contains("CountJoin"),
        "cost gate should suppress the rewrite when the baseline can broadcast")
    }
    // gate ON but broadcast disabled -> nothing broadcast-eligible -> rewrite fires
    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_COST_GATE_ENABLED.key -> "true",
      SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1") {
      val df = sql(q)
      checkAnswer(df, expected)
      assert(df.queryExecution.optimizedPlan.toString.contains("CountJoin"),
        "cost gate should allow the rewrite when no relation is broadcast-eligible")
    }
  }

  test("cost gate skips non-expanding joins with a column-stat unique side") {
    withTable("cg_unique_fact", "cg_unique_dim") {
      sql("CREATE TABLE cg_unique_fact (k INT, v DOUBLE) USING parquet")
      sql("CREATE TABLE cg_unique_dim (k INT) USING parquet")
      sql("INSERT INTO cg_unique_fact VALUES (1, 10.0), (2, 20.0), (3, 30.0)")
      sql("INSERT INTO cg_unique_dim VALUES (1), (2), (3)")

      val q = "select sum(f.v) as s from cg_unique_fact f join cg_unique_dim d on f.k = d.k"
      var expected: Seq[Row] = null
      withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
        expected = sql(q).collect().toSeq
      }

      val gateOnNoBroadcast = Seq(
        SQLConf.YANNAKAKIS_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_COST_GATE_ENABLED.key -> "true",
        SQLConf.CBO_ENABLED.key -> "true",
        SQLConf.PLAN_STATS_ENABLED.key -> "true",
        SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1")
      withSQLConf(gateOnNoBroadcast: _*) {
        val withoutColStats = sql(q)
        checkAnswer(withoutColStats, expected)
        assert(withoutColStats.queryExecution.optimizedPlan.toString.contains("CountJoin"),
          "without column stats, broadcast-disabled cost gate should keep the rewrite")
      }

      sql("ANALYZE TABLE cg_unique_dim COMPUTE STATISTICS FOR COLUMNS k")
      withSQLConf(gateOnNoBroadcast: _*) {
        val withUniqueSideStats = sql(q)
        checkAnswer(withUniqueSideStats, expected)
        assert(!withUniqueSideStats.queryExecution.optimizedPlan.toString.contains("CountJoin"),
          "cost gate should skip a non-expanding join with a unique column-stat side")
      }
    }
  }

  test("no-op dimension elimination drops unreferenced PK-FK joins, keeps filtered ones") {
    withTable("noop_fact", "noop_unref", "noop_filt") {
      sql("CREATE TABLE noop_fact (fk INT, dk INT, v DOUBLE) USING parquet")
      sql("CREATE TABLE noop_unref (k INT, label STRING) USING parquet")   // no-op: unfiltered
      sql("CREATE TABLE noop_filt (k INT, yr INT) USING parquet")          // selective dimension
      sql("INSERT INTO noop_fact VALUES (1,100,1.0),(2,100,2.0),(3,200,3.0),(1,100,4.0)")
      sql("INSERT INTO noop_unref VALUES (1,'a'),(2,'b'),(3,'c')")          // k unique, covers fk
      sql("INSERT INTO noop_filt VALUES (100,2001),(200,2002)")            // k unique

      // noop_unref is joined on its unique key to a non-null fact FK and NONE of its columns are
      // referenced -> a no-op that must be eliminated. noop_filt is joined the same way but its
      // WHERE yr=2001 makes the join SELECTIVE (drops dk=200) -> must NOT be eliminated.
      val q =
        """select f.dk, count(*) c, sum(f.v) s
           from noop_fact f
           join noop_unref n on f.fk = n.k
           join noop_filt d on f.dk = d.k
           where d.yr = 2001
           group by f.dk"""
      var expected: Seq[Row] = null
      withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
        expected = sql(q).collect().toSeq   // d.yr=2001 keeps only dk=100: (100, 3, 7.0)
      }

      sql("ANALYZE TABLE noop_unref COMPUTE STATISTICS FOR COLUMNS k")
      sql("ANALYZE TABLE noop_filt COMPUTE STATISTICS FOR COLUMNS k")
      sql("ANALYZE TABLE noop_fact COMPUTE STATISTICS FOR COLUMNS fk, dk")
      val cfg = Seq(
        SQLConf.YANNAKAKIS_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_COST_GATE_ENABLED.key -> "false",
        SQLConf.YANNAKAKIS_ELIMINATE_NOOP_DIMS_ENABLED.key -> "true",  // default off; force on here
        SQLConf.CBO_ENABLED.key -> "true",
        SQLConf.PLAN_STATS_ENABLED.key -> "true")
      withSQLConf(cfg: _*) {
        val df = sql(q)
        checkAnswer(df, expected)   // correctness: result must be unchanged by the elimination
        val plan = df.queryExecution.optimizedPlan.toString
        assert(!plan.contains("noop_unref"),
          "unfiltered, unreferenced PK-FK dimension noop_unref should be eliminated:\n" + plan)
        assert(plan.contains("noop_filt"),
          "selective (filtered) dimension noop_filt must NOT be eliminated:\n" + plan)
      }
      // With the flag off, nothing is eliminated (and results stay correct).
      withSQLConf((cfg :+
          (SQLConf.YANNAKAKIS_ELIMINATE_NOOP_DIMS_ENABLED.key -> "false")): _*) {
        val df = sql(q)
        checkAnswer(df, expected)
        assert(df.queryExecution.optimizedPlan.toString.contains("noop_unref"),
          "with elimination disabled, noop_unref should be retained")
      }
    }
  }

  test("decorating dimension pre-aggregate preserves duplicate dimension rows") {
    Seq((1, 10, 5L), (1, 20, 7L), (2, 10, 11L), (3, 10, 13L))
      .toDF("c_sk", "d_sk", "v").createOrReplaceTempView("dd_sales")
    Seq((10, 2001), (20, 2002)).toDF("d_sk", "yr").createOrReplaceTempView("dd_date")
    Seq(
      (1, "A", "N"),
      (1, "A", "N"),
      (2, "A", "S"),
      (2, "B", "S"),
      (4, "Z", "X")).toDF("c_sk", "seg", "region")
      .createOrReplaceTempView("dd_customer")

    assertDecoratingDimensionPreAggAndCorrect("""
      select seg, region, yr, count(*) as cnt, sum(v) as total
      from dd_sales s
      join dd_customer c on s.c_sk = c.c_sk
      join dd_date d on s.d_sk = d.d_sk
      group by seg, region, yr""",
      "decorating customer attributes should be joined after partial aggregation")
  }

  test("non-guarded collect_set rides the distinct-reduced path and is correct") {
    Seq((1, 10), (2, 20)).toDF("g", "k").createOrReplaceTempView("cs_r1")
    Seq((10, 100), (10, 200), (20, 300)).toDF("k", "m").createOrReplaceTempView("cs_r2")
    // each group collects a single distinct value (via fan-out) so the array is
    // order-deterministic for comparison: g=1 -> [7], g=2 -> [8]
    Seq((100, 7), (200, 7), (300, 8)).toDF("m", "x").createOrReplaceTempView("cs_r3")
    assertDistinctReducedAndCorrect("""
      select g, collect_set(x) as xs
      from cs_r1, cs_r2, cs_r3
      where cs_r1.k = cs_r2.k and cs_r2.m = cs_r3.m
      group by g""",
      "non-guarded collect_set")
  }

  test("non-guarded approx_count_distinct rides the distinct-reduced path and is correct") {
    Seq((1, 10)).toDF("g", "k").createOrReplaceTempView("ac_r1")
    Seq((10, 100), (10, 200)).toDF("k", "m").createOrReplaceTempView("ac_r2")
    Seq((100, 7), (200, 7), (200, 8)).toDF("m", "x").createOrReplaceTempView("ac_r3")
    assertDistinctReducedAndCorrect("""
      select g, approx_count_distinct(x) as c
      from ac_r1, ac_r2, ac_r3
      where ac_r1.k = ac_r2.k and ac_r2.m = ac_r3.m
      group by g""",
      "non-guarded approx_count_distinct")
  }

  test("non-guarded count(distinct) grouped by a derived expression is correct") {
    Seq((1, 10), (2, 20)).toDF("g", "k").createOrReplaceTempView("dg_r1")
    Seq((10, 100), (20, 200)).toDF("k", "m").createOrReplaceTempView("dg_r2")
    Seq((100, 7), (200, 8)).toDF("m", "x").createOrReplaceTempView("dg_r3")
    // group by a derived expression over a base attr (g) in a different relation than x
    assertDistinctReducedAndCorrect("""
      select g * 10 as g10, count(distinct x) as c
      from dg_r1, dg_r2, dg_r3
      where dg_r1.k = dg_r2.k and dg_r2.m = dg_r3.m
      group by g * 10""",
      "non-guarded count(distinct) by derived group key")
  }

  test("non-guarded multiple distinct args in different relations are correct") {
    Seq((1, 10, 100)).toDF("g", "k", "y").createOrReplaceTempView("mt_r1")
    Seq((10, 1), (10, 2)).toDF("k", "m").createOrReplaceTempView("mt_r2")
    Seq((1, 7), (2, 7), (2, 8)).toDF("m", "x").createOrReplaceTempView("mt_r3")
    // distinct x in r3, distinct y in r1 -> both args carried to the common node
    assertDistinctReducedAndCorrect("""
      select g, count(distinct x) as cx, count(distinct y) as cy
      from mt_r1, mt_r2, mt_r3
      where mt_r1.k = mt_r2.k and mt_r2.m = mt_r3.m
      group by g""",
      "non-guarded multiple distinct args")
  }

  test("non-guarded mixed distinct and additive aggregates split and stay correct") {
    Seq((1, 10)).toDF("g", "k").createOrReplaceTempView("md_r1")
    Seq((10, 100), (10, 200)).toDF("k", "m").createOrReplaceTempView("md_r2")
    Seq((100, 7, 1.0), (200, 8, 2.0)).toDF("m", "x", "v").createOrReplaceTempView("md_r3")
    // count(distinct x) + sum(v) over a 3-relation path: mixed duplicate-insensitive + counting.
    // The rewrite splits into a distinct half (0MA semijoin reduction) and a counting half
    // (count-join), rejoined on g. Both halves accelerate, so the split fires.
    val query = """
      select g, count(distinct x) as c, sum(v) as s
      from md_r1, md_r2, md_r3
      where md_r1.k = md_r2.k and md_r2.m = md_r3.m
      group by g"""
    var expected: Seq[Row] = null
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      expected = sql(query).collect().toSeq
    }
    withSQLConf(yannakakisOn: _*) {
      val df = sql(query)
      checkAnswer(df, expected)
      assert(df.queryExecution.optimizedPlan.toString.contains("CountJoin"),
        "mixed distinct + additive should split and rewrite (counting half -> CountJoin)")
    }
  }

  test("count(distinct) over fan-out join returns correct results (Q16 shape)") {
    Seq(
      (1, "Brand#12", "STEEL", 14),
      (2, "Brand#13", "COPPER", 23),
      (3, "Brand#14", "TIN", 9)
    ).toDF("p_partkey", "p_brand", "p_type", "p_size").createOrReplaceTempView("part_t")
    Seq(
      (1, 10), (1, 20), (2, 10), (2, 20), (3, 20), (3, 30)
    ).toDF("ps_partkey", "ps_suppkey").createOrReplaceTempView("partsupp_t")

    val query = """
      select p_brand, p_type, p_size, count(distinct ps_suppkey) as supplier_cnt
      from partsupp_t, part_t
      where p_partkey = ps_partkey
      group by p_brand, p_type, p_size"""

    assertSameResults(query, "count(distinct) must not be broken by the rewrite")
  }

  test("sum(distinct) under fan-out is not decomposed via counts") {
    Seq(
      (1, "A", 5.0),
      (2, "A", 5.0),
      (3, "B", 7.0)
    ).toDF("k", "g", "b").createOrReplaceTempView("dist_t1")
    // fan-out: k=1 matches three times, k=2 twice, k=3 once
    Seq(1, 1, 1, 2, 2, 3).toDF("k").createOrReplaceTempView("dist_t2")

    // sum(distinct b) per g: A -> 5.0 (one distinct value), B -> 7.0
    val query = """
      select g, sum(distinct b) as s
      from dist_t1 join dist_t2 on dist_t1.k = dist_t2.k
      group by g"""

    assertSameResults(query, "sum(distinct) must not be multiplied by counts")
  }

  test("aggregate with FILTER clause is not rewritten incorrectly") {
    Seq(
      (1, "A", 5.0),
      (2, "A", -3.0)
    ).toDF("k", "g", "b").createOrReplaceTempView("filt_t1")
    Seq(1, 1, 2).toDF("k").createOrReplaceTempView("filt_t2")

    val query = """
      select g, count(b) filter (where b > 0) as cnt
      from filt_t1 join filt_t2 on filt_t1.k = filt_t2.k
      group by g"""

    assertSameResults(query, "FILTER clause must be respected by the rewrite")
  }

  test("Q3 shape is piecewise-guarded via join equivalence (rewrites without unguarded)") {
    Seq(
      (1, "BUILDING"),
      (2, "AUTO")
    ).toDF("c_custkey", "c_mktsegment").createOrReplaceTempView("customer_t")
    Seq(
      (10, 1, d("1995-02-01"), 0),
      (20, 1, d("1995-03-01"), 1),
      (30, 2, d("1995-02-15"), 0)
    ).toDF("o_orderkey", "o_custkey", "o_orderdate", "o_shippriority")
      .createOrReplaceTempView("orders_t")
    Seq(
      (10, 1000.0, 0.05),
      (10, 500.0, 0.10),
      (20, 700.0, 0.00),
      (30, 300.0, 0.20)
    ).toDF("l_orderkey", "l_extendedprice", "l_discount")
      .createOrReplaceTempView("lineitem_t")

    // group attrs {l_orderkey, o_orderdate, o_shippriority}: covered by orders_t only
    // modulo l_orderkey = o_orderkey; agg refs within lineitem_t => piecewise-guarded
    val query = """
      select l_orderkey, o_orderdate, o_shippriority,
             sum(l_extendedprice * (1 - l_discount)) as revenue
      from customer_t, orders_t, lineitem_t
      where c_mktsegment = 'BUILDING' and c_custkey = o_custkey
        and l_orderkey = o_orderkey
      group by l_orderkey, o_orderdate, o_shippriority"""

    var expected: Seq[Row] = null
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      expected = sql(query).collect().toSeq
    }
    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "false") {
      val df = sql(query)
      val applied = df.queryExecution.executedPlan.toString.contains("CountJoin")
      assert(applied, "expected pwg classification to rewrite the plan " +
        "(group attrs covered by orders via l_orderkey = o_orderkey)")
      checkAnswer(df, expected)
    }
  }

  test("sort-merge count-join with cross-relation filter does not early-terminate") {
    // Faithful TPC-H Q7 structure (6-way join, two nation refs, disjunctive cross-relation
    // filter, grouping). Several joined rows fail the FRANCE/GERMANY filter; if the
    // sort-merge count join terminates its scan on the first all-filtered left row,
    // surviving groups are silently dropped.
    Seq((1, "FRANCE"), (2, "GERMANY"), (3, "BRAZIL"), (4, "ARGENTINA"))
      .toDF("n_nationkey", "n_name").createOrReplaceTempView("nation_q7")
    Seq((10, 1), (20, 2), (30, 3), (40, 4))
      .toDF("s_suppkey", "s_nationkey").createOrReplaceTempView("supplier_q7")
    Seq((100, 2), (200, 1), (300, 3), (400, 4))
      .toDF("c_custkey", "c_nationkey").createOrReplaceTempView("customer_q7")
    Seq((1000, 100), (2000, 200), (3000, 300), (4000, 400))
      .toDF("o_orderkey", "o_custkey").createOrReplaceTempView("orders_q7")
    // (orderkey, suppkey) -> (supp_nation, cust_nation):
    //   o1000->c100(GERMANY); o2000->c200(FRANCE); o3000->c300(BRAZIL); o4000->c400(ARGENTINA)
    Seq(
      (3000, 30, 100.0, d("1995-06-01")), // BRAZIL/BRAZIL       - filtered out
      (4000, 40, 50.0, d("1995-06-01")),  // ARGENTINA/ARGENTINA - filtered out
      (1000, 10, 10.0, d("1995-06-01")),  // FRANCE/GERMANY      - kept
      (2000, 20, 20.0, d("1996-06-01")),  // GERMANY/FRANCE      - kept
      (3000, 40, 30.0, d("1995-06-01")),  // BRAZIL/ARGENTINA    - filtered out
      (1000, 10, 5.0, d("1996-06-01"))    // FRANCE/GERMANY      - kept
    ).toDF("l_orderkey", "l_suppkey", "l_extendedprice", "l_shipdate")
      .createOrReplaceTempView("lineitem_q7")

    val query = """
      select supp_nation, cust_nation, l_year, sum(volume) as revenue
      from (
        select n1.n_name as supp_nation, n2.n_name as cust_nation,
               extract(year from l_shipdate) as l_year, l_extendedprice as volume
        from supplier_q7, lineitem_q7, orders_q7, customer_q7,
             nation_q7 n1, nation_q7 n2
        where s_suppkey = l_suppkey and o_orderkey = l_orderkey
          and c_custkey = o_custkey and s_nationkey = n1.n_nationkey
          and c_nationkey = n2.n_nationkey
          and ((n1.n_name = 'FRANCE' and n2.n_name = 'GERMANY')
            or (n1.n_name = 'GERMANY' and n2.n_name = 'FRANCE'))
      ) shipping
      group by supp_nation, cust_nation, l_year"""

    var expected: Seq[Row] = null
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      expected = sql(query).collect().toSeq
    }
    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1",
      SQLConf.PREFER_SORTMERGEJOIN.key -> "true",
      "spark.sql.join.forceApplyShuffledHashJoin" -> "false") {
      val df = sql(query)
      val plan = df.queryExecution.executedPlan.toString
      assert(plan.contains("SortMergeCountJoin"), s"expected SortMergeCountJoin:\n$plan")
      checkAnswer(df, expected)
    }
  }

  test("Q7 shape: groups differing only in derived year keep distinct values") {
    Seq(
      (3, "FRANCE"),
      (4, "GERMANY")
    ).toDF("n_nationkey", "n_name").createOrReplaceTempView("nation_t")
    Seq(
      (2, 3) // supplier 2 in FRANCE
    ).toDF("s_suppkey", "s_nationkey").createOrReplaceTempView("supplier_t")
    Seq(
      (2, 4) // customer 2 in GERMANY
    ).toDF("c_custkey", "c_nationkey").createOrReplaceTempView("customer_t7")
    Seq(
      (2, 2)
    ).toDF("o_orderkey", "o_custkey").createOrReplaceTempView("orders_t7")
    Seq(
      (2, 2, 400.0, 0.00, d("1995-08-01")),
      (2, 2, 600.0, 0.10, d("1996-01-15"))
    ).toDF("l_orderkey", "l_suppkey", "l_extendedprice", "l_discount", "l_shipdate")
      .createOrReplaceTempView("lineitem_t7")

    val query = """
      select supp_nation, cust_nation, l_year, sum(volume) as revenue
      from (
        select n1.n_name as supp_nation, n2.n_name as cust_nation,
               extract(year from l_shipdate) as l_year,
               l_extendedprice * (1 - l_discount) as volume
        from supplier_t, lineitem_t7, orders_t7, customer_t7, nation_t n1, nation_t n2
        where s_suppkey = l_suppkey and o_orderkey = l_orderkey
          and c_custkey = o_custkey and s_nationkey = n1.n_nationkey
          and c_nationkey = n2.n_nationkey
      ) as shipping
      group by supp_nation, cust_nation, l_year"""

    assertSameResults(query, "per-year groups must not collapse (TPC-H Q7 shape)")
  }

  private def assertRewrittenAndCorrect(
      query: String, hint: String, planMarker: String = "CountJoin"): Unit = {
    var expected: Seq[Row] = null
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      expected = sql(query).collect().toSeq
    }
    withSQLConf(yannakakisOn: _*) {
      val df = sql(query)
      val plan = df.queryExecution.optimizedPlan.toString +
        df.queryExecution.executedPlan.toString
      assert(plan.contains(planMarker),
        s"$hint: expected the plan to be rewritten (marker '$planMarker' missing):\n" +
          df.queryExecution.optimizedPlan)
      checkAnswer(df, expected)
    }
  }

  test("filtered sum over fan-out join is rewritten and correct") {
    Seq((1, 10.0), (2, 20.0), (3, 30.0)).toDF("k", "x")
      .createOrReplaceTempView("ft1")
    Seq((1, 1), (1, 0), (1, 1), (2, 1), (3, 0)).toDF("k", "flag")
      .createOrReplaceTempView("ft2")

    // desugared CASE input spans both relations (Q14 shape) => unguarded count-join path
    assertRewrittenAndCorrect("""
      select sum(x) filter (where flag = 1) as s, sum(x) as total
      from ft1 join ft2 on ft1.k = ft2.k""",
      "sum FILTER must be desugared and rewritten")
  }

  test("filtered count over fan-out join is rewritten and correct") {
    Seq((1, 10.0), (2, 20.0), (3, 30.0)).toDF("k", "x")
      .createOrReplaceTempView("fc1")
    Seq((1, 1), (1, 0), (1, 1), (2, 1), (3, 0)).toDF("k", "flag")
      .createOrReplaceTempView("fc2")

    assertRewrittenAndCorrect("""
      select count(*) filter (where flag = 1) as c, count(*) as total
      from fc1 join fc2 on fc1.k = fc2.k""",
      "count FILTER must be desugared and rewritten")
  }

  test("guarded filtered count is rewritten and correct") {
    Seq((1, "A", 5.0), (2, "A", -3.0), (3, "B", 7.0)).toDF("k", "g", "x")
      .createOrReplaceTempView("gfc1")
    Seq(1, 1, 1, 2, 2, 3).toDF("k").createOrReplaceTempView("gfc2")

    // group + agg refs all in gfc1 => guarded counting path with a CASE count input
    assertRewrittenAndCorrect("""
      select g, count(*) filter (where x > 0) as c
      from gfc1 join gfc2 on gfc1.k = gfc2.k
      group by g""",
      "guarded count FILTER must be desugared and rewritten")
  }

  test("conditional one-zero sum across relations is carried by count-join") {
    Seq(
      (1, 10, 100, 5, 1),
      (2, 20, 200, 50, 1),
      (3, 30, 300, 90, 2))
      .toDF("ss_ticket_number", "ss_item_sk", "ss_customer_sk", "ss_sold_date_sk",
        "ss_store_sk")
      .createOrReplaceTempView("q50_sales_t")
    Seq(
      (1, 10, 100, 20),
      (1, 10, 100, 40),
      (2, 20, 200, 70),
      (3, 30, 300, 250))
      .toDF("sr_ticket_number", "sr_item_sk", "sr_customer_sk", "sr_returned_date_sk")
      .createOrReplaceTempView("q50_returns_t")
    Seq((1, "A"), (2, "B")).toDF("s_store_sk", "s_store_name")
      .createOrReplaceTempView("q50_store_t")
    Seq((20, 2001, 8), (40, 2001, 8), (70, 2001, 8), (250, 2001, 8))
      .toDF("d_date_sk", "d_year", "d_moy")
      .createOrReplaceTempView("q50_date_t")

    val query = """
      select s_store_name,
             sum(case when sr_returned_date_sk - ss_sold_date_sk <= 30 then 1 else 0 end) as d30,
             sum(case when sr_returned_date_sk - ss_sold_date_sk > 30 and
                       sr_returned_date_sk - ss_sold_date_sk <= 60 then 1 else 0 end) as d60,
             sum(case when sr_returned_date_sk - ss_sold_date_sk > 120 then 1 else 0 end) as d120
      from q50_sales_t, q50_returns_t, q50_store_t, q50_date_t
      where sr_returned_date_sk = d_date_sk and d_year = 2001 and d_moy = 8
        and ss_ticket_number = sr_ticket_number
        and ss_item_sk = sr_item_sk
        and ss_customer_sk = sr_customer_sk
        and ss_store_sk = s_store_sk
      group by s_store_name"""

    assertSameResults(query, "conditional one-zero bucket sums")
    withSQLConf((yannakakisOn :+ (SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "false")): _*) {
      val plan = sql(query).queryExecution.executedPlan
      val carryingCountJoins = plan.collect {
        case cj: HashCountJoin if cj.aggregatesRight.nonEmpty => cj
      }
      assert(carryingCountJoins.nonEmpty,
        s"""expected a count-join carrying conditional bucket aggregates:
           |$plan""".stripMargin)
    }
  }

  test("guarded count over nullable column does not overcount") {
    Seq((1, "A", Some(5.0)), (2, "A", None), (3, "B", Some(7.0)))
      .toDF("k", "g", "x").createOrReplaceTempView("nc1")
    Seq(1, 1, 1, 2, 2, 3).toDF("k").createOrReplaceTempView("nc2")

    // count(x) must skip the NULL x rows: A -> 3 (k=1 fan-out), not 5 (3+2)
    assertRewrittenAndCorrect("""
      select g, count(x) as c
      from nc1 join nc2 on nc1.k = nc2.k
      group by g""",
      "guarded count(nullable) must skip NULL inputs")
  }

  test("guarded product sum with fan-out is not multiplied per attribute") {
    Seq((1, "A", 2.0, 3.0), (2, "B", 5.0, 7.0)).toDF("k", "g", "x", "y")
      .createOrReplaceTempView("ps1")
    Seq(1, 1, 1, 2).toDF("k").createOrReplaceTempView("ps2")

    // correct: A -> 2*3*3 = 18, B -> 5*7*1 = 35; per-attribute count multiplication
    // would give (2*3)*(3*3) = 54 for A
    assertRewrittenAndCorrect("""
      select g, sum(x * y) as s
      from ps1 join ps2 on ps1.k = ps2.k
      group by g""",
      "guarded sum(x*y) must be multiplied by the count exactly once")
  }

  test("guarded count(distinct) is rewritten via semijoin reduction") {
    Seq((1, "A", 10), (2, "A", 10), (3, "A", 20), (4, "B", 30), (5, "B", 30))
      .toDF("k", "g", "x").createOrReplaceTempView("gd1")
    // fan-out + dangling tuple k=5 (no match); distinct sets: A -> {10, 20}, B -> {30}
    Seq(1, 1, 2, 3, 3, 3, 4).toDF("k").createOrReplaceTempView("gd2")

    assertRewrittenAndCorrect("""
      select g, count(distinct x) as c, max(x) as m
      from gd1 join gd2 on gd1.k = gd2.k
      group by g""",
      "guarded count(distinct) must use the duplicate-insensitive semijoin path",
      planMarker = "LeftSemi")
  }

  test("guarded sum(distinct) is rewritten via semijoin reduction") {
    Seq((1, "A", 10), (2, "A", 10), (3, "A", 20), (4, "B", 30), (5, "B", 99))
      .toDF("k", "g", "x").createOrReplaceTempView("gsd1")
    Seq(1, 1, 2, 3, 3, 4).toDF("k").createOrReplaceTempView("gsd2")

    assertRewrittenAndCorrect("""
      select g, sum(distinct x) as s
      from gsd1 join gsd2 on gsd1.k = gsd2.k
      group by g""",
      "guarded sum(distinct) must use the duplicate-insensitive semijoin path",
      planMarker = "LeftSemi")
  }

  test("mixed distinct and plain aggregates split and stay correct") {
    Seq((1, "A", 10, 1.0), (2, "A", 10, 2.0), (3, "B", 30, 3.0))
      .toDF("k", "g", "x", "y").createOrReplaceTempView("mx1")
    Seq(1, 1, 2, 3).toDF("k").createOrReplaceTempView("mx2")

    // count(distinct x) + sum(y), guarded (g, x, y all in mx1). The rewrite splits into a distinct
    // half (0MA) and a counting half (count-join) and rejoins on g; the count-multiplication for
    // sum(y) must not inflate count(distinct x).
    val query = """
      select g, count(distinct x) as c, sum(y) as s
      from mx1 join mx2 on mx1.k = mx2.k
      group by g"""
    var expected: Seq[Row] = null
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      expected = sql(query).collect().toSeq
    }
    withSQLConf(yannakakisOn: _*) {
      val df = sql(query)
      val plan = df.queryExecution.optimizedPlan.toString
      assert(plan.contains("CountJoin"),
        "mixed distinct+plain aggregates should split and rewrite:\n" + plan)
      checkAnswer(df, expected)
    }
  }

  test("min/max with cross-relation filter must not lose the filter (0MA gate)") {
    Seq((1, 5.0, 10.0), (2, 1.0, 10.0)).toDF("k", "x", "a")
      .createOrReplaceTempView("cf1")
    Seq((1, 20.0), (2, 5.0)).toDF("k", "b").createOrReplaceTempView("cf2")

    // a < b keeps only k=1 => min(x) = 5.0; a semijoin on k alone keeps both rows
    // and would wrongly return 1.0
    assertSameResults("""
      select min(x) as m
      from cf1 join cf2 on cf1.k = cf2.k
      where a < b""",
      "0MA path must not drop cross-relation non-equi filters")
  }

  // Regression guard for the count-join grouped-aggregation buffer-aliasing bug:
  // HashCountJoin.newBuffer() created each per-group aggregation buffer via a single shared
  // UnsafeProjection whose apply() returns the SAME reused physical UnsafeRow, so every entry
  // in bufferMap aliased one row and all groups collapsed to the last/first group's value.
  // Nation/supplier-rooted FROM orders are the ones that build a CountJoin with both grouping
  // (groupRight=[o_orderdate]) and a non-trivial right aggregate, so they triggered the
  // collapse (e.g. BRAZIL years all -> 850) while other roots did not. Fixed by copying the
  // buffer row in HashCountJoin.newBuffer() (the SortMergeCountJoin evaluator already did).
  test("Q9 shape: correct for every FROM-clause order (join-order robustness)") {
    val relations = Seq("part_t9", "supplier_t9", "lineitem_t9", "partsupp_t9",
      "orders_t9", "nation_t9")
    // rotations + reverse + a few swaps: enough to vary root choice and child join order
    val orders = (0 until relations.size).map(i =>
      relations.drop(i) ++ relations.take(i)) ++
      Seq(relations.reverse,
        Seq("partsupp_t9", "lineitem_t9", "part_t9", "supplier_t9", "nation_t9", "orders_t9"),
        Seq("orders_t9", "lineitem_t9", "partsupp_t9", "nation_t9", "supplier_t9", "part_t9"),
        Seq("nation_t9", "orders_t9", "part_t9", "partsupp_t9", "supplier_t9", "lineitem_t9"))
    for (fromOrder <- orders) {
      createQ9Tables()
      val query = s"""
        select nation, o_year, sum(amount) as sum_profit
        from (
          select n_name as nation, extract(year from o_orderdate) as o_year,
                 l_extendedprice * (1 - l_discount) - ps_supplycost * l_quantity as amount
          from ${fromOrder.mkString(", ")}
          where s_suppkey = l_suppkey and ps_suppkey = l_suppkey
            and ps_partkey = l_partkey and p_partkey = l_partkey
            and o_orderkey = l_orderkey and s_nationkey = n_nationkey
            and p_name like '%green%'
        ) as profit
        group by nation, o_year"""
      assertSameResults(query, s"Q9 with FROM order: ${fromOrder.mkString(", ")}")
    }
  }

  private def createQ9Tables(): Unit = {
    Seq(
      (1, "BRAZIL"),
      (3, "FRANCE")
    ).toDF("n_nationkey", "n_name").createOrReplaceTempView("nation_t9")
    Seq(
      (1, 1), // supplier 1 in BRAZIL
      (2, 3) // supplier 2 in FRANCE
    ).toDF("s_suppkey", "s_nationkey").createOrReplaceTempView("supplier_t9")
    Seq(
      (1, "forest green metal"),
      (3, "olive green widget")
    ).toDF("p_partkey", "p_name").createOrReplaceTempView("part_t9")
    Seq(
      (1, 1, 10.0), (1, 2, 12.0), (3, 2, 5.5)
    ).toDF("ps_partkey", "ps_suppkey", "ps_supplycost").createOrReplaceTempView("partsupp_t9")
    Seq(
      (4, d("1993-11-15")),
      (1, d("1995-02-01")),
      (5, d("1996-03-01"))
    ).toDF("o_orderkey", "o_orderdate").createOrReplaceTempView("orders_t9")
    Seq(
      (1, 1, 1, 10.0, 1000.0, 0.05),
      (4, 1, 1, 12.0, 1200.0, 0.10),
      (5, 1, 1, 200.0, 20000.0, 0.00),
      (1, 1, 2, 5.0, 500.0, 0.10),
      (5, 1, 2, 150.0, 15000.0, 0.05)
    ).toDF("l_orderkey", "l_partkey", "l_suppkey", "l_quantity", "l_extendedprice",
      "l_discount").createOrReplaceTempView("lineitem_t9")
  }

  test("Q9 shape: cross-relation product sums stay per-group") {
    createQ9Tables()
    val query = """
      select nation, o_year, sum(amount) as sum_profit
      from (
        select n_name as nation, extract(year from o_orderdate) as o_year,
               l_extendedprice * (1 - l_discount) - ps_supplycost * l_quantity as amount
        from part_t9, supplier_t9, lineitem_t9, partsupp_t9, orders_t9, nation_t9
        where s_suppkey = l_suppkey and ps_suppkey = l_suppkey
          and ps_partkey = l_partkey and p_partkey = l_partkey
          and o_orderkey = l_orderkey and s_nationkey = n_nationkey
          and p_name like '%green%'
      ) as profit
      group by nation, o_year"""

    assertSameResults(query, "per (nation, year) sums must not collapse (TPC-H Q9 shape)")
  }

  private val shuffledCountJoinConfs = Seq(
    SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1",
    SQLConf.PREFER_SORTMERGEJOIN.key -> "false",
    SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "false")

  private val sortMergeCountJoinConfs = Seq(
    SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1",
    SQLConf.PREFER_SORTMERGEJOIN.key -> "true",
    "spark.sql.join.forceApplyShuffledHashJoin" -> "false",
    SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "false")

  private def assertSameResultsOnCountJoinExec(
      query: String,
      hint: String,
      expectedExec: String,
      extraConfs: (String, String)*): Unit = {
    withSQLConf(extraConfs: _*) {
      withSQLConf(yannakakisOn: _*) {
        val plan = sql(query).queryExecution.executedPlan.toString
        assert(plan.contains(expectedExec), s"$hint: expected $expectedExec:\n$plan")
      }
      assertSameResults(query, hint)
    }
  }

  private val q9FromOrders: Seq[Seq[String]] = {
    val relations = Seq("part_t9", "supplier_t9", "lineitem_t9", "partsupp_t9",
      "orders_t9", "nation_t9")
    (0 until relations.size).map(i => relations.drop(i) ++ relations.take(i)) ++
      Seq(relations.reverse,
        Seq("partsupp_t9", "lineitem_t9", "part_t9", "supplier_t9", "nation_t9", "orders_t9"),
        Seq("orders_t9", "lineitem_t9", "partsupp_t9", "nation_t9", "supplier_t9", "part_t9"),
        Seq("nation_t9", "orders_t9", "part_t9", "partsupp_t9", "supplier_t9", "lineitem_t9"))
  }

  test("Q9 grouped count-join: per-group sums correct on shuffled-hash path") {
    // Nation-rooted order builds a CountJoin that both groups (groupRight) and aggregates -
    // the shape that exposed the buffer-aliasing bug.
    createQ9Tables()
    val fromOrder = Seq("supplier_t9", "lineitem_t9", "partsupp_t9",
      "orders_t9", "nation_t9", "part_t9")
    val query = s"""
      select nation, o_year, sum(amount) as sum_profit
      from (
        select n_name as nation, extract(year from o_orderdate) as o_year,
               l_extendedprice * (1 - l_discount) - ps_supplycost * l_quantity as amount
        from ${fromOrder.mkString(", ")}
        where s_suppkey = l_suppkey and ps_suppkey = l_suppkey
          and ps_partkey = l_partkey and p_partkey = l_partkey
          and o_orderkey = l_orderkey and s_nationkey = n_nationkey
          and p_name like '%green%'
      ) as profit
      group by nation, o_year"""
    assertSameResultsOnCountJoinExec(query, "Q9 grouped sums on shuffled-hash path",
      "ShuffledHashCountJoin", shuffledCountJoinConfs: _*)
  }

  test("count-join GROUP BY <expr>: correct on shuffled-hash path and FROM order") {
    // GROUP BY year(o_orderdate): the rewrite reduces by the underlying attribute o_orderdate
    // (carried in groupRight) and reconstructs year() at the final aggregate. Exercises a grouping
    // EXPRESSION (not a plain key), and a simple single-relation sum to isolate the grouping path
    // from product handling.
    for (fromOrder <- q9FromOrders) {
      createQ9Tables()
      val query = s"""
        select n_name as nation, year(o_orderdate) as o_year, sum(l_extendedprice) as rev
        from ${fromOrder.mkString(", ")}
        where s_suppkey = l_suppkey and ps_suppkey = l_suppkey
          and ps_partkey = l_partkey and p_partkey = l_partkey
          and o_orderkey = l_orderkey and s_nationkey = n_nationkey
          and p_name like '%green%'
        group by n_name, year(o_orderdate)"""
      assertSameResultsOnCountJoinExec(query,
        s"GROUP BY year(o_orderdate), order=${fromOrder.mkString(",")}",
        "ShuffledHashCountJoin", shuffledCountJoinConfs: _*)
    }
  }

  test("unguarded count-join is enabled by default (no explicit unguardedEnabled flag)") {
    createQ9Tables()
    val query = """
      select n_name as nation, extract(year from o_orderdate) as o_year,
             sum(l_extendedprice * (1 - l_discount)) as rev
      from part_t9, supplier_t9, lineitem_t9, partsupp_t9, orders_t9, nation_t9
      where s_suppkey = l_suppkey and ps_suppkey = l_suppkey
        and ps_partkey = l_partkey and p_partkey = l_partkey
        and o_orderkey = l_orderkey and s_nationkey = n_nationkey and p_name like '%green%'
      group by n_name, extract(year from o_orderdate)"""
    // Enable yannakakis, but do NOT set unguardedEnabled explicitly:
    // the unguarded count-join rewrite should fire because it now defaults to true.
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "true") {
      val plan = sql(query).queryExecution.executedPlan.toString
      assert(plan.contains("CountJoin"),
        s"an unguarded query should rewrite by default (unguardedEnabled):\n$plan")
    }
  }

  test("cross-relation filter: count-join fires and results match vanilla") {
    // a.x < b.y is a non-equi predicate spanning both relations (a cross-relation filter).
    // It must be folded into the count-join (rows failing it are dropped, not emitted as
    // phantom count-0 rows) and the rewrite must still fire, matching vanilla.
    Seq((1, 100, 5), (1, 200, 50), (2, 300, 1)).toDF("k", "v", "x")
      .createOrReplaceTempView("cf_a")
    Seq((1, 10), (1, 60), (2, 0)).toDF("k", "y").createOrReplaceTempView("cf_b")
    val query = "select sum(v) as s from cf_a a, cf_b b where a.k = b.k and a.x < b.y"
    withSQLConf(yannakakisOn: _*) {
      val plan = sql(query).queryExecution.executedPlan.toString
      assert(plan.contains("CountJoin"),
        s"expected the count-join rewrite to fire for a cross-relation filter:\n$plan")
    }
    assertSameResults(query, "cross-relation filter a.x < b.y")
  }

  test("0MA cross-relation filter: max with a.x < b.y rewrites and matches vanilla") {
    Seq((1, 100, 5), (1, 200, 50), (2, 300, 1)).toDF("k", "v", "x")
      .createOrReplaceTempView("cf_a")
    Seq((1, 10), (1, 60), (2, 0)).toDF("k", "y").createOrReplaceTempView("cf_b")
    // max is duplicate-insensitive (0MA path), and a.x < b.y is a cross-relation filter.
    val query = "select max(v) as m from cf_a a, cf_b b where a.k = b.k and a.x < b.y"
    val appender = new LogAppender("0MA rewrite with cross-relation filter")
    withLogAppender(appender) {
      withSQLConf(yannakakisOn: _*) {
        sql(query).collect()
      }
    }
    val fired = appender.loggingEvents.exists(
      _.getMessage.getFormattedMessage.contains("new aggregate (0MA)"))
    assert(fired, "expected the 0MA rewrite to fire for a cross-relation filter")
    assertSameResults(query, "0MA max with cross-relation filter a.x < b.y")
  }

  test("ANSI: count-multiplied SUM must equal vanilla and not overflow the value type") {
    // The rewrite emits SUM(v * count). If that product is computed in v's (narrow) type it can
    // overflow where vanilla's promoted Sum accumulator would not - throwing under ANSI=true and
    // silently wrapping (wrong result) under ANSI=false. Stress it: v near Int.MAX, multiplicity 2.
    Seq((1, 2000000000)).toDF("k", "v").createOrReplaceTempView("fact_ov")
    Seq(1, 1).toDF("k").createOrReplaceTempView("dim_ov")
    val query = "select sum(v) as s from fact_ov f, dim_ov d where f.k = d.k"
    for (ansi <- Seq("false", "true")) {
      withSQLConf(SQLConf.ANSI_ENABLED.key -> ansi) {
        assertSameResults(query, s"ANSI=$ansi: count-multiplied SUM(v) over multiplicity 2")
      }
    }
  }

  test("cross-relation filter: correct on shuffled-hash and sort-merge paths") {
    Seq((1, 100, 5), (1, 200, 50), (2, 300, 1)).toDF("k", "v", "x")
      .createOrReplaceTempView("cf_a")
    Seq((1, 10), (1, 60), (2, 0)).toDF("k", "y").createOrReplaceTempView("cf_b")
    val shuffleQuery = "select /*+ SHUFFLE_HASH(b) */ sum(v) as s " +
      "from cf_a a join cf_b b on a.k = b.k where a.x < b.y"
    assertSameResultsOnCountJoinExec(shuffleQuery,
      "cross-relation filter a.x < b.y on shuffled-hash path", "ShuffledHashCountJoin",
      shuffledCountJoinConfs: _*)

    val sortMergeQuery = "select /*+ MERGE(b) */ sum(v) as s " +
      "from cf_a a join cf_b b on a.k = b.k where a.x < b.y"
    assertSameResultsOnCountJoinExec(sortMergeQuery,
      "cross-relation filter a.x < b.y on sort-merge path", "SortMergeCountJoin",
      sortMergeCountJoinConfs: _*)
  }

  test("cross-relation filter: a fully-filtered group emits no phantom row") {
    // k=2's only match (x=1, y=0) fails a.x < b.y, so its rightCountSum is 0. An ungrouped sum
    // hides a phantom count-0 row (it contributes v*0=0), but grouped count(*) does not: vanilla
    // yields only k=1 (count 3); a phantom row would add a spurious k=2 group.
    Seq((1, 100, 5), (1, 200, 50), (2, 300, 1)).toDF("k", "v", "x")
      .createOrReplaceTempView("cf_a")
    Seq((1, 10), (1, 60), (2, 0)).toDF("k", "y").createOrReplaceTempView("cf_b")
    val shuffleQuery = "select /*+ SHUFFLE_HASH(b) */ a.k as k, count(*) as c " +
      "from cf_a a join cf_b b on a.k = b.k where a.x < b.y group by a.k"
    assertSameResultsOnCountJoinExec(shuffleQuery,
      "fully-filtered group on shuffled-hash path", "ShuffledHashCountJoin",
      shuffledCountJoinConfs: _*)

    val sortMergeQuery = "select /*+ MERGE(b) */ a.k as k, count(*) as c " +
      "from cf_a a join cf_b b on a.k = b.k where a.x < b.y group by a.k"
    assertSameResultsOnCountJoinExec(sortMergeQuery,
      "fully-filtered group on sort-merge path", "SortMergeCountJoin",
      sortMergeCountJoinConfs: _*)
  }

  test("grouped count-join correct under sort-merge spill (tiny in-memory threshold)") {
    // Exercise the SortMergeCountJoin buffered-matches spill path by capping the in-memory
    // threshold at 1 row while the hinted build side has multiple matches per key.
    Seq(1, 1, 2).toDF("k").createOrReplaceTempView("smjsp_a")
    Seq((1, "x"), (1, "x"), (2, "y")).toDF("k", "g").createOrReplaceTempView("smjsp_b")
    val query = "select /*+ MERGE(b) */ b.g, count(*) as c " +
      "from smjsp_a a join smjsp_b b on a.k = b.k group by b.g"
    assertSameResultsOnCountJoinExec(query, "grouped count-join under sort-merge spill",
      "SortMergeCountJoin", (sortMergeCountJoinConfs :+
        (SQLConf.SORT_MERGE_JOIN_EXEC_BUFFER_IN_MEMORY_THRESHOLD.key -> "1")): _*)
  }

  test("codegen: pure-count count-join matches interpreted (whole-stage on vs off)") {
    // 3-relation chain with DIFFERENT multiplicities (a:2 per k, c:3 per j) so a count side-swap
    // would change the total. count(*) carries only the count (aggregatesRight empty) - the
    // pure-count codegen path. Expected count = 2 * 1 * 3 = 6.
    Seq(1, 1).toDF("k").createOrReplaceTempView("cg_a")
    Seq((1, 10)).toDF("k", "j").createOrReplaceTempView("cg_b")
    Seq(10, 10, 10).toDF("j").createOrReplaceTempView("cg_c")
    val query = "select count(*) as c from cg_a a, cg_b b, cg_c c where a.k = b.k and b.j = c.j"
    assertCountJoinCodegenMatches(query, "pure-count 3-relation chain count(*)")
  }

  test("codegen: non-grouped count-only count-join avoids aggregate buffers") {
    // The logical CountJoin still carries a count(1) aggregate result, but the parent only consumes
    // the fan-out count. Codegen should skip the dead aggregate buffer work.
    Seq(1, 1, 2, 2, 2).toDF("k").createOrReplaceTempView("cng_a")
    Seq(1, 2, 3).toDF("k").createOrReplaceTempView("cng_b")
    val query = "select count(*) as c from cng_a a join cng_b b on a.k = b.k"
    withSQLConf((yannakakisOn ++ Seq(
        SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1",
        SQLConf.PREFER_SORTMERGEJOIN.key -> "false",
        SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "false",
        SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "true")): _*) {
      val plan = sql(query).queryExecution.executedPlan
      val countJoins = plan.collect {
        case cj: HashCountJoin if cj.groupRight.isEmpty => cj
      }
      assert(countJoins.nonEmpty, s"expected a non-grouped count-join in:\n$plan")
      val code = org.apache.spark.sql.execution.debug.codegenString(plan)
      assert(!code.contains("cjBuf"),
        s"non-grouped count-only codegen should not allocate aggregate buffers:\n$code")
    }
    assertCountJoinCodegenMatches(query, "non-grouped count-only",
      SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1")
  }

  // Forces the shuffled grouping count-join, asserts it is codegen-able, and that whole-stage
  // codegen ON produces the same rows as OFF (interpreted) and as vanilla. The gate for the
  // grouped-codegen buffer-update inlining.
  private def assertGroupingCodegenMatches(query: String, hint: String): Unit = {
    withSQLConf((yannakakisOn ++ Seq(
        SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1",
        SQLConf.PREFER_SORTMERGEJOIN.key -> "false",
        SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "false")): _*) {
      val plan = sql(query).queryExecution.executedPlan
      val groupingCjs = plan.collect { case cj: HashCountJoin if cj.groupRight.nonEmpty => cj }
      assert(groupingCjs.nonEmpty, s"$hint: expected a grouping count-join in:\n$plan")
      assert(groupingCjs.forall(_.supportCodegen),
        s"$hint: grouping count-join should support whole-stage codegen")
      val on = withSQLConf(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "true") {
        sql(query).collect().toSeq.map(_.toString).sorted
      }
      val off = withSQLConf(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "false") {
        sql(query).collect().toSeq.map(_.toString).sorted
      }
      assert(on == off, s"$hint: codegen $on != interp $off")
    }
    assertSameResults(query, hint)
  }

  test("codegen: GROUPING count-join with decimal SUM matches interpreted") {
    // SUM over a DECIMAL(18,4) carried in the grouping count-join buffer - exercises the inlined
    // updateColumn decimal/avoidSetNullAt path.
    Seq((1, "p"), (2, "q")).toDF("ak", "g1").createOrReplaceTempView("dg_a")
    Seq((1, 10, "123.45"), (1, 11, "200.50"), (2, 10, "300.00"), (2, 11, "0.01"))
      .toDF("ak", "bk", "v0").selectExpr("ak", "bk", "cast(v0 as decimal(18,4)) as v")
      .createOrReplaceTempView("dg_f")
    Seq((10, "x"), (11, "y")).toDF("bk", "g2").createOrReplaceTempView("dg_b")
    Seq(1, 1, 2).toDF("ak").createOrReplaceTempView("dg_d")
    assertGroupingCodegenMatches(
      "select g1, g2, sum(v) as s, count(*) as c from dg_a a, dg_f f, dg_b b, dg_d d " +
        "where a.ak = f.ak and b.bk = f.bk and a.ak = d.ak group by g1, g2",
      "grouping decimal sum")
  }

  test("codegen: GROUPING count-only count-join avoids aggregate buffers") {
    // g1 and g2 live on different dimensions, so the rewrite pushes grouping into a CountJoin.
    // With only count(*), the grouped path should carry group counts without aggregate buffers.
    Seq((1, "p"), (2, "q")).toDF("ak", "g1").createOrReplaceTempView("cog_a")
    Seq((1, 10), (1, 11), (2, 10), (2, 11)).toDF("ak", "bk")
      .createOrReplaceTempView("cog_f")
    Seq((10, "x"), (11, "y")).toDF("bk", "g2").createOrReplaceTempView("cog_b")
    Seq(1, 1, 2).toDF("ak").createOrReplaceTempView("cog_d")
    val query = "select g1, g2, count(*) as c from cog_a a, cog_f f, cog_b b, cog_d d " +
      "where a.ak = f.ak and b.bk = f.bk and a.ak = d.ak group by g1, g2"
    withSQLConf((yannakakisOn ++ Seq(
        SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1",
        SQLConf.PREFER_SORTMERGEJOIN.key -> "false",
        SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "false",
        SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "true")): _*) {
      val plan = sql(query).queryExecution.executedPlan
      val groupingCjs = plan.collect {
        case cj: HashCountJoin if cj.groupRight.nonEmpty => cj
      }
      assert(groupingCjs.nonEmpty, s"expected a grouped count-join in:\n$plan")
      assert(groupingCjs.forall(_.supportCodegen),
        s"grouped count-only count-join should support whole-stage codegen:\n$plan")
      val code = org.apache.spark.sql.execution.debug.codegenString(plan)
      assert(code.contains("cjCountMap"),
        s"grouped count-only codegen should use the count-map fast path:\n$code")
      assert(!code.contains(".newBuffer()"),
        s"grouped count-only codegen should not allocate aggregate buffers:\n$code")
      assert(!code.contains("new long[1]"),
        s"grouped count-only codegen should not allocate per-group long arrays:\n$code")
    }
    assertGroupingCodegenMatches(query, "grouped count-only")
  }

  test("codegen: GROUPING count-join with multiple aggregates matches interpreted") {
    // sum(long) + sum(double) + count(*): three aggregate functions in one buffer - exercises the
    // per-function buffer offsets in the inlined update.
    Seq((1, "p"), (2, "q")).toDF("ak", "g1").createOrReplaceTempView("mg_a")
    Seq((1, 10, 100, 1.5), (1, 11, 200, 2.5), (2, 10, 300, 3.5), (2, 11, 400, 4.5))
      .toDF("ak", "bk", "v1", "v2").createOrReplaceTempView("mg_f")
    Seq((10, "x"), (11, "y")).toDF("bk", "g2").createOrReplaceTempView("mg_b")
    Seq(1, 1, 2).toDF("ak").createOrReplaceTempView("mg_d")
    assertGroupingCodegenMatches(
      "select g1, g2, sum(v1) as s1, sum(v2) as s2, count(*) as c " +
        "from mg_a a, mg_f f, mg_b b, mg_d d " +
        "where a.ak = f.ak and b.bk = f.bk and a.ak = d.ak group by g1, g2",
      "grouping multi-aggregate")
  }

  test("codegen: GROUPING count-join matches interpreted (group keys span relations)") {
    // g1 lives in gj_a, g2 in gj_b, joined through the fact gj_f. Grouping by (g1, g2) forces the
    // count-join that combines the two sides to GROUP inside the operator (groupRight non-empty) -
    // the interpreted bufferMap/sumMap path. Fan-out so a miscount would change the per-group sums.
    Seq((1, "p"), (2, "q")).toDF("ak", "g1").createOrReplaceTempView("gj_a")
    Seq((1, 10, 100), (1, 11, 200), (2, 10, 300), (2, 11, 400))
      .toDF("ak", "bk", "v").createOrReplaceTempView("gj_f")
    Seq((10, "x"), (11, "y")).toDF("bk", "g2").createOrReplaceTempView("gj_b")
    Seq(1, 1, 2).toDF("ak").createOrReplaceTempView("gj_d")  // fan-out on ak
    val query = "select g1, g2, sum(v) as s, count(*) as c from gj_a a, gj_f f, gj_b b, gj_d d " +
      "where a.ak = f.ak and b.bk = f.bk and a.ak = d.ak group by g1, g2"
    withSQLConf((yannakakisOn ++ Seq(
        SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1",
        SQLConf.PREFER_SORTMERGEJOIN.key -> "false",
        SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "false")): _*) {
      // Confirm the test actually exercises a GROUPING count-join.
      val plan = sql(query).queryExecution.executedPlan
      val groupingCjs = plan.collect {
        case cj: HashCountJoin if cj.groupRight.nonEmpty => cj
      }
      assert(groupingCjs.nonEmpty, s"expected a grouping count-join in:\n$plan")
      // After grouped-path codegen lands, the grouping count-join supports codegen (RED: the
      // groupRight.isEmpty gate currently makes this false).
      assert(groupingCjs.forall(_.supportCodegen),
        s"the grouping count-join should support whole-stage codegen:\n$plan")
      val on = withSQLConf(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "true") {
        sql(query).collect().toSeq.map(_.toString).sorted
      }
      val off = withSQLConf(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "false") {
        sql(query).collect().toSeq.map(_.toString).sorted
      }
      assert(on == off, s"codegen $on != interp $off")
    }
    assertSameResults(query, "grouping count-join (2 group keys)")
  }

  test("codegen: grouped count-join under broadcast (getValue/iterator) matches interpreted") {
    // Default broadcast (small dims) -> BroadcastHashCountJoin grouping path. Covers the broadcast
    // branch of the grouped codegen (the prior grouping test forced the shuffled iterator branch).
    Seq((1, "p"), (2, "q"), (3, "p")).toDF("ak", "g1").createOrReplaceTempView("bg_a")
    Seq((1, 10, 100), (1, 11, 200), (2, 10, 300), (3, 11, 400))
      .toDF("ak", "bk", "v").createOrReplaceTempView("bg_f")
    Seq((10, "x"), (11, "y")).toDF("bk", "g2").createOrReplaceTempView("bg_b")
    Seq(1, 1, 2, 3).toDF("ak").createOrReplaceTempView("bg_d")
    val query = "select g1, g2, sum(v) as s, count(*) as c from bg_a a, bg_f f, bg_b b, bg_d d " +
      "where a.ak = f.ak and b.bk = f.bk and a.ak = d.ak group by g1, g2"
    withSQLConf((yannakakisOn :+ (SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "false")): _*) {
      val on = withSQLConf(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "true") {
        sql(query).collect().toSeq.map(_.toString).sorted
      }
      val off = withSQLConf(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "false") {
        sql(query).collect().toSeq.map(_.toString).sorted
      }
      assert(on == off, s"codegen $on != interp $off")
    }
    assertSameResults(query, "grouped count-join under broadcast")
  }

  test("codegen: grouped AVG count-join (Sum-decomposed buffer) matches interpreted") {
    // AVG over a fan-out join, grouped by two cross-relation keys -> the grouping count-join
    // carries Sum aggregates (numerator/denominator); exercises the grouped codegen Sum buffers.
    Seq((1, "p"), (2, "q")).toDF("ak", "g1").createOrReplaceTempView("ag_a")
    Seq((1, 10, 100.0), (1, 11, 200.0), (2, 10, 300.0), (2, 11, 400.0))
      .toDF("ak", "bk", "v").createOrReplaceTempView("ag_f")
    Seq((10, "x"), (11, "y")).toDF("bk", "g2").createOrReplaceTempView("ag_b")
    Seq(1, 1, 2).toDF("ak").createOrReplaceTempView("ag_d")
    assertSameResults(
      "select g1, g2, avg(v) as a from ag_a a, ag_f f, ag_b b, ag_d d " +
        "where a.ak = f.ak and b.bk = f.bk and a.ak = d.ak group by g1, g2",
      "grouped avg count-join")
  }

  test("codegen: count-join on the shuffled-hash path matches interpreted") {
    // Broadcast disabled -> ShuffledHashCountJoin (keyIsUnique always false -> iterator branch).
    Seq(1, 1, 2, 2, 2).toDF("k").createOrReplaceTempView("sh_a")
    Seq((1, 10), (2, 20)).toDF("k", "j").createOrReplaceTempView("sh_b")
    Seq(10, 10, 20).toDF("j").createOrReplaceTempView("sh_c")
    val query = "select count(*) as c from sh_a a, sh_b b, sh_c c where a.k = b.k and b.j = c.j"
    assertCountJoinCodegenMatches(query, "shuffled-hash count(*)",
      SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1")
  }

  // Uses a MERGE hint to select sort-merge count-join and asserts it is whole-stage-codegen'd
  // (the `*(n) ... SortMergeCountJoin` marker), then that codegen results equal interpreted
  // (codegen off) and vanilla. Before SMJ codegen support the operator runs interpreted (no
  // marker) -> fail.
  private def assertSMJCountJoinCodegenMatches(query: String, hint: String): Unit = {
    val smjConf = sortMergeCountJoinConfs
    withSQLConf((yannakakisOn ++ smjConf): _*) {
      val onPlan = withSQLConf(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "true") {
        sql(query).queryExecution.executedPlan.toString
      }
      assert(onPlan.linesIterator.exists(_.matches(".*\\*\\(\\d+\\).*SortMergeCountJoin.*")),
        s"$hint: the SMJ count-join should be whole-stage-codegen'd:\n$onPlan")
      val on = withSQLConf(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "true") {
        sql(query).collect().toSeq.map(_.toString).sorted
      }
      val off = withSQLConf(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "false") {
        sql(query).collect().toSeq.map(_.toString).sorted
      }
      assert(on == off, s"$hint: SMJ codegen result $on != interpreted $off")
    }
    assertSameResults(query, hint)
  }

  test("codegen: sort-merge count-join (non-grouping) matches interpreted") {
    // fan-out on k (a has dup keys) with a carried SUM over the build (dim) column v.
    Seq(1, 1, 2, 2, 2).toDF("k").createOrReplaceTempView("smjng_a")
    Seq((1, 100), (2, 200)).toDF("k", "v").createOrReplaceTempView("smjng_b")
    val query = "select /*+ MERGE(b) */ count(*) as c, sum(v) as s " +
      "from smjng_a a join smjng_b b on a.k = b.k"
    assertSMJCountJoinCodegenMatches(query, "SMJ non-grouping count+sum")
  }

  test("codegen: sort-merge GROUPED count-join matches interpreted") {
    // group by a build (dim) column g, carried SUM over build column v, fan-out on k.
    Seq(1, 1, 2, 2, 2, 3).toDF("k").createOrReplaceTempView("smjg_a")
    Seq((1, 100, "x"), (2, 200, "y"), (3, 300, "x"))
      .toDF("k", "v", "g").createOrReplaceTempView("smjg_b")
    val query = "select /*+ MERGE(b) */ g, count(*) as c, sum(v) as s " +
      "from smjg_a a join smjg_b b on a.k = b.k group by g"
    assertSMJCountJoinCodegenMatches(query, "SMJ grouped count+sum")
  }

  test("codegen: two count-joins on the same key fuse into one stage without colliding") {
    // f joins d1 and d2 BOTH on k, so the two count-joins are co-partitioned on k - no exchange
    // between them, so whole-stage codegen fuses them into one stage. Each count-join builds its
    // hash relation from inputs[1]; without forcing the children to separate codegen stages (as
    // CollapseCodegenStages does for ShuffledHashJoin) the two relations collide on that slot and
    // the outer join reads the inner's build (wrong-width row / wrong count). d1 and d2 have
    // DIFFERENT multiplicities so a collision changes the result: count = 1 * 2 * 3 = 6.
    Seq(1).toDF("k").createOrReplaceTempView("ssk_f")
    Seq(1, 1).toDF("k").createOrReplaceTempView("ssk_d1")
    Seq(1, 1, 1).toDF("k").createOrReplaceTempView("ssk_d2")
    val query = "select count(*) as c from ssk_f f, ssk_d1 d1, ssk_d2 d2 " +
      "where f.k = d1.k and f.k = d2.k"
    withSQLConf((yannakakisOn ++ Seq(
        SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1",
        SQLConf.PREFER_SORTMERGEJOIN.key -> "false",
        SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "false")): _*) {
      val on = withSQLConf(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "true") {
        sql(query).collect().toSeq.map(_.toString)
      }
      val off = withSQLConf(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "false") {
        sql(query).collect().toSeq.map(_.toString)
      }
      assert(on == off, s"codegen $on != interp $off")
      assert(on == Seq("[6]"), s"expected count 6, got $on")
    }
    assertSameResults(query, "two count-joins on same key (shuffled, fused)")
  }

  test("codegen: count-join with a unique build key (getValue branch) matches interpreted") {
    // Build (right) side keys are unique -> the broadcast relation reports keyIsUnique=true, so
    // codegen takes the getValue branch rather than the iterator branch.
    Seq(1, 1, 2, 2, 2, 3).toDF("k").createOrReplaceTempView("uq_fact")
    Seq(1, 2, 3, 4).toDF("k").createOrReplaceTempView("uq_dim")
    val query = "select count(*) as c from uq_fact f join uq_dim d on f.k = d.k"
    assertCountJoinCodegenMatches(query, "unique-build-key count(*)")
  }

  // Asserts a shuffled-hash count-join's generated code reads relation.keyIsUnique() at runtime and
  // emits the single-row getValue fast branch (a shuffled relation is built at run time, so its key
  // uniqueness is unknown at code-gen time; vanilla shuffled-hash inner joins therefore always take
  // the iterator branch). Returns the generated code so callers can add path-specific assertions.
  private def assertShuffledCountJoinHasRuntimeFastPath(query: String, hint: String): String = {
    withSQLConf((yannakakisOn ++ Seq(
        SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1",
        SQLConf.PREFER_SORTMERGEJOIN.key -> "false",
        SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "false",
        SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "true")): _*) {
      val plan = sql(query).queryExecution.executedPlan
      assert(plan.toString.contains("ShuffledHashCountJoin"),
        s"$hint: expected a shuffled-hash count-join:\n$plan")
      val code = org.apache.spark.sql.execution.debug.codegenString(plan)
      assert(code.contains("keyIsUnique()"),
        s"$hint: shuffled count-join should read relation.keyIsUnique() at runtime:\n$code")
      assert(code.contains(".getValue("),
        s"$hint: shuffled count-join should emit the getValue fast branch:\n$code")
      code
    }
  }

  test("codegen: shuffled-hash count-join with a unique build key takes the runtime fast path") {
    // Non-grouping path (codegenCountInner). uq2_dim has unique keys, so relation.keyIsUnique() is
    // true at run time -> the single-row getValue branch runs instead of the iterator branch.
    Seq(1, 1, 2, 2, 2, 3).toDF("k").createOrReplaceTempView("uq2_fact")
    Seq(1, 2, 3, 4).toDF("k").createOrReplaceTempView("uq2_dim")
    val query = "select count(*) as c from uq2_fact f join uq2_dim d on f.k = d.k"
    assertShuffledCountJoinHasRuntimeFastPath(query, "shuffled non-grouping unique-build-key")
    assertCountJoinCodegenMatches(query, "shuffled non-grouping unique-build-key",
      SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1")
  }

  test("codegen: shuffled-hash GROUPED count-join with a unique build key takes the runtime fast " +
    "path") {
    // Grouping path (codegenCountGroupedInner). The build (dim) carries the group key g and has a
    // unique join key k, so relation.keyIsUnique() is true at run time -> grouped getValue branch.
    Seq(1, 1, 2, 2, 2, 3).toDF("k").createOrReplaceTempView("uqg_fact")
    Seq((1, "a"), (2, "b"), (3, "c"), (4, "d")).toDF("k", "g").createOrReplaceTempView("uqg_dim")
    val query = "select g, count(*) as c from uqg_fact f join uqg_dim d on f.k = d.k group by g"
    assertShuffledCountJoinHasRuntimeFastPath(query, "shuffled grouped unique-build-key")
    assertCountJoinCodegenMatches(query, "shuffled grouped unique-build-key",
      SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1")
  }

  // #3-source: when the build (right) join key is PROVABLY unique from the logical plan (the build
  // child's distinctKeys subset the build join keys), the shuffled-hash count-join knows uniqueness
  // STATICALLY at code-gen time. It must then bake the single-row getValue fast path directly and
  // emit NO runtime relation.keyIsUnique() read (unlike the runtime-fast-path case above).
  private def assertShuffledCountJoinHasStaticFastPath(query: String, hint: String): String = {
    withSQLConf((yannakakisOn ++ Seq(
        SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1",
        SQLConf.PREFER_SORTMERGEJOIN.key -> "false",
        SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "false",
        SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "true")): _*) {
      val plan = sql(query).queryExecution.executedPlan
      assert(plan.toString.contains("ShuffledHashCountJoin"),
        s"$hint: expected a shuffled-hash count-join:\n$plan")
      val code = org.apache.spark.sql.execution.debug.codegenString(plan)
      assert(code.contains(".getValue("),
        s"$hint: statically-unique count-join should emit the getValue fast branch:\n$code")
      assert(!code.contains("keyIsUnique()"),
        s"$hint: statically-unique count-join must NOT read relation.keyIsUnique() at runtime:\n" +
          code)
      code
    }
  }

  test("codegen: shuffled count-join with a PROVABLY-unique build key takes the STATIC fast path") {
    // The build side is `select distinct k from ...`, an Aggregate whose distinctKeys = {k}, which
    // subsets the build join key -> #3-source proves uniqueness STATICALLY. The generated code must
    // bake the getValue fast path and emit no runtime keyIsUnique() read; results match vanilla.
    Seq(1, 1, 2, 2, 2, 3).toDF("k").createOrReplaceTempView("st_fact")
    Seq(1, 1, 2, 3, 4, 4).toDF("k").createOrReplaceTempView("st_dim")
    val query =
      "select count(*) as c from st_fact f join (select distinct k from st_dim) d on f.k = d.k"
    assertShuffledCountJoinHasStaticFastPath(query, "shuffled non-grouping static-unique-build-key")
    assertCountJoinCodegenMatches(query, "shuffled non-grouping static-unique-build-key",
      SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1")
  }

  test("codegen: static-unique count-join inside a GROUP BY query takes the STATIC fast path") {
    // A provably-distinct build side (`select distinct k`) joined under an outer GROUP BY. The
    // rewrite keeps the build child as the distinct Aggregate (distinctKeys = {k} subsets the build
    // join key), so the count-join is statically unique and must bake the getValue fast path with
    // no runtime keyIsUnique() read; the outer grouping is a separate HashAggregate above the join.
    Seq(1, 1, 2, 2, 2, 3).toDF("k").createOrReplaceTempView("stg_fact")
    Seq(1, 1, 2, 3, 4, 4).toDF("k").createOrReplaceTempView("stg_dim")
    val query = "select f.k as gk, count(*) as c from stg_fact f join " +
      "(select distinct k from stg_dim) d on f.k = d.k group by f.k"
    assertShuffledCountJoinHasStaticFastPath(query, "static-unique under group-by")
    assertCountJoinCodegenMatches(query, "static-unique under group-by",
      SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1")
  }

  test("codegen: shuffled count-join with NOT-provably-unique build key keeps runtime check") {
    // The build side is a raw scan (Project over an in-memory relation) whose distinctKeys is
    // empty, so #3-source can NOT prove uniqueness. The operator must fall back to the runtime
    // keyIsUnique() check (the safe path), while still emitting the getValue fast branch. Even
    // though the data happens to have unique keys, we must not claim static uniqueness.
    Seq(1, 1, 2, 2, 2, 3).toDF("k").createOrReplaceTempView("nu_fact")
    Seq(1, 2, 3, 4).toDF("k").createOrReplaceTempView("nu_dim")
    val query = "select count(*) as c from nu_fact f join nu_dim d on f.k = d.k"
    val code = assertShuffledCountJoinHasRuntimeFastPath(query, "shuffled not-provably-unique")
    assert(code.contains("keyIsUnique()"),
      s"raw-scan build side must keep the runtime keyIsUnique() check:\n$code")
    assertCountJoinCodegenMatches(query, "shuffled not-provably-unique",
      SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1")
  }

  // ---- Aggregate-dispatch correctness (rewrite rule) -------------------------------------

  test("unguarded AVG over a fan-out join matches vanilla (must not drop count multiplication)") {
    // a.g (group) is in dim_a, avg input x is in dim_c, bridged by fact_b: no single relation
    // contains both g and x, so this takes the unguarded/piecewise count-join path. With fan-out
    // on j=20 (two c rows), the unweighted average over the count-reduced rows != the true mean.
    // Vanilla per g=X: x in {100,200,300} -> avg = 200.0.
    Seq((1, "X"), (2, "X")).toDF("k", "g").createOrReplaceTempView("avu_a")
    Seq((1, 10), (2, 20)).toDF("k", "j").createOrReplaceTempView("avu_b")
    Seq((10, 100.0), (20, 200.0), (20, 300.0)).toDF("j", "x").createOrReplaceTempView("avu_c")
    assertSameResults(
      "select g, avg(x) as a from avu_a a, avu_b b, avu_c c " +
        "where a.k = b.k and b.j = c.j group by g",
      "unguarded avg over fan-out")
  }

  test("unguarded SUM over a high-scale decimal preserves the output schema") {
    // f.x is DECIMAL(18,4); the count-multiply widens to SUM(f.x)'s type DECIMAL(28,4), and
    // DEC(28,4)*DEC(28,4) clamps to DECIMAL(38,6) - diverging from vanilla SUM(DECIMAL(18,4)) =
    // DECIMAL(28,4). Grouping by d.k (dim) while summing f.x (fact) forces the unguarded path.
    // assertSameResults only compares cell values, so assert the SCHEMA explicitly here.
    Seq((1, BigDecimal("1234.5678")), (2, BigDecimal("0.0001")))
      .toDF("k", "x0").selectExpr("k", "cast(x0 as decimal(18,4)) as x")
      .createOrReplaceTempView("decu_fact")
    Seq(1, 1, 1, 2).toDF("k").createOrReplaceTempView("decu_dim")
    val query =
      "select d.k as k, sum(f.x) as s from decu_fact f join decu_dim d on f.k = d.k group by d.k"
    var vanillaSchema: org.apache.spark.sql.types.StructType = null
    var vanillaRows: Seq[String] = null
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      val df = sql(query)
      vanillaSchema = df.schema
      vanillaRows = df.collect().toSeq.map(_.toString).sorted
    }
    withSQLConf(yannakakisOn: _*) {
      val df = sql(query)
      assert(df.schema == vanillaSchema,
        s"rewritten schema ${df.schema.catalogString} != vanilla ${vanillaSchema.catalogString}")
      assert(df.collect().toSeq.map(_.toString).sorted == vanillaRows, "values differ from vanilla")
    }
  }

  test("guarded AVG over a fan-out join matches vanilla (count-multiplied numerator/denominator)") {
    // {g, x} both in fact -> guarded; dim duplicates the key (x2 fan-out). The guarded path
    // computes SUM(x*count)/SUM(count). Vanilla per g=X over [100,100,200,200] -> avg = 150.0.
    Seq(("X", 100.0, 1), ("X", 200.0, 1)).toDF("g", "x", "k").createOrReplaceTempView("avg_fact")
    Seq(1, 1).toDF("k").createOrReplaceTempView("avg_dim")
    assertSameResults(
      "select g, avg(x) as a from avg_fact f join avg_dim d on f.k = d.k group by g",
      "guarded avg over fan-out")
  }

  test("guarded AVG with NON-UNIFORM per-row counts matches vanilla (no count-squaring)") {
    // x=100 has count 1 (k=1 once), x=200 has count 2 (k=2 twice). The correct weighted mean is
    // SUM(x*c)/SUM(c) = 500/3 = 166.67. A buggy SUM(x*c*c)/SUM(c*c) = 900/5 = 180. Uniform-count
    // data hides this because the squared count cancels; non-uniform counts expose it.
    Seq(("X", 100.0, 1), ("X", 200.0, 2)).toDF("g", "x", "k").createOrReplaceTempView("avgn_fact")
    Seq(1, 2, 2).toDF("k").createOrReplaceTempView("avgn_dim")
    assertSameResults(
      "select g, avg(x) as a from avgn_fact f join avgn_dim d on f.k = d.k group by g",
      "guarded avg with non-uniform counts")
  }

  test("guarded query mixing SUM and MIN must not crash (unrecognized aggregate falls back)") {
    // {x, y, g} all in fact -> guarded; sum(x) makes it a counting query, but the guarded
    // counting branch has no MIN case -> currently a MatchError mid-optimization. Must fall back
    // and match vanilla. Vanilla per g=X (x2 fan-out): sum(x)=24, min(y)=100.
    Seq(("X", 5, 100, 1), ("X", 7, 200, 1))
      .toDF("g", "x", "y", "k").createOrReplaceTempView("mix_fact")
    Seq(1, 1).toDF("k").createOrReplaceTempView("mix_dim")
    assertSameResults(
      "select g, sum(x) as s, min(y) as m from mix_fact f join mix_dim d on f.k = d.k group by g",
      "guarded sum + min")
  }

  test("query mixing SUM and STDDEV must not crash (unrecognized aggregate falls back)") {
    Seq(("X", 5.0, 1), ("X", 7.0, 1), ("Y", 9.0, 2))
      .toDF("g", "x", "k").createOrReplaceTempView("sd_fact")
    Seq(1, 1, 2).toDF("k").createOrReplaceTempView("sd_dim")
    assertSameResults(
      "select g, sum(x) as s, stddev(x) as sd from sd_fact f join sd_dim d on f.k = d.k group by g",
      "sum + stddev")
  }

  test("NULL join keys never match (count/sum over a nullable join key) matches vanilla") {
    // equi-join treats NULL = NULL as false, so the null-keyed rows drop. A miscounted semijoin
    // or count would diverge here.
    Seq((Some(1), "X"), (None, "Y"), (Some(2), "Z"))
      .toDF("k", "g").createOrReplaceTempView("nk_a")
    Seq(Some(1), Some(1), None, Some(2)).toDF("k").createOrReplaceTempView("nk_b")
    assertSameResults(
      "select g, count(*) as c from nk_a a join nk_b b on a.k = b.k group by g",
      "null join keys count")
  }

  test("decimal carried aggregate over a fan-out join matches vanilla (precision preserved)") {
    Seq((1, BigDecimal("12345.67")), (2, BigDecimal("0.01")))
      .toDF("k", "x").createOrReplaceTempView("dec_fact")
    Seq(1, 1, 1, 2).toDF("k").createOrReplaceTempView("dec_dim")
    assertSameResults(
      "select sum(x) as s from dec_fact f join dec_dim d on f.k = d.k",
      "decimal sum over fan-out")
  }

  test("physical count-join specs survive decimal aggregate optimization") {
    Seq((1, "A"), (2, "A")).toDF("k", "g").createOrReplaceTempView("cjd_a")
    Seq((1, 10), (2, 20)).toDF("k", "j").createOrReplaceTempView("cjd_b")
    Seq((10, BigDecimal("10.25")), (20, BigDecimal("20.50")), (20, BigDecimal("1.00")))
      .toDF("j", "x0")
      .selectExpr("j", "cast(x0 as decimal(18,2)) as x")
      .createOrReplaceTempView("cjd_c")

    val query =
      "select g, sum(x) as s from cjd_a a, cjd_b b, cjd_c c " +
        "where a.k = b.k and b.j = c.j group by g"

    withSQLConf(yannakakisOn: _*) {
      val df = sql(query)
      assert(df.queryExecution.optimizedPlan.toString.contains("CountJoin"),
        "expected decimal carried aggregate query to use CountJoin:\n" +
          df.queryExecution.optimizedPlan)
    }
    assertSameResults(query, "decimal aggregate carried through physical count-join")
  }

  test("empty join result: ungrouped count(*)=0 and sum=NULL match vanilla") {
    Seq((1, 100), (2, 200)).toDF("k", "x").createOrReplaceTempView("ej_a")
    Seq(7, 8, 9).toDF("k").createOrReplaceTempView("ej_b")
    assertSameResults(
      "select count(*) as c, sum(x) as s from ej_a a join ej_b b on a.k = b.k",
      "empty join count/sum")
  }

  test("count-multiplied SUM over narrow Int does not overflow under ANSI (widened accumulator)") {
    // x is Int near Int.MaxValue; with x3 fan-out, sum(x) = 6e9 > Int.MaxValue but fits in the
    // promoted (bigint) accumulator. A count multiplication done in the narrow Int type would
    // overflow (throw under ANSI / wrap otherwise); vanilla sum(int) promotes and does not.
    withSQLConf(SQLConf.ANSI_ENABLED.key -> "true") {
      Seq((1, 2000000000)).toDF("k", "x").createOrReplaceTempView("ovf_fact")
      Seq(1, 1, 1).toDF("k").createOrReplaceTempView("ovf_dim")
      assertSameResults(
        "select sum(x) as s from ovf_fact f join ovf_dim d on f.k = d.k",
        "narrow-int sum overflow under ANSI")
    }
  }

  test("rewrite applies to an aggregate directly over a join (no Project wrapper)") {
    // When column pruning removes a redundant Project, the rule sees Aggregate(Join) directly.
    // Previously that shape was a no-op; it now rewrites via an identity projectList. Force the
    // shape by excluding ColumnPruning's reinsertion is unnecessary - just assert correctness for
    // both the rewritten and vanilla plans regardless of which matcher arm fires.
    Seq((1, 100), (1, 200), (2, 300)).toDF("k", "v").createOrReplaceTempView("nj_a")
    Seq(1, 1, 2, 2).toDF("k").createOrReplaceTempView("nj_b")
    assertSameResults(
      "select count(*) as c from nj_a a join nj_b b on a.k = b.k",
      "count(*) directly over a join")
    assertSameResults(
      "select sum(v) as s from nj_a a join nj_b b on a.k = b.k",
      "sum directly over a join")
  }

  test("count/sum of the grouping key over a fan-out join must count the fan-out") {
    // Regression (found by YannakakisFuzzSuite seed 64): count(k)/sum(k) where k is BOTH the
    // grouping key and the join key. The build dim has duplicate keys (fan-out), so vanilla counts
    // the fan-out. The classifier used to EXCLUDE aggregates whose only reference is a grouping
    // attribute (treating count(k) like count(*) of a constant), so the query was misclassified as
    // 0MA and took the semijoin (LeftSemi) reduction - which drops the fan-out -> wrong count.
    Seq((1, 10), (2, 20)).toDF("k", "v").createOrReplaceTempView("gk_fact")
    Seq(1, 1, 2).toDF("k").createOrReplaceTempView("gk_dim")  // k=1 duplicated -> fan-out x2
    // count(f.k) of the grouping key is paired with max(v) over a NON-grouping column. max(v) is
    // duplicate-insensitive so it populates the 0MA class; count(f.k) was wrongly EXCLUDED from the
    // counting class (refs subset of grouping), so the query took the 0MA semijoin reduction and
    // dropped the fan-out -> count came back as 1 instead of 2.
    assertSameResults(
      "select f.k as g, count(f.k) as c, max(f.v) as m " +
        "from gk_fact f join gk_dim d on f.k = d.k group by f.k",
      "count(grouping key) over fan-out")
    assertSameResults(
      "select f.k as g, sum(f.k) as s, min(f.v) as m " +
        "from gk_fact f join gk_dim d on f.k = d.k group by f.k",
      "sum(grouping key) over fan-out")
    // Mixed with a DISTINCT aggregate (the exact shape the fuzzer hit): the split's counting half
    // must also count the fan-out.
    assertSameResults(
      "select f.k as g, count(f.k) as c, sum(distinct v) as sd, max(v) as mx " +
        "from gk_fact f join gk_dim d on f.k = d.k group by f.k",
      "count(grouping key) + distinct over fan-out")
  }

  test("mixed DISTINCT + additive aggregates: split fires and matches vanilla (grouped)") {
    // a.k=1 appears twice -> the join fans b's k=1 row out twice. count(distinct x) must IGNORE
    // that fan-out (distinct x stays {10,20}=2), while sum(y)/count(*) must COUNT it. These two
    // requirements are incompatible in one count-join pipeline, so the rewrite splits the aggregate
    // into a duplicate-insensitive half (0MA) and a counting half, then rejoins on the group key.
    Seq(1, 1, 2).toDF("k").createOrReplaceTempView("mxd_a")
    Seq((1, "P", 10, 100), (2, "P", 20, 200))
      .toDF("k", "g", "x", "y").createOrReplaceTempView("mxd_b")
    val query = "select g, count(distinct x) as cd, sum(y) as s, count(*) as c " +
      "from mxd_a a join mxd_b b on a.k = b.k group by g"
    withSQLConf((yannakakisOn :+ (SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "false")): _*) {
      val plan = sql(query).queryExecution.executedPlan.toString
      assert(plan.contains("CountJoin"),
        s"mixed-distinct rewrite should fire (split into distinct + counting halves):\n$plan")
    }
    assertSameResults(query, "mixed distinct + additive (grouped)")
  }

  test("mixed DISTINCT + additive aggregates: split fires and matches vanilla (global)") {
    // No GROUP BY -> the two halves each produce one row and recombine via a 1x1 cross join.
    Seq(1, 1, 2).toDF("k").createOrReplaceTempView("mxg_a")
    Seq((1, 10, 100), (2, 20, 200)).toDF("k", "x", "y").createOrReplaceTempView("mxg_b")
    val query = "select count(distinct x) as cd, sum(y) as s, count(*) as c " +
      "from mxg_a a join mxg_b b on a.k = b.k"
    withSQLConf((yannakakisOn :+ (SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "false")): _*) {
      val plan = sql(query).queryExecution.executedPlan.toString
      assert(plan.contains("CountJoin"),
        s"mixed-distinct rewrite should fire (split, global):\n$plan")
    }
    assertSameResults(query, "mixed distinct + additive (global)")
  }

  test("count-join operators in grouped counting queries are all codegen-able") {
    // Originally a measurement to scope grouped-path codegen: simple grouped counts keep grouping
    // at the top HashAggregate (count-joins non-grouping, codegen-able), while complex aggregates
    // like Q9 push grouping INTO the count-joins. With grouped-path codegen now implemented, BOTH
    // are codegen-able - this asserts every count-join operator (hash; SMJ codegen is still off)
    // across the spread reports supportCodegen = true.
    Seq((1, "g1", 10), (2, "g1", 20), (3, "g2", 30))
      .toDF("k", "g", "v").createOrReplaceTempView("m_fact")
    Seq(1, 1, 2, 3, 3).toDF("k").createOrReplaceTempView("m_d1")
    Seq((10, 100), (20, 200), (30, 300)).toDF("v", "w").createOrReplaceTempView("m_d2")
    Seq((1, 5), (2, 6), (3, 7)).toDF("k", "j").createOrReplaceTempView("m_b")
    Seq(5, 5, 6, 7).toDF("j").createOrReplaceTempView("m_c")
    val queries = Seq(
      "star count(*) grouped by fact col" ->
        "select g, count(*) as c from m_fact f, m_d1 d1 where f.k = d1.k group by g",
      "star sum grouped by fact col" ->
        "select g, sum(v) as s from m_fact f, m_d1 d1 where f.k = d1.k group by g",
      "chain count(*) grouped by leaf col" ->
        ("select g, count(*) as c from m_fact f, m_b b, m_c c " +
          "where f.k = b.k and b.j = c.j group by g"),
      "grouped by a dimension column" ->
        ("select d2.w as w, count(*) as c from m_fact f, m_d1 d1, m_d2 d2 " +
          "where f.k = d1.k and f.v = d2.v group by d2.w"),
      "Q9 green-supplier revenue (grouped sum over 6 relations)" ->
        ("select n_name as nation, extract(year from o_orderdate) as o_year, " +
          "sum(l_extendedprice * (1 - l_discount)) as rev " +
          "from part_t9, supplier_t9, lineitem_t9, partsupp_t9, orders_t9, nation_t9 " +
          "where s_suppkey = l_suppkey and ps_suppkey = l_suppkey " +
          "and ps_partkey = l_partkey and p_partkey = l_partkey " +
          "and o_orderkey = l_orderkey and s_nationkey = n_nationkey and p_name like '%green%' " +
          "group by n_name, extract(year from o_orderdate)"))
    createQ9Tables()
    withSQLConf((yannakakisOn :+ (SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "false")): _*) {
      var total = 0
      var codegenable = 0
      // scalastyle:off println
      for ((label, q) <- queries) {
        val plan = sql(q).queryExecution.executedPlan
        val cjs = plan.collect {
          case cj: HashCountJoin => (cj.groupRight.isEmpty, cj.supportCodegen)
          case cj: SortMergeCountJoinExec => (cj.groupRight.isEmpty, false)
        }
        total += cjs.size
        codegenable += cjs.count(_._2)
        println(s"MEASURE: [$label] ${cjs.size} count-joins, " +
          s"${cjs.count(_._1)} non-grouping, ${cjs.count(_._2)} codegen-able")
      }
      println(s"MEASURE TOTAL: $codegenable / $total count-join operators codegen-able")
      // scalastyle:on println
      assert(total > 0, "expected the grouped counting queries to produce count-joins")
      // All count-joins here are broadcast/shuffled hash (no SMJ at this scale), and with
      // grouped-path codegen every one - grouping or not - now supports whole-stage codegen.
      assert(codegenable == total,
        s"expected all $total count-join operators to be codegen-able, got $codegenable")
    }
  }

  /** Runs `query` with cyclic-bag decomposition ON and asserts the rows match vanilla. */
  private def assertCyclicSameResults(query: String, hint: String): Unit = {
    var expected: Seq[Row] = null
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      expected = sql(query).collect().toSeq.sortBy(_.toString)
    }
    withSQLConf(cyclicBagsOn: _*) {
      val df = sql(query)
      val actual = df.collect().toSeq.sortBy(_.toString)
      val ok = expected.size == actual.size &&
        expected.zip(actual).forall { case (e, a) =>
          e.size == a.size && (0 until e.size).forall(i => cellsMatch(e.get(i), a.get(i)))
        }
      assert(ok,
        s"""$hint
           |expected: ${expected.mkString(" | ")}
           |actual  : ${actual.mkString(" | ")}
           |optimized plan:
           |${df.queryExecution.optimizedPlan}""".stripMargin)
    }
  }

  /** Asserts the cyclic-bag rewrite FIRES (plan contains a CountJoin) for `query`. */
  private def assertCyclicRewriteFires(query: String, hint: String): Unit = {
    withSQLConf((cyclicBagsOn :+
      (SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "false")): _*) {
      val plan = sql(query).queryExecution.executedPlan.toString
      assert(plan.contains("CountJoin"),
        s"$hint: cyclic-bag rewrite should fire (plan should contain a CountJoin):\n$plan")
    }
  }

  /** Asserts the cyclic-bag rewrite FIRES by checking the rewrite emitted the given log. */
  private def assertCyclicRewriteLogged(query: String, logFragment: String, hint: String): Unit = {
    val appender = new LogAppender("cyclic-bag rewrite")
    withLogAppender(appender) {
      withSQLConf(cyclicBagsOn: _*) {
        sql(query).collect()
      }
    }
    val fired = appender.loggingEvents.exists(
      _.getMessage.getFormattedMessage.contains(logFragment))
    assert(fired, s"$hint: expected the cyclic-bag rewrite to fire (log '$logFragment')")
  }

  // Triangle base relations with fan-out: keys are duplicated so the join multiplicities (and
  // thus the counts/sums) are > 1 and a dropped fan-out would be detected.
  private def createTriangleTables(): Unit = {
    // R(a, b): a=1 appears twice (fan-out on a), and pairs (1,10),(1,20),(2,10),(3,30)
    Seq((1, 10), (1, 20), (2, 10), (3, 30), (4, 40))
      .toDF("a", "b").createOrReplaceTempView("tri_r")
    // S(b, c): b=10 appears twice (fan-out on b)
    Seq((10, 100), (10, 200), (20, 100), (30, 300), (50, 500))
      .toDF("b", "c").createOrReplaceTempView("tri_s")
    // T(a, c): closes the cycle a<->c; (1,100) participates in multiple triangles
    Seq((1, 100), (1, 200), (2, 100), (3, 300), (4, 999))
      .toDF("a", "c").createOrReplaceTempView("tri_t")
  }

  test("cyclic triangle: count(*) matches vanilla and the rewrite fires") {
    createTriangleTables()
    // A bare triangle materializes into a single bag that IS the whole query, so the count-join
    // machinery degenerates to the original aggregate over the bag join (no CountJoin needed).
    // We assert the cyclic rewrite definitively activated via its log line and that the count -
    // which must reflect the full join fan-out - matches vanilla.
    val query =
      "select count(*) as c from tri_r r join tri_s s on r.b = s.b " +
        "join tri_t t on r.a = t.a and s.c = t.c"
    assertCyclicRewriteLogged(query, "guarded single-node bag", "triangle count(*)")
    assertCyclicSameResults(query, "triangle count(*)")
  }

  test("cyclic triangle: sum over a column matches vanilla (fan-out counted)") {
    createTriangleTables()
    // sum(c) over the triangle: must count the join fan-out, not the distinct c values.
    val query =
      "select sum(t.c) as s from tri_r r join tri_s s on r.b = s.b " +
        "join tri_t t on r.a = t.a and s.c = t.c"
    assertCyclicRewriteLogged(query, "guarded single-node bag", "triangle sum(c)")
    assertCyclicSameResults(query, "triangle sum(c)")
  }

  test("cyclic triangle: grouped count(*) matches vanilla") {
    createTriangleTables()
    val query =
      "select r.a as a, count(*) as c from tri_r r join tri_s s on r.b = s.b " +
        "join tri_t t on r.a = t.a and s.c = t.c group by r.a"
    assertCyclicSameResults(query, "triangle grouped count(*)")
  }

  test("cyclic triangle with an internal cross-relation filter matches vanilla") {
    // A non-equi predicate spanning two of the bag's relations (r.a < t.c) must be applied
    // INSIDE the materialized bag, so the bag's rows equal the filtered cyclic sub-query.
    createTriangleTables()
    val query =
      "select count(*) as c from tri_r r join tri_s s on r.b = s.b " +
        "join tri_t t on r.a = t.a and s.c = t.c where r.a < t.c"
    assertCyclicRewriteLogged(query, "guarded single-node bag", "triangle + internal filter")
    assertCyclicSameResults(query, "triangle + internal filter count(*)")
  }

  test("cyclic triangle with a dangling fringe relation matches vanilla") {
    // Triangle R-S-T plus an acyclic fringe relation U(c, d) hanging off vertex c. GYO strips U
    // as an ear first, then the triangle stalls and becomes a bag; U must be re-attached as a
    // child of the bag (not orphaned). count(*) over the whole thing must still count fan-out.
    createTriangleTables()
    Seq((100, 7), (100, 8), (200, 9), (300, 9), (999, 1))
      .toDF("c", "d").createOrReplaceTempView("tri_u")
    val query =
      "select count(*) as c from tri_r r join tri_s s on r.b = s.b " +
        "join tri_t t on r.a = t.a and s.c = t.c join tri_u u on t.c = u.c"
    assertCyclicRewriteFires(query, "triangle + fringe count(*)")
    assertCyclicSameResults(query, "triangle + fringe count(*)")
  }

  test("cyclic 4-cycle: count(*) matches vanilla") {
    // A 4-cycle R(a,b)-S(b,c)-T(c,d)-U(d,a). GYO stalls on all four edges (no ear); they form a
    // single bag. With fan-out on b and d so the counts are non-trivial.
    Seq((1, 10), (1, 20), (2, 10), (3, 30)).toDF("a", "b").createOrReplaceTempView("c4_r")
    Seq((10, 100), (20, 100), (30, 300), (10, 200)).toDF("b", "c").createOrReplaceTempView("c4_s")
    Seq((100, 1000), (200, 1000), (300, 3000), (100, 2000))
      .toDF("c", "d").createOrReplaceTempView("c4_t")
    Seq((1000, 1), (2000, 1), (3000, 3), (1000, 2)).toDF("d", "a").createOrReplaceTempView("c4_u")
    val query =
      "select count(*) as c from c4_r r join c4_s s on r.b = s.b " +
        "join c4_t t on s.c = t.c join c4_u u on t.d = u.d and u.a = r.a"
    assertCyclicSameResults(query, "4-cycle count(*)")
  }

  test("cyclic 4-cycle written as a comma-join (non-cycle relation order) matches vanilla") {
    // The same 4-cycle R(a,b)-S(b,c)-T(c,d)-U(d,a) but expressed as a comma-join whose FROM clause
    // lists two OPPOSITE edges first (cyc_w(a,b), cyc_x(c,d) share no vertex). Spark's ReorderJoin
    // still delivers a connected join order to the bag materialization (it avoids cartesians), so
    // the bag chains successfully regardless of how the cycle is written. Regression coverage for
    // the bag's connected-order assumption.
    Seq((1, 10), (1, 20), (2, 10), (3, 30)).toDF("a", "b").createOrReplaceTempView("cyc_w")
    Seq((100, 1000), (200, 1000), (300, 3000), (100, 2000))
      .toDF("c", "d").createOrReplaceTempView("cyc_x")
    Seq((10, 100), (20, 100), (30, 300), (10, 200)).toDF("b", "c").createOrReplaceTempView("cyc_y")
    Seq((1000, 1), (2000, 1), (3000, 3), (1000, 2)).toDF("d", "a").createOrReplaceTempView("cyc_z")
    val query =
      """select count(*) as c from cyc_w w, cyc_x x, cyc_y y, cyc_z z
         where w.b = y.b and y.c = x.c and x.d = z.d and z.a = w.a"""
    assertCyclicRewriteLogged(query, "guarded single-node bag", "comma-join 4-cycle bag")
    assertCyclicSameResults(query, "comma-join 4-cycle count(*)")
  }

  test("cyclic triangle: acyclic regression - with the flag OFF the plan is NOT rewritten") {
    // Belt-and-suspenders: the default (flag off) must leave a cyclic query as the original plan
    // (no CountJoin), proving the new path is strictly opt-in and acyclic behaviour is untouched.
    createTriangleTables()
    val query =
      "select count(*) as c from tri_r r join tri_s s on r.b = s.b " +
        "join tri_t t on r.a = t.a and s.c = t.c"
    var expected: Seq[Row] = null
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      expected = sql(query).collect().toSeq
    }
    withSQLConf(yannakakisOn: _*) { // cyclic-bags NOT enabled
      val df = sql(query)
      checkAnswer(df, expected)
      assert(!df.queryExecution.optimizedPlan.toString.contains("CountJoin"),
        "cyclic query must NOT be rewritten when cyclicBagsEnabled is off")
    }
  }

  test("cyclic cost gate: a non-broadcast-bounded bag falls back, results stay correct") {
    createTriangleTables()
    val query =
      "select count(*) as c from tri_r r join tri_s s on r.b = s.b " +
        "join tri_t t on r.a = t.a and s.c = t.c"
    var expected: Seq[Row] = null
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      expected = sql(query).collect().toSeq
    }
    // The bare triangle becomes a whole-query bag, applied as a "guarded single-node bag" - the one
    // reliable signal that the cyclic rewrite fired (the bag itself is plain inner joins, so the
    // plan carries no CountJoin either way). We assert on that log in both directions.

    // Cost gate ON + a 1-byte broadcast threshold: no triangle relation is broadcast-eligible, so
    // the bag is not broadcast-bounded; materializeBag returns null, the decomposition fails, and
    // the query falls back to the original plan (rewrite declines) - still correct.
    val (gatedRows, gatedFired) = runCyclicCapturingFire(query,
      Seq(SQLConf.YANNAKAKIS_COST_GATE_ENABLED.key -> "true",
        SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "1"))
    assert(!gatedFired, "cost gate + tiny broadcast threshold must decline the cyclic bag")
    assertRowsMatch(gatedRows, expected, "cost-gated cyclic query")

    // Gate off (suite default): the same cyclic query DOES fire and is correct.
    val (firedRows, fired) = runCyclicCapturingFire(query, Seq.empty)
    assert(fired, "with the cost gate off the cyclic rewrite should fire")
    assertRowsMatch(firedRows, expected, "ungated cyclic query")
  }

  // Runs `query` with the cyclic-bags flag on (plus `extraConf`) under a log appender, returning
  // (rows, didTheGuardedSingleNodeBagRewriteFire).
  private def runCyclicCapturingFire(
      query: String, extraConf: Seq[(String, String)]): (Seq[Row], Boolean) = {
    val appender = new LogAppender("single-node bag")
    var rows: Seq[Row] = null
    withLogAppender(appender) {
      withSQLConf((cyclicBagsOn ++ extraConf): _*) { rows = sql(query).collect().toSeq }
    }
    val fired = appender.loggingEvents.exists(
      _.getMessage.getFormattedMessage.contains("guarded single-node bag"))
    (rows, fired)
  }

  private def assertRowsMatch(actual: Seq[Row], expected: Seq[Row], hint: String): Unit = {
    val a = actual.sortBy(_.toString)
    val e = expected.sortBy(_.toString)
    assert(a == e, s"$hint\nexpected: ${e.mkString(" | ")}\nactual  : ${a.mkString(" | ")}")
  }

  /** Asserts the LEFT-OUTER split fired (logs "left-outer split") AND results match vanilla. */
  private def assertLeftOuterSplitAndCorrect(query: String, hint: String): Unit = {
    val appender = new LogAppender("left-outer split rewrite")
    withLogAppender(appender) {
      assertSameResults(query, hint)
    }
    val fired = appender.loggingEvents.exists(
      _.getMessage.getFormattedMessage.contains("new aggregate (left-outer split)"))
    assert(fired, s"$hint: expected the LEFT OUTER split to fire")
  }

  /** Asserts the FULL-OUTER split fired (logs "full-outer split") AND results match vanilla. */
  private def assertFullOuterSplitAndCorrect(query: String, hint: String): Unit = {
    val appender = new LogAppender("full-outer split rewrite")
    withLogAppender(appender) {
      assertSameResults(query, hint)
    }
    val fired = appender.loggingEvents.exists(
      _.getMessage.getFormattedMessage.contains("new aggregate (full-outer split)"))
    assert(fired, s"$hint: expected the FULL OUTER split to fire")
  }

  /**
   * Asserts the direct FULL-OUTER presence-count rewrite fired and results match vanilla. This
   * shape deliberately avoids CountJoin: the matched rows are counted via a left-semi join, and
   * the unmatched counts are derived from scalar side counts.
   */
  private def assertFullOuterPresenceCountsAndCorrect(query: String, hint: String): Unit = {
    var expected: Seq[Row] = null
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      expected = sql(query).collect().toSeq
    }

    val appender = new LogAppender("full-outer presence-count rewrite")
    withLogAppender(appender) {
      withSQLConf(yannakakisOn: _*) {
        val df = sql(query)
        checkAnswer(df, expected)
        val plan = df.queryExecution.optimizedPlan.toString
        assert(!plan.contains("CountJoin"),
          s"$hint: direct presence-count path should not emit CountJoin:\n$plan")
      }
    }
    val fired = appender.loggingEvents.exists(
      _.getMessage.getFormattedMessage.contains("new aggregate (full-outer presence counts)"))
    assert(fired, s"$hint: expected the FULL OUTER presence-count rewrite to fire")
  }

  test("FULL OUTER presence-count sums use scalar counts and match vanilla") {
    val storeRows: Seq[(Option[Int], Option[Int])] = Seq(
      (Some(1), Some(10)),
      (Some(1), Some(10)),
      (Some(2), Some(20)),
      (None, Some(30)),
      (Some(4), None))
    val catalogRows: Seq[(Option[Int], Option[Int])] = Seq(
      (Some(1), Some(10)),
      (Some(3), Some(30)),
      (Some(3), Some(30)),
      (None, Some(40)),
      (Some(5), None))
    storeRows.toDF("customer_sk", "item_sk").createOrReplaceTempView("fo_pc_store")
    catalogRows.toDF("customer_sk", "item_sk").createOrReplaceTempView("fo_pc_catalog")

    assertFullOuterPresenceCountsAndCorrect(
      """with store_keys as (
        |  select customer_sk, item_sk from fo_pc_store group by customer_sk, item_sk
        |), catalog_keys as (
        |  select customer_sk, item_sk from fo_pc_catalog group by customer_sk, item_sk
        |)
        |select
        |  sum(case when s.customer_sk is not null and c.customer_sk is null
        |    then 1 else 0 end) as store_only,
        |  sum(case when s.customer_sk is null and c.customer_sk is not null
        |    then 1 else 0 end) as catalog_only,
        |  sum(case when s.customer_sk is not null and c.customer_sk is not null
        |    then 1 else 0 end) as matched
        |from store_keys s full outer join catalog_keys c
        |on s.customer_sk = c.customer_sk and s.item_sk = c.item_sk
        |""".stripMargin,
      "FULL OUTER presence-count sums")
  }

  // FULL OUTER fixture: matched (k=1 fan-out, k=2), A-only unmatched (k=3,4), B-only unmatched
  // (k=8,9). Exercises all three union branches: inner, left-anti (B->NULL), right-anti (A->NULL).
  private def createFullOuterTables(): Unit = {
    Seq((1, "A", 10.0), (2, "A", 20.0), (3, "B", 30.0), (4, "C", 40.0))
      .toDF("k", "g", "v").createOrReplaceTempView("fo_a")
    // k=1 two matches (fan-out), k=2 one match; k=8,9 have NO a-side match (B-only rows).
    Seq((1, 100), (1, 200), (2, 300), (8, 800), (9, 900)).toDF("k", "x")
      .createOrReplaceTempView("fo_b")
  }

  test("FULL OUTER count(*) over a fan-out join with both-side unmatched groups matches vanilla") {
    createFullOuterTables()
    assertFullOuterSplitAndCorrect(
      "select g, count(*) as c from fo_a a full outer join fo_b b on a.k = b.k group by g",
      "FULL OUTER count(*) with A-only and B-only unmatched groups")
  }

  test("FULL OUTER count/sum over both A-only and B-only measures matches vanilla") {
    createFullOuterTables()
    // count(a.v)/sum(a.v) = 0/NULL on B-only rows; count(b.x)/sum(b.x) = 0/NULL on A-only rows;
    // count(*) counts every row. Grouped by g so B-only rows (a.g NULL) form their own group.
    assertFullOuterSplitAndCorrect(
      """select g, count(*) as c, count(a.v) as ca, sum(a.v) as sa,
                count(b.x) as cb, sum(b.x) as sb
         from fo_a a full outer join fo_b b on a.k = b.k group by g""",
      "FULL OUTER count/sum over A-only and B-only measures")
  }

  test("FULL OUTER min/max over both sides matches vanilla") {
    createFullOuterTables()
    assertFullOuterSplitAndCorrect(
      """select g, min(a.v) as mnv, max(a.v) as mxv, min(b.x) as mnx, max(b.x) as mxx
         from fo_a a full outer join fo_b b on a.k = b.k group by g""",
      "FULL OUTER min/max over both sides")
  }

  test("FULL OUTER grouped by a B column: A-only rows fall in the NULL group") {
    createFullOuterTables()
    assertFullOuterSplitAndCorrect(
      """select b.x as bx, count(*) as c
         from fo_a a full outer join fo_b b on a.k = b.k group by b.x""",
      "FULL OUTER group by B column (NULL group for A-only rows)")
  }

  test("FULL OUTER global aggregate (no GROUP BY) matches vanilla") {
    createFullOuterTables()
    assertFullOuterSplitAndCorrect(
      """select count(*) as c, count(a.v) as ca, sum(b.x) as sb
         from fo_a a full outer join fo_b b on a.k = b.k""",
      "FULL OUTER global aggregate")
  }

  // Fan-out dimension with matched, fan-out, and (crucially) fully-unmatched groups.
  private def createLeftOuterTables(): Unit = {
    // a-side: groups A (k=1 fan-out, k=2 single match), B (k=3 NO match -> fully unmatched),
    // C (k=4 NO match). v is an A-only measure that must still be summed for unmatched rows.
    Seq((1, "A", 10.0), (2, "A", 20.0), (3, "B", 30.0), (4, "C", 40.0))
      .toDF("k", "g", "v").createOrReplaceTempView("lo_a")
    // b-side: k=1 has two matches (fan-out), k=2 one match; k=3/k=4 absent (unmatched).
    Seq((1, 100), (1, 200), (2, 300)).toDF("k", "x").createOrReplaceTempView("lo_b")
  }

  test("LEFT OUTER count(*) over a fan-out join with unmatched groups matches vanilla") {
    createLeftOuterTables()
    // g=A: k=1 -> 2 rows, k=2 -> 1 row => 3; g=B,C: unmatched but LEFT JOIN keeps the row => 1
    assertLeftOuterSplitAndCorrect(
      "select g, count(*) as c from lo_a a left join lo_b b on a.k = b.k group by g",
      "LEFT OUTER count(*) with unmatched groups")
  }

  test("LEFT OUTER count(b.x) is 0 for fully-unmatched groups, count(*) > 0") {
    createLeftOuterTables()
    // count(b.x): A -> 3, B -> 0, C -> 0 ; count(*): A -> 3, B -> 1, C -> 1
    assertLeftOuterSplitAndCorrect(
      """select g, count(*) as c_all, count(b.x) as c_x, sum(b.x) as s_x
         from lo_a a left join lo_b b on a.k = b.k group by g""",
      "LEFT OUTER count(b.x)=0, sum(b.x)=null for unmatched groups")
  }

  test("LEFT OUTER sum of an A-only measure still counts unmatched rows") {
    createLeftOuterTables()
    // sum(a.v) by g: A -> 10*2 (k=1 fan-out) + 20 = 40, B -> 30, C -> 40
    assertLeftOuterSplitAndCorrect(
      "select g, sum(a.v) as s from lo_a a left join lo_b b on a.k = b.k group by g",
      "LEFT OUTER sum(a.v) over A-only measure")
  }

  test("LEFT OUTER min/max over B column are NULL for unmatched groups") {
    createLeftOuterTables()
    assertLeftOuterSplitAndCorrect(
      """select g, min(b.x) as mn, max(b.x) as mx, count(*) as c
         from lo_a a left join lo_b b on a.k = b.k group by g""",
      "LEFT OUTER min/max(b.x) null for unmatched groups")
  }

  test("LEFT OUTER grouped by a B column: unmatched rows fall in the NULL group") {
    createLeftOuterTables()
    // group by b.x: real x values for matched rows, plus a single NULL group for all unmatched.
    assertLeftOuterSplitAndCorrect(
      "select b.x as bx, count(*) as c from lo_a a left join lo_b b on a.k = b.k group by b.x",
      "LEFT OUTER group by B column (NULL group for unmatched)")
  }

  test("LEFT OUTER grouped by an A column AND a B column matches vanilla") {
    createLeftOuterTables()
    assertLeftOuterSplitAndCorrect(
      """select g, b.x as bx, count(*) as c, sum(b.x) as s
         from lo_a a left join lo_b b on a.k = b.k group by g, b.x""",
      "LEFT OUTER group by A and B columns")
  }

  test("LEFT OUTER global aggregate (no GROUP BY) matches vanilla") {
    createLeftOuterTables()
    // count(*) = 3 matched + 2 unmatched = 5 ; count(b.x) = 3 ; sum(a.v) sums all 5 a rows.
    assertLeftOuterSplitAndCorrect(
      """select count(*) as c_all, count(b.x) as c_x, sum(b.x) as s_x, sum(a.v) as s_v,
                min(b.x) as mn, max(b.x) as mx
         from lo_a a left join lo_b b on a.k = b.k""",
      "LEFT OUTER global aggregate")
  }

  test("RIGHT OUTER is normalised to LEFT OUTER and matches vanilla") {
    createLeftOuterTables()
    // b RIGHT JOIN a == a LEFT JOIN b: every a row is kept, unmatched ones get NULL b.x.
    assertLeftOuterSplitAndCorrect(
      """select g, count(*) as c, count(b.x) as cx, sum(b.x) as s
         from lo_b b right join lo_a a on a.k = b.k group by g""",
      "RIGHT OUTER normalised to LEFT OUTER")
  }

  test("LEFT OUTER TPC-H Q13 shape (customer LEFT JOIN orders, count of orderkey)") {
    Seq(1, 2, 3, 4, 5).toDF("c_custkey").createOrReplaceTempView("q13_customer")
    // customer 1 -> 3 orders, customer 2 -> 1 order, customers 3/4/5 -> no orders.
    Seq((10, 1), (11, 1), (12, 1), (20, 2)).toDF("o_orderkey", "o_custkey")
      .createOrReplaceTempView("q13_orders")
    // Faithful Q13 inner shape: per-customer order count (0 for customers with no orders),
    // then the distribution of those counts. Inner half = customer/orders count-join; the
    // unmatched customers (count 0) come from the anti half.
    val query = """
      select c_count, count(*) as custdist
      from (
        select c_custkey, count(o_orderkey) as c_count
        from q13_customer left join q13_orders on c_custkey = o_custkey
        group by c_custkey
      ) c_orders
      group by c_count"""
    assertSameResults(query, "TPC-H Q13 shape (customer LEFT JOIN orders)")
  }

  test("LEFT OUTER with a non-equi join condition (extra predicate on B) matches vanilla") {
    createLeftOuterTables()
    // The anti half must use the SAME condition: rows matching k but failing x>150 are unmatched.
    assertLeftOuterSplitAndCorrect(
      """select g, count(*) as c, count(b.x) as cx, sum(b.x) as s
         from lo_a a left join lo_b b on a.k = b.k and b.x > 150 group by g""",
      "LEFT OUTER with extra B predicate in the join condition")
  }

  test("LEFT OUTER over a 3-relation right subtree (nested inner join) matches vanilla") {
    Seq((1, "A"), (2, "A"), (3, "B")).toDF("k", "g").createOrReplaceTempView("lo3_a")
    Seq((1, 10), (1, 20), (2, 30)).toDF("k", "m").createOrReplaceTempView("lo3_b")
    Seq((10, 100), (20, 200), (30, 300), (40, 400)).toDF("m", "w")
      .createOrReplaceTempView("lo3_c")
    // a LEFT JOIN (b inner-join c): group A keeps fan-out, group B (k=3) is fully unmatched.
    assertLeftOuterSplitAndCorrect(
      """select g, count(*) as c, count(w) as cw, sum(w) as sw
         from lo3_a a left join (
           select b.k as k, c.w as w from lo3_b b join lo3_c c on b.m = c.m
         ) bc on a.k = bc.k
         group by g""",
      "LEFT OUTER over a multi-relation right subtree")
  }

  test("LEFT OUTER avg over a B column is accelerated (double output) and matches vanilla") {
    createLeftOuterTables()
    // avg(b.x): B-only-unmatched groups (k=3,4) have all-NULL b.x, so avg is NULL there.
    assertLeftOuterSplitAndCorrect(
      "select g, avg(b.x) as a from lo_a a left join lo_b b on a.k = b.k group by g",
      "LEFT OUTER avg(b.x) double output")
  }

  test("LEFT OUTER avg over an A-only measure (fan-out weighted) matches vanilla") {
    createLeftOuterTables()
    // avg(a.v): matched rows are fan-out weighted, unmatched A rows counted once - the merge
    // recombines total sum / total count across the matched + anti halves.
    assertLeftOuterSplitAndCorrect(
      "select g, avg(a.v) as a, count(*) as c from lo_a a left join lo_b b on a.k = b.k group by g",
      "LEFT OUTER avg(a.v) A-only fan-out")
  }

  test("FULL OUTER avg over both sides matches vanilla") {
    createFullOuterTables()
    assertFullOuterSplitAndCorrect(
      "select g, avg(a.v) as av, avg(b.x) as bx from fo_a a full outer join fo_b b on a.k = b.k" +
        " group by g",
      "FULL OUTER avg over both sides")
  }

  test("LEFT OUTER falls back for avg over a DECIMAL column (precision parity)") {
    Seq((1, "A"), (2, "A"), (3, "B")).toDF("k", "g").createOrReplaceTempView("dec_a")
    Seq((1, BigDecimal("10.25")), (1, BigDecimal("20.50")), (2, BigDecimal("30.75")))
      .toDF("k", "d").createOrReplaceTempView("dec_b")
    val query = "select g, avg(b.d) as a from dec_a a left join dec_b b on a.k = b.k group by g"
    var expected: Seq[Row] = null
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      expected = sql(query).collect().toSeq
    }
    withSQLConf(yannakakisOn: _*) {
      val df = sql(query)
      checkAnswer(df, expected)
      assert(!df.queryExecution.optimizedPlan.toString.contains("CountJoin"),
        "avg over a DECIMAL column must fall back (no exact Average precision parity)")
    }
  }

  test("LEFT OUTER falls back for count(distinct) (not a mergeable aggregate)") {
    createLeftOuterTables()
    val query =
      "select g, count(distinct b.x) as c from lo_a a left join lo_b b on a.k = b.k group by g"
    var expected: Seq[Row] = null
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      expected = sql(query).collect().toSeq
    }
    withSQLConf(yannakakisOn: _*) {
      checkAnswer(sql(query), expected)
    }
  }

  test("FULL OUTER falls back for AVG (not a mergeable aggregate)") {
    createFullOuterTables()
    val query = "select g, avg(b.x) as a from fo_a a full outer join fo_b b on a.k = b.k group by g"
    var expected: Seq[Row] = null
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      expected = sql(query).collect().toSeq
    }
    withSQLConf(yannakakisOn: _*) {
      checkAnswer(sql(query), expected)
    }
  }
}
