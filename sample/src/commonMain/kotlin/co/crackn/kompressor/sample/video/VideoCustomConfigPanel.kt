/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.sample.video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import co.crackn.kompressor.video.MaxResolution
import kompressor.sample.generated.resources.Res
import kompressor.sample.generated.resources.bitrate_kbps_suffix
import kompressor.sample.generated.resources.frame_rate_suffix
import kompressor.sample.generated.resources.key_frame_interval
import kompressor.sample.generated.resources.key_frame_interval_suffix
import kompressor.sample.generated.resources.max_frame_rate
import kompressor.sample.generated.resources.max_resolution
import kompressor.sample.generated.resources.resolution_1080p
import kompressor.sample.generated.resources.resolution_480p
import kompressor.sample.generated.resources.resolution_720p
import kompressor.sample.generated.resources.resolution_original
import kompressor.sample.generated.resources.video_bitrate
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private val resolutionOptions = listOf(
    MaxResolution.SD_480 to Res.string.resolution_480p,
    MaxResolution.HD_720 to Res.string.resolution_720p,
    MaxResolution.HD_1080 to Res.string.resolution_1080p,
    MaxResolution.Original to Res.string.resolution_original,
)

private val frameRateOptions = listOf(24, 30, 60)

private val keyFrameIntervalOptions = listOf(1, 2, 3)

@Composable
fun VideoCustomConfigPanel(
    visible: Boolean,
    videoBitrate: String,
    maxResolution: MaxResolution,
    maxFrameRate: Int,
    keyFrameInterval: Int,
    onVideoBitrateChanged: (String) -> Unit,
    onMaxResolutionChanged: (MaxResolution) -> Unit,
    onMaxFrameRateChanged: (Int) -> Unit,
    onKeyFrameIntervalChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible = visible, modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = videoBitrate,
                onValueChange = { onVideoBitrateChanged(it.filter { c -> c.isDigit() }) },
                label = { Text(stringResource(Res.string.video_bitrate)) },
                suffix = { Text(stringResource(Res.string.bitrate_kbps_suffix)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = stringResource(Res.string.max_resolution),
                style = MaterialTheme.typography.bodyMedium,
            )
            SegmentedButtonRow(
                options = resolutionOptions,
                selected = maxResolution,
                onSelected = onMaxResolutionChanged,
            )

            Text(
                text = stringResource(Res.string.max_frame_rate),
                style = MaterialTheme.typography.bodyMedium,
            )
            SegmentedIntRow(
                options = frameRateOptions,
                selected = maxFrameRate,
                onSelected = onMaxFrameRateChanged,
                suffix = stringResource(Res.string.frame_rate_suffix),
            )

            Text(
                text = stringResource(Res.string.key_frame_interval),
                style = MaterialTheme.typography.bodyMedium,
            )
            SegmentedIntRow(
                options = keyFrameIntervalOptions,
                selected = keyFrameInterval,
                onSelected = onKeyFrameIntervalChanged,
                suffix = stringResource(Res.string.key_frame_interval_suffix),
            )
        }
    }
}

@Composable
private fun <T> SegmentedButtonRow(
    options: List<Pair<T, StringResource>>,
    selected: T,
    onSelected: (T) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, labelRes) ->
            SegmentedButton(
                selected = selected == value,
                onClick = { onSelected(value) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = options.size,
                ),
            ) {
                Text(stringResource(labelRes))
            }
        }
    }
}

@Composable
private fun SegmentedIntRow(
    options: List<Int>,
    selected: Int,
    onSelected: (Int) -> Unit,
    suffix: String,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, value ->
            SegmentedButton(
                selected = selected == value,
                onClick = { onSelected(value) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = options.size,
                ),
            ) {
                Text("$value $suffix")
            }
        }
    }
}
