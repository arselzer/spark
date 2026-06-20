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

import org.apache.spark.sql.catalyst.plans.logical.CountJoin
import org.apache.spark.sql.catalyst.util.resourceToString
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.sql.execution.adaptive.{AdaptiveSparkPlanExec, QueryStageExec}
import org.apache.spark.sql.execution.joins.HashCountJoin
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types.StructType

/** Manual compact diagnostics for CountJoin TPC-DS performance investigations. */
class TPCDSCountJoinDiagnosticsSuite extends QueryTest with SharedSparkSession with TPCDSSchema {

  override protected def sparkConf: org.apache.spark.SparkConf =
    super.sparkConf
      .set(SQLConf.SHUFFLE_PARTITIONS.key, "16")
      .set(SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key, (10L * 1024 * 1024).toString)
      .set(SQLConf.READ_SIDE_CHAR_PADDING, false)
      .set(SQLConf.PLAN_CHANGE_VALIDATION.key, "false")

  private def propOrEnv(prop: String, env: String, defaultValue: String): String =
    Option(System.getProperty(prop)).orElse(sys.env.get(env)).getOrElse(defaultValue)

  private val dataDir = propOrEnv("tpcds.diag.data", "TPCDS_DIAG_DATA", "/tmp/tpcds-sf5")
  private val parquetDir =
    propOrEnv("tpcds.diag.parquet", "TPCDS_DIAG_PARQUET", "/tmp/tpcds-sf5-parquet")
  private val queryNames = propOrEnv("tpcds.diag.queries", "TPCDS_DIAG_QUERIES", "q13,q25,q48")
    .split(",").map(_.trim).filter(_.nonEmpty).toSeq
  private val modeNames = propOrEnv(
    "tpcds.diag.modes",
    "TPCDS_DIAG_MODES",
    "base,prod")
    .split(",").map(_.trim).filter(_.nonEmpty).toSet
  private val printPlan = propOrEnv("tpcds.diag.printPlan", "TPCDS_DIAG_PRINT_PLAN", "false")
    .toBoolean
  // Warmup/iteration controls. The second run of a query is usually much faster than the first
  // (JIT, page cache, codegen). Each (query, mode) is warmed `warmupRuns` times (discarded), then
  // measured `measureRuns` times; reported `ms` is the MIN (warm steady-state). Each mode warming
  // itself removes the base->forced->prod position bias of single-pass timing.
  private val warmupRuns = propOrEnv("tpcds.diag.warmup", "TPCDS_DIAG_WARMUP", "1").toInt
  private val measureRuns = propOrEnv("tpcds.diag.iters", "TPCDS_DIAG_ITERS", "2").toInt

  private case class Mode(name: String, confs: Seq[(String, String)])
  private case class RunResult(
      rows: Int,
      ms: Long,
      optimizedCountJoins: Int,
      physicalPlan: SparkPlan)

  private val allModes = Seq(
    Mode("base", Seq(SQLConf.YANNAKAKIS_ENABLED.key -> "false")),
    Mode("forced", Seq(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_COST_GATE_ENABLED.key -> "false")),
    Mode("prod", Seq(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_COST_GATE_ENABLED.key -> "true"))
  )
  private val modes = allModes.filter(m => modeNames.contains(m.name))

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

  private def finalPlan(plan: SparkPlan): SparkPlan = plan match {
    case adaptive: AdaptiveSparkPlanExec => adaptive.finalPhysicalPlan
    case other => other
  }

  private def childrenOf(plan: SparkPlan): Seq[SparkPlan] = plan match {
    case stage: QueryStageExec => Seq(stage.plan)
    case other => other.children
  }

  private case class NodeInfo(plan: SparkPlan, depth: Int, inCodegen: Boolean)

  private def allNodes(plan: SparkPlan): Seq[NodeInfo] = {
    def loop(p: SparkPlan, depth: Int, inCodegen: Boolean): Seq[NodeInfo] = {
      val nowInCodegen = inCodegen || p.getClass.getSimpleName == "WholeStageCodegenExec"
      NodeInfo(p, depth, nowInCodegen) +:
        childrenOf(p).flatMap(child => loop(child, depth + 1, nowInCodegen))
    }
    loop(plan, 0, false)
  }

