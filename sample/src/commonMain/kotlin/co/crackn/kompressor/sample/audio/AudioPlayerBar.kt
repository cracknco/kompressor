package co.crackn.kompressor.sample.audio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PauseCircleFilled
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.iamkonstantin.kotlin.gadulka.GadulkaPlayerState
import eu.iamkonstantin.kotlin.gadulka.rememberGadulkaLiveState

/**
 * A compact, messaging-style audio player bar.
 *
 * Displays a play/pause button, seek slider, and elapsed/total time label.
 */
@Composable
fun AudioPlayerBar(
    audioPath: String,
    modifier: Modifier = Modifier,
) {
    val liveState = rememberGadulkaLiveState()
    val player = liveState.player
    val position = liveState.position
    val duration = liveState.duration
    val isPlaying = liveState.state == GadulkaPlayerState.PLAYING ||
        liveState.state == GadulkaPlayerState.BUFFERING
    val progress = if (duration > 0) position.toFloat() / duration.toFloat() else 0f

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(
            onClick = {
                if (isPlaying) player.pause() else player.play("file://$audioPath")
            },
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.PauseCircleFilled else Icons.Filled.PlayCircleFilled,
                contentDescription = if (isPlaying) "Pause" else "Play",
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = progress,
            onValueChange = { value ->
                if (duration > 0) player.seekTo((value * duration).toLong())
            },
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${formatTime(position)} / ${formatTime(duration)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / MS_PER_SECOND).toInt()
    val minutes = totalSeconds / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

private const val MS_PER_SECOND = 1_000
private const val SECONDS_PER_MINUTE = 60
