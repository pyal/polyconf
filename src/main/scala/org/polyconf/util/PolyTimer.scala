package org.polyconf.util

import org.apache.logging.log4j._
import org.polyconf.util.PolyNumFormat.{prettyNanoTime, prettyNumber}

import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.Duration

/** Helper object to make single execution measurement */
object PolyTimer {

  lazy val Log: Logger = LogManager.getLogger(getClass)

  /** Measure function `func` execution time and log result statistics
    * @param func
    *   function whose execution time is measured
    * @param logger
    *   Logger to publish the result. Logger parameters:
    *   - Generated performance result string
    *   - Calculated iteration count for the function result
    *   - Function result
    * @param getFunctionIterationNumber
    *   Method calculating number of iterations done using <b>func</b> output
    * @param measureDescription
    *   Description string for the time measurement
    * @tparam T
    *   Function return type
    * @return
    *   Function result
    */
  def measure[T](func: => T)(
      logger: (String, Long, T) => Unit,
      getFunctionIterationNumber: T => Long = (_: T) => 1L,
      measureDescription: String = ""
  ): T = {
    measureFull(
      func,
      (perfMessage: PerformanceMessage, iterationCount: Long, funcResult: T) =>
        if (iterationCount <= 1)
          logger(s"${perfMessage.timeStr}", iterationCount, funcResult)
        else
          logger(
            s"${perfMessage.speedStr}\n${perfMessage.countTimeStr}",
            iterationCount,
            funcResult
          ),
      getFunctionIterationNumber,
      measureDescription
    )
  }

  def measureSimple[T](func: => T)(
      logger: String => Unit,
      getFunctionIterationNumber: T => Long = (_: T) => 1L,
      measureDescription: String = ""
  ): T =
    measure(func)(
      (message: String, _: Long, _: T) => logger(message),
      getFunctionIterationNumber,
      measureDescription
    )

  def measureFull[T](
      func: => T,
      log: (PerformanceMessage, Long, T) => Unit,
      getFunctionIterationNumber: T => Long,
      measureDescription: String
  ): T = {
    val timer           = new PolyTimer
    val res             = func
    val iterationNumber = getFunctionIterationNumber(res)
    log(timer.increment(iterationNumber).getTotalMessage(measureDescription), iterationNumber, res)
    res
  }

  def measureNanoTime[T](func: => T): (T, Long) = {
    val start = System.nanoTime()
    val res   = func
    (res, System.nanoTime() - start)
  }
}

/** Storing internal data for measure.
  * @param iterationTimeSec
  *   time period to print process performance statistics
  */
class PolyTimer(iterationTimeSec: Int = 10) extends Serializable {
  private val startTime     = new AtomicLong(0L)
  private val iterStartTime = new AtomicLong(0L)
  private val curIter       = new AtomicLong(0L)
  private val totalIter     = new AtomicLong(0L)
  private val iterationStep = iterationTimeSec * 1e9

  def reset(): Unit = {
    startTime.set(System.nanoTime)
    iterStartTime.set(startTime.get())
    curIter.set(0)
    totalIter.set(0)
  }

  reset()

  def startIteration(): Unit = {
    iterStartTime.set(System.nanoTime)
    curIter.set(0)
  }

  def timeToEndIteration(): Boolean = System.nanoTime() > iterStartTime.get() + iterationStep
  def elapsedTime(): Duration       = Duration.fromNanos(System.nanoTime() - startTime.get())

  def increment(addNum: Long): PolyTimer = {
    curIter.addAndGet(addNum)
    totalIter.addAndGet(addNum)
    this
  }

  def getIterationMessage(descr: String = "Iteration"): PerformanceMessage =
    PerformanceMessage(iterStartTime.get(), curIter.get(), descr)

  def getTotalMessage(descr: String = "Total"): PerformanceMessage =
    PerformanceMessage(startTime.get(), totalIter.get(), descr)

  def logIterationInfo(addIterationCount: Long, logInfo: PolyTimer => Unit): Unit = {
    increment(addIterationCount)
    if (timeToEndIteration()) {
      logInfo(this)
      startIteration()
    }
  }

}

/** Helper class to print performance statistics - building description string
  * @param startTime
  *   start nano time
  * @param count
  *   number of internal iterations done
  * @param descr
  *   add description to statistics
  */
final case class PerformanceMessage(startTime: Long, count: Long, descr: String) {
  val elapsedNanos: Long = System.nanoTime() - startTime
  def timeStr: String         = s"$descr Time ${prettyNanoTime(elapsedNanos, 2)}"
  def countStr: String        = s"$descr Count ${prettyNumber(count, 2)}"
  def countTimeStr: String    = countStr + s" in ${prettyNanoTime(elapsedNanos, 2)}"

  def speedStr: String = {
    val iPerSec = 1e9 * count / elapsedNanos
    val secPerI =
      if (count <= 0)
        0L
      else
        (1e9 / iPerSec).toLong
    s"$descr Speed I/s ${"%3.2e".format(iPerSec)} s/I ${prettyNanoTime(secPerI, 2)}"
  }
}
