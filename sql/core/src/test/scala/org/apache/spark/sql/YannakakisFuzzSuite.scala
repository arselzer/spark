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

import scala.collection.mutable.ArrayBuffer
import scala.util.Random

import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types._

/**
 * Property-based / fuzz oracle for the Yannakakis count-join rewrite: generate many random
 * acyclic (star and chain) join+aggregate queries and assert the rewrite produces the SAME
 * results AND the SAME output schema as vanilla Spark. The schema check matters because
 * count-multiplication rewrites can change a decimal aggregate's precision/scale - a divergence
 * that value-only comparison misses (the historical SUM(decimal) bug class).
 *
 * Deterministic: each case is driven by a fixed seed, so a failure prints a reproducible query.
 * The iteration count is configurable via the FUZZ_ITERS env var (default 250).
 */
class YannakakisFuzzSuite extends QueryTest with SharedSparkSession {

  override protected def sparkConf: org.apache.spark.SparkConf =
    super.sparkConf.set(SQLConf.YANNAKAKIS_COST_GATE_ENABLED.key, "false")

  private val yannakakisOn = Seq(
    SQLConf.YANNAKAKIS_ENABLED.key -> "true",
    SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
    SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true")

  private def cellsMatch(a: Any, b: Any): Boolean = (a, b) match {
    case (null, null) => true
    case (null, _) | (_, null) => false
    case (x: Double, y: Double) =>
      (x.isNaN && y.isNaN) || math.abs(x - y) <= 1e-6 * math.max(1.0, math.abs(x))
    case (x: java.math.BigDecimal, y: java.math.BigDecimal) =>
      x.subtract(y).abs().doubleValue() <= 1e-6 * math.max(1.0, math.abs(x.doubleValue()))
    case (x, y) => x == y
  }

  private def rowsMatch(a: Seq[Row], b: Seq[Row]): Boolean = {
    if (a.size != b.size) return false
    a.sortBy(_.toString).zip(b.sortBy(_.toString)).forall { case (ra, rb) =>
      ra.size == rb.size && (0 until ra.size).forall(i => cellsMatch(ra.get(i), rb.get(i)))
    }
  }

  // Compare field data types (incl. decimal precision/scale); ignore nullability differences,
  // which are benign here and would only add false positives.
  private def schemaMatch(s1: StructType, s2: StructType): Boolean =
    s1.length == s2.length && s1.fields.zip(s2.fields).forall { case (f1, f2) =>
      f1.dataType == f2.dataType
    }

  private def rkey(rng: Random, maxK: Int): Integer =
    if (rng.nextInt(10) == 0) null else Int.box(1 + rng.nextInt(maxK))
  private def rint(rng: Random): Integer =
    if (rng.nextInt(8) == 0) null else Int.box(rng.nextInt(120) - 20)
  private def rdbl(rng: Random): java.lang.Double =
    if (rng.nextInt(8) == 0) null else Double.box((rng.nextInt(12000) - 2000) / 100.0)
  private def rdec(rng: Random): java.math.BigDecimal =
    if (rng.nextInt(8) == 0) null
    else new java.math.BigDecimal(rng.nextInt(1000000) - 200000).movePointLeft(2).setScale(4)
  private def rstr(rng: Random): String =
    if (rng.nextInt(12) == 0) null else "G" + rng.nextInt(4)

  private val factSchema = StructType(Seq(
    StructField("k1", IntegerType), StructField("k2", IntegerType), StructField("k3", IntegerType),
    StructField("fm1", IntegerType), StructField("fm2", DoubleType),
    StructField("fm3", DecimalType(18, 4))))
  private val dimSchema = (i: Int) => StructType(Seq(
    StructField(s"d${i}k", IntegerType), StructField(s"d${i}g", StringType),
    StructField(s"d${i}v", IntegerType)))

  private def registerTables(rng: Random): Unit = {
    val maxK = 4
    val nFact = 5 + rng.nextInt(12)
    val factRows = (0 until nFact).map { _ =>
      Row(rkey(rng, maxK), rkey(rng, maxK), rkey(rng, maxK), rint(rng), rdbl(rng), rdec(rng))
    }
    spark.createDataFrame(spark.sparkContext.parallelize(factRows), factSchema)
      .createOrReplaceTempView("fz_fact")
    (1 to 3).foreach { i =>
      // Per key: 0 rows (no match -> tests reduction), 1 row (unique), or 2 rows (fan-out).
      val dimRows = (1 to maxK).flatMap { k =>
        (0 until rng.nextInt(3)).map(_ => Row(Int.box(k), rstr(rng), rint(rng)))
      }
      spark.createDataFrame(spark.sparkContext.parallelize(dimRows), dimSchema(i))
        .createOrReplaceTempView(s"fz_d$i")
    }
  }

  private def pick[T](rng: Random, xs: Seq[T]): T = xs(rng.nextInt(xs.size))

  private def genQuery(rng: Random): String = {
    val nDims = 1 + rng.nextInt(3)
    val usedDims = 1 to nDims
    val star = rng.nextBoolean()
    // Star: fact joins each dim on f.k{i}=d{i}.d{i}k. Chain: fact-d1, d1-d2 (via shared key space),
    // d2-d3. The chain reuses the same integer key domain so joins are non-trivial.
    val joins = if (star) {
      "fz_fact f " + usedDims.map(i => s"join fz_d$i d$i on f.k$i = d$i.d${i}k").mkString(" ")
    } else {
      val parts = new StringBuilder("fz_fact f join fz_d1 d1 on f.k1 = d1.d1k")
      if (nDims >= 2) parts.append(" join fz_d2 d2 on d1.d1v = d2.d2k")
      if (nDims >= 3) parts.append(" join fz_d3 d3 on d2.d2v = d3.d3k")
      parts.toString
    }
    val numCols = Seq("f.fm1", "f.fm2", "f.fm3") ++ usedDims.map(i => s"d$i.d${i}v")
    val allCols = numCols ++ usedDims.map(i => s"d$i.d${i}g") ++ Seq("f.k1")

    def agg(rng: Random): String = rng.nextInt(8) match {
      case 0 => "count(*)"
      case 1 => s"count(${pick(rng, allCols)})"
      case 2 => s"count(distinct ${pick(rng, allCols)})"
      case 3 => s"sum(${pick(rng, numCols)})"
      case 4 => s"sum(distinct ${pick(rng, numCols)})"
      case 5 => s"avg(${pick(rng, numCols)})"
      case 6 => s"min(${pick(rng, allCols)})"
      case _ => s"max(${pick(rng, allCols)})"
    }
    val nAgg = 1 + rng.nextInt(3)
    val aggSelect = (0 until nAgg).map(j => s"${agg(rng)} as a$j")

    val groupPool = usedDims.map(i => s"d$i.d${i}g") ++ Seq("f.k1")
    val nGroup = rng.nextInt(3)
    val groupCols = rng.shuffle(groupPool.toList).take(nGroup)
    val groupSelect = groupCols.zipWithIndex.map { case (g, i) => s"$g as g$i" }
    val selectList = (groupSelect ++ aggSelect).mkString(", ")
    val groupClause = if (groupCols.nonEmpty) " group by " + groupCols.mkString(", ") else ""

    // ~40% of the time add a non-equi cross-relation filter (fact col vs a dim col), a historical
    // bug source for the count-join path (the filter must be applied at/above the join where both
    // attributes are available, and rows whose matches all fail it must drop the carried count).
    val crossFilter = if (rng.nextInt(10) < 4) {
      val fcol = pick(rng, Seq("f.fm1", "f.fm2"))
      val di = 1 + rng.nextInt(nDims)
      val op = pick(rng, Seq("<", ">", "<=", ">=", "<>"))
      s" where $fcol $op d$di.d${di}v"
    } else ""
    s"select $selectList from $joins$crossFilter$groupClause"
  }

  test("fuzz: random acyclic join+aggregate queries match vanilla (values + schema)") {
    val iters = sys.env.getOrElse("FUZZ_ITERS", "250").toInt
    val failures = ArrayBuffer[String]()
    var checked = 0
    (1 to iters).foreach { seed =>
      val rng = new Random(seed.toLong)
      registerTables(rng)
      val query = genQuery(rng)
      try {
        var vSchema: StructType = null
        var vRows: Seq[Row] = null
        withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
          val df = sql(query); vSchema = df.schema; vRows = df.collect().toSeq
        }
        withSQLConf(yannakakisOn: _*) {
          val df = sql(query)
          val rSchema = df.schema
          if (!schemaMatch(rSchema, vSchema)) {
            failures += s"seed=$seed SCHEMA ${rSchema.catalogString} != " +
              s"${vSchema.catalogString}\n$query"
          } else {
            val rRows = df.collect().toSeq
            if (!rowsMatch(rRows, vRows)) {
              failures += s"seed=$seed VALUES (${rRows.size} vs ${vRows.size} rows)\n$query"
            }
          }
        }
        checked += 1
      } catch {
        case e: Throwable => failures += s"seed=$seed EXCEPTION ${e.getClass.getSimpleName}: " +
          s"${Option(e.getMessage).getOrElse("")}\n$query"
      }
    }
    info(s"fuzz: checked $checked/$iters queries, ${failures.size} failures")
    assert(failures.isEmpty,
      s"${failures.size}/$iters fuzz failures (showing up to 12):\n" +
        failures.take(12).mkString("\n----\n"))
  }
}
