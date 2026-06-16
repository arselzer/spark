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

import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession

/**
 * Targeted micro-benchmarks for the journal-version count-join EXTENSIONS (cross-relation
 * predicates, outer joins, cyclic bags), on self-contained synthetic data so the suite always
 * runs (no external dataset). Each scenario times vanilla Spark vs the rewrite (count-join codegen
 * on) over `iters` runs and reports the median, and ASSERTS the rewrite matches vanilla.
 *
 * Honest expectations (the extensions are generality/correctness wins; see the journal-extensions
 * notes): the cross-relation-predicate chain has a real reduction win; LEFT/FULL OUTER are
 * perf-neutral-to-modest (the matched half is reduced, the anti half(s) add work); the cyclic bag
 * IS vanilla's join, so cyclic is parity (this confirms no regression + generality, not speedup).
 *
 * Run: build/sbt "sql/testOnly org.apache.spark.sql.YannakakisExtensionsBenchmarkSuite"
 */
class YannakakisExtensionsBenchmarkSuite extends QueryTest with SharedSparkSession {

  override protected def sparkConf: org.apache.spark.SparkConf =
    super.sparkConf
      .set(SQLConf.YANNAKAKIS_COST_GATE_ENABLED.key, "false")
      .set(SQLConf.SHUFFLE_PARTITIONS.key, "16")
      .set(SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key, (10L * 1024 * 1024).toString)

  private val aqeOff = Seq(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "false")
  private val vanilla = aqeOff ++ Seq(
    SQLConf.YANNAKAKIS_ENABLED.key -> "false",
    SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "true")
  private val rewriteOn = aqeOff ++ Seq(
    SQLConf.YANNAKAKIS_ENABLED.key -> "true",
    SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
    SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true",
    SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "true")
  private val cyclicOn = rewriteOn :+ (SQLConf.YANNAKAKIS_CYCLIC_BAGS_ENABLED.key -> "true")

  private def timeMs[T](f: => T): (T, Long) = {
    val start = System.nanoTime()
    val r = f
    (r, (System.nanoTime() - start) / 1000000)
  }

  private def median(xs: Seq[Long]): Long = { val s = xs.sorted; s(s.size / 2) }

  private def cellsMatch(a: Any, b: Any): Boolean = (a, b) match {
    case (null, null) => true
    case (x: Double, y: Double) =>
      (x.isNaN && y.isNaN) || math.abs(x - y) <= 1e-6 * math.max(1.0, math.abs(x))
    case (x: java.math.BigDecimal, y: java.math.BigDecimal) =>
      x.subtract(y).abs().doubleValue() <= 1e-6 * math.max(1.0, math.abs(x.doubleValue()))
    case (x, y) => x == y
  }

  private def rowsMatch(a: Seq[Row], b: Seq[Row]): Boolean =
    a.size == b.size && a.sortBy(_.toString).zip(b.sortBy(_.toString)).forall { case (ra, rb) =>
      ra.size == rb.size && (0 until ra.size).forall(i => cellsMatch(ra.get(i), rb.get(i)))
    }

  /** Warm up both configs once, then time `iters` runs each; assert equality; print the result. */
  private def bench(name: String, query: String, onConf: Seq[(String, String)],
                    iters: Int = 3): Unit = {
    withSQLConf(vanilla: _*) { sql(query).collect() }
    withSQLConf(onConf: _*) { sql(query).collect() }
    val vRows = withSQLConf(vanilla: _*) { sql(query).collect().toSeq }
    val oRows = withSQLConf(onConf: _*) { sql(query).collect().toSeq }
    assert(rowsMatch(vRows, oRows), s"$name: rewrite result differs from vanilla")
    val vMs = median((1 to iters).map(_ =>
      withSQLConf(vanilla: _*) { timeMs(sql(query).collect()) }._2))
    val oMs = median((1 to iters).map(_ =>
      withSQLConf(onConf: _*) { timeMs(sql(query).collect()) }._2))
    val speedup = if (oMs == 0) 0.0 else vMs.toDouble / oMs.toDouble
    // scalastyle:off println
    println(f"EXT-BENCH | $name%-34s vanilla=$vMs%5dms  rewrite=$oMs%5dms" +
      f"  speedup=$speedup%4.2fx  (rows=${vRows.size})")
    // scalastyle:on println
  }

