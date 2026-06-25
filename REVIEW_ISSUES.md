# Code Review Issues — redButtonLib (polyconf)

## Issues Found: 35

---

### ISSUE #1 — HIGH — `Class[?]` invalid in Scala 2.13
- **File:** `src/main/scala/org/polyconf/core/PolySerde.scala:25`
- **Problem:** `Set[Class[?]]` — `?` is Java-style wildcard, invalid in Scala 2.13. Scala uses `_`.
- **Fix:** Change to `Set[Class[_]]`

### ISSUE #2 — HIGH — `resolveClass` throws opaque NoSuchElementException
- **File:** `src/main/scala/org/polyconf/core/PolyConf.scala:36-37`
- **Problem:** `registeredClasses(className)` — raw `Map.apply` throws `NoSuchElementException` with no message if key is absent.
- **Fix:** Use `registeredClasses.getOrElse(className, ...)` with a meaningful error.

### ISSUE #3 — MEDIUM — `ObjectMapper.copy()` on every deserialize call
- **File:** `src/main/scala/org/polyconf/core/PolyConf.scala:124-137`
- **Problem:** `addDeserializer` calls `mapper.copy().registerModule(module)` on every deserialize.
- **Fix:** Build the mapper once and reuse it.

### ISSUE #4 — MEDIUM — `registeredClasses` / `registeredBaseClasses` not thread-safe
- **File:** `src/main/scala/org/polyconf/core/PolyConf.scala:16-18`
- **Problem:** `mutable.Map` and `mutable.Set` are not thread-safe; `PolyConfRegistry` is a singleton accessed from multiple threads.
- **Fix:** Use `ConcurrentHashMap` or `synchronized` blocks.

### ISSUE #5 — MEDIUM — `jsonRecursiveMapper` is a `def` (creates new ObjectMapper every call)
- **File:** `src/main/scala/org/polyconf/core/PolySerde.scala:33-35`
- **Problem:** `def jsonRecursiveMapper` creates a new `ObjectMapper` + `SimpleModule` + serializer modifier on every call.
- **Fix:** Change to `lazy val`.

### ISSUE #6 — HIGH — `RecursiveJsonSerializer` memory leak (set never cleared)
- **File:** `src/main/scala/org/polyconf/core/PolySerde.scala:69, 72-79`
- **Problem:** `visited: mutable.Set[AnyRef]` grows unbounded — references to every serialized object retained forever.
- **Fix:** Use thread-local `WeakHashMap`-backed identity set, or clear `visited` after each top-level serialization call.

### ISSUE #7 — MEDIUM — Resource leak: `javaSerialize` stream not closed on exception
- **File:** `src/main/scala/org/polyconf/core/PolySerde.scala:83-88`
- **Problem:** `oos.close()` is never called if `oos.writeObject(obj)` throws.
- **Fix:** Use try-finally.

### ISSUE #8 — MEDIUM — Resource leak: `javaDeserialize` stream not closed on exception
- **File:** `src/main/scala/org/polyconf/core/PolySerde.scala:91-99`
- **Problem:** `ois.close()` not guaranteed if `readObject()` or `decode()` throws.
- **Fix:** Use try-finally.

### ISSUE #9 — HIGH — Resource leak: `loadYamlConfig` never closes `Source` / `InputStream`
- **File:** `src/main/scala/org/polyconf/core/PolySerde.scala:139`
- **Problem:** `Source.fromInputStream(loader.getResourceAsStream(configName)).mkString` — `Source` is never closed.
- **Fix:** Use `PolyUtil.withResource` or try-finally.

### ISSUE #10 — LOW — `loadYamlString` roundtrips through JSON (inefficient)
- **File:** `src/main/scala/org/polyconf/core/PolySerde.scala:148-149`
- **Problem:** YAML → Java Map → JSON → Scala Map (2 unnecessary serialization passes).
- **Fix:** Convert the Java Map directly without JSON detour.

### ISSUE #11 — MEDIUM — Repeated unchecked casts (`asInstanceOf[T]`) throughout serialization
- **File:** `src/main/scala/org/polyconf/core/PolySerde.scala:110-132`
- **Problem:** `jsonToMap` returns `Map[String, Any]`, then `getNestedValue`/`getNestedValueTry` use `asInstanceOf[T]` — produces cryptic `ClassCastException` on failure.
- **Fix:** Use typed JSON parsing or pattern-match with descriptive error messages.

