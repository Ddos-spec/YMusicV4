import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    kotlin("multiplatform")
}

kotlin {
    targets.all {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }

    sourceSets.all {
        languageSettings.optIn("kotlin.ExperimentalMultiplatform")
    }

    val interopDir = project.file("src/nativeInterop/cinterop")

    // Desktop-native targets (macOS + Linux), host-gated so each host only configures what it can
    // build. Matches :kmpHttp:core's desktop targets, which depend on this module.
    withDesktopTargets()
    // Windows host mode configures no libcurl target. Kotlin rejects a module with zero
    // targets if this project is pulled into the graph. The desktop JVM does not depend on it.
    if (targets.withType<KotlinNativeTarget>().isEmpty()) {
        jvm()
    }

    // Attach the libcurl cinterop to every configured native target. Headers + libs come from the
    // host's system/Homebrew libcurl via the .def — no vendored files. The openssl cinterop binds a
    // tiny slice of curl's TLS backend, used to merge custom CAs into the default trust store.
    targets.withType<KotlinNativeTarget>().configureEach {
        compilations.getByName("main").cinterops {
            create("libcurl") {
                definitionFile.set(interopDir.resolve("libcurl.def"))
                packageName("libcurl")
            }
            create("openssl") {
                definitionFile.set(interopDir.resolve("openssl.def"))
                packageName("openssl")
            }
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting

        // Desktop source set shared by whichever desktop targets exist on this host.
        // Skip it when none do (Windows host mode): an unreferenced source set is an error.
        val desktopLeaves = listOf("linuxX64Main", "linuxArm64Main", "macosX64Main", "macosArm64Main")
        if (desktopLeaves.any { findByName(it) != null }) {
            val desktopMain by creating {
                dependsOn(commonMain)
            }
            desktopLeaves.forEach { name ->
                findByName(name)?.dependsOn(desktopMain)
            }
        }

        commonMain.dependencies {
            // :curl is a standalone Kotlin wrapper over libcurl (CurlMultiApi/EasyHandle).
            // It does NOT depend on :kmpHttp:core — the HTTP engine lives in core's desktopMain.
            implementation(Libs.Kotlinx.coroutinesCore)
            implementation(Libs.Okio.core)
            implementation(project(":kmpCommon"))
        }
    }
}
