plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.vanniktech.mavenPublish) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kover)
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlinxSerialization) apply false
}

// Aggregate coverage from both library and sample app so that device/integration
// test runs in :sample (which exercise :kompressor code paths) also count toward
// the coverage gate.
dependencies {
    kover(project(":kompressor"))
}

kover {
    reports {
        filters {
            excludes {
                // Exclude the same platform-specific classes as :kompressor does,
                // plus all sample-app UI/DI code which has no unit tests.
                // Must mirror `:kompressor`'s host-only exclude list in kompressor/build.gradle.kts
                // — when the root `koverXmlReport` runs, its filters win over the module's, so any
                // class missing here leaks back into `:kompressor:koverVerify`'s aggregate report
                // (e.g. `FilePathSource`, `ContentUriSource`, `ImageSource` were dragging the gate
                // down to 80.7 % even though `:kompressor` excluded them).
                classes(
                    "co.crackn.kompressor.*.Android*",
                    "co.crackn.kompressor.*.Ios*",
                    "co.crackn.kompressor.image.ImageSource",
                    "co.crackn.kompressor.image.ImageSource\$*",
                    "co.crackn.kompressor.image.FilePathSource",
                    "co.crackn.kompressor.image.FilePathSource\$*",
                    "co.crackn.kompressor.image.ContentUriSource",
                    "co.crackn.kompressor.image.ContentUriSource\$*",
                    "co.crackn.kompressor.image.AndroidImageCompressorKt",
                    "co.crackn.kompressor.audio.AndroidAudioCompressorKt",
                    "co.crackn.kompressor.video.AndroidVideoCompressorKt",
                    "co.crackn.kompressor.Media3ExportRunnerKt",
                    "co.crackn.kompressor.Media3ExportRunnerKt\$*",
                    "co.crackn.kompressor.audio.InputAudioFormat",
                    "co.crackn.kompressor.audio.AudioProcessorPlan",
                    "co.crackn.kompressor.audio.AudioProcessorPlan\$*",
                    "co.crackn.kompressor.AndroidKompressor",
                    "co.crackn.kompressor.AndroidKompressorKt",
                    "co.crackn.kompressor.AndroidDeviceCapabilitiesKt",
                    "co.crackn.kompressor.MediaCodecUtilsKt",
                    "co.crackn.kompressor.IosKompressor",
                    "co.crackn.kompressor.IosKompressorKt",
                    "co.crackn.kompressor.IosDeviceCapabilitiesKt",
                    "co.crackn.kompressor.IosFileUtils*",
                    "co.crackn.kompressor.sample.*",
                )
            }
        }
        verify {
            rule {
                bound {
                    minValue = 70
                }
            }
        }
    }
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom("$rootDir/config/detekt/detekt.yml")
    source.setFrom(
        "kompressor/src/commonMain/kotlin",
        "kompressor/src/androidMain/kotlin",
        "kompressor/src/iosMain/kotlin",
    )
}

ktlint {
    version.set("1.3.1")
    android.set(false)
}
