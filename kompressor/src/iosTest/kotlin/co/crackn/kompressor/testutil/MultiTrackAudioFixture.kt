/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(ExperimentalForeignApi::class)

package co.crackn.kompressor.testutil

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVAssetExportPresetPassthrough
import platform.AVFoundation.AVAssetExportSession
import platform.AVFoundation.AVAssetExportSessionStatusCompleted
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVFileTypeMPEG4
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMutableComposition
import platform.AVFoundation.AVMutableCompositionTrack
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.addMutableTrackWithMediaType
import platform.AVFoundation.tracksWithMediaType
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeRangeMake
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.Foundation.NSUUID

/**
 * Builds an MP4 audio file containing multiple independent audio tracks on iOS.
 *
 * Pipeline: for each requested tone, we synthesize a standalone WAV via [WavGenerator] and drop
 * it to a temp file. Each WAV is then opened as an [AVURLAsset] and inserted as its own track
 * in an [AVMutableComposition]. `AVAssetExportSession` with the passthrough preset writes the
 * composition out as a single MP4 that exposes N independent audio tracks to `AVAssetTrack`.
 */
@Suppress("MagicNumber")
object MultiTrackAudioFixture {

    private const val SAMPLE_RATE = 44_100

    /**
     * Create [outputPath] with one mono PCM audio track per entry in [trackFrequencies], each
     * carrying the listed sine tone (Hz) for [durationSec] seconds.
     */
    fun createMultiTrackAudioMp4(
        outputPath: String,
        durationSec: Int,
        trackFrequencies: List<Int>,
    ): String {
        require(durationSec > 0) { "durationSec must be > 0" }
        require(trackFrequencies.isNotEmpty()) { "at least one track required" }

        val tempDir = NSTemporaryDirectory() + "kompressor-multitrack-${NSUUID().UUIDString}/"
        NSFileManager.defaultManager.createDirectoryAtPath(
            tempDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
        try {
            val singleTrackPaths = trackFrequencies.mapIndexed { idx, freq ->
                val path = "$tempDir/track_$idx.wav"
                writeSineMonoWav(path, freq.toDouble(), durationSec)
                path
            }
            composeMultiTrackMp4(outputPath, singleTrackPaths)
            return outputPath
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(tempDir, null)
        }
    }

    /**
     * Write [path] as a mono PCM RIFF/WAV carrying a sine wave at [freq] Hz for [durationSec]
     * seconds. AVFoundation can open WAV files directly, and [WavGenerator] produces compliant
     * headers, so this is the lowest-friction way to build a single-track audio asset.
     */
    private fun writeSineMonoWav(path: String, freq: Double, durationSec: Int) {
        val bytes = WavGenerator.generateWavBytes(
            durationSeconds = durationSec,
            sampleRate = SAMPLE_RATE,
            channels = 1,
            toneFrequency = freq,
        )
        writeBytes(path, bytes)
    }

    private fun composeMultiTrackMp4(outputPath: String, sourcePaths: List<String>) {
        NSFileManager.defaultManager.removeItemAtPath(outputPath, null)
        val composition = AVMutableComposition.composition()

        for (path in sourcePaths) {
            val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
            val srcTrack = asset.tracksWithMediaType(AVMediaTypeAudio).firstOrNull() as? AVAssetTrack
                ?: error("no audio track in $path")
            val dstTrack: AVMutableCompositionTrack = composition.addMutableTrackWithMediaType(
                mediaType = AVMediaTypeAudio,
                preferredTrackID = 0,
            ) ?: error("addMutableTrack failed")
            val range = CMTimeRangeMake(
                start = CMTimeMake(0, 1),
                duration = asset.duration,
            )
            dstTrack.insertTimeRange(
                timeRange = range,
                ofTrack = srcTrack,
                atTime = CMTimeMake(0, 1),
                error = null,
            )
        }

        val session = AVAssetExportSession.exportSessionWithAsset(
            asset = composition,
            presetName = AVAssetExportPresetPassthrough,
        ) ?: error("export session unavailable")
        session.outputURL = NSURL.fileURLWithPath(outputPath)
        session.outputFileType = AVFileTypeMPEG4

        var done = false
        session.exportAsynchronouslyWithCompletionHandler { done = true }
        // Bounded wait: if the completion handler never fires the test rig would otherwise hang
        // the CI worker indefinitely. 30 s is ~30× the observed export time for the fixtures we
        // build here (few-second mono WAVs), so it surfaces real hangs without flaking.
        val pollIntervalSec = 0.01
        val timeoutSec = 30.0
        var waitedSec = 0.0
        while (!done && waitedSec < timeoutSec) {
            NSThread.sleepForTimeInterval(pollIntervalSec)
            waitedSec += pollIntervalSec
        }
        check(done) { "AVAssetExportSession did not complete within ${timeoutSec}s" }
        check(session.status == AVAssetExportSessionStatusCompleted) {
            "export failed: status=${session.status} err=${session.error}"
        }
    }
}
