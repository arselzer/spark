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

import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession

/**
 * Manual benchmark (not part of normal CI): runs JOB (Join Order Benchmark) queries over the
 * real IMDB parquet dataset with the Yannakakis rewrite on vs off, and prints per-query timings.
 * Skips itself if the dataset is not present. Run with a large heap, e.g.
 *   build/sbt 'set Test/javaOptions += "-Xmx12g"' \
 *     "sql/testOnly org.apache.spark.sql.JOBBenchmarkSuite"
 */
class JOBBenchmarkSuite extends QueryTest with SharedSparkSession {

  override protected def sparkConf: org.apache.spark.SparkConf =
    super.sparkConf
      .set(SQLConf.YANNAKAKIS_COST_GATE_ENABLED.key, "false")
      .set(SQLConf.SHUFFLE_PARTITIONS.key, "16")
      .set(SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key, (10L * 1024 * 1024).toString)

  private val imdbDir = "/home/as/git/Spark-Y/data/parquet/imdb"
  private val jobDir = "/home/as/git/Spark-Y/data/job"

  private val yannakakisOn = Seq(
    SQLConf.YANNAKAKIS_ENABLED.key -> "true",
    SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true")

  private def loadImdb(): Unit = {
    new File(imdbDir).listFiles().filter(_.isDirectory).foreach { d =>
      spark.read.parquet(d.getPath).createOrReplaceTempView(d.getName)
    }
  }

  private def timeMs[T](f: => T): (Either[Throwable, T], Long) = {
    val start = System.nanoTime()
    try {
      val r = f
      (Right(r), (System.nanoTime() - start) / 1000000)
    } catch {
      case t: Throwable => (Left(t), (System.nanoTime() - start) / 1000000)
    }
  }

  private def median(xs: Seq[Long]): Long = {
    val s = xs.sorted
    if (s.isEmpty) 0L else s(s.size / 2)
  }

  /**
   * Turns a JOB query (`SELECT MIN(...) ... FROM ... WHERE ...`) into an ungrouped
   * `SELECT count(*) ... FROM ... WHERE ...` over the same join graph. count(*) has no grouping
   * and references no attribute, so the rewrite produces a chain of non-grouping inner count
   * joins - exactly the path codegenCountInner handles - instead of the 0MA LeftSemi reduction.
   */
  private def toCountStar(sqlText: String): String = {
    val lower = sqlText.toLowerCase(java.util.Locale.ROOT)
    val fromIdx = lower.indexOf("from")
    if (fromIdx < 0) sqlText else s"SELECT count(*) AS c ${sqlText.substring(fromIdx)}"
  }

  test("JOB benchmark: yannakakis on vs off") {
    assume(new File(imdbDir).isDirectory, s"IMDB parquet dataset not present at $imdbDir")
    loadImdb()
    val queries = Seq("1a", "3a", "6a", "8a", "16a", "17a", "26a", "33a")
    // scalastyle:off println
    println("JOB-BENCH: query | off | on | speedup | applied | match")
    for (q <- queries) {
      val file = new File(s"$jobDir/$q.sql")
      if (!file.exists()) {
        println(s"JOB-BENCH: $q | (no sql file)")
      } else {
        val src = scala.io.Source.fromFile(file)
        val sqlText = try src.mkString.trim.stripSuffix(";") finally src.close()
        // Warm up data caches / JIT once (rewrite on), result discarded.
        withSQLConf(yannakakisOn: _*) {
          try sql(sqlText).collect() catch { case _: Throwable => }
        }
        val applied = withSQLConf(yannakakisOn: _*) {
          try {
            val p = sql(sqlText).queryExecution.executedPlan.toString
            // 0MA queries reduce via LeftSemi; counting (unguarded/guarded) ones via CountJoin.
            p.contains("LeftSemi") || p.contains("CountJoin")
          } catch { case _: Throwable => false }
        }
        val (onRes, onMs) = withSQLConf(yannakakisOn: _*) {
          timeMs(sql(sqlText).collect().toSeq)
        }
        val (offRes, offMs) = withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
          timeMs(sql(sqlText).collect().toSeq)
        }
        val matched = (onRes, offRes) match {
          case (Right(a), Right(b)) => a.map(_.toString).toSet == b.map(_.toString).toSet
          case _ => false
        }
        val onStr = onRes.fold(t => s"ERR:${t.getClass.getSimpleName}", _ => s"${onMs}ms")
        val offStr = offRes.fold(t => s"ERR:${t.getClass.getSimpleName}", _ => s"${offMs}ms")
        val speedup = (onRes, offRes) match {
          case (Right(_), Right(_)) if onMs > 0 => f"${offMs.toDouble / onMs}%.2fx"
          case _ => "-"
        }
        println(s"JOB-BENCH: $q | off=$offStr | on=$onStr | speedup=$speedup | " +
          s"applied=$applied | match=$matched")
      }
    }
    // scalastyle:on println
  }

