package org.polyconf

import org.polyconf.util.{PolyUtil, RetryPolicy}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.{BufferedReader, StringReader}
import scala.concurrent.duration._
import scala.util.Try

class PolyUtilSpec extends AnyFlatSpec with Matchers {

  "PolyUtil.withResource" should "return success for valid resource" in {
    val result = PolyUtil.withResource(new StringReader("hello")) { reader =>
      val buf = new BufferedReader(reader)
      buf.readLine()
    }
    result.get shouldBe "hello"
  }

  it should "return failure if resource construction fails" in {
    val result = PolyUtil.withResource[StringReader, Nothing](throw new RuntimeException("boom"))(
      _ => ??? : Nothing
    )
    result.isFailure shouldBe true
  }

  it should "return failure if function throws" in {
    val result = PolyUtil.withResource(new StringReader("")) { _ =>
      throw new RuntimeException("fn error")
    }
    result.isFailure shouldBe true
  }

  "PolyUtil.withData" should "manage lifecycle" in {
    var closed = false
    val res = PolyUtil.withData(new StringBuilder("data"))(s => { closed = true })(_.toString)
    res.get shouldBe "data"
    closed shouldBe true
  }

  "RetryPolicy" should "provide correct delays" in {
    val (d1, p1) = RetryPolicy(1000, 3, 2.0).nextDelayTime
    d1 shouldBe Some(1000)
    val (d2, p2) = p1.nextDelayTime
    d2 shouldBe Some(2000)
    val (d3, p3) = p2.nextDelayTime
    d3 shouldBe Some(4000)
    val (d4, _) = p3.nextDelayTime
    d4 shouldBe None
  }

  it should "handle infinite retries" in {
    val (d, _) = RetryPolicy(100, -1).nextDelayTime
    d shouldBe Some(100)
  }

  "PolyUtil.waitedGet" should "return successful result immediately" in {
    PolyUtil.waitedGet(42) shouldBe 42
  }

  it should "retry on failure" in {
    var count = 0
    val res = PolyUtil.waitedGet(
      {
        count += 1
        if (count < 3) throw new RuntimeException(s"attempt $count failed")
        "success"
      },
      RetryPolicy(10, 5)
    )
    res shouldBe "success"
    count shouldBe 3
  }

  it should "throw after exhausting retries" in {
    var count = 0
    intercept[RuntimeException] {
      PolyUtil.waitedGet(
        {
          count += 1
          throw new RuntimeException(s"fail $count")
        },
        RetryPolicy(10, 2)
      )
    }
    count shouldBe 3 // retryLimit=2 means 2 delays => condition evaluated 3 times (1 initial + 2 retries)
  }

  "PolyUtil.waitForCondition" should "return immediately if condition is true" in {
    PolyUtil.waitForCondition(true, RetryPolicy(10, 3))
  }

  it should "poll until condition becomes true" in {
    var flag = false
    val t = new Thread(() => { Thread.sleep(50); flag = true })
    t.start()
    PolyUtil.waitForCondition(flag, RetryPolicy(10, 10), 5.seconds)
  }

  it should "timeout if condition never becomes true" in {
    intercept[java.util.concurrent.TimeoutException] {
      PolyUtil.waitForCondition(false, RetryPolicy(5, 3), 100.milliseconds)
    }
  }

  it should "throw TimeoutException when retries exhausted" in {
    intercept[java.util.concurrent.TimeoutException] {
      PolyUtil.waitForCondition(false, RetryPolicy(5, 2))
    }
  }

  it should "respect deadline over retryLimit" in {
    intercept[java.util.concurrent.TimeoutException] {
      PolyUtil.waitForCondition(false, RetryPolicy(100, 100), 200.milliseconds)
    }
  }
}
