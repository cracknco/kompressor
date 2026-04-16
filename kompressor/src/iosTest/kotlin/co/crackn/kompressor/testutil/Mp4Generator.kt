/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(ExperimentalForeignApi::class)

package co.crackn.kompressor.testutil

import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.AVFoundation.AVAssetWriter
import platform.AVFoundation.AVAssetWriterInput
import platform.AVFoundation.AVAssetWriterInputPixelBufferAdaptor
import platform.AVFoundation.AVFileTypeMPEG4
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVVideoCodecH264
import platform.AVFoundation.AVVideoCodecKey
import platform.AVFoundation.AVVideoHeightKey
import platform.AVFoundation.AVVideoWidthKey
import platform.AVFoundation.setTransform
import platform.CoreGraphics.CGAffineTransform
import platform.CoreGraphics.CGAffineTransformMakeRotation
import platform.CoreMedia.CMTimeMake
import platform.CoreVideo.CVPixelBufferCreate
import platform.CoreVideo.CVPixelBufferGetBaseAddressOfPlane
import platform.CoreVideo.CVPixelBufferGetBytesPerRowOfPlane
import platform.CoreVideo.CVPixelBufferGetHeightOfPlane
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferRefVar
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.posix.memcpy
import platform.posix.memset

/**
 * Generates minimal valid MP4 video files for iOS tests.
 *
 * Creates solid grey frames encoded with H.264 via [AVAssetWriter].
 */
object Mp4Generator {

    /**
     * Generate a valid MP4 file with the given dimensions and frame count.
     *
     * @return the path of the generated file.
     */
    fun generateMp4(
        outputPath: String,
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
        frameCount: Int = DEFAULT_FRAME_COUNT,
        fps: Int = DEFAULT_FPS,
        rotationDegrees: Int = 0,
    ): String = encodeMp4(
        outputPath = outputPath,
        width = width,
        height = height,
        frameCount = frameCount,
        fps = fps,
        rotationDegrees = rotationDegrees,
        fillPixelBuffer = { buffer, _, _ -> fillGrey(buffer) },
    )

    /**
     * Generate a valid MP4 whose *displayed* frame (after [rotationDegrees] is applied via
     * the track's `preferredTransform`) has coloured corner markers: red at top-left,
     * green at top-right, blue at bottom-left, and black at bottom-right. Used by the
     * rotation sentinel test to catch 90° wrong-way (CW-vs-CCW) rotation bugs that
     * dim-swap assertions miss.
     *
     * The native pixel layout is computed as the inverse of [rotationDegrees] so the
     * player's forward rotation lands each colour in the expected displayed corner.
     */
    fun generateCornerMarkedMp4(
        outputPath: String,
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
        frameCount: Int = DEFAULT_FRAME_COUNT,
        fps: Int = DEFAULT_FPS,
        rotationDegrees: Int = 0,
        markerSize: Int = DEFAULT_MARKER_SIZE,
    ): String {
        val normalisedRotation = ((rotationDegrees % FULL_CIRCLE) + FULL_CIRCLE) % FULL_CIRCLE
        val corners = nativeCornerPositions(normalisedRotation, width, height, markerSize)
        return encodeMp4(
            outputPath = outputPath,
            width = width,
            height = height,
            frameCount = frameCount,
            fps = fps,
            rotationDegrees = rotationDegrees,
            fillPixelBuffer = { buffer, _, _ ->
                fillCornerMarked(buffer, corners, markerSize)
            },
        )
    }

