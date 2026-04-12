@file:OptIn(ExperimentalForeignApi::class)

package co.crackn.kompressor.testutil

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreVideo.CVPixelBufferRefVar
import platform.AVFoundation.AVAssetWriter
import platform.AVFoundation.AVAssetWriterInput
import platform.AVFoundation.AVAssetWriterInputPixelBufferAdaptor
import platform.AVFoundation.AVFileTypeMPEG4
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVVideoCodecH264
import platform.AVFoundation.AVVideoCodecKey
import platform.AVFoundation.AVVideoHeightKey
import platform.AVFoundation.AVVideoWidthKey
import platform.CoreMedia.CMTimeMake
import platform.CoreVideo.CVPixelBufferCreate
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelFormatType_32ARGB
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
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
        val adaptor = AVAssetWriterInputPixelBufferAdaptor(
            assetWriterInput = input,
            sourcePixelBufferAttributes = null,
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
            val pixelBuffer = createGreyPixelBuffer(width, height)
                ?: error("CVPixelBufferCreate failed for ${width}x$height")
            try {
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

    private fun createGreyPixelBuffer(width: Int, height: Int): CVPixelBufferRef? = memScoped {
        val pixelBufferOut = alloc<CVPixelBufferRefVar>()
        CVPixelBufferCreate(
            allocator = null,
            width = width.toULong(),
            height = height.toULong(),
            pixelFormatType = kCVPixelFormatType_32ARGB,
            pixelBufferAttributes = null,
            pixelBufferOut = pixelBufferOut.ptr,
        )
        val buffer = pixelBufferOut.value

        if (buffer != null) {
            CVPixelBufferLockBaseAddress(buffer, 0u)
            val baseAddress = CVPixelBufferGetBaseAddress(buffer)
            val bytesPerRow = CVPixelBufferGetBytesPerRow(buffer)
            val h = CVPixelBufferGetHeight(buffer)
            if (baseAddress != null) {
                memset(baseAddress, GREY_VALUE, bytesPerRow * h)
            }
            CVPixelBufferUnlockBaseAddress(buffer, 0u)
        }
        buffer
    }

    private const val DEFAULT_WIDTH = 320
    private const val DEFAULT_HEIGHT = 240
    private const val DEFAULT_FRAME_COUNT = 30
    private const val DEFAULT_FPS = 30
    private const val GREY_VALUE = 128
    private const val READY_POLL_SEC = 0.01
}
