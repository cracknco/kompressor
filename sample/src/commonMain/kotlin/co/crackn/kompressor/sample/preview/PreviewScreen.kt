/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.sample.preview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import kompressor.sample.generated.resources.Res
import kompressor.sample.generated.resources.preview_audio_failed
import kompressor.sample.generated.resources.preview_audio_section
import kompressor.sample.generated.resources.preview_frame_timestamp_ms
import kompressor.sample.generated.resources.preview_image_failed
import kompressor.sample.generated.resources.preview_image_section
import kompressor.sample.generated.resources.preview_video_failed
import kompressor.sample.generated.resources.preview_video_section
import kompressor.sample.generated.resources.preview_waveform_computing
import kompressor.sample.generated.resources.select_audio
import kompressor.sample.generated.resources.select_image
import kompressor.sample.generated.resources.select_video
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Three-section "Preview" demo that exercises every M12 preview API end-to-end on the same
 * screen — image thumbnail, video frame slider, audio waveform. Each section is independent: a
 * user can pick all three in any order, and re-picking one cancels its in-flight job without
 * disturbing the others.
 */
@Composable
fun PreviewScreen(
    viewModel: PreviewViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val imageErrorPrefix = stringResource(Res.string.preview_image_failed)
    val videoErrorPrefix = stringResource(Res.string.preview_video_failed)
    val audioErrorPrefix = stringResource(Res.string.preview_audio_failed)

    // Each section's error is observed independently. `SnackbarHostState.showSnackbar` suspends
    // until the snackbar is dismissed, so two sections failing in the same window queue two
    // snackbars; only the section whose snackbar finished is cleared, leaving the other two
    // errors free to surface in turn.
    LaunchedEffect(state.image.error) {
        state.image.error?.let { error ->
            snackbarHostState.showSnackbar("$imageErrorPrefix: $error")
            viewModel.clearImageError()
        }
    }
    LaunchedEffect(state.video.error) {
        state.video.error?.let { error ->
            snackbarHostState.showSnackbar("$videoErrorPrefix: $error")
            viewModel.clearVideoError()
        }
    }
    LaunchedEffect(state.audio.error) {
        state.audio.error?.let { error ->
            snackbarHostState.showSnackbar("$audioErrorPrefix: $error")
            viewModel.clearAudioError()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            ImagePreviewSection(
                state = state.image,
                onImagePicked = viewModel::onImagePicked,
            )
            VideoPreviewSection(
                state = state.video,
                onVideoPicked = viewModel::onVideoPicked,
                onTimestampChanged = viewModel::onFrameTimestampChanged,
            )
            AudioPreviewSection(
                state = state.audio,
                onAudioPicked = viewModel::onAudioPicked,
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun ImagePreviewSection(
    state: ImagePreview,
    onImagePicked: (PlatformFile) -> Unit,
) {
    SectionHeader(title = stringResource(Res.string.preview_image_section))
    val scope = rememberCoroutineScope()
    PickerCard(
        icon = Icons.Filled.Image,
        emptyLabel = stringResource(Res.string.select_image),
        sourceFileName = state.sourceFileName,
        aspectRatio = 16f / 10f,
        onClick = {
            scope.launch {
                val file = FileKit.openFilePicker(type = FileKitType.Image)
                if (file != null) onImagePicked(file)
            }
        },
        content = {
            when {
                state.isComputing -> CenteredSpinner()
                state.thumbnailPath != null -> AsyncImage(
                    model = "file://${state.thumbnailPath}",
                    contentDescription = stringResource(Res.string.preview_image_section),
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Fit,
                )
            }
        },
    )
}

@Composable
private fun VideoPreviewSection(
    state: VideoPreview,
    onVideoPicked: (PlatformFile) -> Unit,
    onTimestampChanged: (Long) -> Unit,
) {
    SectionHeader(title = stringResource(Res.string.preview_video_section))
    val scope = rememberCoroutineScope()
    PickerCard(
        icon = Icons.Filled.Videocam,
        emptyLabel = stringResource(Res.string.select_video),
        sourceFileName = state.sourceFileName,
        aspectRatio = 16f / 10f,
        onClick = {
            scope.launch {
                val file = FileKit.openFilePicker(
                    type = FileKitType.File("mp4", "mov", "avi", "mkv", "webm"),
                )
                if (file != null) onVideoPicked(file)
            }
        },
        content = {
            when {
                state.thumbnailPath != null -> AsyncImage(
                    model = "file://${state.thumbnailPath}",
                    contentDescription = stringResource(Res.string.preview_video_section),
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Fit,
                )
                state.isComputing -> CenteredSpinner()
            }
        },
    )
    if (state.sourcePath != null && state.durationMs > 0L) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Slider(
                value = state.atMillis.toFloat(),
                onValueChange = { onTimestampChanged(it.toLong()) },
                valueRange = 0f..state.durationMs.toFloat(),
            )
            Text(
                text = stringResource(Res.string.preview_frame_timestamp_ms, state.atMillis.toString()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AudioPreviewSection(
    state: AudioPreview,
    onAudioPicked: (PlatformFile) -> Unit,
) {
    SectionHeader(title = stringResource(Res.string.preview_audio_section))
    val scope = rememberCoroutineScope()
    OutlinedCard(
        onClick = {
            scope.launch {
                val file = FileKit.openFilePicker(
                    type = FileKitType.File("mp3", "m4a", "wav", "aac", "ogg", "flac"),
                )
                if (file != null) onAudioPicked(file)
            }
        },
        modifier = Modifier.fillMaxWidth().height(WAVEFORM_CARD_HEIGHT),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = 2.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            when {
                state.peaks != null -> WaveformCanvas(
                    peaks = state.peaks,
                    modifier = Modifier.align(Alignment.Center),
                )
                state.isComputing -> WaveformProgress(progress = state.progress)
                else -> EmptyPickerHint(
                    icon = Icons.Filled.MusicNote,
                    label = stringResource(Res.string.select_audio),
                )
            }
        }
    }
    if (state.sourceFileName != null && state.peaks != null) {
        Text(
            text = state.sourceFileName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun WaveformProgress(progress: Float) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.GraphicEq,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(Res.string.preview_waveform_computing),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun PickerCard(
    icon: ImageVector,
    emptyLabel: String,
    sourceFileName: String?,
    aspectRatio: Float,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().aspectRatio(aspectRatio),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = 2.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (sourceFileName == null) {
                EmptyPickerHint(icon = icon, label = emptyLabel)
            } else {
                content()
            }
        }
    }
}

@Composable
private fun EmptyPickerHint(icon: ImageVector, label: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CenteredSpinner() {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator()
    }
}

private val WAVEFORM_CARD_HEIGHT = 160.dp