### ISSUE #12 — HIGH — `NumFormat.format` undefined behavior for `Long.MinValue`
- **File:** `src/main/scala/org/polyconf/fmt/NumFormat.scala:25`
- **Problem:** `Math.abs(Long.MinValue)` returns `Long.MinValue` (still negative) due to two's complement overflow.
- **Fix:** Handle `Long.MinValue` as special case, or use `BigInt`.

### ISSUE #13 — MEDIUM — `RetryPolicy` is a case class with mutable `var` fields
- **File:** `src/main/scala/org/polyconf/util/PolyUtil.scala:9-13`
- **Problem:** `var` fields in case class makes `equals`/`hashCode`/`copy` unpredictable.
- **Fix:** Make immutable or use regular class.

### ISSUE #14 — MEDIUM — `withResource` swallows original exception if `close()` also fails
- **File:** `src/main/scala/org/polyconf/util/PolyUtil.scala:29-34`
- **Problem:** If `function(x)` throws and `close()` also throws, the original exception is lost.
- **Fix:** Use `Throwable.addSuppressed` or `Try` chaining.

### ISSUE #15 — MEDIUM — `RetryPolicy.nextDelayTime` modifies state and returns value in same call
- **File:** `src/main/scala/org/polyconf/util/PolyUtil.scala:14-24`
- **Problem:** `nextDelayTime` mutates `retryLimit` and `waitMs` as side effects — calling it twice consumes retry budget twice.
- **Fix:** Return `(Option[Int], RetryPolicy)` (immutable approach).

### ISSUE #16 — HIGH — `RunnerShell.cmd` hardcoded default `"ls -h /"`
- **File:** `src/main/scala/org/polyconf/cli/RunnerShell.scala:23`
- **Problem:** Default command executes `ls -h /` if `RunnerShell` instantiated without PolyConf.
- **Fix:** Use `""` as default and validate with `require`.

### ISSUE #17 — MEDIUM — `TransformerException` extends `Throwable` directly (not `Exception`/`RuntimeException`)
- **File:** `src/main/scala/org/polyconf/cli/TransformerJobBase.scala:38`
- **Problem:** Extending `Throwable` directly is unconventional; many frameworks only catch `Exception`.
- **Fix:** Extend `RuntimeException` instead.

### ISSUE #18 — MEDIUM — `FileSource` trait has concrete `val` defaults with empty strings
- **File:** `src/main/scala/org/polyconf/cli/TransformerJobBase.scala:9-11`
- **Problem:** `val path: String = ""` — empty string silently passes validation.
- **Fix:** Make `path` abstract (no default).

### ISSUE #19 — LOW — `RunnerStream` is a case class with mutable pipeline
- **File:** `src/main/scala/org/polyconf/cli/RunnerStream.scala:10-14`
- **Problem:** Case class equality/hashCode on mutable components is dangerous.
- **Fix:** Make it a regular class.

### ISSUE #20 — HIGH — `SimpleDataGenerator.readIterator` — NPE from `file.listFiles()`
- **File:** `src/main/scala/org/polyconf/cli/DataGeneratorImpl.scala:20`
- **Problem:** `file.listFiles()` returns `null` on I/O error per Java docs, causing NPE.
- **Fix:** Use `Option(file.listFiles()).getOrElse(Array.empty)`.

### ISSUE #21 — HIGH — Multiple resource leaks in Avro/Parquet I/O
- **Files:** `src/main/scala/org/polyconf/cli/FormatUtils.scala:49-66, 71-82, 91-108, 117-127`
- **Problem:** `DataFileReader`/`DataFileWriter`/`ParquetReader`/`ParquetWriter` never closed in finally blocks.
- **Fix:** Wrap in try-finally or use `PolyUtil.withResource`.

### ISSUE #22 — HIGH — Resource leak: `LocalTextFormat.read` / `LocalJsonFormat.read` never close `Source`
- **Files:** `src/main/scala/org/polyconf/cli/LocalFormat.scala:16, 40`
- **Problem:** `Source.fromFile(path)` is never closed.
- **Fix:** Close the Source in a finally block.

