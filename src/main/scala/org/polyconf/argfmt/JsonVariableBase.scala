package org.polyconf.argfmt

import org.polyconf.core.PolyConf

trait JsonVariableBase extends PolyConf {
  def rename(json: String, replaceStr: String = ""): String
}

object JsonVariableInline {
  def replaceVars(json: String, renameMap: Map[String, String] = Map.empty, variablePrefix: String = "$"): String = {
    val sorted = renameMap.toSeq.sortBy(_._1.length).reverse
    sorted.foldLeft(json) { case (acc, (k, v)) => acc.replace(variablePrefix + k, v) }
  }
}

final case class JsonVariableInline(
    Env: Map[String, Map[String, String]] = Map.empty,
    defaultEnv: String = "local",
    variablePrefix: String = "$"
) extends JsonVariableBase {
  override def rename(json: String, replaceStr: String = ""): String = {
    val renameArgs: Seq[(String, String)] =
      replaceStr.split("~~").filter(_.nonEmpty).map { s =>
        val arr = s.split("--")
        arr(0) -> arr.lift(1).getOrElse("")
      }.toSeq

    val envName = renameArgs.find(_._1 == "Env").map(_._2).getOrElse(defaultEnv)

    val allVars = Env.getOrElse("all", Map.empty).toSeq
    val envVars = Env.getOrElse(envName, Map.empty).toSeq

    def merge(seqs: Seq[Seq[(String, String)]]): Seq[(String, String)] =
      seqs.flatten.groupBy(_._1).map { case (k, vs) => k -> vs.head._2 }.toSeq

    val combined = merge(Seq(renameArgs, envVars, allVars))
    JsonVariableInline.replaceVars(json, combined.toMap, variablePrefix)
  }
}