  private def metricValue(plan: SparkPlan, name: String): Long =
    plan.metrics.get(name).map(_.value).getOrElse(0L)

  private def total(nodes: Seq[NodeInfo], className: String, metric: String): Long =
    nodes.filter(_.plan.getClass.getSimpleName == className)
      .map(n => metricValue(n.plan, metric)).sum

  private def count(nodes: Seq[NodeInfo], className: String): Int =
    nodes.count(_.plan.getClass.getSimpleName == className)

  private def operatorCounts(nodes: Seq[NodeInfo]): String = {
    Seq(
      "BroadcastHashJoinExec",
      "SortMergeJoinExec",
      "ShuffledHashJoinExec",
      "BroadcastHashCountJoinExec",
      "ShuffledHashCountJoinExec",
      "SortMergeCountJoinExec",
      "HashAggregateExec",
      "ShuffleExchangeExec",
      "BroadcastExchangeExec",
      "SortExec",
      "WholeStageCodegenExec").flatMap { key =>
      val n = count(nodes, key)
      if (n == 0) None else Some(s"$key=$n")
    }.mkString(",")
  }

  private def metricSummary(nodes: Seq[NodeInfo]): String = {
    val countJoinOutput = nodes.filter(_.plan.getClass.getSimpleName.contains("CountJoin"))
      .map(n => metricValue(n.plan, "numOutputRows")).sum
    val countJoinBuild = total(nodes, "ShuffledHashCountJoinExec", "buildDataSize")
    val countJoinBuildTime = total(nodes, "ShuffledHashCountJoinExec", "buildTime")
    val countJoinSpill = total(nodes, "SortMergeCountJoinExec", "spillSize")
    val smjOutput = total(nodes, "SortMergeJoinExec", "numOutputRows")
    val bhjOutput = total(nodes, "BroadcastHashJoinExec", "numOutputRows")
    val aggTime = total(nodes, "HashAggregateExec", "aggTime")
    val aggOutput = total(nodes, "HashAggregateExec", "numOutputRows")
    val shuffleBytes = total(nodes, "ShuffleExchangeExec", "shuffleBytesWritten")
    val shuffleRecords = total(nodes, "ShuffleExchangeExec", "shuffleRecordsWritten")
    val sortTime = total(nodes, "SortExec", "sortTime")
    val sortPeak = total(nodes, "SortExec", "peakMemory")
    val codegenMs = total(nodes, "WholeStageCodegenExec", "pipelineTime")
    s"cjOut=$countJoinOutput,cjBuildBytes=$countJoinBuild,cjBuildMs=$countJoinBuildTime," +
      s"cjSpill=$countJoinSpill,smjOut=$smjOutput,bhjOut=$bhjOutput,aggMs=$aggTime," +
      s"aggOut=$aggOutput,shuffleBytes=$shuffleBytes,shuffleRecords=$shuffleRecords," +
      s"sortMs=$sortTime,sortPeak=$sortPeak,codegenMs=$codegenMs"
  }

  private def metricString(plan: SparkPlan): String = {
    plan.metrics.toSeq.sortBy(_._1).flatMap { case (name, metric) =>
      val value = metric.value
      if (value == 0L) None else Some(s"$name=$value")
    }.mkString(",")
  }

  private def countJoinLines(nodes: Seq[NodeInfo]): Seq[String] = {
    nodes.filter(_.plan.getClass.getSimpleName.contains("CountJoin")).map { n =>
      val cls = n.plan.getClass.getSimpleName
      val details = n.plan match {
        case cj: HashCountJoin =>
          val rightKeys = cj.rightKeys.map(_.sql).mkString("[", ",", "]")
          val groups = cj.groupRight.map(_.sql).mkString("[", ",", "]")
          val aggs = cj.aggregatesRight.map(_.aggregateFunction.sql).mkString("[", ",", "]")
          s" rightKeys=$rightKeys groups=$groups aggs=$aggs"
        case _ => ""
      }
      s"${"  " * n.depth}${n.plan.nodeName}<$cls> codegen=${n.inCodegen}${details} " +
        s"${metricString(n.plan)}"
    }
  }