  test("JOB count(*) benchmark: count-join whole-stage codegen on vs off") {
    assume(new File(imdbDir).isDirectory, s"IMDB parquet dataset not present at $imdbDir")
    loadImdb()
    // A spread of join sizes; count(*) over each routes through the non-grouping count-join.
    val queries = Seq("1a", "6a", "8a", "17a", "26a")
    val iters = 5
    // Pin AQE off so the WholeStageCodegen `*(n)` markers are visible in the static plan and the
    // codegen on/off comparison is not perturbed by adaptive replanning.
    val aqeOff = Seq(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "false")
    val offConf = aqeOff :+ (SQLConf.YANNAKAKIS_ENABLED.key -> "false")
    val interpConf = aqeOff ++ yannakakisOn :+
      (SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "false")
    val codegenConf = aqeOff ++ yannakakisOn :+
      (SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "true")
    // scalastyle:off println
    println("JOB-CG: query | off | on(interp) | on(codegen) | cg-marker | match")
    for (q <- queries) {
      val file = new File(s"$jobDir/$q.sql")
      if (!file.exists()) {
        println(s"JOB-CG: $q | (no sql file)")
      } else {
        val src = scala.io.Source.fromFile(file)
        val raw = try src.mkString.trim.stripSuffix(";") finally src.close()
        val cq = toCountStar(raw)
        // Warm up once per variant (JIT / caches), results discarded.
        Seq(offConf, interpConf, codegenConf).foreach { c =>
          withSQLConf(c: _*) { try sql(cq).collect() catch { case _: Throwable => } }
        }
        val cgMarker = withSQLConf(codegenConf: _*) {
          try {
            sql(cq).queryExecution.executedPlan.toString.linesIterator
              .exists(_.matches(".*\\*\\(\\d+\\).*HashCountJoin.*"))
          } catch { case _: Throwable => false }
        }
        val offTs = scala.collection.mutable.ArrayBuffer[Long]()
        val intTs = scala.collection.mutable.ArrayBuffer[Long]()
        val cgTs = scala.collection.mutable.ArrayBuffer[Long]()
        var offRows: Seq[Row] = null
        var cgRows: Seq[Row] = null
        // Alternate the three variants each iteration so drift hits them evenly.
        for (_ <- 0 until iters) {
          val (o, oMs) = withSQLConf(offConf: _*) { timeMs(sql(cq).collect().toSeq) }
          val (i, iMs) = withSQLConf(interpConf: _*) { timeMs(sql(cq).collect().toSeq) }
          val (c, cMs) = withSQLConf(codegenConf: _*) { timeMs(sql(cq).collect().toSeq) }
          o.foreach { r => offTs += oMs; offRows = r }
          i.foreach { _ => intTs += iMs }
          c.foreach { r => cgTs += cMs; cgRows = r }
        }
        val matched = (offRows != null && cgRows != null &&
          offRows.map(_.toString) == cgRows.map(_.toString))
        println(s"JOB-CG: $q | off=${median(offTs.toSeq)}ms | " +
          s"interp=${median(intTs.toSeq)}ms | codegen=${median(cgTs.toSeq)}ms | " +
          s"cg-marker=$cgMarker | match=$matched")
        assert(matched, s"count(*) codegen result must match vanilla for $q")
      }
    }
    // scalastyle:on println
  }

