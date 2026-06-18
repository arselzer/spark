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

import java.nio.file.{Files, Paths}
import java.util.Locale

import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types._

/**
 * Applicability report for the Yannakakis rewrite over the full TPC-H query set.
 * Requires dbgen .tbl files in /tmp/tpch-sf001 and query files in
 * /home/as/git/Spark-Y/data/tpch/new. Prints one line per query:
 *   TPCH-APPLICABILITY: <q> | class=<classes> | plan=<markers> | <OK/MISMATCH/...> | ...
 */
class TPCHApplicabilitySuite extends QueryTest with SharedSparkSession {

  override protected def sparkConf: org.apache.spark.SparkConf =
    super.sparkConf.set(SQLConf.YANNAKAKIS_COST_GATE_ENABLED.key, "false")

  private val dataDir = "/tmp/tpch-sf001"
  private val queryDir = "/home/as/git/Spark-Y/data/tpch/new"

  private val schemas: Map[String, StructType] = Map(
    "region" -> StructType(Seq(
      StructField("r_regionkey", IntegerType), StructField("r_name", StringType),
      StructField("r_comment", StringType))),
    "nation" -> StructType(Seq(
      StructField("n_nationkey", IntegerType), StructField("n_name", StringType),
      StructField("n_regionkey", IntegerType), StructField("n_comment", StringType))),
    "supplier" -> StructType(Seq(
      StructField("s_suppkey", IntegerType), StructField("s_name", StringType),
      StructField("s_address", StringType), StructField("s_nationkey", IntegerType),
      StructField("s_phone", StringType), StructField("s_acctbal", DoubleType),
      StructField("s_comment", StringType))),
    "customer" -> StructType(Seq(
      StructField("c_custkey", IntegerType), StructField("c_name", StringType),
      StructField("c_address", StringType), StructField("c_nationkey", IntegerType),
      StructField("c_phone", StringType), StructField("c_acctbal", DoubleType),
      StructField("c_mktsegment", StringType), StructField("c_comment", StringType))),
    "part" -> StructType(Seq(
      StructField("p_partkey", IntegerType), StructField("p_name", StringType),
      StructField("p_mfgr", StringType), StructField("p_brand", StringType),
      StructField("p_type", StringType), StructField("p_size", IntegerType),
      StructField("p_container", StringType), StructField("p_retailprice", DoubleType),
      StructField("p_comment", StringType))),
    "partsupp" -> StructType(Seq(
      StructField("ps_partkey", IntegerType), StructField("ps_suppkey", IntegerType),
      StructField("ps_availqty", IntegerType), StructField("ps_supplycost", DoubleType),
      StructField("ps_comment", StringType))),
    "orders" -> StructType(Seq(
      StructField("o_orderkey", IntegerType), StructField("o_custkey", IntegerType),
      StructField("o_orderstatus", StringType), StructField("o_totalprice", DoubleType),
      StructField("o_orderdate", DateType), StructField("o_orderpriority", StringType),
      StructField("o_clerk", StringType), StructField("o_shippriority", IntegerType),
      StructField("o_comment", StringType))),
    "lineitem" -> StructType(Seq(
      StructField("l_orderkey", IntegerType), StructField("l_partkey", IntegerType),
      StructField("l_suppkey", IntegerType), StructField("l_linenumber", IntegerType),
      StructField("l_quantity", DoubleType), StructField("l_extendedprice", DoubleType),
      StructField("l_discount", DoubleType), StructField("l_tax", DoubleType),
      StructField("l_returnflag", StringType), StructField("l_linestatus", StringType),
      StructField("l_shipdate", DateType), StructField("l_commitdate", DateType),
      StructField("l_receiptdate", DateType), StructField("l_shipinstruct", StringType),
      StructField("l_shipmode", StringType), StructField("l_comment", StringType)))
  )

  private def loadTables(): Unit = {
    for ((table, schema) <- schemas) {
      val df = spark.read.option("sep", "|").schema(schema)
        .csv(s"$dataDir/$table.tbl")
      df.cache().count()
      df.createOrReplaceTempView(table)
    }
  }

  private def statements(file: String): Seq[String] = {
    val text = new String(Files.readAllBytes(Paths.get(file)))
    text.split(";").map(_.split("\n").filterNot(_.trim.startsWith("--")).mkString("\n"))
      .map(_.trim).filter(_.nonEmpty)
      // Q15 creates a plain view over our temp views, which Spark forbids
      .map(_.replaceAll("(?i)^create view", "create temporary view"))
      // Q1 uses the TPC-H interval precision syntax "day (3)" Spark cannot parse
      .map(_.replaceAll("(?i)day\\s*\\(\\d+\\)", "day"))
      .toSeq
  }

  private def cellsMatch(a: Any, b: Any): Boolean = (a, b) match {
    case (null, null) => true
    case (x: Double, y: Double) =>
      (x.isNaN && y.isNaN) || math.abs(x - y) <= 1e-6 * math.max(1.0, math.abs(x))
    case (x, y) => x == y
  }

