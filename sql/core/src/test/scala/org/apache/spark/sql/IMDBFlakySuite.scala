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
 * Test suite for flaky independent product tests.
 * Run with: build/sbt 'sql/testOnly org.apache.spark.sql.IMDBFlakySuite'
 */
class IMDBFlakySuite extends QueryTest with SharedSparkSession {

  override protected def sparkConf: org.apache.spark.SparkConf =
    super.sparkConf.set(SQLConf.YANNAKAKIS_COST_GATE_ENABLED.key, "false")

  import testImplicits._

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
        SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true"
      ) {
        val df = sql(query)
        println("=== CASE 6c: DUPLICATE ROWS - COUNT ACCURACY ===")
        println(s"Baseline: ${baseline.map(_.toString).mkString}")
        println(s"Yannakakis result: ${df.collect().map(_.toString).mkString}")
        checkAnswer(df, baseline)
      }
      // scalastyle:on println
    }
  }

  test("independent products with real IMDB schema - flaky bug test") {
    // Mimics the actual IMDB 12-table query structure
    val ci = Seq(
      (1, 1, 1, 2, "(voice)")
    ).toDF("movie_id", "person_id", "person_role_id", "role_id", "note")

    val mi = Seq(
      (1, 16, "USA:2011")
    ).toDF("movie_id", "info_type_id", "info")

    val t = Seq(
      (1, 2011, 9, 7)
    ).toDF("id", "production_year", "season_nr", "kind_id")

    val mc = Seq(
      (1, 1, 1), (1, 1, 1), (1, 1, 1), (1, 2, 1),
      (1, 3, 2), (1, 3, 2), (1, 4, 2), (1, 5, 2), (1, 5, 2)
    ).toDF("movie_id", "company_id", "company_type_id")

    val chn = Seq(
      (1, null.asInstanceOf[java.lang.Integer])
    ).toDF("id", "imdb_id")

    val rt = Seq((2, "actress")).toDF("id", "role")
    val n = Seq((1, "f", "Anna")).toDF("id", "gender", "name")
    val an = Seq((1), (1), (1), (1), (1), (1)).toDF("person_id")
    val cn = Seq(
      (1, "[us]"), (2, "[us]"), (3, "[us]"), (4, "[us]"), (5, "[us]")
    ).toDF("id", "country_code")
    val it = Seq((16, "release dates")).toDF("id", "info")
    val k = Seq((1, "hero")).toDF("id", "keyword")
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
      WHERE ci.note IN ('(voice)',
                        '(voice: Japanese version)',
                        '(voice) (uncredited)',
                        '(voice: English version)')
        AND cn.country_code = '[us]'
        AND it.info = 'release dates'
        AND k.keyword IN ('hero', 'martial-arts', 'hand-to-hand-combat')
        AND mi.info IS NOT NULL
        AND (mi.info LIKE 'Japan:%201%' OR mi.info LIKE 'USA:%201%')
        AND n.gender = 'f'
        AND n.name LIKE '%An%'
        AND rt.role = 'actress'
        AND t.production_year > 2010
        AND t.id = mi.movie_id
        AND t.id = mc.movie_id
        AND t.id = ci.movie_id
        AND t.id = mk.movie_id
        AND mc.movie_id = ci.movie_id
        AND mc.movie_id = mi.movie_id
        AND mc.movie_id = mk.movie_id
        AND mi.movie_id = ci.movie_id
        AND mi.movie_id = mk.movie_id
        AND ci.movie_id = mk.movie_id
        AND cn.id = mc.company_id
        AND it.id = mi.info_type_id
        AND n.id = ci.person_id
        AND rt.id = ci.role_id
        AND n.id = an.person_id
        AND ci.person_id = an.person_id
        AND chn.id = ci.person_role_id
        AND k.id = mk.keyword_id
    """

    // scalastyle:off println
    withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
      val df = sql(query)
      println("=== INDEPENDENT PRODUCTS - FLAKY BUG TEST ===")
      println("P1 = SUM(ci.role_id * mi.info_type_id)")
      println("P2 = SUM(mc.company_type_id * t.kind_id)")
      val baseline = df.collect()
      println(s"Baseline (Yannakakis OFF): ${baseline.map(_.toString).mkString}")

      withSQLConf(
        SQLConf.YANNAKAKIS_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true"
      ) {
        val df2 = sql(query)
        val yannakakis = df2.collect()
        println(s"Yannakakis (ON):            ${yannakakis.map(_.toString).mkString}")
        checkAnswer(df2, baseline)
      }
    }
    // scalastyle:on println
  }
}
