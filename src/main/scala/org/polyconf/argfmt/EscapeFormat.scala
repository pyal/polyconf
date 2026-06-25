package org.polyconf.argfmt

import org.polyconf.core.{PolyConf, PolyConfRegistry}

trait EscapeFormatBase extends PolyConf {
  def formatName: String
  def format(args: Seq[String]): String
}

class EscapeFormatShell extends EscapeFormatBase {
  override def formatName: String = "shell"
  override def help: String       = "Shell-escapes arguments"

  override def format(args: Seq[String]): String = args.map(shellEscape).mkString(" ")

  private def shellEscape(s: String): String =
    if (s.isEmpty) "''"
    else if (s.matches("[a-zA-Z0-9_./@%+=:-]+")) s
    else "'" + s.replace("'", "'\\''") + "'"
}

class EscapeFormatYaml extends EscapeFormatBase {
  override def formatName: String = "yaml"
  override def help: String       = "Single-quote escapes each argument"

  override def format(args: Seq[String]): String = args.map(x => s"'${x.replace("'", "''")}'").mkString(" ")
}