  private def genData(): Unit = {
    // Acyclic chain fact -> r -> s -> t with MODERATE fan-out via modulo keys (cardinality 1000,
    // ~4 rows/key in the chain relations). The chain's intermediate (~2-3M rows) is large enough
    // that materialize-then-aggregate costs real time, but bounded so vanilla still completes -
    // the regime where the bottom-up count reduction shows a gap without exploding the baseline.
    spark.range(0, 40000).selectExpr("id % 1000 as a", "cast(id % 1000 as double) as fm")
      .createOrReplaceTempView("ex_fact")
    spark.range(0, 4000).selectExpr("id % 1000 as a", "(id * 7) % 1000 as b", "id % 100 as rv")
      .createOrReplaceTempView("ex_r")
    spark.range(0, 4000).selectExpr("id % 1000 as b", "(id * 11) % 1000 as c", "id % 100 as sv")
      .createOrReplaceTempView("ex_s")
    spark.range(0, 4000).selectExpr("id % 1000 as c", "(id * 13) % 1000 as d", "id % 100 as tv")
      .createOrReplaceTempView("ex_t")
    // Outer fixture: a fact LEFT/FULL joined to a dimension where some keys are unmatched on each
    // side (fan-out on matched keys); dim keys 25..274, fact keys 0..299 -> both-side unmatched.
    spark.range(0, 50000)
      .selectExpr("id % 300 as k", "cast(id % 1000 as double) as fm", "id % 50 as g")
      .createOrReplaceTempView("ex_lo_fact")
    spark.range(0, 8000).selectExpr("(id % 250) + 25 as k", "cast(id % 100 as double) as x")
      .createOrReplaceTempView("ex_lo_dim")
    // Cyclic triangle over a moderate graph (cardinality 300, ~17 rows/key -> bounded fan-out).
    spark.range(0, 5000).selectExpr("id % 300 as a", "(id * 3) % 300 as b")
      .createOrReplaceTempView("ex_tri_r")
    spark.range(0, 5000).selectExpr("id % 300 as b", "(id * 5) % 300 as c")
      .createOrReplaceTempView("ex_tri_s")
    spark.range(0, 5000).selectExpr("id % 300 as a", "(id * 5) % 300 as c")
      .createOrReplaceTempView("ex_tri_t")
  }

  test("count-join extensions micro-benchmark (synthetic)") {
    genData()
    // scalastyle:off println
    println("=== Yannakakis count-join EXTENSIONS micro-benchmark (rewrite vs vanilla) ===")
    // scalastyle:on println

    // 1. Cross-relation predicate over a fan-out chain: rv (from r) + tv (from t) span two
    //    relations, so vanilla must materialize the chain to apply it; the rewrite carries the
    //    predicate's attributes up the bottom-up reduction. Expect a real win from the reduction.
    bench("predicate: fan-out chain + cross filter",
      """select count(*) as cnt, sum(f.fm) as sm
         from ex_fact f join ex_r r on f.a = r.a
         join ex_s s on r.b = s.b join ex_t t on s.c = t.c
         where r.rv + t.tv > 120""", rewriteOn)

    // 2. LEFT OUTER with fan-out + grouped aggregate: matched half reduced, unmatched anti half a
    //    plain agg. Expect parity-to-modest (generality win; the anti half adds work).
    bench("left-outer: fan-out + grouped agg",
      """select g, count(*) as c, count(d.x) as cx, sum(d.x) as sx
         from ex_lo_fact f left join ex_lo_dim d on f.k = d.k group by g""", rewriteOn)

    // 3. FULL OUTER: the three-branch split (matched + A-only + B-only). Expect parity-to-modest.
    bench("full-outer: three-branch split",
      """select g, count(*) as c, count(d.x) as cx, sum(d.x) as sx
         from ex_lo_fact f full outer join ex_lo_dim d on f.k = d.k group by g""", rewriteOn)

    // 4. Cyclic triangle count(*): the bag IS vanilla's inner join, so this is PARITY by design -
    //    it confirms no regression and that the cyclic path is correct, not a speedup.
    bench("cyclic: triangle count(*) (parity expected)",
      """select count(*) as c from ex_tri_r r join ex_tri_s s on r.b = s.b
         join ex_tri_t t on r.a = t.a and s.c = t.c""", cyclicOn)
  }

