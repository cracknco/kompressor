/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.errortaxonomy

import java.io.File

/**
 * Renders `docs/error-handling.md` from the three `…CompressionError.kt` sealed hierarchies.
 *
 * The companion test [ErrorTaxonomyDocUpToDateTest] runs this as an `androidHostTest`
 * (required on every PR via the existing `Tests & coverage (host)` job), asserting the
 * committed markdown is byte-identical to the rendered output. Drift between source
 * and doc therefore surfaces as a red check at PR time, not as a rotting doc at read time.
 *
 * Lives in `androidHostTest` rather than `commonMain` because it does JVM file I/O
 * (`java.io.File`) — not something we want to leak into the published artefact. The three
 * sealed hierarchies it reads are pure `commonMain` sources; the renderer walks them with
 * regex (no compiler / reflection dependency).
 *
 * ## Design choices
 *
 * - **Editorial guidance lives in [GUIDANCE]**, keyed `"Hierarchy.Subtype"`. Adding a
 *   sealed subtype without a matching entry fails fast with a clear message — intentional
 *   structural invariant: every user-visible error gets a documented response. This
 *   mirrors the pattern established in `FormatSupportMatrix` (CRA-43).
 * - **First-sentence KDoc summary** via the `\.\s+(?=[A-Z])` heuristic. A follow-up
 *   smoke check fails loud when the heuristic returns empty (e.g. a subtype whose KDoc
 *   starts with an abbreviation that splits the summary early).
 * - **Regeneration** happens through [ErrorTaxonomyDocUpToDateTest] when invoked with
 *   `-PregenerateErrorTaxonomyDoc=true`. The `scripts/regenerate-error-taxonomy.sh` wrapper
 *   drives this — mirrors the CRA-43 pattern and avoids needing a standalone `kotlin` CLI.
 */
internal object ErrorTaxonomyRenderer {

    /**
     * Repo-relative source paths for the three error hierarchies. Kept in load order so
     * the generated doc always renders Image → Audio → Video.
     */
    val SOURCE_PATHS: List<String> = listOf(
        "kompressor/src/commonMain/kotlin/co/crackn/kompressor/image/ImageCompressionError.kt",
        "kompressor/src/commonMain/kotlin/co/crackn/kompressor/audio/AudioCompressionError.kt",
        "kompressor/src/commonMain/kotlin/co/crackn/kompressor/video/VideoCompressionError.kt",
    )

    /**
     * Renders the full markdown document from the three hierarchies under [repoRoot].
     *
     * @throws IllegalStateException if any hierarchy is missing a [GUIDANCE] entry for one
     *   of its parsed subtypes, or if the first-sentence heuristic produced an empty summary
     *   for any subtype. Both conditions indicate the generator can't faithfully render the
     *   doc — the caller should surface them as test failures, not silently paper over.
     */
    fun render(repoRoot: File): String {
        val hierarchies = SOURCE_PATHS.map { relPath -> parseHierarchy(File(repoRoot, relPath), relPath) }
        validateAllSubtypesHaveGuidance(hierarchies)
        validateAllSummariesNonEmpty(hierarchies)
        return renderDoc(hierarchies)
    }

    // ── Data model ──────────────────────────────────────────────────────────

    internal data class Subtype(val name: String, val summary: String)

    internal data class Hierarchy(val name: String, val sourcePath: String, val subtypes: List<Subtype>)

    internal enum class Availability(
        val treeMarker: String,
        val androidCell: String,
        val iosCell: String,
        val divergenceReason: String,
    ) {
        Both("", "\u2705", "\u2705", ""),
        AndroidOnly(" *(Android-only)*", "\u2705", "\u2014", "Raised exclusively by the Android pipeline."),
        IosOnly(" *(iOS-only)*", "\u2014", "\u2705", "Raised exclusively by the iOS pipeline."),
    }

    internal data class Guidance(
        val retrySafe: String,
        val consumerFix: String,
        val availability: Availability = Availability.Both,
    )

