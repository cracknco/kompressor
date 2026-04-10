package co.crackn.kompressor.sample.image

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.crackn.kompressor.Kompressor
import co.crackn.kompressor.image.ImageCompressionConfig
import co.crackn.kompressor.image.ImagePresets
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.cacheDir
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.path
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.write
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject

@Inject
class ImageCompressViewModel(
    private val kompressor: Kompressor,
) : ViewModel() {

    private val _state = MutableStateFlow(ImageCompressState())
    val state: StateFlow<ImageCompressState> = _state.asStateFlow()

    fun onImagePicked(file: PlatformFile) {
        viewModelScope.launch {
            val bytes = file.readBytes()
            val inputFile = PlatformFile(FileKit.cacheDir, "kompressor_input_${Random.nextLong(1_000_000_000)}.jpg")
            inputFile write bytes
            _state.update {
                it.copy(
                    selectedImagePath = inputFile.path,
                    selectedFileName = file.name,
                    compressedImagePath = null,
                    result = null,
                    error = null,
                )
            }
        }
    }

    fun onPresetSelected(preset: PresetOption) {
        _state.update { it.copy(selectedPreset = preset) }
    }

    fun onCustomQualityChanged(quality: Int) {
        _state.update { it.copy(customQuality = quality.coerceIn(1, 100)) }
    }

    fun onCustomMaxWidthChanged(value: String) {
        _state.update { it.copy(customMaxWidth = value) }
    }

    fun onCustomMaxHeightChanged(value: String) {
        _state.update { it.copy(customMaxHeight = value) }
    }

    fun compress() {
        val inputPath = _state.value.selectedImagePath ?: return
        val outputFile = PlatformFile(FileKit.cacheDir, "kompressor_output_${Random.nextLong(1_000_000_000)}.jpg")
        val config = buildConfig()

        _state.update { it.copy(isCompressing = true, progress = 0f, error = null) }

        viewModelScope.launch {
            kompressor.image.compress(
                inputPath = inputPath,
                outputPath = outputFile.path,
                config = config,
                onProgress = { progress -> _state.update { it.copy(progress = progress) } },
            ).onSuccess { result ->
                _state.update {
                    it.copy(
                        isCompressing = false,
                        compressedImagePath = outputFile.path,
                        result = result,
                        progress = 1f,
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isCompressing = false,
                        error = error.message ?: "Unknown error",
                    )
                }
            }
        }
    }

    fun reset() {
        _state.update { ImageCompressState() }
    }

    private fun buildConfig(): ImageCompressionConfig = when (_state.value.selectedPreset) {
        PresetOption.THUMBNAIL -> ImagePresets.THUMBNAIL
        PresetOption.WEB -> ImagePresets.WEB
        PresetOption.HIGH_QUALITY -> ImagePresets.HIGH_QUALITY
        PresetOption.CUSTOM -> ImageCompressionConfig(
            quality = _state.value.customQuality,
            maxWidth = _state.value.customMaxWidth.toIntOrNull(),
            maxHeight = _state.value.customMaxHeight.toIntOrNull(),
        )
    }
}
