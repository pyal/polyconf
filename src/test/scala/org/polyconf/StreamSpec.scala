package org.polyconf

import org.polyconf.argfmt.JsonVariableInline
import org.polyconf.cli.stream._
import org.polyconf.core.{PolyConf, PolySerde}
import org.polyconf.core.PolyConf.jsonBasicMapper

import java.io.{File, PrintWriter}
import org.polyconf.util.PolyUtil
import scala.io.Source

class StreamSpec extends AccStoreFixture {

  private val yaml = PolySerde.loadYamlResource("testStream.yaml")

  private val sharedData =
    yaml("data").asInstanceOf[Seq[Map[String, Any]]]
  private def writeJson(f: File, data: Seq[Map[String, Any]]): Unit =
    PolyUtil.withResource(new PrintWriter(f))(_.write(PolySerde.jsonFormat(data))).get

  private def tempFile(prefix: String, suffix: String = ".json"): File = {
    val f = File.createTempFile(prefix, suffix)
    f.deleteOnExit()
    f
  }

  private def getJob(name: String): TransformerJob[StreamData] = {
    val config = PolySerde
      .getNestedValue[Map[String, Any]](yaml, Seq("tests", name))

    val inFile = tempFile("stream_in_")
    writeJson(inFile, sharedData)

    val outFile = tempFile("stream_out_")

    val json = JsonVariableInline.replaceVars(
      PolySerde.jsonFormat(config),
      Map("INPUT_PATH" -> inFile.getAbsolutePath, "OUTPUT_PATH" -> outFile.getAbsolutePath),
      ""
    )
    PolyConf.deserialize[TransformerJob[StreamData]](json)
  }

  private def runJob(name: String): StreamData = {
    val trJob = getJob(name)
    trJob.runTransformers.get
    val outPath = trJob.writers.collectFirst {
      case w: FileSource => w.path
    }.get
    val content = PolyUtil.withResource(Source.fromFile(outPath))(_.mkString).get
    StreamData(
      jsonBasicMapper.readValue(content, classOf[Seq[Map[String, Any]]])
    )
  }

  "TestTransformer" should "filter to given columns" in {
    val res = runJob("selectColumns")
    res.data.foreach(_.keys should contain only ("name", "age"))
  }

  it should "limit rows" in {
    runJob("limitRows").data.size shouldBe 3
  }

  it should "sort by columns" in {
    val res = runJob("sortByName")
    res.data.map(_("name")) shouldBe Seq("Alice", "Bob", "Charlie", "Diana", "Eve")
  }

  it should "filter by hash residual" in {
    val res = runJob("hashFilter")
    res.data.foreach(r => (r("name").hashCode.abs % 2) shouldBe 0)
  }

  it should "combine multiple transforms" in {
    val res = runJob("combined")
    res.data.size shouldBe 3
    res.data.foreach(_.keys should contain only ("name", "age", "id"))
    res.data.map(_("age")) shouldBe Seq(25, 28, 30)
  }

  "VerifyDataWriter" should "verify roundtrip" in {
    noException should be thrownBy runJob("verifyRoundtrip")
  }

  "CsvSplit" should "split single file into chunks of rowsPerFile" in {
    val csvFile = tempFile("csv_split_", ".csv")
    PolyUtil.withResource(new PrintWriter(csvFile)) { pw =>
      pw.println("id,val")
      (1 to 10).foreach { i => pw.println(s"$i,v$i") }
    }.get

    val outFile = tempFile("csv_split_out_", ".json")

    val config = PolySerde.getNestedValue[Map[String, Any]](yaml, Seq("tests", "csvSplit"))
    val json = JsonVariableInline.replaceVars(
      PolySerde.jsonFormat(config),
      Map("INPUT_PATH" -> csvFile.getAbsolutePath, "OUTPUT_PATH" -> outFile.getAbsolutePath),
      ""
    )
    val trJob = PolyConf.deserialize[TransformerJob[StreamData]](json)
    trJob.runTransformers.get

    // 10 rows, rowsPerFile=3 -> 4 splits (3+3+3+1), each overwrites the output file
    // Last chunk has 1 row (id=10)
    val content = PolyUtil.withResource(Source.fromFile(outFile))(_.mkString).get
    val res = jsonBasicMapper.readValue(content, classOf[Seq[Map[String, Any]]])
    res.size shouldBe 1
    res.head("id") shouldBe "10"
  }

  "CsvMultiWriter" should "process 1000 CSV rows through 2 transformers to 2 writers" in {
    val csvFile = tempFile("csv_test_", ".csv")
    PolyUtil.withResource(new PrintWriter(csvFile)) { pw =>
      pw.println("id,name,age,city")
      (1 to 1000).foreach { i =>
        pw.println(s"$i,name_$i,${i % 100},city_${i % 20}")
      }
    }.get

    val outFile1 = tempFile("csv_out1_", ".json")
    val outFile2 = tempFile("csv_out2_", ".json")

    val config = PolySerde
      .getNestedValue[Map[String, Any]](yaml, Seq("tests", "csvMultiWriter"))
    val json = JsonVariableInline.replaceVars(
      PolySerde.jsonFormat(config),
      Map(
        "INPUT_PATH"  -> csvFile.getAbsolutePath,
        "OUTPUT_PATH1" -> outFile1.getAbsolutePath,
        "OUTPUT_PATH2" -> outFile2.getAbsolutePath,
      ),
      ""
    )
    val trJob = PolyConf.deserialize[TransformerJob[StreamData]](json)
    trJob.runTransformers.get

    val content1 = PolyUtil.withResource(Source.fromFile(outFile1))(_.mkString).get
    val content2 = PolyUtil.withResource(Source.fromFile(outFile2))(_.mkString).get
    val res1 = jsonBasicMapper.readValue(content1, classOf[Seq[Map[String, Any]]])
    val res2 = jsonBasicMapper.readValue(content2, classOf[Seq[Map[String, Any]]])
    res1.size shouldBe 100
    res2.size shouldBe 100
    res1.foreach(_.keys should contain only ("name", "age"))
    res2.foreach(_.keys should contain only ("name", "age"))
    res1 shouldBe res2
  }

  "CsvSortedVerify" should "read from scripts input.csv, drop city, sort by name limit 100, write+verify" in {
    val scriptsCsv = getClass.getResource("/input.csv").getPath
    val outFile1 = tempFile("csv_sorted_out1_", ".json")
    val outFile2 = tempFile("csv_sorted_out2_", ".csv")

    val config = PolySerde
      .getNestedValue[Map[String, Any]](yaml, Seq("tests", "csvSortedVerify"))
    val json = JsonVariableInline.replaceVars(
      PolySerde.jsonFormat(config),
      Map(
        "SCRIPTS_CSV"  -> scriptsCsv,
        "OUTPUT_PATH1" -> outFile1.getAbsolutePath,
        "OUTPUT_PATH2" -> outFile2.getAbsolutePath,
      ),
      ""
    )
    val trJob = PolyConf.deserialize[TransformerJob[StreamData]](json)
    trJob.runTransformers.get

    val content1 = PolyUtil.withResource(Source.fromFile(outFile1))(_.mkString).get
    val res1 = jsonBasicMapper.readValue(content1, classOf[Seq[Map[String, Any]]])
    res1.size shouldBe 100
    res1.foreach(_.keys should contain only ("id", "name", "age"))
    val names = res1.map(_("name").toString)
    names shouldBe names.sorted
  }
}
