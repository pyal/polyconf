package org.polyconf.stream

import org.polyconf.argfmt.JsonVariableInline
import org.polyconf.cli.stream._
import org.polyconf.core.{PolyConf, PolyConfRegistry, PolySerde}
import org.polyconf.util.PolyUtil
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.{File, PrintWriter}
import scala.io.Source

class TransformerJobPerfSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  override def beforeAll(): Unit = {
    PolyConfRegistry.registerAll()
  }

  private def tempFile(prefix: String, suffix: String = ".json"): File = {
    val f = File.createTempFile(prefix, suffix)
    f.deleteOnExit()
    f
  }

  private def writeJson(f: File, data: Seq[Map[String, Any]]): Unit =
    PolyUtil.withResource(new PrintWriter(f))(_.write(PolySerde.jsonFormat(data))).get

  private def buildJob(
      inputPath: String,
      outputPath: String,
      filterExpr: String = "",
      limit: Int = 0,
      columns: Seq[String] = Seq.empty,
  ): TransformerJob[StreamData] = {
    val config = Map(
      "CN" -> "TransformerJob",
      "generator" -> Map(
        "CN" -> "SimpleDataGenerator",
        "path" -> inputPath,
        "format" -> "json"
      ),
      "transformers" -> Seq(
        (if (columns.nonEmpty) Map("columns" -> columns) else Map.empty)
          ++ (if (limit > 0) Map("limit" -> limit) else Map.empty)
          ++ (if (filterExpr.nonEmpty) Map.empty else Map.empty)
          ++ Map("CN" -> "TransformerDebug")
      ).filter(_.nonEmpty),
      "writers" -> Seq(
        Map(
          "CN" -> "SimpleDataWriter",
          "path" -> outputPath,
          "format" -> "json"
        )
      )
    )
    PolyConf.deserialize[TransformerJob[StreamData]](PolySerde.jsonFormat(config))
  }

  private def runSimpleJob(data: Seq[Map[String, Any]]): TransformerJob[StreamData] = {
    val inFile = tempFile("perf_in_")
    val outFile = tempFile("perf_out_")
    writeJson(inFile, data)
    val job = PolyConf.deserialize[TransformerJob[StreamData]](
      s"""{"CN":"TransformerJob","generator":{"CN":"SimpleDataGenerator","path":"${inFile.getAbsolutePath}","format":"json"},"writers":[{"CN":"SimpleDataWriter","path":"${outFile.getAbsolutePath}","format":"json"}]}"""
    )
    job.runTransformers.get
    job
  }

  "TransformerJob perf (StreamData)" should "track genRows and written for simple pipeline" in {
    val inFile = tempFile("perf_in_")
    val outFile = tempFile("perf_out_")
    writeJson(inFile, (1 to 10).map(i => Map("id" -> i, "name" -> s"n$i")))
    val job = PolyConf.deserialize[TransformerJob[StreamData]](
      s"""{"CN":"TransformerJob","generator":{"CN":"SimpleDataGenerator","path":"${inFile.getAbsolutePath}","format":"json"},"writers":[{"CN":"SimpleDataWriter","path":"${outFile.getAbsolutePath}","format":"json"}]}"""
    )
    job.runTransformers.get
    job.perf.get("filesRead").count shouldBe 1
    job.perf.get("genRows").count shouldBe 10
    job.perf.get("written").count shouldBe 10
    job.perf.get("wr1").count shouldBe 10
    job.perf.get("wr1").timeNanos should be > 0L
    job.perf.get("totalTime").count shouldBe 10
    job.perf.get("totalTime").timeNanos should be > 0L
  }

  it should "track tr1 after limit transformer" in {
    val inFile = tempFile("perf_in_")
    val outFile = tempFile("perf_out_")
    writeJson(inFile, (1 to 20).map(i => Map("id" -> i, "name" -> s"n$i")))
    val job = PolyConf.deserialize[TransformerJob[StreamData]](
      s"""{"CN":"TransformerJob","generator":{"CN":"SimpleDataGenerator","path":"${inFile.getAbsolutePath}","format":"json"},"transformers":[{"CN":"TransformerDebug","limit":5}],"writers":[{"CN":"SimpleDataWriter","path":"${outFile.getAbsolutePath}","format":"json"}]}"""
    )
    job.runTransformers.get
    job.perf.get("genRows").count shouldBe 20
    job.perf.get("tr1").count shouldBe 5
    job.perf.get("written").count shouldBe 5
    job.perf.get("wr1").count shouldBe 5
  }

  it should "track select columns transformer count" in {
    val inFile = tempFile("perf_in_")
    val outFile = tempFile("perf_out_")
    writeJson(inFile, (1 to 10).map(i => Map("id" -> i, "name" -> s"n$i", "age" -> (20 + i))))
    val job = PolyConf.deserialize[TransformerJob[StreamData]](
      s"""{"CN":"TransformerJob","generator":{"CN":"SimpleDataGenerator","path":"${inFile.getAbsolutePath}","format":"json"},"transformers":[{"CN":"TransformerDebug","columns":["id","name"]}],"writers":[{"CN":"SimpleDataWriter","path":"${outFile.getAbsolutePath}","format":"json"}]}"""
    )
    job.runTransformers.get
    job.perf.get("genRows").count shouldBe 10
    job.perf.get("tr1").count shouldBe 10 // select preserves row count
    job.perf.get("written").count shouldBe 10
  }

  it should "handle two transformers" in {
    val inFile = tempFile("perf_in_")
    val outFile = tempFile("perf_out_")
    writeJson(inFile, (1 to 10).map(i => Map("id" -> i, "name" -> s"n$i")))
    val job = PolyConf.deserialize[TransformerJob[StreamData]](
      s"""{"CN":"TransformerJob","generator":{"CN":"SimpleDataGenerator","path":"${inFile.getAbsolutePath}","format":"json"},"transformers":[{"CN":"TransformerDebug","limit":8},{"CN":"TransformerDebug","columns":["id"]}],"writers":[{"CN":"SimpleDataWriter","path":"${outFile.getAbsolutePath}","format":"json"}]}"""
    )
    job.runTransformers.get
    job.perf.get("genRows").count shouldBe 10
    job.perf.get("tr1").count shouldBe 8
    job.perf.get("tr2").count shouldBe 8
    job.perf.get("written").count shouldBe 8
  }

  it should "track zero rows from generator" in {
    val job = runSimpleJob(Seq.empty)
    job.perf.get("genRows").count shouldBe 0
    job.perf.get("written").count shouldBe 0
    job.perf.get("wr1").count shouldBe 0
  }

  it should "track two writers with same last-stage count" in {
    val inFile = tempFile("perf_in_")
    val outFile1 = tempFile("perf_out1_")
    val outFile2 = tempFile("perf_out2_")
    writeJson(inFile, (1 to 5).map(i => Map("id" -> i)))
    val job = PolyConf.deserialize[TransformerJob[StreamData]](
      s"""{"CN":"TransformerJob","generator":{"CN":"SimpleDataGenerator","path":"${inFile.getAbsolutePath}","format":"json"},"writers":[{"CN":"SimpleDataWriter","path":"${outFile1.getAbsolutePath}","format":"json"},{"CN":"SimpleDataWriter","path":"${outFile2.getAbsolutePath}","format":"json"}]}"""
    )
    job.runTransformers.get
    job.perf.get("genRows").count shouldBe 5
    job.perf.get("wr1").count shouldBe 5
    job.perf.get("wr2").count shouldBe 5
    job.perf.get("written").count shouldBe 5
  }

  it should "contain all expected metric keys" in {
    val job = runSimpleJob((1 to 3).map(i => Map("id" -> i)))
    val keys = job.perf.entries.keySet
    keys should contain("filesRead")
    keys should contain("genRows")
    keys should contain("written")
    keys should contain("wr1")
    keys should contain("totalTime")
  }

  it should "not contain filesFailed when all succeed" in {
    val job = runSimpleJob((1 to 3).map(i => Map("id" -> i)))
    // perf.get creates on absent -> returns 0, so check entries set
    job.perf.entries.get("filesFailed") shouldBe None
  }

  it should "NOT double-count stage when transformer produces multiple outputs" in {
    val splitter = new StreamTransformer[StreamData] {
      override def transform(input: StreamDataEnvelope[StreamData]): Iterator[StreamDataEnvelope[StreamData]] =
        input.data match {
          case scala.util.Failure(e) => Iterator(StreamDataEnvelope(scala.util.Failure(e), input.inputPath))
          case scala.util.Success(data) =>
            val (evens, odds) = data.data.zipWithIndex.partition(_._2 % 2 == 0)
            Iterator(
              StreamDataEnvelope(scala.util.Success(StreamData(evens.map(_._1))), input.inputPath),
              StreamDataEnvelope(scala.util.Success(StreamData(odds.map(_._1))), input.inputPath)
            )
        }
      override def help: String = "Test splitter"
    }

    val inFile = tempFile("perf_split_")
    val outFile = tempFile("perf_split_out_")
    writeJson(inFile, (1 to 10).map(i => Map("id" -> i, "name" -> s"n$i")))
    val job = PolyConf.deserialize[TransformerJob[StreamData]](
      s"""{"CN":"TransformerJob","generator":{"CN":"SimpleDataGenerator","path":"${inFile.getAbsolutePath}","format":"json"},"writers":[{"CN":"SimpleDataWriter","path":"${outFile.getAbsolutePath}","format":"json"}]}"""
    )
    // Replace transformer with splitter
    val splitJob = new TransformerJob[StreamData](
      generator = job.generator,
      transformers = Seq(splitter),
      writers = job.writers
    )
    splitJob.runTransformers.get

    // genRows should be 10 (not 20, which would indicate double-counting)
    splitJob.perf.get("genRows").count shouldBe 10
    // tr1 should be 10 (5+5, not 20 or 40 from double-counting)
    splitJob.perf.get("tr1").count shouldBe 10
    // written should be 10 (not 20)
    splitJob.perf.get("written").count shouldBe 10
  }
}
