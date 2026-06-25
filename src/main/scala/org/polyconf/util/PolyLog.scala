package org.polyconf.util

import org.apache.logging.log4j._
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.config._
import org.apache.logging.log4j.util.Supplier

import scala.jdk.CollectionConverters._

object PolyLog {
  val defaultLogRules    = "WARN,DEBUG:org.polyconf"
  var currentLogRules  = defaultLogRules
  lazy val Log: Logger = LogManager.getLogger(getClass)

  def setLogConfig(): Unit = {
    val ctx    = getContext
    val config = ctx.getConfiguration
    config.getRootLogger.setLevel(Level.WARN)
    ctx.updateLoggers()
  }

  def logSupplier[T](function: => T): Supplier[T] = () => function

  def setLogRules(logRules: String): Unit = {
    setLogRules(logRules, Seq.empty)
    currentLogRules = logRules
  }

  private[polyconf] def setLogRules(logRules: String, leaveLogs: Seq[String] = Seq.empty): Unit = {
    val ctx    = getContext
    val config = ctx.getConfiguration
    val rulesList = logRules.split(",").map(_.split(":").toSeq)

    // Clear first so rules are the only source of truth — without this, stale
    // logger configs from framework init (Spark, etc.) survive and produce
    // unpredictable log levels under root. Do NOT remove this call.
    clearLoggers(config, leaveLogs)
    rulesList.foreach { r =>
      val level = r.head.trim
      val loggerName = r.lift(1).map(_.trim)
      setLogLevel(config, level, loggerName)
    }
    ctx.updateLoggers()
  }

  private def setLogLevel(config: Configuration, levelName: String, loggerOpt: Option[String]): Unit = {
    Option(Level.getLevel(levelName)) match {
      case None => Log.warn(s"Skipping unknown log rule: $levelName")
      case Some(level) =>
        loggerOpt match {
          case Some(name) =>
            val cfg = config.getLoggerConfig(name)
            if (cfg.getName != name) {
              val nl = new LoggerConfig(name, level, true)
              config.addLogger(name, nl)
            } else {
              cfg.setLevel(level)
              cfg.setAdditive(true)
            }
          case None =>
            config.getRootLogger.setLevel(level)
        }
    }
  }

  private def clearLoggers(config: Configuration, leave: Seq[String]): Unit = {
    val leaveSet = leave.toSet
    val names = config.getLoggers.keySet().asScala.toSeq
    names.foreach { name =>
      if (!leaveSet.contains(name)) config.removeLogger(name)
    }
  }

  def getLevel(loggerName: String): Level = getLevel(loggerName, currentLogRules)

  def getLevel(loggerName: String, logRules: String): Level = {
    val rules = logRules.split(",").map(_.split(":").map(_.trim))
    val namedRules = rules.flatMap {
      case Array(level, name) if Level.getLevel(level) != null => Some((name, Level.getLevel(level)))
      case _ => None
    }
    val matches = namedRules.filter { case (name, _) =>
      loggerName == name || loggerName.startsWith(name + ".")
    }
    if (matches.nonEmpty) {
      matches.maxByOption(_._1.length).map(_._2).getOrElse(Level.WARN)
    } else {
      rules.find(_.length == 1).flatMap(r => Option(Level.getLevel(r(0)))).getOrElse(Level.WARN)
    }
  }

  private def getContext: LoggerContext =
    LogManager.getContext(getClass.getClassLoader, false).asInstanceOf[LoggerContext]
}