  private def run(sqlText: String, mode: Mode): RunResult = {
    withSQLConf(mode.confs: _*) {
      val df = sql(sqlText)
      val optimized = df.queryExecution.optimizedPlan
      val countJoins = optimized.collect { case _: CountJoin => 1 }.size
      val start = System.nanoTime()
      val rows = df.collect().length
      val ms = (System.nanoTime() - start) / 1000000
      RunResult(rows, ms, countJoins, finalPlan(df.queryExecution.executedPlan))
    }
  }

  test("TPC-DS CountJoin performance diagnostics") {
    assume(new File(dataDir).exists(), s"TPC-DS data dir $dataDir absent; skipping")
    loadTables()

    // Global JIT/codegen warmup: the first query of a fresh JVM pays a one-time global compilation
    // cost that would otherwise inflate its base timing (and overstate its apparent speedup). Run
    // one throwaway execution before any measurement so all queries start from a warm JVM.
    if (queryNames.nonEmpty && modes.nonEmpty) {
      try {
        val warm = resourceToString(s"tpcds/${queryNames.head}.sql",
          classLoader = Thread.currentThread().getContextClassLoader)
        run(warm, modes.head)
      } catch { case _: Throwable => () }
    }

    for (name <- queryNames) {
      val sqlText = resourceToString(s"tpcds/$name.sql",
        classLoader = Thread.currentThread().getContextClassLoader)
      // scalastyle:off println
      println(s"TPCDS-DIAG-QUERY: $name")
      // scalastyle:on println
      modes.foreach { mode =>
        try {
          (1 to warmupRuns).foreach(_ => run(sqlText, mode)) // warm JIT/cache/codegen; discard
          val measured = (1 to measureRuns).map(_ => run(sqlText, mode))
          val result = measured.last
          val timings = measured.map(_.ms)
          val minMs = timings.min
          val medMs = timings.sorted.apply(timings.size / 2)
          val nodes = allNodes(result.physicalPlan)
          // scalastyle:off println
          println(s"TPCDS-DIAG: $name | ${mode.name} | rows=${result.rows} | " +
            s"ms=${minMs} | msMin=${minMs} | msMed=${medMs} | " +
            s"msAll=${timings.mkString("/")} | warmup=${warmupRuns} iters=${measureRuns} | " +
            s"logicalCountJoins=${result.optimizedCountJoins} | " +
            s"operators=${operatorCounts(nodes)} | metrics=${metricSummary(nodes)}")
          if (printPlan) {
            println(s"TPCDS-DIAG-PLAN: $name | ${mode.name}")
            println(result.physicalPlan.treeString)
          }
          countJoinLines(nodes).foreach { line =>
            println(s"TPCDS-DIAG-COUNTJOIN: $name | ${mode.name} | $line")
          }
          // scalastyle:on println
        } catch {
          case t: Throwable =>
            // scalastyle:off println
            println(s"TPCDS-DIAG: $name | ${mode.name} | EXCEPTION " +
              s"${t.getClass.getSimpleName}: ${Option(t.getMessage).getOrElse("")}")
            // scalastyle:on println
        }
      }
    }
  }

