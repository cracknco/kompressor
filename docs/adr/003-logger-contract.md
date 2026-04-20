# ADR-003: Pluggable Logger Contract

## Status

Accepted (2026-04-20)

## Context

Kompressor is a consumer-facing KMP library that runs inside host apps ranging
from greenfield projects to enterprise monorepos. Those apps make opinionated
choices about logging: Timber, Napier, SwiftLog, CocoaLumberjack, `os_log`,
Sentry, Datadog, Crashlytics — or a custom pipeline. A media library that
writes to `println` / `android.util.Log` / `NSLog` directly:

1. **Bypasses the host's filtering** — production builds that mute everything
   below WARN still get our DEBUG lines.
2. **Pollutes crash reporters** — Sentry / Crashlytics breadcrumbs fill with
   compression chatter the host never opted into.
3. **Can't be silenced** — even apps that don't care (no bug reports from
   users, no active debugging) eat the log cost on every frame update.
4. **Can't be enriched** — the host can't attach request IDs, user IDs, or
   span context to our lines because it never sees them.

Before CRA-47 the library had a handful of ad-hoc `android.util.Log.w` calls in
the video and audio pipelines. This ADR documents the decision to replace
every raw log call with a pluggable sink, and nails down the taxonomy so the
implementation is stable across modules and platforms.

## Decision

Introduce a public `KompressorLogger` sink. Every library emission routes
through it. The consumer chooses what happens next.

### 1. Surface

```kotlin
public enum class LogLevel(public val priority: Int) {
    VERBOSE(1), DEBUG(2), INFO(3), WARN(4), ERROR(5);
}

public fun interface KompressorLogger {
    public fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?)
}

public expect fun PlatformLogger(): KompressorLogger   // default
public object NoOpLogger : KompressorLogger            // opt-in silence

public fun createKompressor(logger: KompressorLogger = PlatformLogger()): Kompressor
```

- `PlatformLogger()` delegates to `android.util.Log` on Android and `NSLog` on
  iOS. `NSLog` is chosen over `os_log` because `os_log` is a C macro and not
  directly callable from Kotlin/Native; the `os_log` subsystem is reachable
  from a Swift bridge as documented in `docs/logging.md`.
- `fun interface` means consumers can pass a lambda if that's all they need:
  `createKompressor(logger = { level, tag, msg, t -> ... })`.
- Level threshold filtering is the **implementation's** responsibility, not
  the library's — see [§ 3](#3-the-library-does-not-filter-the-implementation-does).

### 2. Level taxonomy

Each level has a single, documented meaning. The library picks the same level
for the same kind of event across Android and iOS, so a consumer can write one
filter expression and get parity.

| Level | When to emit | Where Kompressor emits it |
|---|---|---|
| `VERBOSE` | Fine-grained per-step trace. Silent by default on both platforms. | Reserved — no library emissions today. Available for future `MediaCodec` per-buffer traces. |
| `DEBUG` | Developer-oriented decisions: codec path chosen, passthrough on/off, HW→SW fallback, pipeline branch. | Probe start line; `AndroidAudioCompressor` passthrough vs transcode decision; `IosVideoCompressor` ExportSession vs AssetWriter decision; cancellation of an in-flight compress. |
| `INFO` | High-level operation lifecycle: started, completed. | `compress()` entry point via `SafeLogger.instrumentCompress` — one line on start, one on success, for image / audio / video on both platforms. |
| `WARN` | Recoverable anomaly or expected failure: probe couldn't read, HDR tone-mapping applied, passthrough disabled because bitrate/channels drift. | Probe failure; future HW→SW fallbacks. |
| `ERROR` | Unrecoverable failure — the operation is about to surface a typed error to the caller. | `compress()` failure path via `instrumentCompress`; the throwable is attached. |

What this means in practice for each pipeline:

- **Probe** (`probe()`) — exactly one line per call. DEBUG start, DEBUG
  success with the decoded info, or WARN failure with the cause attached.
- **Image `compress()`** — INFO start, INFO success, ERROR on failure. No
  per-pixel DEBUG; the operation is too short to warrant mid-flight tracing.
