package org.polyconf.util

import PolyLog.Log

import java.util.concurrent.TimeoutException
import scala.concurrent.duration.Duration
import scala.util.{Try, Success, Failure}

final case class RetryPolicy(
    waitMs: Int = 1000,
    retryLimit: Int = 1,
    scaleCoef: Double = 2
) {
  def nextDelayTime: (Option[Int], RetryPolicy) = retryLimit match {
    case x if x < 0  => (Some(waitMs), this)
    case x if x <= 0 => (None, this)
    case x =>
      (Some(waitMs), copy(retryLimit = x - 1, waitMs = (waitMs * scaleCoef).toInt))
  }
}

object PolyUtil {

  private def sleepWithDeadline(delayMs: Int, deadlineNanos: Option[Long], startNanos: Long): Unit = {
    val remaining = deadlineNanos.map(dl => (dl - (System.nanoTime() - startNanos)) / 1000000).filter(_ > 0).getOrElse(delayMs.toLong)
    Try(Thread.sleep(Math.min(delayMs.toLong, remaining))).failed.foreach {
      case ie: InterruptedException =>
        Thread.currentThread().interrupt()
        throw ie
      case e => throw e
    }
  }

  def withResource[A <: AutoCloseable, B](builder: => A)(function: A => B): Try[B] =
    withData(builder)(_.close())(function)

  def withData[A, B](builder: => A)(closer: A => Unit)(function: A => B): Try[B] = {
    Try(builder) match {
      case Failure(e) => Failure(e)
      case Success(resource) =>
        val result = Try(function(resource))
        val closeResult = Try(closer(resource))
        (result, closeResult) match {
          case (Failure(funcE), Failure(closeE)) =>
            funcE.addSuppressed(closeE)
            Failure(funcE)
          case (_, Failure(closeE)) => Failure(closeE)
          case _ => result
        }
    }
  }

  def waitedGet[T](
      x: => T,
      policy: RetryPolicy = RetryPolicy(),
      duration: Duration = Duration.Inf,
      logErrors: Boolean = false
  ): T = {
    val deadlineNanos = if (duration.isFinite) Some(duration.toNanos) else None
    val startNanos = System.nanoTime()
    var lastError: Option[Throwable] = None
    var done = false
    var currentPolicy = policy

    while (!done) {
      deadlineNanos.foreach { dl =>
        if (System.nanoTime() - startNanos >= dl) done = true
      }
      if (!done) {
        Try(x) match {
          case Success(v) => return v
          case Failure(e) =>
            if (logErrors) Log.info(s"Error in waitedGet: ${e.getClass.getSimpleName}")
            lastError = Some(e)
            currentPolicy.nextDelayTime match {
              case (Some(d), nextPolicy) =>
                currentPolicy = nextPolicy
                sleepWithDeadline(d, deadlineNanos, startNanos)
              case (None, _) => done = true
            }
        }
      }
    }

    throw lastError.getOrElse(
      new TimeoutException(
        if (duration.isFinite) s"Failed to get result in $duration"
        else "Retry limit exhausted"
      )
    )
  }

  def waitForCondition(
      condition: => Boolean,
      policy: RetryPolicy = RetryPolicy(),
      duration: Duration = Duration.Inf
  ): Unit = {
    val deadlineNanos = if (duration.isFinite) Some(duration.toNanos) else None
    val startNanos = System.nanoTime()
    var timeout = false
    var currentPolicy = policy
    while (!timeout && !condition) {
      deadlineNanos.foreach { dl =>
        if (System.nanoTime() - startNanos >= dl) timeout = true
      }
      if (!timeout)
        currentPolicy.nextDelayTime match {
          case (Some(d), nextPolicy) =>
            currentPolicy = nextPolicy
            sleepWithDeadline(d, deadlineNanos, startNanos)
          case (None, _) => timeout = true
        }
    }
    if (timeout) throw new TimeoutException("Timeout waiting for condition")
  }
}
