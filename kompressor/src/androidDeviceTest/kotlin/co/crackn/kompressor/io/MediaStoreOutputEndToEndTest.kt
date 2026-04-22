/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.image.AndroidImageCompressor
import co.crackn.kompressor.testutil.createTestImage
import io.kotest.assertions.withClue
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Round-trip coverage of [MediaStoreOutputStrategy] through the public builder — inserts a
 * MediaStore row, uses `MediaDestination.of(mediaStoreUri)` as the compressor output, then
 * queries `IS_PENDING` to verify Kompressor cleared it back to `0` after the write.
 *
 * Uses [MediaStore.Downloads.EXTERNAL_CONTENT_URI] because it's writable from any app on API
 * 29+ without `WRITE_EXTERNAL_STORAGE` — Video/Image/Audio collection URIs need READ_MEDIA_*
 * permissions on API 33+. The IS_PENDING state-machine contract is identical across all
 * MediaStore collections, so Downloads is a sufficient smoke test for the common case.
 *
 * API gate: `MediaStore.Downloads.EXTERNAL_CONTENT_URI` was added in API 29 (Android 10) and
 * the whole `IS_PENDING` pattern is an Android 10+ concept, so we `assumeTrue` on that.
 *
 * Runs on androidDeviceTest only — PR CI skips this (no real MediaStore on host JVM).
 */
class MediaStoreOutputEndToEndTest {

    private lateinit var tempDir: File
    private val imageCompressor = AndroidImageCompressor()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        assumeTrue(
            "MediaStore.Downloads requires API 29+",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
        )
        tempDir = File(context.cacheDir, "kompressor-mediastore-e2e-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        if (::tempDir.isInitialized) tempDir.deleteRecursively()
    }

    @Test
    fun image_toMediaStoreUri_clearsIsPendingAfterWrite() = runTest {
        val input = createTestImage(tempDir, 320, 240)

        // Insert a MediaStore row with IS_PENDING=1 the way a real consumer would. Kompressor's
        // MediaStoreOutputStrategy should see IS_PENDING is already set, perform the write,
        // then clear IS_PENDING=0 on output-stream close.
        val resolver = context.contentResolver
        val pending = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "kompressor-${System.currentTimeMillis()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val mediaStoreUri = checkNotNull(
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, pending),
        ) { "MediaStore.Downloads.insert returned null — cannot run IS_PENDING round-trip" }

        try {
            val result = imageCompressor.compress(
                input = MediaSource.Local.FilePath(input.absolutePath),
                output = MediaDestination.of(mediaStoreUri),
            )
            withClue("Image compress to MediaStore URI must succeed: ${result.exceptionOrNull()}") {
                result.isSuccess shouldBe true
            }

            // IS_PENDING must be 0 after compress() returns — gallery / file pickers rely on this
            // to make the newly-written file visible.
            resolver.query(
                mediaStoreUri,
                arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.SIZE),
                null,
                null,
                null,
            ).use { cursor ->
                withClue("Query returned no row") {
                    (cursor != null && cursor.moveToFirst()) shouldBe true
                }
                checkNotNull(cursor)
                val pendingCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                withClue("IS_PENDING must be cleared after write") {
                    cursor.getInt(pendingCol) shouldBe 0
                }
                withClue("Entry must have bytes written") {
                    cursor.getLong(sizeCol) shouldBeGreaterThan 0L
                }
            }
        } finally {
            resolver.delete(mediaStoreUri, null, null)
        }
    }
}
