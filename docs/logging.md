# Logging

Kompressor never writes to `println`, `android.util.Log`, or `NSLog` directly.
Every diagnostic record goes through a pluggable
[`KompressorLogger`](../kompressor/src/commonMain/kotlin/co/crackn/kompressor/logging/KompressorLogger.kt)
sink, chosen at `createKompressor()` time.

This page covers:

- [What the library emits](#what-the-library-emits) — tags, levels, and the phases that produce them.
- [The default logger](#the-default-logger) — `PlatformLogger`, what you see out of the box.
- [Silencing the library](#silencing-the-library) — `NoOpLogger`.
- [Custom sinks](#custom-sinks) — Timber (Android), SwiftLog / CocoaLumberjack / `os_log` (iOS).
- [Contract](#contract) — thread-safety, no-throw, performance expectations.

The architectural rationale for the taxonomy is in
[ADR-003](adr/003-logger-contract.md).

## What the library emits

Every record is tagged with one of the `Kompressor.<Facet>` constants from
[`LogTags`](../kompressor/src/commonMain/kotlin/co/crackn/kompressor/logging/LogTags.kt):

| Tag | Emitted from |
|---|---|
| `Kompressor.Init` | `createKompressor()` / `KompressorInitializer` on Android. |
| `Kompressor.Probe` | `probe()` — one DEBUG start line and one DEBUG success **or** WARN failure line per call. |
| `Kompressor.Image` | `image.compress()` — INFO lifecycle (start + success) + ERROR on failure. |
| `Kompressor.Audio` | `audio.compress()` — INFO lifecycle + DEBUG for passthrough/transcode decisions + ERROR on failure. |
| `Kompressor.Video` | `video.compress()` — INFO lifecycle + DEBUG for pipeline choice (ExportSession vs AssetWriter, HW/SW fallback) + ERROR on failure. |

All tags are 23 characters or shorter so they remain valid on Android API < 26
(which enforces that limit on `android.util.Log`).

Filtering to just Kompressor output is a single glob in every mainstream tool:

```text
Logcat:          tag:Kompressor.*
Console.app:     subsystem or tag contains "Kompressor."
SwiftLog:        label.starts(with: "Kompressor.")
```

## The default logger

`createKompressor()` with no `logger` argument installs
[`PlatformLogger()`](../kompressor/src/commonMain/kotlin/co/crackn/kompressor/logging/PlatformLogger.kt):

- **Android** — records are dispatched to `android.util.Log.{v,d,i,w,e}` with
  the matching priority. Logcat's runtime filter decides what is visible:
  VERBOSE / DEBUG are typically hidden on release builds per Android tooling
  defaults; INFO / WARN / ERROR show up out of the box.
- **iOS** — records are dispatched to `NSLog`, which on modern iOS routes
  through Apple's unified logging system. Messages appear in Console.app,
  device syslog, and `OSLogStore` consumers. VERBOSE / DEBUG are prefixed with
  the level name so filtered views can recognise them.

This is the right default for integration / initial wiring: you immediately see
WARN and ERROR if something misbehaves without having to configure anything.
It is **not** the right default for production on a logging-sensitive app —
see [Silencing the library](#silencing-the-library).

## Silencing the library

For opt-in silence, pass [`NoOpLogger`](../kompressor/src/commonMain/kotlin/co/crackn/kompressor/logging/NoOpLogger.kt):

```kotlin
import co.crackn.kompressor.createKompressor
import co.crackn.kompressor.logging.NoOpLogger

val kompressor = createKompressor(logger = NoOpLogger)
```

`NoOpLogger` discards every record; no formatting, no I/O, no allocation. Use
it when log volume is a measurable cost — Google Play Pre-Launch farms, CI
transcoders, background workers.

Silence is **opt-in on purpose**. If you swap to `NoOpLogger`, do it after
you've confirmed no silent failures during integration, not before.

## Custom sinks

`KompressorLogger` is a `fun interface`, so a SAM lambda, a function reference,
or a class all work. Full contract in [§ Contract](#contract).

### Android — Timber

```kotlin
import android.util.Log
import co.crackn.kompressor.createKompressor
import co.crackn.kompressor.logging.KompressorLogger
import co.crackn.kompressor.logging.LogLevel
import timber.log.Timber

class TimberKompressorLogger : KompressorLogger {
    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        val priority = when (level) {
            LogLevel.VERBOSE -> Log.VERBOSE
            LogLevel.DEBUG -> Log.DEBUG
            LogLevel.INFO -> Log.INFO
            LogLevel.WARN -> Log.WARN
            LogLevel.ERROR -> Log.ERROR
        }
        Timber.tag(tag).log(priority, throwable, message)
    }
}

val kompressor = createKompressor(logger = TimberKompressorLogger())
```

Timber planted `DebugTree` / `ReleaseTree` instances take over filtering from
there — if your release tree drops everything below WARN, that is exactly what
happens to Kompressor's output too.

### iOS — SwiftLog

Expose a Kotlin bridge through the framework and wire Swift-side:

```kotlin
// shared/iosMain/kotlin/.../SwiftLogBridge.kt
public fun swiftLogKompressorLogger(
    emit: (level: LogLevel, tag: String, message: String, throwableDescription: String?) -> Unit,
): KompressorLogger = KompressorLogger { level, tag, message, throwable ->
    emit(level, tag, message, throwable?.stackTraceToString())
}
```

```swift
import Logging
import shared

let swiftLogger = Logger(label: "Kompressor")

let kompressor = KompressorKt.createKompressor(
    logger: SwiftLogBridgeKt.swiftLogKompressorLogger { level, tag, message, throwableDescription in
        var meta: Logger.Metadata = ["kompressor.tag": .string(tag)]
        if let t = throwableDescription { meta["throwable"] = .string(t) }
        switch level {
        case .verbose: swiftLogger.trace(.init(stringLiteral: message), metadata: meta)
        case .debug:   swiftLogger.debug(.init(stringLiteral: message), metadata: meta)
        case .info:    swiftLogger.info(.init(stringLiteral: message), metadata: meta)
        case .warn:    swiftLogger.warning(.init(stringLiteral: message), metadata: meta)
        case .error:   swiftLogger.error(.init(stringLiteral: message), metadata: meta)
        default:       swiftLogger.info(.init(stringLiteral: message), metadata: meta)
        }
    }
)
```

SwiftLog's installed `LogHandler` (console, file, OSLog, your own) takes over
from there.

### iOS — CocoaLumberjack

```swift
import CocoaLumberjackSwift
import shared

let kompressor = KompressorKt.createKompressor(
    logger: SwiftLogBridgeKt.swiftLogKompressorLogger { level, tag, message, throwableDescription in
        let formatted = throwableDescription.map { "[\(tag)] \(message)\n\($0)" } ?? "[\(tag)] \(message)"
        switch level {
        case .verbose: DDLogVerbose(formatted)
        case .debug:   DDLogDebug(formatted)
        case .info:    DDLogInfo(formatted)
        case .warn:    DDLogWarn(formatted)
        case .error:   DDLogError(formatted)
        default:       DDLogInfo(formatted)
        }
    }
)
```

### iOS — `os_log` (Swift)

`os_log` is a C macro and not directly callable from Kotlin/Native, which is
why the default `PlatformLogger` on iOS uses `NSLog`. Wire a Swift bridge if
you want the structured `os_log` API:

```swift
import os
import shared

private let subsystem = Bundle.main.bundleIdentifier ?? "co.crackn.kompressor"
private let osLogger = Logger(subsystem: subsystem, category: "Kompressor")

let kompressor = KompressorKt.createKompressor(
    logger: SwiftLogBridgeKt.swiftLogKompressorLogger { level, tag, message, throwableDescription in
        let line = "[\(tag)] \(message)" + (throwableDescription.map { "\n\($0)" } ?? "")
        switch level {
        case .verbose, .debug: osLogger.debug("\(line, privacy: .public)")
        case .info:            osLogger.info("\(line, privacy: .public)")
        case .warn:            osLogger.notice("\(line, privacy: .public)")
        case .error:           osLogger.error("\(line, privacy: .public)")
        default:               osLogger.info("\(line, privacy: .public)")
        }
    }
)
```

## Contract

Implementations of [`KompressorLogger.log`](../kompressor/src/commonMain/kotlin/co/crackn/kompressor/logging/KompressorLogger.kt)
**must** guarantee:

### Thread-safety

`log` may be invoked from any thread, including multiple threads concurrently.
Video and audio pipelines run on `Dispatchers.Main` / `Dispatchers.IO` /
AVFoundation worker threads, and the library does not marshal onto a single
thread before emitting. Timber, SwiftLog, `android.util.Log`, `NSLog`, and
`os_log` are all thread-safe — custom file-backed or network-backed sinks must
add their own synchronisation.

### No-throw

`log` **must not** throw. The library already wraps every call site in
[`SafeLogger`](../kompressor/src/commonMain/kotlin/co/crackn/kompressor/logging/SafeLogger.kt),
which catches any `Throwable` from the delegate and drops the record on the
floor — but a logger that throws during a video transcode would otherwise
crash mid-pipeline and lose the in-progress output file. Disk-full, network
timeouts, serialisation bugs: handle them inside the implementation.

Rationale and the `SafeLogger` design in
[ADR-003 § 5](adr/003-logger-contract.md#5-no-throw-enforcement-is-the-librarys-job-not-yours).

### Performance / filtering

`log` is called on hot paths (per-step decisions at DEBUG, pipeline choices at
DEBUG). Implementations **must** filter by `LogLevel` as early as possible —
ideally before any string concatenation or formatting.

The library itself short-circuits the lazy message lambda at DEBUG / VERBOSE
before invoking the delegate (see `SafeLogger.emitLazy`), but your delegate
still sees every record at or above the highest level we emit. If you want
"only WARN and up in production", filter in your implementation:

```kotlin
class ProductionLogger(private val delegate: KompressorLogger) : KompressorLogger {
    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        if (level.priority < LogLevel.WARN.priority) return
        delegate.log(level, tag, message, throwable)
    }
}
```

`LogLevel.priority` is the canonical comparator — never compare by enum
ordinal, the ordering of `LogLevel` declarations is incidental.

## Library internals — no raw logging allowed

To keep the "every log routes through `KompressorLogger`" invariant
enforceable, the library module has two gates:

1. **Detekt `ForbiddenImport`** — `android.util.Log` is a forbidden import
   everywhere in `kompressor/src` **except** `PlatformLogger.android.kt`
   (the one authorised delegate).
2. **[`scripts/check-no-raw-logging.sh`](../scripts/check-no-raw-logging.sh)**
   — runs on every PR via the `no-raw-logging` job in `pr.yml`. Scans for
   `println(`, `print(`, `android.util.Log.*`, and `NSLog(` across the library
   module and fails the build if any slip in outside the two platform
   actuals.

If you're contributing and the gate flags your change: add a `logger.info/...`
call via the `SafeLogger` the subsystem already holds, not a raw platform call.
