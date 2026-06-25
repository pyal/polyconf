package org.polyconf.argfmt

import org.polyconf.cli.CliParamsBase
import org.polyconf.core.{PolyConf, PolyConfRegistry, PolySerde}
import org.polyconf.core.PolySerde._
import org.polyconf.util.PolyUtil
import org.rogach.scallop.ScallopOption

import scala.io.Source
import scala.sys.process._
import scala.util.Try

class CliArgGenerator {
  class CliArgGeneratorParams(arguments: Seq[String]) extends CliParamsBase(arguments) {
    val yamlPath: ScallopOption[String] =
      opt[String](required = false, name = "yamlPath", descr = "YAML file path (append ::mapPath to select a top-level key, e.g. file.yaml::myJob)")
    val resourcePath: ScallopOption[String] =
      opt[String](required = false, name = "resourcePath", descr = "Classpath resource path (append ::mapPath to select a key, e.g. app.yaml::myJob)")
    val format: ScallopOption[String] =
      opt[String](default = Some("shell"), name = "format", descr = s"Output format: ${formatSeq.mkString(", ")} (default: shell)")
    val mapPath: ScallopOption[String] =
      opt[String](required = false, name = "mapPath", descr = "Map path override (takes precedence over :: suffix in yamlPath/resourcePath)")
    val renameString: ScallopOption[String] =
      opt[String](default = Some(""), name = "renameStr", descr = "Rename literal strings: key1--val1~~key2--val2")
    val fullArgs: ScallopOption[Boolean] =
      opt[Boolean](default = Some(false), name = "fullArgs", descr = "Show full list of args")
    val execute: ScallopOption[Boolean] =
      opt[Boolean](default = Some(false), name = "execute", descr = "Execute generated command instead of printing (forces --format shell)")

    override def commandDescription: String = {
      val formatsHelp = allFormats.map { case (name, fmt) =>
        s"  $name  - ${fmt.help}"
      }.mkString("\n")
      s"""
         |CliArgGenerator - formats job params from YAML description to various output formats.
         |
         |Classes are auto-discovered via SPI (ServiceLoader) + classpath scanning.
         |Child libraries add their own runners/generators/transformers by creating a
         |PolyConfProvider subclass that calls registerAllChildForBases(...) and
         |listing it in META-INF/services/org.polyconf.core.PolyConfProvider.
         |Concrete classes in the same module are found automatically.
         |
         |Use ::mapPath suffix on yamlPath/resourcePath to select a top-level YAML key:
         |  --yamlPath path/to/file.yaml::myJob
         |
         |Defaults: --format shell, -l WARN.
         |
         |Registered formats:
         |$formatsHelp
         |""".stripMargin
    }

    override def defaultLogRules: String = "WARN"

    verify()
  }

  def allFormats: Map[String, EscapeFormatBase] =
    PolyConfRegistry.getRegisteredSubclasses(classOf[EscapeFormatBase])
      .map { cl =>
        val inst = cl.getDeclaredConstructor().newInstance().asInstanceOf[EscapeFormatBase]
        inst.formatName -> inst
      }
      .toMap

  def formatSeq: Seq[String] = allFormats.keys.toSeq :+ "all"

  def formatArgs(formatName: String, args: Seq[String]): String =
    allFormats.get(formatName) match {
      case Some(fmt) => fmt.format(args)
      case None =>
        throw new IllegalArgumentException(
          s"Unknown format: $formatName. Available: ${allFormats.keys.mkString(", ")}"
        )
    }

  def formatAll(args: Seq[String]): String =
    allFormats.map { case (name, _) =>
      s"====== $name ========\n" + formatArgs(name, args)
    }.mkString("\n")

  private def splitMapPath(s: String): (String, String) =
    s.split("::", 2) match {
      case Array(p, m) => (p, m)
      case _           => (s, "")
    }

  def generate(args: Array[String]): Unit = {
    PolyConfRegistry.registerAll()
    val params = new CliArgGeneratorParams(args.toSeq)
    params.mainExecutionLogic {
      val (yamlPathActual, mapPathFromYaml) = params.yamlPath.toOption.map(splitMapPath)
        .getOrElse((null, ""))
      val (resPathActual, mapPathFromRes) = params.resourcePath.toOption.map(splitMapPath)
        .getOrElse((null, ""))

      val yamlStr = (Option(yamlPathActual), Option(resPathActual)) match {
        case (Some(path), _) => PolyUtil.withResource(Source.fromFile(path))(_.mkString).get
        case (_, Some(res))  => PolyUtil.withResource(Source.fromResource(res))(_.mkString).get
        case (None, None)    => sys.error("Either --yamlPath or --resourcePath is required")
      }
      val fullMap = PolySerde.loadYamlString(yamlStr)
      val effectiveMapPath = params.mapPath.toOption.filter(_.nonEmpty)
        .orElse(Some(mapPathFromYaml).filter(_.nonEmpty))
        .orElse(Some(mapPathFromRes).filter(_.nonEmpty))
        .getOrElse(sys.error(s"--mapPath is required (via arg or :: suffix). Available top-level keys: ${fullMap.keys.mkString(", ")}"))
      val mapParts = effectiveMapPath.split("\\.").toSeq.filter(_.nonEmpty)
      val jobMap = if (mapParts.isEmpty)
        sys.error(s"--mapPath is required. Available top-level keys: ${fullMap.keys.mkString(", ")}")
      else
        getNestedValue[Map[String, Any]](fullMap, mapParts)

      val renamer: JsonVariableBase = jobMap.get("Renamer").map { obj =>
        PolyConf.deserialize[JsonVariableBase](jsonFormat(Some(obj)), verify = false)
      }.getOrElse(JsonVariableInline(variablePrefix = ""))
      val formatter    = jsonFormat(jobMap.get("Formatter"))
      val formatterStr = renamer.rename(formatter, params.renameString.getOrElse(""))
      val runFormat    = PolyConf.deserialize[ParamsGeneratorBase](formatterStr, verify = false)

      val result = params.format() match {
        case "all" if !params.execute() => formatAll(runFormat.toArgs(params.fullArgs.getOrElse(false)))
        case f     => formatArgs(if (params.execute()) "shell" else f, runFormat.toArgs(params.fullArgs.getOrElse(false)))
      }

      if (params.execute()) {
        val exitCode = result.!
        if (exitCode != 0) sys.exit(exitCode)
      } else {
        println(result)
      }
    }
  }
}

object CliArgGenerator extends CliArgGenerator {
  def main(args: Array[String]): Unit = generate(args)
}
