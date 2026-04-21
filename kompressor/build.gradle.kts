import com.android.build.api.dsl.androidLibrary

import java.net.URI

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
    alias(libs.plugins.kover)
    alias(libs.plugins.binaryCompatibilityValidator)
    // Dokka v2 — generates the HTML API reference consumed by the gh-pages publishing workflow
    // (`.github/workflows/publish-dokka.yml`). The same HTML output is also packaged as the
    // `-javadoc.jar` attached to every Maven Central release — see the `mavenPublishing` block
    // below. We intentionally do NOT apply `org.jetbrains.dokka-javadoc`: Dokka v2's Javadoc
    // plugin does not yet support Kotlin Multiplatform (it aborts with "Pre-generation validity
    // check failed: Dokka Javadoc plugin currently does not support generating documentation for
    // multiplatform project"). Using the HTML bundle as the javadoc JAR is the standard workaround
    // across KMP libraries on Maven Central (kotlinx-serialization, Ktor, etc.) — consumers get a
    // navigable, up-to-date reference in the JAR either way.
    alias(libs.plugins.dokka)
}

group = "co.crackn"
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
            // okio — leaked in the public API surface via `MediaSource.Local.Stream.source`
            // (okio.Source) and `MediaDestination.Local.Stream.sink` (okio.Sink). Declared as
            // `api` so consumers constructing `Stream` variants get the symbols on their
            // classpath without manually re-declaring the dependency — otherwise a caller
            // discovering `Stream` via IDE autocomplete hits a surprising
            // `unresolved reference: okio.Source`. The original plan (`tmp/tier1-1-io-model.md`
            // §9.2 / Q1) leaned toward `implementation` to avoid forcing okio on `FilePath`-only
            // callers, but the UX cost of broken autocomplete outweighs a ~320 KB transitive
            // dependency [CRA-90 review]. If the transitive cost ever becomes a real concern,
            // the proper fix is to hide okio behind an internal adapter rather than leak it via
            // `implementation`.
            api(libs.okio)
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
            // CRA-50 — LeakCanary is scoped to the instrumentation test classpath so heap
            // dumps / retained-instance detection run alongside our golden tests, but the
            // library's published artifact ships zero LeakCanary bytes. If a leak slips
            // into the Media3/AVFoundation integration, `CompressionLeakTest` fails CI.
            implementation(libs.leakcanary.android.instrumentation)
        }
    }
}

// Kover host-only gate at 85 %. Device tests are not wired into CI; if someone runs
// `connectedAndroidDeviceTest` locally, coverage is emitted but not merged into this gate.
//
// CRA-43: `FormatSupportDocUpToDateTest` (in `androidHostTest`) compares the auto-generated
// section of `docs/format-support.md` against `renderFormatSupportMatrixTables()`. The test
// needs to know where `docs/` lives regardless of where Gradle is invoked from, so we inject
// the absolute path via a JVM system property rather than have the test walk up from the CWD.
// `-PregenerateFormatSupportDoc=true` flips the test from verify-mode into rewrite-mode —
// driven by `scripts/regenerate-format-support-doc.sh`.
val docsDir = rootProject.file("docs").absolutePath
val regenerateFormatSupportDoc =
    providers.gradleProperty("regenerateFormatSupportDoc").orNull == "true"
// CRA-44 drift gate (follow-up, PR #132): `ErrorTaxonomyDocUpToDateTest` in `androidHostTest`
// renders `docs/error-handling.md` from the three `…CompressionError.kt` sealed hierarchies and
// asserts byte-identity with the committed copy. `-PregenerateErrorTaxonomyDoc=true` flips the
// test into rewrite mode — driven by `scripts/regenerate-error-taxonomy.sh`.
val regenerateErrorTaxonomyDoc =
    providers.gradleProperty("regenerateErrorTaxonomyDoc").orNull == "true"
// CRA-43 (follow-up to peer review): expose the authoritative build-time platform floors to
// host tests so `FormatSupportMatrixBuildVersionPinTest` can assert the hand-mirrored
// `FormatSupportMatrix.ANDROID_MIN_SDK` / `IOS_MIN_VERSION` constants haven't drifted. Any
// future change to these versions (typically in `libs.versions.toml`) will fail CI until the
// matrix is regenerated, matching the existing HEIC / AVIF gate pinning.
val matrixBuildAndroidMinSdk = libs.versions.android.minSdk.get()
val matrixBuildIosDeploymentTarget = libs.versions.ios.deploymentTarget.get()
tasks.withType<Test>().configureEach {
    systemProperty("kompressor.docsDir", docsDir)
    systemProperty("kompressor.buildAndroidMinSdk", matrixBuildAndroidMinSdk)
    systemProperty("kompressor.buildIosDeploymentTarget", matrixBuildIosDeploymentTarget)
    if (regenerateFormatSupportDoc) {
        systemProperty("kompressor.regenerateFormatSupportDoc", "true")
        // Test outputs are cacheable by default; a cached "verify passed" run skips the
        // file write in regenerate mode. Force re-execution whenever we're rewriting.
        outputs.upToDateWhen { false }
    }
    if (regenerateErrorTaxonomyDoc) {
        systemProperty("kompressor.regenerateErrorTaxonomyDoc", "true")
        outputs.upToDateWhen { false }
    }
}

// Single source of truth for the Kover exclusion set lives in the root `build.gradle.kts`,
// which populates `rootProject.extra["baseKoverExcludes"]` before this script evaluates.
@Suppress("UNCHECKED_CAST")
val koverExcludedClasses = rootProject.extra["baseKoverExcludes"] as List<String>

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

