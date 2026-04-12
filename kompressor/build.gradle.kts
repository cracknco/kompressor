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

        withJava()
        withHostTestBuilder {}.configure {}
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
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
                    "co.crackn.kompressor.audio.TranscodeLoop",
                    "co.crackn.kompressor.video.AndroidVideoCompressorKt",
                    "co.crackn.kompressor.AndroidKompressor",
                    "co.crackn.kompressor.AndroidKompressorKt",
                    "co.crackn.kompressor.AndroidDeviceCapabilitiesKt",
                    "co.crackn.kompressor.IosKompressor",
                    "co.crackn.kompressor.IosKompressorKt",
                    "co.crackn.kompressor.IosDeviceCapabilitiesKt",
                    "co.crackn.kompressor.IosFileUtils*",
                    "co.crackn.kompressor.MediaCodecUtilsKt",
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
