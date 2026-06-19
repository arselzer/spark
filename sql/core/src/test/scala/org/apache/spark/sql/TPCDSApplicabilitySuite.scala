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
 * Applicability report for the Yannakakis count-join rewrite over the full TPC-DS v1.4 query set
 * (99 queries). Uses the [[TPCDSBase]] catalog (real column schemas + injected sf100 statistics),
 * so NO generated data is needed: we only need the rule to fire (or not) during logical
 * optimization. For each query this plans it with the rewrite enabled and reports:
 *   - whether the rewrite FIRED (any "new aggregate (...)" classification was logged), and how
 *     many distinct join blocks it rewrote (count of CountJoin markers in the optimized plan);
 *   - the aggregate class(es) the rule assigned (0MA / guarded / distinct-reduced / outer split /
 *     mixed-distinct split / ...);
 *   - whether the rewrite survives the production cost gate (cost gate ON);
 *   - any EXCEPTION thrown during optimization (the rule must never crash a query - it has a
 *     try/catch fallback, but the outer-join and cyclic paths are tested here for the first time
 *     on the TPC-DS shapes).
 *
 * Run with:
 *   build/sbt "sql/testOnly org.apache.spark.sql.TPCDSApplicabilitySuite"
 */
class TPCDSApplicabilitySuite extends QueryTest with TPCDSBase {

  // Inject sf100 row/byte stats so the cost gate and broadcast decisions are realistic.
  override def injectStats: Boolean = true

  override protected def sparkConf: SparkConf =
    super.sparkConf
      // keep generated code under the 8000-byte method limit for the wide TPC-DS projects
      .set(SQLConf.READ_SIDE_CHAR_PADDING, false)

  // The canonical TPC-DS v1.4 query set (matches the files in src/test/resources/tpcds/).
  private val allQueries: Seq[String] = Seq(
    "q1", "q2", "q3", "q4", "q5", "q6", "q7", "q8", "q9", "q10", "q11",
    "q12", "q13", "q14a", "q14b", "q15", "q16", "q17", "q18", "q19", "q20",
    "q21", "q22", "q23a", "q23b", "q24a", "q24b", "q25", "q26", "q27", "q28", "q29", "q30",
    "q31", "q32", "q33", "q34", "q35", "q36", "q37", "q38", "q39a", "q39b", "q40",
    "q41", "q42", "q43", "q44", "q45", "q46", "q47", "q48", "q49", "q50",
    "q51", "q52", "q53", "q54", "q55", "q56", "q57", "q58", "q59", "q60",
    "q61", "q62", "q63", "q64", "q65", "q66", "q67", "q68", "q69", "q70",
    "q71", "q72", "q73", "q74", "q75", "q76", "q77", "q78", "q79", "q80",
    "q81", "q82", "q83", "q84", "q85", "q86", "q87", "q88", "q89", "q90",
    "q91", "q92", "q93", "q94", "q95", "q96", "q97", "q98", "q99")

  // CountJoin is the only count-propagation path; CountJoin plan markers identify rewritten
  // counting blocks.
  /** Optimize `queryString` with the rewrite enabled; returns (optimizedPlanString, classes). */
  private def optimizeAndClassify(queryString: String, costGate: Boolean)
      : (String, Seq[String], Option[Throwable]) = {
    val appender = new LogAppender("yannakakis classification")
    var planStr = ""
    var err: Option[Throwable] = None
    try {
      withLogAppender(appender) {
        withSQLConf(
          SQLConf.YANNAKAKIS_ENABLED.key -> "true",
          SQLConf.YANNAKAKIS_UNGUARDED_ENABLED.key -> "true",
          SQLConf.YANNAKAKIS_COST_GATE_ENABLED.key -> costGate.toString) {
          planStr = sql(queryString).queryExecution.optimizedPlan.toString
        }
      }
    } catch {
      case t: Throwable => err = Some(t)
    }
    val classes = appender.loggingEvents
      .map(_.getMessage.getFormattedMessage).distinct
      .collect {
        case m if m.contains("new aggregate (") =>
          m.substring(m.indexOf("new aggregate (") + 15).takeWhile(_ != ')')
        case m if m.contains("rewrite dropped attributes") => "dropped-fallback"
      }.distinct.toSeq
    (planStr, classes, err)
  }

  test("TPC-DS applicability report") {
    val report = new StringBuilder
    val exceptions = scala.collection.mutable.ListBuffer[String]()
    var firedStruct = 0
    var firedGated = 0

    for (name <- allQueries) {
      val queryString =
        try {
          resourceToString(s"tpcds/$name.sql",
            classLoader = Thread.currentThread().getContextClassLoader)
        } catch { case _: Throwable => null }

      val line = if (queryString == null) {
        s"TPCDS-APPLICABILITY: $name | MISSING-RESOURCE"
      } else {
        // Structural applicability: cost gate OFF.
        val (planOff, classesOff, errOff) = optimizeAndClassify(queryString, costGate = false)
        // Production applicability: cost gate ON.
        val (planOn, _, errOn) = optimizeAndClassify(queryString, costGate = true)

        val countJoins = "CountJoin".r.findAllIn(planOff).size
        val leftSemis = "LeftSemi".r.findAllIn(planOff).size
        val countJoinsOn = "CountJoin".r.findAllIn(planOn).size
        val leftSemisOn = "LeftSemi".r.findAllIn(planOn).size
        val firedOff = classesOff.exists(_ != "dropped-fallback")
        val firedOn = countJoinsOn > 0 || leftSemisOn > 0
        if (firedOff) firedStruct += 1
        if (firedOn) firedGated += 1

        val classStr = if (classesOff.isEmpty) "-" else classesOff.mkString("+")
        val fired = if (firedOff) "FIRED" else "no"
        val gated =
          if (firedOff && !firedOn) "cost-gate-SKIPS" else if (firedOn) "survives" else "-"
        val errStr = (errOff.toSeq ++ errOn.toSeq).headOption.map { t =>
          " | EXCEPTION " + (t.getClass.getSimpleName + " " +
            Option(t.getMessage).getOrElse("")).replaceAll("\\s+", " ").take(140)
        }.getOrElse("")
        if (errOff.isDefined || errOn.isDefined) {
          exceptions.append(s"$name:${errStr}")
        }
        f"TPCDS-APPLICABILITY: $name%-5s | $fired%-5s | " +
          f"blocks(off=CountJoin:$countJoins%d LeftSemi:$leftSemis%d, " +
          f"gate=CountJoin:$countJoinsOn%d LeftSemi:$leftSemisOn%d) | " +
          f"class=$classStr | gate=$gated$errStr"
      }
      // scalastyle:off println
      println(line)
      // scalastyle:on println
      report.append(line).append('\n')
    }

    val summary = s"TPCDS-APPLICABILITY-SUMMARY: ${allQueries.size} queries | " +
      s"fired(structural)=$firedStruct | fired(cost-gated)=$firedGated | " +
      s"exceptions=${exceptions.size}"
    // scalastyle:off println
    println(summary)
    println("TPCDS-APPLICABILITY-REPORT:\n" + report)
    // scalastyle:on println

    assert(exceptions.isEmpty,
      s"Yannakakis rewrite threw during optimization on ${exceptions.size} query/queries:\n" +
        exceptions.mkString("\n"))
  }
}
