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

    for (name <- queryNames) {
      val sqlText = resourceToString(s"tpcds/$name.sql",
        classLoader = Thread.currentThread().getContextClassLoader)
      // scalastyle:off println
      println(s"TPCDS-DIAG-QUERY: $name")
      // scalastyle:on println
      modes.foreach { mode =>
        try {
          val result = run(sqlText, mode)
          val nodes = allNodes(result.physicalPlan)
          // scalastyle:off println
          println(s"TPCDS-DIAG: $name | ${mode.name} | rows=${result.rows} | " +
            s"ms=${result.ms} | logicalCountJoins=${result.optimizedCountJoins} | " +
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
}
