package co.crackn.kompressor.sample.image

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kompressor.sample.generated.resources.Res
import kompressor.sample.generated.resources.max_height
import kompressor.sample.generated.resources.max_width
import kompressor.sample.generated.resources.pixels_suffix
import kompressor.sample.generated.resources.quality
import org.jetbrains.compose.resources.stringResource

@Composable
fun CustomConfigPanel(
    visible: Boolean,
    quality: Int,
    maxWidth: String,
    maxHeight: String,
    onQualityChanged: (Int) -> Unit,
    onMaxWidthChanged: (String) -> Unit,
    onMaxHeightChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible = visible, modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.quality),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = quality.toFloat(),
                    onValueChange = { onQualityChanged(it.toInt()) },
                    valueRange = 1f..100f,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "$quality",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = maxWidth,
                    onValueChange = { onMaxWidthChanged(it.filter { c -> c.isDigit() }) },
                    label = { Text(stringResource(Res.string.max_width)) },
                    suffix = { Text(stringResource(Res.string.pixels_suffix)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = maxHeight,
                    onValueChange = { onMaxHeightChanged(it.filter { c -> c.isDigit() }) },
                    label = { Text(stringResource(Res.string.max_height)) },
                    suffix = { Text(stringResource(Res.string.pixels_suffix)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
