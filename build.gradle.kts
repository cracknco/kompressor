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
//
// In merged mode (`-PkoverMergedGate=true`) the device-only excludes are dropped because FTL
// device coverage covers them — same trim as the module's gate so the two stay in lockstep.
val rootMergedCoverageGate = providers.gradleProperty("koverMergedGate").orNull == "true"

val rootKoverExcludes =
    buildList {
        // Native-only platform glue — no host-side equivalent on either platform. Excluded in
        // every mode because no test job can ever exercise these classes.
        add("co.crackn.kompressor.AndroidKompressor")
        add("co.crackn.kompressor.AndroidKompressorKt")
        add("co.crackn.kompressor.AndroidDeviceCapabilitiesKt")
        add("co.crackn.kompressor.MediaCodecUtilsKt")
        add("co.crackn.kompressor.IosKompressor")
        add("co.crackn.kompressor.IosKompressorKt")
        add("co.crackn.kompressor.IosDeviceCapabilitiesKt")
        add("co.crackn.kompressor.IosFileUtils*")
        // Sample app — no unit tests. Excluded irrespective of mode.
        add("co.crackn.kompressor.sample.*")
        if (!rootMergedCoverageGate) {
            // Host-only mode: drop everything that needs a real codec stack. In merged mode
            // device tests cover these so they are *included* in the gate.
            add("co.crackn.kompressor.*.Android*")
            add("co.crackn.kompressor.*.Ios*")
            add("co.crackn.kompressor.image.ImageSource")
            add("co.crackn.kompressor.image.ImageSource\$*")
            add("co.crackn.kompressor.image.FilePathSource")
            add("co.crackn.kompressor.image.FilePathSource\$*")
            add("co.crackn.kompressor.image.ContentUriSource")
            add("co.crackn.kompressor.image.ContentUriSource\$*")
            add("co.crackn.kompressor.image.AndroidImageCompressorKt")
            add("co.crackn.kompressor.audio.AndroidAudioCompressorKt")
            add("co.crackn.kompressor.audio.AudioTrackExtractionKt")
            add("co.crackn.kompressor.video.AndroidVideoCompressorKt")
            add("co.crackn.kompressor.Media3ExportRunnerKt")
            add("co.crackn.kompressor.Media3ExportRunnerKt\$*")
            add("co.crackn.kompressor.audio.InputAudioFormat")
            add("co.crackn.kompressor.audio.AudioProcessorPlan")
            add("co.crackn.kompressor.audio.AudioProcessorPlan\$*")
        }
    }

kover {
    reports {
        filters {
            excludes { classes(rootKoverExcludes) }
        }
        verify {
            rule {
                bound {
                    // Keep the root gate in lockstep with the module's two-mode gate so
                    // `./gradlew koverVerify` (root) and `./gradlew :kompressor:koverVerify`
                    // both signal the same regression at the same threshold.
                    minValue = if (rootMergedCoverageGate) 90 else 85
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
