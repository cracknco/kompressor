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

// LOCKSTEP: this exclude list must stay in sync with `kompressor/build.gradle.kts`'s
// `koverExcludedClasses`. The root `koverXmlReport` filters win over the module's, so any class
// excluded in one list but not the other silently leaks into or out of the aggregate report —
// `:kompressor:koverVerify` reads the root's resolved set. The delta between them is intentional
// and narrow: only `co.crackn.kompressor.sample.*` lives at the root level because the sample
// app isn't part of the library module. Every other entry must match.
val rootKoverExcludes =
    listOf(
        // Native-only platform glue — no host-side equivalent on either platform.
        "co.crackn.kompressor.AndroidKompressor",
        "co.crackn.kompressor.AndroidKompressor\$*",
        "co.crackn.kompressor.AndroidKompressorKt",
        "co.crackn.kompressor.AndroidDeviceCapabilitiesKt",
        "co.crackn.kompressor.MediaCodecUtilsKt",
        "co.crackn.kompressor.IosKompressor",
        "co.crackn.kompressor.IosKompressorKt",
        "co.crackn.kompressor.IosDeviceCapabilitiesKt",
        "co.crackn.kompressor.IosFileUtils*",
        // Sample app — no unit tests.
        "co.crackn.kompressor.sample.*",
        // Classes that need a real codec stack / native platform APIs — unreachable from
        // `testAndroidHostTest`; covered only when someone runs device tests locally.
        "co.crackn.kompressor.*.Android*",
        "co.crackn.kompressor.*.Ios*",
        "co.crackn.kompressor.image.ImageSource",
        "co.crackn.kompressor.image.ImageSource\$*",
        "co.crackn.kompressor.image.FilePathSource",
        "co.crackn.kompressor.image.FilePathSource\$*",
        "co.crackn.kompressor.image.ContentUriSource",
        "co.crackn.kompressor.image.ContentUriSource\$*",
        "co.crackn.kompressor.image.AndroidImageCompressorKt",
        "co.crackn.kompressor.image.ExifRotation",
        "co.crackn.kompressor.audio.AndroidAudioCompressorKt",
        "co.crackn.kompressor.audio.AudioTrackExtractionKt",
        "co.crackn.kompressor.audio.ForceTranscodeAudioProcessor",
        "co.crackn.kompressor.audio.ForceTranscodeAudioProcessor\$*",
        "co.crackn.kompressor.video.AndroidVideoCompressorKt",
        "co.crackn.kompressor.video.VideoProbe",
        "co.crackn.kompressor.Media3ExportRunnerKt",
        "co.crackn.kompressor.Media3ExportRunnerKt\$*",
        "co.crackn.kompressor.DeletingOutputOnFailureKt",
        "co.crackn.kompressor.SuspendRunCatchingKt",
        "co.crackn.kompressor.audio.InputAudioFormat",
        "co.crackn.kompressor.audio.AudioProbeResult",
        "co.crackn.kompressor.audio.AudioProcessorPlan",
        "co.crackn.kompressor.audio.AudioProcessorPlan\$*",
    )

kover {
    // JaCoCo for host-side `.ec` files; matches AGP's `enableCoverage = true` on
    // `withDeviceTestBuilder` so local runs of `connectedAndroidDeviceTest` produce a
    // compatible binary format if anyone wants to merge them manually.
    useJacoco()
    reports {
        filters {
            excludes { classes(rootKoverExcludes) }
        }
        verify {
            rule {
                bound {
                    minValue = 85
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
