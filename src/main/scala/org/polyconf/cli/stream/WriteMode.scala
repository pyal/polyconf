package org.polyconf.cli.stream

import com.fasterxml.jackson.annotation.{JsonCreator, JsonFormat, JsonValue}

@JsonFormat(shape = JsonFormat.Shape.STRING)
sealed trait WriteMode {
  @JsonValue
  def serialized: String = toString
}
object WriteMode {
  case object Overwrite extends WriteMode { override def toString = "overwrite" }
  case object Append extends WriteMode { override def toString = "append" }
  case object ErrorIfExists extends WriteMode { override def toString = "error" }
  case object Ignore extends WriteMode { override def toString = "ignore" }

  @JsonCreator
  def fromString(s: String): WriteMode = s.toLowerCase match {
    case "overwrite" => Overwrite
    case "append"    => Append
    case "error"     => ErrorIfExists
    case "ignore"    => Ignore
    case _           => throw new IllegalArgumentException(s"Unknown WriteMode: '$s'. Valid options: overwrite, append, error, ignore")
  }
}
