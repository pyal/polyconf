package org.polyconf.cli.stream

import scala.math.Ordering.Implicits._

final case class TransformerDebug(
    columns: Seq[String] = Seq.empty,
    limit: Int = 0,
    hashColumn: String = "",
    maxHashes: Int = 0,
    givenHash: Int = 0,
    sortColumns: Seq[String] = Seq.empty,
    sortAsc: Boolean = true
) extends StreamTransformer[StreamData] {

  override def transform(input: StreamDataEnvelope[StreamData]): Iterator[StreamDataEnvelope[StreamData]] = {
    val result = input.mapData { data =>
      var d = data
      if (columns.nonEmpty) d = d.select(columns: _*)
      if (hashColumn.nonEmpty && maxHashes > 0)
        d = d.filter { row =>
          row.get(hashColumn).exists(v => (v.hashCode & Int.MaxValue) % maxHashes == givenHash)
        }
      if (sortColumns.nonEmpty) {
        val sorted = d.data.sortBy { row =>
          sortColumns.map(c => row.getOrElse(c, "").toString)
        }
        d = if (sortAsc) StreamData(sorted) else StreamData(sorted.reverse)
      }
      if (limit > 0) d = d.head(limit)
      d
    }
    Iterator(result)
  }

  override def help: String =
    """
      |Test transformer for verifying pipeline functionality.
      |columns: keep only these columns
      |limit: max rows to keep
      |hashColumn / maxHashes / givenHash: filter by hash % maxHashes == givenHash
      |sortColumns / sortAsc: sort by columns
      |""".stripMargin
}
