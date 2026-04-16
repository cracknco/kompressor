/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.sample.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import co.crackn.kompressor.sample.common.CompressButton
import co.crackn.kompressor.sample.common.ProgressSection
import kompressor.sample.generated.resources.Res
import kompressor.sample.generated.resources.error_compression_failed
import kompressor.sample.generated.resources.error_video_decoding_failed
import kompressor.sample.generated.resources.error_video_encoding_failed
import kompressor.sample.generated.resources.error_video_io_failed
import kompressor.sample.generated.resources.error_video_unsupported_format
import kompressor.sample.generated.resources.ok
import kompressor.sample.generated.resources.try_another
import org.jetbrains.compose.resources.stringResource

@Composable
fun VideoScreen(
    viewModel: VideoCompressViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val errorTitle = stringResource(Res.string.error_compression_failed)
    val unsupportedMsg = stringResource(Res.string.error_video_unsupported_format)
    val decodingMsg = stringResource(Res.string.error_video_decoding_failed)
    val encodingMsg = stringResource(Res.string.error_video_encoding_failed)
    val ioMsg = stringResource(Res.string.error_video_io_failed)

    state.error?.let { rawError ->
        val localized = when (state.errorKind) {
            VideoErrorKind.UnsupportedFormat -> unsupportedMsg
            VideoErrorKind.DecodingFailed -> decodingMsg
            VideoErrorKind.EncodingFailed -> encodingMsg
            VideoErrorKind.IoFailed -> ioMsg
            VideoErrorKind.Other, null -> rawError
        }
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            title = { Text(errorTitle) },
            text = { Text(localized) },
            confirmButton = {
                TextButton(onClick = viewModel::clearError) { Text(stringResource(Res.string.ok)) }
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        VideoPickerSection(
            selectedFileName = state.selectedFileName,
            onVideoPicked = viewModel::onVideoPicked,
        )

        if (state.selectedVideoPath != null && state.result == null) {
            VideoPresetSelector(
                selectedPreset = state.selectedPreset,
                onPresetSelected = viewModel::onPresetSelected,
            )

            VideoCustomConfigPanel(
                visible = state.selectedPreset == VideoPresetOption.CUSTOM,
                videoBitrate = state.customVideoBitrate,
                maxResolution = state.customMaxResolution,
                maxFrameRate = state.customMaxFrameRate,
                keyFrameInterval = state.customKeyFrameInterval,
                onVideoBitrateChanged = viewModel::onCustomVideoBitrateChanged,
                onMaxResolutionChanged = viewModel::onCustomMaxResolutionChanged,
                onMaxFrameRateChanged = viewModel::onCustomMaxFrameRateChanged,
                onKeyFrameIntervalChanged = viewModel::onCustomKeyFrameIntervalChanged,
            )

            ProgressSection(
                visible = state.isCompressing,
                progress = state.progress,
            )

            CompressButton(
                isCompressing = state.isCompressing,
                enabled = true,
                onClick = viewModel::compress,
            )
        }

        VideoResultCard(
            visible = state.result != null,
            originalPath = state.selectedVideoPath,
            compressedPath = state.compressedVideoPath,
            result = state.result,
        )

        if (state.result != null) {
            OutlinedButton(
                onClick = viewModel::reset,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.try_another))
            }
        }
    }
}