- **Audio / Video `compress()`** — INFO start, DEBUG for the pipeline choice
  (passthrough, codec path, ExportSession vs AssetWriter), INFO success with
  the compressed size / ratio, ERROR on failure.

Progress callbacks are **not** logged — the consumer gave us an
`onProgress: suspend (Float) -> Unit` to drive their own UI; duplicating them
in Logcat would be noise.

### 3. The library does not filter, the implementation does

The library has no notion of a "minimum level". Every emission is dispatched
to the delegate; the delegate decides what survives. This pushes the policy
to the layer that knows — a release build using Timber's `ReleaseTree`
already mutes everything below WARN; re-implementing that filter in the
library would mean either duplicating the host's rule or overriding it.

The only concession the library makes is **lazy message construction**:
VERBOSE / DEBUG / INFO call sites pass a `() -> String` lambda that is
evaluated inside `SafeLogger.emitLazy`. For WARN / ERROR we materialise
eagerly — the hot path is already failing, and the string concat cost is
negligible next to the I/O that prompted it.

### 4. Tag vocabulary

Five constants in `LogTags`, all 23 characters or shorter so they stay valid
on Android API < 26:

```
Kompressor.Init   Kompressor.Probe   Kompressor.Image   Kompressor.Audio   Kompressor.Video
```

A consumer who wants to filter every Kompressor line in Logcat or Console.app
writes `Kompressor.*` once and is done. Subcategory structure (e.g.
`Kompressor.Video.Transformer`) is deliberately **not** modelled: the host's
logger already has that layer (Timber's `Timber.tag()`, SwiftLog's
`subsystem/category`), and duplicating it in the library would create two
inconsistent hierarchies for consumers to understand.

### 5. No-throw enforcement is the library's job, not yours

The KDoc says "implementations must not throw". Saying that isn't enough —
a single misbehaving logger (disk full, serialisation bug, reflection error)
would crash a compression pipeline mid-transcode and lose the output file.
So every emission goes through
[`SafeLogger`](../../kompressor/src/commonMain/kotlin/co/crackn/kompressor/logging/SafeLogger.kt),
which wraps the delegate call in a catch-all:

```kotlin
try { delegate.log(level, tag, message, throwable) }
catch (_: Throwable) { /* swallow deliberately */ }
```

We never re-emit a swallowed exception via the same logger (that would
recurse into the already-broken sink). The record is dropped on the floor;
the compression pipeline keeps running.

### 6. CI enforcement

Two gates stop raw logs from creeping back in:

1. **Detekt `ForbiddenImport`** — `android.util.Log` is forbidden everywhere
   in `kompressor/src` except `PlatformLogger.android.kt`.
2. **`scripts/check-no-raw-logging.sh`** — a POSIX `grep` script wired into
   `pr.yml` as the `no-raw-logging` job (< 10 s, no Gradle). Catches
   `println(`, `print(`, fully-qualified `android.util.Log.*`, and `NSLog(`
   across the library module, allowing only the two platform actuals.

Neither gate replaces the other. The script catches `println` (which isn't an
import) and `NSLog` (iOS), which Detekt wouldn't flag. Detekt catches
imports that would bypass a grep on the fully-qualified call site.

## Alternatives considered

### A. Keep raw `android.util.Log` / `NSLog` calls

