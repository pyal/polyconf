# polyconf

Polyconf is a Scala framework for building **config-driven, polymorphic applications** —
programs where the concrete implementation of every component is determined at runtime
from a JSON or YAML configuration string. Instead of hardcoding class instantiation,
you declare abstract base traits, register all concrete subclasses, and let the config
choose which one to use.

A single YAML file defines a complex job. That same YAML can be executed in every
environment — local dev, verification, staging, production — and in every execution
mode — direct shell command, Spark cluster, workflow orchestrator — with environment-
specific parameters injected via command-line variables. No environment-specific
configs, no copy-paste, no manual edits per deployment.

## Core idea: `PolyConf`

`PolyConf` is a trait with a `CN` (className) field. When you deserialize a JSON string
like `{"CN":"MyImpl", ...}`, the framework looks up `MyImpl` in a registry of known
subclasses and instantiates the correct class, populating all its fields directly
from the JSON via Jackson reflection — no setters, no constructors, no manual wiring.

```scala
val config = """{"CN":"CsvToParquetJob", "inputPath":"/data/in", "outputPath":"/data/out"}"""
val job = PolyConf.deserialize[RunnerBase](config)
job.run()
```

Every external parameter is declared as a `val` with a sensible default. Jackson sets
them via field reflection, so a trait can define defaults and a child class inherits
them without redeclaration:

```scala
trait FileSource { self: PolyConf =>
  val path: String = ""
  val format: String = "text"
  val options: Map[String, String] = Map.empty
}

class MyWriter extends StreamWriter[DataStream] with FileSource
// YAML: {"CN":"MyWriter", "path":"/out", "format":"parquet", "options":{"header":"true"}}
```

## What it enables

### All CLI jobs as a single `RunnerBase.run()` method

Every executable in the framework implements the same interface:

```scala
trait RunnerBase {
  def run(): Try[Unit]
}
```

A runner can be a shell command executor, a data pipeline processor, a parameter
optimizer, a test harness — anything. The CLI entry point (`CliRun`) deserializes
the runner from the JSON config and calls `run()`. Adding a new executable requires
only one class with a `run()` method; no CLI parameter parsing, no infrastructure.

### `TransformerJob` — configurable data pipelines

The framework ships with a generic, reusable pipeline runner:

```
Generator → [Transformer, Transformer, ...] → [Writer, Writer, ...]
```

The pipeline is parameterized over its data type. A `TransformerJob[DataStream]`
reads data from a generator, runs it through a chain of transformers, and writes it
with one or more writers — all selected by the JSON/YAML config:

```yaml
myPipeline:
  CN: TransformerJob
  generator:
    CN: SimpleDataGenerator
    path: data.csv
    format: csv
  transformers:
    - CN: TransformerDebug
      columns: [id, name, age]
    - CN: UpperCaseTransformer
  writers:
    - CN: SimpleDataWriter
      path: /out/result.json
      format: json
    - CN: VerifyDataWriter
      path: /out/expected.csv
      format: csv
```

Each component (generator, transformer, writer) is an auto-discovered `PolyConf`
subclass, registered by its `CN`. Plug in different components by changing the
YAML — no code changes, no recompilation.

#### Format readers / writers for generators and writers

File formats are pluggable via `LocalDataIORegistry`. Built-in formats include:

| Format | Reader | Writer |
|--------|--------|--------|
| `text` | Line-by-line text | Text file |
| `json` | JSON array / line-delimited JSON | JSON file |
| `csv` | CSV with header, infer schema | CSV with header |
| `avro` | Avro file | Avro file |
| `parquet` | Parquet file | Parquet file |

Additional datasources (BigQuery, PubSub, Elasticsearch, Delta, etc.) are available
in the `polyconf-spark` module, which extends the pipeline to work with Spark
DataFrames — enabling cloud-scale processing while keeping the same config-driven
pipeline model.

#### Transformers — arbitrary data transformations

Transformers are `PolyConf` subclasses that implement a `transform` method.
Built-in transformers include:

- **`TransformerDebug`** — column selection, SQL-style filter, sort, limit
- **`UpperCaseTransformer`** — uppercase all string values

Custom transformers extend `DataTransformerBase[T]` and are auto-discovered via SPI:

```scala
class SqlFilterTransformer extends DataTransformerBase[DataStream] {
  val filterExpr: String = ""
  override def transform(input: TransformerData[DataStream]) =
    input.mapData(_.filter(row => evaluate(filterExpr, row)))
}
```

#### `VerifyDataWriter` — test verification

