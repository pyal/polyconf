package org.polyconf

import org.polyconf.util.PolyNumFormat
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PolyNumFormatSpec extends AnyFlatSpec with Matchers {

  "PolyNumFormat.prettyNumber" should "format small numbers" in {
    PolyNumFormat.prettyNumber(0) shouldBe "0"
    PolyNumFormat.prettyNumber(5) shouldBe "5"
    PolyNumFormat.prettyNumber(999) shouldBe "999"
  }

  it should "format thousands" in {
    PolyNumFormat.prettyNumber(1000) shouldBe "1K"
    PolyNumFormat.prettyNumber(1500) shouldBe "1K 500"
    PolyNumFormat.prettyNumber(1234567) shouldBe "1M 234K"
  }

  it should "format negative numbers" in {
    PolyNumFormat.prettyNumber(-1000) shouldBe "-(1K)"
  }

  "PolyNumFormat.prettyNanoTime" should "format nanoseconds" in {
    PolyNumFormat.prettyNanoTime(500) shouldBe "500ns"
    PolyNumFormat.prettyNanoTime(1500) shouldBe "1us 500ns"
  }

  it should "format seconds and minutes" in {
    PolyNumFormat.prettyNanoTime(123456789000L) shouldBe "2m 3s"
  }
}
