#!/usr/bin/env kotlin
/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 *
 * Regenerates `docs/error-handling.md` from the three sealed-class error
 * hierarchies that live under
 * `kompressor/src/commonMain/kotlin/co/crackn/kompressor/{image,audio,video}/`.
 *
 * Why a script instead of a Gradle task / JVM test? CRA-44's DoD explicitly
 * asks for a standalone `.kts` — the doc is meant to be a cheap, Gradle-free
 * artefact contributors can regenerate in seconds. (The companion
 * `FormatSupportDocUpToDateTest` pattern from CRA-43 still lives in the repo
 * for the format matrix; if drift becomes a recurring problem here we can
 * add an equivalent JVM host test without touching this script.)
 *
 * Usage (from the repo root):
 *
 *     kotlin scripts/gen-error-taxonomy.kts            # rewrite the doc in place
 *     kotlin scripts/gen-error-taxonomy.kts --check    # exit 1 if the committed doc is stale
 *
 * The sealed classes supply the *structure* (hierarchy name, subtype names,
 * KDoc summaries). The *editorial* guidance (retry-safe verdict, consumer fix,
 * platform availability) lives in the GUIDANCE map below because it's
 * prescriptive, not mechanical. Adding a sealed subtype without a matching
 * GUIDANCE entry fails this script with a clear message — intentional:
 * every user-visible error gets a documented response.
 */

import java.io.File
import kotlin.system.exitProcess

// ---------------------------------------------------------------------------
// Inputs
// ---------------------------------------------------------------------------

private val SOURCES = listOf(
    "kompressor/src/commonMain/kotlin/co/crackn/kompressor/image/ImageCompressionError.kt",
    "kompressor/src/commonMain/kotlin/co/crackn/kompressor/audio/AudioCompressionError.kt",
    "kompressor/src/commonMain/kotlin/co/crackn/kompressor/video/VideoCompressionError.kt",
)

private val OUTPUT_PATH = "docs/error-handling.md"

// ---------------------------------------------------------------------------
// Data model
// ---------------------------------------------------------------------------

private data class Subtype(val name: String, val summary: String)

private data class Hierarchy(val name: String, val sourcePath: String, val subtypes: List<Subtype>)

private enum class Availability(
    val treeMarker: String,
    val androidCell: String,
    val iosCell: String,
    val divergenceReason: String,
) {
    Both("", "\u2705", "\u2705", ""),
    AndroidOnly(" *(Android-only)*", "\u2705", "\u2014", "Raised exclusively by the Android pipeline."),
    IosOnly(" *(iOS-only)*", "\u2014", "\u2705", "Raised exclusively by the iOS pipeline."),
}

private data class Guidance(
    val retrySafe: String,
    val consumerFix: String,
    val availability: Availability = Availability.Both,
)

// ---------------------------------------------------------------------------
// Editorial guidance — keyed by "HierarchyName.SubtypeName".
// Adding or renaming a sealed subtype requires a matching entry here.
// ---------------------------------------------------------------------------