    @Suppress("LongMethod", "LongParameterList")
    private fun encodeMp4(
        outputPath: String,
        width: Int,
        height: Int,
        frameCount: Int,
        fps: Int,
        rotationDegrees: Int,
        fillPixelBuffer: (CVPixelBufferRef, Int, Int) -> Unit,
    ): String {
        NSFileManager.defaultManager.removeItemAtPath(outputPath, null)

        val url = NSURL.fileURLWithPath(outputPath)
        val writer = AVAssetWriter.assetWriterWithURL(url, fileType = AVFileTypeMPEG4, error = null)
            ?: error("Failed to create AVAssetWriter")

        val videoSettings: Map<Any?, *> = mapOf(
            AVVideoCodecKey to AVVideoCodecH264,
            AVVideoWidthKey to width,
            AVVideoHeightKey to height,
        )
        val input = AVAssetWriterInput.assetWriterInputWithMediaType(
            mediaType = AVMediaTypeVideo,
            outputSettings = videoSettings,
        )
        input.expectsMediaDataInRealTime = false
        // Only tag a transform when we actually want one: a 0° source must produce an output
        // with no explicit rotation matrix, so downstream `readTrackRotation` distinguishes
        // "0° because we said so" from "0° because we forgot to set it". Mirrors the Android
        // fixture's `if (normalisedRotation != 0) muxer.setOrientationHint(...)` branch.
        val normalisedRotation = ((rotationDegrees % FULL_CIRCLE) + FULL_CIRCLE) % FULL_CIRCLE
        if (normalisedRotation != 0) {
            input.setTransform(rotationTransform(normalisedRotation))
        }
        // Feed YUV 4:2:0 biplanar (NV12, BT.601 video range) directly to AVAssetWriter so
        // H.264 encoding is effectively pass-through on the colour path — no RGB→YUV matrix
        // is applied. An earlier BGRA fixture produced ~25-unit chroma drift at saturated
        // corners (red decoded as rgb(245,33,25)) which blew through the ±5 sentinel
        // tolerance; writing limited-range YUV directly keeps corner colours within ±3.
        val sourceAttrs: Map<Any?, *> = mapOf(
            platform.CoreVideo.kCVPixelBufferPixelFormatTypeKey to
                kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
            platform.CoreVideo.kCVPixelBufferWidthKey to width,
            platform.CoreVideo.kCVPixelBufferHeightKey to height,
        )
        val adaptor = AVAssetWriterInputPixelBufferAdaptor(
            assetWriterInput = input,
            sourcePixelBufferAttributes = sourceAttrs,
        )
        // addInput throws an Obj-C exception if the settings are unsupported,
        // which crashes the whole test process. canAddInput is the documented
        // way to check first and fail with an actionable Kotlin error.
        check(writer.canAddInput(input)) {
            "AVAssetWriter cannot add video input for ${width}x$height @${fps}fps"
        }
        writer.addInput(input)

        check(writer.startWriting()) { "Failed to start writing: ${writer.error}" }
        writer.startSessionAtSourceTime(CMTimeMake(value = 0, timescale = fps))

        for (frame in 0 until frameCount) {
            while (!input.readyForMoreMediaData) {
                platform.Foundation.NSThread.sleepForTimeInterval(READY_POLL_SEC)
            }
            val pixelBuffer = createPixelBuffer(width, height)
                ?: error("CVPixelBufferCreate failed for ${width}x$height")
            try {
                fillPixelBuffer(pixelBuffer, width, height)
                val pts = CMTimeMake(value = frame.toLong(), timescale = fps)
                check(adaptor.appendPixelBuffer(pixelBuffer, withPresentationTime = pts)) {
                    "appendPixelBuffer failed at frame $frame: ${writer.error}"
                }
            } finally {
                CVPixelBufferRelease(pixelBuffer)
            }
        }

        input.markAsFinished()
        // finishWriting() is synchronous but begins an asynchronous finalization.
        // Use the completion-handler form and busy-wait on writer.status so callers
        // get a file that's actually flushed to disk when we return.
        var done = false
        writer.finishWritingWithCompletionHandler { done = true }
        while (!done && writer.status == platform.AVFoundation.AVAssetWriterStatusWriting) {
            platform.Foundation.NSThread.sleepForTimeInterval(READY_POLL_SEC)
        }
        check(writer.status == platform.AVFoundation.AVAssetWriterStatusCompleted) {
            "AVAssetWriter did not complete: status=${writer.status} error=${writer.error}"
        }

        return outputPath
    }

    // Caller has already normalised [degrees] into [0, 360). Kept as a tiny helper so the
    // radian conversion stays out of the main control flow.
    private fun rotationTransform(degrees: Int): CValue<CGAffineTransform> =
        CGAffineTransformMakeRotation(degrees * kotlin.math.PI / HALF_CIRCLE_D)

