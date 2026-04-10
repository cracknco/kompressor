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
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.path
import kotlin.coroutines.cancellation.CancellationException
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
                _state.update {
                    it.copy(
                        selectedImagePath = null,
                        selectedFileName = null,
                        compressedImagePath = null,
                        result = null,
                        progress = 0f,
                        error = e.message ?: "Failed to import image",
                    )
                }
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

        _state.update {
            it.copy(
                isCompressing = true,
                progress = 0f,
                error = null,
                compressedImagePath = null,
                result = null,
            )
        }

        viewModelScope.launch(Dispatchers.IO) { runCompression(inputPath) }
    }

    private suspend fun runCompression(inputPath: String) {
        val outputFile = createTempFile("output")
        try {
            kompressor.image.compress(
                inputPath = inputPath,
                outputPath = outputFile.path,
                config = buildConfig(),
                onProgress = { progress ->
                    _state.update { it.copy(progress = progress) }
                },
            ).fold(
                onSuccess = { handleSuccess(outputFile.path, it) },
                onFailure = { handleFailure(it, outputFile.path) },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFailure(e, outputFile.path)
        } finally {
            _state.update {
                if (it.isCompressing) it.copy(isCompressing = false) else it
            }
        }
    }

    fun reset() {
        val paths = currentTempPaths()
        _state.update { ImageCompressState() }
        viewModelScope.launch(Dispatchers.IO) { deletePaths(paths) }
    }

    /** Clears the current error so the same message can retrigger the snackbar. */
    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    override fun onCleared() {
        // Best-effort cleanup — cache files are ephemeral by nature
        val paths = currentTempPaths()
        viewModelScope.launch(Dispatchers.IO) { deletePaths(paths) }
        super.onCleared()
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

    private fun handleFailure(error: Throwable, outputPath: String? = null) {
        outputPath?.let { path ->
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { PlatformFile(path).delete(mustExist = false) }
            }
        }
        _state.update {
            it.copy(
                isCompressing = false,
                progress = 0f,
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
                maxWidth = _state.value.customMaxWidth.toIntOrNull()?.takeIf { it > 0 },
                maxHeight = _state.value.customMaxHeight.toIntOrNull()?.takeIf { it > 0 },
            )
        }

    private fun currentTempPaths(): List<String> = listOfNotNull(
        _state.value.selectedImagePath,
        _state.value.compressedImagePath,
    )

    private suspend fun deleteTempFiles() {
        deletePaths(currentTempPaths())
    }

    private suspend fun deletePaths(paths: List<String>) {
        paths.forEach { path ->
            runCatching { PlatformFile(path).delete(mustExist = false) }
        }
    }

    private fun createTempFile(prefix: String): PlatformFile =
        PlatformFile(
            FileKit.cacheDir,
            "kompressor_${prefix}_${Random.nextLong(1_000_000_000)}.jpg",
        )
}
