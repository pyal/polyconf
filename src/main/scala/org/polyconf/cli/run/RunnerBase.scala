package org.polyconf.cli.run

import org.polyconf.core.{PolyConf, PolyConfRegistry}

/** Base trait for all Runners. Implement run() to define job execution logic.
  * Concrete RunnerBase subclasses are auto-discovered via SPI + classpath scanning.
  * Child libraries add runners by providing a PolyConfProvider in
  * META-INF/services/org.polyconf.core.PolyConfProvider. */
trait RunnerBase extends PolyConf {
  def run(): String
  def init(): Unit = {}
}

object RunnerBase {
  /** Registers all PolyConf classes via SPI (ServiceLoader). Called by CliRun.doInternal(). */
  def registerClasses(): Unit = PolyConfRegistry.registerAll()
}