// Dokka configuration — generates the HTML API reference consumed by `publish-dokka.yml` and the
// Javadoc HTML attached as `-javadoc.jar` to every Maven Central release. Default output location
// (`kompressor/build/dokka/html/`) matches the path documented in the DoD and the publish workflow.
dokka {
    moduleName.set("kompressor")
    // Local `./gradlew :kompressor:dokkaHtml` invocations will stamp the hardcoded `version`
    // on line 24 ("0.1.0") in the footer, search index, and JSON metadata. CI overrides this
    // by passing `-Pversion=<release-tag>` from `.github/workflows/publish-dokka.yml` so
    // gh-pages archives reflect the real release version.
    moduleVersion.set(project.version.toString())

    // Ref used to build GitHub source-link URLs. Defaults to `main` so local Dokka runs
    // resolve to the latest code; the release workflow overrides via `-PsourceLinkRef=v<version>`
    // so immutable `/api/<version>/` archives point at the matching release tag and don't drift
    // as `main` evolves.
    val sourceLinkRef = (project.findProperty("sourceLinkRef") as? String) ?: "main"

    dokkaSourceSets.configureEach {
        // Hide anything not part of the public API contract. `internal` visibility is only used
        // for platform implementations (e.g. `AndroidImageCompressor`) that must not leak into
        // the reference; Dokka's default already excludes them — the explicit list is a guard
        // against future visibility drift.
        documentedVisibilities.set(setOf(org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier.Public))
        // Link back to the source lines on GitHub so readers can jump from the reference into
        // the implementation. `remoteLineSuffix` is `#L` for GitHub's anchor convention.
        sourceLink {
            localDirectory.set(projectDir.resolve("src"))
            remoteUrl.set(URI("https://github.com/cracknco/kompressor/tree/$sourceLinkRef/kompressor/src"))
            remoteLineSuffix.set("#L")
        }
        // External doc links so Kotlin stdlib / coroutines / Android SDK symbols are clickable
        // instead of plain text. Without these, e.g. `kotlinx.coroutines.flow.Flow` references in
        // the API render as dead strings.
        // Pin `packageListUrl` on both links. Without an explicit URL, Dokka tries to derive it
        // by appending `/package-list` to the base `url` — which is correct today for both
        // endpoints, but a silent failure mode if JetBrains or Google ever reorganise their docs
        // tree (links render as plain text instead of anchors, no build warning). Pinning keeps
        // the behaviour audit-able at review time.
        externalDocumentationLinks.register("kotlinx-coroutines") {
            url.set(URI("https://kotlinlang.org/api/kotlinx.coroutines/"))
            packageListUrl.set(URI("https://kotlinlang.org/api/kotlinx.coroutines/package-list"))
        }
        externalDocumentationLinks.register("android") {
            url.set(URI("https://developer.android.com/reference/"))
            packageListUrl.set(URI("https://developer.android.com/reference/package-list"))
        }

        includes.from(project.layout.projectDirectory.file("dokka/module.md"))
    }

    pluginsConfiguration.html {
        footerMessage.set(
            "Kompressor is licensed under Apache 2.0. " +
                "See the <a href=\"https://cracknco.github.io/kompressor/\">user guide</a> " +
                "for conceptual documentation.",
        )
        // Links back to the Mintlify documentation site — keeps the two surfaces cross-linked so
        // readers who land on the generated reference don't get stuck (CRA-40 DoD: "liens croisés
        // avec Mintlify site").
        homepageLink.set("https://cracknco.github.io/kompressor/")
    }
}

// DoD alias: `./gradlew dokkaHtml` must produce the HTML reference at
// `kompressor/build/dokka/html/` (CRA-40). Dokka v2's canonical task is
// `dokkaGeneratePublicationHtml`; keeping the legacy `dokkaHtml` name as a lightweight wrapper
// means build scripts, docs, and muscle memory keep working through the v1 → v2 migration.
tasks.register("dokkaHtml") {
    group = "documentation"
    description = "Generate HTML API docs into kompressor/build/dokka/html/ (alias for dokkaGeneratePublicationHtml)."
    dependsOn("dokkaGeneratePublicationHtml")
}

mavenPublishing {
    // `automaticRelease = true` uploads the bundle to Sonatype Central Portal AND auto-promotes it
    // to Maven Central in one CI pass. The previous plain `publishToMavenCentral()` left every
    // deployment in `USER_MANAGED` staging waiting for a manual "Publish" click in the Portal UI
    // — with three consecutive releases (0.1.0 / 0.2.0 / 0.2.1) piling up unpromoted, this was a
    // silent publish-pipeline failure (BUILD SUCCESSFUL but nothing on repo1.maven.org). Auto-
    // promote means every tagged release reaches consumers without manual intervention.
    // Point the javadoc JAR at Dokka v2's HTML publication task explicitly — see the plugin-
    // application comment above for why we can't use the dedicated Javadoc plugin on a KMP
    // project. Without this explicit wiring the Vanniktech plugin falls back to its own empty-
    // stub JAR, which Maven Central accepts but gives consumers no rendered reference.
    configure(
        com.vanniktech.maven.publish.KotlinMultiplatform(
            javadocJar = com.vanniktech.maven.publish.JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
        ),
    )
    publishToMavenCentral(automaticRelease = true)

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

