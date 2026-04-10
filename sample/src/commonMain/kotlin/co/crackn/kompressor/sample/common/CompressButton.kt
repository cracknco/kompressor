package co.crackn.kompressor.sample.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kompressor.sample.generated.resources.Res
import kompressor.sample.generated.resources.compress
import org.jetbrains.compose.resources.stringResource

@Composable
fun CompressButton(
    isCompressing: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !isCompressing,
        modifier = modifier.fillMaxWidth().height(52.dp),
    ) {
        if (isCompressing) {
            CircularProgressIndicator(
                modifier = Modifier.height(24.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text(
                text = stringResource(Res.string.compress),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
