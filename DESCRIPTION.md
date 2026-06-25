# polyconf

**Config-driven polymorphic class dispatch for Scala.**

## What it is

polyconf is a foundation library for building systems where the concrete implementation of a class is
determined at runtime from a JSON configuration string. Instead of hardcoding class instantiation,
you declare an abstract base, register its subclasses by name, and deserialize from `{"CN":"MyImpl", ...}`.

The library does **not** depend on Spark or any heavy framework — it's a lightweight toolkit for
configurable, polymorphic application design.

## Core idea

```scala
val config = """{"CN":"StdTransformer", "colFilter":"name", "repartitionMin":4}"""
val transformer: TransformBase = PolyConf.deserialize[TransformBase](config)
transformer.run(data)
```

The `CN` field selects which registered subclass to instantiate. All parameters from the JSON
string are deserialized into that class's fields via `FIELD, ANY` visibility — even `private val`
fields and constructor parameters are set directly by reflection. No setters or `var` declarations
are needed. Use `@JsonIgnore` to exclude a field from serialization.

**JSON initialization treats constructor parameters and `private val` fields identically** —
Jackson sets both the same way via field reflection. A `final case class SparkBasicTransformer(
sqlFilter: String = "")` and a `class SparkWriterImpl { private val path: String = "" }` are both
populated from JSON the same way: Jackson finds the backing field, ignores the access modifier or
whether it came from a constructor parameter, and writes the deserialized value directly. This is
why `private val` requires no special serialization annotations, and why case class constructor
params can be mixed with trait `val` fields in the same class hierarchy — Jackson does not
distinguish between them.

This JSON-as-object-definition approach makes it possible to build **flexible programs where real
objects are created purely from command-line or config-file input** — no recompilation, no plugin
JARs, no runtime classpath tricks. You define a pipeline, transformer, writer, or runner entirely
in a JSON/YAML string, feed it to the CLI, and the library constructs and executes the matching
classes. The same binary can run an infinite variety of jobs, each specified by a different config.

Because Jackson sets fields via reflection (not constructor args), **traits can define concrete vals
with defaults** and subclasses don't need to redeclare them. Example: `FileSource` provides
`path`/`format`/`options` — `SparkWriterImpl` mixes it in without redefining those fields.
This pattern keeps class hierarchies flat and avoids val duplication in JSON-deserialized classes.

## Design philosophy

The codebase follows three consistent design rules:

### 1. All external parameters as `val`, initialized from JSON config

Every class that needs external input (paths, format names, filter expressions, job config)
declares them as `val` fields with defaults. Jackson's `FIELD, ANY` visibility sets them
directly via reflection from the JSON/YAML config — no setters, no `var`, no constructor args:

```scala
// User writes this in YAML:
//   CN: SparkBasicTransformer
//   sqlFilter: "age > 25"
//   selectColumns: [name, age]
//   repartition: 4

// Class declares vals with defaults — Jackson fills them from JSON:
final case class SparkBasicTransformer(
    sqlFilter: String = "",
    selectColumns: Seq[String] = Seq.empty,
    repartition: Int = -1,
    cacheEnabled: Boolean = false
) extends StreamTransformer[DFData]
```

This works across the class hierarchy. A child class can inherit parent `val` defaults
and override them from JSON without redeclaring:

```scala
trait FileSource { self: PolyConf =>
  val path: String = ""
  val format: String = "text"
  val options: Map[String, String] = Map.empty
}

// SparkWriterImpl mixes in FileSource — path/format/options come from JSON config:
class SparkWriterImpl extends StreamWriter[DFData] with FileSource
// YAML: { "CN":"SparkWriterImpl", "path":"/out", "format":"json" }
//       → path="/out", format="json" set by Jackson reflection
```

Generators, transformers, writers, runners, format parameters — **every external parameter
is a `val` with a sensible default**, initialized entirely from the JSON config string.
No constructor injection, no builder pattern, no manual wiring.

### 2. Eliminate raw `try/catch/finally` — use `Try`, `withResource`, `withData`

Raw `try/catch/finally` blocks are avoided. Instead:

**`Try`** wraps fallible expressions, returning `Success`/`Failure`:
```scala
def write(input: StreamDataEnvelope[DFData]): Try[Unit] = Try {
  input.data.foreach { fd => fd.df.write.format("noop").save() }
}
```

**`withResource`** manages `AutoCloseable` resources:
```scala
// Instead of:
//   val file = new File(path)
//   try { writeData(file) } finally { file.close() }
PolyUtil.withResource(new PrintWriter(new File(path)))(writeFn).get

// Nested resources for Avro I/O:
PolyUtil.withResource(new DataFileReader[GenericRecord](file, datumReader)) { reader =>
  PolyUtil.withResource(new DataFileWriter[GenericRecord](datumWriter)) { writer =>
    // both reader and writer are auto-closed, even on exception
  }
}
```

**`withData`** generalizes `withResource` for non-`AutoCloseable` lifecycles:
```scala
def withData[A, B](builder: => A)(closer: A => Unit)(function: A => B): Try[B]
```

Key benefits:
- Every `Try` is a value: compose with `map`, `flatMap`, `getOrElse`, pattern match
- Exceptions never cross API boundaries silently — callers choose how to handle failures
- Resources never leak: close is guaranteed even if `function` throws
- Suppressed exceptions are handled correctly (unlike raw `finally` overriding the primary exception)

### 3. Minimize global state — prefer instance-level state over `object` vals

Global mutable state is kept to an absolute minimum. Where state is unavoidable, it is
hidden inside a class or wrapped in an `object` that acts as a singleton instance.

