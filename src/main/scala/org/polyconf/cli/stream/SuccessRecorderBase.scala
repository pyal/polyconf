package org.polyconf.cli.stream

import com.fasterxml.jackson.annotation.JsonIgnore
import org.polyconf.core.PolyConf
import org.polyconf.util.PolyUtil

import scala.collection.mutable

trait SuccessRecorderBase extends PolyConf {
  def record(inputPath: String): Unit
  def isSuccess(inputPath: String): Boolean
  def successCount: Int
  def clear(): Unit
}

case class SuccessRecorder(path: String = ".success_log") extends SuccessRecorderBase {
  @JsonIgnore private val completed = mutable.Set.empty[String]
  private lazy val loadCompleted: Unit = load()

  def record(inputPath: String): Unit = synchronized {
    loadCompleted
    completed += inputPath
    save()
  }

  def isSuccess(inputPath: String): Boolean = synchronized {
    loadCompleted
    completed.contains(inputPath)
  }

  def successCount: Int = synchronized { completed.size }

  def clear(): Unit = synchronized {
    completed.clear()
    save()
  }

  def load(): Unit = synchronized {
    val file = new java.io.File(path)
    if (file.exists) {
      PolyUtil.withResource(scala.io.Source.fromFile(file))(_.getLines().toSeq.foreach(completed.add)).get
    }
  }

  def save(): Unit = synchronized {
    new java.io.File(path).getParentFile.mkdirs()
    PolyUtil.withResource(new java.io.PrintWriter(path))(_.println(completed.mkString("\n"))).get
  }
}
