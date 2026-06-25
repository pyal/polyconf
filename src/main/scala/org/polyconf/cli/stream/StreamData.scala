package org.polyconf.cli.stream

import org.polyconf.util.PolyLog

final case class StreamData(data: Seq[Map[String, Any]], description: Map[String, String] = Map.empty) extends StreamDataBase {
  def columns: Set[String] = data.flatMap(_.keys).toSet
  def size: Int = data.size
  def isEmpty: Boolean = data.isEmpty
  def nonEmpty: Boolean = data.nonEmpty

  def filter(p: Map[String, Any] => Boolean): StreamData =
    StreamData(data.filter(p), description)

  def map(f: Map[String, Any] => Map[String, Any]): StreamData =
    StreamData(data.map(f), description)

  def flatMap(f: Map[String, Any] => Seq[Map[String, Any]]): StreamData =
    StreamData(data.flatMap(f), description)

  def select(cols: String*): StreamData = {
    val available = if (data.nonEmpty) data.head.keySet else Set.empty[String]
    cols.filterNot(available.contains).foreach { c =>
      PolyLog.Log.warn(s"Column '$c' not found in data. Available columns: [${available.mkString(", ")}]")
    }
    StreamData(data.map(m => cols.flatMap(c => m.get(c).map(c -> _)).toMap), description)
  }

  def head(n: Int): StreamData = StreamData(data.take(n), description)

  def countPass: (StreamDataBase, () => Long) = (this, () => data.size.toLong)

  def persist: this.type = this

  def unpersist: this.type = this
}
