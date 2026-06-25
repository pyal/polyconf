package org.polyconf.argfmt

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import org.polyconf.core.PolyConf
import org.polyconf.core.PolyConf.{serialize, deserialize}

trait ParamsGeneratorBase extends PolyConf {
  def toArgs(fullConfig: Boolean): Seq[String]
}

case class RunParamsGenerator(
    jobStr: Map[String, Any],
    @JsonDeserialize(contentAs = classOf[Object])
    additionalArgs: Seq[Any] = Seq.empty,
    jarPath: String = "./target/scala-2.13/polyconf-assembly-0.1.0-SNAPSHOT.jar",
    shellPrefix: String = ""
) extends ParamsGeneratorBase {

  override def help: String =
    """
      |Generates a shell command to run a job via CliRun.
      |jobStr: job JSON (RunnerBase subclass — auto-discovered via SPI)
      |jarPath: path to polyconf assembly JAR
      |shellPrefix: override with custom prefix (e.g. ./run.sh run.local)
      |additionalArgs: extra args appended to the command
      |""".stripMargin
  override def toArgs(fullConfig: Boolean): Seq[String] = {
    val jobRaw = serialize(jobStr)
    val job = if (fullConfig) serialize(deserialize(jobRaw)) else jobRaw
    val cmd = shellPrefix match {
      case "" => Seq(s"java -cp $jarPath org.polyconf.cli.run.CliRun")
      case shell => shell.split(" ").toSeq
    }
    val extra = additionalArgs.map {
      case s: String => s
      case m: Map[_, _] => serialize(m.asInstanceOf[Map[String @unchecked, Any]])
      case x => x.toString
    }
    cmd ++ Seq("-j", job) ++ extra
  }
}
