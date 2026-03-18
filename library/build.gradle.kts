import com.android.build.api.dsl.androidLibrary
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
    alias(libs.plugins.kotlin.cocoapods)
}

group = "com.kerobit"
version = "1.0.0"

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

    js(IR) {
        browser()
    }

    cocoapods {
        summary = "KPeer - simple WebRTC data channel peer for Kotlin Multiplatform"
        name = "KPeer"
        homepage = "https://kerobit.com"
        version = "1.0.0"
        ios.deploymentTarget = "16.0"
        pod("WebRTC-SDK") {
            version = "~> 137.7151.12"
            moduleName = "WebRTC"
        }
        framework {
            baseName = "KPeer"
        }
    }

    targets.all {
        compilations.all {
            compilerOptions.configure {
                freeCompilerArgs.add("-Xexpect-actual-classes")
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