The `VerifyDataWriter` reads a pre-recorded expected output file and compares it
with the pipeline's actual output. If they differ, the job fails with a detailed diff
showing mismatched rows. This makes it possible to write **self-verifying integration
tests** entirely in YAML — no Scala test code needed.

### `CliArgGenerator` — solving the CLI escape-problem problem

The main practical challenge of config-driven programs is that passing complex JSON on
the command line requires tedious escaping — quotes inside quotes, nested braces, shell
interpretation. `CliArgGenerator` solves this completely.

Instead of typing JSON on the CLI, you write a **flat YAML definition**:

```yaml
myJob:
  Renamer:
    CN: JsonVariableInline
    Env:
      all:
        OUTPUT_DIR: /tmp/out
      local:
        OUTPUT_DIR: /tmp/out
      prod:
        OUTPUT_DIR: /data/prod/out
      ver:
        OUTPUT_DIR: /tmp/ver-out
    defaultEnv: local

  Formatter:
    CN: RunParamsGenerator
    shellPrefix: ./run.sh run.local
    jobStr:
      CN: RunnerShell
      cmd: ls /tmp/
```

The generator produces a properly shell-escaped command string. No manual escaping,
no broken JSON in bash history:

```bash
./run.sh run.dev.args --yamlPath jobs.yaml::myJob
```

#### Environment-specific variable substitution

The `Renamer.Env` section defines named environments with their own parameter values.
Select an environment at generation time:

```bash
# Default environment (defaultEnv: local):
./run.sh run.dev.args --yamlPath jobs.yaml::myJob

# Prod environment with a custom override on top:
./run.sh run.dev.args --yamlPath jobs.yaml::myJob --renameStr Env--prod~~OUTPUT_DIR--/tmp/custom-path
```

Pairs are separated by `~~` (double tilde), key and value by `--`.
Environment selection (`Env--<name>`) can be combined with individual
variable overrides — the override takes precedence over the env value.

#### Extensible escape formats

`EscapeFormatBase` defines how the generated command string is encoded for different
target environments:

- **`ShellEscapeFormat`** — single-quote escaping for POSIX shells
- **`YamlEscapeFormat`** — YAML-safe encoding for embedding in workflow files
- Custom formats can be registered for any target (e.g. BigQuery query strings)

#### Generate or execute

`CliArgGenerator` can either **print** the generated command (for use in a terminal
or script — pipe it or capture it to run the job later) or **execute it inline**
with `--execute`, which runs the command directly.

### Log level configuration per path

Log levels are controlled by the `--logRules` / `-l` flag. The format is
`ROOT_LEVEL,LOGGER_NAME:LEVEL` — set root level and per-logger overrides:

```bash
# All polyconf logs at DEBUG:
./run.sh run.dev.args --yamlPath jobs.yaml::myJob -l DEBUG

# WARN root, TRACE for polyconf core:
./run.sh run.dev.args --yamlPath jobs.yaml::myJob -l "WARN,TRACE:org.polyconf.core"
```

In YAML, inject `--logRules` into the generated command via `additionalArgs`:

```yaml
myJob:
  Formatter:
    CN: RunParamsGenerator
    shellPrefix: ./run.sh run.local
    additionalArgs:
      - -l
      - "WARN,DEBUG:org.polyconf"
    jobStr:
      CN: TransformerJob
      generator:
        CN: SimpleDataGenerator
        path: data.csv
        format: csv
      transformers:
        - CN: TransformerDebug
          columns: [id, name, age]
      writers:
        - CN: SimpleDataWriter
          path: /out/result.json
          format: json
```

### Spark integration for big data

The `polyconf-spark` module extends the pipeline to work with Spark DataFrames:

- **`DFData`** — wraps a `DataFrame`, provides deferred row counting via `LongAccumulator`
- **`SparkTransformerJob`** — `TransformerJob[DFData]` with automatic Spark session management
- **`SparkBasicTransformer`** — DataFrame filter, select, repartition, cache
- **`SparkGeneratorImpl`** / **`SparkWriterImpl`** — file and datasource I/O with Spark
- **Datasources:** BigQuery, PubSub, Elasticsearch, Delta, Parquet, Avro, CSV, JSON, Text

The same YAML-driven pipeline model works unchanged — swap in Spark-native generators,
transformers, and writers for cloud-scale data processing.

### Worker log capture

`SparkLogRelay` captures log events from Spark executors and transfers them to the
driver via a `CollectionAccumulator`. Each executor's log output is collected into
a Spark accumulator, merged, and printed on the driver — giving visibility into
distributed execution without per-worker SSH access.

## Registration via SPI and classpath scanning

All concrete classes are auto-discovered at startup. No manual `register()` calls.

