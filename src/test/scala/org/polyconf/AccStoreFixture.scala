package org.polyconf

import org.polyconf.cli.run.RunnerBase
import org.polyconf.util.{PolyAccStorage, PolyUtil}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

trait AccStoreFixture extends AnyFlatSpec with Matchers {
  RunnerBase.registerClasses()

  override def withFixture(test: NoArgTest) =
    PolyUtil.withData(PolyAccStorage.accLong)(_.clear())(a => { a.clear(); super.withFixture(test) }).get
}