**What is global:**
- `PolyConfRegistry.registeredClasses` — `ConcurrentHashMap` managed by SPI providers
- `PolyAccStorage.accLong` / `.accPerf` — global named accumulator stores
- `LocalDataIORegistry.formats` / `SparkDataIO.registry` — format lookup tables
- `SparkLogRelay` — shared accumulator for distributed log capture

**What is NOT global:**
- Per-pipeline state (performance metrics, file failures, stage counts) lives inside
  the `TransformerJob` instance — fresh per job run
- Spark session state lives inside the `SparkSession` instance, created and stopped
  per job
- Log level configuration is passed through CLI params, not stored globally
- `PolySerde.jsonRecursiveMapper` is a `def` (not `val`), returning a fresh mapper
  each time — no shared mutable mapper state

The rationale: global mutable state makes parallel and distributed execution unsafe.
By keeping state inside job instances, the same process can run multiple pipelines
concurrently, and each Spark executor gets a clean copy.

## Design / architecture

### Auto-registration via SPI + classpath scanning

All concrete classes (runners, transformers, generators, writers, formats, parameter
generators) are **auto-discovered at startup** — no manual `register()` calls needed.

Each library JAR provides a `PolyConfProvider` implementation discovered via
`ServiceLoader` (SPI). A provider calls `registerAllChildForBases(...)` with its
abstract base classes; the framework scans the classpath URLs for concrete subclasses
and registers them automatically:

```
META-INF/services/org.polyconf.core.PolyConfProvider
  └── points to a class extending PolyConfProvider, e.g. CorePolyConfProvider

CorePolyConfProvider calls:
  PolyConfProvider.registerAllChildForBases(
    classOf[RunnerBase], classOf[EscapeFormatBase], classOf[ParamsGeneratorBase],
    classOf[JsonVariableBase], classOf[DataGeneratorBase[_]],
    classOf[DataTransformerBase[_]], classOf[DataWriterBase[_]],
    classOf[SuccessRecorderBase]
  )
  → scans classpath for all concrete subclasses of these bases
  → returns (concreteClasses, baseClasses) for registration
```

The scanner uses reflection on all `URLClassLoader` entries, filtering to
`org.polyconf.*` packages. Classes in dependency JARs and child modules
are found automatically — no manual class list, no annotation processing.

**Classloader limitation:** The scanner loads discovered classes using its own
classloader (`SubclassScanner.getClass.getClassLoader`). In modular environments
(Spark `--jars`, custom classloaders, container-based deployments), the polyconf-core
classloader may not have visibility to classes from child JARs. Using the base
class's classloader instead (`base.getClassLoader`) also fails in the common case
where the base (`PolyConf`) lives in polyconf-core but the concrete class lives in
a child JAR invisible to the core classloader. A complete fix would need to try
multiple classloaders (thread context, system, application) in order.

### Multi-module design

The library ships in two modules — polyconf-core (lightweight, no Spark) and
polyconf-spark (adds Spark runners and transformers):

```
polyconf-core (no heavy dependencies)
├── CorePolyConfProvider     — registers core bases
├── runner/transformer/writer/generator base traits
├── CliArgGenerator          — YAML → CLI arg conversion
├── CliRun                   — deserializes and runs any RunnerBase
└── PolyConfRegistry         — resolves CN → class at deserialization time

polyconf-spark (depends on polyconf-core + Spark)
└── SparkPolyConfProvider    — registers spark bases (CliSparkInit, etc.)
    └── concrete classes: SparkTransformerJob, SparkBasicTransformer, RunSparkParamsGenerator
```

