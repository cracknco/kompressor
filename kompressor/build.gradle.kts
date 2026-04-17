import com.android.build.api.dsl.androidLibrary
import com.github.jk1.license.filter.LicenseBundleNormalizer
import com.github.jk1.license.render.InventoryHtmlReportRenderer
import com.github.jk1.license.render.JsonReportRenderer

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
    alias(libs.plugins.kover)
    alias(libs.plugins.binaryCompatibilityValidator)
    alias(libs.plugins.dependencyLicenseReport)
}

group = "co.crackn.kompressor"
version = "0.1.0"

kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }

    androidLibrary {
        namespace = "co.crackn.kompressor"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        // Several transitive dependencies (AndroidX, Media3, Kotest) ship the same
        // Apache 2.0 / LGPL license notice files. Without these excludes the
        // `mergeAndroidDeviceTestJavaResource` task fails with DuplicateRelativeFileException
        // on the APK packaging step for instrumentation tests.
        packaging {
            resources.excludes.addAll(
                listOf(
                    "META-INF/AL2.0",
                    "META-INF/LGPL2.1",
                    "META-INF/licenses/ASM",
                    "META-INF/DEPENDENCIES",
                    "META-INF/LICENSE*",
                    "META-INF/NOTICE*",
                    "META-INF/*.kotlin_module",
                ),
            )
        }

        withJava()
        withHostTestBuilder {}.configure {}
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            // JaCoCo instrumentation so `connectedAndroidDeviceTest` emits `.ec` coverage
            // files under kompressor/build/outputs/code_coverage/. Kover picks these up
            // automatically when `koverXmlReport` runs after the device test task.
            enableCoverage = true
        }

        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(
                        org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
                    )
                }
            }
        }
    }
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Kompressor"
            isStatic = true
        }
        val interopDir = project.file("src/nativeInterop/cinterop")
        val targetLibDir = when (target.name) {
            "iosArm64" -> "iosArm64"
            "iosSimulatorArm64" -> "iosSimulatorArm64"
            "iosX64" -> "iosX64"
            else -> error("Unexpected iOS target: ${target.name}")
        }
        target.compilations.getByName("main") {
            cinterops {
                create("ObjCExceptionCatcher") {
                    defFile(interopDir.resolve("ObjCExceptionCatcher.def"))
                    includeDirs(interopDir)
                    extraOpts("-libraryPath", interopDir.resolve("libs/$targetLibDir").absolutePath)
                }
            }
        }
    }

    sourceSets {
        // Tests freely exercise experimental (`@ExperimentalKompressorApi`) surface — opt in at
        // the module level so individual `@OptIn` on every test class is unnecessary. The
        // propagation is scoped to every test source set (common + android host/device +
        // per-target iosX64/iosArm64/iosSimulatorArm64Test); production
        // `commonMain`/`androidMain`/`iosMain` must still opt in explicitly so accidental use of
        // experimental API from stable code is still caught at compile time. See
        // docs/api-stability.md for the stability contract.
        val experimentalOptIn = "co.crackn.kompressor.ExperimentalKompressorApi"
        matching { it.name.endsWith("Test") }.configureEach {
            languageSettings.optIn(experimentalOptIn)
        }

        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotest.framework.engine)
            implementation(libs.kotest.assertions.core)
            implementation(libs.kotest.property)
            implementation(libs.turbine)
        }

        androidMain.dependencies {
            // `kotlinx-coroutines-core` is inherited from commonMain but it does NOT provide
            // the Handler-backed Dispatchers.Main on Android. Without this artifact, any
            // `withContext(Dispatchers.Main)` (used by the Media3 Transformer integration)
            // throws "Module with the Main dispatcher had failed to initialize" at runtime.
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.androidx.exifinterface)
            implementation(libs.androidx.startup.runtime)
            implementation(libs.androidx.media3.transformer)
            implementation(libs.androidx.media3.effect)
            implementation(libs.androidx.media3.common)
        }

        getByName("androidHostTest").dependencies {
            implementation(libs.mockk)
        }

        getByName("androidDeviceTest").dependencies {
            implementation(libs.junit)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.rules)
        }
    }
}

