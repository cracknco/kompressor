package co.crackn.kompressor.sample.audio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import co.crackn.kompressor.CompressionResult
import co.crackn.kompressor.sample.common.StatsRow
import kompressor.sample.generated.resources.Res
import kompressor.sample.generated.resources.after
import kompressor.sample.generated.resources.before
import org.jetbrains.compose.resources.stringResource

@Composable
fun AudioResultCard(
    visible: Boolean,
    originalPath: String?,
    compressedPath: String?,
    result: CompressionResult?,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 2 },
        modifier = modifier,
    ) {
        if (result != null && originalPath != null && compressedPath != null) {
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
                    AudioPlayerColumn(
                        label = stringResource(Res.string.before),
                        audioPath = originalPath,
                    )
                    AudioPlayerColumn(
                        label = stringResource(Res.string.after),
                        audioPath = compressedPath,
                    )
                    StatsRow(result = result)
                }
            }
        }
    }
}

@Composable
private fun AudioPlayerColumn(
    label: String,
    audioPath: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AudioPlayerBar(audioPath = audioPath)
    }
}