Both providers are loaded at startup. A class registered as a base by one
provider and found as a concrete subclass by another (e.g. `CliSparkRunner` / `CliSparkInit`
is both `RunnerBase`'s concrete subclass and a base for `SparkTransformerJob`)
is handled correctly by the two-phase registration (bases first, concrete second).

### Adding a child library

To add custom runners, transformers, or generators from a separate module:

1. **Create a provider** extending `PolyConfProvider`:
   ```scala
   package com.example
   class MyProvider extends PolyConfProvider {
     private val (concrete, bases) = PolyConfProvider.registerAllChildForBases(
       classOf[RunnerBase],
       classOf[DataTransformerBase]
     )
     override def getConcreteClasses = concrete
     override def getBaseClasses     = bases
   }
   ```

2. **Register it** in `src/main/resources/META-INF/services/org.polyconf.core.PolyConfProvider`:
   ```
   com.example.MyProvider
   ```

3. **Place your concrete classes** anywhere in the same module's classpath.
   They are auto-discovered by the scanner.

No XML config, no annotation processor, no manual class roster. Any concrete
subclass of a registered base in the `org.polyconf` package hierarchy is
found automatically.

### Runtime discovery and usage

At CLI startup (`CliArgGenerator.generate()` or `CliRun.doInternal()`):
1. `ServiceLoader.load(classOf[PolyConfProvider])` discovers all providers
2. Each provider's base classes are registered (phase 1)
3. Each provider's concrete classes are registered (phase 2)
4. `PolyConf.deserialize[RunnerBase](json)` resolves the `CN` field against
   the registry and instantiates the correct class
5. Help commands enumerate all registered subclasses dynamically

### `::` map-path syntax

YAML and resource paths support a `::` suffix to select a top-level key:
```bash
./run.sh run.dev.args --yamlPath scripts/testArgs.yaml::myJob
```
This selects the `myJob` entry from the YAML file. When no `::` suffix is
given, `--mapPath` provides the key. The syntax avoids needing a separate
`--mapPath` argument in common cases.

### Defaults

- `--format shell` — output is a single shell-escaped command string
- `-l WARN` — log level is WARN by default (set by `CliArgGenerator.defaultLogRules`)

## Components

### PolyConf (polymorphic JSON serialization)

The heart of the library. A trait with a `CN` (className) field, plus a registry and custom Jackson
deserializer to dispatch `{"CN":"...", ...}` strings to the correct registered subclass.
Contrary to standard JSON deserializers, the code does not need to know the real class at
compile time — it is extracted from the `CN` name at runtime.

- `PolyConf` — trait for serializable config nodes
- `PolyConfRegistry` — registry of subclasses by name; supports help generation listing all
  registered implementations
- `PolyConf.deserialize[T](jsonString)` — instantiate class from JSON (with optional `verify()`)
- `PolyConf.serialize(obj)` — serialize object back to JSON

**getXxx caveat**: Jackson treats any `def getXxx(): T` as a JSON property `"xxx"` in serialized
output, but cannot set it during deserialization (no backing field/setter), causing
`UnrecognizedPropertyException`. Use `@JsonIgnore` on the getter to suppress it.

Use case: pipeline/transformer definitions, job configs, plugin systems — anywhere you need to
define a chain of operations in a config file without recompiling.

### CliArgGenerator (YAML → CLI argument converter)

**The second most important component.** The main drawback of providing `{"CN":"..."}` classes
on the command line is how hard it is to run a new job: you must hand-craft long, deeply nested
JSON strings, escape them correctly for the shell, and fix parameter mismatches through trial
and error — each change means re-escaping and re-testing the command line.

`CliArgGenerator` solves this completely. Instead of typing JSON on the CLI, you write a **flat
YAML definition**:

```yaml
myJob:
  Renamer:
    CN: JsonVariableInline
    Env:
      all:
        OUTPUT_DIR: /tmp/out
      local:
        SCRIPTS_DIR: scripts
      prod:
        SCRIPTS_DIR: /data/prod/scripts
    defaultEnv: local

  Formatter:
    CN: RunParamsGenerator
    shellPrefix: ./run.sh run.local
    jobStr:
      CN: RunnerShell
      cmd: ls /tmp/
```

The generator takes care of:
- **Escaping** — producing a properly shell-escaped command string (single-quote for POSIX, etc.)
- **Parameter construction** — building the classpath, Scallop `--flag value` pairs, and argument
  order from the YAML structure
- **Variable substitution** — the `Renamer.Env` section defines `$variable` references
  per environment (`local`, `prod`, etc.) with a `defaultEnv`. Replace them at
  generation time with `--renameStr` to run the **same** config against
  different environments without editing the file:

```bash
# Local run (defaultEnv: local):
./run.sh run.dev.args --yamlPath testArgs.yaml --mapPath myJob -l WARN

# Prod run — same config, different SCRIPTS_DIR:
./run.sh run.dev.args --yamlPath testArgs.yaml --mapPath myJob -l WARN \
  --renameStr Env--prod
```

The result is a single **executable string** — copy, paste, and run. No manual escaping, no
broken JSON in bash history, no trial-and-error with parameter quoting.

- `--yamlPath <file>` — YAML config file path
- `--resourcePath <name>` — YAML loaded from classpath resource (for bundled JAR deployments)
- `--mapPath <name>` — top-level map key to generate args for
- `--renameStr "<from>/--/<to>"` — variable substitution pairs
- `--format shell|yaml` — output format
- `--execute` — run the generated command directly instead of printing it (forces `--format shell`)

### RunnerBase (executable objects — third core component)

Any CLI job is a runner class with a single `run(): Try[Unit]` method, registered by name
so `PolyConf.deserialize[RunnerBase](json)` dispatches to the right implementation:

```scala
class MyJob extends RunnerBase {
  def run(): Try[Unit] = Try { /* do work */ }
}
```

**Adding a new executable requires only one runner class** — no parameter parsing, no CLI
infrastructure. The runner is deserialized from the `jobStr` field in the YAML config, and
`CliRun` calls `run()` on it.

If the built-in YAML→JSON dispatch is enough, you never need to write CLI code.

If you do want **custom user-friendly CLI parameters** (named flags, defaults, help text),
define a `CliXXX` object with its own `CliXXXParams` and implement `ParamsGeneratorBase`
for the job's JSON. The YAML config specifies the generator in the `Formatter:` section
via `CN: RunParamsGenerator` (the built-in implementation). Custom generators work the same way.

`CliArgGenerator` itself is built exactly as such a `CliXXX` object — it has user-friendly
`--yamlPath`, `--mapPath`, `--renameStr`, `--format` parameters, each with help text and defaults.

This means every new `CliXXX` executable automatically gets `CliArgGenerator` support:
its YAML config can be converted to CLI args by a generator as easily as any other job.

The naming convention:
- `CliXXX` — executable object with `def main(args: Array[String])`
- `CliXXXParams` — parameters class for that executable (e.g. `CliArgGeneratorParams` for `CliArgGenerator`)
- `CliParamsBase` — abstract base class for all `CliXXXParams` classes
- No `Cli` prefix on non-executable code

### PolySerde (JSON / YAML / Java serialization utilities)

Jackson-based JSON object mapper with Scala module, YAML loading via SnakeYAML, Java
serialization to/from Base64 strings, and nested map access helpers.

**Recursive serialization and visited-set design:** `jsonFormat(allowRecursion=true)` wraps
Jackson's bean serializer with `RecursiveJsonSerializer`, which tracks already-visited
objects in a `ThreadLocal[java.util.Set[AnyRef]]` (reference identity) to break cycles.
Objects are added to the set before the child serializer runs, and are never removed on
successful serialization. This is intentional: removing after each level would break
cross-recursion detection (parent B → child A → parent B would not be detected because
parent B would already be removed from the set when A's serializer returns). The visited
set is scoped to a single `RecursiveJsonSerializer` instance. Since `jsonRecursiveMapper`
is a `def` (not a `val`), each top-level call creates a fresh mapper with new serializer
instances, so the set does not persist across calls. A side effect is that shared object
references (non-cyclic) within a single tree are also treated as cycles and written as
`toString`. Cross-type cycles (A → B → A) are not detected because each type gets its
own `RecursiveJsonSerializer` with its own visited set.

### PolyNumFormat (number and time formatter)

Human-readable formatting of numbers and durations:

- `prettyNumber(1234567)` → `"1M 234K"`
- `prettyNanoTime(123456789000L)` → `"2m 3s"`

### PolyUtil (resource management, retry, wait)

- `withResource` / `withData` — safe AutoCloseable helpers returning `Try`
- `waitedGet[T]` — retry a block with exponential backoff
- `waitForCondition` — poll until a condition is true with configurable timeout

### PolyParallelRunner (parallel execution)

- `parallelRun` — execute function over inputs using fixed thread pool or fork-join
- `parallelOrderedRun` — same but maintain input order via atomic counter
- `retryJob` — retry a block with delay
- `waitUntil` — poll condition until timeout

### PolyLog (log4j configuration)

Dynamic log level adjustment at runtime, log4j 2.x native configuration via `log4j2.xml`.
Uses `%highlight{}` for ANSI colors and `(%F:%L)` for file:line in output.

### Setting log levels

Log levels are controlled by the `--logRules` / `-l` CLI flag, defined in `CliParamsBase` and
inherited by both `CliArgGeneratorParams` (Phase 1: YAML → command generation) and `CliRunParams`
(Phase 2: job execution). The flag accepts a comma-separated format:
`"ROOT_LEVEL,LOGGER_NAME:LEVEL,ANOTHER:LEVEL"`. Default: `"WARN,DEBUG:org.polyconf"`.

**From the CLI** — pass `-l` to any `run.args` or `run.local` invocation:
```bash
./run.sh run.dev.args --yamlPath scripts/testArgs.yaml::myJob -l DEBUG
./run.sh run.dev.args --yamlPath scripts/testArgs.yaml::myJob -l "WARN,TRACE:com.gsk"
```

**From YAML `additionalArgs`** — inject `--logRules` into the generated command so
the job process (Phase 2) receives it:
```yaml
myJob:
  Formatter:
    CN: RunParamsGenerator
    shellPrefix: ./run.sh run.local
    additionalArgs:
      - -l
      - DEBUG
    jobStr:
      CN: RunnerShell
      cmd: ls /tmp/
```

This produces: `./run.sh run.local -j '...' -l DEBUG`. CliRun's `CliParamsBase.verify()`
picks up `-l` and calls `PolyLog.setLogRules("DEBUG")`.

**In Spark jobs** — the `sparkConfig` map in `SparkTransformerJob` can carry
`spark.polyconf.logRules` for executor-side log capture. See `polyconf-spark/DESCRIPTION.md`.

### PolyTimer / PerformanceMessage (execution measurement)

Measure function execution time and print human-readable performance statistics (speed, count, time).

### PolyAccStorage (accumulators — thread-safe counters and perf tracking)

Named, thread-safe accumulators for collecting metrics across parallel workers:

- `PolyAccumulatorLong` — atomic long counter (add/get/copy/clear)
- `PolyAccumulatorBasic[V]` — generic accumulator with `add`/`get`/`copy`/`clear`
- `PolyAccStorage[V]` — registry of named accumulators, thread-safe via `Map.synchronized`
- `PolyAccumulatorPerf[V]` — self-referential accumulator for count + wall-clock time + calculated speed

`PolyAccumulatorPerf[V]` is self-referential: it extends `PolyAccumulatorBasic[PolyAccumulatorPerf[V]]`,
mirroring Spark's `AccumulatorV2.merge(other)` semantics. `add(other)` merges
count and time; `get` returns a snapshot `copy`. `toString` renders human-readable
metrics (items, wall-clock time, speed).

Usage:
```scala
PolyAccStorage.accLong.add("filesRead", 1)
PolyAccStorage.accLong.get("filesRead")  // => 1

PolyAccStorage.accPerf[Long].add("genRows", PolyAccumulatorPerf.withCount(1000))
PolyAccStorage.accPerf[Long].add("genRows", PolyAccumulatorPerf.withTime(500_000_000L))
PolyAccStorage.accPerf[Long].get("genRows")  // => PolyAccumulatorPerf(1000, 500ms)
// toString => "1K items in 500ms (Speed I/s 2.00e+03 s/I 500ms)"
```

The `TransformerJob` uses these internally for all pipeline metrics
(`genRows`, `filesRead`, `filesFailed`, `written`, `tr1`/`tr2`/..., `totalTime`),
but they can be used standalone in any multithreaded context.

## Dependencies

- Jackson (core, databind, module-scala, datatype-jsr310)
- Scallop (for CliParamsBase)
- SnakeYAML (for CliArgGenerator and Serde YAML loading)
- log4j 2.x (for PolyLog)
- Apache Avro + Parquet + Hadoop (for Avro/Parquet I/O formats)
- Scala parallel collections (for PolyParallelRunner)

## TransformerJob (generic streaming pipeline)

A config-driven data pipeline: **generator → transformers → writers**, parameterized over
data type `T <: TransformerDataBase`.

- `TransformerDataBase` — trait with `def rowCount: Long`; `DataStream` (core) and any future
  `DataFrameData` (Spark) extend it
- `TransformerData[T]` — pipeline unit wrapping `Try[T]` + input path; `mapData(f: T => T)` for
  transform chaining
- `TransformerJob[T]` — generic pipeline runner with perf accumulators, resilient error handling,
  success-recording, and life-cycle hooks
- `DataGeneratorBase[T]` — generic generator; default `readIterator(Option[SuccessRecorderBase])`
  so generators can pre-filter already-processed inputs before reading
- `DataTransformerBase[T]` — generic transformer; `transform(input): Iterator[TransformerData[T]]`
- `DataWriterBase[T]` — generic writer; `write(input): Try[Unit]`
- `TransformerException[T]` — aggregates all failed `TransformerData[T]` with paths and causes

### Pipeline lifecycle

```
TransformerJob.run()
  → init()       — optional pre-hook (clears success recorder if clearStatus=true)
  → generator.readIterator(successRecorderOpt)
      .filterNot(successRecorderOpt.exists(_.isSuccess(...)))   — skip already-recorded inputs
      .foreach { inputFileData ⇒
          → transformers.foldLeft(inputFileData)(transform)
          → writers.foreach(write)
          → successRecorder.record(inputFileData.inputPath)      — record success
      }
  → finalizeResults(result)   — optional post-hook (for Spark stats/logging)
  → print metrics
```

### Concrete implementations (core)

- `SimpleDataGenerator` — file/directory reader supporting formats (text, json, csv, avro, parquet);
  optional `rowsPerFile` splitting
- `TransformerDebug` — test transformer (select columns, filter, sort, limit)
- `SimpleDataWriter` / `VerifyDataWriter` — write output; `VerifyDataWriter` roundtrip-verifies
- `LocalFormat` (Text, Json, Csv, Avro, Parquet) — pluggable I/O via `LocalDataIORegistry`
- `DataStream` — immutable data container `Seq[Map[String, Any]]` + metadata; extends `TransformerDataBase`

### Success recording

`SuccessRecorderBase` is a `PolyConf` trait serializable via JSON config. `SuccessRecorder`
(case class) persists completed input paths to a file (`.success_log` by default):

- On load, reads all lines from the file into a mutable set
- `record(path)` appends and saves
- `isSuccess(path)` checks membership
- `clear()` empties the set and file

Because `SuccessRecorderBase extends PolyConf`, it round-trips through JSON config
(`Option[PolyConf]` serialization/deserialization is verified by `OptionPolyConfSpec`).
In `TransformerJob`, pass it as `successRecorderOpt: Option[SuccessRecorderBase]` in the
JSON config — no special serialization annotations needed.

Set `clearStatus: true` on `TransformerJob` to wipe the recorder before starting
a fresh run, preventing stale success data from skipping inputs.

### Extending for custom data types

The pipeline is fully generic. For a Spark integration:

```scala
class DataFrameData(df: DataFrame) extends TransformerDataBase {
  def rowCount: Long = df.count()
}

class SparkBasicTransformer extends DataTransformerBase[DataFrameData] {
  override def transform(input: TransformerData[DataFrameData]): Iterator[TransformerData[DataFrameData]] =
    input.mapData { case DataFrameData(df) => DataFrameData(df.filter(...)) }
}
```

### Help by type

`PolyConfRegistry.getHelpByType` groups registered generators, transformers, and writers
by their `T` type argument (extracted via `typeArgOf` reflection). The `TransformerJob.help`
command shows only compatible implementations for the job's `T`, making it easy to see
which generators/transformers/writers work together.

### Resilience

Individual write/transform failures are caught per-file, errors aggregated, and a single
`TransformerException` is thrown at the end with all failed paths. No single failure aborts
the whole job.

### Performance metrics

Built on `PolyAccStorage` / `PolyAccumulatorPerf` (see above).
Tracks counts and timing per metric key — `genRows`, `filesRead`, `filesFailed`,
`wr1`/`wr2` (per-writer), `tr1`/`tr2`/... (per-transformer), `totalTime` — with
human-readable formatting (items, time, speed).

## run.sh / test.sh (CLI entry points)

### run.sh — run or generate commands

```
./run.sh run.args ...       # CliArgGenerator (assembly JAR)
./run.sh run.local ...      # CliRun — run a job (assembly JAR)
./run.sh run.dev.args ...   # CliArgGenerator via sbt (no JAR)
./run.sh run.dev.local ...  # CliRun via sbt (no JAR)
```

Production mode requires `sbt assembly` first (builds the fat JAR).
Dev mode (`run.dev.*`) uses sbt directly — no JAR build needed.

The `::mapKey` suffix on `--yamlPath` selects a top-level key from the YAML:
```bash
./run.sh run.dev.args --yamlPath scripts/testArgs.yaml::shellTest
```
This selects the `shellTest` entry. Equivalent to `--yamlPath ... --mapPath shellTest`.

### test.sh — run all e2e tests

Executes 7 end-to-end tests defined in `scripts/testArgs.yaml`, each testing
a different job config or feature. Builds the assembly JAR first, then for
each test generates a command from YAML and executes it:

```bash
./test.sh                # run all, quiet mode
./test.sh --verbose      # show full command output
./test.sh --help         # show usage
```

**Test cases:**

| Test | Feature under test |
|------|-------------------|
| `shellTest (default)` | Basic `RunnerShell` command generation from YAML |
| `shellTest (rename)` | `--renameStr` literal path override |
| `transformerPipeline (default)` | Full `TransformerJob` pipeline execution |
| `transformerPipeline (ver env)` | `--renameStr Env--ver` environment selection |
| `transformerPipeline (override SCRIPTS_DIR)` | `--renameStr` variable override with file copy |
| `transformerPipeline (from resource)` | `--resourcePath` classpath loading |
| `shellTest (--execute)` | `--execute` flag (generate + run in one step) |

Each test:
1. Generates a command from YAML via `CliArgGenerator`
2. Extracts the generated `./run.sh run.local -j '...'` command
3. Executes it with `-l WARN` and checks exit code 0

Sample output when running the transformer pipeline:

```
19:24:28.078 [main] INFO  (TransformerJob.scala:47) runTransformers      - TransformerJob finished
  filesRead: 1
  genRows: 1K
  totalTime: 100 items, 24ms 422us (Speed I/s 4.09e+03 s/I 244us 223ns)
  tr1: 1K
  tr2: 100
  wr1: 100 items, 4ms 751us (Speed I/s 2.10e+04 s/I 47us 517ns)
  wr2: 100 items, 2ms 84us (Speed I/s 4.80e+04 s/I 20us 845ns)
  written: 100
```

Each metric line shows count, wall-clock time, and speed in items/second or seconds/item.
Per-transformer (`tr1`, `tr2`) and per-writer (`wr1`, `wr2`) are tracked independently.

## scripts/ (test data and config)

```
scripts/
├── input.csv        # 1000 rows of sample data (id, name, age, city)
├── test.json        # same 1000 rows in JSON format
└── testArgs.yaml    # CliArgGenerator config with 2 test entries
```

### testArgs.yaml entries

**shellTest** — a shell command runner with no Renamer section:
```yaml
shellTest:
  Formatter:
    CN: RunParamsGenerator
    shellPrefix: ./run.sh run.local
    jobStr:
      CN: RunnerShell
      cmd: ls /tmp/
```
Use `--renameStr` to override literal substrings in the generated JSON:
```bash
./run.sh run.dev.args --yamlPath scripts/testArgs.yaml::shellTest
./run.sh run.dev.args --yamlPath scripts/testArgs.yaml::shellTest \
  --renameStr "/tmp/--/Users/"
```

**transformerPipeline** — a data pipeline with env-based variable renaming:
```yaml
transformerPipeline:
  Renamer:
    CN: JsonVariableInline
    Env:
      all:
        OUTPUT_DIR: /tmp/pipeline-output
      local:
        SCRIPTS_DIR: scripts
      ver:
        SCRIPTS_DIR: scripts
        OUTPUT_DIR: /tmp/pipeline-verified
    defaultEnv: local

  Formatter:
    CN: RunParamsGenerator
    shellPrefix: ./run.sh run.local
    jobStr:
      CN: TransformerJob
      generator:
        CN: SimpleDataGenerator
        path: $SCRIPTS_DIR/input.csv
        format: csv
      transformers:
        - CN: TransformerDebug
          columns: [id, name, age]
        - CN: TransformerDebug
          sortColumns: [name]
          sortAsc: true
          limit: 100
      writers:
        - CN: SimpleDataWriter
          path: $OUTPUT_DIR/output.json
          format: json
        - CN: VerifyDataWriter
          path: $OUTPUT_DIR/output-verify.csv
          format: csv
```

Select environment (`local` is default, also `ver`, `sand`, `prod`):
```bash
./run.sh run.dev.args --yamlPath scripts/testArgs.yaml::transformerPipeline
./run.sh run.dev.args --yamlPath scripts/testArgs.yaml::transformerPipeline \
  --renameStr Env--ver     # select "ver" env → OUTPUT_DIR=/tmp/pipeline-verified
```

Override a specific variable:
```bash
./run.sh run.dev.args --yamlPath scripts/testArgs.yaml::transformerPipeline \
  --renameStr SCRIPTS_DIR--/tmp/custom   # overrides $SCRIPTS_DIR
```

The `Renamer` section is optional. When absent, an empty-prefix renamer is
created so `--renameStr` replaces literal substrings directly without `$`
variable prefix. Both entries can also load from classpath via `--resourcePath`:

## Tests (sbt test, 82 tests)

| Suite | Tests | What it covers |
|-------|-------|----------------|
| StreamSpec | 9 | TransformerJob pipelines (JSON, CSV), row splitting, multi-writer, sorted limit + verify roundtrip |
| PolyConfSpec | 18 | Registry registration/resolution, polymorphic serialization, help generation, round-trip, getXxx asymmetry, nested JSON, YAML loading |
| PolyUtilSpec | 14 | withResource/withData lifecycle, RetryPolicy delays, waitedGet retries, waitForCondition with timeout |
| PolySerdeSpec | 13 | replaceVars, JSON/YAML serde roundtrip, nested map access |
| CliArgGeneratorSpec | 7 | YAML config → CLI args, format selection, env rename, --execute |
| PolyAccStoreSpec | 5 | Accumulator add/copy/clear/get, PolyAccumulatorPerf self-referential merge |
| PolyParallelRunnerSpec | 5 | parallel/ordered runs, retryJob, waitUntil |
| PolyNumFormatSpec | 5 | prettyNumber, prettyNanoTime |
| CliParamsBaseSpec | 4 | Argument parsing, defaults, error handling, command description |
| OptionPolyConfSpec | 2 | `Option[SuccessRecorderBase]` serialization round-trip (Some/None) |

Run all: `sbt test`
Run single suite: `sbt "testOnly org.polyconf.StreamSpec"`

## How to use this library

Main use cases:

### 1. Define a custom transformer / writer — build a transformation pipeline

Extend the generic base traits and place them in a module with a `PolyConfProvider`:

```scala
// src/main/scala/com/example/UpperCaseTransformer.scala
class UpperCaseTransformer extends DataTransformerBase[DataStream] {
  override def transform(input: TransformerData[DataStream]): Iterator[TransformerData[DataStream]] =
    Iterator(input.mapData(data =>
      DataStream(data.data.map(row =>
        row.map { case (k, v) => k -> v.toString.toUpperCase }
      ))
    ))
}

// src/main/scala/com/example/AgeFilterWriter.scala
class AgeFilterWriter extends DataWriterBase[DataStream] {
  override def write(input: TransformerData[DataStream]): Try[Unit] = input.data.map { data =>
    val filtered = data.data.filter(_.get("age").exists(_.toString.toInt >= 18))
    println(s"${filtered.size} adult rows")
  }
}
```

No manual registration needed — these classes are auto-discovered via SPI +
classpath scanning as long as your module provides a `PolyConfProvider`.
See [Adding a child library](#adding-a-child-library) above.

Run from JSON (any JVM process with your JAR on the classpath):

```scala
val job = PolyConf.deserialize[TransformerJob[DataStream]]("""
  {"CN":"TransformerJob",
   "generator":{"CN":"SimpleDataGenerator","path":"data.csv","format":"csv"},
   "transformers":[{"CN":"UpperCaseTransformer"}],
   "writers":[{"CN":"AgeFilterWriter"}]}
""")
job.runTransformers.get
```

Or equivalently from a YAML config via `CliArgGenerator` — no Scala code needed
to run a different pipeline. Just change the YAML and re-run.

The same pattern applies to **custom generators** (`DataGeneratorBase[T]`) and **custom writers**
(`DataWriterBase[T]`). Implementing a custom generator lets you read from **remote databases,
GCS, S3, Kafka, or any other data source** — not just local files. Implementing a custom
writer lets you **stream results to Elasticsearch, Pub/Sub, BigQuery, or any external system**.
The pipeline framework handles threading, error aggregation, and performance tracking regardless
of where data comes from or goes.

For a different data type (e.g. Spark `DataFrame`), create a new `TransformerDataBase`
subclass and parameterize the pipeline with `T = DataFrameData`. See
[Extending for custom data types](#extending-for-custom-data-types) above.

### 2. Define a custom runner — get a new job with custom logic

Instead of running a shell command (`RunnerShell`), implement your own `RunnerBase`:

```scala
// src/main/scala/com/example/DbCleanupJob.scala
class DbCleanupJob extends RunnerBase {
  override def run(): Try[Unit] = Try {
    println("Cleaning stale records...")
    // custom logic here
  }
}
```

The class is auto-discovered if your module's `PolyConfProvider` lists
`classOf[RunnerBase]` in `registerAllChildForBases(...)`.

Reference it in the YAML config:

```yaml
cleanupJob:
  Formatter:
    CN: RunParamsGenerator
    shellPrefix: ./run.sh run.local
    jobStr:
      CN: DbCleanupJob
```

Then generate and execute the command:

```bash
./run.sh run.dev.args --yamlPath jobs.yaml --mapPath cleanupJob -l WARN | bash
```

No parameter classes, no CLI boilerplate — just a class with a `run()` method, a
`PolyConfProvider`, and its `CN` in the config.

## TransformerJob internals (detailed)

### Data type abstraction: `StreamDataBase`

The pipeline is parameterized over `T <: StreamDataBase`. This trait provides three critical
operations that differ between non-Spark and Spark data types:

```scala
trait StreamDataBase {
  def countPass: (StreamDataBase, () => Long)  // deferred row count
  def persist: this.type                       // cache in memory
  def unpersist: this.type                     // release cache
}
```

**`countPass`** returns a `(data, countFn)` pair:
- The data object (possibly wrapped with a counting layer)
- A `() => Long` function that will return the row count when called

For non-Spark (`StreamData`): count is immediate from `data.size.toLong`. The count function
can be called at any time and always returns the correct value.

For Spark (`DFData`): a new `LongAccumulator` is created and the DataFrame is wrapped with an
RDD `map` that increments the accumulator per row. The count function `() => acc.value` returns
the accumulator value. **Data must be consumed through a Spark action before the accumulator
holds the correct value** — this is why `countFn` is deferred and not called inline.

### `processFile()` — the core pipeline engine

```
For each input envelope (from generator):
  1. Call data.countPass → (genCounted, genFn)
  2. Prepend genCounted to the transform chain
  3. Fold over transformers:
     acc.flatMap { td =>
       val result = transformers(idx).transform(td)
       result.map { r =>
         r.mapData { d =>
           val (c, countFn) = d.countPass     ← keep countFn in stageCountFnBuilders
           c
         }
       }
     }
  4. For each transformed envelope in the foreach body:
     a. If multiple writers (>1), cache the counted data: counted.persist
        (prevents accumulator double-counting on subsequent writes)
     b. Call all writers with the counted data
     c. Release cache: counted.unpersist
     d. countFn() returns the row count for this envelope
  5. After all envelopes processed:
     - Evaluate stageCountFnBuilders → sums per-transformer index across all envelopes
     - Evaluate genFn() → total generator rows
     - Aggregate written = sum of envelope row counts
     - Log metrics
```

### Stage count collection (`stageCountFnBuilders`)

Each transformer in the pipeline can produce multiple output envelopes (fan-out). The stage
count functions are collected per transformer index:

```scala
val stageCountFnBuilders = transformers.indices.map(_ => List.newBuilder[() => Long])
```

In the foldLeft body, each `d.countPass._2` is appended to the builder for that transformer
index. After all envelopes are processed, the builders are evaluated:

```scala
val stageCounts = stageCountFnBuilders.map(_.result().map(_()).sum)
transformers.indices.foreach(i => perf.add(trRows(i), withCount(stageCounts(i))))
```

This correctly handles:
- Single-output transformers (one countFn per transformer)
- Multi-output transformers (multiple countFns summed per transformer index)
- Combined transformer + multi-writer pipelines (intermediate counts from the foldLeft,
  final counts from the foreach body)

### Multi-writer caching

When multiple writers are configured (writers.size > 1), the counted data is cached
BEFORE the first writer to avoid Spark accumulator re-incrementation:

```scala
if (writers.size > 1) counted.persist
```

Without this cache, each writer would trigger a separate evaluation of the counting RDD,
incrementing the accumulator by the row count for each writer. With the cache, the first
write evaluates and caches the result; subsequent writes read from cache without
re-evaluating the counting RDD.

When there is only one writer, no cache is created — eliminating unnecessary overhead.

### `genFn()` — deferred generator count

`genFn` is created at the start of `processFile` via `data.countPass._2`. It is evaluated
at the end of processing (after all envelopes, all writes) via `genFn()`. This ensures
Spark DF accumulators are populated before the count is read.

### Pipeline error handling

Individual failures (transform failure, write failure) are collected per-envelope.
The first failure is recorded; remaining envelopes continue processing. After all
envelopes, if any failure exists, it is thrown. This gives maximum throughput: a single
bad writer does not abort the whole file, and a single bad transform does not abort
the whole pipeline.

### Performance metrics

`TransformerJob` tracks these metrics via `PolyAccStorage[PolyAccumulatorPerf[Long]]`:

| Key | Source | Description |
|-----|--------|-------------|
| `filesRead` | In `processFile` on success | Count of input files processed |
| `filesFailed` | In `processFile` on failure | Count of input files that failed |
| `genRows` | `genFn()` after all envelopes | Total rows from generator |
| `tr1`, `tr2`, ... | `stageCountFnBuilders` after all envelopes | Rows output by each transformer |
| `wr1`, `wr2`, ... | Per-writer count from `writtenCount` | Rows consumed by each writer |
| `written` | Sum of `writtenCount` across envelopes | Total rows processed by pipeline |
| `totalTime` | `PolyTimer.measureNanoTime` around the whole run | Wall-clock execution time |

## Development workflow

### sbt commands

| Command | Description |
|---------|-------------|
| `sbt compile` | Compile main sources |
| `sbt test` | Run all 91 tests |
| `sbt "testOnly org.polyconf.StreamSpec"` | Run a single suite |
| `sbt "testOnly -- -z \"filter to given columns\""` | Run specific test by name substring |
| `sbt "testOnly org.polyconf.stream.TransformerJobPerfSpec"` | Run the pipeline perf spec |
| `sbt publishLocal` | Publish to local ivy cache (required after changes when polyconf-spark consumes it) |
| `sbt assembly` | Build fat JAR |
| `sbt "scalafmtCheck"` | Check code formatting |
| `sbt "scalafmtAll"` | Apply code formatting |

### Inter-project workflow

When making changes in polyconf that affect polyconf-spark:

1. Make changes in polyconf
2. `sbt test` — verify polyconf tests pass
3. `sbt publishLocal` — publish snapshot to local ivy cache
4. In polyconf-spark: `sbt test` — verify Spark tests pick up the new polyconf version

polyconf-spark depends on polyconf via the published artifact
(`"io.github.pyal" %% "polyconf" % "0.1.0-SNAPSHOT"`), not via source dependency.
Without `publishLocal`, polyconf-spark uses the stale previously-published jar.

### run.sh

| Command | Description |
|---------|-------------|
| `./run.sh run.args` | CliArgGenerator (assembly JAR) |
| `./run.sh run.local` | CliRun — run a job (assembly JAR) |
| `./run.sh run.dev.args` | CliArgGenerator via sbt (no JAR needed) |
| `./run.sh run.dev.local` | CliRun via sbt (no JAR needed) |

`::mapKey` suffix on `--yamlPath` selects a top-level key from the YAML.

### test.sh

Runs 7 end-to-end tests from `scripts/testArgs.yaml`. Builds assembly JAR first:
```bash
./test.sh                # quiet mode
./test.sh --verbose      # full output
./test.sh --help         # usage
```

## Cross-reference: polyconf-spark

The Spark integration module (`polyconf-spark`) adds these components:

- **`DFData`** implements `StreamDataBase` with a `DataFrame`:
  - `countPass` wraps the DF's RDD with a counting map + `LongAccumulator`
  - `persist` calls `df.cache()`
  - `unpersist` calls `df.unpersist()`
- **`SparkTransformerJob`** extends `TransformerJob[DFData]` with `CliSparkInit`
  (manages Spark session lifecycle)
- **`StreamDataAdapter`** converts between `StreamData` and `DataFrame`
- **Datasources:** `SparkFileDataIO`, `SparkBqIO`, `SparkPubsubIO`, `SparkEsIO`
- **Adapter layer:** `StreamDataGenerator`, `StreamDataTransformer`, `StreamDataWriter`
  wrap core `StreamData`-based components to work with `DFData`

See `polyconf-spark/DESCRIPTION.md` for full documentation.

## Structure

```
polyconf/
├── src/
│   ├── main/
│   │   ├── resources/
│   │   │   ├── log4j2.xml       # log4j2 config (included in JAR)
│   │   │   └── testArgs.yaml    # bundled for --resourcePath
│   │   └── scala/org/polyconf/
│   │       ├── core/            # PolyConf (+registry), PolySerde, PolyConfProvider
│   │       ├── cli/             # CliParamsBase, CliRun, RunnerBase (+RunnerShell)
│   │       │   └── stream/      # TransformerJob[T], DataStream, SuccessRecorderBase,
│   │       │                       LocalFormat, generators/transformers/writers
│   │       ├── argfmt/          # CliArgGenerator, ParamsGeneratorBase, JsonVariable*
│   │       └── util/            # PolyUtil, PolyParallelRunner, PolyLog, PolyTimer,
│   │                               PolyAccStorage, PolyAccumulatorPerf, PolyNumFormat
│   └── test/
│       ├── resources/
│       │   ├── input.csv        # 1000-row sample for tests
│       │   ├── log4j2-test.xml  # test log config
│       │   ├── testArg.yaml     # CliArgGenerator test config
│       │   └── testStream.yaml  # TransformerJob test config
│       └── scala/org/polyconf/  # test specs
├── scripts/          # Sample data + CliArgGenerator config
│   ├── input.csv
│   ├── test.json
│   └── testArgs.yaml
├── run.sh            # CLI entry point
├── test.sh           # Runs all examples from testArgs.yaml
└── DESCRIPTION.md    # This file
```
