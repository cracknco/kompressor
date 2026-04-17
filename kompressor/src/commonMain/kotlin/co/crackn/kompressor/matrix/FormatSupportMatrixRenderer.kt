/*
 * Copyright 2026 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.matrix

/**
 * Markers delimiting the auto-generated section in `docs/format-support.md`. Everything between
 * the two markers (inclusive of the markers themselves) is rewritten by
 * `scripts/regenerate-format-support-doc.sh`; everything outside is hand-written context that
 * survives regeneration.
 *
 * The `DO NOT EDIT` banner lives inside the emitted block so it's visible to anyone editing the
 * file, not just consumers of this Kotlin source.
 */
internal const val FORMAT_SUPPORT_MATRIX_BEGIN_MARKER: String =
    "<!-- BEGIN GENERATED: format-support-matrix (CRA-43) -->"
internal const val FORMAT_SUPPORT_MATRIX_END_MARKER: String =
    "<!-- END GENERATED: format-support-matrix (CRA-43) -->"

private const val DO_NOT_EDIT_BANNER: String =
    "<!-- DO NOT EDIT BY HAND — regenerate via `./scripts/regenerate-format-support-doc.sh`. -->"

private const val TABLE_HEADER: String =
    "| Format in | Format out | Android min-API | iOS min-version | Codec path |" +
        " Fast-path eligible? | Notes |"

private const val TABLE_SEPARATOR: String =
    "|-----------|------------|-----------------|-----------------|------------|" +
        "---------------------|-------|"

/**
 * Render the auto-generated section of `docs/format-support.md`. The output starts with the
 * BEGIN marker and ends with the END marker (inclusive) so the caller can splice it directly
 * into an existing file without tracking offsets.
 *
 * Pure function: deterministic output for a given [matrix], no I/O, safe to call from any
 * source set. `FormatSupportDocUpToDateTest` in `androidHostTest` compares this to the
 * committed file; `FormatSupportMatrixRendererTest` in `commonTest` pins the rendering rules.
 */
internal fun renderFormatSupportMatrixTables(
    matrix: FormatSupportMatrix = FormatSupportMatrix,
): String = buildString {
    appendLine(FORMAT_SUPPORT_MATRIX_BEGIN_MARKER)
    appendLine(DO_NOT_EDIT_BANNER)
    renderSection(title = "Image formats", rows = matrix.image)
    renderSection(title = "Audio formats", rows = matrix.audio)
    renderSection(title = "Video formats", rows = matrix.video)
    appendLine()
    append(FORMAT_SUPPORT_MATRIX_END_MARKER)
}

private fun StringBuilder.renderSection(title: String, rows: List<MatrixRow>) {
    appendLine()
    appendLine("### $title")
    appendLine()
    appendLine(TABLE_HEADER)
    appendLine(TABLE_SEPARATOR)
    rows.forEach { appendLine(renderRow(it)) }
}

private fun renderRow(row: MatrixRow): String =
    "| ${row.formatIn} | ${row.formatOut} | ${renderAndroidMinApi(row.androidMinApi)} | " +
        "${renderIosMinVersion(row.iosMinVersion)} | ${row.codecPath} | " +
        "${renderFastPath(row.fastPathEligible)} | ${row.notes} |"

/**
 * Render an Android min-API cell. The matrix uses `24` for "no gate — the library's min-SDK is
 * enough"; higher values are rendered bold to draw attention, matching the style already used
 * in the hand-written doc.
 */
private fun renderAndroidMinApi(value: Int): String = when {
    value <= FormatSupportMatrix.ANDROID_MIN_SDK -> value.toString()
    else -> "**$value**"
}

/**
 * Same convention as Android: bold when the gate is stricter than the library's iOS 15 floor,
 * "—" when unsupported.
 */
private fun renderIosMinVersion(value: Int): String = when {
    value == FormatSupportMatrix.IOS_UNSUPPORTED -> "—"
    value <= FormatSupportMatrix.IOS_MIN_VERSION -> value.toString()
    else -> "**$value**"
}

private fun renderFastPath(eligible: Boolean): String = if (eligible) "Yes" else "No"
