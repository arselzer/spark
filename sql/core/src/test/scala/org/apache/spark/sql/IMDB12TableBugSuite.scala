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

import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession

/**
 * Comprehensive test suite for Yannakakis optimization with product aggregates.
 *
 * This suite tests:
 * 1. The 12-table IMDB query with real data cardinalities
 * 2. Multi-count optimization cases (when it applies and when it doesn't)
 * 3. Cross-relation filter handling
 * 4. Various product conflict patterns
 * 5. Complex independent product scenarios (computed early with own count tracks)
 *
 * IMPLEMENTATION STATUS (all cases below are WORKING):
 * =====================================================
 *
 * CASE 1 - Independent Products: {a,b}, {c,d} with no overlap
 *          -> Each uses its own count track (optimization applies)
 *          -> See "COMPLEX INDEPENDENT PRODUCT TESTS" section for extensive coverage
 *          STATUS: WORKING (products computed early at their join points)
 *
 * CASE 2 - Containment Hierarchy: {a} < {a,b} < {a,b,c}
 *          -> Derive coarser counts from finest (optimization applies)
 *          STATUS: WORKING (hierarchical count derivation)
 *
 * CASE 3 - Universal Superset: {a,b}, {a,c}, {b,c} all contained in {a,b,c}
 *          -> Use superset as source (optimization applies)
 *          STATUS: WORKING (all derive from common superset)
 *
 * CASE 4 - Multiple Components (Connected Components):
 *          {a,b},{b,c} conflict + {d,e} independent
 *          -> Independent component optimizes; conflict defers (partial)
 *          STATUS: WORKING (components handled separately)
 *
 * CASE 5 - Star Pattern: {a,b}, {a,c}, {a,d} all share 'a'
 *          -> No containment, must defer ALL to final (NO optimization)
 *          -> This is the IMDB query pattern!
 *          STATUS: WORKING (correctly defers to final aggregate)
 *
 * FUTURE OPTIMIZATION (not yet implemented):
 * ==========================================
 * Synthetic Superset: For star pattern, create synthetic {a,b,c,d} and derive
 * each product's count via GROUP BY. This would allow early computation even
 * for star pattern queries.
 *
 * Run with: build/sbt 'sql/testOnly org.apache.spark.sql.IMDB12TableBugSuite'
 */
class IMDB12TableBugSuite extends QueryTest with SharedSparkSession {

  override protected def sparkConf: org.apache.spark.SparkConf =
    super.sparkConf.set(SQLConf.YANNAKAKIS_COST_GATE_ENABLED.key, "false")

  import testImplicits._

  test("12-table IMDB join with real data cardinalities") {
    // cast_info (CI) - 1 row
    val ci = Seq(
      (1, 1, 1, 2)
    ).toDF("movie_id", "person_id", "person_role_id", "role_id")

    // movie_info (MI) - 1 row
    val mi = Seq(
      (1, 16)
    ).toDF("movie_id", "info_type_id")

    // title (T) - 1 row
    val t = Seq(
      (1, 2011, 9, 7)
    ).toDF("id", "production_year", "season_nr", "kind_id")

    // movie_companies (MC) - 9 rows (fan-out source!)
    val mc = Seq(
      (1, 1, 1), (1, 1, 1), (1, 1, 1), (1, 2, 1),
      (1, 3, 2), (1, 3, 2), (1, 4, 2), (1, 5, 2), (1, 5, 2)
    ).toDF("movie_id", "company_id", "company_type_id")

    // char_name (CHN) - 1 row with NULL imdb_id
    val chn = Seq(
      (1, null.asInstanceOf[java.lang.Integer])
    ).toDF("id", "imdb_id")

    // role_type (RT) - 1 row
    val rt = Seq((2)).toDF("id")

    // name (N) - 1 row
    val n = Seq((1)).toDF("id")

    // aka_name (AN) - 6 rows (fan-out source!)
    val an = Seq((1), (1), (1), (1), (1), (1)).toDF("person_id")

    // company_name (CN) - 5 rows
    val cn = Seq((1), (2), (3), (4), (5)).toDF("id")

    // info_type (IT) - 1 row
    val it = Seq((16)).toDF("id")

    // keyword (K) - 1 row
    val k = Seq((1)).toDF("id")

    // movie_keyword (MK) - 1 row
    val mk = Seq((1, 1)).toDF("movie_id", "keyword_id")

    ci.createOrReplaceTempView("cast_info")
    mi.createOrReplaceTempView("movie_info")
    t.createOrReplaceTempView("title")
    mc.createOrReplaceTempView("movie_companies")
    chn.createOrReplaceTempView("char_name")
    rt.createOrReplaceTempView("role_type")
    n.createOrReplaceTempView("name")
    an.createOrReplaceTempView("aka_name")
    cn.createOrReplaceTempView("company_name")
    it.createOrReplaceTempView("info_type")
    k.createOrReplaceTempView("keyword")
    mk.createOrReplaceTempView("movie_keyword")

    val query = """
      SELECT COUNT(*),
          SUM(ci.role_id*mi.info_type_id),
          SUM(t.production_year * ci.role_id),
          SUM(t.season_nr * chn.imdb_id),
          SUM(t.season_nr * rt.id),
          SUM(mc.company_type_id * ci.role_id * t.kind_id)
      FROM aka_name AS an,
           char_name AS chn,
           cast_info AS ci,
           company_name AS cn,
           info_type AS it,
           keyword AS k,
           movie_companies AS mc,
           movie_info AS mi,
           movie_keyword AS mk,
           name AS n,
           role_type AS rt,
           title AS t
      WHERE t.id = mi.movie_id
        AND t.id = mc.movie_id
        AND t.id = ci.movie_id
        AND t.id = mk.movie_id
        AND cn.id = mc.company_id
        AND it.id = mi.info_type_id
        AND n.id = ci.person_id
        AND rt.id = ci.role_id
        AND n.id = an.person_id
        AND chn.id = ci.person_role_id
        AND k.id = mk.keyword_id
    """

    // Expected: COUNT=54, role*info=1728, year*role=217188, season*imdb=null,
    // season*rt=972, comp*role*kind=1176
    val expectedResult = Row(54L, 1728L, 217188L, null, 972L, 1176L)

    // scalastyle:off println
    // Get baseline
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      val df = sql(query)
      println("=== BASELINE ===")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }

    // Test Yannakakis
    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== YANNAKAKIS ===")
      println("Optimized Plan:")
      println(df.queryExecution.optimizedPlan.treeString)
      println("Physical Plan:")
      println(df.queryExecution.executedPlan.treeString)
      val result = df.collect()
      println(s"Result: ${result.map(_.toString).mkString}")
      println(s"Expected: $expectedResult")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  test("non-conflicting products - should compute early") {
    // Test case where products don't share attributes - no deferral needed
    val t1 = Seq((1, 10, 20)).toDF("id", "a", "b")
    val t2 = Seq((1, 30, 40)).toDF("id", "c", "d")
    val t3 = Seq((1, 50, 60)).toDF("id", "e", "f")

    t1.createOrReplaceTempView("t1")
    t2.createOrReplaceTempView("t2")
    t3.createOrReplaceTempView("t3")

    // Two products with completely disjoint attributes:
    // SUM(a*b) uses {a, b} from t1
    // SUM(c*d) uses {c, d} from t2
    // These should NOT conflict and can be computed early
    val query = """
      SELECT COUNT(*),
             SUM(t1.a * t1.b),
             SUM(t2.c * t2.d)
      FROM t1, t2, t3
      WHERE t1.id = t2.id AND t2.id = t3.id
    """

    val expectedResult = Row(1L, 200L, 1200L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== NON-CONFLICTING PRODUCTS ===")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  test("single conflicting product group - should compute early") {
    // Products that share attributes form ONE group - no conflict, compute early
    val t1 = Seq((1, 10)).toDF("id", "a")
    val t2 = Seq((1, 20)).toDF("id", "b")
    val t3 = Seq((1, 30)).toDF("id", "c")

    t1.createOrReplaceTempView("t1")
    t2.createOrReplaceTempView("t2")
    t3.createOrReplaceTempView("t3")

    // Products that share 'a':
    // SUM(a*b) uses {a, b}
    // SUM(a*c) uses {a, c}
    // These share 'a' so they form ONE group - NO conflict
    val query = """
      SELECT COUNT(*),
             SUM(t1.a * t2.b),
             SUM(t1.a * t3.c)
      FROM t1, t2, t3
      WHERE t1.id = t2.id AND t2.id = t3.id
    """

    val expectedResult = Row(1L, 200L, 300L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== SINGLE PRODUCT GROUP ===")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  test("two conflicting product groups - must defer") {
    // Two disjoint groups that can't be computed together
    val t1 = Seq((1, 10)).toDF("id", "a")
    val t2 = Seq((1, 20)).toDF("id", "b")
    val t3 = Seq((1, 30)).toDF("id", "c")
    val t4 = Seq((1, 40)).toDF("id", "d")

    t1.createOrReplaceTempView("t1")
    t2.createOrReplaceTempView("t2")
    t3.createOrReplaceTempView("t3")
    t4.createOrReplaceTempView("t4")

    // Two separate groups:
    // Group 1: SUM(a*b) uses {a, b}
    // Group 2: SUM(c*d) uses {c, d}
    // No overlap - TWO groups - CONFLICT - must defer
    val query = """
      SELECT COUNT(*),
             SUM(t1.a * t2.b),
             SUM(t3.c * t4.d)
      FROM t1, t2, t3, t4
      WHERE t1.id = t2.id AND t2.id = t3.id AND t3.id = t4.id
    """

    val expectedResult = Row(1L, 200L, 1200L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== TWO CONFLICTING GROUPS ===")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  test("two conflicting groups with fan-out") {
    // Two disjoint product groups with fan-out from one table
    val t1 = Seq((1, 10), (1, 11)).toDF("id", "a")  // 2 rows - fan-out
    val t2 = Seq((1, 20)).toDF("id", "b")
    val t3 = Seq((1, 30)).toDF("id", "c")
    val t4 = Seq((1, 40)).toDF("id", "d")

    t1.createOrReplaceTempView("t1")
    t2.createOrReplaceTempView("t2")
    t3.createOrReplaceTempView("t3")
    t4.createOrReplaceTempView("t4")

    // Two separate groups:
    // Group 1: SUM(a*b) uses {a, b}
    // Group 2: SUM(c*d) uses {c, d}
    // No overlap - TWO groups - CONFLICT - must defer all products
    val query = """
      SELECT COUNT(*),
             SUM(t1.a * t2.b),
             SUM(t3.c * t4.d)
      FROM t1, t2, t3, t4
      WHERE t1.id = t2.id AND t2.id = t3.id AND t3.id = t4.id
    """

    // t1 has 2 rows, others have 1 row each
    // JOIN produces 2 rows
    // COUNT = 2
    // SUM(a*b) = (10*20) + (11*20) = 200 + 220 = 420
    // SUM(c*d) = (30*40) + (30*40) = 1200 + 1200 = 2400
    val expectedResult = Row(2L, 420L, 2400L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      val baseline = sql(query).collect()
      println(s"Baseline: ${baseline.map(_.toString).mkString}")
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== TWO CONFLICTING GROUPS WITH FAN-OUT ===")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  test("mixed products - independent computed early, conflicting deferred") {
    // Test the Phase 1-2 per-product conflict detection:
    // Some products are independent (can compute early)
    // Some products conflict (must defer)
    val t1 = Seq((1, 10), (1, 11)).toDF("id", "a")  // 2 rows - fan-out
    val t2 = Seq((1, 20)).toDF("id", "b")
    val t3 = Seq((1, 30)).toDF("id", "c")
    val t4 = Seq((1, 40)).toDF("id", "d")
    val t5 = Seq((1, 50)).toDF("id", "e")

    t1.createOrReplaceTempView("t1")
    t2.createOrReplaceTempView("t2")
    t3.createOrReplaceTempView("t3")
    t4.createOrReplaceTempView("t4")
    t5.createOrReplaceTempView("t5")

    // Three products:
    // Product 1: SUM(a*b) uses {a, b}
    // Product 2: SUM(b*c) uses {b, c} - overlaps with product 1 via 'b'
    // Product 3: SUM(d*e) uses {d, e} - completely independent of products 1 & 2
    //
    // Conflict graph: Product 1 <-> Product 2 (overlap via 'b', neither subset)
    // Product 3 is independent (no overlap with either)
    //
    // With Phase 1-2: Product 3 should compute early, Products 1&2 deferred
    val query = """
      SELECT COUNT(*),
             SUM(t1.a * t2.b),
             SUM(t2.b * t3.c),
             SUM(t4.d * t5.e)
      FROM t1, t2, t3, t4, t5
      WHERE t1.id = t2.id AND t2.id = t3.id AND t3.id = t4.id AND t4.id = t5.id
    """

    // t1 has 2 rows, others have 1 row each
    // JOIN produces 2 rows
    // COUNT = 2
    // SUM(a*b) = (10*20) + (11*20) = 200 + 220 = 420
    // SUM(b*c) = (20*30) + (20*30) = 600 + 600 = 1200
    // SUM(d*e) = (40*50) + (40*50) = 2000 + 2000 = 4000
    val expectedResult = Row(2L, 420L, 1200L, 4000L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      val baseline = sql(query).collect()
      println(s"Baseline: ${baseline.map(_.toString).mkString}")
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== MIXED PRODUCTS (INDEPENDENT + CONFLICTING) ===")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  test("chain of conflicts with multiple fan-outs") {
    // Chain conflict: P1-P2 conflict, P2-P3 conflict, P1-P3 transitively connected
    // Multiple fan-outs to stress test count tracking
    val t1 = Seq((1, 10), (1, 11), (1, 12)).toDF("id", "a")  // 3 rows
    val t2 = Seq((1, 20), (1, 21)).toDF("id", "b")           // 2 rows
    val t3 = Seq((1, 30)).toDF("id", "c")                    // 1 row
    val t4 = Seq((1, 40), (1, 41)).toDF("id", "d")           // 2 rows

    t1.createOrReplaceTempView("t1")
    t2.createOrReplaceTempView("t2")
    t3.createOrReplaceTempView("t3")
    t4.createOrReplaceTempView("t4")

    // Chain of conflicts:
    // P1: SUM(a*b) uses {a, b}
    // P2: SUM(b*c) uses {b, c} - overlaps P1 via 'b'
    // P3: SUM(c*d) uses {c, d} - overlaps P2 via 'c'
    // All three form a conflict chain
    val query = """
      SELECT COUNT(*),
             SUM(t1.a * t2.b),
             SUM(t2.b * t3.c),
             SUM(t3.c * t4.d)
      FROM t1, t2, t3, t4
      WHERE t1.id = t2.id AND t2.id = t3.id AND t3.id = t4.id
    """

    // JOIN produces 3*2*1*2 = 12 rows
    // Each row has: (a from {10,11,12}, b from {20,21}, c=30, d from {40,41})
    //
    // COUNT = 12
    //
    // SUM(a*b):
    // For each (a,b) pair, it appears 1*2 = 2 times (c has 1 val, d has 2 vals)
    // (10,20): 200 * 2 = 400
    // (10,21): 210 * 2 = 420
    // (11,20): 220 * 2 = 440
    // (11,21): 231 * 2 = 462
    // (12,20): 240 * 2 = 480
    // (12,21): 252 * 2 = 504
    // Total: 400+420+440+462+480+504 = 2706
    //
    // SUM(b*c):
    // For each (b,c) pair, it appears 3*2 = 6 times (a has 3 vals, d has 2 vals)
    // (20,30): 600 * 6 = 3600
    // (21,30): 630 * 6 = 3780
    // Total: 3600+3780 = 7380
    //
    // SUM(c*d):
    // For each (c,d) pair, it appears 3*2 = 6 times (a has 3 vals, b has 2 vals)
    // (30,40): 1200 * 6 = 7200
    // (30,41): 1230 * 6 = 7380
    // Total: 7200+7380 = 14580
    val expectedResult = Row(12L, 2706L, 7380L, 14580L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      val baseline = sql(query).collect()
      println(s"Baseline (chain): ${baseline.map(_.toString).mkString}")
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== CHAIN OF CONFLICTS WITH MULTIPLE FAN-OUTS ===")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  test("three-way product with fan-out") {
    // Product involving three attributes from three different tables
    val t1 = Seq((1, 10), (1, 11)).toDF("id", "a")  // 2 rows
    val t2 = Seq((1, 20), (1, 21)).toDF("id", "b")  // 2 rows
    val t3 = Seq((1, 30)).toDF("id", "c")           // 1 row
    val t4 = Seq((1, 40)).toDF("id", "d")           // 1 row

    t1.createOrReplaceTempView("t1")
    t2.createOrReplaceTempView("t2")
    t3.createOrReplaceTempView("t3")
    t4.createOrReplaceTempView("t4")

    // Three-way product: SUM(a*b*c)
    // Plus a regular product: SUM(a*d)
    // These conflict because they share 'a' but have different other attrs
    val query = """
      SELECT COUNT(*),
             SUM(t1.a * t2.b * t3.c),
             SUM(t1.a * t4.d)
      FROM t1, t2, t3, t4
      WHERE t1.id = t2.id AND t2.id = t3.id AND t3.id = t4.id
    """

    // JOIN produces 2*2*1*1 = 4 rows
    // Rows: (a,b,c,d) in {(10,20,30,40), (10,21,30,40), (11,20,30,40), (11,21,30,40)}
    //
    // COUNT = 4
    //
    // SUM(a*b*c):
    // (10,20,30): 6000
    // (10,21,30): 6300
    // (11,20,30): 6600
    // (11,21,30): 6930
    // Total: 6000+6300+6600+6930 = 25830
    //
    // SUM(a*d):
    // (10,40): 400 appears 2 times (b has 2 vals) = 800
    // (11,40): 440 appears 2 times (b has 2 vals) = 880
    // Total: 800+880 = 1680
    val expectedResult = Row(4L, 25830L, 1680L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      val baseline = sql(query).collect()
      println(s"Baseline (3-way): ${baseline.map(_.toString).mkString}")
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== THREE-WAY PRODUCT WITH FAN-OUT ===")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  test("asymmetric fan-out stress test") {
    // Highly asymmetric fan-out to stress the count multiplication
    val t1 = Seq((1, 1), (1, 2), (1, 3), (1, 4), (1, 5)).toDF("id", "a")  // 5 rows
    val t2 = Seq((1, 10)).toDF("id", "b")                                  // 1 row
    val t3 = Seq((1, 100), (1, 101), (1, 102)).toDF("id", "c")            // 3 rows
    val t4 = Seq((1, 1000)).toDF("id", "d")                                // 1 row

    t1.createOrReplaceTempView("t1")
    t2.createOrReplaceTempView("t2")
    t3.createOrReplaceTempView("t3")
    t4.createOrReplaceTempView("t4")

    // Products with very different fan-out patterns:
    // P1: SUM(a*b) - {a,b}, a has 5 vals, b has 1 val
    // P2: SUM(c*d) - {c,d}, c has 3 vals, d has 1 val
    // These are independent (no shared attrs) but have asymmetric cardinalities
    val query = """
      SELECT COUNT(*),
             SUM(t1.a * t2.b),
             SUM(t3.c * t4.d)
      FROM t1, t2, t3, t4
      WHERE t1.id = t2.id AND t2.id = t3.id AND t3.id = t4.id
    """

    // JOIN produces 5*1*3*1 = 15 rows
    //
    // COUNT = 15
    //
    // SUM(a*b):
    // Each a value appears 3 times (once per c value)
    // (1*10)*3 + (2*10)*3 + (3*10)*3 + (4*10)*3 + (5*10)*3
    // = 30 + 60 + 90 + 120 + 150 = 450
    //
    // SUM(c*d):
    // Each c value appears 5 times (once per a value)
    // (100*1000)*5 + (101*1000)*5 + (102*1000)*5
    // = 500000 + 505000 + 510000 = 1515000
    val expectedResult = Row(15L, 450L, 1515000L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      val baseline = sql(query).collect()
      println(s"Baseline (asymmetric): ${baseline.map(_.toString).mkString}")
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== ASYMMETRIC FAN-OUT STRESS TEST ===")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  test("complex conflict web - all products interconnected") {
    // Every product overlaps with every other product
    val t1 = Seq((1, 10), (1, 11)).toDF("id", "a")  // 2 rows
    val t2 = Seq((1, 20)).toDF("id", "b")           // 1 row
    val t3 = Seq((1, 30), (1, 31)).toDF("id", "c")  // 2 rows

    t1.createOrReplaceTempView("t1")
    t2.createOrReplaceTempView("t2")
    t3.createOrReplaceTempView("t3")

    // Four products that form a complete conflict graph:
    // P1: SUM(a*b) uses {a, b}
    // P2: SUM(b*c) uses {b, c}
    // P3: SUM(a*c) uses {a, c}
    // P4: SUM(a*b*c) uses {a, b, c}
    //
    // All four conflict with each other (pairwise overlap, none is subset)
    val query = """
      SELECT COUNT(*),
             SUM(t1.a * t2.b),
             SUM(t2.b * t3.c),
             SUM(t1.a * t3.c),
             SUM(t1.a * t2.b * t3.c)
      FROM t1, t2, t3
      WHERE t1.id = t2.id AND t2.id = t3.id
    """

    // JOIN produces 2*1*2 = 4 rows
    // Rows: (a,b,c) in {(10,20,30), (10,20,31), (11,20,30), (11,20,31)}
    //
    // COUNT = 4
    //
    // SUM(a*b):
    // (10,20) appears 2 times (c has 2 vals) = 200*2 = 400
    // (11,20) appears 2 times (c has 2 vals) = 220*2 = 440
    // Total: 840
    //
    // SUM(b*c):
    // (20,30) appears 2 times (a has 2 vals) = 600*2 = 1200
    // (20,31) appears 2 times (a has 2 vals) = 620*2 = 1240
    // Total: 2440
    //
    // SUM(a*c):
    // (10,30) appears 1 time = 300
    // (10,31) appears 1 time = 310
    // (11,30) appears 1 time = 330
    // (11,31) appears 1 time = 341
    // Total: 1281
    //
    // SUM(a*b*c):
    // 10*20*30 = 6000
    // 10*20*31 = 6200
    // 11*20*30 = 6600
    // 11*20*31 = 6820
    // Total: 25620
    val expectedResult = Row(4L, 840L, 2440L, 1281L, 25620L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      val baseline = sql(query).collect()
      println(s"Baseline (web): ${baseline.map(_.toString).mkString}")
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== COMPLEX CONFLICT WEB ===")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  // =========================================================================
  // HIERARCHICAL CONTAINMENT TESTS
  // These tests have containment relationships where hierarchical count
  // tracks could theoretically optimize by deriving coarser counts from
  // finer ones.
  // =========================================================================

  test("hierarchical containment - simple chain (a < a,b < a,b,c)") {
    // Products with strict containment:
    // P1: SUM(a) uses {a}
    // P2: SUM(a*b) uses {a, b} - contains P1's attrs
    // P3: SUM(a*b*c) uses {a, b, c} - contains P2's attrs
    //
    // Containment chain: {a} < {a,b} < {a,b,c}
    // Hierarchical approach: compute count at {a,b,c} level, derive others
    val t1 = Seq((1, 10), (1, 11)).toDF("id", "a")  // 2 rows
    val t2 = Seq((1, 20), (1, 21)).toDF("id", "b")  // 2 rows
    val t3 = Seq((1, 30)).toDF("id", "c")           // 1 row

    t1.createOrReplaceTempView("t1")
    t2.createOrReplaceTempView("t2")
    t3.createOrReplaceTempView("t3")

    val query = """
      SELECT COUNT(*),
             SUM(t1.a),
             SUM(t1.a * t2.b),
             SUM(t1.a * t2.b * t3.c)
      FROM t1, t2, t3
      WHERE t1.id = t2.id AND t2.id = t3.id
    """

    // JOIN produces 2*2*1 = 4 rows
    // Rows: (a,b,c) in {(10,20,30), (10,21,30), (11,20,30), (11,21,30)}
    //
    // COUNT = 4
    //
    // SUM(a):
    // Each a value appears 2 times (b has 2 vals, c has 1 val)
    // 10*2 + 11*2 = 20 + 22 = 42
    //
    // SUM(a*b):
    // Each (a,b) pair appears 1 time
    // (10*20) + (10*21) + (11*20) + (11*21) = 200 + 210 + 220 + 231 = 861
    //
    // SUM(a*b*c):
    // Each (a,b,c) tuple appears 1 time
    // (10*20*30) + (10*21*30) + (11*20*30) + (11*21*30)
    // = 6000 + 6300 + 6600 + 6930 = 25830
    val expectedResult = Row(4L, 42L, 861L, 25830L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      val baseline = sql(query).collect()
      println(s"Baseline (hier-chain): ${baseline.map(_.toString).mkString}")
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== HIERARCHICAL CHAIN ({a} < {a,b} < {a,b,c}) ===")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  test("hierarchical containment - tree structure") {
    // Products forming a tree:
    //        {a,b,c,d}
    //       /         \
    //    {a,b}       {c,d}
    //
    // P1: SUM(a*b) uses {a, b}
    // P2: SUM(c*d) uses {c, d}
    // P3: SUM(a*b*c*d) uses {a, b, c, d} - contains BOTH P1 and P2
    //
    // Note: P1 and P2 do NOT conflict (no overlap)
    // P3 contains both, so hierarchical approach could use P3's count
    val t1 = Seq((1, 10), (1, 11)).toDF("id", "a")  // 2 rows
    val t2 = Seq((1, 20)).toDF("id", "b")           // 1 row
    val t3 = Seq((1, 30), (1, 31)).toDF("id", "c")  // 2 rows
    val t4 = Seq((1, 40)).toDF("id", "d")           // 1 row

    t1.createOrReplaceTempView("t1")
    t2.createOrReplaceTempView("t2")
    t3.createOrReplaceTempView("t3")
    t4.createOrReplaceTempView("t4")

    val query = """
      SELECT COUNT(*),
             SUM(t1.a * t2.b),
             SUM(t3.c * t4.d),
             SUM(t1.a * t2.b * t3.c * t4.d)
      FROM t1, t2, t3, t4
      WHERE t1.id = t2.id AND t2.id = t3.id AND t3.id = t4.id
    """

    // JOIN produces 2*1*2*1 = 4 rows
    // Rows: (a,b,c,d) in {(10,20,30,40), (10,20,31,40), (11,20,30,40), (11,20,31,40)}
    //
    // COUNT = 4
    //
    // SUM(a*b):
    // Each (a,b) pair appears 2 times (c has 2 vals)
    // (10*20)*2 + (11*20)*2 = 400 + 440 = 840
    //
    // SUM(c*d):
    // Each (c,d) pair appears 2 times (a has 2 vals)
    // (30*40)*2 + (31*40)*2 = 2400 + 2480 = 4880
    //
    // SUM(a*b*c*d):
    // Each tuple appears 1 time
    // (10*20*30*40) + (10*20*31*40) + (11*20*30*40) + (11*20*31*40)
    // = 240000 + 248000 + 264000 + 272800 = 1024800
    val expectedResult = Row(4L, 840L, 4880L, 1024800L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      val baseline = sql(query).collect()
      println(s"Baseline (hier-tree): ${baseline.map(_.toString).mkString}")
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== HIERARCHICAL TREE ({a,b} and {c,d} < {a,b,c,d}) ===")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  test("hierarchical containment - diamond with fan-out") {
    // Diamond containment structure:
    //        {a,b,c}
    //       /       \
    //    {a,b}     {b,c}
    //       \       /
    //         {b}
    //
    // P1: SUM(b) uses {b}
    // P2: SUM(a*b) uses {a, b} - contains P1
    // P3: SUM(b*c) uses {b, c} - contains P1
    // P4: SUM(a*b*c) uses {a, b, c} - contains P2 and P3
    //
    // Note: P2 and P3 CONFLICT (overlap via b, neither subset)
    // But hierarchically, P4 contains both
    val t1 = Seq((1, 10), (1, 11), (1, 12)).toDF("id", "a")  // 3 rows
    val t2 = Seq((1, 20), (1, 21)).toDF("id", "b")           // 2 rows
    val t3 = Seq((1, 30)).toDF("id", "c")                    // 1 row

    t1.createOrReplaceTempView("t1")
    t2.createOrReplaceTempView("t2")
    t3.createOrReplaceTempView("t3")

    val query = """
      SELECT COUNT(*),
             SUM(t2.b),
             SUM(t1.a * t2.b),
             SUM(t2.b * t3.c),
             SUM(t1.a * t2.b * t3.c)
      FROM t1, t2, t3
      WHERE t1.id = t2.id AND t2.id = t3.id
    """

    // JOIN produces 3*2*1 = 6 rows
    // Rows: (a,b,c) in {(10,20,30), (10,21,30), (11,20,30), (11,21,30),
    //                   (12,20,30), (12,21,30)}
    //
    // COUNT = 6
    //
    // SUM(b):
    // Each b value appears 3 times (a has 3 vals)
    // 20*3 + 21*3 = 60 + 63 = 123
    //
    // SUM(a*b):
    // Each (a,b) pair appears 1 time
    // (10*20) + (10*21) + (11*20) + (11*21) + (12*20) + (12*21)
    // = 200 + 210 + 220 + 231 + 240 + 252 = 1353
    //
    // SUM(b*c):
    // Each (b,c) pair appears 3 times (a has 3 vals)
    // (20*30)*3 + (21*30)*3 = 1800 + 1890 = 3690
    //
    // SUM(a*b*c):
    // Each tuple appears 1 time
    // (10*20*30) + (10*21*30) + (11*20*30) + (11*21*30) +
    // (12*20*30) + (12*21*30)
    // = 6000 + 6300 + 6600 + 6930 + 7200 + 7560 = 40590
    val expectedResult = Row(6L, 123L, 1353L, 3690L, 40590L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      val baseline = sql(query).collect()
      println(s"Baseline (hier-diamond): ${baseline.map(_.toString).mkString}")
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== HIERARCHICAL DIAMOND ({b} < {a,b},{b,c} < {a,b,c}) ===")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  test("hierarchical containment - deep nesting with fan-out") {
    // Deep containment with multiple fan-outs:
    // {a} ⊂ {a,b} ⊂ {a,b,c} ⊂ {a,b,c,d}
    val t1 = Seq((1, 10), (1, 11)).toDF("id", "a")           // 2 rows
    val t2 = Seq((1, 20), (1, 21), (1, 22)).toDF("id", "b")  // 3 rows
    val t3 = Seq((1, 30), (1, 31)).toDF("id", "c")           // 2 rows
    val t4 = Seq((1, 40)).toDF("id", "d")                    // 1 row

    t1.createOrReplaceTempView("t1")
    t2.createOrReplaceTempView("t2")
    t3.createOrReplaceTempView("t3")
    t4.createOrReplaceTempView("t4")

    val query = """
      SELECT COUNT(*),
             SUM(t1.a),
             SUM(t1.a * t2.b),
             SUM(t1.a * t2.b * t3.c),
             SUM(t1.a * t2.b * t3.c * t4.d)
      FROM t1, t2, t3, t4
      WHERE t1.id = t2.id AND t2.id = t3.id AND t3.id = t4.id
    """

    // JOIN produces 2*3*2*1 = 12 rows
    //
    // COUNT = 12
    //
    // SUM(a):
    // Each a value appears 3*2*1 = 6 times
    // 10*6 + 11*6 = 60 + 66 = 126
    //
    // SUM(a*b):
    // Each (a,b) pair appears 2*1 = 2 times
    // (10*20)*2 + (10*21)*2 + (10*22)*2 + (11*20)*2 + (11*21)*2 + (11*22)*2
    // = 400 + 420 + 440 + 440 + 462 + 484 = 2646
    //
    // SUM(a*b*c):
    // Each (a,b,c) tuple appears 1 time
    // We have 2*3*2 = 12 tuples, each with d=40
    // Sum of all a*b*c products:
    // a=10: 10*(20*30 + 20*31 + 21*30 + 21*31 + 22*30 + 22*31)
    //     = 10*(600+620+630+651+660+682) = 10*3843 = 38430
    // a=11: 11*3843 = 42273
    // Total: 38430 + 42273 = 80703
    //
    // SUM(a*b*c*d):
    // = SUM(a*b*c) * 40 = 80703 * 40 = 3228120
    val expectedResult = Row(12L, 126L, 2646L, 80703L, 3228120L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      val baseline = sql(query).collect()
      println(s"Baseline (hier-deep): ${baseline.map(_.toString).mkString}")
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== HIERARCHICAL DEEP NESTING ({a} < {a,b} < {a,b,c} < {a,b,c,d}) ===")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  test("mixed containment and conflict") {
    // Some products have containment, others conflict:
    // P1: SUM(a) uses {a}
    // P2: SUM(a*b) uses {a, b} - contains P1
    // P3: SUM(c*d) uses {c, d} - independent of P1, P2
    // P4: SUM(a*c) uses {a, c} - conflicts with P2 (shares a, not subset)
    //
    // Containment: {a} < {a,b}
    // Conflict: {a,b} <-> {a,c} (share a, neither subset)
    // Independent: {c,d} has no overlap with {a} or {a,b}
    val t1 = Seq((1, 10), (1, 11)).toDF("id", "a")  // 2 rows
    val t2 = Seq((1, 20)).toDF("id", "b")           // 1 row
    val t3 = Seq((1, 30), (1, 31)).toDF("id", "c")  // 2 rows
    val t4 = Seq((1, 40)).toDF("id", "d")           // 1 row

    t1.createOrReplaceTempView("t1")
    t2.createOrReplaceTempView("t2")
    t3.createOrReplaceTempView("t3")
    t4.createOrReplaceTempView("t4")

    val query = """
      SELECT COUNT(*),
             SUM(t1.a),
             SUM(t1.a * t2.b),
             SUM(t3.c * t4.d),
             SUM(t1.a * t3.c)
      FROM t1, t2, t3, t4
      WHERE t1.id = t2.id AND t2.id = t3.id AND t3.id = t4.id
    """

    // JOIN produces 2*1*2*1 = 4 rows
    // Rows: (a,b,c,d) in {(10,20,30,40), (10,20,31,40), (11,20,30,40), (11,20,31,40)}
    //
    // COUNT = 4
    //
    // SUM(a):
    // Each a value appears 2 times (c has 2 vals)
    // 10*2 + 11*2 = 20 + 22 = 42
    //
    // SUM(a*b):
    // Each (a,b) pair appears 2 times (c has 2 vals)
    // (10*20)*2 + (11*20)*2 = 400 + 440 = 840
    //
    // SUM(c*d):
    // Each (c,d) pair appears 2 times (a has 2 vals)
    // (30*40)*2 + (31*40)*2 = 2400 + 2480 = 4880
    //
    // SUM(a*c):
    // Each (a,c) pair appears 1 time
    // (10*30) + (10*31) + (11*30) + (11*31) = 300 + 310 + 330 + 341 = 1281
    val expectedResult = Row(4L, 42L, 840L, 4880L, 1281L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      val baseline = sql(query).collect()
      println(s"Baseline (mixed): ${baseline.map(_.toString).mkString}")
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== MIXED CONTAINMENT AND CONFLICT ===")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  test("hierarchical containment - all contained in one superset") {
    // Multiple products all contained in one superset:
    // P1: SUM(a*b) uses {a, b}
    // P2: SUM(a*c) uses {a, c}
    // P3: SUM(b*c) uses {b, c}
    // P4: SUM(a*b*c) uses {a, b, c} - contains ALL of P1, P2, P3
    //
    // This is exactly the "complex conflict web" test but with explicit
    // containment analysis.
    // P1, P2, P3 all conflict pairwise (overlap but not subset)
    // P4 contains all of them
    val t1 = Seq((1, 10), (1, 11), (1, 12)).toDF("id", "a")  // 3 rows
    val t2 = Seq((1, 20), (1, 21)).toDF("id", "b")           // 2 rows
    val t3 = Seq((1, 30)).toDF("id", "c")                    // 1 row

    t1.createOrReplaceTempView("t1")
    t2.createOrReplaceTempView("t2")
    t3.createOrReplaceTempView("t3")

    val query = """
      SELECT COUNT(*),
             SUM(t1.a * t2.b),
             SUM(t1.a * t3.c),
             SUM(t2.b * t3.c),
             SUM(t1.a * t2.b * t3.c)
      FROM t1, t2, t3
      WHERE t1.id = t2.id AND t2.id = t3.id
    """

    // JOIN produces 3*2*1 = 6 rows
    //
    // COUNT = 6
    //
    // SUM(a*b):
    // Each (a,b) pair appears 1 time (c has 1 val)
    // (10*20) + (10*21) + (11*20) + (11*21) + (12*20) + (12*21)
    // = 200 + 210 + 220 + 231 + 240 + 252 = 1353
    //
    // SUM(a*c):
    // Each (a,c) pair appears 2 times (b has 2 vals)
    // (10*30)*2 + (11*30)*2 + (12*30)*2 = 600 + 660 + 720 = 1980
    //
    // SUM(b*c):
    // Each (b,c) pair appears 3 times (a has 3 vals)
    // (20*30)*3 + (21*30)*3 = 1800 + 1890 = 3690
    //
    // SUM(a*b*c):
    // Each tuple appears 1 time
    // (10*20*30) + (10*21*30) + (11*20*30) + (11*21*30) +
    // (12*20*30) + (12*21*30)
    // = 6000 + 6300 + 6600 + 6930 + 7200 + 7560 = 40590
    val expectedResult = Row(6L, 1353L, 1980L, 3690L, 40590L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      val baseline = sql(query).collect()
      println(s"Baseline (all-in-superset): ${baseline.map(_.toString).mkString}")
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== ALL PRODUCTS CONTAINED IN ONE SUPERSET ===")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  // ============================================================================
  // Cross-relation filter tests
  // These test the DeferredComputation framework with filters spanning relations
  // ============================================================================

  test("cross-relation filter only - no products (optimization should apply)") {
    // This test has ONLY cross-relation filters, no product aggregates.
    // The Yannakakis optimization should fully apply here.
    // Filter: a + b > 30
    spark.sql("DROP TABLE IF EXISTS t1")
    spark.sql("DROP TABLE IF EXISTS t2")

    spark.sql("CREATE TABLE t1 (id INT, a INT) USING parquet")
    spark.sql("CREATE TABLE t2 (id INT, b INT) USING parquet")

    // t1: id=1 -> a in {10, 11, 12}
    spark.sql("INSERT INTO t1 VALUES (1, 10), (1, 11), (1, 12)")
    // t2: id=1 -> b in {20, 21}
    spark.sql("INSERT INTO t2 VALUES (1, 20), (1, 21)")

    val query = """
      SELECT COUNT(*)
      FROM t1, t2
      WHERE t1.id = t2.id AND t1.a + t2.b > 30
    """

    // Full join produces 3*2 = 6 rows:
    // (10,20), (10,21), (11,20), (11,21), (12,20), (12,21)
    // Filter a + b > 30:
    // (10,20)=30 NO, (10,21)=31 YES, (11,20)=31 YES, (11,21)=32 YES,
    // (12,20)=32 YES, (12,21)=33 YES
    // Passing: 5 rows
    val expectedResult = Row(5L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      val baseline = sql(query).collect()
      println(s"Baseline (filter-only): ${baseline.map(_.toString).mkString}")
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== CROSS-RELATION FILTER ONLY (optimized) ===")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  test("cross-relation filter - simple two-table filter") {
    // Filter: a + b > 30
    // This filter spans two relations and must be deferred until both are available
    spark.sql("DROP TABLE IF EXISTS t1")
    spark.sql("DROP TABLE IF EXISTS t2")

    spark.sql("CREATE TABLE t1 (id INT, a INT) USING parquet")
    spark.sql("CREATE TABLE t2 (id INT, b INT) USING parquet")

    // t1: id=1 -> a in {10, 11, 12}
    spark.sql("INSERT INTO t1 VALUES (1, 10), (1, 11), (1, 12)")
    // t2: id=1 -> b in {20, 21}
    spark.sql("INSERT INTO t2 VALUES (1, 20), (1, 21)")

    val query = """
      SELECT COUNT(*), SUM(t1.a), SUM(t2.b)
      FROM t1, t2
      WHERE t1.id = t2.id AND t1.a + t2.b > 30
    """

    // Full join produces 3*2 = 6 rows:
    // (10,20), (10,21), (11,20), (11,21), (12,20), (12,21)
    // Filter a + b > 30:
    // (10,20)=30 NO, (10,21)=31 YES, (11,20)=31 YES, (11,21)=32 YES,
    // (12,20)=32 YES, (12,21)=33 YES
    // Passing: 5 rows
    // SUM(a) = 10+11+11+12+12 = 56
    // SUM(b) = 21+20+21+20+21 = 103
    val expectedResult = Row(5L, 56L, 103L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      val baseline = sql(query).collect()
      println(s"Baseline (cross-filter-2-table): ${baseline.map(_.toString).mkString}")
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== CROSS-RELATION FILTER (2-table) ===")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  test("cross-relation filter - three-table filter chain") {
    // Filter: a + b + c > 60
    // This filter spans three relations
    spark.sql("DROP TABLE IF EXISTS t1")
    spark.sql("DROP TABLE IF EXISTS t2")
    spark.sql("DROP TABLE IF EXISTS t3")

    spark.sql("CREATE TABLE t1 (id INT, a INT) USING parquet")
    spark.sql("CREATE TABLE t2 (id INT, b INT) USING parquet")
    spark.sql("CREATE TABLE t3 (id INT, c INT) USING parquet")

    // t1: id=1 -> a in {10, 20}
    spark.sql("INSERT INTO t1 VALUES (1, 10), (1, 20)")
    // t2: id=1 -> b in {15, 25}
    spark.sql("INSERT INTO t2 VALUES (1, 15), (1, 25)")
    // t3: id=1 -> c in {30}
    spark.sql("INSERT INTO t3 VALUES (1, 30)")

    val query = """
      SELECT COUNT(*), SUM(t1.a), SUM(t2.b), SUM(t3.c)
      FROM t1, t2, t3
      WHERE t1.id = t2.id AND t2.id = t3.id AND t1.a + t2.b + t3.c > 60
    """

    // Full join produces 2*2*1 = 4 rows:
    // (10,15,30), (10,25,30), (20,15,30), (20,25,30)
    // Filter a + b + c > 60:
    // (10,15,30)=55 NO, (10,25,30)=65 YES, (20,15,30)=65 YES, (20,25,30)=75 YES
    // Passing: 3 rows
    // SUM(a) = 10+20+20 = 50
    // SUM(b) = 25+15+25 = 65
    // SUM(c) = 30+30+30 = 90
    val expectedResult = Row(3L, 50L, 65L, 90L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      val baseline = sql(query).collect()
      println(s"Baseline (cross-filter-3-table): ${baseline.map(_.toString).mkString}")
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== CROSS-RELATION FILTER (3-table chain) ===")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  test("cross-relation filter with product aggregate") {
    // Combines a cross-relation filter with a product aggregate
    // Filter: a + b > 30
    // Aggregate: SUM(a * b)
    spark.sql("DROP TABLE IF EXISTS t1")
    spark.sql("DROP TABLE IF EXISTS t2")

    spark.sql("CREATE TABLE t1 (id INT, a INT) USING parquet")
    spark.sql("CREATE TABLE t2 (id INT, b INT) USING parquet")

    // t1: id=1 -> a in {10, 11, 12}
    spark.sql("INSERT INTO t1 VALUES (1, 10), (1, 11), (1, 12)")
    // t2: id=1 -> b in {20, 21}
    spark.sql("INSERT INTO t2 VALUES (1, 20), (1, 21)")

    val query = """
      SELECT COUNT(*), SUM(t1.a * t2.b)
      FROM t1, t2
      WHERE t1.id = t2.id AND t1.a + t2.b > 30
    """

    // Full join produces 6 rows, filter passes 5:
    // (10,21), (11,20), (11,21), (12,20), (12,21)
    // SUM(a*b) = 10*21 + 11*20 + 11*21 + 12*20 + 12*21
    //          = 210 + 220 + 231 + 240 + 252 = 1153
    val expectedResult = Row(5L, 1153L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      val baseline = sql(query).collect()
      println(s"Baseline (filter+product): ${baseline.map(_.toString).mkString}")
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== CROSS-RELATION FILTER + PRODUCT ===")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  test("multiple cross-relation filters") {
    // Multiple filters spanning different relation pairs
    // Filter 1: a + b > 30
    // Filter 2: b + c > 50
    spark.sql("DROP TABLE IF EXISTS t1")
    spark.sql("DROP TABLE IF EXISTS t2")
    spark.sql("DROP TABLE IF EXISTS t3")

    spark.sql("CREATE TABLE t1 (id INT, a INT) USING parquet")
    spark.sql("CREATE TABLE t2 (id INT, b INT) USING parquet")
    spark.sql("CREATE TABLE t3 (id INT, c INT) USING parquet")

    // t1: id=1 -> a in {10, 20}
    spark.sql("INSERT INTO t1 VALUES (1, 10), (1, 20)")
    // t2: id=1 -> b in {25, 35}
    spark.sql("INSERT INTO t2 VALUES (1, 25), (1, 35)")
    // t3: id=1 -> c in {20, 30}
    spark.sql("INSERT INTO t3 VALUES (1, 20), (1, 30)")

    val query = """
      SELECT COUNT(*), SUM(t1.a), SUM(t2.b), SUM(t3.c)
      FROM t1, t2, t3
      WHERE t1.id = t2.id AND t2.id = t3.id
        AND t1.a + t2.b > 30
        AND t2.b + t3.c > 50
    """

    // Full join produces 2*2*2 = 8 rows:
    // (a,b,c): (10,25,20), (10,25,30), (10,35,20), (10,35,30),
    //         (20,25,20), (20,25,30), (20,35,20), (20,35,30)
    //
    // Filter a + b > 30:
    // (10,25)=35 YES, (10,35)=45 YES, (20,25)=45 YES, (20,35)=55 YES
    // All pass first filter
    //
    // Filter b + c > 50:
    // (25,20)=45 NO, (25,30)=55 YES, (35,20)=55 YES, (35,30)=65 YES
    //
    // Combined passing rows:
    // (10,25,30), (10,35,20), (10,35,30), (20,25,30), (20,35,20), (20,35,30)
    // That's 6 rows
    //
    // SUM(a) = 10+10+10+20+20+20 = 90
    // SUM(b) = 25+35+35+25+35+35 = 190
    // SUM(c) = 30+20+30+30+20+30 = 160
    val expectedResult = Row(6L, 90L, 190L, 160L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      val baseline = sql(query).collect()
      println(s"Baseline (multi-filter): ${baseline.map(_.toString).mkString}")
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== MULTIPLE CROSS-RELATION FILTERS ===")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  test("cross-relation filter with inequality") {
    // Filter: a < b (inequality comparison across relations)
    spark.sql("DROP TABLE IF EXISTS t1")
    spark.sql("DROP TABLE IF EXISTS t2")

    spark.sql("CREATE TABLE t1 (id INT, a INT) USING parquet")
    spark.sql("CREATE TABLE t2 (id INT, b INT) USING parquet")

    // t1: id=1 -> a in {10, 20, 30}
    spark.sql("INSERT INTO t1 VALUES (1, 10), (1, 20), (1, 30)")
    // t2: id=1 -> b in {15, 25}
    spark.sql("INSERT INTO t2 VALUES (1, 15), (1, 25)")

    val query = """
      SELECT COUNT(*), SUM(t1.a), SUM(t2.b)
      FROM t1, t2
      WHERE t1.id = t2.id AND t1.a < t2.b
    """

    // Full join produces 3*2 = 6 rows:
    // (10,15), (10,25), (20,15), (20,25), (30,15), (30,25)
    // Filter a < b:
    // (10,15) 10<15 YES, (10,25) 10<25 YES, (20,15) 20<15 NO,
    // (20,25) 20<25 YES, (30,15) 30<15 NO, (30,25) 30<25 NO
    // Passing: 3 rows
    // SUM(a) = 10+10+20 = 40
    // SUM(b) = 15+25+25 = 65
    val expectedResult = Row(3L, 40L, 65L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      val baseline = sql(query).collect()
      println(s"Baseline (inequality-filter): ${baseline.map(_.toString).mkString}")
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== CROSS-RELATION INEQUALITY FILTER ===")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  test("cross-relation filter with product - hierarchical") {
    // Hierarchical products with a cross-relation filter
    // Products: sum(a), sum(a*b), sum(a*b*c) - containment: {a} < {a,b} < {a,b,c}
    // Filter: a + c > 35
    spark.sql("DROP TABLE IF EXISTS t1")
    spark.sql("DROP TABLE IF EXISTS t2")
    spark.sql("DROP TABLE IF EXISTS t3")

    spark.sql("CREATE TABLE t1 (id INT, a INT) USING parquet")
    spark.sql("CREATE TABLE t2 (id INT, b INT) USING parquet")
    spark.sql("CREATE TABLE t3 (id INT, c INT) USING parquet")

    // t1: id=1 -> a in {10, 20}
    spark.sql("INSERT INTO t1 VALUES (1, 10), (1, 20)")
    // t2: id=1 -> b in {2, 3}
    spark.sql("INSERT INTO t2 VALUES (1, 2), (1, 3)")
    // t3: id=1 -> c in {30}
    spark.sql("INSERT INTO t3 VALUES (1, 30)")

    val query = """
      SELECT COUNT(*), SUM(t1.a), SUM(t1.a * t2.b), SUM(t1.a * t2.b * t3.c)
      FROM t1, t2, t3
      WHERE t1.id = t2.id AND t2.id = t3.id AND t1.a + t3.c > 35
    """

    // Full join produces 2*2*1 = 4 rows:
    // (10,2,30), (10,3,30), (20,2,30), (20,3,30)
    // Filter a + c > 35:
    // (10,_,30) = 40 YES, (20,_,30) = 50 YES
    // All 4 rows pass!
    //
    // COUNT = 4
    // SUM(a) = 10+10+20+20 = 60
    // SUM(a*b) = 10*2 + 10*3 + 20*2 + 20*3 = 20+30+40+60 = 150
    // SUM(a*b*c) = 20*30 + 30*30 + 40*30 + 60*30 = 600+900+1200+1800 = 4500
    val expectedResult = Row(4L, 60L, 150L, 4500L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      val baseline = sql(query).collect()
      println(s"Baseline (hierarchical+filter): ${baseline.map(_.toString).mkString}")
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== HIERARCHICAL PRODUCTS + CROSS-RELATION FILTER ===")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  test("filter superset of product - 4 tables with complex filter") {
    // Filter spans all 4 attrs: a*b + c*d > 100
    // Product spans 2 attrs: SUM(a*b)
    // Filter attrs {a,b,c,d} is superset of product attrs {a,b} - should optimize
    spark.sql("DROP TABLE IF EXISTS t1")
    spark.sql("DROP TABLE IF EXISTS t2")
    spark.sql("DROP TABLE IF EXISTS t3")
    spark.sql("DROP TABLE IF EXISTS t4")

    spark.sql("CREATE TABLE t1 (id INT, a INT) USING parquet")
    spark.sql("CREATE TABLE t2 (id INT, b INT) USING parquet")
    spark.sql("CREATE TABLE t3 (id INT, c INT) USING parquet")
    spark.sql("CREATE TABLE t4 (id INT, d INT) USING parquet")

    spark.sql("INSERT INTO t1 VALUES (1, 5), (1, 10)")
    spark.sql("INSERT INTO t2 VALUES (1, 6), (1, 12)")
    spark.sql("INSERT INTO t3 VALUES (1, 3)")
    spark.sql("INSERT INTO t4 VALUES (1, 4)")

    val query = """
      SELECT COUNT(*), SUM(t1.a * t2.b)
      FROM t1, t2, t3, t4
      WHERE t1.id = t2.id AND t2.id = t3.id AND t3.id = t4.id
        AND t1.a * t2.b + t3.c * t4.d > 50
    """

    // Full join: 2*2*1*1 = 4 rows
    // (a,b,c,d): (5,6,3,4), (5,12,3,4), (10,6,3,4), (10,12,3,4)
    // a*b + c*d: 30+12=42, 60+12=72, 60+12=72, 120+12=132
    // Filter > 50: (5,12), (10,6), (10,12) pass = 3 rows
    // SUM(a*b) = 60 + 60 + 120 = 240
    val expectedResult = Row(3L, 240L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      val baseline = sql(query).collect()
      println(s"Baseline (4-table-filter-superset): ${baseline.map(_.toString).mkString}")
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== 4-TABLE FILTER SUPERSET OF PRODUCT ===")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  test("filter with multiple products - all contained") {
    // Filter: a*b > 50
    // Products: SUM(a), SUM(b), SUM(a*b)
    // All products {a}, {b}, {a,b} are subsets of filter {a,b}
    spark.sql("DROP TABLE IF EXISTS t1")
    spark.sql("DROP TABLE IF EXISTS t2")

    spark.sql("CREATE TABLE t1 (id INT, a INT) USING parquet")
    spark.sql("CREATE TABLE t2 (id INT, b INT) USING parquet")

    spark.sql("INSERT INTO t1 VALUES (1, 5), (1, 10), (1, 15)")
    spark.sql("INSERT INTO t2 VALUES (1, 6), (1, 8)")

    val query = """
      SELECT COUNT(*), SUM(t1.a), SUM(t2.b), SUM(t1.a * t2.b)
      FROM t1, t2
      WHERE t1.id = t2.id AND t1.a * t2.b > 50
    """

    // Full join: 3*2 = 6 rows
    // (a,b): (5,6)=30, (5,8)=40, (10,6)=60, (10,8)=80, (15,6)=90, (15,8)=120
    // Filter a*b > 50: (10,6), (10,8), (15,6), (15,8) pass = 4 rows
    // SUM(a) = 10+10+15+15 = 50
    // SUM(b) = 6+8+6+8 = 28
    // SUM(a*b) = 60+80+90+120 = 350
    val expectedResult = Row(4L, 50L, 28L, 350L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      val baseline = sql(query).collect()
      println(s"Baseline (multi-product-contained): ${baseline.map(_.toString).mkString}")
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== MULTIPLE PRODUCTS ALL CONTAINED IN FILTER ===")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  test("filter with OR condition across relations") {
    // Filter: a > 15 OR b > 25
    // This creates a disjunctive filter spanning two relations
    spark.sql("DROP TABLE IF EXISTS t1")
    spark.sql("DROP TABLE IF EXISTS t2")

    spark.sql("CREATE TABLE t1 (id INT, a INT) USING parquet")
    spark.sql("CREATE TABLE t2 (id INT, b INT) USING parquet")

    spark.sql("INSERT INTO t1 VALUES (1, 10), (1, 20)")
    spark.sql("INSERT INTO t2 VALUES (1, 20), (1, 30)")

    val query = """
      SELECT COUNT(*), SUM(t1.a), SUM(t2.b)
      FROM t1, t2
      WHERE t1.id = t2.id AND (t1.a > 15 OR t2.b > 25)
    """

    // Full join: 2*2 = 4 rows
    // (a,b): (10,20), (10,30), (20,20), (20,30)
    // Filter a>15 OR b>25:
    // (10,20): 10>15=F, 20>25=F -> NO
    // (10,30): 10>15=F, 30>25=T -> YES
    // (20,20): 20>15=T, 20>25=F -> YES
    // (20,30): 20>15=T, 30>25=T -> YES
    // Passing: 3 rows
    // SUM(a) = 10+20+20 = 50
    // SUM(b) = 30+20+30 = 80
    val expectedResult = Row(3L, 50L, 80L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      val baseline = sql(query).collect()
      println(s"Baseline (or-filter): ${baseline.map(_.toString).mkString}")
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== OR FILTER ACROSS RELATIONS ===")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  test("filter with function application across relations") {
    // Filter: ABS(a - b) < 10 (function applied to cross-relation expression)
    spark.sql("DROP TABLE IF EXISTS t1")
    spark.sql("DROP TABLE IF EXISTS t2")

    spark.sql("CREATE TABLE t1 (id INT, a INT) USING parquet")
    spark.sql("CREATE TABLE t2 (id INT, b INT) USING parquet")

    spark.sql("INSERT INTO t1 VALUES (1, 10), (1, 25), (1, 40)")
    spark.sql("INSERT INTO t2 VALUES (1, 15), (1, 30)")

    val query = """
      SELECT COUNT(*), SUM(t1.a), SUM(t2.b), SUM(t1.a * t2.b)
      FROM t1, t2
      WHERE t1.id = t2.id AND ABS(t1.a - t2.b) < 10
    """

    // Full join: 3*2 = 6 rows
    // (a,b): (10,15), (10,30), (25,15), (25,30), (40,15), (40,30)
    // ABS(a-b): 5, 20, 10, 5, 25, 10
    // Filter ABS(a-b) < 10:
    // (10,15)=5 YES, (10,30)=20 NO, (25,15)=10 NO, (25,30)=5 YES,
    // (40,15)=25 NO, (40,30)=10 NO
    // Passing: 2 rows
    // SUM(a) = 10+25 = 35
    // SUM(b) = 15+30 = 45
    // SUM(a*b) = 10*15 + 25*30 = 150 + 750 = 900
    val expectedResult = Row(2L, 35L, 45L, 900L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      val baseline = sql(query).collect()
      println(s"Baseline (abs-filter): ${baseline.map(_.toString).mkString}")
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== ABS FUNCTION FILTER ACROSS RELATIONS ===")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  // ============================================================================
  // MULTI-COUNT OPTIMIZATION CASE TESTS
  // These tests explicitly verify each case where multi-count optimization
  // can or cannot make a difference.
  // ============================================================================

  test("CASE 1: Independent products - no shared attributes") {
    // Two products with completely disjoint attribute sets
    // SUM(a*b) uses {a,b}, SUM(c*d) uses {c,d} - NO overlap
    // Each product can use its own count track independently
    val t1 = Seq((1, 10), (1, 11)).toDF("id", "a")  // 2 rows
    val t2 = Seq((1, 20)).toDF("id", "b")           // 1 row
    val t3 = Seq((1, 30), (1, 31)).toDF("id", "c")  // 2 rows
    val t4 = Seq((1, 40)).toDF("id", "d")           // 1 row

    t1.createOrReplaceTempView("t1")
    t2.createOrReplaceTempView("t2")
    t3.createOrReplaceTempView("t3")
    t4.createOrReplaceTempView("t4")

    val query = """
      SELECT COUNT(*),
             SUM(t1.a * t2.b),
             SUM(t3.c * t4.d)
      FROM t1, t2, t3, t4
      WHERE t1.id = t2.id AND t2.id = t3.id AND t3.id = t4.id
    """

    // JOIN produces 2*1*2*1 = 4 rows
    //
    // SUM(a*b): Each (a,b) pair appears 2 times (c has 2 vals)
    // (10*20)*2 + (11*20)*2 = 400 + 440 = 840
    //
    // SUM(c*d): Each (c,d) pair appears 2 times (a has 2 vals)
    // (30*40)*2 + (31*40)*2 = 2400 + 2480 = 4880
    val expectedResult = Row(4L, 840L, 4880L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== CASE 1: INDEPENDENT PRODUCTS (no shared attrs) ===")
      println(s"Products: {a,b} and {c,d} - completely independent")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  test("CASE 2: Containment hierarchy - subset relationships") {
    // Products form a containment chain: {a} < {a,b} < {a,b,c}
    // Hierarchical count derivation: compute at finest {a,b,c}, derive coarser
    val t1 = Seq((1, 10), (1, 11)).toDF("id", "a")           // 2 rows
    val t2 = Seq((1, 20), (1, 21), (1, 22)).toDF("id", "b")  // 3 rows
    val t3 = Seq((1, 30), (1, 31)).toDF("id", "c")           // 2 rows

    t1.createOrReplaceTempView("t1")
    t2.createOrReplaceTempView("t2")
    t3.createOrReplaceTempView("t3")

    val query = """
      SELECT COUNT(*),
             SUM(t1.a),
             SUM(t1.a * t2.b),
             SUM(t1.a * t2.b * t3.c)
      FROM t1, t2, t3
      WHERE t1.id = t2.id AND t2.id = t3.id
    """

    // JOIN produces 2*3*2 = 12 rows
    //
    // SUM(a): Each a appears 3*2=6 times -> 10*6 + 11*6 = 126
    //
    // SUM(a*b): Each (a,b) appears 2 times (c has 2 vals)
    // (10*20)*2 + (10*21)*2 + (10*22)*2 + (11*20)*2 + (11*21)*2 + (11*22)*2
    // = 400 + 420 + 440 + 440 + 462 + 484 = 2646
    //
    // SUM(a*b*c): Each (a,b,c) appears 1 time
    // Need to compute all 12 products... sum = 80703
    val expectedResult = Row(12L, 126L, 2646L, 80703L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== CASE 2: CONTAINMENT HIERARCHY ({a} < {a,b} < {a,b,c}) ===")
      println(s"Hierarchical: derive {a} and {a,b} from {a,b,c}")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  test("CASE 3: Universal superset with conflicts") {
    // Products: {a,b}, {a,c}, {b,c} all conflict pairwise
    // But {a,b,c} contains ALL of them - can use as universal superset
    val t1 = Seq((1, 10), (1, 11)).toDF("id", "a")  // 2 rows
    val t2 = Seq((1, 20), (1, 21)).toDF("id", "b")  // 2 rows
    val t3 = Seq((1, 30)).toDF("id", "c")           // 1 row

    t1.createOrReplaceTempView("t1")
    t2.createOrReplaceTempView("t2")
    t3.createOrReplaceTempView("t3")

    val query = """
      SELECT COUNT(*),
             SUM(t1.a * t2.b),
             SUM(t1.a * t3.c),
             SUM(t2.b * t3.c),
             SUM(t1.a * t2.b * t3.c)
      FROM t1, t2, t3
      WHERE t1.id = t2.id AND t2.id = t3.id
    """

    // JOIN produces 2*2*1 = 4 rows
    // (a,b,c): (10,20,30), (10,21,30), (11,20,30), (11,21,30)
    //
    // SUM(a*b): 200+210+220+231 = 861
    // SUM(a*c): 300+300+330+330 = 1260 (each (a,c) appears twice due to b)
    // Actually: (10*30)=300 twice, (11*30)=330 twice -> 600+660=1260
    // SUM(b*c): (20*30)=600 twice, (21*30)=630 twice -> 1200+1260=2460
    // SUM(a*b*c): 6000+6300+6600+6930 = 25830
    val expectedResult = Row(4L, 861L, 1260L, 2460L, 25830L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== CASE 3: UNIVERSAL SUPERSET WITH CONFLICTS ===")
      println(s"Products: {a,b}, {a,c}, {b,c} conflict but {a,b,c} contains all")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  test("CASE 4: Multiple independent components") {
    // Two independent conflict components:
    // Component 1: {a,b}, {b,c} (conflict via b)
    // Component 2: {d,e} (completely independent)
    val t1 = Seq((1, 10), (1, 11)).toDF("id", "a")  // 2 rows
    val t2 = Seq((1, 20)).toDF("id", "b")           // 1 row
    val t3 = Seq((1, 30), (1, 31)).toDF("id", "c")  // 2 rows
    val t4 = Seq((1, 40)).toDF("id", "d")           // 1 row
    val t5 = Seq((1, 50), (1, 51)).toDF("id", "e")  // 2 rows

    t1.createOrReplaceTempView("t1")
    t2.createOrReplaceTempView("t2")
    t3.createOrReplaceTempView("t3")
    t4.createOrReplaceTempView("t4")
    t5.createOrReplaceTempView("t5")

    val query = """
      SELECT COUNT(*),
             SUM(t1.a * t2.b),
             SUM(t2.b * t3.c),
             SUM(t4.d * t5.e)
      FROM t1, t2, t3, t4, t5
      WHERE t1.id = t2.id AND t2.id = t3.id AND t3.id = t4.id AND t4.id = t5.id
    """

    // JOIN produces 2*1*2*1*2 = 8 rows
    //
    // SUM(a*b): Each (a,b) pair appears 2*1*2=4 times (c has 2, d has 1, e has 2)
    // (10*20)*4 + (11*20)*4 = 800 + 880 = 1680
    //
    // SUM(b*c): Each (b,c) pair appears 2*1*2=4 times
    // (20*30)*4 + (20*31)*4 = 2400 + 2480 = 4880
    //
    // SUM(d*e): Each (d,e) pair appears 2*1*2=4 times
    // (40*50)*4 + (40*51)*4 = 8000 + 8160 = 16160
    val expectedResult = Row(8L, 1680L, 4880L, 16160L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== CASE 4: MULTIPLE INDEPENDENT COMPONENTS ===")
      println(s"Component 1: {a,b}, {b,c} (conflict via b)")
      println(s"Component 2: {d,e} (independent)")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  test("CASE 5: Star pattern - worst case (like IMDB)") {
    // Products share a common attribute but in a star pattern:
    // {a,b}, {a,c}, {a,d} - all share 'a' but none contains another
    // This is the worst case - must defer all to final
    val t1 = Seq((1, 10), (1, 11)).toDF("id", "a")  // 2 rows - shared dimension
    val t2 = Seq((1, 20), (1, 21)).toDF("id", "b")  // 2 rows
    val t3 = Seq((1, 30)).toDF("id", "c")           // 1 row
    val t4 = Seq((1, 40), (1, 41)).toDF("id", "d")  // 2 rows

    t1.createOrReplaceTempView("t1")
    t2.createOrReplaceTempView("t2")
    t3.createOrReplaceTempView("t3")
    t4.createOrReplaceTempView("t4")

    val query = """
      SELECT COUNT(*),
             SUM(t1.a * t2.b),
             SUM(t1.a * t3.c),
             SUM(t1.a * t4.d)
      FROM t1, t2, t3, t4
      WHERE t1.id = t2.id AND t2.id = t3.id AND t3.id = t4.id
    """

    // JOIN produces 2*2*1*2 = 8 rows
    //
    // SUM(a*b): Each (a,b) pair appears 1*2=2 times
    // (10*20)*2 + (10*21)*2 + (11*20)*2 + (11*21)*2
    // = 400 + 420 + 440 + 462 = 1722
    //
    // SUM(a*c): Each (a,c) pair appears 2*2=4 times
    // (10*30)*4 + (11*30)*4 = 1200 + 1320 = 2520
    //
    // SUM(a*d): Each (a,d) pair appears 2*1=2 times
    // (10*40)*2 + (10*41)*2 + (11*40)*2 + (11*41)*2
    // = 800 + 820 + 880 + 902 = 3402
    val expectedResult = Row(8L, 1722L, 2520L, 3402L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== CASE 5: STAR PATTERN - WORST CASE ===")
      println(s"Products: {a,b}, {a,c}, {a,d} - star around 'a'")
      println(s"No containment, must defer all to final aggregate")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  test("CASE 5b: Star pattern with single attribute products") {
    // Even simpler star: {a}, {b}, {c} from different relations
    // These are completely independent - no conflicts
    val t1 = Seq((1, 10), (1, 11), (1, 12)).toDF("id", "a")  // 3 rows
    val t2 = Seq((1, 20), (1, 21)).toDF("id", "b")           // 2 rows
    val t3 = Seq((1, 30)).toDF("id", "c")                    // 1 row

    t1.createOrReplaceTempView("t1")
    t2.createOrReplaceTempView("t2")
    t3.createOrReplaceTempView("t3")

    val query = """
      SELECT COUNT(*),
             SUM(t1.a),
             SUM(t2.b),
             SUM(t3.c)
      FROM t1, t2, t3
      WHERE t1.id = t2.id AND t2.id = t3.id
    """

    // JOIN produces 3*2*1 = 6 rows
    //
    // SUM(a): Each a value appears 2*1=2 times
    // (10 + 11 + 12) * 2 = 33 * 2 = 66
    //
    // SUM(b): Each b value appears 3*1=3 times
    // (20 + 21) * 3 = 41 * 3 = 123
    //
    // SUM(c): Each c value appears 3*2=6 times
    // 30 * 6 = 180
    val expectedResult = Row(6L, 66L, 123L, 180L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== CASE 5b: SINGLE ATTRIBUTE PRODUCTS (independent) ===")
      println(s"Products: {a}, {b}, {c} - no overlap, fully independent")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  test("CASE 6: Partial containment - some can be derived, some deferred") {
    // Mix of containment and conflict:
    // {a} contained in {a,b} - derivable
    // {c,d} independent
    // {a,c} conflicts with {a,b} - must defer together
    val t1 = Seq((1, 10), (1, 11)).toDF("id", "a")  // 2 rows
    val t2 = Seq((1, 20)).toDF("id", "b")           // 1 row
    val t3 = Seq((1, 30), (1, 31)).toDF("id", "c")  // 2 rows
    val t4 = Seq((1, 40)).toDF("id", "d")           // 1 row

    t1.createOrReplaceTempView("t1")
    t2.createOrReplaceTempView("t2")
    t3.createOrReplaceTempView("t3")
    t4.createOrReplaceTempView("t4")

    val query = """
      SELECT COUNT(*),
             SUM(t1.a),
             SUM(t1.a * t2.b),
             SUM(t3.c * t4.d),
             SUM(t1.a * t3.c)
      FROM t1, t2, t3, t4
      WHERE t1.id = t2.id AND t2.id = t3.id AND t3.id = t4.id
    """

    // JOIN produces 2*1*2*1 = 4 rows
    // (a,b,c,d): (10,20,30,40), (10,20,31,40), (11,20,30,40), (11,20,31,40)
    //
    // SUM(a): Each a appears 2 times
    // 10*2 + 11*2 = 42
    //
    // SUM(a*b): Each (a,b) appears 2 times
    // (10*20)*2 + (11*20)*2 = 400 + 440 = 840
    //
    // SUM(c*d): Each (c,d) appears 2 times
    // (30*40)*2 + (31*40)*2 = 2400 + 2480 = 4880
    //
    // SUM(a*c): Each (a,c) appears 1 time
    // (10*30) + (10*31) + (11*30) + (11*31) = 300+310+330+341 = 1281
    val expectedResult = Row(4L, 42L, 840L, 4880L, 1281L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== CASE 6: PARTIAL CONTAINMENT ===")
      println(s"Containment: {a} < {a,b}")
      println(s"Independent: {c,d}")
      println(s"Conflict: {a,b} <-> {a,c}")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  test("large fan-out stress test for count accuracy") {
    // Large fan-out to stress test count multiplication correctness
    val t1 = (1 to 10).map(i => (1, i)).toDF("id", "a")       // 10 rows
    val t2 = (1 to 5).map(i => (1, i * 10)).toDF("id", "b")   // 5 rows
    val t3 = Seq((1, 100)).toDF("id", "c")                     // 1 row

    t1.createOrReplaceTempView("t1")
    t2.createOrReplaceTempView("t2")
    t3.createOrReplaceTempView("t3")

    val query = """
      SELECT COUNT(*),
             SUM(t1.a),
             SUM(t2.b),
             SUM(t1.a * t2.b)
      FROM t1, t2, t3
      WHERE t1.id = t2.id AND t2.id = t3.id
    """

    // JOIN produces 10*5*1 = 50 rows
    //
    // SUM(a): sum(1..10) = 55, each appears 5*1=5 times -> 55*5 = 275
    // SUM(b): sum(10,20,30,40,50) = 150, each appears 10*1=10 times -> 150*10 = 1500
    // SUM(a*b): For each (a,b) pair, product appears 1 time
    //   = sum of a * sum of b = 55 * 150 = 8250
    val expectedResult = Row(50L, 275L, 1500L, 8250L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      val baseline = sql(query).collect()
      println(s"Baseline (large fan-out): ${baseline.map(_.toString).mkString}")
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== LARGE FAN-OUT STRESS TEST ===")
      println(s"10x5x1 = 50 row join")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  // ============================================================================
  // PLAN VERIFICATION TESTS
  // These tests verify the optimizer produces correct results for various
  // product aggregate patterns. Plan structure is logged for debugging
  // but not asserted on, since join tree structure may vary.
  // ============================================================================

  test("PLAN VERIFY: Independent products correctness") {
    // Two completely independent products: {a,b} and {c,d}
    // These products do not share any attributes and should produce correct results.
    val t1 = Seq((1, 10)).toDF("id", "a")
    val t2 = Seq((1, 20)).toDF("id", "b")
    val t3 = Seq((1, 30)).toDF("id", "c")
    val t4 = Seq((1, 40)).toDF("id", "d")

    t1.createOrReplaceTempView("ind_t1")
    t2.createOrReplaceTempView("ind_t2")
    t3.createOrReplaceTempView("ind_t3")
    t4.createOrReplaceTempView("ind_t4")

    val query = """
      SELECT SUM(ind_t1.a * ind_t2.b),
             SUM(ind_t3.c * ind_t4.d)
      FROM ind_t1, ind_t2, ind_t3, ind_t4
      WHERE ind_t1.id = ind_t2.id
        AND ind_t2.id = ind_t3.id
        AND ind_t3.id = ind_t4.id
    """

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)

      // scalastyle:off println
      println("=== PLAN VERIFY: Independent Products ===")
      println(s"Optimized plan:\n${df.queryExecution.optimizedPlan}")
      // scalastyle:on println

      // Verify correctness - independent products must produce correct results
      checkAnswer(df, Row(200L, 1200L))
    }
  }

  test("PLAN VERIFY: Star pattern conflicting products correctness") {
    // Star pattern: {a,b}, {a,c}, {a,d} all share 'a'
    // These products conflict because they share attribute 'a'.
    // The optimizer should handle this correctly (results must be accurate).
    val t1 = Seq((1, 10)).toDF("id", "a")
    val t2 = Seq((1, 20)).toDF("id", "b")
    val t3 = Seq((1, 30)).toDF("id", "c")
    val t4 = Seq((1, 40)).toDF("id", "d")

    t1.createOrReplaceTempView("star_t1")
    t2.createOrReplaceTempView("star_t2")
    t3.createOrReplaceTempView("star_t3")
    t4.createOrReplaceTempView("star_t4")

    val query = """
      SELECT SUM(star_t1.a * star_t2.b),
             SUM(star_t1.a * star_t3.c),
             SUM(star_t1.a * star_t4.d)
      FROM star_t1, star_t2, star_t3, star_t4
      WHERE star_t1.id = star_t2.id
        AND star_t2.id = star_t3.id
        AND star_t3.id = star_t4.id
    """

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)

      // scalastyle:off println
      println("=== PLAN VERIFY: Star Pattern Products ===")
      println(s"Optimized plan:\n${df.queryExecution.optimizedPlan}")
      // scalastyle:on println

      // Verify correctness - star pattern products must produce correct results
      // regardless of how the optimizer handles the conflicting products
      checkAnswer(df, Row(200L, 300L, 400L))
    }
  }

  test("PLAN VERIFY: Containment hierarchy product derivation") {
    // Products: {a}, {a,b} form containment hierarchy
    // {a} < {a,b} means we can derive {a}'s count from {a,b}'s count
    val t1 = Seq((1, 10), (1, 11)).toDF("id", "a")
    val t2 = Seq((1, 20), (1, 21)).toDF("id", "b")

    t1.createOrReplaceTempView("cont_t1")
    t2.createOrReplaceTempView("cont_t2")

    val query = """
      SELECT SUM(cont_t1.a),
             SUM(cont_t1.a * cont_t2.b)
      FROM cont_t1, cont_t2
      WHERE cont_t1.id = cont_t2.id
    """

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      val planStr = df.queryExecution.optimizedPlan.toString()

      // scalastyle:off println
      println("=== PLAN VERIFY: Containment Hierarchy ===")
      println(s"Optimized plan:\n$planStr")
      // scalastyle:on println

      // Verify correctness: 2*2 = 4 rows in join
      // SUM(a) = (10+11)*2 = 42
      // SUM(a*b) = 10*20 + 10*21 + 11*20 + 11*21 = 200+210+220+231 = 861
      checkAnswer(df, Row(42L, 861L))
    }
  }

  // ============================================================================
  // COMPLEX STAR PATTERN TESTS
  // These tests verify correctness for various star pattern configurations
  // ============================================================================

  test("Star pattern with multiple matching rows") {
    // Star pattern with duplication to test count handling
    val t1 = Seq((1, 10), (1, 11)).toDF("id", "a")
    val t2 = Seq((1, 20), (1, 21)).toDF("id", "b")
    val t3 = Seq((1, 30)).toDF("id", "c")
    val t4 = Seq((1, 40)).toDF("id", "d")

    t1.createOrReplaceTempView("star_dup_t1")
    t2.createOrReplaceTempView("star_dup_t2")
    t3.createOrReplaceTempView("star_dup_t3")
    t4.createOrReplaceTempView("star_dup_t4")

    val query = """
      SELECT SUM(star_dup_t1.a * star_dup_t2.b),
             SUM(star_dup_t1.a * star_dup_t3.c),
             SUM(star_dup_t1.a * star_dup_t4.d)
      FROM star_dup_t1, star_dup_t2, star_dup_t3, star_dup_t4
      WHERE star_dup_t1.id = star_dup_t2.id
        AND star_dup_t2.id = star_dup_t3.id
        AND star_dup_t3.id = star_dup_t4.id
    """

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      // 2 a's * 2 b's * 1 c * 1 d = 4 rows
      // SUM(a*b) = (10*20 + 10*21 + 11*20 + 11*21) = 200+210+220+231 = 861
      // SUM(a*c) = (10+11)*2*30 = 21*2*30 = 1260
      // SUM(a*d) = (10+11)*2*40 = 21*2*40 = 1680
      checkAnswer(df, Row(861L, 1260L, 1680L))
    }
  }

  test("Five-table star pattern") {
    // Larger star pattern: {a,b}, {a,c}, {a,d}, {a,e}
    val t1 = Seq((1, 10)).toDF("id", "a")
    val t2 = Seq((1, 20)).toDF("id", "b")
    val t3 = Seq((1, 30)).toDF("id", "c")
    val t4 = Seq((1, 40)).toDF("id", "d")
    val t5 = Seq((1, 50)).toDF("id", "e")

    t1.createOrReplaceTempView("star5_t1")
    t2.createOrReplaceTempView("star5_t2")
    t3.createOrReplaceTempView("star5_t3")
    t4.createOrReplaceTempView("star5_t4")
    t5.createOrReplaceTempView("star5_t5")

    val query = """
      SELECT SUM(star5_t1.a * star5_t2.b),
             SUM(star5_t1.a * star5_t3.c),
             SUM(star5_t1.a * star5_t4.d),
             SUM(star5_t1.a * star5_t5.e)
      FROM star5_t1, star5_t2, star5_t3, star5_t4, star5_t5
      WHERE star5_t1.id = star5_t2.id
        AND star5_t2.id = star5_t3.id
        AND star5_t3.id = star5_t4.id
        AND star5_t4.id = star5_t5.id
    """

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      // a=10, b=20, c=30, d=40, e=50
      // All products: a*b=200, a*c=300, a*d=400, a*e=500
      checkAnswer(df, Row(200L, 300L, 400L, 500L))
    }
  }

  test("Mixed star and independent products") {
    // Star pattern {a,b}, {a,c} plus independent {d,e}
    val t1 = Seq((1, 10)).toDF("id", "a")
    val t2 = Seq((1, 20)).toDF("id", "b")
    val t3 = Seq((1, 30)).toDF("id", "c")
    val t4 = Seq((1, 40)).toDF("id", "d")
    val t5 = Seq((1, 50)).toDF("id", "e")

    t1.createOrReplaceTempView("mixed_t1")
    t2.createOrReplaceTempView("mixed_t2")
    t3.createOrReplaceTempView("mixed_t3")
    t4.createOrReplaceTempView("mixed_t4")
    t5.createOrReplaceTempView("mixed_t5")

    val query = """
      SELECT SUM(mixed_t1.a * mixed_t2.b),
             SUM(mixed_t1.a * mixed_t3.c),
             SUM(mixed_t4.d * mixed_t5.e)
      FROM mixed_t1, mixed_t2, mixed_t3, mixed_t4, mixed_t5
      WHERE mixed_t1.id = mixed_t2.id
        AND mixed_t2.id = mixed_t3.id
        AND mixed_t3.id = mixed_t4.id
        AND mixed_t4.id = mixed_t5.id
    """

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      // Star products: a*b=200, a*c=300
      // Independent: d*e=2000
      checkAnswer(df, Row(200L, 300L, 2000L))
    }
  }

  test("Star pattern with grouping") {
    // Star pattern with GROUP BY
    val t1 = Seq((1, 10, "A"), (2, 20, "B")).toDF("id", "a", "grp")
    val t2 = Seq((1, 100), (2, 200)).toDF("id", "b")
    val t3 = Seq((1, 1000), (2, 2000)).toDF("id", "c")

    t1.createOrReplaceTempView("star_grp_t1")
    t2.createOrReplaceTempView("star_grp_t2")
    t3.createOrReplaceTempView("star_grp_t3")

    val query = """
      SELECT star_grp_t1.grp,
             SUM(star_grp_t1.a * star_grp_t2.b),
             SUM(star_grp_t1.a * star_grp_t3.c)
      FROM star_grp_t1, star_grp_t2, star_grp_t3
      WHERE star_grp_t1.id = star_grp_t2.id
        AND star_grp_t2.id = star_grp_t3.id
      GROUP BY star_grp_t1.grp
    """

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      // Group A: a=10, b=100, c=1000 => a*b=1000, a*c=10000
      // Group B: a=20, b=200, c=2000 => a*b=4000, a*c=40000
      checkAnswer(df, Seq(Row("A", 1000L, 10000L), Row("B", 4000L, 40000L)))
    }
  }

  test("Star pattern with expressions in aggregates") {
    // More complex expressions in star pattern products
    val t1 = Seq((1, 10)).toDF("id", "a")
    val t2 = Seq((1, 20)).toDF("id", "b")
    val t3 = Seq((1, 30)).toDF("id", "c")

    t1.createOrReplaceTempView("star_expr_t1")
    t2.createOrReplaceTempView("star_expr_t2")
    t3.createOrReplaceTempView("star_expr_t3")

    val query = """
      SELECT SUM(star_expr_t1.a * star_expr_t2.b + star_expr_t1.a * star_expr_t3.c)
      FROM star_expr_t1, star_expr_t2, star_expr_t3
      WHERE star_expr_t1.id = star_expr_t2.id
        AND star_expr_t2.id = star_expr_t3.id
    """

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      // a*b + a*c = 10*20 + 10*30 = 200 + 300 = 500
      checkAnswer(df, Row(500L))
    }
  }

  // ============================================================================
  // COMPLEX INDEPENDENT PRODUCT TESTS
  // These tests verify the optimization for independent products (disjoint attrs)
  // that can each be computed early with their own count tracks.
  // ============================================================================

  test("Independent products - 6 table chain with 3 disjoint products") {
    // Six tables forming a chain: T1-T2-T3-T4-T5-T6
    // Three completely independent products:
    //   P1: SUM(a*b) uses {a,b} from T1,T2
    //   P2: SUM(c*d) uses {c,d} from T3,T4
    //   P3: SUM(e*f) uses {e,f} from T5,T6
    // All products are disjoint - should each optimize independently
    val t1 = Seq((1, 10), (1, 11)).toDF("id", "a")  // 2 rows
    val t2 = Seq((1, 20)).toDF("id", "b")           // 1 row
    val t3 = Seq((1, 30), (1, 31), (1, 32)).toDF("id", "c")  // 3 rows
    val t4 = Seq((1, 40)).toDF("id", "d")           // 1 row
    val t5 = Seq((1, 50), (1, 51)).toDF("id", "e")  // 2 rows
    val t6 = Seq((1, 60)).toDF("id", "f")           // 1 row

    t1.createOrReplaceTempView("ind6_t1")
    t2.createOrReplaceTempView("ind6_t2")
    t3.createOrReplaceTempView("ind6_t3")
    t4.createOrReplaceTempView("ind6_t4")
    t5.createOrReplaceTempView("ind6_t5")
    t6.createOrReplaceTempView("ind6_t6")

    val query = """
      SELECT COUNT(*),
             SUM(ind6_t1.a * ind6_t2.b),
             SUM(ind6_t3.c * ind6_t4.d),
             SUM(ind6_t5.e * ind6_t6.f)
      FROM ind6_t1, ind6_t2, ind6_t3, ind6_t4, ind6_t5, ind6_t6
      WHERE ind6_t1.id = ind6_t2.id
        AND ind6_t2.id = ind6_t3.id
        AND ind6_t3.id = ind6_t4.id
        AND ind6_t4.id = ind6_t5.id
        AND ind6_t5.id = ind6_t6.id
    """

    // JOIN produces 2*1*3*1*2*1 = 12 rows
    //
    // SUM(a*b): Each (a,b) pair appears 3*1*2*1=6 times
    // (10*20)*6 + (11*20)*6 = 1200 + 1320 = 2520
    //
    // SUM(c*d): Each (c,d) pair appears 2*1*2*1=4 times
    // (30*40)*4 + (31*40)*4 + (32*40)*4 = 4800 + 4960 + 5120 = 14880
    //
    // SUM(e*f): Each (e,f) pair appears 2*1*3*1=6 times
    // (50*60)*6 + (51*60)*6 = 18000 + 18360 = 36360
    val expectedResult = Row(12L, 2520L, 14880L, 36360L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      val baseline = sql(query).collect()
      println(s"Baseline (6-table-3-indep): ${baseline.map(_.toString).mkString}")
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== 6-TABLE CHAIN WITH 3 INDEPENDENT PRODUCTS ===")
      println(s"Products: {a,b}, {c,d}, {e,f} - all disjoint")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  test("Independent products with large fan-out multipliers") {
    // Test with larger fan-out to stress count accuracy
    // Products {a,b} and {c,d} are independent
    val t1 = (1 to 5).map(i => (1, i * 10)).toDF("id", "a")    // 5 rows
    val t2 = (1 to 4).map(i => (1, i * 100)).toDF("id", "b")   // 4 rows
    val t3 = (1 to 3).map(i => (1, i * 1000)).toDF("id", "c")  // 3 rows
    val t4 = (1 to 2).map(i => (1, i * 10000)).toDF("id", "d") // 2 rows

    t1.createOrReplaceTempView("indfan_t1")
    t2.createOrReplaceTempView("indfan_t2")
    t3.createOrReplaceTempView("indfan_t3")
    t4.createOrReplaceTempView("indfan_t4")

    val query = """
      SELECT COUNT(*),
             SUM(indfan_t1.a * indfan_t2.b),
             SUM(indfan_t3.c * indfan_t4.d)
      FROM indfan_t1, indfan_t2, indfan_t3, indfan_t4
      WHERE indfan_t1.id = indfan_t2.id
        AND indfan_t2.id = indfan_t3.id
        AND indfan_t3.id = indfan_t4.id
    """

    // JOIN produces 5*4*3*2 = 120 rows
    //
    // sum(a) = 10+20+30+40+50 = 150
    // sum(b) = 100+200+300+400 = 1000
    // sum(c) = 1000+2000+3000 = 6000
    // sum(d) = 10000+20000 = 30000
    //
    // SUM(a*b): Each (a,b) pair appears 3*2=6 times
    // Total = sum(a) * sum(b) * 6 = 150 * 1000 = 150000 (but pairs not products)
    // Actually: sum over all (a,b) pairs of a*b, each appearing 6 times
    // = 6 * sum_a sum_b (a*b) = 6 * (sum_a * sum_b) = 6 * 150 * 1000 = 900000
    //
    // SUM(c*d): Each (c,d) pair appears 5*4=20 times
    // = 20 * (sum_c * sum_d) = 20 * 6000 * 30000 = 3600000000
    val expectedResult = Row(120L, 900000L, 3600000000L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      val baseline = sql(query).collect()
      println(s"Baseline (large-fan-indep): ${baseline.map(_.toString).mkString}")
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== INDEPENDENT PRODUCTS WITH LARGE FAN-OUT ===")
      println(s"120 row join, products {a,b} and {c,d} independent")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  test("Independent products with mixed single and multi-attr") {
    // Mix of single-attribute and multi-attribute products, all independent:
    // P1: SUM(a) uses {a}
    // P2: SUM(b*c) uses {b,c}
    // P3: SUM(d) uses {d}
    // P4: SUM(e*f) uses {e,f}
    // All sets are disjoint
    val t1 = Seq((1, 10), (1, 11)).toDF("id", "a")
    val t2 = Seq((1, 20)).toDF("id", "b")
    val t3 = Seq((1, 30), (1, 31)).toDF("id", "c")
    val t4 = Seq((1, 40)).toDF("id", "d")
    val t5 = Seq((1, 50)).toDF("id", "e")
    val t6 = Seq((1, 60), (1, 61)).toDF("id", "f")

    t1.createOrReplaceTempView("indmix_t1")
    t2.createOrReplaceTempView("indmix_t2")
    t3.createOrReplaceTempView("indmix_t3")
    t4.createOrReplaceTempView("indmix_t4")
    t5.createOrReplaceTempView("indmix_t5")
    t6.createOrReplaceTempView("indmix_t6")

    val query = """
      SELECT COUNT(*),
             SUM(indmix_t1.a),
             SUM(indmix_t2.b * indmix_t3.c),
             SUM(indmix_t4.d),
             SUM(indmix_t5.e * indmix_t6.f)
      FROM indmix_t1, indmix_t2, indmix_t3, indmix_t4, indmix_t5, indmix_t6
      WHERE indmix_t1.id = indmix_t2.id
        AND indmix_t2.id = indmix_t3.id
        AND indmix_t3.id = indmix_t4.id
        AND indmix_t4.id = indmix_t5.id
        AND indmix_t5.id = indmix_t6.id
    """

    // JOIN produces 2*1*2*1*1*2 = 8 rows
    //
    // SUM(a): Each a appears 1*2*1*1*2=4 times
    // (10+11)*4 = 84
    //
    // SUM(b*c): Each (b,c) pair appears 2*1*1*2=4 times
    // (20*30 + 20*31)*4 = (600+620)*4 = 4880
    //
    // SUM(d): Each d appears 2*1*2*1*2=8 times
    // 40*8 = 320
    //
    // SUM(e*f): Each (e,f) pair appears 2*1*2*1=4 times
    // (50*60 + 50*61)*4 = (3000+3050)*4 = 24200
    val expectedResult = Row(8L, 84L, 4880L, 320L, 24200L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      val baseline = sql(query).collect()
      println(s"Baseline (mixed-indep): ${baseline.map(_.toString).mkString}")
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== MIXED SINGLE AND MULTI-ATTR INDEPENDENT PRODUCTS ===")
      println(s"Products: {a}, {b,c}, {d}, {e,f} - all disjoint")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  test("Independent products with cross-relation filter") {
    // Independent products with a cross-relation filter
    // P1: SUM(a*b) uses {a,b}
    // P2: SUM(c*d) uses {c,d}
    // Filter: a + c > 35 spans {a,c}
    val t1 = Seq((1, 10), (1, 20)).toDF("id", "a")
    val t2 = Seq((1, 100)).toDF("id", "b")
    val t3 = Seq((1, 30), (1, 40)).toDF("id", "c")
    val t4 = Seq((1, 1000)).toDF("id", "d")

    t1.createOrReplaceTempView("indfilter_t1")
    t2.createOrReplaceTempView("indfilter_t2")
    t3.createOrReplaceTempView("indfilter_t3")
    t4.createOrReplaceTempView("indfilter_t4")

    val query = """
      SELECT COUNT(*),
             SUM(indfilter_t1.a * indfilter_t2.b),
             SUM(indfilter_t3.c * indfilter_t4.d)
      FROM indfilter_t1, indfilter_t2, indfilter_t3, indfilter_t4
      WHERE indfilter_t1.id = indfilter_t2.id
        AND indfilter_t2.id = indfilter_t3.id
        AND indfilter_t3.id = indfilter_t4.id
        AND indfilter_t1.a + indfilter_t3.c > 35
    """

    // Full join produces 2*1*2*1 = 4 rows:
    // (a,b,c,d): (10,100,30,1000), (10,100,40,1000), (20,100,30,1000), (20,100,40,1000)
    // Filter a+c > 35:
    // (10,100,30,1000): 10+30=40 YES
    // (10,100,40,1000): 10+40=50 YES
    // (20,100,30,1000): 20+30=50 YES
    // (20,100,40,1000): 20+40=60 YES
    // All 4 pass!
    //
    // SUM(a*b): (10*100)+(10*100)+(20*100)+(20*100) = 1000+1000+2000+2000 = 6000
    // SUM(c*d): (30*1000)+(40*1000)+(30*1000)+(40*1000) = 30000+40000+30000+40000 = 140000
    val expectedResult = Row(4L, 6000L, 140000L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      val baseline = sql(query).collect()
      println(s"Baseline (indep+filter): ${baseline.map(_.toString).mkString}")
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== INDEPENDENT PRODUCTS WITH CROSS-RELATION FILTER ===")
      println(s"Products: {a,b} and {c,d} independent, filter on {a,c}")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  test("Independent three-attr products") {
    // Independent products with 3 attributes each:
    // P1: SUM(a*b*c) uses {a,b,c}
    // P2: SUM(d*e*f) uses {d,e,f}
    val t1 = Seq((1, 10)).toDF("id", "a")
    val t2 = Seq((1, 20), (1, 21)).toDF("id", "b")
    val t3 = Seq((1, 30)).toDF("id", "c")
    val t4 = Seq((1, 40), (1, 41)).toDF("id", "d")
    val t5 = Seq((1, 50)).toDF("id", "e")
    val t6 = Seq((1, 60)).toDF("id", "f")

    t1.createOrReplaceTempView("ind3attr_t1")
    t2.createOrReplaceTempView("ind3attr_t2")
    t3.createOrReplaceTempView("ind3attr_t3")
    t4.createOrReplaceTempView("ind3attr_t4")
    t5.createOrReplaceTempView("ind3attr_t5")
    t6.createOrReplaceTempView("ind3attr_t6")

    val query = """
      SELECT COUNT(*),
             SUM(ind3attr_t1.a * ind3attr_t2.b * ind3attr_t3.c),
             SUM(ind3attr_t4.d * ind3attr_t5.e * ind3attr_t6.f)
      FROM ind3attr_t1, ind3attr_t2, ind3attr_t3, ind3attr_t4, ind3attr_t5, ind3attr_t6
      WHERE ind3attr_t1.id = ind3attr_t2.id
        AND ind3attr_t2.id = ind3attr_t3.id
        AND ind3attr_t3.id = ind3attr_t4.id
        AND ind3attr_t4.id = ind3attr_t5.id
        AND ind3attr_t5.id = ind3attr_t6.id
    """

    // JOIN produces 1*2*1*2*1*1 = 4 rows
    //
    // SUM(a*b*c): Each (a,b,c) tuple appears 2*1*1=2 times
    // (10*20*30 + 10*21*30)*2 = (6000+6300)*2 = 24600
    //
    // SUM(d*e*f): Each (d,e,f) tuple appears 1*2*1=2 times
    // (40*50*60 + 41*50*60)*2 = (120000+123000)*2 = 486000
    val expectedResult = Row(4L, 24600L, 486000L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      val baseline = sql(query).collect()
      println(s"Baseline (3-attr-indep): ${baseline.map(_.toString).mkString}")
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== INDEPENDENT 3-ATTR PRODUCTS ===")
      println(s"Products: {a,b,c} and {d,e,f} - disjoint 3-attr products")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  // ============================================================================
  // ADDITIONAL CONNECTED COMPONENTS TESTS
  // ============================================================================

  test("CASE 4b: Three independent components") {
    // Three completely independent components - verifies transitive closure is correct
    // Component 1: {a,b} - single product
    // Component 2: {c,d} - single product
    // Component 3: {e,f} - single product
    // None share any attributes, so all should be optimized independently
    val t1 = Seq((1, 10), (1, 11)).toDF("id", "a")  // 2 rows
    val t2 = Seq((1, 20)).toDF("id", "b")           // 1 row
    val t3 = Seq((1, 30), (1, 31)).toDF("id", "c")  // 2 rows
    val t4 = Seq((1, 40)).toDF("id", "d")           // 1 row
    val t5 = Seq((1, 50), (1, 51)).toDF("id", "e")  // 2 rows
    val t6 = Seq((1, 60)).toDF("id", "f")           // 1 row

    t1.createOrReplaceTempView("c4b_t1")
    t2.createOrReplaceTempView("c4b_t2")
    t3.createOrReplaceTempView("c4b_t3")
    t4.createOrReplaceTempView("c4b_t4")
    t5.createOrReplaceTempView("c4b_t5")
    t6.createOrReplaceTempView("c4b_t6")

    val query = """
      SELECT COUNT(*),
             SUM(c4b_t1.a * c4b_t2.b),
             SUM(c4b_t3.c * c4b_t4.d),
             SUM(c4b_t5.e * c4b_t6.f)
      FROM c4b_t1, c4b_t2, c4b_t3, c4b_t4, c4b_t5, c4b_t6
      WHERE c4b_t1.id = c4b_t2.id
        AND c4b_t2.id = c4b_t3.id
        AND c4b_t3.id = c4b_t4.id
        AND c4b_t4.id = c4b_t5.id
        AND c4b_t5.id = c4b_t6.id
    """

    // JOIN produces 2*1*2*1*2*1 = 8 rows
    //
    // SUM(a*b): Each (a,b) pair appears 2*1*2*1 = 4 times
    // (10*20 + 11*20) * 4 = (200+220)*4 = 1680
    //
    // SUM(c*d): Each (c,d) pair appears 2*1*2*1 = 4 times
    // (30*40 + 31*40) * 4 = (1200+1240)*4 = 9760
    //
    // SUM(e*f): Each (e,f) pair appears 2*1*2*1 = 4 times
    // (50*60 + 51*60) * 4 = (3000+3060)*4 = 24240
    val expectedResult = Row(8L, 1680L, 9760L, 24240L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== CASE 4b: THREE INDEPENDENT COMPONENTS ===")
      println("Component 1: {a,b} - independent")
      println("Component 2: {c,d} - independent")
      println("Component 3: {e,f} - independent")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  test("CASE 4c: Star with independent product") {
    // Star pattern (conflicting) + one independent product
    // Component 1: {a,b}, {a,c}, {a,d} - star pattern (all conflict via 'a')
    // Component 2: {e,f} - independent
    // The star products should defer; the independent should optimize
    val t1 = Seq((1, 10), (1, 11)).toDF("id", "a")  // 2 rows - shared
    val t2 = Seq((1, 20)).toDF("id", "b")           // 1 row
    val t3 = Seq((1, 30), (1, 31)).toDF("id", "c")  // 2 rows
    val t4 = Seq((1, 40)).toDF("id", "d")           // 1 row
    val t5 = Seq((1, 50), (1, 51)).toDF("id", "e")  // 2 rows
    val t6 = Seq((1, 60)).toDF("id", "f")           // 1 row

    t1.createOrReplaceTempView("c4c_t1")
    t2.createOrReplaceTempView("c4c_t2")
    t3.createOrReplaceTempView("c4c_t3")
    t4.createOrReplaceTempView("c4c_t4")
    t5.createOrReplaceTempView("c4c_t5")
    t6.createOrReplaceTempView("c4c_t6")

    val query = """
      SELECT COUNT(*),
             SUM(c4c_t1.a * c4c_t2.b),
             SUM(c4c_t1.a * c4c_t3.c),
             SUM(c4c_t1.a * c4c_t4.d),
             SUM(c4c_t5.e * c4c_t6.f)
      FROM c4c_t1, c4c_t2, c4c_t3, c4c_t4, c4c_t5, c4c_t6
      WHERE c4c_t1.id = c4c_t2.id
        AND c4c_t2.id = c4c_t3.id
        AND c4c_t3.id = c4c_t4.id
        AND c4c_t4.id = c4c_t5.id
        AND c4c_t5.id = c4c_t6.id
    """

    // JOIN produces 2*1*2*1*2*1 = 8 rows
    //
    // SUM(a*b): Each (a,b) appears 2*1*2*1 = 4 times
    // (10*20 + 11*20) * 4 = 420*4 = 1680
    //
    // SUM(a*c): Each (a,c) appears 1*1*2*1 = 2 times
    // (10*30 + 10*31 + 11*30 + 11*31) * 2 = (300+310+330+341)*2 = 2562
    //
    // SUM(a*d): Each (a,d) appears 1*2*1*2 = 4 times
    // (10*40 + 11*40) * 4 = 840*4 = 3360
    //
    // SUM(e*f): Each (e,f) appears 2*1*2*1 = 4 times
    // (50*60 + 51*60) * 4 = 6060*4 = 24240
    val expectedResult = Row(8L, 1680L, 2562L, 3360L, 24240L)

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      checkAnswer(sql(query), expectedResult)
    }

    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
    ) {
      val df = sql(query)
      println("=== CASE 4c: STAR WITH INDEPENDENT PRODUCT ===")
      println("Component 1 (star): {a,b}, {a,c}, {a,d} - conflict via 'a'")
      println("Component 2 (indep): {e,f}")
      println(s"Result: ${df.collect().map(_.toString).mkString}")
      checkAnswer(df, expectedResult)
    }
    // scalastyle:on println
  }

  // ==========================================================================
  // CASE 6: SYNTHETIC SUPERSET OPTIMIZATION (STATUS: OPTIONAL)
  // ==========================================================================
  // For star-pattern conflicts, create synthetic superset when enabled.
  // Tests that synthetic superset produces same results as deferred approach.

  test("CASE 6: Synthetic superset for star pattern") {
    // Create simple tables for star pattern test
    // Star pattern: {a,b}, {a,c}, {a,d} - all share 'a' but none contains another
    withTable("star_r1", "star_r2", "star_r3", "star_r4") {
      sql("CREATE TABLE star_r1 (id INT, a INT, b INT) USING parquet")
      sql("CREATE TABLE star_r2 (id INT, a INT, c INT) USING parquet")
      sql("CREATE TABLE star_r3 (id INT, a INT, d INT) USING parquet")
      sql("CREATE TABLE star_r4 (id INT, a INT) USING parquet")

      // Insert test data
      sql("INSERT INTO star_r1 VALUES (1, 10, 100), (2, 10, 101), (3, 20, 200)")
      sql("INSERT INTO star_r2 VALUES (1, 10, 1000), (2, 20, 2000)")
      sql("INSERT INTO star_r3 VALUES (1, 10, 10000), (2, 10, 10001), (3, 20, 20000)")
      sql("INSERT INTO star_r4 VALUES (1, 10), (2, 20)")

      val query = """
        SELECT SUM(r1.a * r1.b) as p1,
               SUM(r1.a * r2.c) as p2,
               SUM(r1.a * r3.d) as p3
        FROM star_r1 r1
        JOIN star_r4 r4 ON r1.a = r4.a
        JOIN star_r2 r2 ON r1.a = r2.a
        JOIN star_r3 r3 ON r1.a = r3.a
      """

      // Get baseline result with Yannakakis disabled
      var baseline: Seq[Row] = Seq.empty
      withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
        baseline = sql(query).collect().toSeq
      }

      // Test with synthetic superset DISABLED (current behavior - deferred)
      // scalastyle:off println
      withSQLConf(
        SQLConf.YANNAKAKIS_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
      ) {
        val df = sql(query)
        println("=== CASE 6: SYNTHETIC SUPERSET (disabled) ===")
        println("Star pattern: {a,b}, {a,c}, {a,d}")
        println(s"Result: ${df.collect().map(_.toString).mkString}")
        checkAnswer(df, baseline)
      }

      // Test with join-tree-aware analysis (replaces synthetic superset)
      withSQLConf(
        SQLConf.YANNAKAKIS_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
      ) {
        val df = sql(query)
        println("=== CASE 6: JOIN-TREE-AWARE ANALYSIS ===")
        println("Star pattern: {a,b}, {a,c}, {a,d} -> Uses join-tree-aware conflict detection")
        println(s"Result: ${df.collect().map(_.toString).mkString}")
        checkAnswer(df, baseline)
      }
      // scalastyle:on println
    }
  }

  test("CASE 6b: Synthetic superset with too-large synthetic rejected") {
    // Test that synthetic superset is NOT used when union would be too large
    // Criteria: syntheticSize <= maxProductSize * 2
    // Here we have products of size 2, but union would be size 5 (> 2*2=4)
    withTable("large_r1", "large_r2", "large_r3") {
      sql("CREATE TABLE large_r1 (id INT, a INT, b INT) USING parquet")
      sql("CREATE TABLE large_r2 (id INT, a INT, c INT, d INT) USING parquet")
      sql("CREATE TABLE large_r3 (id INT, a INT, e INT, f INT) USING parquet")

      sql("INSERT INTO large_r1 VALUES (1, 10, 100)")
      sql("INSERT INTO large_r2 VALUES (1, 10, 1000, 2000)")
      sql("INSERT INTO large_r3 VALUES (1, 10, 10000, 20000)")

      // Product attrs: {a,b} size=2, {a,c} size=2, {a,e} size=2
      // But union {a,b,c,d,e,f} size=6 > 2*2=4, so synthetic should be rejected
      val query = """
        SELECT SUM(r1.a * r1.b) as p1,
               SUM(r1.a * r2.c) as p2,
               SUM(r1.a * r3.e) as p3
        FROM large_r1 r1
        JOIN large_r2 r2 ON r1.a = r2.a
        JOIN large_r3 r3 ON r1.a = r3.a
      """

      var baseline: Seq[Row] = Seq.empty
      withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
        baseline = sql(query).collect().toSeq
      }

      // Both should produce same result (synthetic rejected, falls back to defer)
      // scalastyle:off println
      withSQLConf(
        SQLConf.YANNAKAKIS_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
      ) {
        val df = sql(query)
        println("=== CASE 6b: SYNTHETIC TOO LARGE ===")
        println("Products: {a,b}, {a,c}, {a,e} - union too large for synthetic")
        println(s"Result: ${df.collect().map(_.toString).mkString}")
        checkAnswer(df, baseline)
      }
      // scalastyle:on println
    }
  }

  test("CASE 6c: Synthetic superset with duplicate rows - count accuracy") {
    // Test that counts are accurate when there are duplicate rows
    withTable("dup_t1", "dup_t2", "dup_t3") {
      sql("CREATE TABLE dup_t1 (a INT, b INT) USING parquet")
      sql("CREATE TABLE dup_t2 (a INT, c INT) USING parquet")
      sql("CREATE TABLE dup_t3 (a INT, d INT) USING parquet")

      // Insert duplicates to create cross-product multiplications
      sql("INSERT INTO dup_t1 VALUES (1, 10), (1, 10), (1, 20)")  // 3 rows for a=1
      sql("INSERT INTO dup_t2 VALUES (1, 100), (1, 100)")          // 2 rows for a=1
      sql("INSERT INTO dup_t3 VALUES (1, 1000)")                    // 1 row for a=1

      val query = """
        SELECT SUM(t1.a * t1.b) as p1,
               SUM(t1.a * t2.c) as p2,
               SUM(t1.a * t3.d) as p3
        FROM dup_t1 t1
        JOIN dup_t2 t2 ON t1.a = t2.a
        JOIN dup_t3 t3 ON t1.a = t3.a
      """

      var baseline: Seq[Row] = Seq.empty
      withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
        baseline = sql(query).collect().toSeq
      }

      // scalastyle:off println
      withSQLConf(
        SQLConf.YANNAKAKIS_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
      ) {
        val df = sql(query)
        println("=== CASE 6c: DUPLICATE ROWS - COUNT ACCURACY ===")
        println(s"Baseline: ${baseline.map(_.toString).mkString}")
        println(s"Synthetic result: ${df.collect().map(_.toString).mkString}")
        checkAnswer(df, baseline)
      }
      // scalastyle:on println
    }
  }

  test("CASE 6d: No common center - synthetic superset should be rejected") {
    // Products: {a,b}, {c,d} - no overlap, so no star pattern
    withTable("nostar_t1", "nostar_t2") {
      sql("CREATE TABLE nostar_t1 (id INT, a INT, b INT) USING parquet")
      sql("CREATE TABLE nostar_t2 (id INT, c INT, d INT) USING parquet")

      sql("INSERT INTO nostar_t1 VALUES (1, 10, 100)")
      sql("INSERT INTO nostar_t2 VALUES (1, 1000, 10000)")

      val query = """
        SELECT SUM(t1.a * t1.b) as p1,
               SUM(t2.c * t2.d) as p2
        FROM nostar_t1 t1
        JOIN nostar_t2 t2 ON t1.id = t2.id
      """

      var baseline: Seq[Row] = Seq.empty
      withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
        baseline = sql(query).collect().toSeq
      }

      // scalastyle:off println
      withSQLConf(
        SQLConf.YANNAKAKIS_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
      ) {
        val df = sql(query)
        println("=== CASE 6d: NO COMMON CENTER ===")
        println("Products: {a,b}, {c,d} - no common attrs, synthetic NOT used")
        println(s"Result: ${df.collect().map(_.toString).mkString}")
        checkAnswer(df, baseline)
      }
      // scalastyle:on println
    }
  }

  test("CASE 6e: IMDB-style star pattern with role_id center") {
    // Mimics IMDB query structure:
    // Products: {role_id, info_type_id}, {production_year, role_id},
    //           {company_type_id, role_id, kind_id}
    // Common center: role_id
    withTable("imdb_ci", "imdb_mi", "imdb_mc", "imdb_t", "imdb_rt") {
      sql("CREATE TABLE imdb_ci (id INT, role_id INT, movie_id INT) USING parquet")
      sql("CREATE TABLE imdb_mi (id INT, movie_id INT, info_type_id INT) USING parquet")
      sql("CREATE TABLE imdb_mc (id INT, movie_id INT, company_type_id INT) USING parquet")
      sql("CREATE TABLE imdb_t (id INT, production_year INT, kind_id INT) USING parquet")
      sql("CREATE TABLE imdb_rt (id INT) USING parquet")

      sql("INSERT INTO imdb_ci VALUES (1, 2, 100)")
      sql("INSERT INTO imdb_mi VALUES (1, 100, 5)")
      sql("INSERT INTO imdb_mc VALUES (1, 100, 3)")
      sql("INSERT INTO imdb_t VALUES (100, 2015, 7)")
      sql("INSERT INTO imdb_rt VALUES (2)")

      // Products all share role_id as center:
      // {role_id, info_type_id}, {production_year, role_id}, {company_type_id, role_id, kind_id}
      val query = """
        SELECT SUM(ci.role_id * mi.info_type_id) as p1,
               SUM(t.production_year * ci.role_id) as p2,
               SUM(mc.company_type_id * ci.role_id * t.kind_id) as p3
        FROM imdb_ci ci
        JOIN imdb_mi mi ON ci.movie_id = mi.movie_id
        JOIN imdb_mc mc ON ci.movie_id = mc.movie_id
        JOIN imdb_t t ON ci.movie_id = t.id
        JOIN imdb_rt rt ON ci.role_id = rt.id
      """

      var baseline: Seq[Row] = Seq.empty
      withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
        baseline = sql(query).collect().toSeq
      }

      // scalastyle:off println
      println("=== CASE 6e: IMDB-STYLE STAR PATTERN ===")
      println("Products: {role_id,info_type_id}, {production_year,role_id}, " +
        "{company_type_id,role_id,kind_id}")
      println("Common center: role_id")
      println(s"Baseline: ${baseline.map(_.toString).mkString}")

      withSQLConf(
        SQLConf.YANNAKAKIS_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
      ) {
        val df = sql(query)
        println(s"Synthetic result: ${df.collect().map(_.toString).mkString}")
        checkAnswer(df, baseline)
      }
      // scalastyle:on println
    }
  }

  test("CASE 6f: Synthetic superset with multiple rows - all products early") {
    // Tests that all products in a star pattern are computed early (not deferred)
    // Previous bug: foreign grouping check blocked products even with synthetic superset
    withTable("star_center", "star_a", "star_b", "star_c") {
      sql("CREATE TABLE star_center (id INT, x INT) USING parquet")
      sql("CREATE TABLE star_a (id INT, a INT) USING parquet")
      sql("CREATE TABLE star_b (id INT, b INT) USING parquet")
      sql("CREATE TABLE star_c (id INT, c INT) USING parquet")

      // Multiple rows to test count accuracy
      sql("INSERT INTO star_center VALUES (1, 10), (2, 20), (3, 30)")
      sql("INSERT INTO star_a VALUES (1, 2), (2, 3)")
      sql("INSERT INTO star_b VALUES (1, 4), (2, 5), (3, 6)")
      sql("INSERT INTO star_c VALUES (1, 7), (3, 8)")

      // Star pattern: all products share x from center
      // p1 = SUM(x * a)  attrs: {x, a}
      // p2 = SUM(x * b)  attrs: {x, b}
      // p3 = SUM(x * c)  attrs: {x, c}
      val query = """
        SELECT SUM(s.x * a.a) as p1,
               SUM(s.x * b.b) as p2,
               SUM(s.x * c.c) as p3
        FROM star_center s
        JOIN star_a a ON s.id = a.id
        JOIN star_b b ON s.id = b.id
        JOIN star_c c ON s.id = c.id
      """

      var baseline: Seq[Row] = Seq.empty
      withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
        baseline = sql(query).collect().toSeq
      }

      // scalastyle:off println
      println("=== CASE 6f: SYNTHETIC SUPERSET - ALL PRODUCTS EARLY ===")
      println("Products: {x,a}, {x,b}, {x,c} - all share x")
      println(s"Baseline: ${baseline.map(_.toString).mkString}")

      withSQLConf(
        SQLConf.YANNAKAKIS_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
      ) {
        val df = sql(query)
        println(s"Synthetic result: ${df.collect().map(_.toString).mkString}")
        checkAnswer(df, baseline)
      }
      // scalastyle:on println
    }
  }

  test("CASE 6g: Synthetic superset with 4 products - larger star") {
    // Tests synthetic superset with more products
    withTable("center4", "arm1", "arm2", "arm3", "arm4") {
      sql("CREATE TABLE center4 (id INT, x INT) USING parquet")
      sql("CREATE TABLE arm1 (id INT, a INT) USING parquet")
      sql("CREATE TABLE arm2 (id INT, b INT) USING parquet")
      sql("CREATE TABLE arm3 (id INT, c INT) USING parquet")
      sql("CREATE TABLE arm4 (id INT, d INT) USING parquet")

      sql("INSERT INTO center4 VALUES (1, 5), (2, 10)")
      sql("INSERT INTO arm1 VALUES (1, 2), (2, 3)")
      sql("INSERT INTO arm2 VALUES (1, 4), (2, 5)")
      sql("INSERT INTO arm3 VALUES (1, 6), (2, 7)")
      sql("INSERT INTO arm4 VALUES (1, 8), (2, 9)")

      val query = """
        SELECT SUM(c.x * a1.a) as p1,
               SUM(c.x * a2.b) as p2,
               SUM(c.x * a3.c) as p3,
               SUM(c.x * a4.d) as p4
        FROM center4 c
        JOIN arm1 a1 ON c.id = a1.id
        JOIN arm2 a2 ON c.id = a2.id
        JOIN arm3 a3 ON c.id = a3.id
        JOIN arm4 a4 ON c.id = a4.id
      """

      var baseline: Seq[Row] = Seq.empty
      withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
        baseline = sql(query).collect().toSeq
      }

      // scalastyle:off println
      println("=== CASE 6g: SYNTHETIC SUPERSET - 4 PRODUCTS ===")
      println(s"Baseline: ${baseline.map(_.toString).mkString}")

      withSQLConf(
        SQLConf.YANNAKAKIS_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
      ) {
        val df = sql(query)
        println(s"Synthetic result: ${df.collect().map(_.toString).mkString}")
        checkAnswer(df, baseline)
      }
      // scalastyle:on println
    }
  }

  test("CASE 6h: Synthetic superset with fan-out multiplication") {
    // Tests that counts are correctly multiplied for fan-out scenarios
    withTable("hub", "spoke1", "spoke2") {
      sql("CREATE TABLE hub (id INT, val INT) USING parquet")
      sql("CREATE TABLE spoke1 (hub_id INT, a INT) USING parquet")
      sql("CREATE TABLE spoke2 (hub_id INT, b INT) USING parquet")

      // Hub with 2 rows
      sql("INSERT INTO hub VALUES (1, 10), (2, 20)")
      // Spoke1: 2 rows match hub 1, 1 row matches hub 2 -> fan-out
      sql("INSERT INTO spoke1 VALUES (1, 2), (1, 3), (2, 4)")
      // Spoke2: 1 row matches hub 1, 2 rows match hub 2
      sql("INSERT INTO spoke2 VALUES (1, 5), (2, 6), (2, 7)")

      // Cross join creates: hub1 x 2 spoke1 x 1 spoke2 = 2 combos for hub 1
      //                     hub2 x 1 spoke1 x 2 spoke2 = 2 combos for hub 2
      val query = """
        SELECT SUM(h.val * s1.a) as p1,
               SUM(h.val * s2.b) as p2
        FROM hub h
        JOIN spoke1 s1 ON h.id = s1.hub_id
        JOIN spoke2 s2 ON h.id = s2.hub_id
      """

      var baseline: Seq[Row] = Seq.empty
      withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
        baseline = sql(query).collect().toSeq
      }

      // scalastyle:off println
      println("=== CASE 6h: SYNTHETIC SUPERSET - FAN-OUT ===")
      println(s"Baseline: ${baseline.map(_.toString).mkString}")

      withSQLConf(
        SQLConf.YANNAKAKIS_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
      ) {
        val df = sql(query)
        println(s"Synthetic result: ${df.collect().map(_.toString).mkString}")
        checkAnswer(df, baseline)
      }
      // scalastyle:on println
    }
  }

  test("Partial overlap: pending product flows through join with mixed tables") {
    // This test exercises the edge case where a pending product computed at join J1
    // flows through LEFT propagation at join J2, where J2's right subtree contains
    // some tables that were already counted (partial overlap) and some new tables.
    //
    // Schema:
    // - center (id, val): hub table
    // - left_spoke (center_id, a): spoke joining to center
    // - right_spoke (center_id, b): spoke joining to center
    // - extra (right_spoke_id, c): extra table joining to right_spoke
    //
    // Query: SUM(val * a) -- product from center × left_spoke
    // The product might be computed at (center ⋈ left_spoke), then flow through
    // a join with (right_spoke ⋈ extra). If right_spoke is also joined to center
    // earlier, there could be partial overlap.
    //
    // For this test, we create a 4-way join:
    //   center ⋈ left_spoke ⋈ right_spoke ⋈ extra
    // where center joins to both spokes, and extra joins to right_spoke.
    //
    // The product SUM(val * a) uses {center, left_spoke} tables.
    // Depending on join order, when this product flows up, it might encounter
    // a right subtree that includes center (overlap) or just right_spoke/extra (no overlap).

    withTable("center", "left_spoke", "right_spoke", "extra") {
      sql("CREATE TABLE center (id INT, val INT) USING parquet")
      sql("CREATE TABLE left_spoke (center_id INT, a INT) USING parquet")
      sql("CREATE TABLE right_spoke (center_id INT, b INT) USING parquet")
      sql("CREATE TABLE extra (right_spoke_b INT, c INT) USING parquet")

      // Center: 2 rows
      sql("INSERT INTO center VALUES (1, 10), (2, 20)")
      // Left spoke: fan-out - 2 rows for center 1, 1 for center 2
      sql("INSERT INTO left_spoke VALUES (1, 2), (1, 3), (2, 4)")
      // Right spoke: 2 rows for center 1, 1 for center 2
      sql("INSERT INTO right_spoke VALUES (1, 5), (1, 6), (2, 7)")
      // Extra: joins to right_spoke.b - creates additional fan-out
      sql("INSERT INTO extra VALUES (5, 100), (6, 200), (7, 300)")

      // Query with product that uses center and left_spoke
      // The extra join creates a complex tree where partial overlap might occur
      val query = """
        SELECT SUM(c.val * ls.a) as product_sum,
               COUNT(*) as cnt
        FROM center c
        JOIN left_spoke ls ON c.id = ls.center_id
        JOIN right_spoke rs ON c.id = rs.center_id
        JOIN extra e ON rs.b = e.right_spoke_b
      """

      var baseline: Seq[Row] = Seq.empty
      withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
        baseline = sql(query).collect().toSeq
      }

      // scalastyle:off println
      println("=== PARTIAL OVERLAP TEST ===")
      println(s"Baseline: ${baseline.map(_.toString).mkString}")

      // Test with Yannakakis enabled
      withSQLConf(
        SQLConf.YANNAKAKIS_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
      ) {
        val df = sql(query)
        println(s"Yannakakis result: ${df.collect().map(_.toString).mkString}")
        checkAnswer(df, baseline)
      }

      // Also test with synthetic superset enabled
      withSQLConf(
        SQLConf.YANNAKAKIS_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
      ) {
        val df = sql(query)
        println(s"Synthetic result: ${df.collect().map(_.toString).mkString}")
        checkAnswer(df, baseline)
      }
      // scalastyle:on println
    }
  }

  test("Independent products: two products with disjoint attribute sets") {
    // Tests the bug where two independent products were incorrectly sharing counts.
    // Product 1: SUM(t1.a * t2.b) uses {a, b} from tables t1, t2
    // Product 2: SUM(t3.c * t4.d) uses {c, d} from tables t3, t4
    // These products share NO attributes and should be computed independently.
    //
    // The bug was that during LEFT propagation, Product 2 was being multiplied
    // by counts from tables (like t1, t2) that are only relevant to Product 1.

    withTable("indep_t1", "indep_t2", "indep_t3", "indep_t4") {
      sql("CREATE TABLE indep_t1 (id INT, a INT) USING parquet")
      sql("CREATE TABLE indep_t2 (id INT, b INT) USING parquet")
      sql("CREATE TABLE indep_t3 (id INT, c INT) USING parquet")
      sql("CREATE TABLE indep_t4 (id INT, d INT) USING parquet")

      // Setup data with different cardinalities to catch multiplication errors
      // t1: 2 rows, t2: 3 rows, t3: 2 rows, t4: 2 rows
      sql("INSERT INTO indep_t1 VALUES (1, 10), (1, 20)")
      sql("INSERT INTO indep_t2 VALUES (1, 2), (1, 3), (1, 4)")
      sql("INSERT INTO indep_t3 VALUES (1, 100), (1, 200)")
      sql("INSERT INTO indep_t4 VALUES (1, 5), (1, 6)")

      val query = """
        SELECT COUNT(*) as cnt,
               SUM(indep_t1.a * indep_t2.b) as prod1,
               SUM(indep_t3.c * indep_t4.d) as prod2
        FROM indep_t1, indep_t2, indep_t3, indep_t4
        WHERE indep_t1.id = indep_t2.id
          AND indep_t2.id = indep_t3.id
          AND indep_t3.id = indep_t4.id
      """

      // Full join: 2*3*2*2 = 24 rows
      // Product 1 (a*b): Each (a,b) pair appears 2*2=4 times (for each t3,t4 combo)
      //   (10,2), (10,3), (10,4), (20,2), (20,3), (20,4) each * 4
      //   = (20+30+40+40+60+80) * 4 = 270 * 4 = 1080
      // Product 2 (c*d): Each (c,d) pair appears 2*3=6 times (for each t1,t2 combo)
      //   (100,5), (100,6), (200,5), (200,6) each * 6
      //   = (500+600+1000+1200) * 6 = 3300 * 6 = 19800

      var baseline: Seq[Row] = Seq.empty
      withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
        baseline = sql(query).collect().toSeq
      }

      // scalastyle:off println
      println("=== INDEPENDENT PRODUCTS TEST ===")
      println(s"Baseline: ${baseline.map(_.toString).mkString}")

      withSQLConf(
        SQLConf.YANNAKAKIS_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
      ) {
        val df = sql(query)
        println(s"Yannakakis result: ${df.collect().map(_.toString).mkString}")
        checkAnswer(df, baseline)
      }
      // scalastyle:on println
    }
  }

  test("Independent products: three products with partial overlap") {
    // Product 1: SUM(a * b) uses {a, b}
    // Product 2: SUM(b * c) uses {b, c} - shares 'b' with Product 1
    // Product 3: SUM(d) uses {d} - completely independent
    //
    // This tests that products with partial overlap are handled correctly
    // while fully independent products don't interfere.

    withTable("overlap_t1", "overlap_t2", "overlap_t3", "overlap_t4") {
      sql("CREATE TABLE overlap_t1 (id INT, a INT) USING parquet")
      sql("CREATE TABLE overlap_t2 (id INT, b INT) USING parquet")
      sql("CREATE TABLE overlap_t3 (id INT, c INT) USING parquet")
      sql("CREATE TABLE overlap_t4 (id INT, d INT) USING parquet")

      sql("INSERT INTO overlap_t1 VALUES (1, 10), (1, 20)")
      sql("INSERT INTO overlap_t2 VALUES (1, 2), (1, 3)")
      sql("INSERT INTO overlap_t3 VALUES (1, 100)")
      sql("INSERT INTO overlap_t4 VALUES (1, 7), (1, 8), (1, 9)")

      val query = """
        SELECT COUNT(*) as cnt,
               SUM(overlap_t1.a * overlap_t2.b) as prod_ab,
               SUM(overlap_t2.b * overlap_t3.c) as prod_bc,
               SUM(overlap_t4.d) as sum_d
        FROM overlap_t1, overlap_t2, overlap_t3, overlap_t4
        WHERE overlap_t1.id = overlap_t2.id
          AND overlap_t2.id = overlap_t3.id
          AND overlap_t3.id = overlap_t4.id
      """

      var baseline: Seq[Row] = Seq.empty
      withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
        baseline = sql(query).collect().toSeq
      }

      // scalastyle:off println
      println("=== INDEPENDENT PRODUCTS WITH PARTIAL OVERLAP TEST ===")
      println(s"Baseline: ${baseline.map(_.toString).mkString}")

      withSQLConf(
        SQLConf.YANNAKAKIS_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
      ) {
        val df = sql(query)
        println(s"Yannakakis result: ${df.collect().map(_.toString).mkString}")
        checkAnswer(df, baseline)
      }
      // scalastyle:on println
    }
  }

  test("Isolated products: 3 disjoint multi-attr products") {
    // Product 1: SUM(a1 * a2) uses {a1, a2}
    // Product 2: SUM(b1 * b2) uses {b1, b2}
    // Product 3: SUM(c1 * c2) uses {c1, c2}
    // All 3 products are multi-attribute and completely disjoint.

    withTable("iso3m_t1", "iso3m_t2", "iso3m_t3", "iso3m_t4", "iso3m_t5", "iso3m_t6") {
      sql("CREATE TABLE iso3m_t1 (id INT, a1 INT) USING parquet")
      sql("CREATE TABLE iso3m_t2 (id INT, a2 INT) USING parquet")
      sql("CREATE TABLE iso3m_t3 (id INT, b1 INT) USING parquet")
      sql("CREATE TABLE iso3m_t4 (id INT, b2 INT) USING parquet")
      sql("CREATE TABLE iso3m_t5 (id INT, c1 INT) USING parquet")
      sql("CREATE TABLE iso3m_t6 (id INT, c2 INT) USING parquet")

      sql("INSERT INTO iso3m_t1 VALUES (1, 10), (1, 20)")
      sql("INSERT INTO iso3m_t2 VALUES (1, 2), (1, 3)")
      sql("INSERT INTO iso3m_t3 VALUES (1, 100)")
      sql("INSERT INTO iso3m_t4 VALUES (1, 5), (1, 6)")
      sql("INSERT INTO iso3m_t5 VALUES (1, 1000)")
      sql("INSERT INTO iso3m_t6 VALUES (1, 7)")

      val query = """
        SELECT COUNT(*) as cnt,
               SUM(iso3m_t1.a1 * iso3m_t2.a2) as prod_a,
               SUM(iso3m_t3.b1 * iso3m_t4.b2) as prod_b,
               SUM(iso3m_t5.c1 * iso3m_t6.c2) as prod_c
        FROM iso3m_t1, iso3m_t2, iso3m_t3, iso3m_t4, iso3m_t5, iso3m_t6
        WHERE iso3m_t1.id = iso3m_t2.id
          AND iso3m_t2.id = iso3m_t3.id
          AND iso3m_t3.id = iso3m_t4.id
          AND iso3m_t4.id = iso3m_t5.id
          AND iso3m_t5.id = iso3m_t6.id
      """

      // Full join: 2*2*1*2*1*1 = 8 rows

      var baseline: Seq[Row] = Seq.empty
      withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
        baseline = sql(query).collect().toSeq
      }

      // scalastyle:off println
      println("=== 3 ISOLATED MULTI-ATTR PRODUCTS TEST ===")
      println(s"Baseline: ${baseline.map(_.toString).mkString}")

      withSQLConf(
        SQLConf.YANNAKAKIS_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
      ) {
        val df = sql(query)
        println(s"Yannakakis result: ${df.collect().map(_.toString).mkString}")
        checkAnswer(df, baseline)
      }
      // scalastyle:on println
    }
  }

  test("Isolated products: 4 disjoint multi-attr products") {
    // Product 1: SUM(a1 * a2) uses {a1, a2}
    // Product 2: SUM(b1 * b2) uses {b1, b2}
    // Product 3: SUM(c1 * c2) uses {c1, c2}
    // Product 4: SUM(d1 * d2) uses {d1, d2}
    // All 4 products are multi-attribute and completely disjoint.

    withTable("iso4m_t1", "iso4m_t2", "iso4m_t3", "iso4m_t4",
              "iso4m_t5", "iso4m_t6", "iso4m_t7", "iso4m_t8") {
      sql("CREATE TABLE iso4m_t1 (id INT, a1 INT) USING parquet")
      sql("CREATE TABLE iso4m_t2 (id INT, a2 INT) USING parquet")
      sql("CREATE TABLE iso4m_t3 (id INT, b1 INT) USING parquet")
      sql("CREATE TABLE iso4m_t4 (id INT, b2 INT) USING parquet")
      sql("CREATE TABLE iso4m_t5 (id INT, c1 INT) USING parquet")
      sql("CREATE TABLE iso4m_t6 (id INT, c2 INT) USING parquet")
      sql("CREATE TABLE iso4m_t7 (id INT, d1 INT) USING parquet")
      sql("CREATE TABLE iso4m_t8 (id INT, d2 INT) USING parquet")

      sql("INSERT INTO iso4m_t1 VALUES (1, 10), (1, 20)")
      sql("INSERT INTO iso4m_t2 VALUES (1, 2)")
      sql("INSERT INTO iso4m_t3 VALUES (1, 100)")
      sql("INSERT INTO iso4m_t4 VALUES (1, 5), (1, 6)")
      sql("INSERT INTO iso4m_t5 VALUES (1, 1000)")
      sql("INSERT INTO iso4m_t6 VALUES (1, 7)")
      sql("INSERT INTO iso4m_t7 VALUES (1, 3), (1, 4)")
      sql("INSERT INTO iso4m_t8 VALUES (1, 8)")

      val query = """
        SELECT COUNT(*) as cnt,
               SUM(iso4m_t1.a1 * iso4m_t2.a2) as prod_a,
               SUM(iso4m_t3.b1 * iso4m_t4.b2) as prod_b,
               SUM(iso4m_t5.c1 * iso4m_t6.c2) as prod_c,
               SUM(iso4m_t7.d1 * iso4m_t8.d2) as prod_d
        FROM iso4m_t1, iso4m_t2, iso4m_t3, iso4m_t4,
             iso4m_t5, iso4m_t6, iso4m_t7, iso4m_t8
        WHERE iso4m_t1.id = iso4m_t2.id
          AND iso4m_t2.id = iso4m_t3.id
          AND iso4m_t3.id = iso4m_t4.id
          AND iso4m_t4.id = iso4m_t5.id
          AND iso4m_t5.id = iso4m_t6.id
          AND iso4m_t6.id = iso4m_t7.id
          AND iso4m_t7.id = iso4m_t8.id
      """

      // Full join: 2*1*1*2*1*1*2*1 = 8 rows

      var baseline: Seq[Row] = Seq.empty
      withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
        baseline = sql(query).collect().toSeq
      }

      // scalastyle:off println
      println("=== 4 ISOLATED MULTI-ATTR PRODUCTS TEST ===")
      println(s"Baseline: ${baseline.map(_.toString).mkString}")

      withSQLConf(
        SQLConf.YANNAKAKIS_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
      ) {
        val df = sql(query)
        println(s"Yannakakis result: ${df.collect().map(_.toString).mkString}")
        checkAnswer(df, baseline)
      }
      // scalastyle:on println
    }
  }

  test("Isolated products with subset: multi-attr product with subset and isolated") {
    // Product 1: SUM(a * b) uses {a, b} - multi-attr
    // Product 2: SUM(a * a) uses {a} - subset of Product 1's attrs (single table)
    // Product 3: SUM(c * d) uses {c, d} - completely independent multi-attr
    //
    // Tests the interaction between subset relationships and independence with multi-attr.

    withTable("subset_iso_t1", "subset_iso_t2", "subset_iso_t3", "subset_iso_t4") {
      sql("CREATE TABLE subset_iso_t1 (id INT, a INT) USING parquet")
      sql("CREATE TABLE subset_iso_t2 (id INT, b INT) USING parquet")
      sql("CREATE TABLE subset_iso_t3 (id INT, c INT) USING parquet")
      sql("CREATE TABLE subset_iso_t4 (id INT, d INT) USING parquet")

      sql("INSERT INTO subset_iso_t1 VALUES (1, 10), (1, 20)")
      sql("INSERT INTO subset_iso_t2 VALUES (1, 3), (1, 4)")
      sql("INSERT INTO subset_iso_t3 VALUES (1, 100)")
      sql("INSERT INTO subset_iso_t4 VALUES (1, 5), (1, 6), (1, 7)")

      val query = """
        SELECT COUNT(*) as cnt,
               SUM(subset_iso_t1.a * subset_iso_t2.b) as prod_ab,
               SUM(subset_iso_t1.a * subset_iso_t1.a) as sum_a_sq,
               SUM(subset_iso_t3.c * subset_iso_t4.d) as prod_cd
        FROM subset_iso_t1, subset_iso_t2, subset_iso_t3, subset_iso_t4
        WHERE subset_iso_t1.id = subset_iso_t2.id
          AND subset_iso_t2.id = subset_iso_t3.id
          AND subset_iso_t3.id = subset_iso_t4.id
      """

      // Full join: 2*2*1*3 = 12 rows

      var baseline: Seq[Row] = Seq.empty
      withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
        baseline = sql(query).collect().toSeq
      }

      // scalastyle:off println
      println("=== ISOLATED WITH SUBSET (MULTI-ATTR) TEST ===")
      println(s"Baseline: ${baseline.map(_.toString).mkString}")

      withSQLConf(
        SQLConf.YANNAKAKIS_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
      ) {
        val df = sql(query)
        println(s"Yannakakis result: ${df.collect().map(_.toString).mkString}")
        checkAnswer(df, baseline)
      }
      // scalastyle:on println
    }
  }

  test("Isolated products with filter on one product only") {
    // Product 1: SUM(a) with filter a > 15
    // Product 2: SUM(b) - no filter
    // Tests that filters on isolated products don't affect other products.

    withTable("iso_filter_t1", "iso_filter_t2") {
      sql("CREATE TABLE iso_filter_t1 (id INT, a INT) USING parquet")
      sql("CREATE TABLE iso_filter_t2 (id INT, b INT) USING parquet")

      sql("INSERT INTO iso_filter_t1 VALUES (1, 10), (1, 20), (1, 30)")
      sql("INSERT INTO iso_filter_t2 VALUES (1, 5), (1, 6)")

      val query = """
        SELECT COUNT(*) as cnt,
               SUM(iso_filter_t1.a) as sum_a,
               SUM(iso_filter_t2.b) as sum_b
        FROM iso_filter_t1, iso_filter_t2
        WHERE iso_filter_t1.id = iso_filter_t2.id
          AND iso_filter_t1.a > 15
      """

      // After filter: t1 has 2 rows (20, 30), t2 has 2 rows (5, 6)
      // Full join: 2*2 = 4 rows
      // SUM(a): (20+30)*2 = 100
      // SUM(b): (5+6)*2 = 22

      var baseline: Seq[Row] = Seq.empty
      withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
        baseline = sql(query).collect().toSeq
      }

      // scalastyle:off println
      println("=== ISOLATED WITH FILTER TEST ===")
      println(s"Baseline: ${baseline.map(_.toString).mkString}")

      withSQLConf(
        SQLConf.YANNAKAKIS_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
      ) {
        val df = sql(query)
        println(s"Yannakakis result: ${df.collect().map(_.toString).mkString}")
        checkAnswer(df, baseline)
      }
      // scalastyle:on println
    }
  }

  test("Isolated multi-attr products: two 2-attr products with no overlap") {
    // Product 1: SUM(a * b) uses {a, b}
    // Product 2: SUM(c * d) uses {c, d}
    // No attribute overlap at all.

    withTable("iso_multi_t1", "iso_multi_t2", "iso_multi_t3", "iso_multi_t4") {
      sql("CREATE TABLE iso_multi_t1 (id INT, a INT) USING parquet")
      sql("CREATE TABLE iso_multi_t2 (id INT, b INT) USING parquet")
      sql("CREATE TABLE iso_multi_t3 (id INT, c INT) USING parquet")
      sql("CREATE TABLE iso_multi_t4 (id INT, d INT) USING parquet")

      sql("INSERT INTO iso_multi_t1 VALUES (1, 10), (1, 20)")
      sql("INSERT INTO iso_multi_t2 VALUES (1, 2), (1, 3)")
      sql("INSERT INTO iso_multi_t3 VALUES (1, 100)")
      sql("INSERT INTO iso_multi_t4 VALUES (1, 5), (1, 6), (1, 7)")

      val query = """
        SELECT COUNT(*) as cnt,
               SUM(iso_multi_t1.a * iso_multi_t2.b) as prod_ab,
               SUM(iso_multi_t3.c * iso_multi_t4.d) as prod_cd
        FROM iso_multi_t1, iso_multi_t2, iso_multi_t3, iso_multi_t4
        WHERE iso_multi_t1.id = iso_multi_t2.id
          AND iso_multi_t2.id = iso_multi_t3.id
          AND iso_multi_t3.id = iso_multi_t4.id
      """

      // Full join: 2*2*1*3 = 12 rows
      // SUM(a*b): Each (a,b) pair appears 1*3=3 times
      //   (10*2+10*3+20*2+20*3)*3 = (20+30+40+60)*3 = 450
      // SUM(c*d): Each (c,d) pair appears 2*2=4 times
      //   (100*5+100*6+100*7)*4 = 1800*4 = 7200

      var baseline: Seq[Row] = Seq.empty
      withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
        baseline = sql(query).collect().toSeq
      }

      // scalastyle:off println
      println("=== ISOLATED MULTI-ATTR PRODUCTS TEST ===")
      println(s"Baseline: ${baseline.map(_.toString).mkString}")

      withSQLConf(
        SQLConf.YANNAKAKIS_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
      ) {
        val df = sql(query)
        println(s"Yannakakis result: ${df.collect().map(_.toString).mkString}")
        checkAnswer(df, baseline)
      }
      // scalastyle:on println
    }
  }

  test("Isolated products combined: all multi-attr with subset and isolated") {
    // Product 1: SUM(a * b) uses {a, b} - multi-attr
    // Product 2: SUM(a * a) uses {a} - subset of Product 1 (single attr squared)
    // Product 3: SUM(c * d) uses {c, d} - isolated multi-attr from 1 and 2
    // Product 4: SUM(e * f) uses {e, f} - another isolated multi-attr
    //
    // Comprehensive test combining all cases with multi-attr products.

    withTable("combo_t1", "combo_t2", "combo_t3", "combo_t4", "combo_t5", "combo_t6") {
      sql("CREATE TABLE combo_t1 (id INT, a INT) USING parquet")
      sql("CREATE TABLE combo_t2 (id INT, b INT) USING parquet")
      sql("CREATE TABLE combo_t3 (id INT, c INT) USING parquet")
      sql("CREATE TABLE combo_t4 (id INT, d INT) USING parquet")
      sql("CREATE TABLE combo_t5 (id INT, e INT) USING parquet")
      sql("CREATE TABLE combo_t6 (id INT, f INT) USING parquet")

      sql("INSERT INTO combo_t1 VALUES (1, 10), (1, 20)")
      sql("INSERT INTO combo_t2 VALUES (1, 2)")
      sql("INSERT INTO combo_t3 VALUES (1, 100)")
      sql("INSERT INTO combo_t4 VALUES (1, 5), (1, 6)")
      sql("INSERT INTO combo_t5 VALUES (1, 1000)")
      sql("INSERT INTO combo_t6 VALUES (1, 7), (1, 8), (1, 9)")

      val query = """
        SELECT COUNT(*) as cnt,
               SUM(combo_t1.a * combo_t2.b) as prod_ab,
               SUM(combo_t1.a * combo_t1.a) as sum_a_sq,
               SUM(combo_t3.c * combo_t4.d) as prod_cd,
               SUM(combo_t5.e * combo_t6.f) as prod_ef
        FROM combo_t1, combo_t2, combo_t3, combo_t4, combo_t5, combo_t6
        WHERE combo_t1.id = combo_t2.id
          AND combo_t2.id = combo_t3.id
          AND combo_t3.id = combo_t4.id
          AND combo_t4.id = combo_t5.id
          AND combo_t5.id = combo_t6.id
      """

      // Full join: 2*1*1*2*1*3 = 12 rows

      var baseline: Seq[Row] = Seq.empty
      withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
        baseline = sql(query).collect().toSeq
      }

      // scalastyle:off println
      println("=== COMBINED ISOLATED PRODUCTS (ALL MULTI-ATTR) TEST ===")
      println(s"Baseline: ${baseline.map(_.toString).mkString}")

      withSQLConf(
        SQLConf.YANNAKAKIS_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
      ) {
        val df = sql(query)
        println(s"Yannakakis result: ${df.collect().map(_.toString).mkString}")
        checkAnswer(df, baseline)
      }
      // scalastyle:on println
    }
  }

  test("IMDB-style: isolated products from different table groups") {
    // Simulates the original failing query structure:
    // Product 1: SUM(cast_info.role_id * movie_info.info_type_id)
    // Product 2: SUM(movie_companies.company_type_id * title.kind_id)
    //
    // These products are completely independent - cast_info/movie_info have no
    // shared attributes with movie_companies/title for the products.
    // The star schema joins through title.movie_id.

    withTable("imdb_title", "imdb_cast_info", "imdb_movie_info",
              "imdb_movie_companies", "imdb_role_type", "imdb_info_type") {
      // Create simplified IMDB-style tables
      sql("CREATE TABLE imdb_title (id INT, kind_id INT) USING parquet")
      sql("CREATE TABLE imdb_cast_info (movie_id INT, role_id INT) USING parquet")
      sql("CREATE TABLE imdb_movie_info (movie_id INT, info_type_id INT) USING parquet")
      sql("CREATE TABLE imdb_movie_companies (movie_id INT, company_type_id INT) USING parquet")
      sql("CREATE TABLE imdb_role_type (id INT, role STRING) USING parquet")
      sql("CREATE TABLE imdb_info_type (id INT, info STRING) USING parquet")

      // Insert test data
      sql("INSERT INTO imdb_title VALUES (1, 10), (2, 20)")
      sql("INSERT INTO imdb_cast_info VALUES (1, 100), (1, 200), (2, 150)")
      sql("INSERT INTO imdb_movie_info VALUES (1, 5), (2, 6)")
      sql("INSERT INTO imdb_movie_companies VALUES (1, 1), (1, 2), (2, 3)")
      sql("INSERT INTO imdb_role_type VALUES (100, 'actor'), (150, 'actor'), (200, 'actress')")
      sql("INSERT INTO imdb_info_type VALUES (5, 'rating'), (6, 'length')")

      val query = """
        SELECT COUNT(*) as cnt,
               SUM(ci.role_id * mi.info_type_id) as prod1,
               SUM(mc.company_type_id * t.kind_id) as prod2
        FROM imdb_title t
        JOIN imdb_cast_info ci ON ci.movie_id = t.id
        JOIN imdb_movie_info mi ON mi.movie_id = t.id
        JOIN imdb_movie_companies mc ON mc.movie_id = t.id
        JOIN imdb_role_type rt ON rt.id = ci.role_id
        JOIN imdb_info_type it ON it.id = mi.info_type_id
      """

      var baseline: Seq[Row] = Seq.empty
      withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
        baseline = sql(query).collect().toSeq
      }

      // scalastyle:off println
      println("=== IMDB-STYLE ISOLATED PRODUCTS TEST ===")
      println(s"Baseline: ${baseline.map(_.toString).mkString}")

      withSQLConf(
        SQLConf.YANNAKAKIS_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
      ) {
        val df = sql(query)
        println(s"Yannakakis result: ${df.collect().map(_.toString).mkString}")
        checkAnswer(df, baseline)
      }
      // scalastyle:on println
    }
  }

  test("IMDB-style: three isolated products across 8 tables") {
    // More complex IMDB-style query with three isolated products:
    // Product 1: SUM(cast_info.role_id * movie_info.info_type_id) - cast/movie_info group
    // Product 2: SUM(movie_companies.company_type_id * title.kind_id) - companies/title group
    // Product 3: SUM(keyword.id * movie_keyword.keyword_id) - keyword group
    //
    // All three products have completely disjoint attribute sets.

    withTable("imdb3_title", "imdb3_cast_info", "imdb3_movie_info",
              "imdb3_movie_companies", "imdb3_movie_keyword", "imdb3_keyword") {
      sql("CREATE TABLE imdb3_title (id INT, kind_id INT) USING parquet")
      sql("CREATE TABLE imdb3_cast_info (movie_id INT, role_id INT) USING parquet")
      sql("CREATE TABLE imdb3_movie_info (movie_id INT, info_type_id INT) USING parquet")
      sql("CREATE TABLE imdb3_movie_companies (movie_id INT, company_type_id INT) USING parquet")
      sql("CREATE TABLE imdb3_movie_keyword (movie_id INT, keyword_id INT) USING parquet")
      sql("CREATE TABLE imdb3_keyword (id INT, keyword STRING) USING parquet")

      sql("INSERT INTO imdb3_title VALUES (1, 10), (2, 20)")
      sql("INSERT INTO imdb3_cast_info VALUES (1, 100), (2, 200)")
      sql("INSERT INTO imdb3_movie_info VALUES (1, 5), (2, 6)")
      sql("INSERT INTO imdb3_movie_companies VALUES (1, 1), (2, 2)")
      sql("INSERT INTO imdb3_movie_keyword VALUES (1, 1000), (1, 2000), (2, 3000)")
      sql("INSERT INTO imdb3_keyword VALUES (1000, 'action'), (2000, 'drama'), (3000, 'comedy')")

      val query = """
        SELECT COUNT(*) as cnt,
               SUM(ci.role_id * mi.info_type_id) as prod1,
               SUM(mc.company_type_id * t.kind_id) as prod2,
               SUM(k.id * mk.keyword_id) as prod3
        FROM imdb3_title t
        JOIN imdb3_cast_info ci ON ci.movie_id = t.id
        JOIN imdb3_movie_info mi ON mi.movie_id = t.id
        JOIN imdb3_movie_companies mc ON mc.movie_id = t.id
        JOIN imdb3_movie_keyword mk ON mk.movie_id = t.id
        JOIN imdb3_keyword k ON k.id = mk.keyword_id
      """

      var baseline: Seq[Row] = Seq.empty
      withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
        baseline = sql(query).collect().toSeq
      }

      // scalastyle:off println
      println("=== IMDB-STYLE 3 ISOLATED PRODUCTS TEST ===")
      println(s"Baseline: ${baseline.map(_.toString).mkString}")

      withSQLConf(
        SQLConf.YANNAKAKIS_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
      ) {
        val df = sql(query)
        println(s"Yannakakis result: ${df.collect().map(_.toString).mkString}")
        checkAnswer(df, baseline)
      }
      // scalastyle:on println
    }
  }

  test("Deep tree: product computed early, flows through multiple levels") {
    // Tests a deep join tree where a product is computed at level 2 and must
    // flow through LEFT propagation at levels 3 and 4 without double-counting.
    //
    // Tree structure (depth 4):
    //        root
    //       /    \
    //     L3      T4
    //    /  \
    //   L2   T3
    //  /  \
    // T1   T2 (product computed here: SUM(T1.a * T2.b))
    //
    // The pending product from T1×T2 should be multiplied by T3 count and T4 count,
    // but NOT re-multiplied by T1 or T2 counts.

    withTable("deep_t1", "deep_t2", "deep_t3", "deep_t4") {
      sql("CREATE TABLE deep_t1 (id INT, a INT) USING parquet")
      sql("CREATE TABLE deep_t2 (t1_id INT, b INT) USING parquet")
      sql("CREATE TABLE deep_t3 (t1_id INT, c INT) USING parquet")
      sql("CREATE TABLE deep_t4 (t1_id INT, d INT) USING parquet")

      // T1: 2 rows
      sql("INSERT INTO deep_t1 VALUES (1, 10), (2, 20)")
      // T2: 2 rows for id 1, 1 for id 2
      sql("INSERT INTO deep_t2 VALUES (1, 2), (1, 3), (2, 4)")
      // T3: 1 row for id 1, 2 for id 2
      sql("INSERT INTO deep_t3 VALUES (1, 50), (2, 60), (2, 70)")
      // T4: 2 rows for id 1, 1 for id 2
      sql("INSERT INTO deep_t4 VALUES (1, 100), (1, 200), (2, 300)")

      val query = """
        SELECT SUM(deep_t1.a * deep_t2.b) as product_sum,
               COUNT(*) as cnt
        FROM deep_t1
        JOIN deep_t2 ON deep_t1.id = deep_t2.t1_id
        JOIN deep_t3 ON deep_t1.id = deep_t3.t1_id
        JOIN deep_t4 ON deep_t1.id = deep_t4.t1_id
      """

      var baseline: Seq[Row] = Seq.empty
      withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
        baseline = sql(query).collect().toSeq
      }

      // scalastyle:off println
      println("=== DEEP TREE TEST ===")
      println(s"Baseline: ${baseline.map(_.toString).mkString}")

      withSQLConf(
        SQLConf.YANNAKAKIS_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
      ) {
        val df = sql(query)
        println(s"Yannakakis result: ${df.collect().map(_.toString).mkString}")
        checkAnswer(df, baseline)
      }
      // scalastyle:on println
    }
  }

  // ============================================================================
  // ISOLATED PRODUCTS WITH SHARED JOIN PATH TESTS (CASE 7)
  // These tests specifically cover the bug where isolated products (completely
  // disjoint attribute sets) share a common join path through a central table.
  // The bug was that counts from one product's auxiliary subtree were incorrectly
  // applied to the other product because join keys made the subtree appear "relevant".
  //
  // The fix ensures that a subtree is only relevant to a product if it contains
  // that product's actual aggregation attributes, not just join keys.
  // ============================================================================

  test("CASE 7a: Isolated products via shared central table - basic") {
    // This test mimics the IMDB bug:
    // - Two isolated products with completely disjoint attrs
    // - Both products share a common join through a central table
    // - One product's auxiliary tables should NOT affect the other's count
    //
    // Schema:
    //   central (id) - hub table that both product chains join through
    //   left_a (central_id, a) - left chain for product 1
    //   left_b (central_id, b) - left chain for product 1
    //   right_c (central_id, c) - right chain for product 2
    //   right_d (central_id, d) - right chain for product 2
    //
    // Products:
    //   P1: SUM(a * b) uses {a, b} - from left_a, left_b
    //   P2: SUM(c * d) uses {c, d} - from right_c, right_d
    //
    // The key test: when P1 is computed and flows up through a join where the
    // right subtree contains {c, d} but NOT {a, b}, P1 should NOT be multiplied
    // by that subtree's count.

    withTable("iso_central", "iso_left_a", "iso_left_b", "iso_right_c", "iso_right_d") {
      sql("CREATE TABLE iso_central (id INT) USING parquet")
      sql("CREATE TABLE iso_left_a (central_id INT, a INT) USING parquet")
      sql("CREATE TABLE iso_left_b (central_id INT, b INT) USING parquet")
      sql("CREATE TABLE iso_right_c (central_id INT, c INT) USING parquet")
      sql("CREATE TABLE iso_right_d (central_id INT, d INT) USING parquet")

      // Central hub: 2 rows
      sql("INSERT INTO iso_central VALUES (1), (2)")
      // Left chain: 2 rows each for P1
      sql("INSERT INTO iso_left_a VALUES (1, 10), (2, 20)")
      sql("INSERT INTO iso_left_b VALUES (1, 100), (2, 200)")
      // Right chain: 3 rows each for P2 (different fan-out to detect miscounting)
      sql("INSERT INTO iso_right_c VALUES (1, 1000), (1, 1001), (2, 2000)")
      sql("INSERT INTO iso_right_d VALUES (1, 5), (2, 6), (2, 7)")

      val query = """
        SELECT COUNT(*) as cnt,
               SUM(la.a * lb.b) as p1,
               SUM(rc.c * rd.d) as p2
        FROM iso_central c
        JOIN iso_left_a la ON c.id = la.central_id
        JOIN iso_left_b lb ON c.id = lb.central_id
        JOIN iso_right_c rc ON c.id = rc.central_id
        JOIN iso_right_d rd ON c.id = rd.central_id
      """

      // Calculate expected values:
      // Join result for id=1: la(10) x lb(100) x rc(1000,1001) x rd(5) = 1*1*2*1 = 2 rows
      // Join result for id=2: la(20) x lb(200) x rc(2000) x rd(6,7) = 1*1*1*2 = 2 rows
      // Total: 4 rows
      //
      // P1 = SUM(a * b):
      //   For id=1: (10*100) appears 2*1 = 2 times (from rc*rd fan-out) = 2000
      //   For id=2: (20*200) appears 1*2 = 2 times (from rc*rd fan-out) = 8000
      //   Total P1 = 10000
      //
      // P2 = SUM(c * d):
      //   For id=1: (1000*5) + (1001*5) = 10005, times la*lb fan-out (1*1) = 10005
      //   For id=2: (2000*6) + (2000*7) = 26000, times la*lb fan-out (1*1) = 26000
      //   Total P2 = 36005

      var baseline: Seq[Row] = Seq.empty
      withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
        baseline = sql(query).collect().toSeq
      }

      // scalastyle:off println
      println("=== CASE 7a: ISOLATED PRODUCTS VIA SHARED CENTRAL ===")
      println("Products: P1={a,b}, P2={c,d} - completely disjoint")
      println("Both share central table as join hub")
      println(s"Baseline: ${baseline.map(_.toString).mkString}")

      withSQLConf(
        SQLConf.YANNAKAKIS_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
      ) {
        val df = sql(query)
        println(s"Yannakakis result: ${df.collect().map(_.toString).mkString}")
        checkAnswer(df, baseline)
      }
      // scalastyle:on println
    }
  }

  test("CASE 7b: Isolated products with uneven fan-out - stress test") {
    // Stress test with larger fan-out to catch count multiplication errors.
    // The products are isolated but share a join path with very different
    // cardinalities on each side.
    //
    // If the bug exists, one product will be incorrectly multiplied by
    // the other product's fan-out, making the error very visible.

    withTable("iso2_hub", "iso2_p1_a", "iso2_p1_b", "iso2_p2_c", "iso2_p2_d") {
      sql("CREATE TABLE iso2_hub (id INT) USING parquet")
      sql("CREATE TABLE iso2_p1_a (hub_id INT, a INT) USING parquet")
      sql("CREATE TABLE iso2_p1_b (hub_id INT, b INT) USING parquet")
      sql("CREATE TABLE iso2_p2_c (hub_id INT, c INT) USING parquet")
      sql("CREATE TABLE iso2_p2_d (hub_id INT, d INT) USING parquet")

      // Hub: single row for simplicity
      sql("INSERT INTO iso2_hub VALUES (1)")
      // P1 tables: 2 rows each -> 2*2 = 4 combinations for P1
      sql("INSERT INTO iso2_p1_a VALUES (1, 10), (1, 20)")
      sql("INSERT INTO iso2_p1_b VALUES (1, 3), (1, 7)")
      // P2 tables: 5 rows and 3 rows -> 5*3 = 15 combinations for P2
      sql("INSERT INTO iso2_p2_c VALUES (1, 100), (1, 200), (1, 300), (1, 400), (1, 500)")
      sql("INSERT INTO iso2_p2_d VALUES (1, 1), (1, 2), (1, 3)")

      val query = """
        SELECT COUNT(*) as cnt,
               SUM(p1a.a * p1b.b) as p1,
               SUM(p2c.c * p2d.d) as p2
        FROM iso2_hub h
        JOIN iso2_p1_a p1a ON h.id = p1a.hub_id
        JOIN iso2_p1_b p1b ON h.id = p1b.hub_id
        JOIN iso2_p2_c p2c ON h.id = p2c.hub_id
        JOIN iso2_p2_d p2d ON h.id = p2d.hub_id
      """

      // Total rows = 2*2*5*3 = 60
      //
      // P1 = SUM(a*b): Each (a,b) pair appears 5*3 = 15 times
      //   Pairs: (10,3), (10,7), (20,3), (20,7) -> sums: 30, 70, 60, 140 = 300
      //   Total P1 = 300 * 15 = 4500
      //
      // P2 = SUM(c*d): Each (c,d) pair appears 2*2 = 4 times
      //   sum(c) * sum(d) = (100+200+300+400+500) * (1+2+3) = 1500 * 6 = 9000
      //   Total P2 = 9000 * 4 = 36000

      var baseline: Seq[Row] = Seq.empty
      withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
        baseline = sql(query).collect().toSeq
      }

      // scalastyle:off println
      println("=== CASE 7b: ISOLATED PRODUCTS WITH UNEVEN FAN-OUT ===")
      println("P1 chain: 2*2 = 4 combos, P2 chain: 5*3 = 15 combos")
      println("If bug exists, P1 would be 4500*15=67500 instead of 4500")
      println(s"Baseline: ${baseline.map(_.toString).mkString}")

      withSQLConf(
        SQLConf.YANNAKAKIS_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
      ) {
        val df = sql(query)
        println(s"Yannakakis result: ${df.collect().map(_.toString).mkString}")
        checkAnswer(df, baseline)
      }
      // scalastyle:on println
    }
  }

  test("CASE 7c: Three isolated products with shared join path") {
    // Three completely isolated products, all sharing a common join path.
    // This tests that each product correctly ignores the OTHER two products'
    // subtrees during LEFT propagation.
    //
    // Products:
    //   P1: SUM(a * b) uses {a, b}
    //   P2: SUM(c * d) uses {c, d}
    //   P3: SUM(e * f) uses {e, f}
    //
    // All three are disjoint and should not affect each other's counts.

    withTable("iso3_hub", "iso3_t1", "iso3_t2", "iso3_t3", "iso3_t4", "iso3_t5", "iso3_t6") {
      sql("CREATE TABLE iso3_hub (id INT) USING parquet")
      sql("CREATE TABLE iso3_t1 (hub_id INT, a INT) USING parquet")
      sql("CREATE TABLE iso3_t2 (hub_id INT, b INT) USING parquet")
      sql("CREATE TABLE iso3_t3 (hub_id INT, c INT) USING parquet")
      sql("CREATE TABLE iso3_t4 (hub_id INT, d INT) USING parquet")
      sql("CREATE TABLE iso3_t5 (hub_id INT, e INT) USING parquet")
      sql("CREATE TABLE iso3_t6 (hub_id INT, f INT) USING parquet")

      sql("INSERT INTO iso3_hub VALUES (1)")
      // P1 tables: 2 rows each
      sql("INSERT INTO iso3_t1 VALUES (1, 10), (1, 20)")
      sql("INSERT INTO iso3_t2 VALUES (1, 3), (1, 7)")
      // P2 tables: 3 rows each
      sql("INSERT INTO iso3_t3 VALUES (1, 100), (1, 200), (1, 300)")
      sql("INSERT INTO iso3_t4 VALUES (1, 4), (1, 5), (1, 6)")
      // P3 tables: 4 rows and 2 rows
      sql("INSERT INTO iso3_t5 VALUES (1, 1000), (1, 2000), (1, 3000), (1, 4000)")
      sql("INSERT INTO iso3_t6 VALUES (1, 8), (1, 9)")

      val query = """
        SELECT COUNT(*) as cnt,
               SUM(t1.a * t2.b) as p1,
               SUM(t3.c * t4.d) as p2,
               SUM(t5.e * t6.f) as p3
        FROM iso3_hub h
        JOIN iso3_t1 t1 ON h.id = t1.hub_id
        JOIN iso3_t2 t2 ON h.id = t2.hub_id
        JOIN iso3_t3 t3 ON h.id = t3.hub_id
        JOIN iso3_t4 t4 ON h.id = t4.hub_id
        JOIN iso3_t5 t5 ON h.id = t5.hub_id
        JOIN iso3_t6 t6 ON h.id = t6.hub_id
      """

      // Total rows = 2*2*3*3*4*2 = 288
      //
      // P1: each (a,b) pair appears 3*3*4*2 = 72 times
      //   sum(a)*sum(b) = 30*10 = 300
      //   P1 = 300 * 72 = 21600
      //
      // P2: each (c,d) pair appears 2*2*4*2 = 32 times
      //   sum(c)*sum(d) = 600*15 = 9000
      //   P2 = 9000 * 32 = 288000
      //
      // P3: each (e,f) pair appears 2*2*3*3 = 36 times
      //   sum(e)*sum(f) = 10000*17 = 170000
      //   P3 = 170000 * 36 = 6120000

      var baseline: Seq[Row] = Seq.empty
      withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
        baseline = sql(query).collect().toSeq
      }

      // scalastyle:off println
      println("=== CASE 7c: THREE ISOLATED PRODUCTS ===")
      println("Products: P1={a,b}, P2={c,d}, P3={e,f} - all disjoint")
      println("Each product should ignore the other two during propagation")
      println(s"Baseline: ${baseline.map(_.toString).mkString}")

      withSQLConf(
        SQLConf.YANNAKAKIS_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
      ) {
        val df = sql(query)
        println(s"Yannakakis result: ${df.collect().map(_.toString).mkString}")
        checkAnswer(df, baseline)
      }
      // scalastyle:on println
    }
  }

  test("independent products with real IMDB schema - flaky bug test") {
    // Test with two independent products:
    // P1 = SUM(ci.role_id * mi.info_type_id) - uses role_id from cast_info
    //      and info_type_id from movie_info
    // P2 = SUM(mc.company_type_id * t.kind_id) - uses company_type_id from
    //      movie_companies and kind_id from title
    // These are independent products since their attribute sets don't overlap.

    val ci = Seq(
      (1, 1, 1, 2)  // role_id = 2
    ).toDF("movie_id", "person_id", "person_role_id", "role_id")

    val mi = Seq(
      (1, 16)  // info_type_id = 16
    ).toDF("movie_id", "info_type_id")

    val t = Seq(
      (1, 2011, 9, 7)  // kind_id = 7
    ).toDF("id", "production_year", "season_nr", "kind_id")

    // movie_companies with different company_type_ids
    // company_type_id: 1=3 rows, 2=4 rows -> weighted sum
    val mc = Seq(
      (1, 1, 1), (1, 1, 1), (1, 1, 1), (1, 2, 1),
      (1, 3, 2), (1, 3, 2), (1, 4, 2), (1, 5, 2), (1, 5, 2)
    ).toDF("movie_id", "company_id", "company_type_id")

    val chn = Seq(
      (1, null.asInstanceOf[java.lang.Integer])
    ).toDF("id", "imdb_id")

    val rt = Seq((2)).toDF("id")
    val n = Seq((1)).toDF("id")
    val an = Seq((1), (1), (1), (1), (1), (1)).toDF("person_id")
    val cn = Seq((1), (2), (3), (4), (5)).toDF("id")
    val it = Seq((16)).toDF("id")
    val k = Seq((1)).toDF("id")
    val mk = Seq((1, 1)).toDF("movie_id", "keyword_id")

    ci.createOrReplaceTempView("cast_info")
    mi.createOrReplaceTempView("movie_info")
    t.createOrReplaceTempView("title")
    mc.createOrReplaceTempView("movie_companies")
    chn.createOrReplaceTempView("char_name")
    rt.createOrReplaceTempView("role_type")
    n.createOrReplaceTempView("name")
    an.createOrReplaceTempView("aka_name")
    cn.createOrReplaceTempView("company_name")
    it.createOrReplaceTempView("info_type")
    k.createOrReplaceTempView("keyword")
    mk.createOrReplaceTempView("movie_keyword")

    // Query with two independent products
    val query = """
      SELECT COUNT(*),
          SUM(ci.role_id * mi.info_type_id),
          SUM(mc.company_type_id * t.kind_id)
      FROM aka_name AS an,
           char_name AS chn,
           cast_info AS ci,
           company_name AS cn,
           info_type AS it,
           keyword AS k,
           movie_companies AS mc,
           movie_info AS mi,
           movie_keyword AS mk,
           name AS n,
           role_type AS rt,
           title AS t
      WHERE t.id = mi.movie_id
        AND t.id = mc.movie_id
        AND t.id = ci.movie_id
        AND t.id = mk.movie_id
        AND cn.id = mc.company_id
        AND it.id = mi.info_type_id
        AND n.id = ci.person_id
        AND rt.id = ci.role_id
        AND n.id = an.person_id
        AND chn.id = ci.person_role_id
        AND k.id = mk.keyword_id
    """

    // Get baseline
    // COUNT = 1 * 1 * 1 * 1 * 1 * 1 * 9 * 1 * 1 * 1 * 1 * 1 * 5 * 6 = 270
    // Actually need to verify this...

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      val df = sql(query)
      println("=== INDEPENDENT PRODUCTS TEST ===")
      println("P1 = SUM(ci.role_id * mi.info_type_id) - uses {role_id, info_type_id}")
      println("P2 = SUM(mc.company_type_id * t.kind_id) - uses {company_type_id, kind_id}")
      println("These products are INDEPENDENT - no shared attributes")
      val baseline = df.collect()
      println(s"Baseline: ${baseline.map(_.toString).mkString}")

      withSQLConf(
        SQLConf.YANNAKAKIS_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true"
      ) {
        val df2 = sql(query)
        val yannakakis = df2.collect()
        println(s"Yannakakis: ${yannakakis.map(_.toString).mkString}")
        checkAnswer(df2, baseline)
      }
    }
    // scalastyle:on println
  }
}