    // ── Editorial guidance ──────────────────────────────────────────────────
    //
    // Keyed by "HierarchyName.SubtypeName". Adding or renaming a sealed subtype requires a
    // matching entry here — the test calling [render] fails fast otherwise.

    internal val GUIDANCE: Map<String, Guidance> = buildMap {
        // ImageCompressionError
        put(
            "ImageCompressionError.UnsupportedSourceFormat",
            Guidance(
                retrySafe = "no",
                consumerFix = "Offer a \"convert first\" flow. The bytes can't be decoded by the current " +
                    "platform decoder.",
            ),
        )
        put(
            "ImageCompressionError.UnsupportedInputFormat",
            Guidance(
                retrySafe = "depends",
                consumerFix = "Show a localised \"requires \$platform \$minApi+\" hint when " +
                    "`isNotImplementedOnPlatform == false`; otherwise reject (this platform never decodes " +
                    "the format). Never retry the same config on this device.",
            ),
        )
        put(
            "ImageCompressionError.UnsupportedOutputFormat",
            Guidance(
                retrySafe = "yes (fallback format)",
                consumerFix = "Retry with a widely-supported format \u2014 JPEG everywhere, WEBP on " +
                    "Android, HEIC on iOS 15+.",
            ),
        )
        put(
            "ImageCompressionError.DecodingFailed",
            Guidance(
                retrySafe = "no",
                consumerFix = "File-specific \u2014 ask the user to re-export or reacquire the source.",
            ),
        )
        put(
            "ImageCompressionError.EncodingFailed",
            Guidance(
                retrySafe = "maybe (OOM path)",
                consumerFix = "Usually device-wide. Free memory and retry once with smaller " +
                    "`maxWidth` / `maxHeight`; else report.",
            ),
        )
        put(
            "ImageCompressionError.IoFailed",
            Guidance(
                retrySafe = "yes (after user fix or transient retry)",
                consumerFix = "For storage / permission causes, show storage / permission UI and retry when " +
                    "resolved. For PhotoKit resolution failures, retry once \u2014 iCloud download hiccups " +
                    "are frequently transient.",
            ),
        )
        put(
            "ImageCompressionError.Unknown",
            Guidance(
                retrySafe = "no",
                consumerFix = "Report with `cause` attached \u2014 we failed to classify it.",
            ),
        )
        putAll(ioGuidance("ImageCompressionError"))

        // AudioCompressionError
        put(
            "AudioCompressionError.UnsupportedSourceFormat",
            Guidance(
                retrySafe = "no",
                consumerFix = "Offer a \"convert first\" flow (AAC / MP3 / WAV). iOS additionally rejects " +
                    "MP3 / FLAC / OGG inputs \u2014 see `docs/format-support.md`.",
            ),
        )
        put(
            "AudioCompressionError.DecodingFailed",
            Guidance(
                retrySafe = "no",
                consumerFix = "File-specific \u2014 don't retry the same bytes.",
            ),
        )
        put(
            "AudioCompressionError.EncodingFailed",
            Guidance(
                retrySafe = "maybe",
                consumerFix = "Usually device-wide. Try a different bitrate / sample rate once; else report.",
            ),
        )
        put(
            "AudioCompressionError.IoFailed",
            Guidance(
                retrySafe = "yes (after user fix or transient retry)",
                consumerFix = "For storage / permission causes, show storage / permission UI and retry when " +
                    "resolved. For PhotoKit resolution failures, retry once \u2014 iCloud download hiccups " +
                    "are frequently transient.",
            ),
        )
        put(
            "AudioCompressionError.UnsupportedConfiguration",
            Guidance(
                retrySafe = "yes (narrower config)",
                consumerFix = "Retry with a config the source supports \u2014 typically " +
                    "`channels = AudioChannels.MONO` or drop the upmix ask.",
            ),
        )
        put(
            "AudioCompressionError.UnsupportedBitrate",
            Guidance(
                retrySafe = "yes (in-range bitrate)",
                consumerFix = "Read `details`, clamp the requested bitrate to the reported range, retry.",
                availability = Availability.IosOnly,
            ),
        )
        put(
            "AudioCompressionError.Unknown",
            Guidance(
                retrySafe = "no",
                consumerFix = "Report with `cause` attached.",
            ),
        )
        put(
            "AudioCompressionError.NoAudioTrack",
            Guidance(
                retrySafe = "no",
                consumerFix = "Pick a different source \u2014 the input container has no audio stream " +
                    "(e.g. a silent video, an image file). For video-only inputs prefer " +
                    "`VideoCompressor.thumbnail`.",
            ),
        )
        putAll(ioGuidance("AudioCompressionError"))

        // VideoCompressionError
        put(
            "VideoCompressionError.UnsupportedSourceFormat",
            Guidance(
                retrySafe = "no",
                consumerFix = "Offer a \"convert first\" flow \u2014 the codec / profile / level isn't " +
                    "decodable on this device.",
            ),
        )
        put(
            "VideoCompressionError.Hdr10NotSupported",
            Guidance(
                retrySafe = "yes (SDR fallback)",
                consumerFix = "Retry with `DynamicRange.SDR`, or surface \"HDR10 requires a newer device\". " +
                    "`device` / `codec` / `reason` are the bug-report payload. We deliberately don't " +
                    "auto-downgrade \u2014 that would be silent data loss.",
                availability = Availability.AndroidOnly,
            ),
        )
        put(
            "VideoCompressionError.DecodingFailed",
            Guidance(
                retrySafe = "no",
                consumerFix = "File-specific \u2014 ask the user to re-export the source.",
            ),
        )
        put(
            "VideoCompressionError.EncodingFailed",
            Guidance(
                retrySafe = "maybe",
                consumerFix = "Try once at a lower resolution or bitrate; else report.",
            ),
        )
        put(
            "VideoCompressionError.IoFailed",
            Guidance(
                retrySafe = "yes (after user fix or transient retry)",
                consumerFix = "For storage / permission causes, show storage / permission UI and retry when " +
                    "resolved. For PhotoKit resolution failures, retry once \u2014 iCloud download hiccups " +
                    "are frequently transient.",
            ),
        )
        put(
            "VideoCompressionError.Unknown",
            Guidance(
                retrySafe = "no",
                consumerFix = "Report with `cause` attached.",
            ),
        )
        put(
            "VideoCompressionError.TimestampOutOfRange",
            Guidance(
                retrySafe = "yes (clamped offset)",
                consumerFix = "Clamp `atMillis` to `[0, duration]` and retry \u2014 the requested offset " +
                    "exceeds the video's duration. Read `AVURLAsset.duration` / " +
                    "`MediaMetadataRetriever.METADATA_KEY_DURATION` upfront to pick a valid offset.",
            ),
        )
        putAll(ioGuidance("VideoCompressionError"))
    }

