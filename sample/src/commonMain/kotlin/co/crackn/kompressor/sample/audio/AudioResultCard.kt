package co.crackn.kompressor.sample.audio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.crackn.kompressor.CompressionResult
import co.crackn.kompressor.sample.common.formatFileSize
import kompressor.sample.generated.resources.Res
import kompressor.sample.generated.resources.compression_ratio
import kompressor.sample.generated.resources.duration
import kompressor.sample.generated.resources.duration_ms
import kompressor.sample.generated.resources.input_size
import kompressor.sample.generated.resources.output_size
import kompressor.sample.generated.resources.size_increase_percent
import kompressor.sample.generated.resources.size_reduction_percent
import org.jetbrains.compose.resources.stringResource

@Composable
fun AudioResultCard(
    visible: Boolean,
    fileName: String?,
    result: CompressionResult?,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 2 },
        modifier = modifier,
    ) {
        if (result != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        if (fileName != null) {
                            Text(
                                text = fileName,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                    StatsRow(result = result)
                }
            }
        }
    }
}

@Composable
private fun StatsRow(result: CompressionResult) {
    val deltaPercent = ((1f - result.compressionRatio) * PERCENT_MULTIPLIER).toInt()
    val sizeChangeText = if (deltaPercent >= 0) {
        stringResource(Res.string.size_reduction_percent, "${deltaPercent}%")
    } else {
        stringResource(Res.string.size_increase_percent, "${-deltaPercent}%")
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        StatItem(
            label = stringResource(Res.string.input_size),
            value = formatFileSize(result.inputSize),
        )
        StatItem(
            label = stringResource(Res.string.output_size),
            value = formatFileSize(result.outputSize),
        )
        StatItem(
            label = stringResource(Res.string.compression_ratio),
            value = sizeChangeText,
        )
        StatItem(
            label = stringResource(Res.string.duration),
            value = stringResource(Res.string.duration_ms, result.durationMs.toString()),
        )
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val PERCENT_MULTIPLIER = 100
