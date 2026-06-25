package org.polyconf

import org.polyconf.argfmt.{JsonVariableBase, CliArgGenerator, ParamsGeneratorBase, EscapeFormatShell, EscapeFormatYaml}
import org.polyconf.core.{PolyConf, PolyConfRegistry, PolySerde}
import org.polyconf.core.PolySerde._
class CliArgGeneratorSpec extends AccStoreFixture {

  "ArgFormat implementations" should "format args correctly" in {
    val args = Seq("--name", "test value", "--count", "3")

    new EscapeFormatYaml().format(args) should include("test value")
    new EscapeFormatShell().format(args) should include("'test value'")
  }

  "CliArgGenerator" should "format via named format" in {
    val args = Seq("--a", "1", "--b", "2")
    CliArgGenerator.formatArgs("yaml", args) should include("--a")
    CliArgGenerator.formatArgs("shell", args) should include("1")
  }

  it should "fail for unknown format" in {
    intercept[IllegalArgumentException] {
      CliArgGenerator.formatArgs("unknown", Seq.empty)
    }
  }

  it should "auto-discover registered format classes via PolyConfRegistry" in {
    PolyConfRegistry.resolveClass("EscapeFormatShell") shouldBe classOf[EscapeFormatShell]
    PolyConfRegistry.resolveClass("EscapeFormatYaml") shouldBe classOf[EscapeFormatYaml]
  }

  it should "support PolyConf deserialization of formats" in {
    PolyConfRegistry.registerBase(classOf[ArgFormatStub])

    val json  = """{"CN":"EscapeFormatShell"}"""
    val node  = PolyConf.deserialize[PolyConf](json)
    node shouldBe a[EscapeFormatShell]
  }

  it should "auto-generate allFormats from registered subclasses" in {
    CliArgGenerator.allFormats should contain key "shell"
    CliArgGenerator.allFormats should contain key "yaml"
  }

  it should "generate java execution line for fmtTest with ver env" in {
    val yamlPath = new java.io.File(getClass.getResource("/testArg.yaml").toURI).getAbsolutePath

    // Part 1: fmtTestAddParam via CliArgGenerator.main (complex additional params, no Renamer)
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      CliArgGenerator.main(Array(
        "--yamlPath", yamlPath,
        "--mapPath", "fmtTestAddParam",
        "--format", "shell"
      ))
    }
    val addParamOut = out.toString("UTF-8").trim
    addParamOut should include("run.local")
    addParamOut should include("-l")
    addParamOut should include("--verbose")
    addParamOut should include("BBB")
    addParamOut should include("xx")
    addParamOut should include("nested")

    // Part 2: fmtTest var replace verification (with Renamer)
    val fullMap = PolySerde.loadYamlResource("testArg.yaml")
    val jobMap  = getNestedValue[Map[String, Any]](fullMap, Seq("fmtTest"))

    jobMap should contain key "Renamer"
    val renamer    = PolyConf.deserialize[JsonVariableBase](
      PolySerde.jsonFormat(jobMap.get("Renamer")), verify = false
    )
    val formatter    = PolySerde.jsonFormat(jobMap.get("Formatter"))
    val formatterStr = renamer.rename(formatter, "Env--ver")
    val runFormat    = PolyConf.deserialize[ParamsGeneratorBase](formatterStr, verify = false)

    val args = runFormat.toArgs(true)
    args should contain("run.local")
    args should contain("-l")
    args should contain("WARN")
    args.exists(_.contains("RunnerShell")) shouldBe true
    args.exists(_.contains("ls /tmp/")) shouldBe true
  }
}

abstract class ArgFormatStub extends PolyConf
