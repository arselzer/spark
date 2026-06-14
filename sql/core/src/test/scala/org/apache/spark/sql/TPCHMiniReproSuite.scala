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

import java.sql.Date

import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession

/**
 * Investigation harness: runs TPC-H queries on a deterministic mini dataset,
 * comparing baseline (Yannakakis off) against the rewrite (Yannakakis on).
 * Prints one verdict line per query: TPCH-REPRO: <q> -> OK | MISMATCH | EXCEPTION.
 * The data is designed so every query returns non-empty results and joins fan out.
 */
class TPCHMiniReproSuite extends QueryTest with SharedSparkSession {

  override protected def sparkConf: org.apache.spark.SparkConf =
    super.sparkConf.set(SQLConf.YANNAKAKIS_COST_GATE_ENABLED.key, "false")

  import testImplicits._

  private def d(s: String): Date = Date.valueOf(s)

  private def createTables(): Unit = {
    Seq(
      (1, "AMERICA"),
      (2, "EUROPE")
    ).toDF("r_regionkey", "r_name").createOrReplaceTempView("region")

    Seq(
      (1, "BRAZIL", 1),
      (2, "ARGENTINA", 1),
      (3, "FRANCE", 2),
      (4, "GERMANY", 2)
    ).toDF("n_nationkey", "n_name", "n_regionkey").createOrReplaceTempView("nation")

    Seq(
      (1, "S1", 1, "fine supplier"),
      (2, "S2", 3, "good supplier"),
      (3, "S3", 4, "reliable supplier"),
      (4, "S4", 2, "bad Customer xx Complaints yy")
    ).toDF("s_suppkey", "s_name", "s_nationkey", "s_comment")
      .createOrReplaceTempView("supplier")

    Seq(
      (1, "forest green metal", "M1", "Brand#12", "ECONOMY ANODIZED STEEL", 14, "BOX", 100.0),
      (2, "red shiny thing", "M1", "Brand#13", "PROMO BURNISHED COPPER", 23, "BOX", 200.0),
      (3, "olive green widget", "M2", "Brand#14", "STANDARD PLATED TIN", 9, "JAR", 150.0),
      (4, "blue gadget", "M2", "Brand#45", "MEDIUM POLISHED STEEL", 49, "CAN", 120.0)
    ).toDF("p_partkey", "p_name", "p_mfgr", "p_brand", "p_type", "p_size",
      "p_container", "p_retailprice").createOrReplaceTempView("part")

    Seq(
      (1, 1, 100, 10.0),
      (1, 2, 100, 12.0),
      (2, 1, 100, 20.0),
      (2, 2, 100, 22.0),
      (3, 2, 100, 5.5),
      (3, 3, 100, 5.0),
      (3, 4, 100, 6.0),
      (4, 1, 100, 7.0)
    ).toDF("ps_partkey", "ps_suppkey", "ps_availqty", "ps_supplycost")
      .createOrReplaceTempView("partsupp")

    Seq(
      (1, "C1", "addr1", 1, "11-111", 100.0, "BUILDING", "c comment 1"),
      (2, "C2", "addr2", 4, "22-222", 200.0, "BUILDING", "c comment 2"),
      (3, "C3", "addr3", 3, "33-333", 300.0, "AUTOMOBILE", "c comment 3"),
      (4, "C4", "addr4", 2, "44-444", 400.0, "BUILDING", "c comment 4")
    ).toDF("c_custkey", "c_name", "c_address", "c_nationkey", "c_phone", "c_acctbal",
      "c_mktsegment", "c_comment").createOrReplaceTempView("customer")

    Seq(
      (1, 1, "O", 1000.0, d("1995-02-01"), "1-URGENT", 0),
      (2, 2, "O", 2000.0, d("1995-06-01"), "3-MEDIUM", 0),
      (3, 3, "F", 3000.0, d("1995-07-01"), "2-HIGH", 0),
      (4, 1, "F", 4000.0, d("1993-11-15"), "1-URGENT", 0),
      (5, 4, "O", 5000.0, d("1996-03-01"), "3-MEDIUM", 0),
      (6, 2, "O", 600.0, d("1994-05-01"), "5-LOW", 0)
    ).toDF("o_orderkey", "o_custkey", "o_orderstatus", "o_totalprice", "o_orderdate",
      "o_orderpriority", "o_shippriority").createOrReplaceTempView("orders")

    Seq(
      (1, 1, 1, 1, 10.0, 1000.0, 0.05, 0.0, "N",
        d("1995-04-01"), d("1995-04-10"), d("1995-04-20"), "TRUCK"),
      (1, 1, 2, 2, 5.0, 500.0, 0.10, 0.0, "N",
        d("1995-05-01"), d("1995-05-10"), d("1995-05-20"), "AIR"),
      (1, 2, 1, 3, 7.0, 700.0, 0.00, 0.0, "N",
        d("1995-04-15"), d("1995-04-20"), d("1995-04-30"), "MAIL"),
      (2, 1, 2, 1, 4.0, 400.0, 0.00, 0.0, "N",
        d("1995-08-01"), d("1995-08-10"), d("1995-08-20"), "SHIP"),
      (2, 3, 2, 2, 6.0, 600.0, 0.10, 0.0, "N",
        d("1996-01-15"), d("1996-01-20"), d("1996-01-30"), "RAIL"),
      (3, 3, 3, 1, 8.0, 800.0, 0.05, 0.0, "N",
        d("1995-09-10"), d("1995-09-15"), d("1995-09-25"), "FOB"),
      (3, 2, 2, 2, 3.0, 300.0, 0.00, 0.0, "N",
        d("1995-09-05"), d("1995-09-15"), d("1995-09-20"), "TRUCK"),
      (4, 4, 1, 1, 12.0, 1200.0, 0.10, 0.0, "R",
        d("1993-12-01"), d("1994-01-15"), d("1994-02-01"), "MAIL"),
      (4, 1, 1, 2, 2.0, 200.0, 0.00, 0.0, "R",
        d("1993-12-15"), d("1994-01-10"), d("1994-01-25"), "SHIP"),
      (5, 1, 1, 1, 200.0, 20000.0, 0.00, 0.0, "N",
        d("1996-04-01"), d("1996-04-10"), d("1996-04-20"), "TRUCK"),
      (5, 1, 2, 2, 150.0, 15000.0, 0.05, 0.0, "N",
        d("1996-05-01"), d("1996-05-10"), d("1996-05-20"), "AIR"),
      (6, 2, 2, 1, 10.0, 1000.0, 0.06, 0.0, "N",
        d("1994-03-01"), d("1994-03-10"), d("1994-03-20"), "SHIP"),
      (6, 3, 4, 2, 20.0, 2000.0, 0.07, 0.0, "N",
        d("1994-06-01"), d("1994-06-10"), d("1994-06-08"), "RAIL")
    ).toDF("l_orderkey", "l_partkey", "l_suppkey", "l_linenumber", "l_quantity",
      "l_extendedprice", "l_discount", "l_tax", "l_returnflag",
      "l_shipdate", "l_commitdate", "l_receiptdate", "l_shipmode")
      .createOrReplaceTempView("lineitem")
  }

