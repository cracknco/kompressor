# Module kompressor

Kotlin Multiplatform library for compressing images, videos, and audio on Android
and iOS, using each platform's native hardware encoders. Zero bundled binary codecs.

- **API stability contract:** [docs/api-stability.md](https://github.com/cracknco/kompressor/blob/main/docs/api-stability.md)
- **Conceptual documentation & guides:** [cracknco.github.io/kompressor](https://cracknco.github.io/kompressor/)
- **Source & issues:** [github.com/cracknco/kompressor](https://github.com/cracknco/kompressor)

Symbols annotated with `@ExperimentalKompressorApi` are opt-in and may change or be
removed in any minor release without a major version bump.

# Package co.crackn.kompressor

Top-level entry points: the [Kompressor] interface, the [createKompressor] factory,
and the shared result / probe types.

# Package co.crackn.kompressor.image

Image compression (`BitmapFactory` on Android, `UIImage` + Core Graphics on iOS).

# Package co.crackn.kompressor.video

Video compression (Media3 `Transformer` on Android, `AVAssetExportSession` /
`AVAssetWriter` on iOS).

# Package co.crackn.kompressor.audio

Audio compression (Media3 `Transformer` with AAC-in-M4A output on Android,
`AVAssetExportSession` on iOS).

# Package co.crackn.kompressor.matrix

Format-support matrix describing which decoder / encoder combinations are guaranteed
across the supported OS version floors.
