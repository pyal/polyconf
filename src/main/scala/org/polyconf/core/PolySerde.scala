package org.polyconf.core

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind._
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import PolyConf.jsonBasicMapper
import org.polyconf.util.{PolyLog, PolyUtil}
import org.yaml.snakeyaml.Yaml

import java.io._
import java.util.Base64
import scala.io.Source
import scala.jdk.CollectionConverters._
import scala.reflect.ClassTag
import scala.util.{Failure, Try}
import scala.util.control.NonFatal

object PolySerde {

  private val PrimitiveClasses: Set[Class[_]] =
    Set(
      classOf[Int], classOf[Long], classOf[Double], classOf[Boolean], classOf[Char], classOf[String]
    )

  // def (not lazy val) — each call creates a fresh mapper + module so serialized-object state
  // (e.g. the visited set in RecursiveJsonSerializer) is discarded after each top-level call
  private def jsonRecursiveMapper: ObjectMapper =
    PolyConf.buildJsonMapper()
      .registerModule(new SimpleModule().setSerializerModifier(new JsonSerializationModifier))

  private class JsonSerializationModifier extends BeanSerializerModifier {
    override def modifySerializer(
        config: SerializationConfig,
        beanDesc: BeanDescription,
        serializer: JsonSerializer[_]
    ): JsonSerializer[_] =
      if (PrimitiveClasses(beanDesc.getBeanClass))
        serializer
      else {
        val ser = serializer.asInstanceOf[JsonSerializer[AnyRef]]
        new RecursiveJsonSerializer(ser)
      }
  }

  private class RecursiveJsonSerializer(defaultSerializer: JsonSerializer[AnyRef])
      extends StdSerializer[AnyRef](classOf[AnyRef]) {
    private val visited = new ThreadLocal[java.util.Set[AnyRef]] {
      override def initialValue = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap[AnyRef, java.lang.Boolean])
    }

    override def serialize(value: AnyRef, gen: JsonGenerator, provider: SerializerProvider): Unit = {
      val s = visited.get()
      if (!s.add(value))
        gen.writeString(value.toString)
      else {
        Try(defaultSerializer.serialize(value, gen, provider)).recover {
          case e if !NonFatal(e) => throw e
          case e =>
            s.remove(value)
            PolyLog.Log.warn(s"Failed to serialize value: {}", value, e)
            gen.writeNull()
        }
      }
    }
  }

  def javaSerialize[T <: Serializable](obj: T): String = {
    val baos = new ByteArrayOutputStream()
    PolyUtil.withResource(new ObjectOutputStream(baos)) { oos =>
      oos.writeObject(obj)
    }.get
    Base64.getEncoder.encodeToString(baos.toByteArray)
  }

  def javaDeserialize[T <: Serializable](str: String): Try[T] =
    Try {
      val bytes = Base64.getDecoder.decode(str)
      val bais  = new ByteArrayInputStream(bytes)
      PolyUtil.withResource(new ObjectInputStream(bais)) { ois =>
        ois.readObject().asInstanceOf[T]
      }.get
    }

  def jsonFormat(obj: Any, allowRecursion: Boolean = false, prettyFormat: Boolean = false): String = {
    val mapper =
      if (allowRecursion) jsonRecursiveMapper else jsonBasicMapper
    if (prettyFormat)
      mapper.writerWithDefaultPrettyPrinter.writeValueAsString(obj)
    else
      mapper.writeValueAsString(obj)
  }

  def jsonToMap(jsonStr: String): Map[String, Any] =
    jsonBasicMapper.readValue(jsonStr, classOf[Map[String, Any]])

  def getNestedValue[T](data: Map[String, Any], path: Seq[String], default: Option[T] = None)(implicit ct: ClassTag[T]): T =
    getNestedValueTry(data, path).fold(
      e =>
        default.getOrElse(
          throw new RuntimeException(
            s"Path ${path.mkString(", ")} not found. Given keys: ${data.keys.mkString(", ")} map:\n${jsonFormat(data, prettyFormat = true)}",
            e
          )
        ),
      (x: T) => x
    )

  def getNestedValueTry[T](data: Map[String, Any], path: Seq[String])(implicit ct: ClassTag[T]): Try[T] = {
    if (path.isEmpty) return Failure(new IllegalArgumentException("Path must not be empty"))
    val initialKeys = path.init
    val finalKey    = path.last
    Try {
      val finalMap = initialKeys.foldLeft(data) { (m, p) =>
        m.getOrElse(p, throw new RuntimeException(
          s"Path '${path.mkString(".")}': key '$p' not found"
        )) match {
          case child: Map[?, ?] => child.asInstanceOf[Map[String @unchecked, Any]]
          case other => throw new RuntimeException(
            s"Path '${path.mkString(".")}': key '$p' is ${other.getClass.getName}, expected Map"
          )
        }
      }
      val raw = finalMap.getOrElse(finalKey, throw new RuntimeException(
        s"Path '${path.mkString(".")}': key '$finalKey' not found at final level"
      ))
      ct match {
        case ClassTag.Int => raw.asInstanceOf[Int].asInstanceOf[T]
        case ClassTag.Long => raw.asInstanceOf[Long].asInstanceOf[T]
        case ClassTag.Double => raw.asInstanceOf[Double].asInstanceOf[T]
        case ClassTag.Float => raw.asInstanceOf[Float].asInstanceOf[T]
        case ClassTag.Boolean => raw.asInstanceOf[Boolean].asInstanceOf[T]
        case _ => ct.runtimeClass.cast(raw).asInstanceOf[T]
      }
    }
  }

  def loadYamlString(yamlString: String): Map[String, Any] =
    javaMapToScala(new Yaml().loadAs(yamlString, classOf[java.util.Map[String, Any]])).asInstanceOf[Map[String, Any]]

  def loadYamlResource(resourcePath: String): Map[String, Any] =
    loadYamlString(PolyUtil.withResource(Source.fromResource(resourcePath))(_.mkString).get)

  private def javaMapToScala(obj: Any): Any = obj match {
    case m: java.util.Map[_, _] =>
      m.asScala.map { case (k, v) => k.toString -> javaMapToScala(v) }.toMap
    case l: java.util.List[_] =>
      l.asScala.map(javaMapToScala).toSeq
    case other => other
  }

  def any2ConfNode[T](data: Any)(implicit ct: ClassTag[T]): T =
    PolyConf.deserializeAny[T](PolyConf.serialize(data))
}
