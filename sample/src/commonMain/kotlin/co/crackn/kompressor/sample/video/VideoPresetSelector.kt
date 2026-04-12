package co.crackn.kompressor.sample.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kompressor.sample.generated.resources.Res
import kompressor.sample.generated.resources.preset_custom
import kompressor.sample.generated.resources.preset_high_quality
import kompressor.sample.generated.resources.preset_low_bandwidth
import kompressor.sample.generated.resources.preset_messaging
import kompressor.sample.generated.resources.preset_social_media
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private fun presetLabel(preset: VideoPresetOption): StringResource = when (preset) {
    VideoPresetOption.MESSAGING -> Res.string.preset_messaging
    VideoPresetOption.HIGH_QUALITY -> Res.string.preset_high_quality
    VideoPresetOption.LOW_BANDWIDTH -> Res.string.preset_low_bandwidth
    VideoPresetOption.SOCIAL_MEDIA -> Res.string.preset_social_media
    VideoPresetOption.CUSTOM -> Res.string.preset_custom
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VideoPresetSelector(
    selectedPreset: VideoPresetOption,
    onPresetSelected: (VideoPresetOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VideoPresetOption.entries.forEach { preset ->
            FilterChip(
                selected = preset == selectedPreset,
                onClick = { onPresetSelected(preset) },
                label = { Text(stringResource(presetLabel(preset))) },
            )
        }
    }
}
