/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.sample.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import co.crackn.kompressor.io.CompressionProgress
import kompressor.sample.generated.resources.Res
import kompressor.sample.generated.resources.compressing
import kompressor.sample.generated.resources.compressing_percent
import kompressor.sample.generated.resources.phase_finalising
import kompressor.sample.generated.resources.phase_preparing
import org.jetbrains.compose.resources.stringResource

/**
 * Shows a progress indicator during compression.
 *
 * When [progress] is non-null, a determinate bar with percentage is displayed and the label
 * adapts to [phase] — `MATERIALIZING_INPUT` shows "Preparing…", `COMPRESSING` shows
 * "Compressing X%", `FINALIZING_OUTPUT` shows "Finalising…". When [progress] is null, an
 * indeterminate bar with a generic label is shown.
 */
@Composable
fun ProgressSection(
    visible: Boolean,
    progress: Float? = null,
    phase: CompressionProgress.Phase? = null,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible = visible, modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (progress != null) {
                val clampedProgress = progress.coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { clampedProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
                val label = when (phase) {
                    CompressionProgress.Phase.MATERIALIZING_INPUT -> stringResource(Res.string.phase_preparing)
                    CompressionProgress.Phase.FINALIZING_OUTPUT -> stringResource(Res.string.phase_finalising)
                    CompressionProgress.Phase.COMPRESSING, null -> stringResource(
                        Res.string.compressing_percent,
                        "${(clampedProgress * 100).toInt()}%",
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = stringResource(Res.string.compressing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
