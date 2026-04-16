/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.sample.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedVisibility(visible = isCompressing) {
                Row {
                    CircularProgressIndicator(
                        modifier = Modifier.height(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
            }
            Text(
                text = stringResource(Res.string.compress),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
