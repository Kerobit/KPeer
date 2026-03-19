import com.android.build.api.dsl.androidLibrary
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
    alias(libs.plugins.kotlin.cocoapods)
}

group = "com.kerobit"
version = "1.0.0"

base {
    archivesName.set("kpeer")
}

val jsModuleName = "kpeer"
val cocoaPodName = "KerobitKPeer"
val appleFrameworkName = "KerobitKPeer"

kotlin {
    jvm()
    androidLibrary {
        namespace = "com.kerobit.kpeer"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withJava() // enable java compilation support
        withHostTestBuilder {}.configure {}
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }

        compilations.configureEach {
            compilerOptions.configure {
                jvmTarget.set(
                    JvmTarget.JVM_11
                )
            }
        }
    }
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    linuxX64()

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
        // - library(): publishable artifact (npm-style, may be split into multiple files)
        // - executable(): webpack bundle for easy <script> usage (single output file in build/distributions)
        //binaries.library()
        binaries.executable()
    }

    cocoapods {
        summary = "KPeer - simple WebRTC data channel peer for Kotlin Multiplatform"
        name = cocoaPodName
        homepage = "https://kerobit.com"
        version = "1.0.0"
        ios.deploymentTarget = "16.0"
        pod("WebRTC-SDK") {
            version = "~> 137.7151.12"
            moduleName = "WebRTC"
        }
        framework {
            baseName = appleFrameworkName
            isStatic = true
        }
    }

    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
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
            implementation("io.getstream:stream-webrtc-android:1.1.1")
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// Gradle task validation: some JS packaging tasks consume outputs from compileSync tasks.
// Newer Gradle versions require explicit task dependencies to avoid implicit dependency warnings/errors.
// val jsProductionLibraryCompileSyncTask = "jsProductionLibraryCompileSync"
// val jsProductionExecutableCompileSyncTask = "jsProductionExecutableCompileSync"

// tasks.matching { it.name == "jsBrowserProductionWebpack" || it.name == "jsBrowserDevelopmentWebpack" }.configureEach {
//     dependsOn(jsProductionLibraryCompileSyncTask)
// }

// tasks.matching {
//     it.name == "jsBrowserProductionLibraryDistribution" || it.name == "jsBrowserDevelopmentLibraryDistribution"
// }.configureEach {
//     dependsOn(jsProductionExecutableCompileSyncTask)
// }

mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    coordinates(group.toString(), "kpeer", version.toString())

    pom {
        name = "KPeer"
        description = "KPeer - simple WebRTC data channel peer for Kotlin Multiplatform"
        inceptionYear = "2024"
        licenses {
            license {
                name = "Proprietary"
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
