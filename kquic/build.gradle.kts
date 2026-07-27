import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
    alias(libs.plugins.kotlin.cocoapods)
}

group = "com.kerobit"
version = "0.1.0-SNAPSHOT"

base {
    archivesName.set("kquic")
}

val cocoaPodName = "KerobitKQuic"
val appleFrameworkName = "KerobitKQuic"

kotlin {
    applyDefaultHierarchyTemplate()

    jvm()
    android {
        namespace = "com.kerobit.kquic"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.kquic.minSdk.get().toInt()

        withJava()
        withHostTestBuilder {}.configure {}
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    val iosTargets = listOf(iosX64(), iosArm64(), iosSimulatorArm64())
    val macosArm64 = macosArm64()
    linuxX64()
    mingwX64()

    val xcf = XCFramework(appleFrameworkName)

    cocoapods {
        summary = "KQuic - Kotlin Multiplatform QUIC transport"
        name = cocoaPodName
        homepage = "https://kerobit.com"
        version = version.toString()
        ios.deploymentTarget = "16.0"
        osx.deploymentTarget = "11.0"
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
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(group.toString(), "kquic", version.toString())
    pom {
        name = "KQuic"
        description = "KQuic - Kotlin Multiplatform QUIC peer-to-peer transport"
        inceptionYear = "2026"
        licenses {
            license { name = "MPL2.0" }
        }
        developers {
            developer {
                id = "kerobit"
                name = "Kerobit Team"
            }
        }
    }
}