  // ---- Scaling sweeps (K-run median + min/max) -----------------------------------------------
  // Each sweep grows the relevant join's intermediate (fan-out) while holding the output bounded,
  // and reports vanilla vs rewrite. Asserts rewrite == vanilla at every point.

  // (median, min, max) ms over k timed runs after one warm-up.
  private def timeK(conf: Seq[(String, String)], query: String, k: Int): (Long, Long, Long) = {
    withSQLConf(conf: _*) { sql(query).collect() }
    val ts = (1 to k).map(_ => withSQLConf(conf: _*) { timeMs(sql(query).collect())._2 }).sorted
    (ts(ts.size / 2), ts.head, ts.last)
  }

  private def scaleRow(
      label: String, query: String, onConf: Seq[(String, String)], k: Int): Unit = {
    val vRows = withSQLConf(vanilla: _*) { sql(query).collect().toSeq }
    val oRows = withSQLConf(onConf: _*) { sql(query).collect().toSeq }
    assert(rowsMatch(vRows, oRows), s"$label: rewrite result differs from vanilla")
    val v = timeK(vanilla, query, k)
    val o = timeK(onConf, query, k)
    val speedup = if (o._1 == 0) 0.0 else v._1.toDouble / o._1.toDouble
    // scalastyle:off println
    println(f"  $label%-22s vanilla=${v._1}%5d[${v._2}%d-${v._3}%d]ms" +
      f"  rewrite=${o._1}%5d[${o._2}%d-${o._3}%d]ms  speedup=$speedup%4.2fx")
    // scalastyle:on println
  }

  // Star fact-d1-d2 on key a (fan-out n/card per side); cross-dim predicate spans d1 and d2.
  private def genStar(n: Int, card: Int): Unit = {
    spark.range(0, n).selectExpr(s"id % $card as a", "cast(id % 1000 as double) as fm")
      .createOrReplaceTempView("sc_f")
    spark.range(0, n).selectExpr(s"id % $card as a", "id % 100 as x")
      .createOrReplaceTempView("sc_d1")
    spark.range(0, n).selectExpr(s"id % $card as a", "id % 100 as y")
      .createOrReplaceTempView("sc_d2")
  }

  // Fact LEFT/FULL dim on key k, fan-out on both sides; dim keys shifted to leave some unmatched.
  private def genOuterScale(n: Int, card: Int): Unit = {
    spark.range(0, n)
      .selectExpr(s"id % $card as k", "cast(id % 1000 as double) as fm", "id % 50 as g")
      .createOrReplaceTempView("so_f")
    spark.range(0, n).selectExpr(s"(id % ${card - 20}) + 10 as k", "cast(id % 100 as double) as x")
      .createOrReplaceTempView("so_d")
  }

