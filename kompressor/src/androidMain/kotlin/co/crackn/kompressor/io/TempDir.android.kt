/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import co.crackn.kompressor.KompressorContext
import java.io.File
import java.util.UUID
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Android canonical temp directory for the I/O materialization pipeline. Resolves to
 * `context.cacheDir/kompressor-io` so the files land on the app-private storage tier and
 * are reclaimable by the system under cache pressure. The directory itself is created
 * lazily by [materializeToTempFile]; this helper only resolves the path.
 *
 * The `kompressor-io` sub-directory keeps our temp files segregated from the cache
 * sub-trees used by [co.crackn.kompressor.image.AndroidImageCompressor],
 * [co.crackn.kompressor.video.AndroidVideoCompressor], and
 * [co.crackn.kompressor.audio.AndroidAudioCompressor] — cleaning this directory on a
 * failed upload batch will not wipe other Kompressor artefacts.
 *
 * Requires [KompressorContext] to be initialized (AndroidX App Startup handles this).
 */
internal fun kompressorTempDir(): Path =
    KompressorContext.appContext.cacheDir.toOkioPath() / "kompressor-io"

private fun File.toOkioPath(): Path = absolutePath.toPath()

internal actual fun randomMaterializationId(): String = UUID.randomUUID().toString()

internal actual val kompressorFileSystem: FileSystem = FileSystem.SYSTEM
