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
}
