package org.polyconf.core

/** SPI interface for PolyConf class registration.
  * Implementations are discovered via ServiceLoader from
  * META-INF/services/org.polyconf.core.PolyConfProvider.
  *
  * Each child library provides one class extending PolyConfProvider and calls
  * `registerAllChildForBases(...)` with its base classes.  Concrete subclasses
  * in the same module are auto-discovered via classpath scanning.
  *
  * Example — a child library adding custom runners and transformers:
  * {{{
  * class MyLibProvider extends PolyConfProvider {
  *   private val (concrete, bases) = PolyConfProvider.registerAllChildForBases(
  *     classOf[RunnerBase], classOf[StreamTransformer]
  *   )
  *   override def getConcreteClasses = concrete
  *   override def getBaseClasses     = bases
  * }
  * }}}
  * Register in src/main/resources/META-INF/services/org.polyconf.core.PolyConfProvider
  * with a single line: `com.example.MyLibProvider`. */
trait PolyConfProvider {
  def getConcreteClasses: java.util.Collection[Class[_ <: PolyConf]]
  def getBaseClasses: java.util.Collection[Class[_ <: PolyConf]]
}

object PolyConfProvider {

  /** Scans the classpath for all concrete subclasses of each given base class
    * and returns the aggregated (concrete, bases) pair.
    *
    * Call this from your PolyConfProvider constructor and return the collections
    * directly from `getConcreteClasses` / `getBaseClasses`.  The scanner uses
    * reflection on all classloader URLs, filtering to `org.polyconf.*` packages.
    *
    * {{{
    *   class MyProvider extends PolyConfProvider {
    *     private val (concrete, bases) = PolyConfProvider.registerAllChildForBases(
    *       classOf[RunnerBase], classOf[StreamTransformer]
    *     )
    *     override def getConcreteClasses = concrete
    *     override def getBaseClasses     = bases
    *   }
    * }}}
    *
    * @param bases abstract base classes whose concrete subclasses should be discovered
    * @return (mutable list of concrete subclasses, mutable list of base classes)
    */
  def registerAllChildForBases(
    bases: Class[_ <: PolyConf]*
  ): (java.util.List[Class[_ <: PolyConf]], java.util.List[Class[_ <: PolyConf]]) = {
    val concrete = new java.util.ArrayList[Class[_ <: PolyConf]]()
    val baseList = new java.util.ArrayList[Class[_ <: PolyConf]]()
    bases.foreach { base =>
      baseList.add(base)
      SubclassScanner.findConcreteSubclasses(base).foreach { child =>
        if (!concrete.contains(child)) concrete.add(child)
      }
    }
    (concrete, baseList)
  }
}
