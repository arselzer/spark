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

import java.io.{File, FileInputStream}
import java.util.zip.GZIPInputStream

import scala.io.Source

import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types._

/**
 * Manual benchmark over the STATS-CEB workload (Stack-Exchange statistics; 146 COUNT(*) join
 * queries with filters - a standard cardinality-estimation / join benchmark). Loads the gzipped
 * SQL dump into parquet (cached), then runs every query with the count-join rewrite off vs on
 * (whole-stage codegen), asserting the COUNT matches vanilla and reporting per-query timing and a
 * geomean speedup over the queries the rewrite actually fired on. Skips itself if the data is
 * absent. Run with a large heap:
 *   build/sbt 'set Test/javaOptions += "-Xmx10g"' \
 *     "sql/testOnly org.apache.spark.sql.STATSBenchmarkSuite"
 */
class STATSBenchmarkSuite extends QueryTest with SharedSparkSession {

  override protected def sparkConf: org.apache.spark.SparkConf =
    super.sparkConf
      .set(SQLConf.YANNAKAKIS_COST_GATE_ENABLED.key, "false")
      .set(SQLConf.SHUFFLE_PARTITIONS.key, "16")
      .set(SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key, (10L * 1024 * 1024).toString)

  private val dumpPath = "/home/as/git/Spark-Y/data/sql-dumps/stats.sql.gz"
  private val queryDir = "/home/as/git/Spark-Y/data/stats-ceb"
  private val parquetDir = "/tmp/stats-ceb-parquet"

  // INTEGER/SMALLINT -> IntegerType (avoids Short overflow; COUNT(*) filters are numeric anyway);
  // TIMESTAMP -> TimestampType. Field order matches the dump's CREATE TABLE / INSERT column order.
  private val I = IntegerType
  private val T = TimestampType
  private val schemas: Seq[(String, Seq[(String, DataType)])] = Seq(
    "users" -> Seq("Id" -> I, "Reputation" -> I, "CreationDate" -> T, "Views" -> I,
      "UpVotes" -> I, "DownVotes" -> I),
    "posts" -> Seq("Id" -> I, "PostTypeId" -> I, "CreationDate" -> T, "Score" -> I,
      "ViewCount" -> I, "OwnerUserId" -> I, "AnswerCount" -> I, "CommentCount" -> I,
      "FavoriteCount" -> I, "LastEditorUserId" -> I),
    "postLinks" -> Seq("Id" -> I, "CreationDate" -> T, "PostId" -> I, "RelatedPostId" -> I,
      "LinkTypeId" -> I),
    "postHistory" -> Seq("Id" -> I, "PostHistoryTypeId" -> I, "PostId" -> I, "CreationDate" -> T,
      "UserId" -> I),
    "comments" -> Seq("Id" -> I, "PostId" -> I, "Score" -> I, "CreationDate" -> T, "UserId" -> I),
    "votes" -> Seq("Id" -> I, "PostId" -> I, "VoteTypeId" -> I, "CreationDate" -> T,
      "UserId" -> I, "BountyAmount" -> I),
    "badges" -> Seq("Id" -> I, "UserId" -> I, "Date" -> T),
    "tags" -> Seq("Id" -> I, "Count" -> I, "ExcerptPostId" -> I))

  private def parseCell(raw: String, dt: DataType): Any = {
    val v = raw.trim
    if (v.equalsIgnoreCase("NULL") || v.isEmpty) null
    else dt match {
      case TimestampType => java.sql.Timestamp.valueOf(v.stripPrefix("'").stripSuffix("'"))
      case _ => v.toInt
    }
  }

