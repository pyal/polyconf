package org.polyconf.core

import com.fasterxml.jackson.annotation.{JsonProperty, PropertyAccessor}
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility
import com.fasterxml.jackson.core._
import com.fasterxml.jackson.core.json.JsonWriteFeature
import com.fasterxml.jackson.databind._
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import org.apache.logging.log4j._

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters._
import scala.reflect.ClassTag
import scala.util.Try
import java.util.ServiceLoader


object PolyConfRegistry {
  private val registeredClasses = new ConcurrentHashMap[String, Class[_ <: PolyConf]]()
  val registeredBaseClasses: java.util.Set[Class[_ <: PolyConf]] =
    ConcurrentHashMap.newKeySet[Class[_ <: PolyConf]]()

  def register(cl: Class[_ <: PolyConf]): Unit = {
    if (!registeredBaseClasses.contains(cl))
      registeredClasses.put(cl.getSimpleName, cl)
  }

  def registerBase(cl: Class[_ <: PolyConf]): Unit = {
    registeredBaseClasses.add(cl)
  }

  def resolveClass(className: String): Class[_] =
    Try(Class.forName(className)).getOrElse(
      Option(registeredClasses.get(className)).getOrElse(
        throw new RuntimeException(
          s"Unknown class: [$className]. Available registered classes: [${registeredClasses.keys().asScala.mkString(", ")}]"
        )
      )
    )

  /** Loads all PolyConfProvider implementations via JDK ServiceLoader (SPI).
    * Each provider registers its concrete classes and base classes.
    * Registers all base classes first, then concrete classes, so a class
    * that is both a base and a concrete subclass (e.g. CliSparkInit)
    * is correctly handled as a base with its subclasses discoverable.
    * Called automatically by CliArgGenerator.generate() and CliRun.doInternal(). */
  def registerAll(): Unit = {
    val providers = ServiceLoader.load(classOf[PolyConfProvider]).asScala.toSeq
    providers.foreach(_.getBaseClasses.forEach(registerBase))
    providers.foreach(_.getConcreteClasses.forEach(register))
  }

  def getRegisteredSubclasses(baseClass: Class[_ <: PolyConf]): Seq[Class[_ <: PolyConf]] =
    registeredClasses.values.asScala.filter(baseClass.isAssignableFrom).toSeq

  def typeArgOf(cls: Class[_], base: Class[_]): Option[String] = {
    def find(c: Class[_]): Option[String] = {
      c.getGenericInterfaces.flatMap {
        case pt: java.lang.reflect.ParameterizedType if pt.getRawType == base =>
          pt.getActualTypeArguments.headOption.map {
            case cl: Class[_] => cl.getSimpleName
            case t => t.getTypeName.stripPrefix("class ").stripPrefix("interface ")
          }
        case _ => None
      }.headOption.orElse(Option(c.getSuperclass).flatMap(find))
    }
    find(cls)
  }

  def getHelpByType(baseClasses: Class[_ <: PolyConf]*): String = {
    val allSubs = baseClasses.flatMap(getRegisteredSubclasses).distinct
    val types = allSubs.flatMap(c => typeArgOf(c, baseClasses.find(b => b.isAssignableFrom(c)).get)).distinct.sorted
    types.map { tpe =>
      val parts = baseClasses.flatMap { base =>
        val clses = getRegisteredSubclasses(base).filter(c => typeArgOf(c, base).contains(tpe))
        if (clses.isEmpty) None
        else {
          val objs = clses.map { sc =>
            val obj = PolyConf.deserialize[PolyConf](s"""{"CN":"${sc.getSimpleName}"}""", verify = false)
            s"  ${obj.getClass.getSimpleName}:\n" +
              s"    Serialized: ${PolyConf.serialize(obj)}\n" +
              s"    Help:\n      ${obj.help.replaceAll("\n", "\n      ")}"
          }.mkString("\n")
          Some(s"  ${base.getSimpleName}:\n$objs")
        }
      }.mkString("\n")
      s"$tpe:\n$parts"
    }.mkString("\n\n")
  }

  def getHelpBase(baseClass: Class[_ <: PolyConf]): String = {
    val baseName   = baseClass.getSimpleName
    val subclasses = registeredClasses.values.asScala.filter(baseClass.isAssignableFrom)
    // Note: instantiates each subclass (constructors run, side effects may occur)
    val objects = subclasses.map { sc =>
      PolyConf.deserialize[PolyConf](s"""{"CN":"${sc.getSimpleName}"}""", verify = false)
    }

    val body = objects.map { obj =>
      s"  ${obj.getClass.getSimpleName}:\n" +
        s"    Serialized: ${PolyConf.serialize(obj)}\n" +
        s"    Help:\n      ${obj.help.replaceAll("\n", "\n      ")}"
    }.mkString("\n\n")

    s"Help for Base Class: [$baseName]\n$body"
  }

