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
}