Each module provides a `PolyConfProvider` implementation discovered via Java
`ServiceLoader`. The provider calls `registerAllChildForBases(...)` with its
abstract base classes, and the framework scans the classpath URLs for concrete
subclasses:

```
META-INF/services/org.polyconf.core.PolyConfProvider
  └── points to a class extending PolyConfProvider
```

The scanner uses reflection on all `URLClassLoader` entries, filtering to the
relevant package namespace. Classes from dependency JARs are found automatically.

## `run.sh` — CLI entry point

`run.sh` dispatches to four modes, each taking the same arguments:

| Command | Mode | Purpose | How it runs |
|---------|------|---------|-------------|
| `run.args` | Production | Generate CLI args from YAML — prints the generated command | `java -cp assembly.jar org.polyconf.argfmt.CliArgGenerator` |
| `run.local` | Production | Execute a job from generated JSON — runs the pipeline | `java -cp assembly.jar org.polyconf.cli.run.CliRun` |
| `run.dev.args` | Development | Generate CLI args from YAML (via sbt, no JAR generation) | `sbt runMain org.polyconf.argfmt.CliArgGenerator` |
| `run.dev.local` | Development | Execute a job from generated JSON (via sbt, no JAR generation) | `sbt runMain org.polyconf.cli.run.CliRun` |

**Production** modes require `sbt assembly` first (builds the fat JAR).
**Development** modes use sbt directly — no JAR build needed.

Common usage:

```bash
# Generate a CLI command from YAML (inspect or pipe to terminal/script):
./run.sh run.dev.args --yamlPath jobs.yaml::myJob

# Generate + pipe into bash to execute:
./run.sh run.dev.args --yamlPath jobs.yaml::myJob | bash

# Generate and execute in one step:
./run.sh run.dev.args --yamlPath jobs.yaml::myJob --execute

# Run a job directly from a JSON string (via sbt):
./run.sh run.dev.local -j '{"CN":"RunnerShell","cmd":"ls /tmp/"}' -l WARN

# Production mode (requires sbt assembly first):
./run.sh run.args --yamlPath jobs.yaml::myJob
./run.sh run.local -j '{"CN":"RunnerShell","cmd":"ls /tmp/"}' -l WARN
```

The `::myJob` suffix selects the top-level key from the YAML file.
Equivalent to `--yamlPath jobs.yaml --mapPath myJob`.

## `test.sh` — end-to-end integration tests

```bash
./test.sh                # run all, quiet mode
./test.sh --verbose      # show full command output
./test.sh --help         # show usage
```

Builds the assembly JAR, then runs 7 test definitions from `scripts/testArgs.yaml`:

| Test | What it tests |
|------|---------------|
| `shellTest (default)` | Basic `RunnerShell` command generation from YAML |
| `shellTest (rename /tmp/ to /Users/)` | `--renameStr` literal path override |
| `transformerPipeline (default, local env)` | Full `TransformerJob` pipeline execution |
| `transformerPipeline (ver env)` | `--renameStr Env--ver` environment selection |
| `transformerPipeline (override SCRIPTS_DIR)` | Variable override with file copy |
| `transformerPipeline (from resource)` | `--resourcePath` classpath loading instead of file |
| `shellTest (--execute)` | `--execute` flag (generate + run in one step) |

Each test:
1. Generates a command from YAML via `CliArgGenerator`
2. Extracts the resulting shell command
3. Executes it with `-l WARN` and checks exit code 0

## Build

```bash
sbt test          # 91 unit tests
sbt assembly      # fat JAR
sbt publishLocal  # publish for polyconf-spark consumption
```

## Module structure

```
polyconf/                      # core library (no Spark dependency)
├── core/                      # PolyConf, PolySerde, registry, SPI
├── argfmt/                    # CliArgGenerator, variable renaming, escape formats
├── cli/                       # RunnerBase, CliRun, CliParamsBase
│   └── stream/                # TransformerJob, DataStream, generators, transformers, writers
└── util/                      # PolyUtil, PolyLog, PolyTimer, PolyAccStorage, PolyParallelRunner

polyconf-spark/                # Spark integration (separate repo)
├── core/                      # CliSparkInit, SparkSessionInit, SparkLogRelay, SparkAccStore, SparkPolyConfProvider
├── datasource/                # SparkDataIO, SparkFileDataIO, SparkBqIO, SparkPubsubIO, SparkEsIO
└── stream/                    # DFData, SparkTransformerJob, SparkGeneratorImpl, SparkBasicTransformer, SparkWriterImpl, SparkStatsWriter, StreamDataAdapter
```
