package co.crackn.kompressor.sample.image

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
import kompressor.sample.generated.resources.preset_thumbnail
import kompressor.sample.generated.resources.preset_web
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private val presetLabels = mapOf(
    PresetOption.THUMBNAIL to Res.string.preset_thumbnail,
    PresetOption.WEB to Res.string.preset_web,
    PresetOption.HIGH_QUALITY to Res.string.preset_high_quality,
    PresetOption.CUSTOM to Res.string.preset_custom,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PresetSelector(
    selectedPreset: PresetOption,
    onPresetSelected: (PresetOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PresetOption.entries.forEach { preset ->
            FilterChip(
                selected = preset == selectedPreset,
                onClick = { onPresetSelected(preset) },
                label = { Text(stringResource(presetLabels.getValue(preset))) },
            )
        }
    }
}