  // Demonstrates the no-op dimension elimination on q50 with REAL column stats. The default
  // diagnostics path uses parquet temp views with no ANALYZE'd NDV, so the (correctly conservative)
  // elimination is inert there. Here we register q50's tables as catalog tables, ANALYZE the
  // dimensions for column stats, enable CBO, and compare base / prod-noelim / prod-elim.
  test("q50 stats-equipped wall-clock: no-op dimension elimination") {
    assume(new File(s"$parquetDir/date_dim").exists() &&
      new File(s"$parquetDir/store_sales").exists(),
      s"q50 parquet tables absent under $parquetDir; skipping")
    val q50Tables = Seq("store_sales", "store_returns", "store", "date_dim")
    withTable(q50Tables: _*) {
      q50Tables.foreach { t =>
        spark.sql(s"DROP TABLE IF EXISTS $t")
        spark.sql(s"CREATE TABLE $t USING parquet LOCATION " +
          s"'${new File(s"$parquetDir/$t").getAbsolutePath}'")
      }
      // Full column (NDV) stats on all tables so CBO has complete cardinality info and PK
      // uniqueness is provable.
      q50Tables.foreach(t =>
        spark.sql(s"ANALYZE TABLE $t COMPUTE STATISTICS FOR ALL COLUMNS"))

      val sqlText = resourceToString("tpcds/q50.sql",
        classLoader = Thread.currentThread().getContextClassLoader)
      val cbo = Seq(
        SQLConf.CBO_ENABLED.key -> "true",
        SQLConf.PLAN_STATS_ENABLED.key -> "true")
      val on = Seq(
        SQLConf.YANNAKAKIS_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_COST_GATE_ENABLED.key -> "true")
      val statsModes = Seq(
        Mode("base", cbo :+ (SQLConf.YANNAKAKIS_ENABLED.key -> "false")),
        Mode("prod-noelim",
          cbo ++ on :+ (SQLConf.YANNAKAKIS_ELIMINATE_NOOP_DIMS_ENABLED.key -> "false")),
        Mode("prod-elim",
          cbo ++ on :+ (SQLConf.YANNAKAKIS_ELIMINATE_NOOP_DIMS_ENABLED.key -> "true")))
      statsModes.foreach { mode =>
        run(sqlText, mode) // warmup
        val measured = (1 to 3).map(_ => run(sqlText, mode))
        val result = measured.last
        val minMs = measured.map(_.ms).min
        val nodes = allNodes(result.physicalPlan)
        // scalastyle:off println
        println(s"TPCDS-Q50STATS: ${mode.name} | rows=${result.rows} | ms=${minMs} | " +
          s"logicalCountJoins=${result.optimizedCountJoins} | " +
          s"operators=${operatorCounts(nodes)} | metrics=${metricSummary(nodes)}")
        // scalastyle:on println
      }
    }
  }

  // Verify the production wins under REAL CBO column stats (the benchmark uses temp views with no
  // ANALYZE, so the NDV-dependent gate arms are inert there). Registers all TPC-DS tables as
  // catalog tables over the parquet, ANALYZEs for column stats, enables CBO, reports base vs prod
  // the at-risk pre-agg wins (q4/q11) and the asserted-safe CountJoin wins (q25/q29/q64). Key
  // question: do q4/q11 still rewrite under CBO (pre-agg fires -> fewer SMJ / less shuffle),
  // or do the broadcast/dominated arms skip them (prod ops == base)? Asserts results == vanilla.
  test("q4/q11 + CountJoin wins under CBO column stats") {
    assume(new File(s"$parquetDir/store_sales").exists() &&
      new File(s"$parquetDir/customer").exists(),
      s"q4/q11 parquet tables absent under $parquetDir; skipping")
    val allTables = tableColumns.keys.toSeq.filter(t => new File(s"$parquetDir/$t").exists())
    withTable(allTables: _*) {
      allTables.foreach { t =>
        spark.sql(s"DROP TABLE IF EXISTS $t")
        spark.sql(s"CREATE TABLE $t USING parquet LOCATION " +
          s"'${new File(s"$parquetDir/$t").getAbsolutePath}'")
        // inventory is huge and unused by the verified queries; column-analyze the rest for NDV.
        if (t == "inventory") spark.sql(s"ANALYZE TABLE $t COMPUTE STATISTICS")
        else spark.sql(s"ANALYZE TABLE $t COMPUTE STATISTICS FOR ALL COLUMNS")
      }
      val cbo = Seq(
        SQLConf.CBO_ENABLED.key -> "true",
        SQLConf.PLAN_STATS_ENABLED.key -> "true")
      val baseMode = Mode("base", cbo :+ (SQLConf.YANNAKAKIS_ENABLED.key -> "false"))
      val prodMode = Mode("prod", cbo ++ Seq(
        SQLConf.YANNAKAKIS_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_COST_GATE_ENABLED.key -> "true"))
      for (q <- Seq("q4", "q11", "q25", "q29", "q64")) {
        val sqlText = resourceToString(s"tpcds/$q.sql",
          classLoader = Thread.currentThread().getContextClassLoader)
        run(sqlText, baseMode) // warmup
        val baseR = run(sqlText, baseMode)
        val baseNodes = allNodes(baseR.physicalPlan)
        run(sqlText, prodMode) // warmup
        val prodR = run(sqlText, prodMode)
        val prodNodes = allNodes(prodR.physicalPlan)
        // scalastyle:off println
        println(s"TPCDS-CBOVERIFY: $q | base=${baseR.ms}ms/${baseR.rows}r " +
          s"prod=${prodR.ms}ms/${prodR.rows}r prodLcj=${prodR.optimizedCountJoins} | " +
          s"baseOps=${operatorCounts(baseNodes)} | prodOps=${operatorCounts(prodNodes)} | " +
          s"baseMet=${metricSummary(baseNodes)} | prodMet=${metricSummary(prodNodes)}")
        // scalastyle:on println
        assert(baseR.rows == prodR.rows, s"$q row count differs under CBO (correctness)")
      }
    }
  }

