/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.sample.image

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.copyTo

actual suspend fun importPickedImage(source: PlatformFile, destination: PlatformFile) {
    source.copyTo(destination)
}
