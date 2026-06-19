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

import org.apache.spark.sql.catalyst.plans.logical.{CountJoin, LogicalPlan}
import org.apache.spark.sql.catalyst.util.resourceToString
import org.apache.spark.sql.internal.SQLConf

/** Planning-only CountJoin block dump for performance investigation. */
class TPCDSCountJoinPlanDumpSuite extends QueryTest with TPCDSBase {

  override def injectStats: Boolean = true

  override protected def sparkConf: org.apache.spark.SparkConf =
    super.sparkConf.set(SQLConf.READ_SIDE_CHAR_PADDING, false)

  private def propOrEnv(prop: String, env: String, defaultValue: String): String =
    Option(System.getProperty(prop)).orElse(sys.env.get(env)).getOrElse(defaultValue)

  private val queryNames = propOrEnv(
    "tpcds.dump.queries", "TPCDS_DUMP_QUERIES", "q13,q32,q48,q92")
    .split(",").map(_.trim).filter(_.nonEmpty).toSeq

  private def stats(plan: LogicalPlan): String = {
    val rc = plan.stats.rowCount.map(_.toString).getOrElse("-")
    s"${plan.nodeName}{size=${plan.stats.sizeInBytes},rows=$rc,out=${plan.output.size}}"
  }

  private def dump(queryName: String, costGate: Boolean): Unit = {
    val sqlText = resourceToString(s"tpcds/$queryName.sql",
      classLoader = Thread.currentThread().getContextClassLoader)
    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_COST_GATE_ENABLED.key -> costGate.toString) {
      val plan = sql(sqlText).queryExecution.optimizedPlan
      val countJoins = plan.collect { case cj: CountJoin => cj }
      val stringCountJoins = "CountJoin".r.findAllIn(plan.toString).size
      // scalastyle:off println
      println(s"TPCDS-DUMP: $queryName | costGate=$costGate | " +
        s"countJoins=${countJoins.size} | stringCountJoins=$stringCountJoins")
      countJoins.zipWithIndex.foreach { case (cj, i) =>
        val aggs = cj.aggregatesRight.map(_.aggregateFunction.sql).mkString("[", ",", "]")
        val groups = cj.groupRight.map(_.sql).mkString("[", ",", "]")
        val cond = cj.condition.map(_.sql).getOrElse("-")
        println(s"TPCDS-DUMP-CJ: $queryName | gate=$costGate | #$i | " +
          s"left=${stats(cj.left)} | right=${stats(cj.right)} | " +
          s"countLeft=${cj.countLeft.map(_.sql).getOrElse("-")} | " +
          s"countRight=${cj.countRight.map(_.sql).getOrElse("-")} | " +
          s"aggs=$aggs | groups=$groups | cond=$cond")
      }
      // scalastyle:on println
    }
  }

  test("TPC-DS CountJoin planning dump") {
    queryNames.foreach { name =>
      dump(name, costGate = false)
      dump(name, costGate = true)
    }
  }
}
