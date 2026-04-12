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

kover {
    reports {
        filters {
            excludes {
                // Platform implementations that require device/simulator tests (not host JVM)
                classes(
                    "co.crackn.kompressor.*.Android*",
                    "co.crackn.kompressor.*.Ios*",
                    "co.crackn.kompressor.audio.AndroidAudioCompressorKt",
                    "co.crackn.kompressor.video.AndroidVideoCompressorKt",
                    // Shared Media3 → coroutines glue. The Transformer listener + progress
                    // poller can only be exercised on an emulator/device where Media3 has a
                    // real codec stack to drive. Wildcard also excludes the inner lambda
                    // classes the Kotlin compiler generates for the listener / progress job.
                    "co.crackn.kompressor.Media3ExportRunnerKt",
                    "co.crackn.kompressor.Media3ExportRunnerKt\$*",
                    // Runtime-only probe data class populated from MediaExtractor (device).
                    "co.crackn.kompressor.audio.InputAudioFormat",
                    // The plan *selection* logic (`planAudioProcessors`) is in the same file
                    // and covered by host tests; the data class itself plus `toProcessors`
                    // instantiates Media3 audio processors that pull in `android.util.SparseArray`
                    // and can only run on a device.
                    "co.crackn.kompressor.audio.AudioProcessorPlan",
                    "co.crackn.kompressor.audio.AudioProcessorPlan\$*",
                    // Device/simulator-only platform glue. Pure logic extracted from
                    // these has been moved to separate files that ARE covered.
                    "co.crackn.kompressor.AndroidKompressor",
                    "co.crackn.kompressor.AndroidKompressorKt",
                    "co.crackn.kompressor.AndroidDeviceCapabilitiesKt",
                    "co.crackn.kompressor.MediaCodecUtilsKt",
                    "co.crackn.kompressor.IosKompressor",
                    "co.crackn.kompressor.IosKompressorKt",
                    "co.crackn.kompressor.IosDeviceCapabilitiesKt",
                    "co.crackn.kompressor.IosFileUtils*",
                    "co.crackn.kompressor.KompressorInitializer",
                    "co.crackn.kompressor.KompressorContext",
                )
            }
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
