package co.crackn.kompressor.sample.video

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import chaintech.videoplayer.host.MediaPlayerHost
import chaintech.videoplayer.ui.video.VideoPlayerComposable

private const val VIDEO_ASPECT_RATIO = 16f / 9f

@Composable
fun VideoPlayerBar(
    videoPath: String,
    modifier: Modifier = Modifier,
) {
    val mediaPlayerHost = remember(videoPath) {
        MediaPlayerHost("file://$videoPath")
    }
    DisposableEffect(videoPath) {
        onDispose { mediaPlayerHost.pause() }
    }
    VideoPlayerComposable(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(VIDEO_ASPECT_RATIO)
            .clip(RoundedCornerShape(8.dp)),
        playerHost = mediaPlayerHost,
    )
}
