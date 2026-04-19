/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.sample.capabilities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import co.crackn.kompressor.CodecSupport
import co.crackn.kompressor.SourceMediaInfo
import co.crackn.kompressor.Supportability
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import kompressor.sample.generated.resources.Res
import kompressor.sample.generated.resources.capabilities_10bit
import kompressor.sample.generated.resources.capabilities_audio_decoders
import kompressor.sample.generated.resources.capabilities_audio_encoders
import kompressor.sample.generated.resources.capabilities_badge_hw
import kompressor.sample.generated.resources.capabilities_badge_sw
import kompressor.sample.generated.resources.capabilities_device
import kompressor.sample.generated.resources.capabilities_hdr
import kompressor.sample.generated.resources.capabilities_probe_card_title
import kompressor.sample.generated.resources.capabilities_probe_pick
import kompressor.sample.generated.resources.capabilities_probe_supported
import kompressor.sample.generated.resources.capabilities_probe_unknown
import kompressor.sample.generated.resources.capabilities_probe_unsupported
import kompressor.sample.generated.resources.capabilities_video_decoders
import kompressor.sample.generated.resources.capabilities_video_encoders
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun CapabilitiesScreen(
    viewModel: CapabilitiesViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DeviceHeader(summary = state.deviceSummary)

        CodecSection(title = stringResource(Res.string.capabilities_video_decoders), items = state.videoDecoders)
        CodecSection(title = stringResource(Res.string.capabilities_video_encoders), items = state.videoEncoders)
        CodecSection(title = stringResource(Res.string.capabilities_audio_decoders), items = state.audioDecoders)
        CodecSection(title = stringResource(Res.string.capabilities_audio_encoders), items = state.audioEncoders)

        ProbeCard(
            fileName = state.probeFileName,
            info = state.probeInfo,
            verdict = state.probeVerdict,
            error = state.probeError,
            onPickFile = {
                scope.launch {
                    val file = FileKit.openFilePicker(
                        type = FileKitType.File("mp4", "mov", "avi", "mkv", "webm"),
                    )
                    if (file != null) viewModel.onFilePicked(file)
                }
            },
        )
    }
}

@Composable
private fun DeviceHeader(summary: String) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(Res.string.capabilities_device),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = summary.ifBlank { "…" },
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun CodecSection(title: String, items: List<CodecSupport>) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Column {
                items.forEachIndexed { index, codec ->
                    CodecRow(codec)
                    if (index != items.lastIndex) HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun CodecRow(codec: CodecSupport) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = codec.mimeType, style = MaterialTheme.typography.bodyMedium)
            Badge(
                text = stringResource(
                    if (codec.hardwareAccelerated) Res.string.capabilities_badge_hw
                    else Res.string.capabilities_badge_sw,
                ),
                highlight = codec.hardwareAccelerated,
            )
            VariantBadges(codec)
        }
        codec.codecName?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (codec.profiles.isNotEmpty()) {
            Text(
                text = codec.profiles.joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        VariantDetails(codec)
    }
}

@Composable
private fun VariantBadges(codec: CodecSupport) {
    when (codec) {
        is CodecSupport.Video -> {
            if (codec.supports10Bit) Badge(stringResource(Res.string.capabilities_10bit), highlight = true)
            if (codec.supportsHdr) Badge(stringResource(Res.string.capabilities_hdr), highlight = true)
        }
        is CodecSupport.Audio -> Unit
    }
}

@Composable
private fun VariantDetails(codec: CodecSupport) {
    val text = when (codec) {
        is CodecSupport.Video -> codec.maxResolution?.let { (w, h) ->
            val fps = codec.maxFrameRate?.let { " @ ${it}fps" }.orEmpty()
            "${w}x$h$fps"
        }
        is CodecSupport.Audio -> buildList {
            codec.maxChannels?.let { add("${it}ch") }
            if (codec.sampleRates.isNotEmpty()) {
                add("${codec.sampleRates.min()}–${codec.sampleRates.max()} Hz")
            }
        }.takeIf { it.isNotEmpty() }?.joinToString(" • ")
    }
    if (text != null) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Badge(text: String, highlight: Boolean) {
    val bg = if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (highlight) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = bg, shape = RoundedCornerShape(6.dp)) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = fg,
        )
    }
}

@Composable
private fun ProbeCard(
    fileName: String?,
    info: SourceMediaInfo?,
    verdict: Supportability?,
    error: String?,
    onPickFile: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(Res.string.capabilities_probe_card_title),
                style = MaterialTheme.typography.titleMedium,
            )
            FilledTonalButton(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.capabilities_probe_pick))
            }
            fileName?.let {
                Text(text = it, style = MaterialTheme.typography.bodyMedium)
            }
            info?.let { ProbeInfoLines(it) }
            verdict?.let { Verdict(it) }
            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ProbeInfoLines(info: SourceMediaInfo) {
    val lines = buildList {
        info.videoCodec?.let {
            val profile = info.videoProfile?.let { p -> " $p" }.orEmpty()
            val level = info.videoLevel?.let { l -> " $l" }.orEmpty()
            add("Video: $it$profile$level")
        }
        if (info.width != null && info.height != null) add("${info.width}x${info.height}")
        info.bitDepth?.let { add("${it}-bit") }
        info.frameRate?.let { add("${it.toInt()} fps") }
        info.audioCodec?.let { add("Audio: $it") }
        info.durationMs?.let { add("${it / MILLIS_PER_SEC}s") }
    }
    Column {
        lines.forEach {
            Text(text = it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Verdict(v: Supportability) {
    val (color, label, reasons) = when (v) {
        is Supportability.Supported ->
            Triple(Color(0xFF2E7D32), stringResource(Res.string.capabilities_probe_supported), emptyList())
        is Supportability.Unsupported ->
            Triple(MaterialTheme.colorScheme.error, stringResource(Res.string.capabilities_probe_unsupported), v.reasons)
        is Supportability.Unknown ->
            Triple(Color(0xFFF57C00), stringResource(Res.string.capabilities_probe_unknown), v.reasons)
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = label, style = MaterialTheme.typography.titleSmall, color = color)
        reasons.forEach {
            Text(text = "• $it", style = MaterialTheme.typography.bodySmall, color = color)
        }
    }
}

private const val MILLIS_PER_SEC = 1000
