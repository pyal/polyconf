package org.polyconf.cli.stream

import org.polyconf.core.PolyConf
import org.polyconf.util.PolyLog.Log

import java.io.File
import scala.util._

final case class SimpleDataGenerator(rowsPerFile: Int = -1) extends StreamGenerator[StreamData] with FileSource {

  override def verify(): Unit = {
    super.validate()
    if (path.isEmpty)
      throw new IllegalArgumentException("path must not be empty")
  }

  override def readIterator: Iterator[StreamDataEnvelope[StreamData]] = {
    val file = new File(path)
    if (file.isDirectory) {
      val fileList = file.listFiles()
      val files = Option(fileList).getOrElse {
        Log.warn(s"Could not list files in directory: $path")
        Array.empty[File]
      }.filter(_.isFile).sortBy(_.getName).toSeq
      if (files.isEmpty) {
        Log.warn(s"No files found in directory: $path")
        Iterator.empty
      } else {
        files.iterator.flatMap { f =>
          val fmt = if (format.isEmpty) LocalDataIORegistry.inferFormatFromPath(f.getPath) else format
          val data = Try(LocalDataIORegistry.readFormat(fmt, f.getPath, options).copy(description = Map("InputName" -> f.getName)))
          splitByRows(data, f.getPath)
        }
      }
    } else {
      val data = Try(LocalDataIORegistry.readFormat(format, path, options).copy(description = Map("InputName" -> file.getName)))
      splitByRows(data, path)
    }
  }

  private def splitByRows(data: Try[StreamData], sourcePath: String): Iterator[StreamDataEnvelope[StreamData]] = {
    if (rowsPerFile <= 0) {
      Iterator(StreamDataEnvelope(data, sourcePath))
    } else {
      data match {
        case Failure(e) => Iterator(StreamDataEnvelope(data, sourcePath))
        case Success(dp) =>
          dp.data.grouped(rowsPerFile).zipWithIndex.map { case (chunk, i) =>
            val desc = dp.description + ("SplitPart" -> s"${i + 1}")
            StreamDataEnvelope(Success(StreamData(chunk, desc)), s"$sourcePath#${i + 1}")
          }
      }
    }
  }

  override def help: String =
    s"""
       |Simple file reader.
       |Reads files using format registry.
       |path: file or directory path to read
       |format: one of ${LocalDataIORegistry.availableFormats.mkString(", ")}
       |options: read options (format-specific)
       |""".stripMargin
}