  // Calibration: with the runtime revert ENABLED (divergence factor 4.0, floor 1) under real CBO
  // stats, none of the verified wins must be reverted - under ANALYZE'd stats their build estimate
  // holds (materialized ~= estimate), so the divergence criterion keeps them. Confirms on real data
  // what DemoteNonReducingCountJoinSuite proves on stubs, and that results stay correct with revert
  // on. (A build-vs-probe ratio reverted q25 here - a reducing win can have build >= probe.)
  test("verified wins are NOT reverted under CBO with runtime revert enabled") {
    assume(new File(s"$parquetDir/store_sales").exists() &&
      new File(s"$parquetDir/customer").exists(),
      s"parquet tables absent under $parquetDir; skipping")
    val allTables = tableColumns.keys.toSeq.filter(t => new File(s"$parquetDir/$t").exists())
    withTable(allTables: _*) {
      allTables.foreach { t =>
        spark.sql(s"DROP TABLE IF EXISTS $t")
        spark.sql(s"CREATE TABLE $t USING parquet LOCATION " +
          s"'${new File(s"$parquetDir/$t").getAbsolutePath}'")
        if (t == "inventory") spark.sql(s"ANALYZE TABLE $t COMPUTE STATISTICS")
        else spark.sql(s"ANALYZE TABLE $t COMPUTE STATISTICS FOR ALL COLUMNS")
      }
      val cbo = Seq(
        SQLConf.CBO_ENABLED.key -> "true",
        SQLConf.PLAN_STATS_ENABLED.key -> "true")
      val baseMode = Mode("base", cbo :+ (SQLConf.YANNAKAKIS_ENABLED.key -> "false"))
      val revertMode = Mode("revert", cbo ++ Seq(
        SQLConf.YANNAKAKIS_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_COST_GATE_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_RUNTIME_REVERT_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_RUNTIME_REVERT_MIN_BUILD_ROWS.key -> "1",
        SQLConf.YANNAKAKIS_RUNTIME_REVERT_DIVERGENCE_FACTOR.key -> "4.0"))
      // q25/q29/q64 produce CountJoin execs; q4/q11 pre-aggregate. Assert the CountJoin wins keep
      // their CountJoin execs in the final AQE plan (not reverted); all keep correct results.
      val countJoinWins = Set("q25", "q29", "q64")
      for (q <- Seq("q4", "q11", "q25", "q29", "q64")) {
        val sqlText = resourceToString(s"tpcds/$q.sql",
          classLoader = Thread.currentThread().getContextClassLoader)
        val baseR = run(sqlText, baseMode)
        run(sqlText, revertMode) // warmup
        val revR = run(sqlText, revertMode)
        val revOps = operatorCounts(allNodes(revR.physicalPlan))
        // scalastyle:off println
        println(s"TPCDS-CBOREVERT: $q | base=${baseR.rows}r revert=${revR.rows}r | revOps=$revOps")
        // scalastyle:on println
        assert(baseR.rows == revR.rows, s"$q rows differ with runtime revert enabled (correctness)")
        if (countJoinWins.contains(q)) {
          assert(revOps.contains("CountJoin"),
            s"$q was REVERTED under CBO with revert enabled - the criterion mis-classified a win " +
              s"(its count-join build is not << probe). revOps=$revOps")
        }
      }
    }
  }
}
