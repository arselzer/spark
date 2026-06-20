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

import scala.io.Source

import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types._

/**
 * Manual benchmark over the STATS-CEB workload (Stack-Exchange statistics; 146 COUNT(*) join
 * queries with filters - a standard cardinality-estimation / join benchmark). Loads the CSV dataset
 * into parquet (cached), then runs every query with the count-join rewrite off vs on, asserting the
 * COUNT matches vanilla and reporting per-query timing and a geomean speedup over the queries the
 * rewrite fired on. STATS-CEB queries have huge fan-out intermediates, so vanilla materialization
 * routinely does-not-finish (DNF) within the timeout while the count-join (which never materializes
 * the fan-out) does - those are the biggest wins. Data + queries from the
 * End-to-End-CardEst-Benchmark repo, staged under statsDir. Skips if absent. Run with a large heap:
 *   build/sbt 'set Test/javaOptions += "-Xmx10g"' \
 *     "sql/testOnly org.apache.spark.sql.STATSBenchmarkSuite"
 */
class STATSBenchmarkSuite extends QueryTest with SharedSparkSession {

  override protected def sparkConf: org.apache.spark.SparkConf =
    super.sparkConf
      .set(SQLConf.YANNAKAKIS_COST_GATE_ENABLED.key, "false")
      .set(SQLConf.SHUFFLE_PARTITIONS.key, "16")
      .set(SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key, (10L * 1024 * 1024).toString)

  private val statsDir = "/home/as/git/Spark-Y/data/stats"
  private val csvDir = s"$statsDir/csv"
  private val queriesFile = s"$statsDir/stats_CEB.sql"
  private val parquetDir = "/tmp/stats-ceb-parquet"

  // INTEGER/SMALLINT -> IntegerType; TIMESTAMP -> TimestampType. Field order matches CSV header.
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

  private def buildParquetFromCsv(): Unit = {
    schemas.foreach { case (t, cols) =>
      val st = StructType(cols.map { case (n, dt) => StructField(n, dt, nullable = true) })
      spark.read.option("header", "true").option("timestampFormat", "yyyy-MM-dd HH:mm:ss")
        .schema(st).csv(s"$csvDir/$t.csv")
        .write.mode("overwrite").parquet(s"$parquetDir/$t")
    }
  }

  private def loadStats(): Unit = {
    if (!new File(parquetDir).isDirectory) buildParquetFromCsv()
    schemas.foreach { case (t, _) =>
      spark.read.parquet(s"$parquetDir/$t").createOrReplaceTempView(t)
    }
  }

  /** Parse stats_CEB.sql (`trueCardinality||SQL;` per line); Spark-ify `'...'::timestamp` casts. */
  private def loadQueries(): Seq[(String, String)] = {
    val src = Source.fromFile(queriesFile)
    try {
      src.getLines().filter(_.contains("||")).zipWithIndex.map { case (line, i) =>
        val sql = line.split("\\|\\|", 2)(1).trim.stripSuffix(";")
          .replaceAll("'([^']*)'::timestamp", "CAST('$1' AS TIMESTAMP)")
        f"q${i + 1}%03d" -> sql
      }.toList
    } finally src.close()
  }

  // Run a single COUNT(*) query under `conf` with a wall-clock cap. Returns Some((count, ms)) if it
  // finishes, or None on timeout/error. On timeout the Spark job is CANCELLED (interruptOnCancel),
  // it stops consuming resources before the next query.
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