  private val queries: Seq[(String, String)] = Seq(
    "Q3" -> """
      select l_orderkey, sum(l_extendedprice * (1 - l_discount)) as revenue,
             o_orderdate, o_shippriority
      from customer, orders, lineitem
      where c_mktsegment = 'BUILDING' and c_custkey = o_custkey
        and l_orderkey = o_orderkey
        and o_orderdate < date '1995-03-15' and l_shipdate > date '1995-03-15'
      group by l_orderkey, o_orderdate, o_shippriority
      order by revenue desc, o_orderdate limit 10""",
    "Q6" -> """
      select sum(l_extendedprice * l_discount) as revenue
      from lineitem
      where l_shipdate >= date '1994-01-01'
        and l_shipdate < date '1994-01-01' + interval '1' year
        and l_discount between .06 - 0.01 and .06 + 0.01 and l_quantity < 24""",
    "Q7" -> """
      select supp_nation, cust_nation, l_year, sum(volume) as revenue
      from (
        select n1.n_name as supp_nation, n2.n_name as cust_nation,
               extract(year from l_shipdate) as l_year,
               l_extendedprice * (1 - l_discount) as volume
        from supplier, lineitem, orders, customer, nation n1, nation n2
        where s_suppkey = l_suppkey and o_orderkey = l_orderkey
          and c_custkey = o_custkey and s_nationkey = n1.n_nationkey
          and c_nationkey = n2.n_nationkey
          and ((n1.n_name = 'FRANCE' and n2.n_name = 'GERMANY')
            or (n1.n_name = 'GERMANY' and n2.n_name = 'FRANCE'))
          and l_shipdate between date '1995-01-01' and date '1996-12-31'
      ) as shipping
      group by supp_nation, cust_nation, l_year
      order by supp_nation, cust_nation, l_year""",
    "Q8" -> """
      select o_year,
             sum(case when nation = 'BRAZIL' then volume else 0 end) / sum(volume) as mkt_share
      from (
        select extract(year from o_orderdate) as o_year,
               l_extendedprice * (1 - l_discount) as volume, n2.n_name as nation
        from part, supplier, lineitem, orders, customer, nation n1, nation n2, region
        where p_partkey = l_partkey and s_suppkey = l_suppkey
          and l_orderkey = o_orderkey and o_custkey = c_custkey
          and c_nationkey = n1.n_nationkey and n1.n_regionkey = r_regionkey
          and r_name = 'AMERICA' and s_nationkey = n2.n_nationkey
          and o_orderdate between date '1995-01-01' and date '1996-12-31'
          and p_type = 'ECONOMY ANODIZED STEEL'
      ) as all_nations
      group by o_year order by o_year""",
    "Q9" -> """
      select nation, o_year, sum(amount) as sum_profit
      from (
        select n_name as nation, extract(year from o_orderdate) as o_year,
               l_extendedprice * (1 - l_discount) - ps_supplycost * l_quantity as amount
        from part, supplier, lineitem, partsupp, orders, nation
        where s_suppkey = l_suppkey and ps_suppkey = l_suppkey
          and ps_partkey = l_partkey and p_partkey = l_partkey
          and o_orderkey = l_orderkey and s_nationkey = n_nationkey
          and p_name like '%green%'
      ) as profit
      group by nation, o_year order by nation, o_year desc""",
    "Q10" -> """
      select c_custkey, c_name, sum(l_extendedprice * (1 - l_discount)) as revenue,
             c_acctbal, n_name, c_address, c_phone, c_comment
      from customer, orders, lineitem, nation
      where c_custkey = o_custkey and l_orderkey = o_orderkey
        and o_orderdate >= date '1993-10-01'
        and o_orderdate < date '1993-10-01' + interval '3' month
        and l_returnflag = 'R' and c_nationkey = n_nationkey
      group by c_custkey, c_name, c_acctbal, c_phone, n_name, c_address, c_comment
      order by revenue desc limit 20""",
    "Q12" -> """
      select l_shipmode,
             sum(case when o_orderpriority = '1-URGENT' or o_orderpriority = '2-HIGH'
                 then 1 else 0 end) as high_line_count,
             sum(case when o_orderpriority <> '1-URGENT' and o_orderpriority <> '2-HIGH'
                 then 1 else 0 end) as low_line_count
      from orders, lineitem
      where o_orderkey = l_orderkey and l_shipmode in ('MAIL', 'SHIP')
        and l_commitdate < l_receiptdate and l_shipdate < l_commitdate
        and l_receiptdate >= date '1994-01-01'
        and l_receiptdate < date '1994-01-01' + interval '1' year
      group by l_shipmode order by l_shipmode""",
    "Q14" -> """
      select 100.00 * sum(case when p_type like 'PROMO%'
                          then l_extendedprice * (1 - l_discount) else 0 end)
             / sum(l_extendedprice * (1 - l_discount)) as promo_revenue
      from lineitem, part
      where l_partkey = p_partkey
        and l_shipdate >= date '1995-09-01'
        and l_shipdate < date '1995-09-01' + interval '1' month""",
    "Q16" -> """
      select p_brand, p_type, p_size, count(distinct ps_suppkey) as supplier_cnt
      from partsupp, part
      where p_partkey = ps_partkey and p_brand <> 'Brand#45'
        and p_type not like 'MEDIUM POLISHED%'
        and p_size in (49, 14, 23, 45, 19, 3, 36, 9)
        and ps_suppkey not in (
          select s_suppkey from supplier where s_comment like '%Customer%Complaints%')
      group by p_brand, p_type, p_size
      order by supplier_cnt desc, p_brand, p_type, p_size""",
    "Q18" -> """
      select c_name, c_custkey, o_orderkey, o_orderdate, o_totalprice, sum(l_quantity)
      from customer, orders, lineitem
      where o_orderkey in (
          select l_orderkey from lineitem group by l_orderkey having sum(l_quantity) > 300)
        and c_custkey = o_custkey and o_orderkey = l_orderkey
      group by c_name, c_custkey, o_orderkey, o_orderdate, o_totalprice
      order by o_totalprice desc, o_orderdate limit 100"""
  )

