package co.crackn.kompressor.sample.capabilities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.crackn.kompressor.CodecSupport
import co.crackn.kompressor.Kompressor
import co.crackn.kompressor.queryDeviceCapabilities
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.cacheDir
import io.github.vinceglb.filekit.copyTo
import io.github.vinceglb.filekit.delete
import io.github.vinceglb.filekit.extension
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
class CapabilitiesViewModel(
    private val kompressor: Kompressor,
) : ViewModel() {

    private val _state = MutableStateFlow(CapabilitiesState())
    val state: StateFlow<CapabilitiesState> = _state.asStateFlow()

    init {
        // queryDeviceCapabilities does binder IPC / file reads on Android — use IO, not Default.
        viewModelScope.launch(Dispatchers.IO) {
            val caps = queryDeviceCapabilities()
            _state.update {
                it.copy(
                    deviceSummary = caps.deviceSummary,
                    videoDecoders = caps.video.filter { c -> c.role == CodecSupport.Role.Decoder },
                    videoEncoders = caps.video.filter { c -> c.role == CodecSupport.Role.Encoder },
                    audioDecoders = caps.audio.filter { c -> c.role == CodecSupport.Role.Decoder },
                    audioEncoders = caps.audio.filter { c -> c.role == CodecSupport.Role.Encoder },
                )
            }
        }
    }

    fun onFilePicked(file: PlatformFile) {
        if (_state.value.isProbing) return
        _state.update {
            it.copy(
                isProbing = true,
                probeFileName = file.name,
                probeInfo = null,
                probeVerdict = null,
                probeError = null,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val cached = runCatching { createCachedCopy(file) }.getOrElse { err ->
                _state.update {
                    it.copy(probeError = err.message ?: "Could not open file", isProbing = false)
                }
                return@launch
            }
            try {
                kompressor.probe(cached.path).fold(
                    onSuccess = { info ->
                        val verdict = kompressor.canCompress(info)
                        _state.update { it.copy(probeInfo = info, probeVerdict = verdict, isProbing = false) }
                    },
                    onFailure = { err ->
                        _state.update {
                            it.copy(probeError = err.message ?: "Probe failed", isProbing = false)
                        }
                    },
                )
            } finally {
                runCatching { cached.delete(mustExist = false) }
            }
        }
    }

    private suspend fun createCachedCopy(file: PlatformFile): PlatformFile {
        val copy = PlatformFile(
            FileKit.cacheDir,
            "kompressor_probe_${Random.nextLong(1_000_000_000)}.${file.extension}",
        )
        file.copyTo(copy)
        return copy
    }
}