  /** Parse the gzipped dump (INSERT INTO t (...) VALUES, then one `(..),`/`(..);` tuple per line)
   *  into a DataFrame per table, written to parquet. One-time; subsequent runs read the parquet. */
  private def buildParquetFromDump(): Unit = {
    val gz = new GZIPInputStream(new FileInputStream(dumpPath))
    val lines = try Source.fromInputStream(gz).getLines().toArray finally gz.close()
    val schemaMap = schemas.toMap
    val rowsByTable =
      scala.collection.mutable.Map[String, scala.collection.mutable.ArrayBuffer[Row]]()
    schemas.foreach { case (t, _) =>
      rowsByTable(t) = scala.collection.mutable.ArrayBuffer.empty[Row] }
    var current: String = null
    val insertRe = """(?i)^INSERT INTO\s+(\w+)""".r
    lines.foreach { line =>
      val l = line.trim
      insertRe.findFirstMatchIn(l) match {
        case Some(m) => current = schemaMap.keys.find(_.equalsIgnoreCase(m.group(1))).orNull
        case None =>
          if (current != null && l.startsWith("(")) {
            val inner = l.stripSuffix(",").stripSuffix(";").trim.stripPrefix("(").stripSuffix(")")
            val cols = schemaMap(current)
            val parts = inner.split(",")
            if (parts.length == cols.length) {
              rowsByTable(current) += Row.fromSeq(parts.zip(cols).map { case (p, (_, dt)) =>
                parseCell(p, dt) }.toIndexedSeq)
            }
          }
      }
    }
    schemas.foreach { case (t, cols) =>
      val st = StructType(cols.map { case (n, dt) => StructField(n, dt, nullable = true) })
      val df = spark.createDataFrame(spark.sparkContext.parallelize(rowsByTable(t).toSeq), st)
      df.write.mode("overwrite").parquet(s"$parquetDir/$t")
    }
  }

  private def loadStats(): Unit = {
    if (!new File(parquetDir).isDirectory) buildParquetFromDump()
    schemas.foreach { case (t, _) =>
      spark.read.parquet(s"$parquetDir/$t").createOrReplaceTempView(t)
    }
  }

  // Run a single COUNT(*) query under `conf` with a wall-clock cap. Returns Some((count, ms)) if it
  // finishes, or None on timeout/error. On timeout the Spark job is CANCELLED (interruptOnCancel)
  // so it stops consuming resources before the next query. STATS-CEB queries have huge fan-out
  // intermediates, so vanilla materialization routinely does-not-finish while the count-join does.
  private def runCount(conf: Seq[(String, String)], q: String, secs: Int, tag: String)
      : Option[(Long, Long)] = withSQLConf(conf: _*) {
    val group = s"stats-$tag"
    spark.sparkContext.setJobGroup(group, q, interruptOnCancel = true)
    @volatile var result: Option[(Long, Long)] = None
    val worker = new Thread(() => {
      try {
        val s = System.nanoTime()
        val rows = spark.sql(q).collect()
        val cnt = if (rows.isEmpty) 0L else rows(0).getLong(0)
        result = Some((cnt, (System.nanoTime() - s) / 1000000))
      } catch { case _: Throwable => () } // cancelled/errored -> result stays None
    })
    worker.start()
    worker.join(secs * 1000L)
    if (worker.isAlive) { spark.sparkContext.cancelJobGroup(group); worker.interrupt() }
    spark.sparkContext.clearJobGroup()
    result
  }

  private def geomean(xs: Seq[Double]): Double =
    if (xs.isEmpty) 0.0 else math.exp(xs.map(math.log).sum / xs.size)

