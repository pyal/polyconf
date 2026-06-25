val jacksonVersion = "2.15.2"
val scala213 = "2.13.16"
val scalaTestVersion = "3.2.19"

lazy val commonSettings = Seq(
  organization := "com.github.pyal",
  scalaVersion := scala213,
  crossScalaVersions := Seq(scala213),
  scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-Xfatal-warnings"),
  Test / parallelExecution := false,
  libraryDependencies ++= Seq(
    "com.fasterxml.jackson.core"       % "jackson-core"        % jacksonVersion,
    "com.fasterxml.jackson.core"       % "jackson-databind"    % jacksonVersion,
    "com.fasterxml.jackson.core"       % "jackson-annotations" % jacksonVersion,
    "com.fasterxml.jackson.module"    %% "jackson-module-scala" % jacksonVersion,
    "com.fasterxml.jackson.datatype"   % "jackson-datatype-jsr310" % jacksonVersion,
    "org.apache.logging.log4j"         % "log4j-core"          % "2.20.0",
    "org.apache.logging.log4j"         % "log4j-1.2-api"       % "2.20.0",
    "org.scala-lang.modules"          %% "scala-parallel-collections" % "1.0.4",
    "org.yaml"                         % "snakeyaml"           % "2.3",
    "org.rogach"                      %% "scallop"             % "5.1.0",
    "org.apache.avro"                  % "avro"                % "1.11.3",
    "org.apache.parquet"               % "parquet-avro"        % "1.13.1",
    ("org.apache.hadoop"                % "hadoop-common"       % "3.3.6")
      .exclude("org.slf4j", "slf4j-log4j12")
      .exclude("org.slf4j", "slf4j-reload4j")
      .exclude("log4j", "log4j")
      .exclude("ch.qos.reload4j", "reload4j"),
    ("org.apache.hadoop"                % "hadoop-mapreduce-client-core" % "3.3.6")
      .exclude("org.slf4j", "slf4j-log4j12")
      .exclude("org.slf4j", "slf4j-reload4j")
      .exclude("log4j", "log4j")
      .exclude("ch.qos.reload4j", "reload4j"),
    "org.scalatest"                   %% "scalatest"           % scalaTestVersion % Test,
  ),
)

lazy val `polyconf` = (project in file("."))
  .settings(commonSettings, name := "polyconf")
  .settings(
    assembly / mainClass := Some("org.polyconf.argfmt.ArgFormatter"),
    assembly / assemblyMergeStrategy := {
      case "module-info.class"                               => MergeStrategy.discard
      case "META-INF/MANIFEST.MF"                            => MergeStrategy.discard
      case "META-INF/DEPENDENCIES"                           => MergeStrategy.discard
      case "META-INF/io.netty.versions.properties"           => MergeStrategy.last
      case "META-INF/native/libnetty_transport_native_epoll.c" => MergeStrategy.last
      case "META-INF/services/org.apache.logging.log4j.spi.Provider" => MergeStrategy.concat
      case "META-INF/services/com.fasterxml.jackson.databind.Module" => MergeStrategy.concat
      case x if x.startsWith("META-INF/services/")           => MergeStrategy.concat
      case x if x.endsWith(".conf")                          => MergeStrategy.concat
      case x if x.endsWith(".properties")                    => MergeStrategy.last
      case x if x.startsWith("META-INF/")                    => MergeStrategy.discard
      case _                                                 => MergeStrategy.first
    }
  )