  test("JOB grouped-codegen benchmark: grouping count-join on vs off (real-data)") {
    assume(new File(imdbDir).isDirectory, s"IMDB parquet dataset not present at $imdbDir")
    loadImdb()
    // 1a's join graph, grouped by columns from TWO different relations (title + company_type) so
    // the group keys can't all reach one relation -> grouping is pushed INTO a count-join (the path
    // codegenCountGroupedInner handles), unlike single-relation grouping that stays at the top
    // HashAggregate. count(*) and SUM variants hit the count-only and Sum-buffer grouped paths.
    val file = new File(s"$jobDir/1a.sql")
    assume(file.exists(), "1a.sql not present")
    val src = scala.io.Source.fromFile(file)
    val raw = try src.mkString.trim.stripSuffix(";") finally src.close()
    val from = raw.substring(raw.toLowerCase(java.util.Locale.ROOT).indexOf("from"))
    val queries = Seq(
      "grouped count(*)" ->
        (s"SELECT t.production_year AS py, ct.id AS ctid, count(*) AS c $from " +
          "group by t.production_year, ct.id"),
      "grouped sum" ->
        (s"SELECT t.production_year AS py, ct.id AS ctid, sum(mi_idx.id) AS s $from " +
          "group by t.production_year, ct.id"))
    val iters = 5
    val aqeOff = Seq(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "false")
    val offConf = aqeOff :+ (SQLConf.YANNAKAKIS_ENABLED.key -> "false")
    val interpConf = aqeOff ++ yannakakisOn :+
      (SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "false")
    val codegenConf = aqeOff ++ yannakakisOn :+
      (SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "true")
    // scalastyle:off println
    println("JOB-GCG: query | grouping-cjs | off | on(interp) | on(codegen) | match")
    var sawGroupingCodegen = false
    for ((label, gq) <- queries) {
      // Confirm the rewrite pushes grouping INTO a codegen-able count-join.
      val groupingCjs = withSQLConf((codegenConf): _*) {
        sql(gq).queryExecution.executedPlan.collect {
          case cj: org.apache.spark.sql.execution.joins.HashCountJoin
            if cj.groupRight.nonEmpty => cj.supportCodegen
        }
      }
      Seq(offConf, interpConf, codegenConf).foreach { c =>
        withSQLConf(c: _*) { try sql(gq).collect() catch { case _: Throwable => } }  // warm up
      }
      val offTs = scala.collection.mutable.ArrayBuffer[Long]()
      val intTs = scala.collection.mutable.ArrayBuffer[Long]()
      val cgTs = scala.collection.mutable.ArrayBuffer[Long]()
      var offRows: Seq[Row] = null
      var cgRows: Seq[Row] = null
      for (_ <- 0 until iters) {
        val (o, oMs) = withSQLConf(offConf: _*) { timeMs(sql(gq).collect().toSeq) }
        val (i, iMs) = withSQLConf(interpConf: _*) { timeMs(sql(gq).collect().toSeq) }
        val (c, cMs) = withSQLConf(codegenConf: _*) { timeMs(sql(gq).collect().toSeq) }
        o.foreach { r => offTs += oMs; offRows = r }
        i.foreach { _ => intTs += iMs }
        c.foreach { r => cgTs += cMs; cgRows = r }
      }
      val matched = offRows != null && cgRows != null &&
        offRows.map(_.toString).sorted == cgRows.map(_.toString).sorted
      println(s"JOB-GCG: $label | grouping-cjs=${groupingCjs.size}(codegen=" +
        s"${groupingCjs.count(identity)}) | off=${median(offTs.toSeq)}ms | " +
        s"interp=${median(intTs.toSeq)}ms | codegen=${median(cgTs.toSeq)}ms | match=$matched")
      // Any grouping count-join present must be codegen-able, and results must match vanilla.
      assert(groupingCjs.forall(identity),
        s"$label: a grouping count-join is not codegen-able")
      assert(matched, s"$label grouped codegen result must match vanilla")
      sawGroupingCodegen ||= groupingCjs.nonEmpty
    }
    // At least one query must exercise the grouped-codegen path, else this measures nothing.
    assert(sawGroupingCodegen, "no query pushed grouping into a count-join; benchmark is moot")
    // scalastyle:on println
  }
}