### ISSUE #23 — MEDIUM — Resource leak: `ArgFormatter.main` never closes `Source`
- **File:** `src/main/scala/org/polyconf/argfmt/ArgFormatter.scala:46`
- **Problem:** `Source.fromFile(params.yamlPath()).mkString` — `Source` is never closed.
- **Fix:** Use try-finally or `withResource`.

### ISSUE #24 — MEDIUM — `parallelRun`/`parallelOrderedRun` calls `shutdown()` without `awaitTermination()`
- **File:** `src/main/scala/org/polyconf/util/ParallelOps.scala:34, 57`
- **Problem:** `shutdown()` returns immediately; mid-execution tasks may be interrupted.
- **Fix:** Add `awaitTermination()`.

### ISSUE #25 — MEDIUM — `parallelOrderedRun` doesn't handle `InterruptedException` or `ExecutionException`
- **File:** `src/main/scala/org/polyconf/util/ParallelOps.scala:83-84`
- **Problem:** `.get()` throws unchecked without handling.
- **Fix:** Wrap in `Try` or catch explicitly.

### ISSUE #26 — LOW — `ParallelOps.waitUntil` uses `retryCount = -1` for infinite retries
- **File:** `src/main/scala/org/polyconf/util/ParallelOps.scala:96`
- **Problem:** Negative `retryLimit` overloads semantics, making code harder to understand.
- **Fix:** Use a separate `infinite` flag.

### ISSUE #27 — LOW — `ParallelOps.retryJob` error is discarded on first `waitedGet` failure
- **File:** `src/main/scala/org/polyconf/util/ParallelOps.scala:89-90`
- **Problem:** Double-wrapping in `Try` + `waitedGet` is redundant.
- **Fix:** Simplify retry logic.

### ISSUE #28 — MEDIUM — `PolyLog.clearLoggers` may cause ConcurrentModificationException
- **File:** `src/main/scala/org/polyconf/util/PolyLog.scala:58-60`
- **Problem:** Iterating over logger map while removing entries with `removeLogger`.
- **Fix:** Collect names first, then remove.

### ISSUE #29 — LOW — `PolyTimer` mixes `AtomicLong` with `synchronized` blocks inconsistently
- **File:** `src/main/scala/org/polyconf/util/PolyTimer.scala:82-103`
- **Problem:** Inconsistent threading strategy.
- **Fix:** Choose one strategy and apply consistently.

### ISSUE #30 — MEDIUM — `PolyConfRegistry.getHelpBase` creates real deserialized objects for help generation
- **File:** `src/main/scala/org/polyconf/core/PolyConf.scala:46-48`
- **Problem:** Help generation runs constructors and `verify()` with side effects.
- **Fix:** Use `cl.getDeclaredConstructor().newInstance()` or extract metadata without instantiation.

### ISSUE #31 — LOW — Test registers `TransformBase` twice as base class
- **File:** `src/test/scala/org/polyconf/PolyConfSpec.scala:22-24, 31-33`
- **Problem:** Redundant registration; second test doesn't actually test anything.
- **Fix:** Remove redundant test or add a fresh base class.

### ISSUE #32 — LOW — `CliParamsBase.verify()` swallows log rule parse errors
- **File:** `src/main/scala/org/polyconf/cli/CliParamsBase.scala:54`
- **Problem:** `Try(...).getOrElse(())` silently swallows errors from invalid log rules.
- **Fix:** Log the error instead.

### ISSUE #33 — LOW — `ArgFormatterParams.verify()` calls `super[CliParamsBase].verify()` unnecessarily
- **File:** `src/main/scala/org/polyconf/argfmt/ArgFormatterParams.scala:18`
- **Problem:** Override adds no behavior; constructor already calls `verify()`.
- **Fix:** Remove the override entirely.

### ISSUE #34 — LOW — `DataLocal.select` silently drops missing column names
- **File:** `src/main/scala/org/polyconf/cli/DataLocal.scala:18-19`
- **Problem:** Typo in column name silently produces empty results.
- **Fix:** Log a warning for missing columns.

### ISSUE #35 — MEDIUM — Tests for `PolyUtil.waitForCondition` and `ParallelOps.waitUntil` are absent
- **Files:** `src/test/scala/org/polyconf/BaseUtilsSpec.scala`, `ParallelOpsSpec.scala`
- **Problem:** Core polling/retry logic has zero test coverage.
- **Fix:** Add tests for timeout, immediate success, never succeeds, etc.

---