    /**
     * The four I/O error variants (`SourceNotFound`, `SourceReadFailed`, `DestinationWriteFailed`,
     * `TempFileFailed`) share identical guidance across image / audio / video. Factored out so a
     * future editorial correction lands in one place [CRA-90 review].
     */
    private fun ioGuidance(hierarchy: String): List<Pair<String, Guidance>> = listOf(
        "$hierarchy.SourceNotFound" to Guidance(
            retrySafe = "no",
            consumerFix = "Ask the user to re-select the source \u2014 the underlying resource is gone " +
                "(deleted file, revoked URI, offline iCloud asset with network access disabled).",
        ),
        "$hierarchy.SourceReadFailed" to Guidance(
            retrySafe = "maybe (transient)",
            consumerFix = "Retry once if `cause` looks transient (e.g. a content-provider hiccup); " +
                "otherwise surface the underlying `details` and let the user reacquire the source. " +
                "PhotoKit resolution failures surface as `IoFailed` \u2014 see its row.",
        ),
        "$hierarchy.DestinationWriteFailed" to Guidance(
            retrySafe = "yes (after user fix)",
            consumerFix = "Show storage / permission UI \u2014 disk full, missing `WRITE` permission, or " +
                "a revoked SAF / MediaStore grant. Retry after the user resolves it.",
        ),
        "$hierarchy.TempFileFailed" to Guidance(
            retrySafe = "yes (after user fix)",
            consumerFix = "Free device storage and retry \u2014 temp file allocation failed mid-pipeline.",
        ),
    )

