/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.sample.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Renders a `FloatArray` of normalized peak amplitudes (each in `[0f, 1f]`, the convention
 * returned by `AudioCompressor.waveform`) as a centred bar viz. Each peak becomes one vertical
 * line whose half-height is `peak * canvasHalfHeight`, drawn from the canvas mid-line outward
 * — the same shape voice-note bubbles use in WhatsApp / Telegram / iMessage.
 */
@Composable
fun WaveformCanvas(
    peaks: FloatArray,
    modifier: Modifier = Modifier,
    height: Dp = DEFAULT_HEIGHT,
    barColor: Color = MaterialTheme.colorScheme.primary,
) {
    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        if (peaks.isEmpty()) return@Canvas
        val canvasWidth = size.width
        val midY = size.height / 2f
        // Bar slot: total width / count. The drawn line uses half of that, so adjacent bars
        // have visible gutters even at high counts.
        val slotWidth = canvasWidth / peaks.size
        val strokeWidth = (slotWidth * BAR_WIDTH_FRACTION).coerceAtLeast(MIN_STROKE_PX)
        peaks.forEachIndexed { index, peak ->
            val centreX = (index + 0.5f) * slotWidth
            val halfHeight = peak.coerceIn(0f, 1f) * midY
            drawLine(
                color = barColor,
                start = Offset(centreX, midY - halfHeight),
                end = Offset(centreX, midY + halfHeight),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

private val DEFAULT_HEIGHT = 96.dp
private const val BAR_WIDTH_FRACTION = 0.5f
private const val MIN_STROKE_PX = 1.5f
