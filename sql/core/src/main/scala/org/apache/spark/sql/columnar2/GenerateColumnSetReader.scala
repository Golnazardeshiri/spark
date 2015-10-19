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

package org.apache.spark.sql.columnar2

import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.catalyst.expressions.codegen.{CodeFormatter, CodeGenerator}
import org.apache.spark.sql.types._

/**
 * Specifies data to be read from a ColumnSet. This requires knowing both the original schema
 * and the fields we want to read (i.e. what we're projecting out).
 *
 * @param schema
 * @param fieldsRead
 */
private[sql] case class ReadSpec(schema: StructType, fieldsRead: Array[Int])

/**
 * Code generator for ColumnSetReaders.
 */
private[sql]
class GenerateColumnSetReader extends CodeGenerator[ReadSpec, ColumnSetReader] {
  /**
   * Canonicalizes an input expression. Used to avoid double caching expressions that differ only
   * cosmetically.
   */
  override protected def canonicalize(in: ReadSpec): ReadSpec = in

  /** Binds an input expression to a given input schema */
  override protected def bind(in: ReadSpec, inputSchema: Seq[Attribute]): ReadSpec = in

  /**
   * Generates a class for a given input expression.  Called when there is not cached code
   * already available.
   */
  override protected def create(spec: ReadSpec): ColumnSetReader = {
    val code = s"""
      import org.apache.spark.sql.catalyst.expressions.*;
      import org.apache.spark.sql.columnar2.*;

      public MyReader generate($exprType[] exprs) {
        return new MyReader();
      }

      class MyReader implements ${classOf[ColumnSetReader].getName} {
        private int numRows;
        private int rowsRead;
        private MutableRow outputRow;
        ${spec.fieldsRead.map(i => columnDeclarations(spec.schema(i), "_" + i)).mkString("\n")}

        public void initialize(ColumnSet data, MutableRow outputRow) {
          this.numRows = data.numRows();
          this.rowsRead = 0;
          this.outputRow = outputRow;
          java.util.Map cols = data.columns();
          ${spec.fieldsRead.map(i => columnInitializers(spec.schema(i), "_" + i)).mkString("\n")}
        }

        public boolean readNext() {
          if (this.rowsRead == this.numRows) {
            return false;
          }
          ${spec.fieldsRead.zipWithIndex.map{ case (f, i) =>
             readField(spec.schema(f), "_" + f, "outputRow", i)
           }.mkString("\n")}
          this.rowsRead += 1;
          return true;
        }
      }
      """

    // TODO: replace with log statement
    println(CodeFormatter.format(code))

    compile(code).generate(Array()).asInstanceOf[ColumnSetReader]
  }

  /**
   * Generate the variable declarations required to read a specific column, given its path prefix
   * (e.g. "_0" for field 0, "_0_1" for "0.1", etc).
   */
  private def columnDeclarations(field: StructField, prefix: String): String = {
    val nullable = if (field.nullable) {
      s"""ColumnReader ${prefix}_set;\n"""
    } else {
      ""
    }

    val data = field.dataType match {
      case IntegerType =>
        s"""ColumnReader ${prefix}_data;"""

      case StringType =>
        s"""ColumnReader ${prefix}_data;
            ColumnReader ${prefix}_length;"""

      case StructType(fields) =>
        fields.zipWithIndex.map { case (f, i) =>
          columnDeclarations(f, prefix + "_" + i)
        }.mkString("\n")
    }

    nullable + data
  }

  /**
   * Generate the code to initialize readers for a specific column, given its path prefix
   * (e.g. "_0" for field 0, "_0_1" for "0.1", etc).
   */
  private def columnInitializers(field: StructField, prefix: String): String = {
    val nullable = if (field.nullable) {
      s"""${prefix}_set = new ColumnReader((Column) cols.get("${mapKey(prefix + "_set")}"));\n"""
    } else {
      ""
    }

    val data = field.dataType match {
      case IntegerType =>
        s"""${prefix}_data = new ColumnReader((Column) cols.get("${mapKey(prefix + "_data")}"));"""

      case StringType =>
        s"""${prefix}_data = new ColumnReader((Column) cols.get("${mapKey(prefix + "_data")}"));
            ${prefix}_length = new ColumnReader((Column) cols.get("${mapKey(prefix + "_length")}"));
          """

      case StructType(fields) =>
        fields.zipWithIndex.map { case (f, i) =>
          columnInitializers(f, prefix + "_" + i)
        }.mkString("\n")
    }

    nullable + data
  }

  /**
   * Generate code to read the next instance of a field into a given ordinal position in rowVar
   * (which should be a SpecificMutableRow), given the path prefix for the field.
   */
  private def readField(
      field: StructField,
      prefix: String,
      rowVar: String,
      ordinalInRow: Int): String = {
    // Uniquely named local variables that won't clash with our field names
    val length = s"${prefix}_length_"
    val bytes = s"${prefix}_bytes_"
    val row = s"${prefix}_row_"

    val dataReadCode = field.dataType match {
      case IntegerType =>
        s"""$rowVar.setInt($ordinalInRow, ${prefix}_data.readInt());"""

      // TODO: Could avoid object allocation here by reusing the buffer, but we need to be careful
      // if strings can also be read inside a List or Map
      case StringType =>
        s"""int $length = ${prefix}_length.readInt();
            byte[] $bytes = new byte[$length];
            ${prefix}_data.readBytes($bytes, 0, $length);
            $rowVar.update($ordinalInRow, UTF8String.fromBytes($bytes));
          """

      // TODO: Could avoid object allocation by reusing the row, with same caveats as above
      case StructType(fields) =>
        s"""GenericMutableRow $row = new GenericMutableRow(${fields.length});
            ${fields.zipWithIndex.map { case (f, i) =>
              readField(f, prefix + "_" + i, row, i)
            }.mkString("\n")}
            $rowVar.update($ordinalInRow, $row);
         """
    }

    if (field.nullable) {
      s"""if (${prefix}_set.readBoolean()) {
            $dataReadCode
          } else {
            $rowVar.setNullAt($ordinalInRow);
          }
       """.stripMargin
    } else {
      dataReadCode
    }
  }

  /**
   * Convert a path name as used in our generated code (e.g. "_0_data") to the corresponding key in
   * a ColumnSet's hash map (e.g. "0.data").
   */
  private def mapKey(path: String): String = {
    path.substring(1).replace('_', '.')
  }
}
