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

import scala.collection.mutable.ArrayBuffer
import scala.util.Random

import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types._

/**
 * Property-based / fuzz oracle for the Yannakakis count-join rewrite: generate many random
 * acyclic (star and chain) join+aggregate queries and assert the rewrite produces the SAME
 * results AND the SAME output schema as vanilla Spark. The schema check matters because
 * count-multiplication rewrites can change a decimal aggregate's precision/scale - a divergence
 * that value-only comparison misses (the historical SUM(decimal) bug class).
 *
 * Deterministic: each case is driven by a fixed seed, so a failure prints a reproducible query.
 * The iteration count is configurable via the FUZZ_ITERS env var (default 250).
 */
class YannakakisFuzzSuite extends QueryTest with SharedSparkSession {

  override protected def sparkConf: org.apache.spark.SparkConf =
    super.sparkConf.set(SQLConf.YANNAKAKIS_COST_GATE_ENABLED.key, "false")

  private val yannakakisOn = Seq(
    SQLConf.YANNAKAKIS_ENABLED.key -> "true",
    SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
    SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true")

  private def cellsMatch(a: Any, b: Any): Boolean = (a, b) match {
    case (null, null) => true
    case (null, _) | (_, null) => false
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

  // Compare field data types (incl. decimal precision/scale); ignore nullability differences,
  // which are benign here and would only add false positives.
  private def schemaMatch(s1: StructType, s2: StructType): Boolean =
    s1.length == s2.length && s1.fields.zip(s2.fields).forall { case (f1, f2) =>
      f1.dataType == f2.dataType
    }

  private def rkey(rng: Random, maxK: Int): Integer =
    if (rng.nextInt(10) == 0) null else Int.box(1 + rng.nextInt(maxK))
  private def rint(rng: Random): Integer =
    if (rng.nextInt(8) == 0) null else Int.box(rng.nextInt(120) - 20)
  private def rdbl(rng: Random): java.lang.Double =
    if (rng.nextInt(8) == 0) null else Double.box((rng.nextInt(12000) - 2000) / 100.0)
  private def rdec(rng: Random): java.math.BigDecimal =
    if (rng.nextInt(8) == 0) null
    else new java.math.BigDecimal(rng.nextInt(1000000) - 200000).movePointLeft(2).setScale(4)
  private def rstr(rng: Random): String =
    if (rng.nextInt(12) == 0) null else "G" + rng.nextInt(4)

  private val factSchema = StructType(Seq(
    StructField("k1", IntegerType), StructField("k2", IntegerType), StructField("k3", IntegerType),
    StructField("fm1", IntegerType), StructField("fm2", DoubleType),
    StructField("fm3", DecimalType(18, 4))))
  private val dimSchema = (i: Int) => StructType(Seq(
    StructField(s"d${i}k", IntegerType), StructField(s"d${i}g", StringType),
    StructField(s"d${i}v", IntegerType)))

  private def registerTables(rng: Random): Unit = {
    val maxK = 4
    val nFact = 5 + rng.nextInt(12)
    val factRows = (0 until nFact).map { _ =>
      Row(rkey(rng, maxK), rkey(rng, maxK), rkey(rng, maxK), rint(rng), rdbl(rng), rdec(rng))
    }
    spark.createDataFrame(spark.sparkContext.parallelize(factRows), factSchema)
      .createOrReplaceTempView("fz_fact")
    (1 to 3).foreach { i =>
      // Per key: 0 rows (no match -> tests reduction), 1 row (unique), or 2 rows (fan-out).
      val dimRows = (1 to maxK).flatMap { k =>
        (0 until rng.nextInt(3)).map(_ => Row(Int.box(k), rstr(rng), rint(rng)))
      }
      spark.createDataFrame(spark.sparkContext.parallelize(dimRows), dimSchema(i))
        .createOrReplaceTempView(s"fz_d$i")
    }
  }

  private def pick[T](rng: Random, xs: Seq[T]): T = xs(rng.nextInt(xs.size))

  private def genQuery(rng: Random): String = {
    val nDims = 1 + rng.nextInt(3)
    val usedDims = 1 to nDims
    val star = rng.nextBoolean()
    // ~40% of the time make the fact->d1 join a LEFT/RIGHT/FULL OUTER join (the rest inner). This
    // exercises the outer-join decomposition (matched inner + one anti half per null-extended side)
    // against vanilla. RIGHT is written so the kept side is still the fact (fz_d1 d1 right join
    // fz_fact f), which the rewrite normalises back to LEFT; FULL adds the symmetric B-only half.
    val outerKind = rng.nextInt(100) match {
      case n if n < 15 => "left"
      case n if n < 28 => "right"
      case n if n < 41 => "full"
      case _ => "inner"
    }
    // Star: fact joins each dim on f.k{i}=d{i}.d{i}k. Chain: fact-d1, d1-d2 (via shared key space),
    // d2-d3. The chain reuses the same integer key domain so joins are non-trivial.
    val joins = if (star) {
      val firstJoin = outerKind match {
        case "left" => "fz_fact f left join fz_d1 d1 on f.k1 = d1.d1k"
        case "right" => "fz_d1 d1 right join fz_fact f on f.k1 = d1.d1k"
        case "full" => "fz_fact f full outer join fz_d1 d1 on f.k1 = d1.d1k"
        case _ => "fz_fact f join fz_d1 d1 on f.k1 = d1.d1k"
      }
      firstJoin + usedDims.drop(1)
        .map(i => s" join fz_d$i d$i on f.k$i = d$i.d${i}k").mkString
    } else {
      val parts = new StringBuilder(outerKind match {
        case "left" => "fz_fact f left join fz_d1 d1 on f.k1 = d1.d1k"
        case "right" => "fz_d1 d1 right join fz_fact f on f.k1 = d1.d1k"
        case "full" => "fz_fact f full outer join fz_d1 d1 on f.k1 = d1.d1k"
        case _ => "fz_fact f join fz_d1 d1 on f.k1 = d1.d1k"
      })
      if (nDims >= 2) parts.append(" join fz_d2 d2 on d1.d1v = d2.d2k")
      if (nDims >= 3) parts.append(" join fz_d3 d3 on d2.d2v = d3.d3k")
      parts.toString
    }
    val isOuter = outerKind != "inner"
    val numCols = Seq("f.fm1", "f.fm2", "f.fm3") ++ usedDims.map(i => s"d$i.d${i}v")
    val allCols = numCols ++ usedDims.map(i => s"d$i.d${i}g") ++ Seq("f.k1")

    def agg(rng: Random): String = rng.nextInt(15) match {
      case 0 => "count(*)"
      case 1 => s"count(${pick(rng, allCols)})"
      case 2 => s"count(distinct ${pick(rng, allCols)})"
      case 3 => s"sum(${pick(rng, numCols)})"
      case 4 => s"sum(distinct ${pick(rng, numCols)})"
      case 5 => s"avg(${pick(rng, numCols)})"
      case 6 => s"min(${pick(rng, allCols)})"
      case 7 => s"max(${pick(rng, allCols)})"
      // 2nd central moments: fan-out-sensitive, count-weighted-power-sum reconstruction.
      case 8 => s"var_samp(${pick(rng, numCols)})"
      case 9 => s"var_pop(${pick(rng, numCols)})"
      case 10 => s"stddev_samp(${pick(rng, numCols)})"
      case 11 => s"stddev_pop(${pick(rng, numCols)})"
      // two-column 2nd moments.
      case 12 => s"covar_pop(${pick(rng, numCols)}, ${pick(rng, numCols)})"
      case 13 => s"covar_samp(${pick(rng, numCols)}, ${pick(rng, numCols)})"
      case _ => s"corr(${pick(rng, numCols)}, ${pick(rng, numCols)})"
    }
    val nAgg = 1 + rng.nextInt(3)
    val aggSelect = (0 until nAgg).map(j => s"${agg(rng)} as a$j")

    val groupPool = usedDims.map(i => s"d$i.d${i}g") ++ Seq("f.k1")
    val nGroup = rng.nextInt(3)
    val groupCols = rng.shuffle(groupPool.toList).take(nGroup)
    val groupSelect = groupCols.zipWithIndex.map { case (g, i) => s"$g as g$i" }
    val selectList = (groupSelect ++ aggSelect).mkString(", ")
    val groupClause = if (groupCols.nonEmpty) " group by " + groupCols.mkString(", ") else ""

    // ~40% of the time add a non-equi cross-relation filter (fact col vs a dim col), a historical
    // bug source for the count-join path (the filter must be applied at/above the join where both
    // attributes are available, and rows whose matches all fail it must drop the carried count).
    // Skipped for outer joins: a WHERE predicate on a dim column would convert the outer join back
    // to an inner one, so it would no longer exercise the outer-join decomposition.
    val crossFilter = if (!isOuter && rng.nextInt(10) < 4) {
      val op = pick(rng, Seq("<", ">", "<=", ">=", "<>"))
      val pred = if (nDims >= 2 && rng.nextBoolean()) {
        // Span two relations the reduction does not directly inner-join (sibling dims in a star),
        // optionally with a fact term too (a 3-relation predicate) - exercises the attribute-carry.
        val Seq(di, dj) = rng.shuffle((1 to nDims).toList).take(2)
        if (rng.nextBoolean()) s"d$di.d${di}v $op d$dj.d${dj}v"
        else s"f.fm1 + d$di.d${di}v $op d$dj.d${dj}v"
      } else {
        val di = 1 + rng.nextInt(nDims)
        s"f.${pick(rng, Seq("fm1", "fm2"))} $op d$di.d${di}v"
      }
      s" where $pred"
    } else ""
    s"select $selectList from $joins$crossFilter$groupClause"
  }

  test("fuzz: random acyclic join+aggregate queries match vanilla (values + schema)") {
    val iters = sys.env.getOrElse("FUZZ_ITERS", "250").toInt
    val failures = ArrayBuffer[String]()
    var checked = 0
    var skipped = 0
    (1 to iters).foreach { seed =>
      val rng = new Random(seed.toLong)
      registerTables(rng)
      val query = genQuery(rng)
      // Run vanilla FIRST. If the query itself throws under ANSI (e.g. a degenerate cross-relation
      // corr that Spark divide-by-zeros on), it is not a valid rewrite-correctness test - the
      // rewrite faithfully reproduces vanilla (often by falling back) including the throw - so skip
      // it. Only compare when vanilla succeeds; then an exception/diff is genuinely the rewrite's.
      val vanilla: Option[(StructType, Seq[Row])] =
        try withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
          val df = sql(query); Some((df.schema, df.collect().toSeq))
        } catch { case _: Throwable => None }
      vanilla match {
        case None => skipped += 1
        case Some((vSchema, vRows)) =>
          try withSQLConf(yannakakisOn: _*) {
            val df = sql(query)
            val rSchema = df.schema
            if (!schemaMatch(rSchema, vSchema)) {
              failures += s"seed=$seed SCHEMA ${rSchema.catalogString} != " +
                s"${vSchema.catalogString}\n$query"
            } else {
              val rRows = df.collect().toSeq
              if (!rowsMatch(rRows, vRows)) {
                failures += s"seed=$seed VALUES (${rRows.size} vs ${vRows.size} rows)\n$query"
              }
            }
            checked += 1
          } catch {
            case e: Throwable =>
              failures += s"seed=$seed REWRITE-ONLY EXCEPTION ${e.getClass.getSimpleName}: " +
                s"${Option(e.getMessage).getOrElse("")}\n$query"
          }
      }
    }
    info(s"fuzz: checked $checked/$iters queries ($skipped skipped: vanilla threw), " +
      s"${failures.size} failures")
    assert(failures.isEmpty,
      s"${failures.size}/$iters fuzz failures (showing up to 12):\n" +
        failures.take(12).mkString("\n----\n"))
  }

  // DIAGNOSTIC (ignore()d): reproduce specific acyclic seeds, running vanilla and rewrite apart
  // to tell whether an EXCEPTION is the fuzzer emitting an ANSI-invalid query (vanilla throws too)
  // or a rewrite bug (only rewrite throws). Flip to test() and -z to use.
  ignore("DIAGNOSTIC acyclic repro of specific seeds") {
    val seeds = sys.env.getOrElse("REPRO_SEEDS", "126,133,160,167,194,258").split(",").map(_.toInt)
    // scalastyle:off println
    seeds.foreach { seed =>
      val rng = new Random(seed.toLong)
      registerTables(rng)
      val q = genQuery(rng)
      def run(conf: Seq[(String, String)]): String =
        try { withSQLConf(conf: _*) { sql(q).collect() }; "ok" }
        catch { case e: Throwable => e.getClass.getSimpleName }
      val v = run(Seq(SQLConf.YANNAKAKIS_ENABLED.key -> "false"))
      val r = run(yannakakisOn)
      println(s"REPRO seed=$seed vanilla=$v rewrite=$r\n  $q")
    }
    // scalastyle:on println
  }

  private val cyclicBagsOn =
    yannakakisOn :+ (SQLConf.YANNAKAKIS_CYCLIC_BAGS_ENABLED.key -> "true")

  private def rmeasure(rng: Random): Integer =
    if (rng.nextInt(8) == 0) null else Int.box(rng.nextInt(60) - 10)

  // Runs `query` with `onConf` and asserts both rows and schema match vanilla; returns a failure
  // description or None. (Mirrors the acyclic test's comparison, parameterized by the conf so the
  // cyclic test can run it with cyclic-bags enabled.)
  private def compareAgainstVanilla(
      query: String, onConf: Seq[(String, String)], seed: Int): Option[String] = {
    try {
      var vSchema: StructType = null
      var vRows: Seq[Row] = null
      // AQE off: this is a LOGICAL-correctness oracle, and AQE's runtime re-optimization adds
      // session-dependent non-determinism (more so for the complex bag plans) that is orthogonal
      // to whether the rewrite is correct.
      withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false",
          SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "false") {
        val df = sql(query); vSchema = df.schema; vRows = df.collect().toSeq
      }
      withSQLConf((onConf :+ (SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "false")): _*) {
        val df = sql(query)
        if (!schemaMatch(df.schema, vSchema)) {
          Some(s"seed=$seed SCHEMA ${df.schema.catalogString} != " +
            s"${vSchema.catalogString}\n$query")
        } else if (!rowsMatch(df.collect().toSeq, vRows)) {
          Some(s"seed=$seed VALUES mismatch\n$query")
        } else {
          None
        }
      }
    } catch {
      case e: Throwable =>
        Some(s"seed=$seed EXCEPTION ${e.getClass.getSimpleName}: " +
          s"${Option(e.getMessage).getOrElse("")}\n$query")
    }
  }

