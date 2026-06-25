package org.polyconf.util

class PolyAccumulatorPerf[V](implicit num: Numeric[V]) extends PolyAccumulatorBasic[PolyAccumulatorPerf[V]] {
  private var totalCount: V = num.zero
  private var totalTimeNanos: Long = 0L

  def count: V = totalCount
  def timeNanos: Long = totalTimeNanos

  def add(other: PolyAccumulatorPerf[V]): Unit = {
    totalCount = num.plus(totalCount, other.totalCount)
    totalTimeNanos += other.totalTimeNanos
  }

  def addCount(value: V): Unit = {
    totalCount = num.plus(totalCount, value)
  }

  def addTime(nanos: Long): Unit = {
    totalTimeNanos += nanos
  }

  def addCountTime(value: V, nanos: Long): Unit = {
    totalCount = num.plus(totalCount, value)
    totalTimeNanos += nanos
  }

  def get: PolyAccumulatorPerf[V] = copy

  override def copy: PolyAccumulatorPerf[V] = {
    val c = new PolyAccumulatorPerf[V]
    c.totalCount = totalCount
    c.totalTimeNanos = totalTimeNanos
    c
  }

  override def clear(): PolyAccumulatorPerf[V] = {
    totalCount = num.zero
    totalTimeNanos = 0L
    this
  }

  override def toString: String = {
    val count = num.toLong(totalCount)
    if (totalTimeNanos > 0 && count > 0) {
      val secs = totalTimeNanos / 1e9
      val iPerSec = count / secs
      val secPerINanos = if (iPerSec > 0) (1e9 / iPerSec).toLong else 0L
      s"${PolyNumFormat.prettyNumber(count)} items, ${PolyNumFormat.prettyNanoTime(totalTimeNanos)} " +
        s"(Speed I/s ${"%3.2e".format(iPerSec)} s/I ${PolyNumFormat.prettyNanoTime(secPerINanos)})"
    } else if (totalTimeNanos > 0) {
      PolyNumFormat.prettyNanoTime(totalTimeNanos)
    } else {
      PolyNumFormat.prettyNumber(count)
    }
  }
}

object PolyAccumulatorPerf {
  def withCount[V](value: V)(implicit num: Numeric[V]): PolyAccumulatorPerf[V] = {
    val p = new PolyAccumulatorPerf[V]
    p.totalCount = value
    p
  }

  def withTime[V](nanos: Long)(implicit num: Numeric[V]): PolyAccumulatorPerf[V] = {
    val p = new PolyAccumulatorPerf[V]
    p.totalTimeNanos = nanos
    p
  }

  def withCountTime[V](value: V, nanos: Long)(implicit num: Numeric[V]): PolyAccumulatorPerf[V] = {
    val p = new PolyAccumulatorPerf[V]
    p.totalCount = value
    p.totalTimeNanos = nanos
    p
  }
}
