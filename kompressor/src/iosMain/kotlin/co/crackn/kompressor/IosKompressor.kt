package co.crackn.kompressor

import co.crackn.kompressor.audio.AudioCompressor
import co.crackn.kompressor.audio.IosAudioCompressor
import co.crackn.kompressor.image.ImageCompressor
import co.crackn.kompressor.image.IosImageCompressor
import co.crackn.kompressor.video.IosVideoCompressor
import co.crackn.kompressor.video.VideoCompressor

internal class IosKompressor : Kompressor {
    override val image: ImageCompressor by lazy(LazyThreadSafetyMode.NONE) { IosImageCompressor() }
    override val video: VideoCompressor by lazy(LazyThreadSafetyMode.NONE) { IosVideoCompressor() }
    override val audio: AudioCompressor by lazy(LazyThreadSafetyMode.NONE) { IosAudioCompressor() }
}

/** Creates an iOS [Kompressor] backed by AVFoundation and VideoToolbox. */
actual fun createKompressor(): Kompressor = IosKompressor()
