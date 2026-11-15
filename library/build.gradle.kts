import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.gradle.targets.native.tasks.PodBuildTask

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
    alias(libs.plugins.kotlin.cocoapods)
}

group = "dev.kerobit"
version = "1.0.0"

base {
    archivesName.set("kpeer")
}

val jsModuleName = "kpeer"
val cocoaPodName = "KerobitKPeer"
val appleFrameworkName = "KerobitKPeer"

kotlin {
    applyDefaultHierarchyTemplate()

    jvm()
    android {
        namespace = "dev.kerobit.kpeer"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withJava()
        withHostTestBuilder {}.configure {}
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }

        compilerOptions {
            jvmTarget.set(
                JvmTarget.JVM_11
            )
        }
    }
    val iosTargets = listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    )
    val macosArm64 = macosArm64()
    linuxX64()
    mingwX64()

    val xcf = XCFramework(appleFrameworkName)

    js(IR) {
        outputModuleName.set(jsModuleName)
        browser {
            commonWebpackConfig {
                outputFileName = "$jsModuleName.js"
                output?.libraryTarget = "umd"
                output?.library = jsModuleName
            }
        }
        binaries.executable()
    }

    cocoapods {
        summary = "KPeer - simple WebRTC data channel peer for Kotlin Multiplatform"
        name = cocoaPodName
        homepage = "https://kerobit.dev"
        version = "1.0.0"
        ios.deploymentTarget = "17.0"
        osx.deploymentTarget = "12.0"
        pod("WebRTC-SDK") {
            version = "~> 150.7871.01"
            moduleName = "WebRTC"
        }
        framework {
            baseName = appleFrameworkName
            isStatic = true
        }
    }

    (iosTargets + listOf(macosArm64)).forEach { target ->
        target.binaries.framework {
            baseName = appleFrameworkName
            isStatic = true
            xcf.add(this)
        }
    }

    targets.all {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }

        androidMain.dependencies {
            implementation("io.getstream:stream-webrtc-android:1.3.10")
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// Synthetic Pods retain dependency targets below the supported Apple minimums.
tasks.withType<PodBuildTask>().configureEach {
    if (name.contains("Ios")) {
        xcodeBuildSettings.put("IPHONEOS_DEPLOYMENT_TARGET", "17.0")
    } else if (name.contains("Macos")) {
        xcodeBuildSettings.put("MACOSX_DEPLOYMENT_TARGET", "12.0")
    }
}

mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    coordinates(group.toString(), "kpeer", version.toString())

    pom {
        name = "KPeer"
        description = "KPeer - simple WebRTC data channel peer for Kotlin Multiplatform"
        inceptionYear = "2025"
        licenses {
            license {
                name = "MPL2.0"
            }
        }
        developers {
            developer {
                id = "kerobit"
                name = "Kerobit Team"
            }
        }
    }
}
