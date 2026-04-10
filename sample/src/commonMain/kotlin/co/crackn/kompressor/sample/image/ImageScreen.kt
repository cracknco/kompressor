package co.crackn.kompressor.sample.image

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
fun ImageScreen(
    viewModel: ImageCompressViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val errorMessage = stringResource(Res.string.error_compression_failed)

    LaunchedEffect(state.error) {
        state.error?.let { error ->
            snackbarHostState.showSnackbar("$errorMessage: $error")
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ImagePickerSection(
                selectedImagePath = state.selectedImagePath,
                onImagePicked = viewModel::onImagePicked,
            )

            if (state.selectedImagePath != null && state.result == null) {
                PresetSelector(
                    selectedPreset = state.selectedPreset,
                    onPresetSelected = viewModel::onPresetSelected,
                )

                CustomConfigPanel(
                    visible = state.selectedPreset == PresetOption.CUSTOM,
                    quality = state.customQuality,
                    maxWidth = state.customMaxWidth,
                    maxHeight = state.customMaxHeight,
                    onQualityChanged = viewModel::onCustomQualityChanged,
                    onMaxWidthChanged = viewModel::onCustomMaxWidthChanged,
                    onMaxHeightChanged = viewModel::onCustomMaxHeightChanged,
                )

                ProgressSection(
                    visible = state.isCompressing,
                    progress = state.progress,
                )

                CompressButton(
                    isCompressing = state.isCompressing,
                    enabled = state.selectedImagePath != null,
                    onClick = viewModel::compress,
                )
            }

            ResultCard(
                visible = state.result != null,
                inputImagePath = state.selectedImagePath,
                outputImagePath = state.compressedImagePath,
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