private val GUIDANCE: Map<String, Guidance> = mapOf(
    // ImageCompressionError
    "ImageCompressionError.UnsupportedSourceFormat" to Guidance(
        retrySafe = "no",
        consumerFix = "Offer a \"convert first\" flow. The bytes can't be decoded by the current platform decoder.",
    ),
    "ImageCompressionError.UnsupportedInputFormat" to Guidance(
        retrySafe = "depends",
        consumerFix = "Show a localised \"requires \$platform \$minApi+\" hint when " +
            "`isNotImplementedOnPlatform == false`; otherwise reject (this platform never decodes " +
            "the format). Never retry the same config on this device.",
    ),
    "ImageCompressionError.UnsupportedOutputFormat" to Guidance(
        retrySafe = "yes (fallback format)",
        consumerFix = "Retry with a widely-supported format \u2014 JPEG everywhere, WEBP on Android, " +
            "HEIC on iOS 15+.",
    ),
    "ImageCompressionError.DecodingFailed" to Guidance(
        retrySafe = "no",
        consumerFix = "File-specific \u2014 ask the user to re-export or reacquire the source.",
    ),
    "ImageCompressionError.EncodingFailed" to Guidance(
        retrySafe = "maybe (OOM path)",
        consumerFix = "Usually device-wide. Free memory and retry once with smaller " +
            "`maxWidth` / `maxHeight`; else report.",
    ),
    "ImageCompressionError.IoFailed" to Guidance(
        retrySafe = "yes (after user fix)",
        consumerFix = "Show storage / permission UI and retry when the user resolves it.",
    ),
    "ImageCompressionError.Unknown" to Guidance(
        retrySafe = "no",
        consumerFix = "Report with `cause` attached \u2014 we failed to classify it.",
    ),

    // AudioCompressionError
    "AudioCompressionError.UnsupportedSourceFormat" to Guidance(
        retrySafe = "no",
        consumerFix = "Offer a \"convert first\" flow (AAC / MP3 / WAV). iOS additionally rejects " +
            "MP3 / FLAC / OGG inputs \u2014 see `docs/format-support.md`.",
    ),
    "AudioCompressionError.DecodingFailed" to Guidance(
        retrySafe = "no",
        consumerFix = "File-specific \u2014 don't retry the same bytes.",
    ),
    "AudioCompressionError.EncodingFailed" to Guidance(
        retrySafe = "maybe",
        consumerFix = "Usually device-wide. Try a different bitrate / sample rate once; else report.",
    ),
    "AudioCompressionError.IoFailed" to Guidance(
        retrySafe = "yes (after user fix)",
        consumerFix = "Show storage / permission UI and retry when resolved.",
    ),
    "AudioCompressionError.UnsupportedConfiguration" to Guidance(
        retrySafe = "yes (narrower config)",
        consumerFix = "Retry with a config the source supports \u2014 typically " +
            "`channels = AudioChannels.MONO` or drop the upmix ask.",
    ),
    "AudioCompressionError.UnsupportedBitrate" to Guidance(
        retrySafe = "yes (in-range bitrate)",
        consumerFix = "Read `details`, clamp the requested bitrate to the reported range, retry.",
        availability = Availability.IosOnly,
    ),
    "AudioCompressionError.Unknown" to Guidance(
        retrySafe = "no",
        consumerFix = "Report with `cause` attached.",
    ),

    // VideoCompressionError
    "VideoCompressionError.UnsupportedSourceFormat" to Guidance(
        retrySafe = "no",
        consumerFix = "Offer a \"convert first\" flow \u2014 the codec / profile / level isn't " +
            "decodable on this device.",
    ),
    "VideoCompressionError.Hdr10NotSupported" to Guidance(
        retrySafe = "yes (SDR fallback)",
        consumerFix = "Retry with `DynamicRange.SDR`, or surface \"HDR10 requires a newer device\". " +
            "`device` / `codec` / `reason` are the bug-report payload. We deliberately don't " +
            "auto-downgrade \u2014 that would be silent data loss.",
        availability = Availability.AndroidOnly,
    ),
    "VideoCompressionError.DecodingFailed" to Guidance(
        retrySafe = "no",
        consumerFix = "File-specific \u2014 ask the user to re-export the source.",
    ),
    "VideoCompressionError.EncodingFailed" to Guidance(
        retrySafe = "maybe",
        consumerFix = "Try once at a lower resolution or bitrate; else report.",
    ),
    "VideoCompressionError.IoFailed" to Guidance(
        retrySafe = "yes (after user fix)",
        consumerFix = "Show storage / permission UI and retry when resolved.",
    ),
    "VideoCompressionError.Unknown" to Guidance(
        retrySafe = "no",
        consumerFix = "Report with `cause` attached.",
    ),
)

// ---------------------------------------------------------------------------
// Parser — pulls hierarchy + subtype names + first-sentence KDoc summaries out
// of each source file. Regex-based because the sealed hierarchies are small,
// hand-written, and never use fancy Kotlin features we'd need a real parser for.
// ---------------------------------------------------------------------------

private val SEALED_CLASS_RE = Regex("""public sealed class (\w+)\(""")

// Subtype declarations live at 4-space indent inside the sealed class body.
// Anchoring to the indent + `public class ` keeps top-level helpers (e.g. the
// `buildVersionGatedMessage` file-scope function) out of the match set.
private val SUBTYPE_RE = Regex("""^    public class (\w+)\s*\(""", RegexOption.MULTILINE)

/**
 * Walks back from [declarationStart] to the nearest `/**`, joins the first
 * paragraph of the KDoc into a single line, then returns up to the first
 * sentence end (`. ` followed by an uppercase letter, so we don't break on
 * abbreviations like `e.g.`).
 *
 * Returns an empty string if there's no KDoc above the declaration.
 */
private fun extractKdocSummary(text: String, declarationStart: Int): String {
    val before = text.substring(0, declarationStart)
    val kdocStart = before.lastIndexOf("/**")
    if (kdocStart < 0) return ""
    val kdocEndRel = text.substring(kdocStart).indexOf("*/")
    if (kdocEndRel < 0) return ""
    val rawBlock = text.substring(kdocStart + "/**".length, kdocStart + kdocEndRel)

    val firstParagraph = StringBuilder()
    for (line in rawBlock.lines()) {
        val trimmed = line.trim().removePrefix("*").trim()
        if (trimmed.isEmpty()) {
            if (firstParagraph.isNotEmpty()) break else continue
        }
        if (firstParagraph.isNotEmpty()) firstParagraph.append(' ')
        firstParagraph.append(trimmed)
    }
    val body = firstParagraph.toString()
    if (body.isEmpty()) return ""
    val sentenceBreak = Regex("""\.\s+(?=[A-Z])""").find(body)
    val cut = if (sentenceBreak != null) body.substring(0, sentenceBreak.range.first) else body
    return cut.trimEnd('.', ' ')
}