  private def cellsMatch(a: Any, b: Any): Boolean = (a, b) match {
    case (null, null) => true
    case (x: Double, y: Double) =>
      (x.isNaN && y.isNaN) || math.abs(x - y) <= 1e-6 * math.max(1.0, math.abs(x))
    case (x: java.math.BigDecimal, y: java.math.BigDecimal) =>
      x.subtract(y).abs().doubleValue() <= 1e-6
    case (x, y) => x == y
  }

  private def rowsMatch(a: Seq[Row], b: Seq[Row]): Boolean = {
    if (a.size != b.size) return false
    val sa = a.sortBy(_.toString)
    val sb = b.sortBy(_.toString)
    sa.zip(sb).forall { case (ra, rb) =>
      ra.size == rb.size && (0 until ra.size).forall(i => cellsMatch(ra.get(i), rb.get(i)))
    }
  }

  test("TPC-H mini repro: rewrite vs baseline") {
    createTables()
    val report = new StringBuilder
    // Rewrite-side regressions only: MISMATCH (wrong results) and EXCEPTION (rewrite threw).
    // BASELINE-FAIL means vanilla Spark itself failed and is NOT a rewrite fault.
    val failures = scala.collection.mutable.ListBuffer[String]()
    for ((name, query) <- queries) {
      var baseline: Either[Throwable, Seq[Row]] = null
      try {
        withSQLConf(SQLConf.YANNAKAKIS_ENABLED.key -> "false") {
          baseline = Right(sql(query).collect().toSeq)
        }
      } catch { case t: Throwable => baseline = Left(t) }

      var rewritten: Either[Throwable, (Seq[Row], Boolean)] = null
      try {
        withSQLConf(
          SQLConf.YANNAKAKIS_ENABLED.key -> "true",
          SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
          SQLConf.YANNAKAKIS_PHYSICAL_COUNTJOIN_ENABLED.key -> "true") {
          val df = sql(query)
          val rows = df.collect().toSeq
          val applied = df.queryExecution.executedPlan.toString.contains("CountJoin")
          rewritten = Right((rows, applied))
        }
      } catch { case t: Throwable => rewritten = Left(t) }

      val verdict = (baseline, rewritten) match {
        case (Left(t), _) =>
          s"BASELINE-FAIL ${t.getClass.getSimpleName}: ${t.getMessage.take(200)}"
        case (Right(_), Left(t)) =>
          val root = Option(t.getCause).getOrElse(t)
          s"EXCEPTION ${root.getClass.getSimpleName}: " +
            Option(root.getMessage).getOrElse(t.getMessage).take(300)
        case (Right(b), Right((r, applied))) =>
          val tag = if (applied) "rewrite-applied" else "rewrite-NOT-applied"
          if (rowsMatch(b, r)) {
            s"OK ($tag, ${b.size} rows)"
          } else {
            s"MISMATCH ($tag)\n  baseline : ${b.sortBy(_.toString).mkString(" | ")}" +
              s"\n  rewritten: ${r.sortBy(_.toString).mkString(" | ")}"
          }
      }
      val line = s"TPCH-REPRO: $name -> $verdict"
      // scalastyle:off println
      println(line)
      // scalastyle:on println
      report.append(line).append('\n')
      if (verdict.startsWith("MISMATCH") || verdict.startsWith("EXCEPTION")) {
        failures.append(line)
      }
    }
    // scalastyle:off println
    println("TPCH-REPRO-SUMMARY:\n" + report)
    // scalastyle:on println
    assert(failures.isEmpty,
      s"TPC-H rewrite regressed on ${failures.size} query/queries " +
        s"(MISMATCH = wrong results, EXCEPTION = rewrite threw; BASELINE-FAIL is excluded):\n" +
        failures.mkString("\n") + "\n\nfull report:\n" + report)
  }
}
