/*
 * Copyright 2026 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.matrix

/**
 * Single source-of-truth for the format support matrix published in
 * `docs/format-support.md`.
 *
 * Each list below enumerates one row of the documented table. The shape mirrors the DoD
 * for CRA-43: `Format in`, `Format out`, `Android min-API`, `iOS min-version`, `Codec
 * path`, `Fast-path eligible?`.
 *
 * `FormatSupportMatrixConsistencyTest` in `commonTest` cross-references every row
 * against the actual gate constants (`ImageFormatGates`) and MIME constants
 * (`Supportability.kt`) so the matrix cannot drift silently from the implementation it
 * documents. `FormatSupportDocUpToDateTest` in `androidHostTest` runs
 * [renderFormatSupportMatrixTables] and asserts the committed markdown is byte-identical
 * to what we would regenerate — so documentation and behaviour are always in lockstep.
 *
 * Keep comments in the rows terse; the user-facing rationale lives in the narrative
 * sections of `docs/format-support.md`, which are hand-written and not regenerated.
 */
internal object FormatSupportMatrix {

    /**
     * Image input × output combinations. One row per common input container (DoD:
     * JPEG, PNG, WebP, HEIC, AVIF + siblings). The output column names the primary
     * target (`JPEG`) because every non-experimental output target honours the same
     * decode gate — platform-specific encoder gates are documented separately in the
     * narrative.
     */
    val image: List<ImageMatrixRow> = listOf(
        ImageMatrixRow(
            formatIn = "JPEG",
            formatOut = "JPEG / WEBP (Android) / HEIC (iOS) / AVIF",
            androidMinApi = ANDROID_MIN_SDK,
            iosMinVersion = IOS_MIN_VERSION,
            codecPath = "Android `BitmapFactory` + `Bitmap.compress` / iOS `CGImageSource` + `CGImageDestination`",
            fastPathEligible = false,
            notes = "Universal baseline. Always decodable.",
        ),
        ImageMatrixRow(
            formatIn = "PNG",
            formatOut = "JPEG / WEBP (Android) / HEIC (iOS) / AVIF",
            androidMinApi = ANDROID_MIN_SDK,
            iosMinVersion = IOS_MIN_VERSION,
            codecPath = "Android `BitmapFactory` + `Bitmap.compress` / iOS `CGImageSource` + `CGImageDestination`",
            fastPathEligible = false,
            notes = "Alpha channel is dropped when transcoding to JPEG (no alpha support).",
        ),
        ImageMatrixRow(
            formatIn = "WEBP",
            formatOut = "JPEG / WEBP (Android) / HEIC (iOS) / AVIF",
            androidMinApi = ANDROID_MIN_SDK,
            iosMinVersion = IOS_MIN_VERSION,
            codecPath = "Android `BitmapFactory` + `Bitmap.compress` / iOS `CGImageSource` + `CGImageDestination`",
            fastPathEligible = false,
            notes = "Lossy + lossless both accepted on decode. WebP output is Android-only " +
                "(iOS ImageIO lacks a destination UTI).",
        ),
        ImageMatrixRow(
            formatIn = "HEIC",
            formatOut = "JPEG / WEBP (Android) / HEIC (iOS) / AVIF",
            androidMinApi = HEIC_INPUT_MIN_API_ANDROID,
            iosMinVersion = IOS_MIN_VERSION,
            codecPath = "Android `BitmapFactory` + `Bitmap.compress` / iOS `CGImageSource` + `CGImageDestination`",
            fastPathEligible = false,
            notes = "`@ExperimentalKompressorApi`. Android gate: OEM HEIC decoder coverage is spotty below API 30.",
        ),
        ImageMatrixRow(
            formatIn = "HEIF",
            formatOut = "JPEG / WEBP (Android) / HEIC (iOS) / AVIF",
            androidMinApi = HEIC_INPUT_MIN_API_ANDROID,
            iosMinVersion = IOS_MIN_VERSION,
            codecPath = "Android `BitmapFactory` + `Bitmap.compress` / iOS `CGImageSource` + `CGImageDestination`",
            fastPathEligible = false,
            notes = "Same gate as HEIC.",
        ),
        ImageMatrixRow(
            formatIn = "AVIF",
            formatOut = "JPEG / WEBP (Android) / HEIC (iOS) / AVIF",
            androidMinApi = AVIF_INPUT_MIN_API_ANDROID,
            iosMinVersion = AVIF_INPUT_MIN_IOS,
            codecPath = "Android `BitmapFactory` + `Bitmap.compress` / iOS `CGImageSource` + `CGImageDestination`",
            fastPathEligible = false,
            notes = "`@ExperimentalKompressorApi`. Android decode added in `BitmapFactory` at API 31; " +
                "iOS ImageIO in 16.0.",
        ),
        ImageMatrixRow(
            formatIn = "GIF",
            formatOut = "JPEG / WEBP (Android) / HEIC (iOS) / AVIF",
            androidMinApi = ANDROID_MIN_SDK,
            iosMinVersion = IOS_MIN_VERSION,
            codecPath = "Android `BitmapFactory` + `Bitmap.compress` / iOS `CGImageSource` + `CGImageDestination`",
            fastPathEligible = false,
            notes = "Animations are flattened — only the first frame is decoded.",
        ),
        ImageMatrixRow(
            formatIn = "BMP",
            formatOut = "JPEG / WEBP (Android) / HEIC (iOS) / AVIF",
            androidMinApi = ANDROID_MIN_SDK,
            iosMinVersion = IOS_MIN_VERSION,
            codecPath = "Android `BitmapFactory` + `Bitmap.compress` / iOS `CGImageSource` + `CGImageDestination`",
            fastPathEligible = false,
            notes = "Rarely encountered; cheap to decode.",
        ),
        ImageMatrixRow(
            formatIn = "DNG (raw)",
            formatOut = "JPEG / WEBP (Android) / HEIC (iOS) / AVIF",
            androidMinApi = ANDROID_MIN_SDK,
            iosMinVersion = IOS_MIN_VERSION,
            codecPath = "Extension-only sniffer → platform RAW pipeline",
            fastPathEligible = false,
            notes = "TIFF-based container; magic-byte sniffing falls back to the `.dng` extension. " +
                "Decode quality depends on the device's RAW pipeline.",
        ),
    )