// Kover has two gate modes:
//   * Default (host-only): excludes every class that can only run on a device or simulator
//     because `testAndroidHostTest` alone can't exercise them. 85 % is the bar for this mode.
//   * Merged (host + FTL device `.ec`): triggered by `-PkoverMergedGate=true`, trims the
//     excludes for device-testable classes (Android*/Ios* compressors, `Media3ExportRunner`,
//     `AudioProcessorPlan`) and raises the bar to 90 %. The CI `merged-coverage` job drops
//     the device `coverage.ec` into `kompressor/build/outputs/code_coverage/connectedAndroidDeviceTest/`
//     before invoking `koverXmlReport`, so those classes show up in the merged report.
//
// LOCKSTEP: `koverExcludedClasses` below must stay in sync with the root `build.gradle.kts`'s
// `rootKoverExcludes`. The root filters win over the module's for `:kompressor:koverVerify`,
// so any drift silently changes what the quality gate evaluates. Only `sample.*` is allowed
// to differ (root-only because the sample app isn't part of this module).
val mergedCoverageGate = providers.gradleProperty("koverMergedGate").orNull == "true"

// `reports.filters` cascades to every variant including `verify` per Kover 0.9.8's
// `KoverReportsConfig` docs — `KoverVerifyRule` exposes no `filters { }` DSL of its own, so
// declaring the excludes once here is both correct and the only option. Empirical check:
// host-only mode passes 85 % and `-PkoverMergedGate=true` fails at ~52 % locally, which is
// only possible if `koverVerify` is reading the `reports.filters` excludes.
val koverExcludedClasses = buildList {
    // Platform glue — device or simulator only, no equivalent pure logic available
    // host-side. Excluded irrespective of host-only vs merged mode.
    add("co.crackn.kompressor.AndroidDeviceCapabilitiesKt")
    add("co.crackn.kompressor.MediaCodecUtilsKt")
    add("co.crackn.kompressor.IosDeviceCapabilitiesKt")
    add("co.crackn.kompressor.IosFileUtils*")
    add("co.crackn.kompressor.IosKompressor")
    add("co.crackn.kompressor.IosKompressorKt")
    add("co.crackn.kompressor.AndroidKompressor")
    add("co.crackn.kompressor.AndroidKompressor\$*")
    add("co.crackn.kompressor.AndroidKompressorKt")
    if (!mergedCoverageGate) {
        // Host-only mode: add all classes that require a real codec stack / native
        // platform APIs. In merged mode, device tests cover these and they're included.
        add("co.crackn.kompressor.*.Android*")
        add("co.crackn.kompressor.*.Ios*")
        add("co.crackn.kompressor.image.ImageSource")
        add("co.crackn.kompressor.image.ImageSource\$*")
        add("co.crackn.kompressor.image.FilePathSource")
        add("co.crackn.kompressor.image.FilePathSource\$*")
        add("co.crackn.kompressor.image.ContentUriSource")
        add("co.crackn.kompressor.image.ContentUriSource\$*")
        add("co.crackn.kompressor.image.AndroidImageCompressorKt")
        add("co.crackn.kompressor.image.ExifRotation")
        add("co.crackn.kompressor.audio.AndroidAudioCompressorKt")
        add("co.crackn.kompressor.audio.AudioTrackExtractionKt")
        add("co.crackn.kompressor.audio.ForceTranscodeAudioProcessor")
        add("co.crackn.kompressor.audio.ForceTranscodeAudioProcessor\$*")
        add("co.crackn.kompressor.video.AndroidVideoCompressorKt")
        add("co.crackn.kompressor.video.VideoProbe")
        add("co.crackn.kompressor.Media3ExportRunnerKt")
        add("co.crackn.kompressor.Media3ExportRunnerKt\$*")
        add("co.crackn.kompressor.DeletingOutputOnFailureKt")
        add("co.crackn.kompressor.SuspendRunCatchingKt")
        add("co.crackn.kompressor.audio.InputAudioFormat")
        add("co.crackn.kompressor.audio.AudioProbeResult")
        add("co.crackn.kompressor.audio.AudioProcessorPlan")
        add("co.crackn.kompressor.audio.AudioProcessorPlan\$*")
    }
}

