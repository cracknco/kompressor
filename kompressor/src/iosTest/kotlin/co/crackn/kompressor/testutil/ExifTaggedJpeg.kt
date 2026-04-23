/*
 * Copyright 2026 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package co.crackn.kompressor.testutil

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFNumberRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFURLRef
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSURL
import platform.ImageIO.CGImageDestinationAddImage
import platform.ImageIO.CGImageDestinationCreateWithURL
import platform.ImageIO.CGImageDestinationFinalize
import platform.ImageIO.kCGImagePropertyOrientation

/**
 * iOS sibling of the Android `createExifTaggedJpeg` helper. Produces a solid-colour fixture at
 * [width] × [height] and writes it as a JPEG with the given [orientation] embedded as a
 * top-level `kCGImagePropertyOrientation` entry.
 *
 * ImageIO propagates that top-level property out to the EXIF / TIFF dictionaries on write, so
 * `CGImageSourceCreateThumbnailAtIndex` with `kCGImageSourceCreateThumbnailWithTransform = true`
 * (the code path under test) treats the fixture as if it carried a real EXIF `Orientation` tag.
 * Callers assert post-transform dimensions to prove the thumbnail pipeline honoured the
 * orientation flag.
 *
 * [orientation] follows EXIF conventions: 1 = Up (no rotation), 3 = Down (180°), 6 = Right
 * (visual 90° CW), 8 = Left (visual 90° CCW), plus the four mirrored variants.
 */
@Suppress("UNCHECKED_CAST", "LongMethod")
fun createExifTaggedJpeg(testDir: String, width: Int, height: Int, orientation: Int): String {
    val byteCount = width * height * BYTES_PER_PIXEL
    val pixels = UByteArray(byteCount)
    // Fill with a simple but non-uniform checkerboard-ish pattern so the JPEG encoder doesn't
    // collapse the payload to a trivial size — the test assertions don't depend on content but
    // a degenerate "all-zeroes" JPEG can confuse CGImageSource's auto-format detection.
    var v = 0
    for (i in pixels.indices step BYTES_PER_PIXEL) {
        pixels[i] = (v and MAX_BYTE).toUByte() // R
        pixels[i + 1] = ((v + V_STEP) and MAX_BYTE).toUByte() // G
        pixels[i + 2] = ((v + V_STEP * 2) and MAX_BYTE).toUByte() // B
        pixels[i + 3] = ALPHA_OPAQUE
        v++
    }

    val outPath = testDir + "exif_${orientation}_${width}x$height.jpg"
    pixels.usePinned { pinned ->
        // Every CF/CG object below is "Create rule" — the caller owns one retain count and must
        // pair it with a release. Structure the try/finally ladder to release in reverse of
        // allocation order so partial failures during CGImageDestination setup still unwind the
        // CGColorSpace / CGBitmapContext we created first.
        val colorSpace = CGColorSpaceCreateDeviceRGB()
            ?: error("CGColorSpaceCreateDeviceRGB failed")
        try {
            val ctx = CGBitmapContextCreate(
                data = pinned.addressOf(0) as kotlinx.cinterop.CValuesRef<kotlinx.cinterop.UByteVar>,
                width = width.toULong(),
                height = height.toULong(),
                bitsPerComponent = BITS_PER_COMPONENT,
                bytesPerRow = (width * BYTES_PER_PIXEL).toULong(),
                space = colorSpace,
                bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
            ) ?: error("CGBitmapContextCreate failed")
            try {
                val cgImage = checkNotNull(CGBitmapContextCreateImage(ctx)) { "CGBitmapContextCreateImage failed" }
                try {
                    val urlCF = CFBridgingRetain(NSURL.fileURLWithPath(outPath)) as CFURLRef?
                        ?: error("Failed to bridge URL")
                    val utiCF = CFBridgingRetain(UTI_JPEG) as CFStringRef?
                        ?: error("Failed to bridge UTI")
                    try {
                        val destination = CGImageDestinationCreateWithURL(urlCF, utiCF, 1u, null)
                            ?: error("Failed to create CGImageDestination for $outPath")
                        try {
                            val props = memScoped {
                                val orientationVar = alloc<IntVar>().apply { value = orientation }
                                val orientationNumber: CFNumberRef = checkNotNull(
                                    CFNumberCreate(kCFAllocatorDefault, kCFNumberIntType, orientationVar.ptr),
                                ) { "CFNumberCreate failed" }
                                val keys = allocArray<COpaquePointerVar>(1)
                                val values = allocArray<COpaquePointerVar>(1)
                                keys[0] = kCGImagePropertyOrientation as COpaquePointer?
                                values[0] = orientationNumber as COpaquePointer
                                // Build synchronously and let the destination retain; release our
                                // temporary reference once finalize has run (outer finally).
                                val dict = CFDictionaryCreate(
                                    kCFAllocatorDefault, keys, values, 1,
                                    kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr,
                                )
                                dict to orientationNumber
                            }
                            try {
                                CGImageDestinationAddImage(destination, cgImage, props.first)
                                check(CGImageDestinationFinalize(destination)) {
                                    "CGImageDestinationFinalize returned false for $outPath"
                                }
                            } finally {
                                if (props.first != null) CFRelease(props.first)
                                CFRelease(props.second)
                            }
                        } finally {
                            CFRelease(destination)
                        }
                    } finally {
                        CFRelease(utiCF)
                        CFRelease(urlCF)
                    }
                } finally {
                    CFRelease(cgImage)
                }
            } finally {
                CGContextRelease(ctx)
            }
        } finally {
            CGColorSpaceRelease(colorSpace)
        }
    }
    return outPath
}

private const val BYTES_PER_PIXEL = 4
private const val BITS_PER_COMPONENT: ULong = 8u
private const val V_STEP = 7
private const val MAX_BYTE = 0xFF
private val ALPHA_OPAQUE: UByte = 255u
private const val UTI_JPEG = "public.jpeg"
