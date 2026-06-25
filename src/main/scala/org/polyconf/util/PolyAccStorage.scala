package org.polyconf.util

import scala.collection.mutable

trait PolyAccumulatorBasic[V] {
  def add(value: V): Unit
  def get: V
  def copy: PolyAccumulatorBasic[V]
  def clear(): PolyAccumulatorBasic[V]
}

class PolyAccumulatorLong(private var total: Long = 0L) extends PolyAccumulatorBasic[Long] {
  def add(value: Long): Unit = total += value
  def get: Long = total
  override def copy: PolyAccumulatorBasic[Long] = new PolyAccumulatorLong(total)
  override def clear(): PolyAccumulatorBasic[Long] = { total = 0L; this }
}

class PolyAccStorage[V](val agg: PolyAccumulatorBasic[V]) {
  private val store = mutable.Map.empty[String, PolyAccumulatorBasic[V]]

  def get(name: String): V = store.synchronized {
    store.getOrElseUpdate(name, agg.copy).get
  }

  def update(name: String, acc: PolyAccumulatorBasic[V]): Unit = store.synchronized {
    store(name) = acc
  }

  def add(name: String, value: V): Unit = store.synchronized {
    store.getOrElseUpdate(name, agg.copy).add(value)
  }

  def entries: Map[String, V] = store.synchronized {
    store.view.mapValues(_.get).toMap
  }

  def clear(): Unit = store.synchronized { store.clear() }
}

object PolyAccStorage {
  def apply[V](agg: PolyAccumulatorBasic[V]): PolyAccStorage[V] = new PolyAccStorage(agg)
  lazy val accLong: PolyAccStorage[Long] = apply(new PolyAccumulatorLong())
  def accPerf[V](implicit num: Numeric[V]): PolyAccStorage[PolyAccumulatorPerf[V]] =
    apply(new PolyAccumulatorPerf[V])
}
