package co.crackn.kompressor.sample.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.crackn.kompressor.CompressionResult
import co.crackn.kompressor.Kompressor
import co.crackn.kompressor.video.MaxResolution
import co.crackn.kompressor.video.VideoCompressionConfig
import co.crackn.kompressor.video.VideoPresets
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.cacheDir
import io.github.vinceglb.filekit.copyTo
import io.github.vinceglb.filekit.delete
import io.github.vinceglb.filekit.extension
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.path
import kotlin.random.Random
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject

@Inject
class VideoCompressViewModel(
    private val kompressor: Kompressor,
) : ViewModel() {

    private val _state = MutableStateFlow(VideoCompressState())
    val state: StateFlow<VideoCompressState> = _state.asStateFlow()

    fun onVideoPicked(file: PlatformFile) {
        if (_state.value.isCompressing) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                deleteTempFiles()
                val inputFile = createTempFile("input", file.extension)
                try {
                    file.copyTo(inputFile)
                } catch (e: Exception) {
                    runCatching { inputFile.delete(mustExist = false) }
                    throw e
                }
                _state.update {
                    it.copy(
                        selectedVideoPath = inputFile.path,
                        selectedFileName = file.name,
                        compressedVideoPath = null,
                        result = null,
                        error = null,
                        progress = 0f,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        selectedVideoPath = null,
                        selectedFileName = null,
                        compressedVideoPath = null,
                        result = null,
                        progress = 0f,
                        error = e.message ?: "Failed to import video",
                    )
                }
            }
        }
    }

    fun onPresetSelected(preset: VideoPresetOption) {
        _state.update { it.copy(selectedPreset = preset) }
    }

    fun onCustomVideoBitrateChanged(value: String) {
        _state.update { it.copy(customVideoBitrate = value) }
    }

    fun onCustomMaxResolutionChanged(resolution: MaxResolution) {
        _state.update { it.copy(customMaxResolution = resolution) }
    }

    fun onCustomMaxFrameRateChanged(fps: Int) {
        _state.update { it.copy(customMaxFrameRate = fps) }
    }

    fun onCustomKeyFrameIntervalChanged(interval: Int) {
        _state.update { it.copy(customKeyFrameInterval = interval) }
    }

    fun compress() {
        if (_state.value.isCompressing) return
        val inputPath = _state.value.selectedVideoPath ?: return

        _state.update {
            it.copy(
                isCompressing = true,
                progress = 0f,
                error = null,
                result = null,
            )
        }

        viewModelScope.launch(Dispatchers.IO) { runCompression(inputPath) }
    }

    private suspend fun runCompression(inputPath: String) {
        try {
            val outputFile = createTempFile("output")
            _state.update { it.copy(compressedVideoPath = outputFile.path) }
            kompressor.video.compress(
                inputPath = inputPath,
                outputPath = outputFile.path,
                config = buildConfig(),
                onProgress = { progress ->
                    _state.update { it.copy(progress = progress) }
                },
            ).fold(
                onSuccess = { handleSuccess(it) },
                onFailure = { handleFailure(it) },
            )
        } catch (e: Exception) {
            handleFailure(e)
        } finally {
            _state.update {
                if (it.isCompressing) it.copy(isCompressing = false) else it
            }
        }
    }

    fun reset() {
        val paths = currentTempPaths()
        _state.update { VideoCompressState() }
        viewModelScope.launch(Dispatchers.IO) { deletePaths(paths) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCleared() {
        val paths = currentTempPaths()
        GlobalScope.launch(Dispatchers.IO) { deletePaths(paths) }
        super.onCleared()
    }

    private fun handleSuccess(result: CompressionResult) {
        _state.update {
            it.copy(
                isCompressing = false,
                result = result,
                progress = 1f,
            )
        }
    }

    private fun handleFailure(error: Throwable) {
        val failedOutputPath = _state.value.compressedVideoPath
        _state.update {
            it.copy(
                isCompressing = false,
                progress = 0f,
                compressedVideoPath = null,
                error = error.message ?: "Unknown error",
            )
        }
        if (failedOutputPath != null) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { PlatformFile(failedOutputPath).delete(mustExist = false) }
            }
        }
    }

    private fun buildConfig(): VideoCompressionConfig =
        when (_state.value.selectedPreset) {
            VideoPresetOption.MESSAGING -> VideoPresets.MESSAGING
            VideoPresetOption.HIGH_QUALITY -> VideoPresets.HIGH_QUALITY
            VideoPresetOption.LOW_BANDWIDTH -> VideoPresets.LOW_BANDWIDTH
            VideoPresetOption.SOCIAL_MEDIA -> VideoPresets.SOCIAL_MEDIA
            VideoPresetOption.CUSTOM -> VideoCompressionConfig(
                maxResolution = _state.value.customMaxResolution,
                videoBitrate = parseBitrateKbps() * KBPS_MULTIPLIER,
                maxFrameRate = _state.value.customMaxFrameRate,
                keyFrameInterval = _state.value.customKeyFrameInterval,
            )
        }

    private fun parseBitrateKbps(): Int =
        _state.value.customVideoBitrate.toIntOrNull()
            ?.coerceIn(1, MAX_BITRATE_KBPS)
            ?: DEFAULT_BITRATE_KBPS

    private fun currentTempPaths(): List<String> = listOfNotNull(
        _state.value.selectedVideoPath,
        _state.value.compressedVideoPath,
    )

    private suspend fun deleteTempFiles() {
        deletePaths(currentTempPaths())
    }

    private suspend fun deletePaths(paths: List<String>) {
        paths.forEach { path ->
            runCatching { PlatformFile(path).delete(mustExist = false) }
        }
    }

    private fun createTempFile(prefix: String, extension: String = "mp4"): PlatformFile =
        PlatformFile(
            FileKit.cacheDir,
            "kompressor_video_${prefix}_${Random.nextLong(1_000_000_000)}.$extension",
        )

    private companion object {
        const val DEFAULT_BITRATE_KBPS = 1_200
        const val MAX_BITRATE_KBPS = 10_000
        const val KBPS_MULTIPLIER = 1_000
    }
}
