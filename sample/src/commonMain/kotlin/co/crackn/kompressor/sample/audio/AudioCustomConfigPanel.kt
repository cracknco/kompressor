package co.crackn.kompressor.sample.audio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
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
import co.crackn.kompressor.audio.AudioChannels
import kompressor.sample.generated.resources.Res
import kompressor.sample.generated.resources.bitrate
import kompressor.sample.generated.resources.bitrate_kbps_suffix
import kompressor.sample.generated.resources.channels
import kompressor.sample.generated.resources.mono
import kompressor.sample.generated.resources.sample_rate
import kompressor.sample.generated.resources.sample_rate_22k
import kompressor.sample.generated.resources.sample_rate_44k
import kompressor.sample.generated.resources.sample_rate_48k
import kompressor.sample.generated.resources.stereo
import org.jetbrains.compose.resources.stringResource

private val sampleRateOptions = listOf(
    22_050 to Res.string.sample_rate_22k,
    44_100 to Res.string.sample_rate_44k,
    48_000 to Res.string.sample_rate_48k,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudioCustomConfigPanel(
    visible: Boolean,
    bitrate: String,
    sampleRate: Int,
    channels: AudioChannels,
    onBitrateChanged: (String) -> Unit,
    onSampleRateChanged: (Int) -> Unit,
    onChannelsChanged: (AudioChannels) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible = visible, modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = bitrate,
                onValueChange = { onBitrateChanged(it.filter { c -> c.isDigit() }) },
                label = { Text(stringResource(Res.string.bitrate)) },
                suffix = { Text(stringResource(Res.string.bitrate_kbps_suffix)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = stringResource(Res.string.sample_rate),
                style = MaterialTheme.typography.bodyMedium,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                sampleRateOptions.forEachIndexed { index, (rate, labelRes) ->
                    SegmentedButton(
                        selected = sampleRate == rate,
                        onClick = { onSampleRateChanged(rate) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = sampleRateOptions.size,
                        ),
                    ) {
                        Text(stringResource(labelRes))
                    }
                }
            }

            Text(
                text = stringResource(Res.string.channels),
                style = MaterialTheme.typography.bodyMedium,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = channels == AudioChannels.MONO,
                    onClick = { onChannelsChanged(AudioChannels.MONO) },
                    label = { Text(stringResource(Res.string.mono)) },
                )
                FilterChip(
                    selected = channels == AudioChannels.STEREO,
                    onClick = { onChannelsChanged(AudioChannels.STEREO) },
                    label = { Text(stringResource(Res.string.stereo)) },
                )
            }
        }
    }
}
