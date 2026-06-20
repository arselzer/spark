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

import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types.StructType

/**
 * Manual one-off (not part of CI): converts the JOB IMDB CSV snapshot into the parquet layout
 * JOBBenchmarkSuite expects, deriving each table's columns from the gregrahn schema.sql. Skips a
 * table whose parquet already exists. Run with:
 *   IMDB_CSV=/tmp/imdb-dl/csv sql/testOnly org.apache.spark.sql.IMDBSetupSuite
 */
class IMDBSetupSuite extends QueryTest with SharedSparkSession {

  private val csvDir = sys.env.getOrElse("IMDB_CSV", "/tmp/imdb-dl/csv")
  private val schemaFile = "/home/as/git/Spark-Y/data/job/schema.sql"
  private val outDir = "/home/as/git/Spark-Y/data/parquet/imdb"

  /** Parse schema.sql CREATE TABLE blocks into table -> Spark DDL (integer -> INT, else STRING). */
  private def parseSchema(): Map[String, StructType] = {
    val text = {
      val src = scala.io.Source.fromFile(schemaFile)
      try src.mkString finally src.close()
    }
    val block = "(?s)CREATE TABLE (\\w+)\\s*\\((.*?)\\);".r
    block.findAllMatchIn(text).map { m =>
      val table = m.group(1)
      val ddl = m.group(2).split("\n").map(_.trim).filter(_.nonEmpty).flatMap { line =>
        val toks = line.stripSuffix(",").split("\\s+")
        if (toks.length >= 2) {
          val sparkType = if (toks(1).equalsIgnoreCase("integer")) "INT" else "STRING"
          Some(s"`${toks(0)}` $sparkType")
        } else None
      }.mkString(", ")
      table -> StructType.fromDDL(ddl)
    }.toMap
  }

  test("convert IMDB CSV to parquet for JOB") {
    assume(new File(csvDir).isDirectory, s"IMDB CSVs not found at $csvDir; skipping")
    assume(new File(schemaFile).isFile, s"schema.sql not found at $schemaFile; skipping")
    new File(outDir).mkdirs()
    val schemas = parseSchema()
    // scalastyle:off println
    for ((table, schema) <- schemas.toSeq.sortBy(_._1)) {
      val csv = new File(s"$csvDir/$table.csv")
      val pq = new File(s"$outDir/$table")
      if (!csv.isFile) {
        println(s"IMDB-CONVERT: $table SKIP (no csv)")
      } else if (pq.isDirectory) {
        println(s"IMDB-CONVERT: $table SKIP (parquet exists)")
      } else {
        val df = spark.read
          .option("sep", ",")
          .option("quote", "\"")
          .option("escape", "\\")
          .option("multiLine", "true")
          .option("nullValue", "")
          .schema(schema)
          .csv(csv.getPath)
        df.write.mode("overwrite").parquet(pq.getPath)
        println(s"IMDB-CONVERT: $table OK -> ${spark.read.parquet(pq.getPath).count()} rows, " +
          s"${schema.fields.length} cols")
      }
    }
    // scalastyle:on println
    val converted = new File(outDir).listFiles().count(_.isDirectory)
    println(s"IMDB-CONVERT-SUMMARY: $converted tables under $outDir")
    assert(converted >= 20, s"expected ~21 IMDB tables, got $converted")
  }
}
