# Kompressor — consumer ProGuard / R8 rules
# These rules are bundled with the library AAR so that apps using R8 preserve the public API.

# Keep the public API package and all public/protected members
-keep class co.crackn.kompressor.Kompressor { *; }
-keep class co.crackn.kompressor.CompressionResult { *; }

-keep class co.crackn.kompressor.image.ImageCompressor { *; }
-keep class co.crackn.kompressor.image.ImageCompressionConfig { *; }
-keep class co.crackn.kompressor.image.ImageFormat { *; }
-keep class co.crackn.kompressor.image.ImagePresets { *; }

-keep class co.crackn.kompressor.audio.AudioCompressor { *; }
-keep class co.crackn.kompressor.audio.AudioCompressionConfig { *; }
-keep class co.crackn.kompressor.audio.AudioChannels { *; }
-keep class co.crackn.kompressor.audio.AudioPresets { *; }

-keep class co.crackn.kompressor.video.VideoCompressor { *; }
-keep class co.crackn.kompressor.video.VideoCompressionConfig { *; }
-keep class co.crackn.kompressor.video.VideoCodec { *; }
-keep class co.crackn.kompressor.video.VideoPresets { *; }

# Keep the factory function (top-level expect/actual)
-keep class co.crackn.kompressor.KompressorKt { *; }

# Keep AndroidX App Startup initializer (referenced by name in AndroidManifest.xml)
-keep class co.crackn.kompressor.KompressorInitializer { *; }
