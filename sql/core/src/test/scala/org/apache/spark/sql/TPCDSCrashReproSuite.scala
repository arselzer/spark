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

import org.apache.spark.SparkConf
import org.apache.spark.sql.catalyst.util.resourceToString
import org.apache.spark.sql.internal.SQLConf

/**
 * Isolates the trigger of the ClassCastException the Yannakakis rewrite throws on ~17 TPC-DS
 * queries. For each crasher we plan it with the rewrite on and print the full stack trace when
 * it throws, so we can see whether the throw is inside the rule (caught by its fallback) or in a
 * downstream optimizer rule operating on a malformed plan the rewrite emitted.
 */
class TPCDSCrashReproSuite extends QueryTest with TPCDSBase {
  override def injectStats: Boolean = true
  override protected def sparkConf: SparkConf =
    super.sparkConf.set(SQLConf.READ_SIDE_CHAR_PADDING, false)

  private val crashers = Seq("q30", "q15", "q12", "q98", "q1")

  private def plan(name: String): Unit = {
    val q = resourceToString(s"tpcds/$name.sql",
      classLoader = Thread.currentThread().getContextClassLoader)
    withSQLConf(
      SQLConf.YANNAKAKIS_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
      SQLConf.YANNAKAKIS_COST_GATE_ENABLED.key -> "false") {
      // scalastyle:off println
      try {
        sql(q).queryExecution.optimizedPlan
        println(s"CRASH-REPRO $name: NO EXCEPTION (safe rewrite/fallback)")
      } catch {
        case t: Throwable =>
          println(s"CRASH-REPRO $name: ${t.getClass.getName}: ${t.getMessage}")
          t.getStackTrace.take(18).foreach(f => println(s"    at $f"))
          val ruleFrames = t.getStackTrace.filter(
            _.getClassName.contains("RewriteJoinsAsSemijoins"))
          if (ruleFrames.nonEmpty) {
            println(s"  RULE-FRAMES: ${ruleFrames.map(_.getLineNumber).mkString(",")}")
          } else {
            println("  THROWN OUTSIDE THE RULE (downstream optimizer; fallback cannot catch)")
          }
      }
      // scalastyle:on println
    }
  }

  test("TPC-DS crash repro - countjoin optimizer robustness") {
    for (name <- crashers) {
      plan(name)
    }
  }
}
