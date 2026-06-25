package org.polyconf.core

import org.polyconf.argfmt.{EscapeFormatBase, JsonVariableBase, ParamsGeneratorBase}
import org.polyconf.cli.run.RunnerBase
import org.polyconf.cli.stream.{StreamGenerator, StreamTransformer, StreamWriter, SuccessRecorderBase}

/** SPI provider for polyconf-core classes.
  * Lists all base classes from the core module; their concrete subclasses
  * are auto-discovered via classpath scanning.  Child modules (e.g. polyconf-spark)
  * provide their own PolyConfProvider with additional or overlapping bases. */
class CorePolyConfProvider extends PolyConfProvider {
  private val (concrete, bases) = PolyConfProvider.registerAllChildForBases(
    classOf[RunnerBase],
    classOf[EscapeFormatBase],
    classOf[ParamsGeneratorBase],
    classOf[JsonVariableBase],
    classOf[StreamGenerator[_]],
    classOf[StreamTransformer[_]],
    classOf[StreamWriter[_]],
    classOf[SuccessRecorderBase]
  )

  override def getConcreteClasses: java.util.Collection[Class[_ <: PolyConf]] = concrete
  override def getBaseClasses: java.util.Collection[Class[_ <: PolyConf]] = bases
}