  // Registers a cyclic query's relations (a triangle or a 4-cycle, ~half the time with a fringe
  // relation joined to one cycle vertex so the bag becomes a LEAF in a larger tree and the
  // count-over-bags machinery is actually exercised, rather than the tautological bare-bag case)
  // with random fan-out data, and returns the query. Vertices live in a small domain so the cycle
  // has many solutions (non-trivial counts).
  private def genCyclicQuery(rng: Random, sfx: Int): String = {
    val maxV = 3
    // Unique per-seed view names so seeds never share tables in the shared session (the topology
    // registers a variable set of relations, so fixed names could leave a prior seed's stale view).
    def regRel(view: String, vcols: Seq[String], mcol: String): Unit = {
      val schema = StructType(vcols.map(c => StructField(c, IntegerType)) :+
        StructField(mcol, IntegerType))
      val n = 5 + rng.nextInt(10)
      val rows = (0 until n).map(_ =>
        Row((vcols.map(_ => rkey(rng, maxV)) :+ rmeasure(rng)): _*))
      spark.createDataFrame(spark.sparkContext.parallelize(rows), schema)
        .createOrReplaceTempView(view)
    }
    val (nr, ns, nt, nu, nf) =
      (s"cyc_r_$sfx", s"cyc_s_$sfx", s"cyc_t_$sfx", s"cyc_u_$sfx", s"cyc_f_$sfx")
    val fourCycle = rng.nextBoolean()
    val (cycleJoin, vertexCols, measures) = if (!fourCycle) {
      regRel(nr, Seq("r_a", "r_b"), "r_m")
      regRel(ns, Seq("s_b", "s_c"), "s_m")
      regRel(nt, Seq("t_a", "t_c"), "t_m")
      (s"$nr r join $ns s on r.r_b = s.s_b " +
         s"join $nt t on s.s_c = t.t_c and t.t_a = r.r_a",
       Seq("r.r_a", "r.r_b", "s.s_c"), Seq("r.r_m", "s.s_m", "t.t_m"))
    } else {
      regRel(nr, Seq("r_a", "r_b"), "r_m")
      regRel(ns, Seq("s_b", "s_c"), "s_m")
      regRel(nt, Seq("t_c", "t_d"), "t_m")
      regRel(nu, Seq("u_d", "u_a"), "u_m")
      (s"$nr r join $ns s on r.r_b = s.s_b join $nt t on s.s_c = t.t_c " +
         s"join $nu u on t.t_d = u.u_d and u.u_a = r.r_a",
       Seq("r.r_a", "r.r_b", "s.s_c", "t.t_d"), Seq("r.r_m", "s.s_m", "t.t_m", "u.u_m"))
    }
    val (joins, allVertex, allMeasures) = if (rng.nextBoolean()) {
      regRel(nf, Seq("f_a"), "f_m")
      (cycleJoin + s" join $nf f on f.f_a = r.r_a",
        vertexCols :+ "f.f_a", measures :+ "f.f_m")
    } else {
      (cycleJoin, vertexCols, measures)
    }
    val allCols = allVertex ++ allMeasures
    def agg(): String = rng.nextInt(7) match {
      case 0 => "count(*)"
      case 1 => s"count(${pick(rng, allCols)})"
      case 2 => s"count(distinct ${pick(rng, allCols)})"
      case 3 => s"sum(${pick(rng, allMeasures)})"
      case 4 => s"avg(${pick(rng, allMeasures)})"
      case 5 => s"min(${pick(rng, allCols)})"
      case _ => s"max(${pick(rng, allCols)})"
    }
    val aggSelect = (0 until (1 + rng.nextInt(2))).map(j => s"${agg()} as a$j")
    val groupCols = rng.shuffle(allVertex.toList).take(rng.nextInt(2))
    val groupSelect = groupCols.zipWithIndex.map { case (g, i) => s"$g as g$i" }
    val filter = if (allMeasures.size >= 2 && rng.nextInt(10) < 4) {
      val Seq(m1, m2) = rng.shuffle(allMeasures.toList).take(2)
      s" where $m1 ${pick(rng, Seq("<", ">", "<=", ">=", "<>"))} $m2"
    } else ""
    val selectList = (groupSelect ++ aggSelect).mkString(", ")
    val groupClause = if (groupCols.nonEmpty) " group by " + groupCols.mkString(", ") else ""
    s"select $selectList from $joins$filter$groupClause"
  }