## Round 2 — Fresh Review (Jun 2026)

### Status Legend
- ✅ **Fixed** — fix applied and tested
- ⏭️ **Skipped** — user chose to skip
- ✔️ **Not a bug** — confirmed correct after investigation
- ⬜ **Pending** — not yet addressed

---

### CRITICAL

**P1** — ✅ **Fixed** — `RecursiveJsonSerializer` produces malformed JSON on failure
- **File:** `src/main/scala/org/polyconf/core/PolySerde.scala:57-60`
- **Problem:** `recover` handler writes nothing to `JsonGenerator` after serialization failure, producing invalid JSON like `{"field": , ...}`. Also catches fatal JVM errors.
- **Fix:** `gen.writeNull()` as fallback + `NonFatal` guard.
- **Commit:** Applied in current session.

**P2** — ✅ **Fixed** — `PolyParallelRunner.parallelOrderedRun` data race on timeout
- **File:** `src/main/scala/org/polyconf/util/PolyParallelRunner.scala:93`
- **Problem:** When `f.get(30s)` times out, worker thread continues writing to `results(idx)` while main thread reads `results.toList` — no happens-before edge.
- **Fix:** `f.cancel(true)` to stop the worker thread on timeout.
- **Commit:** Applied in current session.

---

### HIGH

**P3** — ✔️ **Not a bug** — `EscapeFormatShell` regex range `:-`
- **File:** `src/main/scala/org/polyconf/argfmt/EscapeFormat.scala:18`
- **Problem (claimed):** `[a-zA-Z0-9_./@%+=:-]` — range `:-` matches `;`, `<`, `=`, `>`, `?` as safe.
- **Analysis:** In Java regex, `-` at the very end of a character class is a literal hyphen. The regex is correct as-is.
- **Verdict:** No fix needed.

**P4** — ✅ **Fixed** — `EscapeFormatYaml` doesn't escape single quotes
- **File:** `src/main/scala/org/polyconf/argfmt/EscapeFormat.scala:26`
- **Problem:** `args.map(x => s"'$x'")` — arg `it's` produces `'it's'`, invalid YAML.
- **Fix:** `s"'${x.replace("'", "''")}'"`.

**P5** — ✔️ **Not a bug** — `RunnerShell.cmd` can be set by Jackson
- **File:** `src/main/scala/org/polyconf/cli/run/RunnerShell.scala:23`
- **Problem (claimed):** `private val cmd: String = ""` — Jackson can't set a `private final val`.
- **Analysis:** PolyConf uses `.setVisibility(PropertyAccessor.FIELD, Visibility.ANY)`, which lets Jackson set `private val` fields via reflection (see PolyConf.scala:169 and doc lines 119-123).
- **Verdict:** Works correctly as-is.

**P6** — ✅ **Fixed** — `CliRun.main()` logs result at `DEBUG` only
- **File:** `src/main/scala/org/polyconf/cli/run/CliRun.scala:56`
- **Problem:** `Log.debug(...)` — job result invisible at default WARN/INFO levels.
- **Fix:** Changed to `Log.info(...)`.

**P7** — ✅ **Fixed** — `TransformerImpl` `Int.MinValue.abs` returns negative
- **File:** `src/main/scala/org/polyconf/cli/stream/TransformerImpl.scala:21`
- **Problem:** `v.hashCode.abs % maxHashes` — `Int.MinValue.abs` == `Int.MinValue` (negative), producing negative `%` result or index OOB.
- **Fix:** `(v.hashCode & Int.MaxValue) % maxHashes`.

**P8** — ✅ **Fixed** — `TransformerJob` stageCounts double-counted with multi-output transformers
- **File:** `src/main/scala/org/polyconf/cli/stream/TransformerJob.scala:76-131`
- **Problem:** `stageCounts` accumulates entries from ALL output envelopes; `foreach` adds ALL entries on every iteration. `writtenCount` reads wrong envelope's count.
- **Fix:** Moved per-stage counting inside `transformed.foreach` — tracks `genRows` once (per-file), per-envelope counts via `countPass` inside loop.
- **Test:** Added `"should NOT double-count stage when transformer produces multiple outputs"` in `TransformerJobPerfSpec`.

---

### MEDIUM

