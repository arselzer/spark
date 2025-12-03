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
 *
 * MULTI-COUNT OPTIMIZATION CASES:
 * ===============================
 * CASE 1 - Independent Products: {a,b}, {c,d} with no overlap
 *          -> Each can use its own count track (optimization applies)
 *
 * CASE 2 - Containment Hierarchy: {a} < {a,b} < {a,b,c}
 *          -> Derive coarser counts from finest (optimization applies)
 *
 * CASE 3 - Universal Superset: {a,b}, {a,c}, {b,c} all contained in {a,b,c}
 *          -> Use superset as source (optimization applies)
 *
 * CASE 4 - Multiple Components: {a,b},{b,c} conflict + {d,e} independent
 *          -> Independent component optimizes; conflict defers (partial)
 *
 * CASE 5 - Star Pattern: {a,b}, {a,c}, {a,d} all share 'a'
 *          -> No containment, must defer ALL to final (NO optimization)
 *          -> This is the IMDB query pattern!
 *
 * Run with: build/sbt 'sql/testOnly org.apache.spark.sql.IMDB12TableBugSuite'
 */
class IMDB12TableBugSuite extends QueryTest with SharedSparkSession {

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
}
