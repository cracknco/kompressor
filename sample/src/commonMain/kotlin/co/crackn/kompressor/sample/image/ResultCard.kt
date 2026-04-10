package co.crackn.kompressor.sample.image

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import co.crackn.kompressor.CompressionResult
import co.crackn.kompressor.sample.common.StatsRow
import coil3.compose.AsyncImage
import kompressor.sample.generated.resources.Res
import kompressor.sample.generated.resources.after
import kompressor.sample.generated.resources.before
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun ResultCard(
    visible: Boolean,
    inputImagePath: String?,
    outputImagePath: String?,
    result: CompressionResult?,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 2 },
        modifier = modifier,
    ) {
        if (result != null && inputImagePath != null && outputImagePath != null) {
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
                    BeforeAfterImages(
                        inputImagePath = inputImagePath,
                        outputImagePath = outputImagePath,
                    )
                    StatsRow(result = result)
                }
            }
        }
    }
}

@Composable
private fun BeforeAfterImages(
    inputImagePath: String,
    outputImagePath: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ImageColumn(
            labelRes = Res.string.before,
            imagePath = inputImagePath,
            modifier = Modifier.weight(1f),
        )
        ImageColumn(
            labelRes = Res.string.after,
            imagePath = outputImagePath,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ImageColumn(
    labelRes: StringResource,
    imagePath: String,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(labelRes)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        AsyncImage(
            model = "file://$imagePath",
            contentDescription = label,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
        )
    }
}
