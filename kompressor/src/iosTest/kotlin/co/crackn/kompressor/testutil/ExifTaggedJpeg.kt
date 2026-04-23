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
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSURL
import platform.ImageIO.CGImageDestinationAddImage
import platform.ImageIO.CGImageDestinationCreateWithURL
import platform.ImageIO.CGImageDestinationFinalize
import platform.ImageIO.CGImageDestinationRef
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
@Suppress("UNCHECKED_CAST")
fun createExifTaggedJpeg(testDir: String, width: Int, height: Int, orientation: Int): String {
    val pixels = buildGradientPixels(width, height)
    val outPath = testDir + "exif_${orientation}_${width}x$height.jpg"
    pixels.usePinned { pinned ->
        // Core Graphics "Create" rule: CGColorSpaceCreateDeviceRGB, CGBitmapContextCreate,
        // and CGBitmapContextCreateImage all return +1 retained objects. Every branch — happy
        // path AND the `checkNotNull` failure — must release via CGColorSpaceRelease /
        // CGContextRelease / CGImageRelease, otherwise repeated test-suite runs accumulate
        // native leaks on the simulator.
        val colorSpace = CGColorSpaceCreateDeviceRGB()
        try {
            val ctx = checkNotNull(
                CGBitmapContextCreate(
                    data = pinned.addressOf(0) as kotlinx.cinterop.CValuesRef<kotlinx.cinterop.UByteVar>,
                    width = width.toULong(),
                    height = height.toULong(),
                    bitsPerComponent = BITS_PER_COMPONENT,
                    bytesPerRow = (width * BYTES_PER_PIXEL).toULong(),
                    space = colorSpace,
                    bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
                ),
            ) { "CGBitmapContextCreate failed" }
            try {
                val cgImage = checkNotNull(CGBitmapContextCreateImage(ctx)) { "CGBitmapContextCreateImage failed" }
                try {
                    writeJpegWithOrientation(cgImage, outPath, orientation)
                } finally {
                    CGImageRelease(cgImage)
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

/**
 * Encodes [cgImage] to [outPath] as a JPEG carrying [orientation] in its
 * `kCGImagePropertyOrientation` metadata. Split out of [createExifTaggedJpeg] so the
 * CG-native "Create" rule lifecycle (colour space / bitmap context / cgImage) stays
 * visible in one block without the ImageIO / CF bridging ladder interleaving on top.
 */
@Suppress("UNCHECKED_CAST")
private fun writeJpegWithOrientation(
    cgImage: CGImageRef,
    outPath: String,
    orientation: Int,
) {
    val urlCF = CFBridgingRetain(NSURL.fileURLWithPath(outPath)) as CFURLRef?
        ?: error("Failed to bridge URL")
    val utiCF = CFBridgingRetain(UTI_JPEG) as CFStringRef?
        ?: error("Failed to bridge UTI")
    try {
        val destination = CGImageDestinationCreateWithURL(urlCF, utiCF, 1u, null)
            ?: error("Failed to create CGImageDestination for $outPath")
        try {
            writeImageWithOrientationDict(destination, cgImage, orientation, outPath)
        } finally {
            CFRelease(destination)
        }
    } finally {
        CFRelease(utiCF)
        CFRelease(urlCF)
    }
}

@Suppress("UNCHECKED_CAST")
private fun writeImageWithOrientationDict(
    destination: CGImageDestinationRef,
    cgImage: CGImageRef,
    orientation: Int,
    outPath: String,
) = memScoped {
    val orientationVar = alloc<IntVar>().apply { value = orientation }
    val orientationNumber: CFNumberRef = checkNotNull(
        CFNumberCreate(kCFAllocatorDefault, kCFNumberIntType, orientationVar.ptr),
    ) { "CFNumberCreate failed" }
    try {
        val keys = allocArray<COpaquePointerVar>(1)
        val values = allocArray<COpaquePointerVar>(1)
        keys[0] = kCGImagePropertyOrientation as COpaquePointer?
        values[0] = orientationNumber as COpaquePointer
        val dict = CFDictionaryCreate(
            kCFAllocatorDefault, keys, values, 1,
            kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr,
        )
        try {
            CGImageDestinationAddImage(destination, cgImage, dict)
            check(CGImageDestinationFinalize(destination)) {
                "CGImageDestinationFinalize returned false for $outPath"
            }
        } finally {
            if (dict != null) CFRelease(dict)
        }
    } finally {
        CFRelease(orientationNumber)
    }
}

/**
 * Fills a [width]×[height] RGBA buffer with a non-uniform gradient pattern. Extracted from
 * [createExifTaggedJpeg] so the main factory fits under the Detekt length limit without a
 * suppression; the gradient shape itself matters only insofar as a degenerate "all-zeroes"
 * JPEG can confuse CGImageSource's auto-format detection.
 */
private fun buildGradientPixels(width: Int, height: Int): UByteArray {
    val pixels = UByteArray(width * height * BYTES_PER_PIXEL)
    var v = 0
    for (i in pixels.indices step BYTES_PER_PIXEL) {
        pixels[i] = (v and MAX_BYTE).toUByte() // R
        pixels[i + 1] = ((v + V_STEP) and MAX_BYTE).toUByte() // G
        pixels[i + 2] = ((v + V_STEP * 2) and MAX_BYTE).toUByte() // B
        pixels[i + 3] = ALPHA_OPAQUE
        v++
    }
    return pixels
}

private const val BYTES_PER_PIXEL = 4
private const val BITS_PER_COMPONENT: ULong = 8u
private const val V_STEP = 7
private const val MAX_BYTE = 0xFF
private val ALPHA_OPAQUE: UByte = 255u
private const val UTI_JPEG = "public.jpeg"
