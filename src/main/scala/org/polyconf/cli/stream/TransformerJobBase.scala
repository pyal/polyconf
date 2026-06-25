package org.polyconf.cli.stream

import org.polyconf.core.PolyConf

import scala.util._

trait StreamDataBase {
  def countPass: (StreamDataBase, () => Long)
  def persist: this.type
  def unpersist: this.type
}

trait FileSource {
  self: PolyConf =>
  val path: String = ""
  val format: String = "text"
  val options: Map[String, String] = Map.empty

  def validate(): Unit = LocalDataIORegistry.get(format)
}

final case class StreamDataEnvelope[T <: StreamDataBase](
    data: Try[T],
    inputPath: String
) {
  def mapData(f: T => T): StreamDataEnvelope[T] =
    copy(data = data.map(f))
}

trait StreamGenerator[T <: StreamDataBase] extends PolyConf {
  def readIterator: Iterator[StreamDataEnvelope[T]]
  def readIterator(successRecorderOpt: Option[SuccessRecorderBase]): Iterator[StreamDataEnvelope[T]] = readIterator
}

trait StreamTransformer[T <: StreamDataBase] extends PolyConf {
  def transform(input: StreamDataEnvelope[T]): Iterator[StreamDataEnvelope[T]]
  def jobDone(): Unit = {}
}

trait StreamWriter[T <: StreamDataBase] extends PolyConf {
  def write(input: StreamDataEnvelope[T]): Try[Unit]
  def jobDone(): Unit = {}
}

final case class StageCount(path: String, stage: String, count: () => Long)

final case class TransformerException[T <: StreamDataBase](dataFailed: Seq[StreamDataEnvelope[T]]) extends RuntimeException {
  require(dataFailed.nonEmpty && dataFailed.forall(_.data.isFailure))

  override def getMessage: String = {
    val filesFailed = dataFailed.map(_.inputPath)
    s"Failed files ${filesFailed.size}\n" + filesFailed.mkString("\n")
  }

  override def getCause: Throwable = dataFailed.head.data.failed.get
}