    private fun createPixelBuffer(width: Int, height: Int): CVPixelBufferRef? = memScoped {
        val pixelBufferOut = alloc<CVPixelBufferRefVar>()
        CVPixelBufferCreate(
            allocator = null,
            width = width.toULong(),
            height = height.toULong(),
            pixelFormatType = kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
            pixelBufferAttributes = null,
            pixelBufferOut = pixelBufferOut.ptr,
        )
        pixelBufferOut.value
    }

    // Biplanar 4:2:0 (NV12): plane 0 is Y at full resolution, plane 1 is interleaved
    // Cb,Cr,Cb,Cr… at half resolution. Both planes may have `bytesPerRow > width` (row
    // padding for alignment), so we always honour the per-plane stride returned by
    // CoreVideo instead of assuming tight packing.
    private fun fillGrey(buffer: CVPixelBufferRef) {
        CVPixelBufferLockBaseAddress(buffer, 0u)
        try {
            val yAddr = CVPixelBufferGetBaseAddressOfPlane(buffer, 0u)
            if (yAddr != null) {
                val yStride = CVPixelBufferGetBytesPerRowOfPlane(buffer, 0u)
                val yHeight = CVPixelBufferGetHeightOfPlane(buffer, 0u)
                // Y=128 is a neutral mid-grey in limited-range BT.601 (black=16, white=235).
                memset(yAddr, CHROMA_NEUTRAL, yStride * yHeight)
            }
            val uvAddr = CVPixelBufferGetBaseAddressOfPlane(buffer, 1u)
            if (uvAddr != null) {
                val uvStride = CVPixelBufferGetBytesPerRowOfPlane(buffer, 1u)
                val uvHeight = CVPixelBufferGetHeightOfPlane(buffer, 1u)
                // Cb=Cr=128 is the zero-chroma point — neutral (achromatic) colour.
                memset(uvAddr, CHROMA_NEUTRAL, uvStride * uvHeight)
            }
        } finally {
            CVPixelBufferUnlockBaseAddress(buffer, 0u)
        }
    }

    private fun fillCornerMarked(
        buffer: CVPixelBufferRef,
        corners: CornerBlockPositions,
        markerSize: Int,
    ) {
        CVPixelBufferLockBaseAddress(buffer, 0u)
        try {
            val yAddr = CVPixelBufferGetBaseAddressOfPlane(buffer, 0u) ?: return
            val yStride = CVPixelBufferGetBytesPerRowOfPlane(buffer, 0u).toInt()
            val yHeight = CVPixelBufferGetHeightOfPlane(buffer, 0u).toInt()
            val uvAddr = CVPixelBufferGetBaseAddressOfPlane(buffer, 1u) ?: return
            val uvStride = CVPixelBufferGetBytesPerRowOfPlane(buffer, 1u).toInt()
            val uvHeight = CVPixelBufferGetHeightOfPlane(buffer, 1u).toInt()

            val yBytes = ByteArray(yStride * yHeight).apply { fill(BG_Y.toByte()) }
            val uvBytes = ByteArray(uvStride * uvHeight).apply { fill(CHROMA_NEUTRAL.toByte()) }

            paintYuvBlock(yBytes, yStride, uvBytes, uvStride, corners.red, markerSize, RED_YUV)
            paintYuvBlock(yBytes, yStride, uvBytes, uvStride, corners.green, markerSize, GREEN_YUV)
            paintYuvBlock(yBytes, yStride, uvBytes, uvStride, corners.blue, markerSize, BLUE_YUV)
            paintYuvBlock(yBytes, yStride, uvBytes, uvStride, corners.black, markerSize, BLACK_YUV)

            yBytes.usePinned { pinned ->
                memcpy(yAddr, pinned.addressOf(0), yBytes.size.toULong())
            }
            uvBytes.usePinned { pinned ->
                memcpy(uvAddr, pinned.addressOf(0), uvBytes.size.toULong())
            }
        } finally {
            CVPixelBufferUnlockBaseAddress(buffer, 0u)
        }
    }