    // ── Parser ──────────────────────────────────────────────────────────────

    // `\b` (not `\(`) — matches a sealed class with or without a primary constructor, so
    // a future `public sealed class Foo { abstract val details: String }` refactor doesn't
    // silently make the parser error out with "no sealed class declaration found".
    private val SEALED_CLASS_RE = Regex("""public sealed class (\w+)\b""")

    // Subtype declarations at 4-space indent inside the sealed class body. `public [data class|
    // class|object]` covers the common sealed-subtype shapes; `(\s*\(|\s*:)` accepts both primary
    // constructor `(` and direct supertype-list `:` (no constructor) after the name.
    private val SUBTYPE_RE = Regex(
        """^    public (?:data class|class|object) (\w+)(?:\s*\(|\s*:)""",
        RegexOption.MULTILINE,
    )

    // Break the first paragraph on `. ` followed by an uppercase letter — handles ordinary
    // sentences without splitting on abbreviations like `e.g.` / `etc.` that end in `.`
    // followed by a lowercase continuation. The [ABBREVIATIONS] filter below also skips
    // breaks that follow a known abbreviation even when the next word is capitalised (the
    // `(e.g. HEVC Main 10 ...)` case in `VideoCompressionError.UnsupportedSourceFormat`'s
    // KDoc would otherwise truncate the summary to "…codec/profile/level (e.g").
    private val SENTENCE_BREAK_RE = Regex("""\.\s+(?=[A-Z])""")

    /**
     * Lowercase tokens that commonly end in `.` and are followed by a capital letter without
     * starting a new sentence. The sentence-break heuristic skips over a match whose
     * preceding token (after stripping bracket / quote leaders) is in this set.
     *
     * Deliberately small: false positives here corrupt summaries as much as false negatives.
     * Expand only when a real KDoc trips the smoke check.
     */
    private val ABBREVIATIONS: Set<String> = setOf(
        "e.g", "i.e", "etc", "vs", "cf", "esp", "approx",
    )

    /**
     * [repoRelativePath] is the path stored in the rendered markdown `Source: [...](../path)`
     * link — must stay repo-relative so the doc stays portable. [file] is the resolved
     * filesystem handle used to read the source text.
     */
    internal fun parseHierarchy(file: File, repoRelativePath: String): Hierarchy {
        check(file.exists()) {
            "Source file not found: ${file.absolutePath}. Ensure the test is run from the repo root."
        }
        val text = file.readText()
        val hierarchyName = SEALED_CLASS_RE.find(text)?.groupValues?.get(1)
            ?: error("No `public sealed class \u2026` declaration found in ${file.name}")

        val subtypes = SUBTYPE_RE.findAll(text).map { match ->
            Subtype(name = match.groupValues[1], summary = extractKdocSummary(text, match.range.first))
        }.toList()

        check(subtypes.isNotEmpty()) { "No subtypes found in ${file.name}" }
        return Hierarchy(hierarchyName, repoRelativePath, subtypes)
    }

