package org.polyconf.cli.stream

import java.io._
import org.polyconf.core.{PolyConf, PolySerde}
import org.polyconf.util.PolyUtil
import scala.io.Source

private object WriteUtils {
  def writeFile(path: String)(writeFn: PrintWriter => Unit): Unit =
    PolyUtil.withResource(new PrintWriter(new File(path)))(writeFn).get
}

trait LocalDataIO {
  def read(path: String, options: Map[String, String] = Map.empty): StreamData
  def write(data: StreamData, path: String, mode: WriteMode = WriteMode.Overwrite, options: Map[String, String] = Map.empty): Unit
  def removeStorage(storageName: String, options: Map[String, String] = Map.empty): Unit = {}
  def help: String = "No help"
}

object LocalTextIO extends LocalDataIO {
  override def help: String = "text: reads/writes plain text files, each line as a 'value' record"
  override def read(path: String, options: Map[String, String]): StreamData = {
    val lines = PolyUtil.withResource(Source.fromFile(path))(_.getLines().toSeq).get
    StreamData(lines.zipWithIndex.map { case (line, i) =>
      Map("value" -> line, "line" -> i.toLong)
    })
  }

  override def write(data: StreamData, path: String, mode: WriteMode, options: Map[String, String]): Unit =
    WriteUtils.writeFile(path) { pw =>
      data.data.foreach { row =>
        pw.println(row.getOrElse("value", row.values.mkString("\t")))
      }
    }
}

object LocalJsonIO extends LocalDataIO {
  override def help: String = "json: reads/writes JSON array of objects"
  override def read(path: String, options: Map[String, String]): StreamData = {
    val content = PolyUtil.withResource(Source.fromFile(path))(_.mkString).get
    val parsed = PolyConf.jsonBasicMapper.readValue(content, classOf[Seq[Map[String, Any]]])
    StreamData(parsed)
  }

  override def write(data: StreamData, path: String, mode: WriteMode, options: Map[String, String]): Unit =
    WriteUtils.writeFile(path)(_.write(PolySerde.jsonFormat(data.data, prettyFormat = true)))
}

object LocalAvroIO extends LocalDataIO {
  override def help: String = "avro: reads/writes Avro files with inferred schema"
  override def read(path: String, options: Map[String, String]): StreamData = {
    StreamData(AvroUtils.read(path))
  }

  override def write(data: StreamData, path: String, mode: WriteMode, options: Map[String, String]): Unit = {
    val schema = AvroUtils.inferSchema(data)
    AvroUtils.write(data, path, schema)
  }
}

object LocalParquetIO extends LocalDataIO {
  override def help: String = "parquet: reads/writes Parquet files with inferred schema"
  override def read(path: String, options: Map[String, String]): StreamData = {
    ParquetUtils.read(path)
  }

  override def write(data: StreamData, path: String, mode: WriteMode, options: Map[String, String]): Unit = {
    ParquetUtils.write(data, path)
  }
}

object LocalCsvIO extends LocalDataIO {
  override def help: String = "csv: reads/writes CSV files, options: delimiter (char), skipRows (int)"
  override def read(path: String, options: Map[String, String]): StreamData = {
    val delimiter = options.getOrElse("delimiter", ",").headOption.getOrElse(',')
    val skipRows = options.get("skipRows").filter(_.nonEmpty).map(_.toInt).getOrElse(0)
    val lines = PolyUtil.withResource(Source.fromFile(path))(_.getLines().toSeq).get
    val rows = lines.drop(skipRows)
    if (rows.isEmpty) return StreamData(Seq.empty)
    val header = parseCsvLine(rows.head, delimiter).map(_.trim)
    val data = rows.tail.map { line =>
      val values = parseCsvLine(line, delimiter)
      if (values.length != header.length)
        throw new IllegalArgumentException(
          s"CSV row has ${values.length} columns but header has ${header.length} columns. Row: $line"
        )
      header.zip(values.map(_.trim)).toMap
    }
    StreamData(data)
  }

  override def write(data: StreamData, path: String, mode: WriteMode, options: Map[String, String]): Unit = {
    val delimiter = options.getOrElse("delimiter", ",").headOption.getOrElse(',')
    WriteUtils.writeFile(path) { pw =>
      if (data.data.nonEmpty) {
        val header = data.data.head.keys.toSeq
        pw.println(header.map(escapeCsv(_, delimiter)).mkString(delimiter.toString))
        data.data.foreach { row =>
          pw.println(header.map(c => escapeCsv(row.getOrElse(c, "").toString, delimiter)).mkString(delimiter.toString))
        }
      }
    }
  }

  private def parseCsvLine(line: String, delimiter: Char): Seq[String] = {
    val result = Seq.newBuilder[String]
    val current = new StringBuilder
    var inQuotes = false
    var i = 0
    while (i < line.length) {
      val ch = line.charAt(i)
      if (inQuotes) {
        if (ch == '"') {
          if (i + 1 < line.length && line.charAt(i + 1) == '"') {
            current.append('"')
            i += 1
          } else {
            inQuotes = false
          }
        } else {
          current.append(ch)
        }
      } else if (ch == '"') {
        inQuotes = true
      } else if (ch == delimiter) {
        result += current.toString
        current.clear()
      } else {
        current.append(ch)
      }
      i += 1
    }
    result += current.toString
    result.result()
  }

  private def escapeCsv(field: String, delimiter: Char): String = {
    if (field.contains('"') || field.contains(delimiter) || field.contains('\n') || field.contains('\r'))
      "\"" + field.replace("\"", "\"\"") + "\""
    else
      field
  }
}

object LocalDataIORegistry {
  private val formats: Map[String, LocalDataIO] = Map(
    "text"    -> LocalTextIO,
    "json"    -> LocalJsonIO,
    "csv"     -> LocalCsvIO,
    "avro"    -> LocalAvroIO,
    "parquet" -> LocalParquetIO,
  )

  def get(format: String): LocalDataIO =
    formats.getOrElse(format.toLowerCase,
      throw new IllegalArgumentException(
        s"Unknown format: $format. Available: ${formats.keys.toSeq.sorted.mkString(", ")}"
      ))

  def readFormat(format: String, path: String, options: Map[String, String] = Map.empty): StreamData =
    get(format).read(path, options)

  def writeFormat(format: String, data: StreamData, path: String, mode: WriteMode = WriteMode.Overwrite, options: Map[String, String] = Map.empty): Unit =
    get(format).write(data, path, mode, options)

  def inferFormatFromPath(path: String): String = {
    val ext = path.split("\\.").lastOption.getOrElse("text").toLowerCase
    if (formats.contains(ext)) ext else "text"
  }

  def availableFormats: Seq[String] = formats.keys.toSeq.sorted
}