  // Validates the workflow hypothesis: the 21 STATS-CEB "losers" the gate wrongly KEEPS are PK-star
  // joins (users.Id/posts.Id hub); allEquiJoinsHaveUniqueSide is meant to skip them but needs NDV
  // (distinctCount), absent on raw parquet. Registering ANALYZE'd catalog tables + CBO should let
  // the gate correctly SKIP the 21 losers while KEEPING the 4 winners (q003/q004/q007/q008)
  // keys). Plan-only (no timing), so it is fast.
  test("STATS-CEB gate decisions under ANALYZE'd NDV stats") {
    assume(new File(csvDir).isDirectory, s"STATS CSVs not present at $csvDir")
    assume(new File(queriesFile).isFile, s"STATS queries not present at $queriesFile")
    if (!new File(parquetDir).isDirectory) buildParquetFromCsv()
    withTable(schemas.map(_._1): _*) {
      schemas.foreach { case (t, _) =>
        spark.sql(s"DROP TABLE IF EXISTS $t")
        val loc = new File(s"$parquetDir/$t").getAbsolutePath
        spark.sql(s"CREATE TABLE $t USING parquet LOCATION '$loc'")
        spark.sql(s"ANALYZE TABLE $t COMPUTE STATISTICS FOR ALL COLUMNS")
      }
      val queries = loadQueries().toMap
      val gateOn = Seq(
        SQLConf.CBO_ENABLED.key -> "true",
        SQLConf.PLAN_STATS_ENABLED.key -> "true",
        SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "false",
        SQLConf.YANNAKAKIS_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
        SQLConf.YANNAKAKIS_COST_GATE_ENABLED.key -> "true")
      val losers = Seq("q011", "q013", "q025", "q027", "q028", "q029", "q036", "q041", "q054",
        "q061", "q084", "q085", "q093", "q111", "q117", "q118", "q121", "q123", "q124", "q125",
        "q137")
      val winners = Seq("q003", "q004", "q007", "q008")
      def gateKeeps(name: String): Boolean =
        withSQLConf(gateOn: _*) {
          spark.sql(queries(name)).queryExecution.optimizedPlan.toString.contains("CountJoin")
        }
      val losersSkipped = losers.count(n => !gateKeeps(n))
      val winnersKept = winners.count(gateKeeps)
      // scalastyle:off println
      println(s"STATS-CEB NDV-GATE: of 21 known losers, gate now SKIPS $losersSkipped/21; " +
        s"of 4 known winners, gate KEEPS $winnersKept/4")
      losers.foreach(n => println(s"  loser  $n gateKeeps=${gateKeeps(n)} (want false)"))
      winners.foreach(n => println(s"  winner $n gateKeeps=${gateKeeps(n)} (want true)"))
      // scalastyle:on println
      // The unique-side arm should now skip the PK-star losers; the winners (non-unique) stay kept.
      assert(losersSkipped >= 17, s"expected NDV to let the gate skip most PK-star losers, " +
        s"got $losersSkipped/21")
    }
  }

