/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.sample.audio

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
import kompressor.sample.generated.resources.preset_podcast
import kompressor.sample.generated.resources.preset_voice_message
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private fun presetLabel(preset: AudioPresetOption): StringResource = when (preset) {
    AudioPresetOption.VOICE_MESSAGE -> Res.string.preset_voice_message
    AudioPresetOption.PODCAST -> Res.string.preset_podcast
    AudioPresetOption.HIGH_QUALITY -> Res.string.preset_high_quality
    AudioPresetOption.CUSTOM -> Res.string.preset_custom
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudioPresetSelector(
    selectedPreset: AudioPresetOption,
    onPresetSelected: (AudioPresetOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AudioPresetOption.entries.forEach { preset ->
            FilterChip(
                selected = preset == selectedPreset,
                onClick = { onPresetSelected(preset) },
                label = { Text(stringResource(presetLabel(preset))) },
            )
        }
    }
}
