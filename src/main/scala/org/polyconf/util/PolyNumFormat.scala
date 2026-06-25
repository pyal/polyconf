package org.polyconf.util

object PolyNumFormat {

  def prettyNanoTime(dtNanoseconds: Long, numNamedGroups: Int = 2): String = {
    val units   = Seq("ns", "us", "ms", "s", "m", "h", "d", "mo", "y")
    val factors = Seq(1000, 1000, 1000, 60, 60, 24, 30, 12, 10000000)
    format(dtNanoseconds, numNamedGroups, units, factors)
  }

  def prettyNumber(x: Long, numNamedGroups: Int = 2): String = {
    val units   = Seq("", "K", "M", "G", "T", "P", "E", "Z")
    val factors = Seq.fill(units.size)(1000)
    format(x, numNamedGroups, units, factors)
  }

  private def format(
      l: Long,
      printGroups: Int,
      units: Seq[String],
      factors: Seq[Int]
  ): String = {
    val absVal = BigInt(l).abs
    val digits =
      factors
        .scanLeft((absVal, BigInt(1)))((acc, factor) => (acc._1 / factor, acc._1 % factor))
        .tail
        .filter(x => x._1 + x._2 > 0)
        .map(_._2.toInt)

    if (digits.nonEmpty) {
      val pretty = digits
        .zip(units).reverse.take(printGroups).flatMap { case (d, u) =>
          if (d > 0) Some(s"$d$u") else None
        }.mkString(" ")
      if (l < 0) s"-($pretty)" else pretty
    } else "0"
  }
}
