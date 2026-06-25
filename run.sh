#!/bin/bash
JAR=./target/scala-2.13/polyconf-assembly-0.1.0-SNAPSHOT.jar

# ==========================================
# Production: requires assembly JAR
#   sbt assembly  (build the JAR first)
# ==========================================

# Generate CLI args from YAML config via CliArgGenerator
run.args() {
  java -cp "$JAR" org.polyconf.argfmt.CliArgGenerator "$@"
}

# Run a job directly via CliRun
run.local() {
  java -cp "$JAR" org.polyconf.cli.run.CliRun "$@"
}

# ==========================================
# Development: uses sbt (no JAR build needed)
# ==========================================

# Generate CLI args from YAML config via sbt
run.dev.args() {
  sbt "runMain org.polyconf.argfmt.CliArgGenerator $*"
}

# Run a job via sbt
run.dev.local() {
  sbt "runMain org.polyconf.cli.run.CliRun $*"
}

# Spark jobs: use ../polyconf-spark/run.sh

"$@"