**P9** — ⬜ **Pending** — `PolySerde.getNestedValueTry` unsafe type casts
- **File:** `src/main/scala/org/polyconf/core/PolySerde.scala:124-131`
- **Problem:** `raw.asInstanceOf[Int].asInstanceOf[T]` — ClassCastException when Jackson returns `Long` but `ClassTag` is `Int`.
- **Fix:** Use `raw match { case n: Number => n.intValue(); ... }` instead of unchecked casts.

**P10** — ⬜ **Pending** — `PolySerde.loadYamlString` unchecked cast
- **File:** `src/main/scala/org/polyconf/core/PolySerde.scala:136`
- **Problem:** `javaMapToScala(new Yaml().loadAs(...)).asInstanceOf[Map[String, Any]]` — if YAML root is a list/scalar, produces `ClassCastException` at call site.
- **Fix:** Pattern-match on the result type before casting.

**P11** — ⬜ **Pending** — `PolyAccStorage.add` non-thread-safe accumulator
- **File:** `src/main/scala/org/polyconf/util/PolyAccStorage.scala:30-31`
- **Problem:** `store.synchronized` protects the map, but `PolyAccumulatorLong.add(value)` is `total += value` (non-atomic RMW). Two threads on same key lose updates.
- **Fix:** Use `AtomicLong` inside `PolyAccumulatorLong` or synchronize per-accumulator.

**P12** — ⬜ **Pending** — `PolyParallelRunner` hardcoded 30s timeout
- **File:** `src/main/scala/org/polyconf/util/PolyParallelRunner.scala:93`
- **Problem:** `f.get(30, TimeUnit.SECONDS)` — not configurable. Workloads exceeding 30s/item get interrupted.
- **Fix:** Make timeout a parameter.

**P13** — ⬜ **Pending** — `SubclassScanner` filters Scala singleton objects from discovery
- **File:** `src/main/scala/org/polyconf/core/SubclassScanner.scala:66,77`
- **Problem:** `cn.contains("$")` filters ALL class names containing `$`, including Scala `object` classes (e.g. `MyObject$`). Objects extending `PolyConf` will never be found.
- **Fix:** Only filter if name contains `$` in a non-terminal position, or allow `$` classes that are concrete non-companion objects.

**P14** — ⬜ **Pending** — `JsonVariableBase.merge` uses `groupBy` → loses priority order
- **File:** `src/main/scala/org/polyconf/argfmt/JsonVariableBase.scala:33-34`
- **Problem:** `seqs.flatten.groupBy(_._1).map { case (k, vs) => k -> vs.head._2 }` — `groupBy` loses ordering; priority between `renameArgs`/`envVars`/`allVars` becomes non-deterministic.
- **Fix:** Use `foldLeft` with explicit priority: `renameArgs ++ envVars ++ allVars` and let `.toMap` overwrite earlier keys.

**P15** — ⬜ **Pending** — `LocalDataIO.skipRows` uncaught `NumberFormatException`
- **File:** `src/main/scala/org/polyconf/cli/stream/LocalDataIO.scala:76`
- **Problem:** `options.get("skipRows").filter(_.nonEmpty).map(_.toInt)` — if value is non-numeric (e.g. `"abc"`), throws uncaught `NumberFormatException`.
- **Fix:** Use `Try(_.toInt).getOrElse(0)` and log a warning.

---

### LOW

**P16** — ⬜ **Pending** — `TransformerJob.finalizeResults` is empty no-op, never overridden
- **File:** `src/main/scala/org/polyconf/cli/stream/TransformerJob.scala:33`
- **Problem:** Dead code hook with no implementations anywhere in the codebase.
- **Fix:** Remove or make abstract.

**P17** — ⬜ **Pending** — Trailing comma in `LocalDataIORegistry` Map literal
- **File:** `src/main/scala/org/polyconf/cli/stream/LocalDataIO.scala:152`
- **Problem:** `"parquet" -> LocalParquetIO,` — trailing comma may cause warnings with older Scala settings.
- **Fix:** Remove trailing comma.

**P18** — ⬜ **Pending** — `CliParamsBase` calls `sys.exit(0)` on `--help`
- **File:** `src/main/scala/org/polyconf/cli/CliParamsBase.scala:46-48`
- **Problem:** `sys.exit(0)` kills the entire JVM, preventing library reuse in non-CLI contexts (test harnesses, servers).
- **Fix:** Throw a typed `HelpException` instead.
