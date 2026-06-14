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
    SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
    SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true")

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
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_COST_GATE_ENABLED.key -> "true") {
      val df = sql(q)
      checkAnswer(df, expected)
      assert(!df.queryExecution.optimizedPlan.toString.contains("CountJoin"),
        "cost gate should suppress the rewrite when the baseline can broadcast")
    }
    // gate ON but broadcast disabled -> nothing broadcast-eligible -> rewrite fires
    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_COST_GATE_ENABLED.key -> "true",
      SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1") {
      val df = sql(q)
      checkAnswer(df, expected)
      assert(df.queryExecution.optimizedPlan.toString.contains("CountJoin"),
        "cost gate should allow the rewrite when no relation is broadcast-eligible")
    }
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

  test("non-guarded mixed distinct and additive aggregates bail out and stay correct") {
    Seq((1, 10)).toDF("g", "k").createOrReplaceTempView("md_r1")
    Seq((10, 100), (10, 200)).toDF("k", "m").createOrReplaceTempView("md_r2")
    Seq((100, 7, 1.0), (200, 8, 2.0)).toDF("m", "x", "v").createOrReplaceTempView("md_r3")
    // count(distinct x) + sum(v): not all duplicate-insensitive -> must NOT take distinct path
    assertNotRewrittenButCorrect("""
      select g, count(distinct x) as c, sum(v) as s
      from md_r1, md_r2, md_r3
      where md_r1.k = md_r2.k and md_r2.m = md_r3.m
      group by g""",
      "mixed distinct + additive")
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
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "false",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true") {
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
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true",
      SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1",
      SQLConf.PREFER_SORTMERGEJOIN.key -> "true") {
      val df = sql(query)
      // scalastyle:off println
      println("Q7BUG executed plan:\n" + df.queryExecution.executedPlan)
      // scalastyle:on println
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

  test("mixed distinct and plain aggregates bail out and stay correct") {
    Seq((1, "A", 10, 1.0), (2, "A", 10, 2.0), (3, "B", 30, 3.0))
      .toDF("k", "g", "x", "y").createOrReplaceTempView("mx1")
    Seq(1, 1, 2, 3).toDF("k").createOrReplaceTempView("mx2")

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
      assert(!plan.contains("CountJoin") && !plan.contains("LeftSemi"),
        "mixed distinct+plain aggregates must not be rewritten (phase 1):\n" + plan)
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

  // Force the shuffle and sort-merge count-join operators. The default planner already picks
  // broadcast for these tiny relations (so the shared HashCountJoin trait is covered by the
  // other tests via the broadcast variant); "shuffle" re-covers that same trait, and
  // "sortMerge" exercises the otherwise-never-selected SortMergeCountJoin evaluator. Broadcast
  // is intentionally not forced: a multi-relation right subtree can exceed the broadcast size
  // threshold, so it is not always a feasible choice to force.
  private val countJoinOperators = Seq("shuffle", "sortMerge")

  private val q9FromOrders: Seq[Seq[String]] = {
    val relations = Seq("part_t9", "supplier_t9", "lineitem_t9", "partsupp_t9",
      "orders_t9", "nation_t9")
    (0 until relations.size).map(i => relations.drop(i) ++ relations.take(i)) ++
      Seq(relations.reverse,
        Seq("partsupp_t9", "lineitem_t9", "part_t9", "supplier_t9", "nation_t9", "orders_t9"),
        Seq("orders_t9", "lineitem_t9", "partsupp_t9", "nation_t9", "supplier_t9", "part_t9"),
        Seq("nation_t9", "orders_t9", "part_t9", "partsupp_t9", "supplier_t9", "lineitem_t9"))
  }

  test("Q9 grouped count-join: per-group sums correct under every physical operator") {
    // nation-rooted order builds a CountJoin that both groups (groupRight) and aggregates -
    // the shape that exposed the buffer-aliasing bug; run it under each physical operator.
    val fromOrder = Seq("supplier_t9", "lineitem_t9", "partsupp_t9",
      "orders_t9", "nation_t9", "part_t9")
    for (op <- countJoinOperators) {
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
      withSQLConf(SQLConf.YANNAKAKIS_FORCE_PHYSICAL_COUNTJOIN_OPERATOR.key -> op) {
        assertSameResults(query, s"Q9 grouped sums, operator=$op")
      }
    }
  }

  test("count-join GROUP BY <expr>: correct under every physical operator and FROM order") {
    // GROUP BY year(o_orderdate): the rewrite reduces by the underlying attribute o_orderdate
    // (carried in groupRight) and reconstructs year() at the final aggregate. Exercises the
    // grouped count-join under each operator with a grouping EXPRESSION (not a plain key), and
    // a simple single-relation sum to isolate the grouping path from product handling.
    for (op <- countJoinOperators; fromOrder <- q9FromOrders) {
      createQ9Tables()
      val query = s"""
        select n_name as nation, year(o_orderdate) as o_year, sum(l_extendedprice) as rev
        from ${fromOrder.mkString(", ")}
        where s_suppkey = l_suppkey and ps_suppkey = l_suppkey
          and ps_partkey = l_partkey and p_partkey = l_partkey
          and o_orderkey = l_orderkey and s_nationkey = n_nationkey
          and p_name like '%green%'
        group by n_name, year(o_orderdate)"""
      withSQLConf(SQLConf.YANNAKAKIS_FORCE_PHYSICAL_COUNTJOIN_OPERATOR.key -> op) {
        assertSameResults(query,
          s"GROUP BY year(o_orderdate), operator=$op, order=${fromOrder.mkString(",")}")
      }
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
    // Enable yannakakis + the physical count join, but do NOT set unguardedEnabled explicitly:
    // the unguarded count-join rewrite should fire because it now defaults to true.
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "true",
                SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true") {
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

  test("cross-relation filter: correct under shuffle and sort-merge operators") {
    Seq((1, 100, 5), (1, 200, 50), (2, 300, 1)).toDF("k", "v", "x")
      .createOrReplaceTempView("cf_a")
    Seq((1, 10), (1, 60), (2, 0)).toDF("k", "y").createOrReplaceTempView("cf_b")
    val query = "select sum(v) as s from cf_a a, cf_b b where a.k = b.k and a.x < b.y"
    for (op <- countJoinOperators) {
      withSQLConf(SQLConf.YANNAKAKIS_FORCE_PHYSICAL_COUNTJOIN_OPERATOR.key -> op) {
        assertSameResults(query, s"cross-relation filter a.x < b.y, operator=$op")
      }
    }
  }

  test("grouped count-join correct under sort-merge spill (tiny in-memory threshold)") {
    // Force the SortMergeCountJoin buffered-matches array onto its spillable path by capping the
    // in-memory threshold at 1 row, with multiple matches per key (the Q9 nation-rooted shape).
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
      ) as profit group by nation, o_year"""
    withSQLConf(
      SQLConf.YANNAKAKIS_FORCE_PHYSICAL_COUNTJOIN_OPERATOR.key -> "sortMerge",
      SQLConf.SORT_MERGE_JOIN_EXEC_BUFFER_IN_MEMORY_THRESHOLD.key -> "1") {
      assertSameResults(query, "grouped count-join under sort-merge spill")
    }
  }
}
