package org.polyconf

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import org.polyconf.core.{PolyConf, PolyConfRegistry, PolySerde}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PolyConfSpec extends AnyFlatSpec with Matchers {

  Seq(
    classOf[TestTransform],
    classOf[AnotherTransform],
    classOf[SimpleNode],
    classOf[ParentNode],
    classOf[MultiNode],
    classOf[CustomConstructorNode],
    classOf[GetMethodNode],
    classOf[TestData],
    classOf[VerifyTransform]
  ).foreach(PolyConfRegistry.register)
  Seq(
    classOf[TransformBase],
    classOf[NodeBase]
  ).foreach(PolyConfRegistry.registerBase)

  "PolyConfRegistry" should "register and resolve concrete classes" in {
    val cls = PolyConfRegistry.resolveClass("TestTransform")
    cls shouldBe classOf[TestTransform]
  }

  it should "silently skip registering a base class as concrete" in {
    // Base classes are idempotent — no exception thrown
    PolyConfRegistry.register(classOf[TransformBase])
  }

  it should "silently allow registering a concrete class as base" in {
    // Concrete classes can also be bases — no exception thrown
    PolyConfRegistry.registerBase(classOf[TestTransform])
    // Clean up to avoid affecting subsequent deserialization tests
    PolyConfRegistry.registeredBaseClasses.remove(classOf[TestTransform])
  }

  "PolyConf" should "serialize and deserialize polymorphic classes" in {
    val json  = """{"CN":"TestTransform","factor":2,"suffix":"_out"}"""
    val node  = PolyConf.deserialize[TransformBase](json)
    val round = PolyConf.serialize(node)

    node shouldBe a[TestTransform]
    round should include(""""CN":"TestTransform"""")
    round should include(""""factor":2""")
    round should include(""""suffix":"_out"""")
  }

  it should "fail for unknown class" in {
    intercept[Exception] {
      PolyConf.deserialize[PolyConf]("""{"CN":"NonExistent"}""")
    }
  }

  it should "generate help for base classes" in {
    val help = PolyConfRegistry.getHelpBase(classOf[TransformBase])
    help should include("TestTransform")
    help should include("help text for TestTransform")
  }

  it should "generate help for concrete classes" in {
    val help = PolyConfRegistry.getHelpClass(classOf[TestTransform])
    help should include("TestTransform")
    help should include("help text for TestTransform")
  }

  it should "call verify during deserialization" in {
    val valid = PolyConf.deserialize[PolyConf]("""{"CN":"VerifyTransform","valid":true}""")
    valid shouldBe a[VerifyTransform]

    intercept[RuntimeException] {
      PolyConf.deserialize[PolyConf]("""{"CN":"VerifyTransform","valid":false}""")
    }
  }

  "Round-trip serialization" should "produce consistent JSON" in {
    val clStr = "{CN:'TestTransform',factor:2,suffix:'_out'}"
    val node = PolyConf.deserialize[TransformBase](clStr)
    val clStrRestore = PolyConf.serialize(node)
    val nodeRestore: TransformBase = PolyConf.deserialize[TransformBase](clStrRestore)
    clStrRestore shouldEqual PolyConf.serialize(nodeRestore)
  }

  "getXxx asymmetry" should "spontaneously appear in serialized JSON and break round-trip" in {
    // Jackson exposes every public def getXxx() as a property "xxx".
    // GetMethodNode has no field "computed" — only def getComputed.
    // Yet "computed" appears in the output, but feeding it back throws.
    val node = PolyConf.deserialize[NodeBase]("""{"CN":"GetMethodNode","name":"world"}""")
    PolyConf.serialize(node) should include(""""computed":"computed-world"""")

    // Trying to set "computed" in input throws (no field, no setter)
    intercept[UnrecognizedPropertyException] {
      PolyConf.deserialize[NodeBase]("""{"CN":"GetMethodNode","name":"x","computed":"y"}""")
    }

    // Round-trip: serialize → deserialize that output fails for same reason
    val json = PolyConf.serialize(
      PolyConf.deserialize[NodeBase]("""{"CN":"GetMethodNode","name":"x"}""")
    )
    json should include(""""computed"""")
    intercept[UnrecognizedPropertyException] {
      PolyConf.deserialize[NodeBase](json)
    }
  }

  it should "never appear in raw field list (proving synthetic origin)" in {
    val node = new GetMethodNode()
    node.getClass.getDeclaredFields.map(_.getName) should not contain "computed"
  }

  it should "handle private val fields defined in constructor" in {
    val json = """{"CN":"TestData","name":"fromJson","value":7}"""
    val node = PolyConf.deserialize[NodeBase](json)
    val serialized = PolyConf.serialize(node)
    serialized should include(""""CN":"TestData"""")
    serialized should include(""""name":"fromJson"""")
    serialized should include(""""value":7""")
  }

  "Nested PolyConf classes" should "serialize and deserialize Option[NodeBase] fields" in {
    val json = """{"CN":"ParentNode","child":{"CN":"SimpleNode","name":"child1","value":5},"label":"parent1"}"""
    val node = PolyConf.deserialize[NodeBase](json)
    val round = PolyConf.serialize(node)
    round should include(""""CN":"ParentNode"""")
    round should include(""""CN":"SimpleNode"""")
    round should include(""""name":"child1"""")
    round should include(""""value":5""")
    round should include(""""label":"parent1"""")
  }

  it should "serialize and deserialize None Option fields" in {
    val json = """{"CN":"ParentNode","child":null,"label":"nochild"}"""
    val node = PolyConf.deserialize[NodeBase](json)
    val round = PolyConf.serialize(node)
    round should include(""""CN":"ParentNode"""")
    round should include(""""label":"nochild"""")
  }

  it should "serialize and deserialize Seq[NodeBase] fields" in {
    val json = """{"CN":"MultiNode","children":[{"CN":"SimpleNode","name":"c1","value":1},{"CN":"SimpleNode","name":"c2","value":2}],"label":"multi1"}"""
    val node = PolyConf.deserialize[NodeBase](json)
    val round = PolyConf.serialize(node)
    round should include(""""CN":"MultiNode"""")
    round should include(""""CN":"SimpleNode"""")
    round should include(""""name":"c1"""")
    round should include(""""name":"c2"""")
    round should include(""""value":1""")
    round should include(""""value":2""")
    round should include(""""label":"multi1"""")
  }

  it should "serialize and deserialize empty Seq fields" in {
    val json = """{"CN":"MultiNode","children":[],"label":"empty"}"""
    val node = PolyConf.deserialize[NodeBase](json)
    val round = PolyConf.serialize(node)
    round should include(""""CN":"MultiNode"""")
    round should include(""""children":[]""")
    round should include(""""label":"empty"""")
  }

  "Private val fields" should "deserialize via PolyConf" in {
    val json = """{"CN":"CustomConstructorNode","value":42,"label":"test-label"}"""
    val node = PolyConf.deserialize[NodeBase](json)
    val round = PolyConf.serialize(node)
    round should include(""""CN":"CustomConstructorNode"""")
    round should include(""""value":42""")
    round should include(""""label":"test-label"""")
  }

  "Nested PolyConf" should "load and deserialize from YAML" in {
    val yaml =
      """obj1:
        |  CN: SimpleNode
        |  name: yamlNode
        |  value: 99
        |obj2:
        |  CN: ParentNode
        |  child:
        |    CN: SimpleNode
        |    name: yamlChild
        |    value: 7
        |  label: yamlParent""".stripMargin
    val config = PolySerde.loadYamlString(yaml)
    val obj1 = PolyConf.deserialize[NodeBase](PolySerde.jsonFormat(config("obj1")))
    val s1 = PolyConf.serialize(obj1)
    s1 should include(""""name":"yamlNode"""")
    s1 should include(""""value":99""")

    val obj2 = PolyConf.deserialize[NodeBase](PolySerde.jsonFormat(config("obj2")))
    val s2 = PolyConf.serialize(obj2)
    s2 should include(""""CN":"ParentNode"""")
    s2 should include(""""label":"yamlParent"""")
    s2 should include(""""CN":"SimpleNode"""")
    s2 should include(""""name":"yamlChild"""")
  }
}

class TestTransform extends TransformBase {
  val factor: Int = 1
  val suffix: String = ""
  override def help: String = "help text for TestTransform"
}

class AnotherTransform extends TransformBase {
  val value: String = ""
}

class VerifyTransform extends TransformBase {
  val valid: Boolean = false
  override def verify(): Unit =
    if (!valid) throw new RuntimeException("Not valid")
}

abstract class TransformBase extends PolyConf

abstract class NodeBase extends PolyConf

class SimpleNode extends NodeBase {
  private val name: String = ""
  private val value: Int = 0
  override def help: String = s"SimpleNode: name=$name, value=$value"
}

class ParentNode extends NodeBase {
  private val child: Option[NodeBase] = None
  private val label: String = ""
  override def help: String = s"ParentNode: label=$label"
}

class MultiNode extends NodeBase {
  private val children: Seq[NodeBase] = Seq.empty
  private val label: String = ""
  override def help: String = s"MultiNode: label=$label, children=${children.size}"
}

class CustomConstructorNode extends NodeBase {
  private val value: Int = 0
  private val label: String = ""
}

// Jackson treats def getXxx() as property "xxx" in JSON output,
// but cannot set it during deserialization (no backing field/setter).
// This creates an asymmetry: "computed" serializes but won't round-trip.
class GetMethodNode extends NodeBase {
  private val name: String = ""
  def getComputed: String = s"computed-$name"
}

final case class TestData(name: String = "", value: Int = 0) extends NodeBase

class OptionPolyConfSpec extends AnyFlatSpec with Matchers {
  import org.polyconf.cli.stream._
  import org.polyconf.cli.run.RunnerBase

  Seq(
    classOf[SuccessRecorder],
    classOf[SimpleDataGenerator],
    classOf[TransformerJob[StreamData]]
  ).foreach(PolyConfRegistry.register)
  Seq(
    classOf[SuccessRecorderBase],
    classOf[StreamGenerator[_]],
    classOf[StreamTransformer[_]],
    classOf[StreamWriter[_]],
    classOf[RunnerBase]
  ).foreach(PolyConfRegistry.registerBase)

  "Option[SuccessRecorderBase]" should "round-trip Some" in {
    val sr = new SuccessRecorder("/tmp/opt_test.log")
    val job = TransformerJob[StreamData](
      generator = new SimpleDataGenerator(),
      successRecorderOpt = Some(sr)
    )
    val json = PolyConf.serialize(job)
    json should include(""""CN":"TransformerJob"""")
    json should include(""""CN":"SuccessRecorder"""")
    json should include(""""path":"/tmp/opt_test.log"""")

    val deser = PolyConf.deserialize[TransformerJob[StreamData]](json)
    deser.successRecorderOpt.isDefined shouldBe true
    deser.successRecorderOpt.get.asInstanceOf[SuccessRecorder].path shouldBe "/tmp/opt_test.log"
  }

  it should "round-trip None" in {
    val job = TransformerJob[StreamData](
      generator = new SimpleDataGenerator(),
      successRecorderOpt = None
    )
    val json = PolyConf.serialize(job)
    json should include(""""CN":"TransformerJob"""")

    val deser = PolyConf.deserialize[TransformerJob[StreamData]](json)
    deser.successRecorderOpt.isDefined shouldBe false
  }
}
