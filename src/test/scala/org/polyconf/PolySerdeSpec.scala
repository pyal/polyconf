package org.polyconf

import org.polyconf.argfmt.JsonVariableInline
import org.polyconf.core.{PolyConf, PolySerde}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PolySerdeSpec extends AnyFlatSpec with Matchers {

  "PolySerde" should "serialize objects to JSON" in {
    val obj  = Map("a" -> 1, "b" -> "hello")
    val json = PolySerde.jsonFormat(obj)
    json should include(""""a":1""")
    json should include(""""b":"""")
    json should include("hello")
  }

  it should "pretty-print JSON" in {
    val obj    = Map("x" -> 1)
    val pretty = PolySerde.jsonFormat(obj, prettyFormat = true)
    pretty should include("\n")
  }

  it should "convert JSON string to Map" in {
    val map = PolySerde.jsonToMap("""{"a":1,"b":"x"}""")
    map("a") shouldBe 1
    map("b") shouldBe "x"
  }

  it should "get nested values" in {
    val data = Map("a" -> Map("b" -> Map("c" -> 42)))
    PolySerde.getNestedValue[Int](data, Seq("a", "b", "c")) shouldBe 42
  }

  it should "fail for missing nested path" in {
    val data = Map("a" -> 1)
    intercept[RuntimeException] {
      PolySerde.getNestedValue[Int](data, Seq("a", "b"))
    }
  }

  it should "return default for missing nested path" in {
    val data = Map("a" -> 1)
    PolySerde.getNestedValue[Int](data, Seq("a", "b"), Some(99)) shouldBe 99
  }

  it should "handle Try version of nested value" in {
    val data = Map("a" -> Map("b" -> 5))
    PolySerde.getNestedValueTry[Int](data, Seq("a", "b")).isSuccess shouldBe true
    PolySerde.getNestedValueTry[Int](data, Seq("a", "x")).isSuccess shouldBe false
  }

  it should "load YAML from string" in {
    val yaml = "a: 1\nb: hello\n"
    val map  = PolySerde.loadYamlString(yaml)
    map("a") shouldBe 1
    map("b") shouldBe "hello"
  }

  it should "handle Java serialization roundtrip" in {
    val original = "test-string"
    val encoded  = PolySerde.javaSerialize(original)
    val decoded  = PolySerde.javaDeserialize[String](encoded).get
    decoded shouldBe original
  }

  "Jackson field visibility" should "set private val via FIELD access" in {
    val json = """{"name":"overridden","count":42}"""
    val obj  = PolyConf.jsonBasicMapper.readValue(json, classOf[PrivateValBean])
    obj.getName shouldBe "overridden"
    obj.getCount shouldBe 42
  }

  it should "retain defaults for fields not in JSON" in {
    val obj = PolyConf.jsonBasicMapper.readValue("""{"name":"test"}""", classOf[PrivateValBean])
    obj.getName shouldBe "test"
    obj.getCount shouldBe 99
  }

  it should "set private val cmd in RunnerShell via PolyConf" in {
    org.polyconf.core.PolyConfRegistry.register(classOf[PrivateCmdBean])
    val json = """{"CN":"PrivateCmdBean","cmd":"echo hello"}"""
    val obj  = org.polyconf.core.PolyConf.deserialize[org.polyconf.core.PolyConf](json)
    obj.asInstanceOf[PrivateCmdBean].getCmd shouldBe "echo hello"
  }

  "JsonVariableInline.replaceVars" should "replace variables (single, multiple, overlapping, custom prefix)" in {
    JsonVariableInline.replaceVars("""{"host":"$host"}""", Map("host" -> "example.com")) shouldBe """{"host":"example.com"}"""
    JsonVariableInline.replaceVars("$a $b", Map("a" -> "x", "b" -> "y")) shouldBe "x y"
    JsonVariableInline.replaceVars("$HelloWorld", Map("HelloWorld" -> "foo", "Hello" -> "bar")) shouldBe "foo"
    JsonVariableInline.replaceVars("%host", Map("host" -> "value"), variablePrefix = "%") shouldBe "value"
  }
}

class PrivateValBean {
  private val name: String = "default"
  private val count: Int = 99
  def getName: String = name
  def getCount: Int = count
}

class PrivateCmdBean extends org.polyconf.core.PolyConf {
  private val cmd: String = "fallback"
  def getCmd: String = cmd
}