    /**
     * Walks back from [declarationStart] to the nearest KDoc-open marker, joins the first
     * paragraph of the KDoc into a single line, returns up to the first sentence end. Empty
     * string when no KDoc precedes the declaration — the caller's smoke check turns that
     * into a loud failure with a diagnostic message.
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
        val sentenceBreak = SENTENCE_BREAK_RE.findAll(body).firstOrNull { m ->
            val preceding = body.substring(0, m.range.first)
            val lastToken = preceding.takeLastWhile { it != ' ' && it != '\n' }
                .trimStart('(', '"', '\'', '`', '[')
                .lowercase()
            lastToken !in ABBREVIATIONS
        }
        val cut = if (sentenceBreak != null) body.substring(0, sentenceBreak.range.first) else body
        return cut.trimEnd('.', ' ')
    }

    // ── Validation ──────────────────────────────────────────────────────────

    private fun validateAllSubtypesHaveGuidance(hierarchies: List<Hierarchy>) {
        val missing = hierarchies.flatMap { h -> h.subtypes.map { "${h.name}.${it.name}" } }
            .filterNot { it in GUIDANCE }
        check(missing.isEmpty()) {
            "Missing GUIDANCE entries for: ${missing.joinToString()}. Add them to " +
                "`ErrorTaxonomyRenderer.GUIDANCE` (androidHostTest)."
        }
    }

    private fun validateAllSummariesNonEmpty(hierarchies: List<Hierarchy>) {
        val empty = hierarchies.flatMap { h ->
            h.subtypes.filter { it.summary.isBlank() }.map { "${h.name}.${it.name}" }
        }
        check(empty.isEmpty()) {
            "Empty KDoc summary extracted for: ${empty.joinToString()}. " +
                "Check the subtype's leading KDoc — the first sentence must be a complete clause " +
                "(the sentence-break heuristic is `\\.\\s+(?=[A-Z])`, so abbreviations like " +
                "`i.e.` / `e.g.` followed by a capital letter will split the summary early)."
        }
    }

    // ── Renderer ────────────────────────────────────────────────────────────

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
> `./scripts/regenerate-error-taxonomy.sh` after modifying any of the three
> `…CompressionError.kt` sealed classes and commit the result. The companion
> `ErrorTaxonomyDocUpToDateTest` in `androidHostTest` fails CI when the committed
> doc drifts from the source.

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

    private val DIVERGENCE_INTRO: String =
        """Most subtypes are symmetric across Android and iOS — callers can share `when`-branches.
The divergent cases below are the ones worth singling out in platform-aware code.

"""

    private val HANDLING_SECTION: String = """## Handling critical cases

The snippet below covers the critical branches: the retry-worthy subtypes get
dedicated arms so their remediation is explicit, while the "no retry" subtypes
collapse into a reporting fallback. Adapt it to your app's reporting surface.

```kotlin
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource

kompressor.video.compress(
    input = MediaSource.Local.FilePath(inputPath),
    output = MediaDestination.Local.FilePath(outputPath),
    config = config,
)
    .onFailure { err ->
        when (err) {
            is VideoCompressionError.UnsupportedSourceFormat ->
                showConvertFirstBanner(err.details)

            is VideoCompressionError.Hdr10NotSupported ->
                // Android-only. Retry with SDR; never silently downgrade.
                retry(config.copy(dynamicRange = DynamicRange.SDR))

            is VideoCompressionError.IoFailed,
            is VideoCompressionError.DestinationWriteFailed,
            is VideoCompressionError.TempFileFailed ->
                // Storage / permission surface — disk full, revoked write grant, cache miss.
                showStorageErrorDialog(err.message.orEmpty())

            is VideoCompressionError.SourceNotFound ->
                // Resource is gone (deleted file, revoked URI, offline iCloud asset).
                promptReselectSource(err.details)

            is VideoCompressionError.SourceReadFailed ->
                // Transient read failure — one retry is safe, then fall back to the user.
                retryOnceThenReport(err)

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
./scripts/regenerate-error-taxonomy.sh           # rewrite docs/error-handling.md in place
./gradlew :kompressor:testAndroidHostTest \
  --tests co.crackn.kompressor.errortaxonomy.ErrorTaxonomyDocUpToDateTest
                                                 # verify drift (runs on every PR via CI)
```

The test parses the three `…CompressionError.kt` sources with regex, renders this
file, and asserts the committed copy is byte-identical. Pass
`-PregenerateErrorTaxonomyDoc=true` to switch the test from verify mode into rewrite
mode — the wrapper script above is a thin shortcut for that invocation.
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
}