    /**
     * Audio input codec → AAC output. AAC is the only output this release supports
     * (see `AudioCodec.AAC`). Android decode surface comes from Media3's default
     * extractors; iOS is narrower because AVFoundation's built-in extractors do not
     * ship MP3 / FLAC / OGG decoders on the platform baseline we target.
     */
    val audio: List<AudioMatrixRow> = listOf(
        AudioMatrixRow(
            formatIn = "AAC (M4A / MP4)",
            formatOut = "AAC",
            androidMinApi = ANDROID_MIN_SDK,
            iosMinVersion = IOS_MIN_VERSION,
            codecPath = "Android Media3 `MediaExtractor` → AAC encoder / iOS `AVAssetReader` → `AVAssetWriter`",
            fastPathEligible = true,
            notes = "Bitstream-copy passthrough when input config (sample rate / channels / bitrate " +
                "within ±20%) matches the requested output.",
        ),
        AudioMatrixRow(
            formatIn = "MP3",
            formatOut = "AAC",
            androidMinApi = ANDROID_MIN_SDK,
            iosMinVersion = IOS_UNSUPPORTED,
            codecPath = "Android Media3 MP3 extractor → AAC encoder",
            fastPathEligible = false,
            notes = "iOS: AVFoundation's built-in extractors do not decode MP3 in the Kompressor " +
                "pipeline (M4A / WAV / AIF only).",
        ),
        AudioMatrixRow(
            formatIn = "FLAC",
            formatOut = "AAC",
            androidMinApi = ANDROID_MIN_SDK,
            iosMinVersion = IOS_UNSUPPORTED,
            codecPath = "Android Media3 FLAC extractor → AAC encoder",
            fastPathEligible = false,
            notes = "iOS: not supported (same reason as MP3).",
        ),
        AudioMatrixRow(
            formatIn = "OGG (Vorbis)",
            formatOut = "AAC",
            androidMinApi = ANDROID_MIN_SDK,
            iosMinVersion = IOS_UNSUPPORTED,
            codecPath = "Android Media3 OGG extractor → AAC encoder",
            fastPathEligible = false,
            notes = "iOS: not supported.",
        ),
        AudioMatrixRow(
            formatIn = "Opus (OGG container)",
            formatOut = "AAC",
            androidMinApi = ANDROID_MIN_SDK,
            iosMinVersion = IOS_UNSUPPORTED,
            codecPath = "Android Media3 OGG extractor → AAC encoder",
            fastPathEligible = false,
            notes = "iOS: not supported. Multi-track Opus sources additionally fail on Android because " +
                "`MediaMuxer`'s MP4 container only accepts AAC / AMR — see " +
                "`AudioCompressionConfig.audioTrackIndex` docs.",
        ),
        AudioMatrixRow(
            formatIn = "AMR-NB",
            formatOut = "AAC",
            androidMinApi = ANDROID_MIN_SDK,
            iosMinVersion = IOS_UNSUPPORTED,
            codecPath = "Android Media3 AMR extractor → AAC encoder",
            fastPathEligible = false,
            notes = "Phone-call codec (8 kHz mono). iOS: not supported.",
        ),
        AudioMatrixRow(
            formatIn = "WAV (PCM)",
            formatOut = "AAC",
            androidMinApi = ANDROID_MIN_SDK,
            iosMinVersion = IOS_MIN_VERSION,
            codecPath = "Android Media3 WAV extractor → AAC encoder / iOS `AVAudioFile`",
            fastPathEligible = false,
            notes = "Supported on both platforms. Kompressor resamples 24-bit PCM losslessly to the " +
                "encoder input format.",
        ),
    )

