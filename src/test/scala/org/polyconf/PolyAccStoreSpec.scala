package org.polyconf

import org.polyconf.util.{PolyAccStorage, PolyAccumulatorLong}

class PolyAccStoreSpec extends AccStoreFixture {

  "PolyAccumulatorLong" should "accumulate long values" in {
    val acc = new PolyAccumulatorLong()
    acc.add(5)
    acc.add(3)
    acc.get shouldBe 8L
  }

  "PolyAccStorage.accLong" should "accumulate long values by name" in {
    PolyAccStorage.accLong.add("rows", 5)
    PolyAccStorage.accLong.add("errors", 1L)
    PolyAccStorage.accLong.add("rows", 3)

    PolyAccStorage.accLong.get("rows") shouldBe 8L
    PolyAccStorage.accLong.get("errors") shouldBe 1L
  }

  it should "get entries" in {
    PolyAccStorage.accLong.add("a", 10)
    PolyAccStorage.accLong.add("b", 0)

    PolyAccStorage.accLong.entries should contain allOf ("a" -> 10L, "b" -> 0L)
  }

  it should "clear accLong" in {
    PolyAccStorage.accLong.add("x", 1)
    PolyAccStorage.accLong.clear()
    PolyAccStorage.accLong.entries shouldBe empty
  }

  it should "update existing accumulator" in {
    PolyAccStorage.accLong.add("scores", 10)
    val fresh = new PolyAccumulatorLong()
    fresh.add(99)
    PolyAccStorage.accLong.update("scores", fresh)
    PolyAccStorage.accLong.get("scores") shouldBe 99L
  }
}
