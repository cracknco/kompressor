/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.testutil

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Minimal [ContentProvider] for device tests. Maps any `content://$AUTHORITY/<path>` URI onto
 * a file in the app's cache directory.
 *
 * We roll our own rather than using `androidx.core.content.FileProvider` because the KMP
 * Android plugin's androidDeviceTest variant doesn't merge `res/xml/` from main into the
 * test APK namespace, so `FileProvider`'s required paths XML can't be resolved at manifest-
 * processing time. A hand-written provider sidesteps the resource entirely — all the URI
 * mapping is pure Kotlin.
 */
class TestContentProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val cacheDir = context?.cacheDir ?: error("No context")
        val relative = uri.path?.trimStart('/') ?: error("URI has no path: $uri")
        val cacheRoot = cacheDir.canonicalFile
        val target = File(cacheRoot, relative).canonicalFile
        require(target.path.startsWith(cacheRoot.path + File.separator)) {
            "TestContentProvider: path escapes cacheDir: $uri"
        }
        require(target.isFile) { "TestContentProvider: no file at ${target.absolutePath}" }
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = null

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "co.crackn.kompressor.test.contentprovider"

        /** Build a `content://` URI addressing a file relative to the test app's cache dir. */
        fun contentUriFor(relativeCachePath: String): Uri =
            Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendEncodedPath(relativeCachePath)
                .build()
    }
}
