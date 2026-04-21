/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * iOS canonical temp directory for the I/O materialization pipeline. Resolves to
 * `NSTemporaryDirectory()/kompressor-io`. The system purges `NSTemporaryDirectory()` on
 * its own cadence (device restarts, storage pressure), which gives us a safe fallback
 * when a caller forgets to delete a materialized temp file. The directory itself is
 * created lazily by [materializeToTempFile]; this helper only resolves the path.
 *
 * `NSTemporaryDirectory()` returns a trailing-slash path, so the `/ "kompressor-io"`
 * segment composition does not introduce a double slash.
 */
internal fun kompressorTempDir(): Path = (NSTemporaryDirectory() + "kompressor-io").toPath()

internal actual fun randomMaterializationId(): String = NSUUID.UUID().UUIDString

internal actual val kompressorFileSystem: FileSystem = FileSystem.SYSTEM