  private def genTriangleScale(n: Int, card: Int): Unit = {
    spark.range(0, n).selectExpr(s"id % $card as a", s"(id * 3) % $card as b")
      .createOrReplaceTempView("st_r")
    spark.range(0, n).selectExpr(s"id % $card as b", s"(id * 5) % $card as c")
      .createOrReplaceTempView("st_s")
    spark.range(0, n).selectExpr(s"id % $card as a", s"(id * 5) % $card as c")
      .createOrReplaceTempView("st_t")
  }

  test("scaling: CORE fan-out reduction (no spanning predicate) - the real perf curve") {
    // The canonical count-join win: sum(f.fm) over a fan-out star F-d1-d2 with NO cross-dim
    // predicate. Vanilla materializes the F join d1 join d2 blow-up (~n^3/card^2 rows) then sums;
    // the rewrite computes sum(fm * cnt_d1 * cnt_d2) over the reduced relations, never building
    // it. Scales are large enough that the join work dominates fixed overhead, so the speedup grows
    // with fan-out. (Contrast the cross-relation-predicate sweep below: the predicate forces the
    // cross-product, so it is a GENERALITY win, parity in time.)
    // scalastyle:off println
    println("=== EXT-SCALE: CORE fan-out reduction (sum over a fan-out star, no predicate) ===")
    // scalastyle:on println
    Seq(10000, 25000, 45000, 70000).foreach { n =>
      genStar(n, 1000)
      scaleRow(s"n=$n fanout=${n / 1000}",
        "select sum(f.fm) as s from sc_f f join sc_d1 d1 on f.a = d1.a " +
          "join sc_d2 d2 on f.a = d2.a", rewriteOn, k = 2)
    }
  }

  test("scaling: cross-relation predicate (star) - generality, parity in time") {
    // The predicate d1.x + d2.y > c spans the two dims, so evaluating it requires the (x,y) pairs -
    // exactly the cross-product the count-join would otherwise avoid. So this is a GENERALITY win
    // (the rewrite handles the query) at parity time, NOT a reduction speedup. Numbers are noisy at
    // these scales; the point is no regression. Contrast the CORE sweep above (real win curve).
    // scalastyle:off println
    println("=== EXT-SCALE: cross-relation predicate (star) - generality, ~parity ===")
    // scalastyle:on println
    Seq(4000, 8000, 16000, 24000).foreach { n =>
      genStar(n, 1000)
      scaleRow(s"n=$n fanout=${n / 1000}",
        "select count(*) as c, sum(f.fm) as s from sc_f f join sc_d1 d1 on f.a = d1.a " +
          "join sc_d2 d2 on f.a = d2.a where d1.x + d2.y > 100", rewriteOn, k = 3)
    }
  }

  test("scaling: LEFT and FULL OUTER - matched-half reduction as fan-out grows") {
    // scalastyle:off println
    println("=== EXT-SCALE: outer joins (matched half routed through the count-join) ===")
    // scalastyle:on println
    Seq(4000, 8000, 16000, 24000).foreach { n =>
      genOuterScale(n, 1000)
      scaleRow(s"LEFT n=$n",
        "select g, count(*) as c, count(d.x) as cx, sum(d.x) as sx " +
          "from so_f f left join so_d d on f.k = d.k group by g", rewriteOn, k = 3)
      scaleRow(s"FULL n=$n",
        "select g, count(*) as c, count(d.x) as cx, sum(d.x) as sx " +
          "from so_f f full outer join so_d d on f.k = d.k group by g", rewriteOn, k = 3)
    }
  }

  test("scaling: cyclic triangle on a denser graph - parity (no regression)") {
    // scalastyle:off println
    println("=== EXT-SCALE: cyclic triangle count(*) (bag == vanilla join; parity) ===")
    // scalastyle:on println
    Seq(3000, 6000, 12000).foreach { n =>
      genTriangleScale(n, 300)
      scaleRow(s"triangle n=$n",
        "select count(*) as c from st_r r join st_s s on r.b = s.b " +
          "join st_t t on r.a = t.a and s.c = t.c", cyclicOn, k = 3)
    }
  }
}
