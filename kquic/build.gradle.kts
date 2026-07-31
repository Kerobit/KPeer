import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

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
    val iosX64 = iosX64()
    val iosArm64 = iosArm64()
    val iosSimulatorArm64 = iosSimulatorArm64()
    val iosTargets = listOf(iosX64, iosArm64, iosSimulatorArm64)
    val macosArm64 = macosArm64()
    val xcf = XCFramework(appleFrameworkName)

    data class AppleShimTarget(
        val target: KotlinNativeTarget,
        val sdk: String,
        val triple: String
    )
    val swiftcPath = providers.exec {
        commandLine("xcrun", "--find", "swiftc")
    }.standardOutput.asText.get().trim()
    val swiftLibraryRoot = file(swiftcPath).parentFile.parentFile.resolve("lib/swift")

    listOf(
        AppleShimTarget(iosX64, "iphonesimulator", "x86_64-apple-ios16.0-simulator"),
        AppleShimTarget(iosArm64, "iphoneos", "arm64-apple-ios16.0"),
        AppleShimTarget(iosSimulatorArm64, "iphonesimulator", "arm64-apple-ios16.0-simulator"),
        AppleShimTarget(macosArm64, "macosx", "arm64-apple-macos11.0")
    ).forEach { shim ->
        val targetName = shim.target.name
        val sdk = shim.sdk
        val triple = shim.triple
        val sourcePath = file("src/appleMain/swift/KQuicCryptoKitShim.swift").absolutePath
        val outputDirectory = layout.buildDirectory.dir("cryptokit/$targetName")
            .get().asFile.apply(File::mkdirs)
        val outputLibrary = outputDirectory.resolve("libKQuicCryptoKitShim.a")
        val compileShim = tasks.register<Exec>(
            "compile${targetName.replaceFirstChar(Char::uppercase)}CryptoKitShim"
        ) {
            inputs.file(sourcePath)
            outputs.file(outputLibrary)
            commandLine(
                "xcrun", "--sdk", sdk, "swiftc",
                "-parse-as-library", "-O", "-emit-library", "-static",
                "-module-name", "KQuicCryptoKitShim",
                "-target", triple, sourcePath, "-o", outputLibrary.absolutePath
            )
        }
        val interop = shim.target.compilations.getByName("main").cinterops.create("cryptoKitShim") {
            defFile(project.file("src/nativeInterop/cinterop/cryptoKitShim.def"))
            compilerOpts("-I${project.file("src/nativeInterop/cinterop").absolutePath}")
            extraOpts("-libraryPath", outputDirectory.absolutePath)
        }
        tasks.named(interop.interopProcessingTaskName).configure {
            dependsOn(compileShim)
        }
        shim.target.binaries.all {
            linkerOpts("-L${swiftLibraryRoot.resolve(sdk).absolutePath}")
        }
    }

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
            linkerOpts("-framework", "CryptoKit")
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

val kquicProductionLocLimit = 7_400
val kquicProductionSourceTrees = listOf("commonMain", "androidMain", "appleMain").map { sourceSet ->
    fileTree("src/$sourceSet") { include("**/*.kt") }
}

fun codeLines(file: File): Int {
    var insideBlockComment = false
    return file.readLines().count { sourceLine ->
        var remainder = sourceLine
        var containsCode = false
        while (remainder.isNotEmpty() && !containsCode) {
            val trimmed = remainder.trimStart()
            if (insideBlockComment) {
                val end = trimmed.indexOf("*/")
                if (end < 0) {
                    remainder = ""
                } else {
                    insideBlockComment = false
                    remainder = trimmed.substring(end + 2)
                }
            } else if (trimmed.isEmpty() || trimmed.startsWith("//")) {
                remainder = ""
            } else if (trimmed.startsWith("/*")) {
                insideBlockComment = true
                remainder = trimmed.substring(2)
            } else {
                containsCode = true
            }
        }
        containsCode
    }
}

val checkKQuicProductionLoc by tasks.registering {
    group = "verification"
    description = "Checks non-blank, non-comment Kotlin production lines against the KQuic budget."
    inputs.files(kquicProductionSourceTrees)
    doLast {
        val sourceFiles = kquicProductionSourceTrees.flatMap { it.files }.sortedBy(File::getPath)
        val total = sourceFiles.sumOf(::codeLines)
        logger.lifecycle("KQuic production LOC: $total / $kquicProductionLocLimit")
        check(total <= kquicProductionLocLimit) {
            "KQuic production LOC is $total, exceeding the $kquicProductionLocLimit line budget"
        }
    }
}

tasks.named("check") {
    dependsOn(checkKQuicProductionLoc)
}