private fun parseHierarchy(sourceRelPath: String): Hierarchy {
    val file = File(sourceRelPath)
    require(file.exists()) {
        "Source file not found: $sourceRelPath. Run this script from the repo root."
    }
    val text = file.readText()
    val hierarchyName = SEALED_CLASS_RE.find(text)?.groupValues?.get(1)
        ?: error("No `public sealed class \u2026` declaration found in $sourceRelPath")

    val subtypes = SUBTYPE_RE.findAll(text).map { match ->
        Subtype(name = match.groupValues[1], summary = extractKdocSummary(text, match.range.first))
    }.toList()

    require(subtypes.isNotEmpty()) { "No subtypes found in $sourceRelPath" }
    return Hierarchy(hierarchyName, sourceRelPath, subtypes)
}

// ---------------------------------------------------------------------------
// Renderer
// ---------------------------------------------------------------------------

private fun validateGuidance(hierarchies: List<Hierarchy>): List<String> =
    hierarchies.flatMap { h -> h.subtypes.map { "${h.name}.${it.name}" } }
        .filterNot { it in GUIDANCE }

private fun escapeCell(s: String): String = s.replace("|", "\\|").replace("\n", " ")

private fun renderTree(h: Hierarchy): String = buildString {
    appendLine("```text")
    appendLine("${h.name} (sealed)")
    for ((i, s) in h.subtypes.withIndex()) {
        val glyph = if (i == h.subtypes.lastIndex) "\u2514\u2500\u2500" else "\u251C\u2500\u2500"
        val marker = GUIDANCE.getValue("${h.name}.${s.name}").availability.treeMarker
        appendLine("$glyph ${s.name}$marker")
    }
    appendLine("```")
}

private fun renderSubtypeTable(h: Hierarchy): String = buildString {
    appendLine("| Subtype | When it fires | Retry-safe? | Consumer fix |")
    appendLine("|---------|---------------|-------------|--------------|")
    for (s in h.subtypes) {
        val g = GUIDANCE.getValue("${h.name}.${s.name}")
        val whenCell = s.summary.ifBlank { "\u2014" }
        appendLine("| `${s.name}` | ${escapeCell(whenCell)} | ${g.retrySafe} | ${escapeCell(g.consumerFix)} |")
    }
}

private fun renderDivergenceTable(hierarchies: List<Hierarchy>): String = buildString {
    val divergent = hierarchies.flatMap { h ->
        h.subtypes.mapNotNull { s ->
            val g = GUIDANCE.getValue("${h.name}.${s.name}")
            if (g.availability == Availability.Both) null else Triple(h.name, s.name, g)
        }
    }
    appendLine("| Subtype | Android | iOS | Reason |")
    appendLine("|---------|:-------:|:---:|--------|")
    if (divergent.isEmpty()) {
        appendLine("| _(none)_ | \u2014 | \u2014 | All subtypes fire on both platforms. |")
    } else {
        for ((hierarchyName, subtypeName, g) in divergent) {
            appendLine(
                "| `$hierarchyName.$subtypeName` | ${g.availability.androidCell} | " +
                    "${g.availability.iosCell} | ${g.availability.divergenceReason} |",
            )
        }
    }
}

private val HEADER: String = """# Error taxonomy

Kompressor's compress APIs surface failures as sealed class hierarchies wrapped in
`Result.failure(...)`. Typed errors let callers `when`-branch on the concrete subtype
instead of parsing platform messages — `UnsupportedSourceFormat` warrants a "convert
first" UI, while `IoFailed` warrants a "free up storage" UI, and the two need
different telemetry.

This page is the engineering reference. For the shorter user-facing walkthrough see
[`docs/concepts/errors.mdx`](concepts/errors.mdx) (rendered into the Mintlify site).

> **Auto-generated from source.** Do not edit by hand — run
> `kotlin scripts/gen-error-taxonomy.kts` after modifying any of the three
> `…CompressionError.kt` sealed classes and commit the result. `--check` gives
> a non-zero exit code when the committed doc is stale.

## How to read this page

Each hierarchy section has three parts:

1. **Tree** — the sealed class and its direct subtypes, with platform markers for
   subtypes that only fire on one side.
2. **Subtypes** — one row per subtype: *when* the library raises it (extracted from
   the KDoc summary), whether retrying the same call is safe, and what a consumer
   app should typically do.
3. **Cross-hierarchy** — the [Platform divergence](#platform-divergence) and
   [Handling critical cases](#handling-critical-cases) sections at the bottom pull
   the platform-specific / action-critical rows into one place.

Retry-safe column values:

- **`no`** — the same input will fail the same way on this device. Don't retry.
- **`yes (…)`** — retry after the parenthesised change (different config, fallback
  format, resolved user action).
- **`depends`** / **`maybe`** — check the `details` field or error fields to decide.
"""

