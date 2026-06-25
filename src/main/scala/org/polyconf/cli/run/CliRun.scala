package org.polyconf.cli.run

import org.polyconf.cli.CliParamsBase
import org.polyconf.core.{PolyConf, PolyConfRegistry}
import org.polyconf.util.PolyLog.Log
import org.polyconf.util.PolyTimer.measureSimple
import org.rogach.scallop.ScallopOption

class CliRun {
  /** This class helps run any RunnerBase job class from the command line.
    * Job configuration is passed as a JSON string describing the runner, transformers,
    * writers, and generators. Classes are auto-discovered via SPI + classpath scanning. */
  def help: String =
    """
      |This class helps run any RunnerBase job class.
      |Usage: pass a JSON string describing the job pipeline (runner + transformers + writers).
      |Registered classes are auto-discovered via SPI.
      |""".stripMargin

  class CliRunParams(arguments: Seq[String]) extends CliParamsBase(arguments) {
    val jobStr: ScallopOption[String] =
      opt[String](required = true, descr = "Json string representation of job class to run")

    override def commandDescription: String = {
      s"""
         |Runs any RunnerBase job class. Runner, transformer, writer, and generator
         |classes are auto-discovered via SPI + classpath scanning.  Child libraries
         |add their own classes by providing a PolyConfProvider in
         |META-INF/services/org.polyconf.core.PolyConfProvider.
         |
         |Registered job classes:
         |${PolyConfRegistry.getHelpBase(classOf[RunnerBase])}
         |""".stripMargin
    }

    verify()
  }

  def registerClasses(): Unit = RunnerBase.registerClasses()

  def doInternal(args: Array[String]): String = {
    registerClasses()
    val params: CliRunParams = new CliRunParams(args.toSeq)
    params.mainExecutionLogic {
      val jobStr       = params.jobStr()
      Log.debug(s"Job params: ${args.mkString(" ")}")
      val job: RunnerBase = PolyConf.deserialize[RunnerBase](jobStr)
      Log.debug(s"Generated class: ${PolyConf.serialize(job, prettyFormat = true)}")
      job.init()
      measureSimple(job.run())(Log.debug, measureDescription = "Job done")
    }
  }
}

object CliRun extends CliRun {
  def main(args: Array[String]): Unit = Log.info(s"Job results:\n${doInternal(args)}")
}
