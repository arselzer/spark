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

import java.io.File
import java.util.Random

import org.apache.spark.sql.catalyst.plans.logical.CountJoin
import org.apache.spark.sql.catalyst.util.resourceToString
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types.StructType

/** Manual warmed/interleaved TPC-DS benchmark for CountJoin performance investigations. */
class TPCDSCountJoinProperBenchmarkSuite
    extends QueryTest with SharedSparkSession with TPCDSSchema {

  override protected def sparkConf: org.apache.spark.SparkConf =
    super.sparkConf
      .set(SQLConf.SHUFFLE_PARTITIONS.key, "16")
      .set(SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key, (10L * 1024 * 1024).toString)
      .set(SQLConf.READ_SIDE_CHAR_PADDING, false)
      .set(SQLConf.PLAN_CHANGE_VALIDATION.key, "false")

  private def propOrEnv(prop: String, env: String, defaultValue: String): String =
    Option(System.getProperty(prop)).orElse(sys.env.get(env)).getOrElse(defaultValue)

  private val dataDir = propOrEnv("tpcds.proper.data", "TPCDS_PROPER_DATA", "/tmp/tpcds-sf5")
  private val parquetDir =
    propOrEnv("tpcds.proper.parquet", "TPCDS_PROPER_PARQUET", "/tmp/tpcds-sf5-parquet")
  private val queryNames = propOrEnv(
    "tpcds.proper.queries",
    "TPCDS_PROPER_QUERIES",
    "q4,q11,q25,q50,q64,q92,q97")
    .split(",").map(_.trim).filter(_.nonEmpty).toSeq
  private val warmups = propOrEnv("tpcds.proper.warmups", "TPCDS_PROPER_WARMUPS", "1").toInt
  private val repetitions =
    propOrEnv("tpcds.proper.repetitions", "TPCDS_PROPER_REPETITIONS", "3").toInt
  private val randomSeed = propOrEnv("tpcds.proper.seed", "TPCDS_PROPER_SEED", "20260618").toLong

  private case class Mode(name: String, confs: Seq[(String, String)])
  private case class Run(rows: Int, ms: Long)

  private val base = Mode("base", Seq(SQLConf.YANNAKAKIS_ENABLED.key -> "false"))
  private val rewritten = Mode("rewritten", Seq(
    SQLConf.YANNAKAKIS_ENABLED.key -> "true",
    SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
    SQLConf.YANNAKAKIS_COST_GATE_ENABLED.key -> "false"))
  private val costGated = Mode("cost-gated", Seq(
    SQLConf.YANNAKAKIS_ENABLED.key -> "true",
    SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
    SQLConf.YANNAKAKIS_COST_GATE_ENABLED.key -> "true"))

  private def csvSchema(table: String): StructType = {
    val ddl = tableColumns(table).replaceAll("(?i)(VAR)?CHAR\\(\\d+\\)", "STRING")
    StructType.fromDDL(ddl)
  }

  private def loadTables(): Unit = {
    new File(parquetDir).mkdirs()
    for (table <- tableColumns.keys) {
      val datFile = s"$dataDir/$table.dat"
      val pq = s"$parquetDir/$table"
      if (!new File(pq).exists() && new File(datFile).exists()) {
        val df = spark.read.option("sep", "|").schema(csvSchema(table)).csv(datFile)
        df.write.mode("overwrite").parquet(pq)
      }
      if (new File(pq).exists()) {
        spark.read.parquet(pq).createOrReplaceTempView(table)
      }
    }
  }

  private def countJoins(sqlText: String, mode: Mode): Int = {
    withSQLConf(mode.confs: _*) {
      sql(sqlText).queryExecution.optimizedPlan.collect { case _: CountJoin => 1 }.size
    }
  }

  private def run(sqlText: String, mode: Mode): Run = {
    withSQLConf(mode.confs: _*) {
      val df = sql(sqlText)
      val start = System.nanoTime()
      val rows = df.collect().length
      Run(rows, (System.nanoTime() - start) / 1000000)
    }
  }

  private def median(values: Seq[Long]): Long = {
    val sorted = values.sorted
    sorted(sorted.length / 2)
  }

  test("proper warmed CountJoin benchmark") {
    assume(new File(dataDir).exists(), s"TPC-DS data dir $dataDir absent; skipping")
    require(repetitions > 0, "tpcds.proper.repetitions must be positive")
    require(warmups >= 0, "tpcds.proper.warmups must be non-negative")
    loadTables()

    val failures = scala.collection.mutable.ListBuffer[String]()
    val rng = new Random(randomSeed)

    for (name <- queryNames) {
      val sqlText = resourceToString(s"tpcds/$name.sql",
        classLoader = Thread.currentThread().getContextClassLoader)
      try {
        val forcedCountJoins = countJoins(sqlText, rewritten)
        val gatedCountJoins = countJoins(sqlText, costGated)
        val gate = if (gatedCountJoins > 0) "Y" else "N"

        (0 until warmups).foreach { _ =>
          if (rng.nextBoolean()) {
            run(sqlText, base)
            run(sqlText, rewritten)
          } else {
            run(sqlText, rewritten)
            run(sqlText, base)
          }
        }

        val baseRuns = scala.collection.mutable.ArrayBuffer[Long]()
        val rewrittenRuns = scala.collection.mutable.ArrayBuffer[Long]()
        var baseRows: Option[Int] = None
        var rewrittenRows: Option[Int] = None

        (0 until repetitions).foreach { _ =>
          val modes =
            if (rng.nextBoolean()) Seq(base, rewritten) else Seq(rewritten, base)
          modes.foreach { mode =>
            val result = run(sqlText, mode)
            if (mode.name == "base") {
              baseRuns += result.ms
              baseRows = baseRows.orElse(Some(result.rows))
              if (baseRows.exists(_ != result.rows)) {
                failures += s"$name base row-count changed"
              }
            } else {
              rewrittenRuns += result.ms
              rewrittenRows = rewrittenRows.orElse(Some(result.rows))
              if (rewrittenRows.exists(_ != result.rows)) {
                failures += s"$name rewritten row-count changed"
              }
            }
          }
        }

        if (baseRows != rewrittenRows) {
          failures += s"$name row-count mismatch base=${baseRows.getOrElse(-1)} " +
            s"rewritten=${rewrittenRows.getOrElse(-1)}"
        }

        val baseMedian = median(baseRuns.toSeq)
        val rewrittenMedian = median(rewrittenRuns.toSeq)
        val speedup = if (rewrittenMedian > 0) baseMedian.toDouble / rewrittenMedian else 0.0
        // scalastyle:off println
        println(f"TPCDS-PROPER: $name%-5s | gate=$gate | forcedCountJoins=$forcedCountJoins | " +
          f"gatedCountJoins=$gatedCountJoins | rows=${baseRows.getOrElse(-1)} | " +
          f"baseMedian=${baseMedian}%dms | rewrittenMedian=${rewrittenMedian}%dms | " +
          f"speedup=${speedup}%.2fx | baseRuns=${baseRuns.mkString("[", ",", "]")} | " +
          f"rewrittenRuns=${rewrittenRuns.mkString("[", ",", "]")}")
        // scalastyle:on println
      } catch {
        case t: Throwable =>
          val err = t.getClass.getSimpleName + " " + Option(t.getMessage).getOrElse("")
          failures += s"$name EXCEPTION $err"
          // scalastyle:off println
          println(s"TPCDS-PROPER: $name | EXCEPTION ${err.replaceAll("\\s+", " ").take(160)}")
          // scalastyle:on println
      }
    }

    assert(failures.isEmpty, s"TPC-DS proper benchmark failures:\n" + failures.mkString("\n"))
  }
}