  private def rowsMatch(a: Seq[Row], b: Seq[Row]): Boolean = {
    a.size == b.size && a.sortBy(_.toString).zip(b.sortBy(_.toString)).forall {
      case (ra, rb) =>
        ra.size == rb.size && (0 until ra.size).forall(i => cellsMatch(ra.get(i), rb.get(i)))
    }
  }

  /** Runs all statements; returns (rows of the last SELECT, optimized plan, millis). */
  private def runQuery(stmts: Seq[String]): (Seq[Row], String, Long) = {
    var rows: Seq[Row] = Seq.empty
    var plan = ""
    var elapsed = 0L
    for (stmt <- stmts) {
      val lower = stmt.toLowerCase(Locale.ROOT)
      val isSelect = lower.startsWith("select") || lower.startsWith("with")
      if (isSelect) {
        val df = sql(stmt)
        val start = System.nanoTime()
        rows = df.collect().toSeq
        elapsed = (System.nanoTime() - start) / 1000000
        plan = df.queryExecution.optimizedPlan.toString
      } else {
        sql(stmt)
      }
    }
    (rows, plan, elapsed)
  }

  test("TPC-H applicability report") {
    loadTables()
    val report = new StringBuilder
    // Rewrite-side regressions only: MISMATCH (wrong results) and EXCEPTION (rewrite threw).
    // BASELINE-FAIL means vanilla Spark itself failed on the query and is NOT a rewrite fault.
    val failures = scala.collection.mutable.ListBuffer[String]()
    for (q <- 1 to 22) {
      val file = s"$queryDir/$q.sql"
      val stmts = statements(file)

      var baseline: (Seq[Row], String, Long) = null
      var baseErr: Throwable = null
      try {
        withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
          runQuery(stmts) // warmup
          baseline = runQuery(stmts)
        }
      } catch { case t: Throwable => baseErr = t }

      var rewritten: (Seq[Row], String, Long) = null
      var rewErr: Throwable = null
      val classes = scala.collection.mutable.ListBuffer[String]()
      try {
        val appender = new LogAppender("yannakakis classification")
        withLogAppender(appender) {
          withSQLConf(
            SQLConf.YANNAKAKIS_ENABLED.key -> "true",
            SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true") {
            runQuery(stmts) // warmup (also produces the classification logs)
            rewritten = runQuery(stmts)
          }
        }
        val msgs = appender.loggingEvents
          .map(_.getMessage.getFormattedMessage).distinct
        classes ++= msgs.collect {
          case m if m.contains("new aggregate (") =>
            m.substring(m.indexOf("new aggregate (") + 15).takeWhile(_ != ')')
          case m if m.contains("rewrite dropped attributes") => "fallback"
        }.distinct
      } catch { case t: Throwable => rewErr = t }

      val planMarkers = if (rewritten != null) {
        val p = rewritten._2
        Seq(
          if (p.contains("CountJoin")) Some("CountJoin") else None,
          if (p.contains("LeftSemi")) Some("LeftSemi") else None).flatten match {
          case Nil => "none"
          case m => m.mkString("+")
        }
      } else "n/a"

      def errStr(t: Throwable): String = (t.getClass.getSimpleName + " " +
        Option(t.getMessage).getOrElse("")).replaceAll("\\s+", " ").take(160)
      val verdict =
        if (baseErr != null) s"BASELINE-FAIL ${errStr(baseErr)}"
        else if (rewErr != null) s"EXCEPTION ${errStr(rewErr)}"
        else if (rowsMatch(baseline._1, rewritten._1)) "OK"
        else "MISMATCH"

      val classStr = if (classes.isEmpty) "not-rewritten" else classes.mkString("+")
      val timing = if (baseline != null && rewritten != null) {
        s"base=${baseline._3}ms yann=${rewritten._3}ms"
      } else ""
      val rowsStr = if (baseline != null) s"rows=${baseline._1.size}" else ""
      val line = s"TPCH-APPLICABILITY: Q$q | class=$classStr | plan=$planMarkers" +
        s" | $verdict | $rowsStr | $timing"
      // scalastyle:off println
      println(line)
      // scalastyle:on println
      report.append(line).append('\n')
      if (verdict.startsWith("MISMATCH") || verdict.startsWith("EXCEPTION")) {
        failures.append(line)
      }
    }
    // scalastyle:off println
    println("TPCH-APPLICABILITY-REPORT:\n" + report)
    // scalastyle:on println
    assert(failures.isEmpty,
      s"TPC-H rewrite regressed on ${failures.size} query/queries " +
        s"(MISMATCH = wrong results, EXCEPTION = rewrite threw; BASELINE-FAIL is excluded):\n" +
        failures.mkString("\n") + "\n\nfull report:\n" + report)
  }
}
