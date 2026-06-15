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
import org.apache.spark.sql.execution.joins.HashCountJoin
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession

/**
 * Manual benchmark (not part of normal CI): runs grouping-heavy TPC-H queries over a real
 * sf1 parquet dataset with the count-join rewrite off vs on(interpreted) vs on(whole-stage
 * codegen), reporting median-of-K timings and the count-join shape. Skips itself if the
 * dataset is not present. Run with a large heap, e.g.
 *   build/sbt 'set Test/javaOptions += "-Xmx12g"' \
 *     "sql/testOnly org.apache.spark.sql.TPCHBenchmarkSuite"
 */
class TPCHBenchmarkSuite extends QueryTest with SharedSparkSession {

  override protected def sparkConf: org.apache.spark.SparkConf =
    super.sparkConf
      .set(SQLConf.YANNAKAKIS_COST_GATE_ENABLED.key, "false")
      .set(SQLConf.SHUFFLE_PARTITIONS.key, "16")
      .set(SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key, (10L * 1024 * 1024).toString)

  private val tpchDir = "/tmp/tpch-sf1-pq"

  private val yannakakisOn = Seq(
    SQLConf.YANNAKAKIS_ENABLED.key -> "true",
    SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
    SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true")

  private def loadTpch(): Unit = {
    Seq("region", "nation", "supplier", "customer", "part", "partsupp", "orders", "lineitem")
      .foreach { t =>
        spark.read.parquet(s"$tpchDir/$t").createOrReplaceTempView(t)
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

  // Tolerant comparison: the count-join sums in a different order than vanilla, so double/decimal
  // aggregates can differ in their last digits (floating-point non-associativity) - compare with
  // a relative tolerance rather than exact string equality.
  private def cellsMatch(a: Any, b: Any): Boolean = (a, b) match {
    case (null, null) => true
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

  test("TPC-H benchmark: count-join codegen on vs interpreted vs off (sf1)") {
    assume(new File(tpchDir).isDirectory, s"TPC-H parquet dataset not present at $tpchDir")
    loadTpch()
    val iters = 3
    val aqeOff = Seq(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "false")
    // off = vanilla Spark with whole-stage codegen ON (the default; set explicitly so the
    // off-vs-on(codegen) comparison is unambiguously codegen-vs-codegen and can't silently
    // change if the default ever does).
    val offConf = aqeOff ++ Seq(
      SQLConf.YANNAKAKIS_ENABLED.key -> "false",
      SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "true")
    val interpConf = aqeOff ++ yannakakisOn :+
      (SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "false")
    val codegenConf = aqeOff ++ yannakakisOn :+
      (SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "true")
    val mismatches = scala.collection.mutable.ListBuffer[String]()
    // scalastyle:off println
    println("TPCH-BENCH: query | count-joins(grouping) | off | on(interp) | on(codegen) | match")
    for (name <- (1 to 22).map(i => s"q$i")) {
      val q =
        try resourceToString(s"tpch/$name.sql",
          classLoader = Thread.currentThread().getContextClassLoader)
        catch { case _: Throwable => null }
      if (q == null) {
        println(s"TPCH-BENCH: $name | (no sql)")
      } else {
        val cjShape = withSQLConf(codegenConf: _*) {
          val cjs = sql(q).queryExecution.executedPlan.collect {
            case cj: HashCountJoin => cj.groupRight.nonEmpty
          }
          (cjs.size, cjs.count(identity))
        }
        Seq(offConf, interpConf, codegenConf).foreach { c =>
          withSQLConf(c: _*) { try sql(q).collect() catch { case _: Throwable => } }  // warm up
        }
        val offTs = scala.collection.mutable.ArrayBuffer[Long]()
        val intTs = scala.collection.mutable.ArrayBuffer[Long]()
        val cgTs = scala.collection.mutable.ArrayBuffer[Long]()
        var offRows: Seq[Row] = null
        var cgRows: Seq[Row] = null
        for (_ <- 0 until iters) {
          val (o, oMs) = withSQLConf(offConf: _*) { timeMs(sql(q).collect().toSeq) }
          val (i, iMs) = withSQLConf(interpConf: _*) { timeMs(sql(q).collect().toSeq) }
          val (c, cMs) = withSQLConf(codegenConf: _*) { timeMs(sql(q).collect().toSeq) }
          o.foreach { r => offTs += oMs; offRows = r }
          i.foreach { _ => intTs += iMs }
          c.foreach { r => cgTs += cMs; cgRows = r }
        }
        val matched = offRows != null && cgRows != null && rowsMatch(offRows, cgRows)
        println(s"TPCH-BENCH: $name | ${cjShape._1}(${cjShape._2} grouping) | " +
          s"off=${median(offTs.toSeq)}ms | interp=${median(intTs.toSeq)}ms | " +
          s"codegen=${median(cgTs.toSeq)}ms | match=$matched")
        if (offRows != null && cgRows != null && !matched) mismatches.append(name)
      }
    }
    // scalastyle:on println
    assert(mismatches.isEmpty, s"codegen result diverged from vanilla for: ${mismatches.mkString}")
  }

  test("TPC-H full 22-query sweep: rewrite matches vanilla (sf1)") {
    assume(new File(tpchDir).isDirectory, s"TPC-H parquet dataset not present at $tpchDir")
    loadTpch()
    val rewriteOn = Seq(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true",
      SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "false")
    val report = new StringBuilder
    val failures = scala.collection.mutable.ListBuffer[String]()
    // scalastyle:off println
    for (name <- (1 to 22).map(i => s"q$i")) {
      val query =
        try resourceToString(s"tpch/$name.sql",
          classLoader = Thread.currentThread().getContextClassLoader)
        catch { case _: Throwable => null }
      val verdict = if (query == null) "NO-SQL" else {
        var baseline: Either[Throwable, Seq[Row]] = null
        try withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
          baseline = Right(sql(query).collect().toSeq)
        } catch { case t: Throwable => baseline = Left(t) }
        var rewritten: Either[Throwable, (Seq[Row], Boolean)] = null
        try withSQLConf(rewriteOn: _*) {
          val df = sql(query)
          val rows = df.collect().toSeq
          val plan = df.queryExecution.executedPlan.toString
          rewritten = Right((rows, plan.contains("CountJoin") || plan.contains("LeftSemi")))
        } catch { case t: Throwable => rewritten = Left(t) }
        (baseline, rewritten) match {
          case (Left(t), _) => s"BASELINE-FAIL ${t.getClass.getSimpleName}"
          case (Right(_), Left(t)) =>
            val root = Option(t.getCause).getOrElse(t)
            s"EXCEPTION ${root.getClass.getSimpleName}: " +
              Option(root.getMessage).getOrElse("").take(160)
          case (Right(b), Right((r, applied))) =>
            val tag = if (applied) "applied" else "NOT-applied"
            if (rowsMatch(b, r)) s"OK ($tag, ${b.size} rows)" else s"MISMATCH ($tag)"
        }
      }
      val line = s"TPCH-SWEEP: $name -> $verdict"
      println(line)
      report.append(line).append('\n')
      if (verdict.startsWith("MISMATCH") || verdict.startsWith("EXCEPTION")) failures.append(line)
    }
    println("TPCH-SWEEP-SUMMARY:\n" + report)
    // scalastyle:on println
    assert(failures.isEmpty,
      s"TPC-H rewrite regressed on ${failures.size} query/queries (MISMATCH=wrong results, " +
        s"EXCEPTION=rewrite threw; BASELINE-FAIL/NO-SQL excluded):\n" + failures.mkString("\n"))
  }
}