  def getHelpClass(cl: Class[_ <: PolyConf]): String = {
    val obj = PolyConf.deserialize[PolyConf](s"""{"CN":"${cl.getSimpleName}"}""", verify = false)
    s"${cl.getSimpleName}:\n  ${obj.help.replaceAll("\n", "\n  ")}"
  }
}

/** Base trait for polymorphic JSON-serializable configuration using Jackson.
  *
  * == Deserialization sets fields directly ==
  * PolyConf uses Jackson with `FIELD, ANY` visibility.  During deserialization
  * it sets '''every''' field it finds in the JSON — including `private val`
  * fields and constructor parameters — directly via reflection.  You do not
  * need setters or `var` declarations.
  *
  * == Hiding fields from serialization ==
  * To exclude a field from JSON output, annotate it with `@JsonIgnore`:
  * {{{
  * class MyConfig extends PolyConf {
  *   @JsonIgnore
  *   private val internal: String = ""
  * }
  * }}}
  * The value will still be set during deserialization (if present in JSON) but
  * will '''not''' appear when you call `PolyConf.serialize(...)`.
  *
  * == getXxx method caveat ==
  * Jackson treats any public `def getXxx(): T` as a JSON property `"xxx"` — it
  * appears automatically in serialized output even when there is no backing
  * field.  However, the mapper does not know how to '''set''' that property
  * during deserialization (no field, no setter), so providing the same property
  * in input JSON raises `UnrecognizedPropertyException`.  This creates an
  * asymmetry: what serializes successfully may not round-trip if fed back as-is.
  * Use `@JsonIgnore` on the getter to suppress it from serialization.
  */
trait PolyConf {

  @JsonProperty("CN")
  val className: String = this.getClass.getSimpleName

  def help: String   = "No help"
  def verify(): Unit = {}
}

object PolyConf {

  // self-contained mapper to avoid circular dependency with PolySerde
  @transient
  lazy val jsonBasicMapper: ObjectMapper = buildJsonMapper()

  def buildJsonMapper(): ObjectMapper = {
    val mapper =
      new ObjectMapper()
        .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
        .configure(SerializationFeature.WRITE_SELF_REFERENCES_AS_NULL, true)
        .configure(SerializationFeature.FAIL_ON_SELF_REFERENCES, false)
        .configure(JsonWriteFeature.QUOTE_FIELD_NAMES.mappedFeature(), true)
        .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
        .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
        .setVisibility(PropertyAccessor.FIELD, Visibility.ANY)
        .registerModule(DefaultScalaModule)

    mapper.registerModule(new JavaTimeModule())
    mapper
  }

  private lazy val Log: Logger = LogManager.getLogger(getClass)

  def serialize(obj: Any, prettyFormat: Boolean = false): String =
    if (prettyFormat)
      jsonBasicMapper.writerWithDefaultPrettyPrinter.writeValueAsString(obj)
    else
      jsonBasicMapper.writeValueAsString(obj)

  def deserialize[T <: PolyConf](jsonString: String, verify: Boolean = true): T = {
    val res = addDeserializer(jsonBasicMapper).readValue(jsonString, classOf[PolyConf])
    if (verify) res.verify()
    res.asInstanceOf[T]
  }

  def deserializeAny[T](jsonString: String)(implicit ct: ClassTag[T]): T =
    addDeserializer(jsonBasicMapper).readValue(jsonString, ct.runtimeClass.asInstanceOf[Class[T]])

  // Not cached: each call creates a fresh copy to avoid conflicts with nested/recursive deserialization
  private def addDeserializer(mapper: ObjectMapper): ObjectMapper = {
    val module = new SimpleModule()
    (PolyConfRegistry.registeredBaseClasses.asScala.toSeq :+ classOf[PolyConf])
      .foreach(cl => module.addDeserializer(cl, new PolyConfDeserializer()(ClassTag(cl))))
    mapper.copy().registerModule(module)
  }

  private class PolyConfDeserializer[T <: PolyConf](implicit ct: ClassTag[T])
      extends JsonDeserializer[T] {

    override def deserialize(p: JsonParser, ctxt: DeserializationContext): T = {
      val tree: JsonNode = p.getCodec.readTree(p)
      if (!tree.isObject)
        throw new RuntimeException(
          s"PolyConf requires a JSON object with a 'CN' field, but got: ${tree.getNodeType}"
        )
      val objNode = tree.asInstanceOf[ObjectNode]
      if (!objNode.has("CN"))
        throw new RuntimeException(
          s"Missing required field 'CN' in JSON for PolyConf deserialization. " +
            s"Available fields: ${objNode.fieldNames().asScala.mkString(", ")}"
        )
      val shortClassName = objNode.get("CN").asText
      val resultClass    = PolyConfRegistry.resolveClass(shortClassName)
      val serializerName = ct.runtimeClass.getSimpleName

      if (serializerName.equalsIgnoreCase(shortClassName))
        throw new RuntimeException(
          s"Deserializer for $serializerName cannot deserialize itself. " +
            s"Custom deserializers can only be defined for abstract base classes"
        )

      p.getCodec.treeToValue(tree, resultClass).asInstanceOf[T]
    }
  }
}
