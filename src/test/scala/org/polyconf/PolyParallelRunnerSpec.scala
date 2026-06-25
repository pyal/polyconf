package org.polyconf

import org.polyconf.util.PolyParallelRunner
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class PolyParallelRunnerSpec extends AnyFlatSpec with Matchers {

  "PolyParallelRunner.parallelRun" should "process all inputs" in {
    val inputs   = (1 to 10).toList
    val results  = PolyParallelRunner.parallelRun(4, inputs, (x: Int) => x * 2).get
    results should contain theSameElementsAs (2 to 20 by 2)
  }

  it should "preserve order with parallelOrderedRun" in {
    val inputs  = (0 to 9).toList
    val results = PolyParallelRunner.parallelOrderedRun(4, inputs, (x: Int) => x * 2).get
    results shouldBe (0 to 9).map(_ * 2)
  }

  it should "fail on first error" in {
    val inputs = (1 to 5).toList
    val result = PolyParallelRunner.parallelRun(
      2,
      inputs, { (x: Int) =>
        if (x == 3) throw new RuntimeException("error on 3")
        x
      }
    )
    result.isFailure shouldBe true
  }

  "PolyParallelRunner.retryJob" should "retry on failure" in {
    var count = 0
    val result = PolyParallelRunner.retryJob(
      {
        count += 1
        if (count < 3) throw new RuntimeException(s"fail $count")
        "ok"
      },
      5,
      1.millisecond
    )
    result.get shouldBe "ok"
    count shouldBe 3
  }

  it should "fail after exhausting retries" in {
    var count = 0
    val result = PolyParallelRunner.retryJob(
      {
        count += 1
        throw new RuntimeException("always fails")
      },
      2,
      1.millisecond
    )
    result.isFailure shouldBe true
  }
}
