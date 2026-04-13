import com.android.build.api.dsl.androidLibrary

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
    alias(libs.plugins.kover)
    alias(libs.plugins.binaryCompatibilityValidator)
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
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
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
val mergedCoverageGate = providers.gradleProperty("koverMergedGate").orNull == "true"
kover {
    reports {
        filters {
            excludes {
                // Always-excluded platform glue — device or simulator only, no equivalent pure
                // logic available host-side. These exist irrespective of merged vs host-only mode.
                classes(
                    "co.crackn.kompressor.KompressorInitializer",
                    "co.crackn.kompressor.KompressorContext",
                    "co.crackn.kompressor.AndroidDeviceCapabilitiesKt",
                    "co.crackn.kompressor.MediaCodecUtilsKt",
                    "co.crackn.kompressor.IosDeviceCapabilitiesKt",
                    "co.crackn.kompressor.IosFileUtils*",
                    "co.crackn.kompressor.IosKompressor",
                    "co.crackn.kompressor.IosKompressorKt",
                    "co.crackn.kompressor.AndroidKompressor",
                    "co.crackn.kompressor.AndroidKompressorKt",
                )
                if (!mergedCoverageGate) {
                    // Host-only mode: add all classes that require a real codec stack / native
                    // platform APIs. In merged mode, device tests cover these and they're included.
                    classes(
                        "co.crackn.kompressor.*.Android*",
                        "co.crackn.kompressor.*.Ios*",
                        "co.crackn.kompressor.image.ImageSource",
                        "co.crackn.kompressor.image.ImageSource\$*",
                        "co.crackn.kompressor.image.FilePathSource",
                        "co.crackn.kompressor.image.ContentUriSource",
                        "co.crackn.kompressor.image.AndroidImageCompressorKt",
                        "co.crackn.kompressor.audio.AndroidAudioCompressorKt",
                        "co.crackn.kompressor.video.AndroidVideoCompressorKt",
                        "co.crackn.kompressor.Media3ExportRunnerKt",
                        "co.crackn.kompressor.Media3ExportRunnerKt\$*",
                        "co.crackn.kompressor.audio.InputAudioFormat",
                        "co.crackn.kompressor.audio.AudioProcessorPlan",
                        "co.crackn.kompressor.audio.AudioProcessorPlan\$*",
                    )
                }
            }
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
