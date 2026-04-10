package co.crackn.kompressor.sample.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import co.crackn.kompressor.CompressionResult
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
fun StatsRow(
    result: CompressionResult,
    modifier: Modifier = Modifier,
) {
    val deltaPercent = ((1f - result.compressionRatio) * PERCENT_MULTIPLIER).toInt()
    val sizeChangeText = if (deltaPercent >= 0) {
        stringResource(Res.string.size_reduction_percent, "${deltaPercent}%")
    } else {
        stringResource(Res.string.size_increase_percent, "${-deltaPercent}%")
    }

    Row(
        modifier = modifier.fillMaxWidth(),
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