Zero infrastructure, but violates all four problems in [Context](#context).
Already proven inadequate by the few ad-hoc `Log.w` calls that prompted this
ticket.

### B. Depend on Kermit / Napier

Would give us cross-platform logging for free. Rejected because:

- It forces every Kompressor consumer to adopt Kermit / Napier transitively.
  Mobile apps that already standardise on Timber / Datadog / Sentry don't
  want a second logger configured in parallel.
- It adds a runtime dependency for what is ultimately a ~120-LOC contract
  (interface + enum + two default sinks + `SafeLogger`). The library is
  "zero binary overhead" by positioning; pulling Kermit in contradicts that.
- It locks us to Kermit's level taxonomy, tag semantics, and release cadence.

See also the FileKit / Coil-KMP / Turbine benchmark in
[ADR-002](002-decline-level-3-supply-chain.md) — none of those indie KMP libs
depend on a logging facade; they expose their own sink interface when they
need one.

### C. `sealed class` instead of `fun interface` for the sink

`sealed class` would let us ship `NoOpLogger` / `PlatformLogger` as
`sealed class` instances and prevent consumers from extending. Rejected
because the whole point is to let consumers extend — forbidding it
contradicts the goal. `fun interface` also gives the lambda form for free.

### D. Library-side level filter (`createKompressor(minLevel = WARN)`)

Would let the library short-circuit before dispatching. Rejected because:

- It duplicates what Timber / SwiftLog / `android.util.Log` already do.
- It means the consumer has two configuration surfaces to keep in sync:
  theirs and ours. The documented rule is "filter in your implementation".
- Lazy message construction in `SafeLogger.emitLazy` already gates the
  expensive path (string formatting) on the level — the non-lazy work that
  remains is an enum comparison and a no-op lambda call.

## Consequences

### Positive

- **Host apps can route Kompressor into their existing logging pipeline**
  (Timber, SwiftLog, `os_log`, Sentry, Datadog) with a ~20-line adapter.
  Recipes documented in `docs/logging.md`.
- **Hosts can silence the library completely** (`NoOpLogger`) when log volume
  is a measurable cost.
- **Library behaviour is uniform across platforms**: same level, same tag,
  same lifecycle shape for image / audio / video on Android and iOS.
- **Invariant is enforceable**: two CI gates catch any new raw log call in
  PR review, before it ships.

### Negative / accepted tradeoffs

- **One more parameter on `createKompressor`.** Mitigated by defaulting to
  `PlatformLogger()` so existing call sites compile unchanged; the old
  `createKompressor()` signature is still valid and documented as a binary
  overload in `kompressor/api/kompressor.api`.
- **Consumers who were relying on our `Log.w` lines ending up in Logcat
  unfiltered** now get them via `PlatformLogger()` by default, which still
  dispatches to `android.util.Log` — so visible behaviour is unchanged for
  them. Only consumers who actively override the logger see the change.
- **`os_log` parity on iOS requires a Swift bridge.** Kotlin/Native cannot
  call the `os_log` macro directly; `NSLog` is the closest native-callable
  API. Documented in `docs/logging.md`; anyone who wants structured `os_log`
  metadata writes a two-line bridge on the Swift side.

### Reversal criteria

This decision sits until one of:

1. The public API needs a level-threshold parameter on `createKompressor`
   (revisit [§ 3](#3-the-library-does-not-filter-the-implementation-does)) —
   would require a minor-version bump and a binary-compat baseline refresh.
2. A mainstream host logger frames our level taxonomy as wrong (someone
   flags DEBUG-for-pipeline-decisions as too noisy, or INFO-for-lifecycle as
   too quiet). Revisit the table in [§ 2](#2-level-taxonomy) and update the
   library to match.

Absent either, leave this decision standing.

## References

- **CRA-47** — this ADR and the accompanying implementation.
- **ADR-002** — indie KMP benchmark (FileKit / Kermit / Turbine / Coil-KMP /
  Koin). Used to scope out "depend on Kermit" as an alternative.
- [`KompressorLogger`](../../kompressor/src/commonMain/kotlin/co/crackn/kompressor/logging/KompressorLogger.kt)
  / [`SafeLogger`](../../kompressor/src/commonMain/kotlin/co/crackn/kompressor/logging/SafeLogger.kt)
  / [`LogTags`](../../kompressor/src/commonMain/kotlin/co/crackn/kompressor/logging/LogTags.kt)
  — the artefacts this ADR documents.
- [`docs/logging.md`](../logging.md) — consumer-facing guide with integration
  recipes.
- [`scripts/check-no-raw-logging.sh`](../../scripts/check-no-raw-logging.sh)
  — the CI gate that enforces the "no raw logs in the library" invariant.
