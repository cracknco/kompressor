import com.android.build.api.dsl.androidLibrary
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

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
                // CRA-80: `posix_spawn` / `waitpid` are not in K/N's built-in iOS posix
                // binding. These static-inline shims expose them to `iosMain` (and by
                // extension the iosTest source set) so the inter-process test can launch
                // the `compressWorker` binary. Header-only — no static library dependency.
                create("PosixSpawn") {
                    defFile(interopDir.resolve("PosixSpawn.def"))
                    includeDirs(interopDir)
                }
            }
        }
    }

    // Standalone K/N executable target used by `iosTest/ConcurrentCompressInterProcessTest`
    // (CRA-80) to get real cross-process coverage of `createKompressor().image.compress(...)`.
    // Only the simulator variant ships the executable — on-device inter-process testing is
    // out of scope and `posix_spawn` requires code-signing on real iOS hardware anyway.
    // `entryPoint` resolves to the top-level `fun main(Array<String>)` in
    // `iosMain/.../worker/CompressWorkerMain.kt`. The function is left `public` (not
    // `internal`) because K/N mangles internal-function names in a way that its entry-point
    // resolver can't reliably look up; the symbol is namespaced under `co.crackn.kompressor
    // .worker` so the leak into the framework's ObjC header is cosmetic rather than a
    // public-API risk.
    iosSimulatorArm64().binaries.executable("compressWorker") {
        entryPoint = "co.crackn.kompressor.worker.main"
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

// Wire the `compressWorker` K/N executable into `iosSimulatorArm64Test` (CRA-80).
//
// The inter-process test (`iosTest/ConcurrentCompressInterProcessTest`) `posix_spawn`s this
// binary, so the test task must (a) trigger the link step for it, and (b) pass the resulting
// absolute path as an env var the test binary can read via `platform.posix.getenv`.
//
// DEBUG build type matches the test binary's own build type — a mismatch would double the
// K/N link cost per CI run (debug + release link tasks both fire) without any diagnostic
// benefit.
//
// `simctl` env var convention: `KotlinNativeSimulatorTest.environment(...)` sets env on the
// `xcrun simctl spawn` invocation, but `simctl spawn` itself only forwards env vars to the
// simulator process when they carry the `SIMCTL_CHILD_` prefix (see `man simctl`). We set
// both the prefixed and unprefixed form — prefixed for simctl's forwarding and unprefixed as
// a safety net for anyone running the raw `.kexe` directly (e.g. from an IDE attach).
val iosSimWorkerBinary = kotlin.iosSimulatorArm64().binaries.getExecutable(
    "compressWorker",
    NativeBuildType.DEBUG,
)
tasks.named<KotlinNativeSimulatorTest>("iosSimulatorArm64Test") {
    dependsOn(iosSimWorkerBinary.linkTaskProvider)
    val workerPath = iosSimWorkerBinary.outputFile.absolutePath
    environment("KOMPRESSOR_COMPRESS_WORKER_PATH", workerPath)
    environment("SIMCTL_CHILD_KOMPRESSOR_COMPRESS_WORKER_PATH", workerPath)
}

// Kover host-only gate at 85 %. Device tests are not wired into CI; if someone runs
// `connectedAndroidDeviceTest` locally, coverage is emitted but not merged into this gate.
//
// LOCKSTEP: `koverExcludedClasses` below must stay in sync with the root `build.gradle.kts`'s
// `rootKoverExcludes`. The root filters win over the module's for `:kompressor:koverVerify`,
// so any drift silently changes what the quality gate evaluates. Only `sample.*` is allowed
// to differ (root-only because the sample app isn't part of this module).
val koverExcludedClasses = listOf(
    // Platform glue — device or simulator only, no equivalent pure logic host-side.
    "co.crackn.kompressor.AndroidDeviceCapabilitiesKt",
    "co.crackn.kompressor.MediaCodecUtilsKt",
    "co.crackn.kompressor.IosDeviceCapabilitiesKt",
    "co.crackn.kompressor.IosFileUtils*",
    "co.crackn.kompressor.IosKompressor",
    "co.crackn.kompressor.IosKompressorKt",
    "co.crackn.kompressor.AndroidKompressor",
    "co.crackn.kompressor.AndroidKompressor\$*",
    "co.crackn.kompressor.AndroidKompressorKt",
    // Classes that need a real codec stack / native platform APIs — unreachable from
    // `testAndroidHostTest`.
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
    // JaCoCo for `.ec` host-side binaries. Matches AGP's `enableCoverage = true` on
    // `withDeviceTestBuilder` so local device-test runs produce a compatible binary format.
    useJacoco()
    reports {
        filters {
            excludes { classes(koverExcludedClasses) }
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

