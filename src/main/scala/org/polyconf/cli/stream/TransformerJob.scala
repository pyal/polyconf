package org.polyconf.cli.stream

import com.fasterxml.jackson.annotation.JsonIgnore
import org.polyconf.cli.run.RunnerBase
import org.polyconf.core.PolyConfRegistry
import org.polyconf.util._

import scala.util._

class TransformerJob[T <: StreamDataBase](
    val generator: StreamGenerator[T] = null,
    val transformers: Seq[StreamTransformer[T]] = Seq.empty,
    val writers: Seq[StreamWriter[T]] = Seq.empty,
    val successRecorderOpt: Option[SuccessRecorderBase] = None,
    val clearStatus: Boolean = false
) extends RunnerBase {

  @JsonIgnore
  val perf: PolyAccStorage[PolyAccumulatorPerf[Long]] = PolyAccStorage.accPerf[Long]

  @JsonIgnore private val genRows     = "genRows"
  @JsonIgnore private val filesRead   = "filesRead"
  @JsonIgnore private val filesFailed = "filesFailed"
  @JsonIgnore private val written     = "written"

  private def trRows(i: Int): String = s"tr${i + 1}"
  private def wrName(i: Int): String = s"wr${i + 1}"

  override def init(): Unit = {
    require(generator != null, "generator must be provided")
    if (clearStatus) successRecorderOpt.foreach(_.clear())
  }

  def finalizeResults(result: Try[Unit]): Unit = {}

  def runTransformers: Try[Unit] = {
    var totalWrittenRows = 0L
    val (tryResult, totalTimeNs) = PolyTimer.measureNanoTime(Try {
      PolyLog.Log.info(s"TransformerJob started: ${transformers.size} transform(s), ${writers.size} writer(s)")
      val fileFailures = List.newBuilder[(String, Throwable)]
      generator.readIterator(successRecorderOpt)
        .filterNot(input => successRecorderOpt.exists(_.isSuccess(input.inputPath)))
        .foreach { inputFileData =>
          processFile(inputFileData) match {
            case Success(written) =>
              totalWrittenRows += written
              successRecorderOpt.foreach(_.record(inputFileData.inputPath))
            case Failure(e) =>
              fileFailures += (inputFileData.inputPath -> e)
          }
        }
      val failures = fileFailures.result()
      if (failures.nonEmpty)
        throw TransformerException(failures.map { case (p, e) => StreamDataEnvelope[T](Failure(e), p) })
    })

    perf.add("totalTime", PolyAccumulatorPerf.withTime(totalTimeNs))
    perf.add("totalTime", PolyAccumulatorPerf.withCount(totalWrittenRows))
    finalizeResults(tryResult)
    transformers.foreach(_.jobDone())
    writers.foreach(_.jobDone())
    PolyLog.Log.info(
      "TransformerJob finished\n" +
        perf.entries.toSeq.sortBy(_._1).map { case (k, v) => s"  $k: $v" }.mkString("\n")
    )
    tryResult
  }

  private def processFile(inputFileData: StreamDataEnvelope[T]): Try[Long] = {
    val log = PolyLog.Log
    inputFileData.data match {
      case Failure(e) =>
        perf.add(filesFailed, PolyAccumulatorPerf.withCount(1L))
        log.error(s"Failed to read ${inputFileData.inputPath}: ${e.getMessage}")
        Failure(e)
      case Success(data) =>
        perf.add(filesRead, PolyAccumulatorPerf.withCount(1L))
        val path = inputFileData.inputPath
        val (genCounted, genFn) = data.countPass
        // Defer genFn() evaluation — Spark DF accumulators need data consumption first
        val genData = inputFileData.mapData(_ => genCounted.asInstanceOf[T])

        val stageCountFnBuilders = transformers.indices.map(_ => List.newBuilder[() => Long])

        val transformed = transformers.indices.foldLeft(Iterator(genData)) { (acc, idx) =>
          acc.flatMap { td =>
            val result = transformers(idx).transform(td)
            result.map { r =>
              val rWithCount = r.mapData { d =>
                val (c, countFn) = d.countPass
                stageCountFnBuilders(idx) += countFn
                c.asInstanceOf[T]
              }
              rWithCount
            }
          }
        }

        Try {
          var writtenCount = 0L
          var failure: Option[Throwable] = None
          transformed.foreach { data =>
            data.data match {
              case Success(dp) =>
                val (counted, countFn) = dp.countPass
                val countedData = data.mapData(_ => counted.asInstanceOf[T])

                if (writers.size > 1) counted.persist
                writers.zipWithIndex.foreach { case (w, wi) =>
                  val (writeResult, writeTime) = PolyTimer.measureNanoTime(w.write(countedData))
                  writeResult match {
                    case Success(_) =>
                      perf.add(wrName(wi), PolyAccumulatorPerf.withTime(writeTime))
                    case Failure(err) =>
                      if (failure.isEmpty) failure = Some(err)
                      log.error(s"Write ${w.getClass.getSimpleName} failed for ${data.inputPath}: ${err.getMessage}")
                  }
                }
                counted.unpersist
                val rowCount = countFn()
                writtenCount += rowCount
              case Failure(err) =>
                if (failure.isEmpty) failure = Some(err)
                log.error(s"Transform failed for ${data.inputPath}: ${err.getMessage}")
            }
          }
          val stageCounts = stageCountFnBuilders.map(_.result().map(_()).sum)
          transformers.indices.foreach(i => perf.add(trRows(i), PolyAccumulatorPerf.withCount(stageCounts(i))))
          perf.add(genRows, PolyAccumulatorPerf.withCount(genFn()))
          perf.add(written, PolyAccumulatorPerf.withCount(writtenCount))
          writers.indices.foreach(wi => perf.add(wrName(wi), PolyAccumulatorPerf.withCount(writtenCount)))
          log.info(
            s"Completed $path: ${PolyNumFormat.prettyNumber(writtenCount)} rows"
          )
          failure.foreach(e => throw e)
          writtenCount
        }
    }
  }

  override def run(): String = {
    init()
    runTransformers match {
      case Success(_) => "TransformerJob completed successfully"
      case Failure(e) => throw e
    }
  }

  override def help: String = {
    s"""
      |TransformerJob[T] runs a data pipeline: generator -> transformers -> writers.
      |T is the data type (must implement StreamDataBase).
      |
      |Available implementations grouped by their T type (only compatible implementations
      |with the same T can be mixed in a single pipeline):
      |${PolyConfRegistry.getHelpByType(classOf[StreamGenerator[_]], classOf[StreamTransformer[_]], classOf[StreamWriter[_]])}
      |${PolyConfRegistry.getHelpBase(classOf[SuccessRecorderBase])}
      |  clearStatus: clear success recorder on init (default: false)
      |""".stripMargin
  }
}

object TransformerJob {
  def apply[T <: StreamDataBase](
    generator: StreamGenerator[T],
    transformers: Seq[StreamTransformer[T]] = Seq.empty,
    writers: Seq[StreamWriter[T]] = Seq.empty,
    successRecorderOpt: Option[SuccessRecorderBase] = None,
    clearStatus: Boolean = false
  ): TransformerJob[T] =
    new TransformerJob[T](generator, transformers, writers, successRecorderOpt, clearStatus)

  def unapply[T <: StreamDataBase](job: TransformerJob[T]): Option[(StreamGenerator[T], Seq[StreamTransformer[T]], Seq[StreamWriter[T]], Option[SuccessRecorderBase], Boolean)] =
    Some((job.generator, job.transformers, job.writers, job.successRecorderOpt, job.clearStatus))
}
