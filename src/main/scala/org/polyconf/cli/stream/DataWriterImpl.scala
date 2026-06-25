package org.polyconf.cli.stream

import org.polyconf.util.PolyLog.Log
import scala.util.Try

final case class SimpleDataWriter() extends StreamWriter[StreamData] with FileSource {

  override def verify(): Unit = super.validate()

  override def write(input: StreamDataEnvelope[StreamData]): Try[Unit] = {
    input.data.flatMap { data =>
      Try {
        LocalDataIORegistry.writeFormat(format, data, path, WriteMode.fromString(options.getOrElse("mode", "overwrite")), options)
        Log.info(s"Wrote ${data.size} rows to $path ($format) mode=${options.getOrElse("mode", "overwrite")}")
      }
    }
  }

  override def help: String =
    s"""
       |Simple file writer.
       |Writes data using format registry.
       |path: output file path
       |format: one of ${LocalDataIORegistry.availableFormats.mkString(", ")}
       |options: write options (mode: overwrite, append, insert, insertDedup, etc.)
       |""".stripMargin
}



final case class VerifyDataWriter() extends StreamWriter[StreamData] with FileSource {

  override def verify(): Unit = super.validate()

  override def write(input: StreamDataEnvelope[StreamData]): Try[Unit] = {
    input.data.flatMap { data =>
      Try {
        LocalDataIORegistry.writeFormat(format, data, path, WriteMode.fromString(options.getOrElse("mode", "overwrite")), options)
        val written = LocalDataIORegistry.readFormat(format, path, options)
        if (written.data != data.data) {
          val msg = diffMsg(data.data, written.data)
          throw new RuntimeException(
            s"Data mismatch after write/read roundtrip at $path\n$msg"
          )
        }
        Log.info(s"Verified ${data.size} rows roundtrip at $path")
      }
    }
  }

  private def diffMsg(
                       original: Seq[Map[String, Any]],
                       written: Seq[Map[String, Any]]
                     ): String = {
    if (original.size != written.size)
      s"Row count differs: original=${original.size} written=${written.size}"
    else
      original.zip(written).zipWithIndex
        .flatMap { case ((a, b), i) =>
          val diffKeys = (a.keySet ++ b.keySet).filter(k => a.get(k) != b.get(k))
          if (diffKeys.isEmpty) None
          else Some(s"Row $i differs on keys: $diffKeys\n  original: $a\n  written: $b")
        }
        .mkString("\n")
  }

  override def help: String =
    """
      |Writes data using LocalDataIO, then reads it back and verifies roundtrip.
      |Throws on mismatch.
      |""".stripMargin
}
