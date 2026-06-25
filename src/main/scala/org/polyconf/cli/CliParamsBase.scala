package org.polyconf.cli

import org.apache.logging.log4j._
import org.polyconf.core.PolySerde
import org.polyconf.util.PolyLog
import org.polyconf.util.PolyLog.setLogConfig
import org.rogach.scallop.{ScallopConf, ScallopOption}
import org.rogach.scallop.exceptions.Help

import scala.util.Try

abstract class CliParamsBase(arguments: Seq[String]) extends ScallopConf(arguments) {

  lazy val Log: Logger = LogManager.getLogger(getClass)

  val logRules: ScallopOption[String] =
    opt[String](
      default = Some(defaultLogRules),
      short = 'l',
      name = "logRules",
      descr = f"Sets custom log rules. Ex: |$defaultLogRules|"
    )

  val helpFlag: ScallopOption[Boolean] =
    opt[Boolean](name = "help", short = 'h', descr = "Show detailed help")

  def commandDescription: String = ""

  def defaultLogRules: String = PolyLog.defaultLogRules

  override def getFullHelpString(): String =
    s"""
       |$commandDescription
       |Available arguments:
       |${super.getFullHelpString()}
       |Provided arguments:
       |${PolySerde.jsonFormat(arguments)}
       |""".stripMargin

  stdoutPrintln = (s: String) => Log.info(s)
  exitHandler = (exitCode: Int) =>
    throw new IllegalArgumentException(s"Scallop verification failure code $exitCode")

  override def onError(e: Throwable): Unit = e match {
    case _: Help =>
      Console.err.println(getFullHelpString())
      sys.exit(0)
    case _ => super.onError(e)
  }

  override def verify(): Unit = {
    setLogConfig()
    val verificationResult = Try(super.verify())
    logRules.foreach(r => Try(PolyLog.setLogRules(r)).recover { case e =>
      Log.warn(s"Failed to set log rule '$r': ${e.getMessage}")
    })
    Log.info(Try(summary).getOrElse(s"Failed generating summary. Args:\n${arguments.mkString("\n")}"))
    verificationResult.failed.foreach(e =>
      Log.error(s"Argument verification failed: ${e.getMessage}")
    )
    if (helpFlag.isSupplied || verificationResult.isFailure) {
      Log.info(getFullHelpString())
      if (helpFlag.isSupplied) sys.exit(0)
      verificationResult.failed.foreach(throw _)
    }
  }

  def mainExecutionLogic[T](f: => T): T =
    Try(f).fold(
      e => {
        Log.error(s"Failed job ${arguments.mkString(", ")}.\nException: ${e.getClass.getName} msg ${e.getMessage}")
        Log.info(s"$summary\n${getFullHelpString()}")
        throw e
      },
      identity
    )
}
