package co.crackn.kompressor.sample.audio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.crackn.kompressor.CompressionResult
import co.crackn.kompressor.Kompressor
import co.crackn.kompressor.audio.AudioChannels
import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.audio.AudioPresets
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.cacheDir
import io.github.vinceglb.filekit.copyTo
import io.github.vinceglb.filekit.delete
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.path
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
class AudioCompressViewModel(
    private val kompressor: Kompressor,
) : ViewModel() {

    private val _state = MutableStateFlow(AudioCompressState())
    val state: StateFlow<AudioCompressState> = _state.asStateFlow()

    fun onAudioPicked(file: PlatformFile) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                deleteTempFiles()
                val inputFile = createTempFile("input")
                file.copyTo(inputFile)
                _state.update {
                    it.copy(
                        selectedAudioPath = inputFile.path,
                        selectedFileName = file.name,
                        result = null,
                        error = null,
                        progress = 0f,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        selectedAudioPath = null,
                        selectedFileName = null,
                        result = null,
                        progress = 0f,
                        error = e.message ?: "Failed to import audio",
                    )
                }
            }
        }
    }

    fun onPresetSelected(preset: AudioPresetOption) {
        _state.update { it.copy(selectedPreset = preset) }
    }

    fun onCustomBitrateChanged(value: String) {
        _state.update { it.copy(customBitrate = value) }
    }

    fun onCustomSampleRateChanged(sampleRate: Int) {
        _state.update { it.copy(customSampleRate = sampleRate) }
    }

    fun onCustomChannelsChanged(channels: AudioChannels) {
        _state.update { it.copy(customChannels = channels) }
    }

    fun compress() {
        val inputPath = _state.value.selectedAudioPath ?: return

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
            kompressor.audio.compress(
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
        _state.update { AudioCompressState() }
        viewModelScope.launch(Dispatchers.IO) { deletePaths(paths) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    override fun onCleared() {
        val paths = currentTempPaths()
        viewModelScope.launch(Dispatchers.IO) { deletePaths(paths) }
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
        _state.update {
            it.copy(
                isCompressing = false,
                progress = 0f,
                error = error.message ?: "Unknown error",
            )
        }
    }

    private fun buildConfig(): AudioCompressionConfig =
        when (_state.value.selectedPreset) {
            AudioPresetOption.VOICE_MESSAGE -> AudioPresets.VOICE_MESSAGE
            AudioPresetOption.PODCAST -> AudioPresets.PODCAST
            AudioPresetOption.HIGH_QUALITY -> AudioPresets.HIGH_QUALITY
            AudioPresetOption.CUSTOM -> AudioCompressionConfig(
                bitrate = (_state.value.customBitrate.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_BITRATE_KBPS) * KBPS_MULTIPLIER,
                sampleRate = _state.value.customSampleRate,
                channels = _state.value.customChannels,
            )
        }

    private fun currentTempPaths(): List<String> = listOfNotNull(
        _state.value.selectedAudioPath,
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
            "kompressor_audio_${prefix}_${Random.nextLong(1_000_000_000)}.m4a",
        )

    private companion object {
        const val DEFAULT_BITRATE_KBPS = 128
        const val KBPS_MULTIPLIER = 1_000
    }
}