  test("STATS-CEB benchmark: count-join rewrite off vs on, all 146 queries") {
    assume(new File(dumpPath).isFile, s"STATS dump not present at $dumpPath")
    assume(new File(queryDir).isDirectory, s"STATS queries not present at $queryDir")
    loadStats()
    val queries =
      new File(queryDir).listFiles().filter(_.getName.endsWith(".sql")).sortBy(_.getName)
    val aqeOff = Seq(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "false")
    val offConf = aqeOff ++ Seq(SQLConf.YANNAKAKIS_ENABLED.key -> "false",
      SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "true")
    val onConf = aqeOff ++ Seq(SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "true")
    val offTimeout = sys.env.getOrElse("STATS_OFF_TIMEOUT", "15").toInt
    val onTimeout = sys.env.getOrElse("STATS_ON_TIMEOUT", "60").toInt

    // Gate-ON config: does the CURRENT (broadcast-size) cost gate keep or skip the rewrite?
    val gateOnConf = onConf :+ (SQLConf.YANNAKAKIS_COST_GATE_ENABLED.key -> "true")

    var rewritten = 0
    var mismatches = 0
    var offDNF = 0
    var onDNF = 0
    var gateSkips = 0       // queries the gate skips that would otherwise fire
    var gateSkipsAWin = 0   // gate skips a query where vanilla DNF -> a lost big win
    val speedups = scala.collection.mutable.ArrayBuffer[Double]() // only where both finished
    // scalastyle:off println
    println(s"=== STATS-CEB: ${queries.length} queries, vanilla(<=${offTimeout}s) vs rewrite ===")
    queries.zipWithIndex.foreach { case (qf, idx) =>
      val q = Source.fromFile(qf).mkString.trim.stripSuffix(";")
      val name = qf.getName.stripSuffix(".sql")
      val fired =
        try withSQLConf(onConf: _*) {
          sql(q).queryExecution.optimizedPlan.toString.contains("CountJoin")
        } catch { case _: Throwable => false }
      if (fired) rewritten += 1
      val gateKeeps =
        try withSQLConf(gateOnConf: _*) {
          sql(q).queryExecution.optimizedPlan.toString.contains("CountJoin")
        } catch { case _: Throwable => false }
      val on = runCount(onConf, q, onTimeout, "on")
      val off = runCount(offConf, q, offTimeout, "off")
      if (off.isEmpty) offDNF += 1
      if (on.isEmpty) onDNF += 1
      if (fired && !gateKeeps) {
        gateSkips += 1
        if (off.isEmpty) {
          gateSkipsAWin += 1
          println(f"  GATE-MISSKIP $name: vanilla DNF but the size-gate SKIPS the rewrite")
        }
      }
      (off, on) match {
        case (Some((vc, _)), Some((oc, _))) if vc != oc =>
          mismatches += 1; println(f"  MISMATCH $name: off=$vc on=$oc")
        case _ =>
      }
      val spStr = (off, on) match {
        case (Some((_, vMs)), Some((_, oMs))) =>
          val sp = if (oMs == 0) 1.0 else vMs.toDouble / oMs.toDouble
          speedups += sp; f"$sp%5.2fx"
        case (None, Some(_)) => s">${offTimeout * 1000 / math.max(1, on.get._2)}x (vanilla DNF)"
        case _ => "-"
      }
      val offStr = off.map(r => s"${r._2}ms").getOrElse(s"DNF>${offTimeout}s")
      val onStr = on.map(r => s"${r._2}ms").getOrElse(s"DNF>${onTimeout}s")
      println(f"STATS-Q ${idx + 1}%3d/${queries.length} $name%-10s fired=$fired%-5s " +
        f"off=$offStr%-9s on=$onStr%-9s sp=$spStr")
    }
    println(f"STATS-CEB: ${queries.length} queries | rewritten=$rewritten | " +
      f"vanilla-DNF(>${offTimeout}s)=$offDNF | rewrite-DNF=$onDNF | mismatches=$mismatches")
    println(f"STATS-CEB: geomean speedup where BOTH finished (n=${speedups.size}) = " +
      f"${geomean(speedups.toSeq)}%.2fx ; vanilla DID-NOT-FINISH on $offDNF/${queries.length}")
    println(f"STATS-CEB GATE (broadcast-size gate): skips $gateSkips/$rewritten rewrites; " +
      f"$gateSkipsAWin of those are vanilla-DNF WINS wrongly thrown away")
    // scalastyle:on println
    assert(mismatches == 0, s"$mismatches STATS queries gave a different count under the rewrite")
  }
}
