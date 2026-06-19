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

import org.apache.spark.sql.catalyst.util.resourceToString
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types.StructType

/**
 * Manual performance + correctness benchmark for the Yannakakis count-join rewrite over TPC-DS.
 * Loads dsdgen .dat files (default /tmp/tpcds-sf1, override -Dtpcds.bench.data=...) into parquet
 * (cached under /tmp/tpcds-sf1-parquet), registers them as temp views, then runs a chosen set of
 * queries with the rewrite OFF (vanilla) vs ON (cost gate OFF, so it fires wherever structurally
 * possible). For each query it asserts the rewritten result equals vanilla and reports timing plus
 * whether a physical CountJoin appeared.
 *
 * Query set: -Dtpcds.bench.queries=q11,q74,...  (default = a small multi-fact shortlist).
 * Skips itself if the data dir is absent. Run with:
 *   build/sbt -Dtpcds.bench.queries=q11,q74 'set Test/javaOptions += "-Xmx8g"' \
 *     "sql/testOnly org.apache.spark.sql.TPCDSBenchmarkSuite"
 */
class TPCDSBenchmarkSuite extends QueryTest with SharedSparkSession with TPCDSSchema {

  override protected def sparkConf: org.apache.spark.SparkConf =
    super.sparkConf
      .set(SQLConf.SHUFFLE_PARTITIONS.key, "16")
      .set(SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key, (10L * 1024 * 1024).toString)
      .set(SQLConf.READ_SIDE_CHAR_PADDING, false)
      // Production-like: the post-rule plan-integrity check defaults ON only under Utils.isTesting.
      // Turn it OFF so this measures what a real query would do (execute), not the test-only check.
      .set(SQLConf.PLAN_CHANGE_VALIDATION.key, "false")

  private val dataDir = System.getProperty("tpcds.bench.data", "/tmp/tpcds-sf1")
  private val parquetDir = System.getProperty("tpcds.bench.parquet", "/tmp/tpcds-sf1-parquet")
  // Fan-out / fact-fact candidates that fire + are correct, plus a pure-star contrast (q3) the
  // cost gate should skip. At SF5 the largest dimension (customer) and the facts stop being
  // broadcastable, so this is where the rewrite could actually win (or not).
  private val queryNames: Seq[String] =
    System.getProperty("tpcds.bench.queries", "q5,q12,q15,q19,q20")
      .split(",").map(_.trim).filter(_.nonEmpty).toSeq

  // CHAR(n)/VARCHAR(n) -> STRING so the CSV read does not pad/truncate; everything else (INT,
  // DECIMAL(p,s), DATE) is read with its declared type. Same data feeds vanilla and rewrite, so the
  // correctness comparison is apples-to-apples regardless of null/padding nuances.
  private def csvSchema(table: String): StructType = {
    val ddl = tableColumns(table)
      .replaceAll("(?i)(VAR)?CHAR\\(\\d+\\)", "STRING")
    StructType.fromDDL(ddl)
  }

  private def loadTables(): Unit = {
    new File(parquetDir).mkdirs()
    for (table <- tableColumns.keys) {
      val datFile = s"$dataDir/$table.dat"
      val pq = s"$parquetDir/$table"
      if (!new File(pq).exists()) {
        if (new File(datFile).exists()) {
          val df = spark.read.option("sep", "|").schema(csvSchema(table)).csv(datFile)
          df.write.mode("overwrite").parquet(pq)
        }
      }
      if (new File(pq).exists()) {
        spark.read.parquet(pq).createOrReplaceTempView(table)
      }
    }
  }

  private def cellsMatch(a: Any, b: Any): Boolean = (a, b) match {
    case (null, null) => true
    case (x: Double, y: Double) =>
      (x.isNaN && y.isNaN) || math.abs(x - y) <= 1e-6 * math.max(1.0, math.abs(x))
    case (x: java.math.BigDecimal, y: java.math.BigDecimal) =>
      x.subtract(y).abs().doubleValue() <= 1e-6 * math.max(1.0, x.abs().doubleValue())
    case (x, y) => x == y
  }

