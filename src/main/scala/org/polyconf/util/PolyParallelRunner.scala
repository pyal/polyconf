package org.polyconf.util

import PolyUtil.withResource

import java.util.concurrent._
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer
import scala.collection.parallel.CollectionConverters._
import scala.collection.parallel._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util._

object PolyParallelRunner {

  private case class ThreadPoolResources(
    threadPool: ExecutorService,
    taskSupport: Option[TaskSupport]
  ) extends AutoCloseable {
    override def close(): Unit = {
      threadPool.shutdown()
      threadPool.awaitTermination(30, TimeUnit.SECONDS)
    }
  }

  private def withThreadPool[A](numThreads: Int, useFixedThread: Boolean)(f: (ExecutorService, Option[TaskSupport]) => A): Try[A] = {
    val threadFactory = new ThreadFactory {
      override def newThread(r: Runnable): Thread = new Thread(r, "polyconf-worker")
    }
    withResource(
      if (useFixedThread) {
        val tp = Executors.newFixedThreadPool(numThreads, threadFactory).asInstanceOf[ThreadPoolExecutor]
        ThreadPoolResources(tp, Some(new ExecutionContextTaskSupport(ExecutionContext.fromExecutor(tp))))
      } else {
        val tp = new ForkJoinPool(numThreads)
        ThreadPoolResources(tp, Some(new ForkJoinTaskSupport(tp)))
      }
    ) { resources => f(resources.threadPool, resources.taskSupport) }
  }

  def parallelRun[A, B](
      numThreads: Int,
      inputs: Seq[A],
      f: A => B,
      useFixedThread: Boolean = true
  ): Try[List[B]] =
    withThreadPool(numThreads, useFixedThread) { (_, taskSupport) =>
      val parJobs = inputs.par
      parJobs.tasksupport = taskSupport.get
      val results = parJobs.map(x => Try(f(x))).seq.toList
      val failures = results.collect { case Failure(e) => e }
      if (failures.nonEmpty) {
        val composite = new RuntimeException(s"${failures.size} error(s) in parallel execution")
        failures.foreach(composite.addSuppressed)
        throw composite
      }
      results.map(_.get)
    }

  def parallelOrderedRun[A: ClassTag, B](
      numThreads: Int,
      inputs: Seq[A],
      f: A => B,
      useFixedThread: Boolean = true
  ): Try[List[B]] =
    withThreadPool(numThreads, useFixedThread) { (threadPool, _) =>
      val inputsArray = inputs.toArray
      val results     = new Array[Any](inputsArray.length)
      val nextIndex   = new AtomicInteger(0)
      val errors      = ArrayBuffer[Throwable]()
      val errorsLock  = new Object()

      val worker = new Runnable {
        override def run(): Unit = {
          var continue = true
          while (continue) {
            val idx = nextIndex.getAndIncrement()
            if (idx >= inputsArray.length)
              continue = false
            else
              Try(f(inputsArray(idx))) match {
                case Success(r) => results(idx) = r
                case Failure(e) =>
                  errorsLock.synchronized { errors += e }
                  continue = false
              }
          }
        }
      }

      val futures = (1 to numThreads).map(_ => threadPool.submit(worker))
      futures.foreach { f =>
        Try(f.get(30, TimeUnit.SECONDS)).recover { case e =>
          f.cancel(true)
          errorsLock.synchronized {
            errors += (e match {
              case ee: ExecutionException => ee.getCause
              case other => other
            })
          }
        }
      }
      val hasErrors = errorsLock.synchronized { errors.nonEmpty }
      if (hasErrors) errorsLock.synchronized {
        val composite = new RuntimeException(s"${errors.size} error(s) in parallel ordered execution")
        errors.foreach(composite.addSuppressed)
        throw composite
      }
      results.toList.asInstanceOf[List[B]]
    }

  def retryJob[V](func: => V, retryCount: Int = 3, delay: Duration = 10.milliseconds): Try[V] =
    Try(PolyUtil.waitedGet(func, RetryPolicy(delay.toMillis.toInt, retryCount)))

  def waitUntil(
      condition: => Boolean,
      timeout: Duration = 60.seconds,
      sleepTime: Duration = 1.second
  ): Unit = PolyUtil.waitForCondition(condition, RetryPolicy(sleepTime.toMillis.toInt, -1), timeout) // retryLimit = -1 means infinite retries
}