    @Suppress("LongParameterList")
    private fun paintYuvBlock(
        yBytes: ByteArray,
        yStride: Int,
        uvBytes: ByteArray,
        uvStride: Int,
        position: Pair<Int, Int>,
        size: Int,
        yuv: IntArray,
    ) {
        val (x0, y0) = position
        // Y plane: full-resolution solid block.
        for (dy in 0 until size) {
            val rowStart = (y0 + dy) * yStride
            for (dx in 0 until size) {
                yBytes[rowStart + x0 + dx] = yuv[0].toByte()
            }
        }
        // CbCr plane: half-resolution, interleaved Cb,Cr pairs. A markerSize×markerSize Y
        // block maps to (markerSize/2)×(markerSize/2) chroma samples at native (x/2, y/2).
        val uvBlock = size / 2
        for (dy in 0 until uvBlock) {
            val rowStart = (y0 / 2 + dy) * uvStride
            for (dx in 0 until uvBlock) {
                val off = rowStart + (x0 / 2 + dx) * UV_PAIR_BYTES
                uvBytes[off] = yuv[1].toByte()
                uvBytes[off + 1] = yuv[2].toByte()
            }
        }
    }

    private data class CornerBlockPositions(
        val red: Pair<Int, Int>,
        val green: Pair<Int, Int>,
        val blue: Pair<Int, Int>,
        val black: Pair<Int, Int>,
    )

    /**
     * Native (pre-rotation) top-left coordinates of the four marker blocks so that, once
     * the track's `preferredTransform` is applied at display time, the displayed frame
     * has red/green/blue/black in top-left, top-right, bottom-left, bottom-right.
     *
     * Uses CW display rotation (matches the Android fixture's [MediaMuxer.setOrientationHint]
     * convention). At 90°, native (0, 0) rotates to displayed top-right, so green — the
     * displayed-top-right marker — sits at native top-left, etc.
     */
    private fun nativeCornerPositions(
        rotation: Int,
        width: Int,
        height: Int,
        markerSize: Int,
    ): CornerBlockPositions {
        val tl = 0 to 0
        val tr = (width - markerSize) to 0
        val bl = 0 to (height - markerSize)
        val br = (width - markerSize) to (height - markerSize)
        return when (rotation) {
            0 -> CornerBlockPositions(red = tl, green = tr, blue = bl, black = br)
            QUARTER_TURN -> CornerBlockPositions(red = bl, green = tl, blue = br, black = tr)
            HALF_CIRCLE -> CornerBlockPositions(red = br, green = bl, blue = tr, black = tl)
            THREE_QUARTER_TURN -> CornerBlockPositions(red = tr, green = br, blue = tl, black = bl)
            else -> error("Unsupported rotation for corner-marker fixture: $rotation")
        }
    }

    private const val DEFAULT_WIDTH = 320
    private const val DEFAULT_HEIGHT = 240
    private const val DEFAULT_FRAME_COUNT = 30
    private const val DEFAULT_FPS = 30
    private const val DEFAULT_MARKER_SIZE = 16
    private const val READY_POLL_SEC = 0.01
    private const val FULL_CIRCLE = 360
    private const val HALF_CIRCLE_D = 180.0
    private const val QUARTER_TURN = 90
    private const val HALF_CIRCLE = 180
    private const val THREE_QUARTER_TURN = 270
    private const val UV_PAIR_BYTES = 2

    // BT.601 limited-range RGB → YUV (Y in [16..235], Cb/Cr in [16..240]). Matches the
    // Android fixture exactly. Feeding YUV directly to `AVAssetWriter` via NV12 keeps the
    // colour path effectively pass-through and lets the sentinel read ±3 from the expected
    // sRGB values at each corner; an earlier BGRA source buffer produced ~25-unit chroma
    // drift from the implicit RGB→YUV conversion and blew through the ±5 tolerance.
    private const val BG_Y = 126
    private const val CHROMA_NEUTRAL = 128
    private val RED_YUV = intArrayOf(81, 90, 240)
    private val GREEN_YUV = intArrayOf(145, 54, 34)
    private val BLUE_YUV = intArrayOf(41, 240, 110)
    private val BLACK_YUV = intArrayOf(16, 128, 128)
}