private val DIVERGENCE_INTRO: String = """Most subtypes are symmetric across Android and iOS — callers can share `when`-branches.
The divergent cases below are the ones worth singling out in platform-aware code.

"""

private val HANDLING_SECTION: String = """## Handling critical cases

The snippet below covers the critical branches: the retry-worthy subtypes get
dedicated arms so their remediation is explicit, while the "no retry" subtypes
collapse into a reporting fallback. Adapt it to your app's reporting surface.

```kotlin
kompressor.video.compress(inputPath, outputPath, config)
    .onFailure { err ->
        when (err) {
            is VideoCompressionError.UnsupportedSourceFormat ->
                showConvertFirstBanner(err.details)

            is VideoCompressionError.Hdr10NotSupported ->
                // Android-only. Retry with SDR; never silently downgrade.
                retry(config.copy(dynamicRange = DynamicRange.SDR))

            is VideoCompressionError.IoFailed ->
                showStorageErrorDialog(err.details)

            is VideoCompressionError.DecodingFailed ->
                // File-specific — ask for a re-export.
                reportBadFile(err)

            is VideoCompressionError.EncodingFailed,
            is VideoCompressionError.Unknown ->
                reportToCrashAnalytics(err)
        }
    }
```

The audio and image hierarchies follow the same shape. `AudioCompressionError`
additionally has `UnsupportedConfiguration` (retry with a narrower channel layout)
and — iOS-only — `UnsupportedBitrate` (retry with an in-range bitrate);
`ImageCompressionError` additionally has `UnsupportedInputFormat` /
`UnsupportedOutputFormat` (version-gated — branch on `minApi` and
`isNotImplementedOnPlatform`).

## Don't forget `CancellationException`

Because every `compress` call is a `suspend` function, `CancellationException` is
**re-thrown**, not wrapped in `Result.failure`. Your `onFailure { }` block won't see
cancellations. See [`docs/threading-model.md`](threading-model.md) for the structured
concurrency contract.

## Regenerating this document

```bash
# From the repo root:
kotlin scripts/gen-error-taxonomy.kts            # rewrite docs/error-handling.md
kotlin scripts/gen-error-taxonomy.kts --check    # CI drift check, exits non-zero on drift
```

The script parses the three `…CompressionError.kt` sources with regex, renders this
file, and (in `--check` mode) diffs it against the committed copy.
"""

private fun renderDoc(hierarchies: List<Hierarchy>): String = buildString {
    append(HEADER)
    append('\n')
    for (h in hierarchies) {
        append("## `").append(h.name).append("`\n\n")
        append("Source: [`").append(File(h.sourcePath).name).append("`](../").append(h.sourcePath).append(")\n\n")
        append("### Tree\n\n")
        append(renderTree(h))
        append('\n')
        append("### Subtypes\n\n")
        append(renderSubtypeTable(h))
        append('\n')
    }
    append("## Platform divergence\n\n")
    append(DIVERGENCE_INTRO)
    append(renderDivergenceTable(hierarchies))
    append('\n')
    append(HANDLING_SECTION)
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

val hierarchies = SOURCES.map { parseHierarchy(it) }

val missing = validateGuidance(hierarchies)
if (missing.isNotEmpty()) {
    System.err.println(
        "Missing GUIDANCE entries for: ${missing.joinToString()}\n" +
            "Add entries to the GUIDANCE map in scripts/gen-error-taxonomy.kts and rerun.",
    )
    exitProcess(2)
}

val rendered = renderDoc(hierarchies)
val outFile = File(OUTPUT_PATH)
val checkMode = args.contains("--check")

if (checkMode) {
    val current = if (outFile.exists()) outFile.readText() else ""
    if (current != rendered) {
        System.err.println(
            "$OUTPUT_PATH is out of date \u2014 run `kotlin scripts/gen-error-taxonomy.kts` and commit the result.",
        )
        exitProcess(1)
    }
    println("$OUTPUT_PATH is up to date.")
} else {
    outFile.parentFile?.mkdirs()
    outFile.writeText(rendered)
    val totalSubtypes = hierarchies.sumOf { it.subtypes.size }
    println("Wrote $OUTPUT_PATH ($totalSubtypes subtypes across ${hierarchies.size} hierarchies).")
}