  private def rowsMatch(a: Seq[Row], b: Seq[Row]): Boolean = {
    a.size == b.size && a.sortBy(_.toString).zip(b.sortBy(_.toString)).forall {
      case (ra, rb) =>
        ra.size == rb.size && (0 until ra.size).forall(i => cellsMatch(ra.get(i), rb.get(i)))
    }
  }

  private def run(stmt: String): (Seq[Row], String, Long) = {
    val df = sql(stmt)
    val start = System.nanoTime()
    val rows = df.collect().toSeq
    val ms = (System.nanoTime() - start) / 1000000
    (rows, df.queryExecution.executedPlan.toString, ms)
  }

  test("TPC-DS performance + correctness") {
    assume(new File(dataDir).exists(), s"TPC-DS data dir $dataDir absent; skipping")
    loadTables()
    val report = new StringBuilder
    val failures = scala.collection.mutable.ListBuffer[String]()

    for (name <- queryNames) {
      val sqlText =
        try resourceToString(s"tpcds/$name.sql",
          classLoader = Thread.currentThread().getContextClassLoader)
        catch { case _: Throwable => null }
      if (sqlText == null) {
        report.append(s"TPCDS-BENCH: $name | MISSING-RESOURCE\n")
      } else {
        // TPC-DS query files are a single SELECT (possibly with WITH); run as one statement.
        var base: (Seq[Row], String, Long) = null
        var rew: (Seq[Row], String, Long) = null   // rewrite forced (cost gate OFF)
        var prod: (Seq[Row], String, Long) = null  // production default (cost gate ON)
        var err: String = null
        try {
          withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
            run(sqlText)            // warmup
            base = run(sqlText)
          }
          withSQLConf(
            SQLConf.YANNAKAKIS_ENABLED.key -> "true",
            SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
            SQLConf.YANNAKAKIS_COST_GATE_ENABLED.key -> "false") {
            run(sqlText)            // warmup
            rew = run(sqlText)
          }
          withSQLConf(
            SQLConf.YANNAKAKIS_ENABLED.key -> "true",
            SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
            SQLConf.YANNAKAKIS_COST_GATE_ENABLED.key -> "true") {
            run(sqlText)            // warmup
            prod = run(sqlText)
          }
        } catch { case t: Throwable =>
          err = t.getClass.getSimpleName + " " + Option(t.getMessage).getOrElse("")
        }

        val line = if (err != null) {
          failures.append(s"$name EXCEPTION $err")
          s"TPCDS-BENCH: $name | EXCEPTION ${err.replaceAll("\\s+", " ").take(140)}"
        } else {
          val fired = if (rew._2.contains("CountJoin")) "Y" else "n"
          val pGate = if (prod._2.contains("CountJoin")) "FIRES" else "skips"
          val ok = rowsMatch(base._1, rew._1) && rowsMatch(base._1, prod._1)
          if (!ok) failures.append(s"$name MISMATCH base=${base._1.size} rew=${rew._1.size}")
          val speedup = if (rew._3 > 0) base._3.toDouble / rew._3 else 0.0
          f"TPCDS-BENCH: $name%-5s | ${if (ok) "OK" else "MISMATCH"}%-8s | " +
            f"forced=$fired prodGate=$pGate | rows=${base._1.size}%-4d | " +
            f"base=${base._3}%5dms forced=${rew._3}%5dms prod=${prod._3}%5dms | " +
            f"forced-speedup=$speedup%.2fx"
        }
        // scalastyle:off println
        println(line)
        // scalastyle:on println
        report.append(line).append('\n')
      }
    }
    // scalastyle:off println
    println("TPCDS-BENCH-REPORT:\n" + report)
    // scalastyle:on println
    assert(failures.isEmpty, s"TPC-DS bench failures:\n" + failures.mkString("\n"))
  }
}
