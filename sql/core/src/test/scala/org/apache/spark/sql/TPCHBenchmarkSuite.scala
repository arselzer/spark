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

  // Grouping-heavy TPC-H queries that route through the count-join rewrite. Q7/Q9 group by keys
  // spanning multiple relations, so grouping is pushed INTO the count-joins (grouped codegen).
  private val queries: Seq[(String, String)] = Seq(
    "Q3" ->
      """select l_orderkey, sum(l_extendedprice * (1 - l_discount)) as revenue,
               o_orderdate, o_shippriority
        from customer, orders, lineitem
        where c_mktsegment = 'BUILDING' and c_custkey = o_custkey and l_orderkey = o_orderkey
          and o_orderdate < date '1995-03-15' and l_shipdate > date '1995-03-15'
        group by l_orderkey, o_orderdate, o_shippriority""",
    "Q7" ->
      """select supp_nation, cust_nation, l_year, sum(volume) as revenue
        from (
          select n1.n_name as supp_nation, n2.n_name as cust_nation,
                 extract(year from l_shipdate) as l_year,
                 l_extendedprice * (1 - l_discount) as volume
          from supplier, lineitem, orders, customer, nation n1, nation n2
          where s_suppkey = l_suppkey and o_orderkey = l_orderkey and c_custkey = o_custkey
            and s_nationkey = n1.n_nationkey and c_nationkey = n2.n_nationkey
            and ((n1.n_name = 'FRANCE' and n2.n_name = 'GERMANY')
              or (n1.n_name = 'GERMANY' and n2.n_name = 'FRANCE'))
            and l_shipdate between date '1995-01-01' and date '1996-12-31'
        ) as shipping
        group by supp_nation, cust_nation, l_year""",
    "Q9" ->
      """select nation, o_year, sum(amount) as sum_profit
        from (
          select n_name as nation, extract(year from o_orderdate) as o_year,
                 l_extendedprice * (1 - l_discount) - ps_supplycost * l_quantity as amount
          from part, supplier, lineitem, partsupp, orders, nation
          where s_suppkey = l_suppkey and ps_suppkey = l_suppkey and ps_partkey = l_partkey
            and p_partkey = l_partkey and o_orderkey = l_orderkey and s_nationkey = n_nationkey
            and p_name like '%green%'
        ) as profit
        group by nation, o_year""",
    "Q10" ->
      """select c_custkey, c_name, sum(l_extendedprice * (1 - l_discount)) as revenue,
               c_acctbal, n_name, c_address, c_phone, c_comment
        from customer, orders, lineitem, nation
        where c_custkey = o_custkey and l_orderkey = o_orderkey
          and o_orderdate >= date '1993-10-01'
          and o_orderdate < date '1993-10-01' + interval '3' month
          and l_returnflag = 'R' and c_nationkey = n_nationkey
        group by c_custkey, c_name, c_acctbal, c_phone, n_name, c_address, c_comment""")

  test("TPC-H benchmark: count-join codegen on vs interpreted vs off (sf1)") {
    assume(new File(tpchDir).isDirectory, s"TPC-H parquet dataset not present at $tpchDir")
    loadTpch()
    val iters = 3
    val aqeOff = Seq(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "false")
    val offConf = aqeOff :+ (SQLConf.YANNAKAKIS_ENABLED.key -> "false")
    val interpConf = aqeOff ++ yannakakisOn :+
      (SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "false")
    val codegenConf = aqeOff ++ yannakakisOn :+
      (SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "true")
    // scalastyle:off println
    println("TPCH-BENCH: query | count-joins(grouping) | off | on(interp) | on(codegen) | match")
    for ((name, q) <- queries) {
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
      assert(matched, s"$name codegen result must match vanilla")
    }
    // scalastyle:on println
  }
}