kover {
    // Match the root: JaCoCo for both host and device so `connectedAndroidDeviceTest`'s `.ec`
    // merges cleanly with `testAndroidHostTest`'s `.ec` in the aggregate report.
    useJacoco()
    reports {
        filters {
            excludes { classes(koverExcludedClasses) }
        }
        verify {
            rule {
                bound {
                    // 85 % host-only, 90 % merged (host + device).
                    minValue = if (mergedCoverageGate) 90 else 85
                }
            }
        }
    }
}

// Inject the on-device JaCoCo `.exec` from `connectedAndroidDeviceTest` into Kover's artifact
// generator. Kover 0.9.8's Android locator (`AbstractVariantArtifacts.fromOrigin`) filters its
// per-variant test-task list to `AndroidUnitTest` subclasses only, so `connectedAndroidDeviceTest`
// (a `DeviceProviderInstrumentTestTask`) is invisible to the auto-locator. The binary report
// file already sits at `build/kover/bin-reports/connectedAndroidDeviceTest.exec` (the CI places
// it there after FTL pulls it) and JaCoCo can read it; Kover just needs to be told the file is
// part of its `reports` FileCollection. Reflection is necessary because `KoverArtifactGenerationTask`
// is declared `internal` in the plugin and we can't reference its type directly.
if (mergedCoverageGate) {
    afterEvaluate {
        val deviceExec = layout.buildDirectory.file("kover/bin-reports/connectedAndroidDeviceTest.exec")
        tasks.matching { it.name.startsWith("koverGenerateArtifact") }.configureEach {
            val task = this
            @Suppress("UNCHECKED_CAST")
            val reports = task.javaClass.getMethod("getReports")
                .invoke(task) as org.gradle.api.file.ConfigurableFileCollection
            reports.from(deviceExec)
            task.inputs.file(deviceExec).optional(true).withPropertyName("deviceBinaryReport")
        }
    }
}

mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    coordinates(group.toString(), "kompressor", version.toString())

    pom {
        name = "Kompressor"
        description = "Compress images, videos and audio on Android & iOS — one Kotlin API, native hardware, zero binaries"
        inceptionYear = "2025"
        url = "https://github.com/cracknco/kompressor"
        licenses {
            license {
                name = "Apache-2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "cracknco"
                name = "crackn.co"
                url = "https://crackn.co"
            }
        }
        scm {
            url = "https://github.com/cracknco/kompressor"
            connection = "scm:git:git://github.com/cracknco/kompressor.git"
            developerConnection = "scm:git:ssh://git@github.com/cracknco/kompressor.git"
        }
    }
}

licenseReport {
    outputDir = rootProject.layout.buildDirectory.dir("reports/dependency-license").get().asFile.path
    renderers = arrayOf<com.github.jk1.license.render.ReportRenderer>(
        InventoryHtmlReportRenderer("index.html"),
        JsonReportRenderer("licenses.json", false),
    )
    filters = arrayOf<com.github.jk1.license.filter.DependencyFilter>(LicenseBundleNormalizer())
    excludeOwnGroup = true
    // Scans only the Android runtime classpath because iOS-side dependencies are Apple
    // system frameworks (AVFoundation, CoreImage, …) shipped with the OS rather than
    // Maven artifacts — there is no iOS configuration with transitive Maven deps to scan.
    configurations = arrayOf("androidRuntimeClasspath")
}