    /**
     * Video input codec → output codec. Output is H.264 (default) or HEVC (required
     * for HDR10). iOS guarantees H.264 + HEVC as its encode/decode floor via
     * VideoToolbox; other formats are not part of the cross-platform promise even on
     * chipsets that happen to decode them.
     */
    val video: List<VideoMatrixRow> = listOf(
        VideoMatrixRow(
            formatIn = "H.264 (AVC)",
            formatOut = "H.264 / HEVC",
            androidMinApi = ANDROID_MIN_SDK,
            iosMinVersion = IOS_MIN_VERSION,
            codecPath = "Android Media3 `Transformer` (MediaCodec) / iOS `AVAssetReader` + `AVAssetWriter`",
            fastPathEligible = true,
            notes = "iOS fast path: `AVAssetExportSession` passthrough at default config (no " +
                "bitrate / resolution change). Android always re-encodes via Media3.",
        ),
        VideoMatrixRow(
            formatIn = "H.265 / HEVC",
            formatOut = "H.264 / HEVC",
            androidMinApi = ANDROID_MIN_SDK,
            iosMinVersion = IOS_MIN_VERSION,
            codecPath = "Android Media3 `Transformer` (MediaCodec) / iOS `AVAssetReader` + `AVAssetWriter`",
            fastPathEligible = true,
            notes = "HDR10 preservation requires HEVC output (see `VideoPresets.HDR10_1080P`). " +
                "iOS fast path applies at default config.",
        ),
        VideoMatrixRow(
            formatIn = "VP9",
            formatOut = "H.264 / HEVC",
            androidMinApi = ANDROID_MIN_SDK,
            iosMinVersion = IOS_UNSUPPORTED,
            codecPath = "Android Media3 `Transformer` (MediaCodec, device-dependent decoder)",
            fastPathEligible = false,
            notes = "Android support is device-dependent — advertised by `MediaCodecList` on most " +
                "modern devices. Not part of the iOS guarantee.",
        ),
        VideoMatrixRow(
            formatIn = "AV1",
            formatOut = "H.264 / HEVC",
            androidMinApi = AV1_INPUT_MIN_API_ANDROID,
            iosMinVersion = IOS_UNSUPPORTED,
            codecPath = "Android Media3 `Transformer` (MediaCodec AV1 decoder, API 29+)",
            fastPathEligible = false,
            notes = "Android: AV1 decoder is platform-level from API 29. Not part of the iOS guarantee.",
        ),
    )

