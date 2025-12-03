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
 * Test suite for the 12-table IMDB bug with real data cardinalities.
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
}