  test("fuzz: random cyclic (triangle/4-cycle) queries match vanilla (cyclic bags on)") {
    val iters = sys.env.getOrElse("CYCLIC_FUZZ_ITERS", "300").toInt
    val failures = ArrayBuffer[String]()
    (1 to iters).foreach { seed =>
      val rng = new Random((seed + 100000).toLong)
      val query = genCyclicQuery(rng, seed)
      compareAgainstVanilla(query, cyclicBagsOn, seed).foreach(failures += _)
    }
    info(s"cyclic fuzz: checked $iters queries, ${failures.size} failures")
    assert(failures.isEmpty,
      s"${failures.size}/$iters cyclic fuzz failures (showing up to 12):\n" +
        failures.take(12).mkString("\n----\n"))
  }

  // DIAGNOSTIC (not an oracle assertion): characterize the cyclic rewrite under AQE-ON, which the
  // oracle above deliberately avoids. For each seed, against the SAME data, compute vanilla AQE-off
  // (ground truth), vanilla AQE-on, and rewrite AQE-on twice. Classifies any divergence as
  // (a) vanilla-itself-diverges-under-AQE, (b) rewrite+AQE wrong vs truth, (c) rewrite+AQE
  // non-deterministic. Run: CYCLIC_AQE_ITERS=300 (env) with -z DIAGNOSTIC.
  // ignore()d: a manual diagnostic (no oracle assertion, slow, and the AQE+batch behaviour probed
  // is non-deterministic). Flip to test() and run with -z to re-investigate. Findings: the cyclic
  // rewrite is correct for single isolated queries under AQE; in a long AQE-on shared-session batch
  // it shows FLAKY divergence (varies run-to-run, vanilla's own truth varies too), not reducible to
  // a deterministic isolated case. Suspect area if a stable repro appears: the mixed-distinct split
  // (cjsplit_gk) interacting with AQE ReusedExchange across its two halves.
  ignore("DIAGNOSTIC cyclic AQE: characterize rewrite vs vanilla under AQE-on") {
    val iters = sys.env.getOrElse("CYCLIC_AQE_ITERS", "200").toInt
    val aqeOn = SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true"
    val aqeOff = SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "false"
    var vanillaAqeDiverged = 0
    var rewriteAqeWrong = 0
    var rewriteAqeNonDet = 0
    val examples = ArrayBuffer[String]()
    (1 to iters).foreach { seed =>
      val rng = new Random((seed + 100000).toLong)
      val query = genCyclicQuery(rng, seed)
      val truth = withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false", aqeOff) {
        sql(query).collect().toSeq
      }
      val vanAqe = withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false", aqeOn) {
        sql(query).collect().toSeq
      }
      val (rw1, rw1Plan) = withSQLConf((cyclicBagsOn :+ aqeOn): _*) {
        val df = sql(query); val rows = df.collect().toSeq
        (rows, df.queryExecution.executedPlan.toString)
      }
      val rw2 = withSQLConf((cyclicBagsOn :+ aqeOn): _*) { sql(query).collect().toSeq }
      if (!rowsMatch(vanAqe, truth)) vanillaAqeDiverged += 1
      if (!rowsMatch(rw1, truth)) {
        rewriteAqeWrong += 1
        // Decisive: on the SAME views (same data, same session state), is rewrite AQE-OFF correct?
        // If rwOff==truth and rw1(AQE-on)!=truth, AQE is unambiguously the differentiator.
        val rwOff = withSQLConf((cyclicBagsOn :+ aqeOff): _*) { sql(query).collect().toSeq }
        val hasReuse = rw1Plan.contains("ReusedExchange")
        if (examples.size < 2) {
          examples += s"seed=$seed WRONG vs truth\n$query" +
            s"\n  truth         =${truth.sortBy(_.toString).take(8)}" +
            s"\n  rw AQE-on     =${rw1.sortBy(_.toString).take(8)}" +
            s"\n  rw AQE-off    =${rwOff.sortBy(_.toString).take(8)}" +
            s"  matchesTruth=${rowsMatch(rwOff, truth)}" +
            s"\n  AQE-on plan has ReusedExchange=$hasReuse"
        }
      }
      if (!rowsMatch(rw1, rw2)) rewriteAqeNonDet += 1
    }
    // scalastyle:off println
    println(s"CYCLIC-AQE DIAGNOSTIC over $iters seeds: vanillaAqeDiverged=$vanillaAqeDiverged " +
      s"rewriteAqeWrong=$rewriteAqeWrong rewriteAqeNonDet=$rewriteAqeNonDet")
    examples.foreach(println)
    // scalastyle:on println
  }

  // DIAGNOSTIC: run ONE seed in isolation (via -z) so the shared session holds no prior-seed state.
  // Prints the query and four results, isolating "real AQE bug" from "multi-seed contamination".
  ignore("DIAGNOSTIC cyclic AQE: seed 31 in isolation") {
    val seed = sys.env.getOrElse("CYCLIC_AQE_SEED", "31").toInt
    val aqeOn = SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true"
    val aqeOff = SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "false"
    val query = genCyclicQuery(new Random((seed + 100000).toLong), seed)
    def run(conf: Seq[(String, String)]): Seq[Row] =
      withSQLConf(conf: _*) { sql(query).collect().toSeq.sortBy(_.toString) }
    val vanOff = run(Seq(SQLConf.YANNAKAKIS_ENABLED.key -> "false", aqeOff))
    val vanOn = run(Seq(SQLConf.YANNAKAKIS_ENABLED.key -> "false", aqeOn))
    val rwOff = run(cyclicBagsOn :+ aqeOff)
    val rwOn = run(cyclicBagsOn :+ aqeOn)
    // scalastyle:off println
    println(s"SEED-$seed ISOLATED\n$query")
    println(s"  vanilla AQE-off (truth): $vanOff")
    println(s"  vanilla AQE-on         : $vanOn   match=${rowsMatch(vanOn, vanOff)}")
    println(s"  rewrite AQE-off        : $rwOff   match=${rowsMatch(rwOff, vanOff)}")
    println(s"  rewrite AQE-on         : $rwOn   match=${rowsMatch(rwOn, vanOff)}")
    // scalastyle:on println
  }
}