    // ── Reference constants exposed for test cross-ref (kept in lockstep with
    // `ImageFormatGates`). We re-declare them here rather than import internal members
    // across packages so the matrix stays a self-contained reference datum; the
    // consistency test then verifies these match the real gates.

    /** Mirrors `kompressor/build.gradle.kts` `android.minSdk`. */
    internal const val ANDROID_MIN_SDK: Int = 24

    /** Mirrors the iOS deployment floor declared in `README.md` and `docs/format-support.md`. */
    internal const val IOS_MIN_VERSION: Int = 15

    /** Mirrors `ImageFormatGates.HEIC_INPUT_MIN_API_ANDROID`. */
    internal const val HEIC_INPUT_MIN_API_ANDROID: Int = 30

    /** Mirrors `ImageFormatGates.AVIF_INPUT_MIN_API_ANDROID`. */
    internal const val AVIF_INPUT_MIN_API_ANDROID: Int = 31

    /** Mirrors `ImageFormatGates.AVIF_INPUT_MIN_IOS`. */
    internal const val AVIF_INPUT_MIN_IOS: Int = 16

    /**
     * Android API level at which `video/av01` decode entered the platform `MediaCodecList`.
     * Before API 29, some OEMs shipped backports but support is not guaranteed — hence
     * the matrix gate.
     */
    internal const val AV1_INPUT_MIN_API_ANDROID: Int = 29

    /**
     * Sentinel placed in `iosMinVersion` when no iOS version in our supported range
     * decodes the input format. Rendered as "—" in the markdown matrix.
     */
    internal const val IOS_UNSUPPORTED: Int = -1
}

/**
 * Common shape of a matrix row. Image/audio/video rows all carry the same fields; the
 * distinct subtypes exist only to keep the three lists in [FormatSupportMatrix] type-safe
 * (you can't accidentally append a video row to the image list). The renderer consumes
 * rows through this interface so it doesn't need one overload per subtype — and so detekt's
 * `LongParameterList` stays satisfied with a single-argument signature.
 */
internal sealed interface MatrixRow {
    val formatIn: String
    val formatOut: String
    val androidMinApi: Int
    val iosMinVersion: Int
    val codecPath: String
    val fastPathEligible: Boolean
    val notes: String
}

/** Image-row entry in [FormatSupportMatrix.image]. */
internal data class ImageMatrixRow(
    override val formatIn: String,
    override val formatOut: String,
    override val androidMinApi: Int,
    override val iosMinVersion: Int,
    override val codecPath: String,
    override val fastPathEligible: Boolean,
    override val notes: String,
) : MatrixRow

/** Audio-row entry in [FormatSupportMatrix.audio]. */
internal data class AudioMatrixRow(
    override val formatIn: String,
    override val formatOut: String,
    override val androidMinApi: Int,
    override val iosMinVersion: Int,
    override val codecPath: String,
    override val fastPathEligible: Boolean,
    override val notes: String,
) : MatrixRow

/** Video-row entry in [FormatSupportMatrix.video]. */
internal data class VideoMatrixRow(
    override val formatIn: String,
    override val formatOut: String,
    override val androidMinApi: Int,
    override val iosMinVersion: Int,
    override val codecPath: String,
    override val fastPathEligible: Boolean,
    override val notes: String,
) : MatrixRow
