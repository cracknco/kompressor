/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.sample.preview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.crackn.kompressor.ExperimentalKompressorApi
import co.crackn.kompressor.Kompressor
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.cacheDir
import io.github.vinceglb.filekit.copyTo
import io.github.vinceglb.filekit.delete
import io.github.vinceglb.filekit.extension
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.path
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.IO // Required on Kotlin/Native targets for Dispatchers.IO visibility.
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject

/**
 * Drives the Preview screen. Manages three independent preview flows on a single
 * `Kompressor` instance — image thumbnail, video frame extraction, audio waveform — each with
 * its own cancellable `Job` so that re-picking a file or scrubbing the video slider cancels the
 * previous in-flight call before starting a new one.
 */
@OptIn(ExperimentalKompressorApi::class)
@Inject
class PreviewViewModel(
    private val kompressor: Kompressor,
) : ViewModel() {

    private val _state = MutableStateFlow(PreviewState())
    val state: StateFlow<PreviewState> = _state.asStateFlow()

    private var imageJob: Job? = null
    private var videoJob: Job? = null
    private var audioJob: Job? = null

    // ----- Image -----

    fun onImagePicked(file: PlatformFile) {
        imageJob?.cancel()
        imageJob = viewModelScope.launch(Dispatchers.IO) {
            val tempInput = createTempFile("preview_img_in", file.extension.ifBlank { "jpg" })
            val tempOutput = createTempFile("preview_img_thumb", "jpg")
            try {
                _state.update {
                    it.copy(
                        image = ImagePreview(
                            sourceFileName = file.name,
                            isComputing = true,
                        ),
                    )
                }
                file.copyTo(tempInput)
                kompressor.image.thumbnail(
                    input = MediaSource.Local.FilePath(tempInput.path),
                    output = MediaDestination.Local.FilePath(tempOutput.path),
                    maxDimension = THUMBNAIL_MAX_DIMENSION,
                ).fold(
                    onSuccess = {
                        _state.update {
                            it.copy(
                                image = it.image.copy(
                                    thumbnailPath = tempOutput.path,
                                    isComputing = false,
                                ),
                            )
                        }
                    },
                    onFailure = { error ->
                        runCatching { tempOutput.delete(mustExist = false) }
                        _state.update {
                            it.copy(
                                image = it.image.copy(
                                    isComputing = false,
                                    error = error.message ?: "Image thumbnail failed",
                                ),
                            )
                        }
                    },
                )
            } catch (e: CancellationException) {
                runCatching { tempOutput.delete(mustExist = false) }
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        image = it.image.copy(
                            isComputing = false,
                            error = e.message ?: "Image thumbnail failed",
                        ),
                    )
                }
            } finally {
                runCatching { tempInput.delete(mustExist = false) }
            }
        }
    }

    // ----- Video -----

    fun onVideoPicked(file: PlatformFile) {
        videoJob?.cancel()
        // Capture the previously-picked video's temp paths for cleanup *inside* the new job. We
        // intentionally don't delete these from the cancelled job's catch block: the slider
        // becomes interactive as soon as `state.video.sourcePath` is first published, so a
        // scrubbing-driven `videoJob.cancel()` while the initial extraction is in flight would
        // otherwise delete the temp input the *new* (scrub) job is about to read.
        val previousSource = _state.value.video.sourcePath
        val previousThumbnail = _state.value.video.thumbnailPath
        videoJob = viewModelScope.launch(Dispatchers.IO) {
            previousSource?.let { runCatching { PlatformFile(it).delete(mustExist = false) } }
            previousThumbnail?.let { runCatching { PlatformFile(it).delete(mustExist = false) } }
            val tempInput = createTempFile("preview_vid_in", file.extension.ifBlank { "mp4" })
            try {
                _state.update {
                    it.copy(
                        video = VideoPreview(
                            sourceFileName = file.name,
                            isComputing = true,
                        ),
                    )
                }
                file.copyTo(tempInput)
                val info = kompressor.probe(tempInput.path).getOrNull()
                val durationMs = info?.durationMs ?: 0L
                _state.update {
                    it.copy(
                        video = it.video.copy(
                            sourcePath = tempInput.path,
                            durationMs = durationMs,
                            atMillis = 0L,
                        ),
                    )
                }
                runFrameExtraction(tempInput.path, atMillis = 0L)
            } catch (e: CancellationException) {
                // Do NOT delete tempInput on cancellation: it may already be referenced by
                // `state.video.sourcePath`, and a follow-up scrub job will read from it.
                // Cleanup happens at the next `onVideoPicked` call (above) or in `onCleared`.
                throw e
            } catch (e: Exception) {
                // Genuine failure (probe/copy/extraction blew up) — tempInput isn't useful for
                // any subsequent extraction, so delete it now.
                runCatching { tempInput.delete(mustExist = false) }
                _state.update {
                    it.copy(
                        video = it.video.copy(
                            isComputing = false,
                            error = e.message ?: "Video import failed",
                        ),
                    )
                }
            }
        }
    }

    fun onFrameTimestampChanged(atMillis: Long) {
        val sourcePath = _state.value.video.sourcePath ?: return
        val clamped = atMillis.coerceIn(0L, _state.value.video.durationMs.coerceAtLeast(0L))
        _state.update { it.copy(video = it.video.copy(atMillis = clamped)) }
        // Cancel any in-flight extraction before launching the next one — scrubbing emits a
        // burst of changes and we only want the latest one to land on disk.
        videoJob?.cancel()
        videoJob = viewModelScope.launch(Dispatchers.IO) {
            runFrameExtraction(sourcePath, clamped)
        }
    }

    private suspend fun runFrameExtraction(sourcePath: String, atMillis: Long) {
        val tempOutput = createTempFile("preview_vid_frame", "jpg")
        try {
            _state.update { it.copy(video = it.video.copy(isComputing = true, error = null)) }
            kompressor.video.thumbnail(
                input = MediaSource.Local.FilePath(sourcePath),
                output = MediaDestination.Local.FilePath(tempOutput.path),
                atMillis = atMillis,
                maxDimension = VIDEO_FRAME_MAX_DIMENSION,
            ).fold(
                onSuccess = {
                    val previousThumbnail = _state.value.video.thumbnailPath
                    _state.update {
                        it.copy(
                            video = it.video.copy(
                                thumbnailPath = tempOutput.path,
                                isComputing = false,
                            ),
                        )
                    }
                    // Drop the previous frame file once the new one has replaced it in state — a
                    // scrubbing user generates one of these per slider change, so we don't want
                    // them to accumulate in the cache dir.
                    previousThumbnail?.let { runCatching { PlatformFile(it).delete(mustExist = false) } }
                },
                onFailure = { error ->
                    runCatching { tempOutput.delete(mustExist = false) }
                    _state.update {
                        it.copy(
                            video = it.video.copy(
                                isComputing = false,
                                error = error.message ?: "Frame extraction failed",
                            ),
                        )
                    }
                },
            )
        } catch (e: CancellationException) {
            runCatching { tempOutput.delete(mustExist = false) }
            throw e
        } catch (e: Exception) {
            runCatching { tempOutput.delete(mustExist = false) }
            _state.update {
                it.copy(
                    video = it.video.copy(
                        isComputing = false,
                        error = e.message ?: "Frame extraction failed",
                    ),
                )
            }
        }
    }

    // ----- Audio -----

    fun onAudioPicked(file: PlatformFile) {
        audioJob?.cancel()
        audioJob = viewModelScope.launch(Dispatchers.IO) {
            val tempInput = createTempFile("preview_aud_in", file.extension.ifBlank { "m4a" })
            try {
                _state.update {
                    it.copy(
                        audio = AudioPreview(
                            sourceFileName = file.name,
                            isComputing = true,
                        ),
                    )
                }
                file.copyTo(tempInput)
                kompressor.audio.waveform(
                    input = MediaSource.Local.FilePath(tempInput.path),
                    targetSamples = WAVEFORM_TARGET_SAMPLES,
                    onProgress = { progress ->
                        _state.update {
                            it.copy(audio = it.audio.copy(progress = progress.fraction))
                        }
                    },
                ).fold(
                    onSuccess = { peaks ->
                        _state.update {
                            it.copy(
                                audio = it.audio.copy(
                                    peaks = peaks,
                                    isComputing = false,
                                    progress = 1f,
                                ),
                            )
                        }
                    },
                    onFailure = { error ->
                        _state.update {
                            it.copy(
                                audio = it.audio.copy(
                                    isComputing = false,
                                    progress = 0f,
                                    error = error.message ?: "Waveform extraction failed",
                                ),
                            )
                        }
                    },
                )
            } catch (e: CancellationException) {
                // User cancelled (re-picked, navigated away). Reset audio sub-state to empty so
                // the UI doesn't get stuck in "computing" — the DoD's "no crash" + "returns to
                // empty" behaviour.
                _state.update { it.copy(audio = AudioPreview()) }
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        audio = it.audio.copy(
                            isComputing = false,
                            progress = 0f,
                            error = e.message ?: "Waveform extraction failed",
                        ),
                    )
                }
            } finally {
                runCatching { tempInput.delete(mustExist = false) }
            }
        }
    }

    fun cancelAudioWaveform() {
        audioJob?.cancel()
    }

    fun clearError() {
        _state.update {
            it.copy(
                image = it.image.copy(error = null),
                video = it.video.copy(error = null),
                audio = it.audio.copy(error = null),
            )
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCleared() {
        // Clean up any preview files on the way out — viewModelScope cancellation handles the
        // jobs themselves, GlobalScope is only here so the deletions can outlive the scope.
        val paths = listOfNotNull(
            _state.value.image.thumbnailPath,
            _state.value.video.sourcePath,
            _state.value.video.thumbnailPath,
        )
        GlobalScope.launch(Dispatchers.IO) {
            paths.forEach { path ->
                runCatching { PlatformFile(path).delete(mustExist = false) }
            }
        }
        super.onCleared()
    }

    private fun createTempFile(prefix: String, extension: String): PlatformFile =
        PlatformFile(
            FileKit.cacheDir,
            "kompressor_${prefix}_${Random.nextLong(1_000_000_000)}.$extension",
        )

    private companion object {
        const val THUMBNAIL_MAX_DIMENSION = 320
        const val VIDEO_FRAME_MAX_DIMENSION = 480
        const val WAVEFORM_TARGET_SAMPLES = 200
    }
}
