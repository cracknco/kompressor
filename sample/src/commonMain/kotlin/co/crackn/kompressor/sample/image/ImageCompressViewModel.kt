package co.crackn.kompressor.sample.image

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.crackn.kompressor.CompressionResult
import co.crackn.kompressor.Kompressor
import co.crackn.kompressor.image.ImageCompressionConfig
import co.crackn.kompressor.image.ImagePresets
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.cacheDir
import io.github.vinceglb.filekit.copyTo
import io.github.vinceglb.filekit.delete
import io.github.vinceglb.filekit.exists
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.path
import io.github.vinceglb.filekit.toKotlinxIoPath
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                deleteTempFiles()
                val inputFile = createTempFile("input")
                file.copyTo(inputFile)
                _state.update {
                    it.copy(
                        selectedImagePath = inputFile.path,
                        selectedFileName = file.name,
                        compressedImagePath = null,
                        result = null,
                        error = null,
                        progress = 0f,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
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
        val outputFile = createTempFile("output")

        _state.update { it.copy(isCompressing = true, progress = 0f, error = null) }

        viewModelScope.launch(Dispatchers.IO) {
            kompressor.image.compress(
                inputPath = inputPath,
                outputPath = outputFile.path,
                config = buildConfig(),
                onProgress = { progress ->
                    _state.update { it.copy(progress = progress) }
                },
            ).fold(
                onSuccess = { handleSuccess(outputFile.path, it) },
                onFailure = { handleFailure(it) },
            )
        }
    }

    fun reset() {
        viewModelScope.launch(Dispatchers.IO) { deleteTempFiles() }
        _state.update { ImageCompressState() }
    }

    override fun onCleared() {
        super.onCleared()
        listOfNotNull(
            _state.value.selectedImagePath,
            _state.value.compressedImagePath,
        ).forEach { path ->
            runCatching {
                val file = PlatformFile(path)
                if (file.exists()) {
                    kotlinx.io.files.SystemFileSystem.delete(
                        file.toKotlinxIoPath(),
                        mustExist = false,
                    )
                }
            }
        }
    }

    private fun handleSuccess(outputPath: String, result: CompressionResult) {
        _state.update {
            it.copy(
                isCompressing = false,
                compressedImagePath = outputPath,
                result = result,
                progress = 1f,
            )
        }
    }

    private fun handleFailure(error: Throwable) {
        _state.update {
            it.copy(
                isCompressing = false,
                error = error.message ?: "Unknown error",
            )
        }
    }

    private fun buildConfig(): ImageCompressionConfig =
        when (_state.value.selectedPreset) {
            PresetOption.THUMBNAIL -> ImagePresets.THUMBNAIL
            PresetOption.WEB -> ImagePresets.WEB
            PresetOption.HIGH_QUALITY -> ImagePresets.HIGH_QUALITY
            PresetOption.CUSTOM -> ImageCompressionConfig(
                quality = _state.value.customQuality,
                maxWidth = _state.value.customMaxWidth.toIntOrNull(),
                maxHeight = _state.value.customMaxHeight.toIntOrNull(),
            )
        }

    private suspend fun deleteTempFiles() {
        listOfNotNull(
            _state.value.selectedImagePath,
            _state.value.compressedImagePath,
        ).forEach { path ->
            val file = PlatformFile(path)
            if (file.exists()) file.delete(mustExist = false)
        }
    }

    private fun createTempFile(prefix: String): PlatformFile =
        PlatformFile(
            FileKit.cacheDir,
            "kompressor_${prefix}_${Random.nextLong(1_000_000_000)}.jpg",
        )
}
