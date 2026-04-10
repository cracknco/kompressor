package co.crackn.kompressor

import co.crackn.kompressor.audio.AudioCompressor
import co.crackn.kompressor.audio.IosAudioCompressor
import co.crackn.kompressor.image.ImageCompressor
import co.crackn.kompressor.image.IosImageCompressor
import co.crackn.kompressor.video.IosVideoCompressor
import co.crackn.kompressor.video.VideoCompressor

internal class IosKompressor : Kompressor {
    override val image: ImageCompressor by lazy { IosImageCompressor() }
    override val video: VideoCompressor by lazy { IosVideoCompressor() }
    override val audio: AudioCompressor by lazy { IosAudioCompressor() }
}

actual fun createKompressor(): Kompressor = IosKompressor()
