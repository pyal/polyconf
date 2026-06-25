package org.polyconf.cli.stream

import org.apache.avro.Schema
import org.apache.avro.Schema.Type._
import org.apache.avro.file.{DataFileReader, DataFileWriter}
import org.apache.avro.generic.{GenericData, GenericDatumReader, GenericDatumWriter, GenericRecord}
import org.apache.avro.io.DatumReader
import org.apache.hadoop.conf.Configuration
import org.apache.parquet.avro.{AvroParquetReader, AvroParquetWriter}
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.hadoop.util.{HadoopInputFile, HadoopOutputFile}

import org.polyconf.util.PolyUtil

import java.io.File
import scala.jdk.CollectionConverters._

private object RecordConverters {
  def recordToMap(record: GenericRecord): Map[String, Any] = {
    val fields = record.getSchema.getFields.asScala.toList.map(_.name())
    fields.map { f =>
      val raw = record.get(f)
      val converted = raw match {
        case u: org.apache.avro.util.Utf8 => u.toString
        case other => other
      }
      f -> converted
    }.toMap
  }

  def mapToRecord(row: Map[String, Any], schema: Schema): GenericData.Record = {
    val record = new GenericData.Record(schema)
    schema.getFields.asScala.foreach { field =>
      record.put(field.name(), row.getOrElse(field.name(), null))
    }
    record
  }
}

private[stream] object AvroUtils {

  def inferSchema(data: StreamData): Schema = {
    val allKeys = data.data.flatMap(_.keys).distinct.sorted
    val fields = allKeys.map { name =>
      val values = data.data.map(_.get(name).orNull)
      val hasNull = values.contains(null)
      val nonNullTypes = values.filter(_ != null).map(avroType).distinct
      val baseType = nonNullTypes.headOption.getOrElse(STRING)
      val fieldSchema =
        if (hasNull)
          Schema.createUnion(Schema.create(NULL), Schema.create(baseType))
        else
          Schema.create(baseType)
      val default = if (hasNull) null else avroDefault(baseType)
      new Schema.Field(name, fieldSchema, null, default)
    }
    Schema.createRecord("Record", null, "polyconf", false, fields.toList.asJava)
  }

  private def avroType(value: Any): Schema.Type = value match {
    case _: String  => STRING
    case _: Int     => INT
    case _: Long    => LONG
    case _: Double  => DOUBLE
    case _: Boolean => BOOLEAN
    case _: Number  => DOUBLE
    case _          => STRING
  }

  private def avroDefault(tpe: Schema.Type): Any = tpe match {
    case STRING  => ""
    case INT     => 0
    case LONG    => 0L
    case DOUBLE  => 0.0
    case BOOLEAN => false
    case _       => null
  }

  def read(path: String): Seq[Map[String, Any]] = {
    val datumReader: DatumReader[GenericRecord] = new GenericDatumReader[GenericRecord]()
    val file = new File(path)
    PolyUtil.withResource(new DataFileReader[GenericRecord](file, datumReader)) { dataFileReader =>
      val result = scala.collection.mutable.ListBuffer.empty[Map[String, Any]]
      dataFileReader.forEach { record => result += RecordConverters.recordToMap(record) }
      result.toSeq
    }.get
  }

  def write(data: StreamData, path: String, schema: Schema): Unit = {
    val datumWriter = new GenericDatumWriter[GenericRecord](schema)
    PolyUtil.withResource(new DataFileWriter[GenericRecord](datumWriter)) { dataFileWriter =>
      dataFileWriter.create(schema, new File(path))
      data.data.foreach { row => dataFileWriter.append(RecordConverters.mapToRecord(row, schema)) }
    }.get
  }
}

private[stream] object ParquetUtils {

  def read(path: String): StreamData = {
    val file = new File(path)
    val conf = new Configuration()
    PolyUtil.withResource(buildReader(file, conf)) { reader =>
      val result = scala.collection.mutable.ListBuffer.empty[Map[String, Any]]
      var record = reader.read()
      while (record != null) {
        result += RecordConverters.recordToMap(record)
        record = reader.read()
      }
      StreamData(result.toSeq)
    }.get
  }

  def write(data: StreamData, path: String): Unit = {
    val schema = AvroUtils.inferSchema(data)
    val file = new File(path)
    val conf = new Configuration()
    PolyUtil.withResource(buildWriter(file, schema, conf)) { writer =>
      data.data.foreach { row => writer.write(RecordConverters.mapToRecord(row, schema)) }
    }.get
  }

  private def buildReader(file: File, conf: Configuration) =
    AvroParquetReader.builder[GenericRecord](HadoopInputFile.fromPath(new org.apache.hadoop.fs.Path(file.toURI), conf))
      .withConf(conf)
      .build()

  private def buildWriter(file: File, schema: org.apache.avro.Schema, conf: Configuration) =
    AvroParquetWriter.builder[GenericRecord](HadoopOutputFile.fromPath(new org.apache.hadoop.fs.Path(file.toURI), conf))
      .withSchema(schema)
      .withConf(conf)
      .withCompressionCodec(CompressionCodecName.SNAPPY)
      .build()
}
