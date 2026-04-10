package co.crackn.kompressor

import co.crackn.kompressor.audio.AndroidAudioCompressor
import co.crackn.kompressor.audio.AudioCompressor
import co.crackn.kompressor.image.AndroidImageCompressor
import co.crackn.kompressor.image.ImageCompressor
import co.crackn.kompressor.video.AndroidVideoCompressor
import co.crackn.kompressor.video.VideoCompressor

internal class AndroidKompressor : Kompressor {
    override val image: ImageCompressor by lazy { AndroidImageCompressor() }
    override val video: VideoCompressor by lazy { AndroidVideoCompressor() }
    override val audio: AudioCompressor by lazy { AndroidAudioCompressor() }
}

/** Creates an Android [Kompressor] backed by MediaCodec and BitmapFactory. */
actual fun createKompressor(): Kompressor = AndroidKompressor()
