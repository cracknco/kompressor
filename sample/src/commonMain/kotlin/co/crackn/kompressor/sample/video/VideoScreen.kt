package co.crackn.kompressor.sample.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import co.crackn.kompressor.sample.common.CompressButton
import co.crackn.kompressor.sample.common.ProgressSection
import kompressor.sample.generated.resources.Res
import kompressor.sample.generated.resources.error_compression_failed
import kompressor.sample.generated.resources.try_another
import org.jetbrains.compose.resources.stringResource

@Composable
fun VideoScreen(
    viewModel: VideoCompressViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val errorMessage = stringResource(Res.string.error_compression_failed)

    // Snackbar kept for compatibility (toast-style notifications)
    LaunchedEffect(state.error) {
        val error = state.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            message = "$errorMessage: $error",
            duration = androidx.compose.material3.SnackbarDuration.Long,
        )
    }

    // Also show a blocking dialog so errors are impossible to miss.
    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = { Text(errorMessage) },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError() }) {
                    Text("OK")
                }
            },
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
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

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
