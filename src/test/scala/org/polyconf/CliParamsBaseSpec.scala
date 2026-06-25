package org.polyconf

import org.polyconf.cli.CliParamsBase
import org.polyconf.core.PolyConfRegistry
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.rogach.scallop.ScallopOption

class CliParamsBaseSpec extends AnyFlatSpec with Matchers {

  "CliBase" should "parse arguments" in {
    val cli = new TestCliParams(Seq("--name", "test", "--count", "5"))
    cli.name() shouldBe "test"
    cli.count() shouldBe 5
  }

  it should "use default values" in {
    val cli = new TestCliParams(Seq.empty)
    cli.name() shouldBe "default"
    cli.count() shouldBe 1
  }

  it should "throw on bad arguments" in {
    intercept[IllegalArgumentException] {
      new TestCliParams(Seq("--unknown"))
    }
  }

  it should "provide command description" in {
    val cli = new TestCliParams(Seq.empty)
    cli.commandDescription should include("TestCli")
  }
}

class TestCliParams(arguments: Seq[String]) extends CliParamsBase(arguments) {
  val name: ScallopOption[String] =
    opt[String](default = Some("default"), name = "name", descr = "Name parameter")
  val count: ScallopOption[Int] =
    opt[Int](default = Some(1), name = "count", descr = "Count parameter")

  override def defaultLogRules: String = "WARN"

  override def commandDescription: String = s"TestCli - testing utility"

  verify()
}