  test("STATS-CEB benchmark: count-join rewrite off vs on, all 146 queries") {
    assume(new File(csvDir).isDirectory, s"STATS CSVs not present at $csvDir")
    assume(new File(queriesFile).isFile, s"STATS queries not present at $queriesFile")
    loadStats()
    val queries = loadQueries()
    val aqeOff = Seq(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "false")
    val offConf = aqeOff ++ Seq(SQLConf.YANNAKAKIS_ENABLED.key -> "false",
      SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "true")
    val onConf = aqeOff ++ Seq(SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "true")
    val offTimeout = sys.env.getOrElse("STATS_OFF_TIMEOUT", "15").toInt
    val onTimeout = sys.env.getOrElse("STATS_ON_TIMEOUT", "60").toInt

    // Gate-ON config = prod: the cost gate decides keep-vs-skip. Comparing to the unguarded run
    // shows whether the gate skips the count-join-overhead LOSERS while keeping the WINS.
    val gateOnConf = onConf :+ (SQLConf.YANNAKAKIS_COST_GATE_ENABLED.key -> "true")
    var rewritten = 0
    var mismatches = 0
    var offDNF = 0
    var onDNF = 0
    var gateKept = 0
    var gateMissedSkipLoser = 0 // gate KEPT a query the unguarded rewrite made >=1.1x slower
    var gateLostWinner = 0      // gate SKIPPED a clear win (unguarded >=1.3x or vanilla-DNF)
    val speedups = scala.collection.mutable.ArrayBuffer[Double]()      // unguarded, both finished
    val gatedSpeedups = scala.collection.mutable.ArrayBuffer[Double]() // prod (gate decides)
    // scalastyle:off println
    println(s"=== STATS-CEB: ${queries.size} queries, vanilla(<=${offTimeout}s) vs rewrite ===")
    queries.zipWithIndex.foreach { case ((name, q), idx) =>
      def planHasCountJoin(conf: Seq[(String, String)]): Boolean =
        try withSQLConf(conf: _*) {
          sql(q).queryExecution.optimizedPlan.toString.contains("CountJoin")
        } catch { case _: Throwable => false }
      val fired = planHasCountJoin(onConf)
      val gateKeeps = fired && planHasCountJoin(gateOnConf)
      if (fired) rewritten += 1
      if (gateKeeps) gateKept += 1
      val on = runCount(onConf, q, onTimeout, "on")
      val off = runCount(offConf, q, offTimeout, "off")
      if (off.isEmpty) offDNF += 1
      if (on.isEmpty) onDNF += 1
      (off, on) match {
        case (Some((vc, _)), Some((oc, _))) if vc != oc =>
          mismatches += 1; println(f"  MISMATCH $name: off=$vc on=$oc")
        case _ =>
      }
      // Unguarded speedup, and the prod (gated) speedup: if the gate skips, prod == vanilla (1.0);
      // if it keeps, prod == the unguarded rewrite. Classify gate decisions against the outcome.
      val unguarded: Option[Double] = (off, on) match {
        case (Some((_, vMs)), Some((_, oMs))) => Some(vMs.toDouble / math.max(1, oMs))
        // vanilla DNF: lower-bound the speedup at timeout/on-ms.
        case (None, Some(_)) => Some(offTimeout * 1000.0 / math.max(1, on.get._2))
        case _ => None
      }
      unguarded.foreach { sp =>
        if (off.isDefined && on.isDefined) speedups += sp
        gatedSpeedups += (if (gateKeeps) sp else 1.0)
        val isLoser = off.isDefined && on.isDefined && sp <= 0.9
        val isWinner = sp >= 1.3 || off.isEmpty
        if (isLoser && gateKeeps) gateMissedSkipLoser += 1
        if (isWinner && !gateKeeps) gateLostWinner += 1
      }
      val spStr = unguarded.map(s => if (off.isEmpty) "vanilla-DNF" else f"$s%.2fx").getOrElse("-")
      val offStr = off.map(r => s"${r._2}ms").getOrElse(s"DNF>${offTimeout}s")
      val onStr = on.map(r => s"${r._2}ms").getOrElse(s"DNF>${onTimeout}s")
      println(f"STATS-Q ${idx + 1}%3d/${queries.size} $name%-5s fired=$fired%-5s " +
        f"gateKeeps=$gateKeeps%-5s off=$offStr%-9s on=$onStr%-9s sp=$spStr")
    }
    println(f"STATS-CEB: ${queries.size} queries | rewritten=$rewritten | gateKept=$gateKept | " +
      f"vanilla-DNF(>${offTimeout}s)=$offDNF | rewrite-DNF=$onDNF | mismatches=$mismatches")
    println(f"STATS-CEB: geomean speedup where BOTH finished (n=${speedups.size}) = " +
      f"${geomean(speedups.toSeq)}%.2fx ; vanilla DID-NOT-FINISH on $offDNF/${queries.size}")
    println(f"STATS-CEB GATE: prod geomean (gate decides, n=${gatedSpeedups.size}) = " +
      f"${geomean(gatedSpeedups.toSeq)}%.2fx | gate KEPT a loser: $gateMissedSkipLoser | " +
      f"gate SKIPPED a winner: $gateLostWinner")
    // scalastyle:on println
    assert(mismatches == 0, s"$mismatches STATS queries gave a different count under the rewrite")
  }
}
