package org.polyconf.cli.run

import org.polyconf.util.PolyLog.Log
import org.polyconf.util.PolyTimer.measureSimple

import scala.collection.mutable.ListBuffer
import scala.sys.process._

/** Shell command execution helper */
object RunnerShell {

  def runShell(jobStr: String): (String, String, Int) = {
    val output: ListBuffer[String] = ListBuffer.empty
    val error: ListBuffer[String]  = ListBuffer.empty
    val logger                     = ProcessLogger(line => output += line, line => error += line)
    val exitCode                   = jobStr ! logger
    (output.mkString("\n"), error.mkString("\n"), exitCode)
  }
}

/** Shell command execution */
class RunnerShell extends RunnerBase {
  private val cmd: String          = ""

  override def run(): String = {
    val (output, error, exitCode) = RunnerShell.runShell(cmd)
    Log.debug(s"Command: |$cmd| \nexit code $exitCode")
    if (output.nonEmpty) {
      Log.debug("Command output:")
      println(output)
    }
    if (error.nonEmpty)
      Log.error("Command error output: \n" + error)
    if (exitCode != 0)
      sys.error(
        s"Failed job |$cmd| with code $exitCode."
      )
    output
  }

  override def init(): Unit = require(cmd.nonEmpty, "cmd must not be empty")

  override def help: String =
    s"""
       |Job will execute shell command: |cmd|
       |cmd: shell command with arguments
       |""".stripMargin

}
