import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties

// TMPlayer for Windows, Linux and macOS (Part B of the 2026-10-03 plan): a Compose Desktop front
// end over :core, playing through libmpv (mediamp). Packaging for releases goes through Conveyor
// later; the Compose plugin's own installers stay working for local builds.

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin { jvmToolchain(21) }

val desktopVersion: String = providers.gradleProperty("desktopVersion").getOrElse("2.0.0-alpha.1")

// The same local.properties the Android build reads, for the same Telegram API credentials.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Credentials and version reach the app as a generated object, the desktop's BuildConfig.
val generateBuildInfo by tasks.registering {
    val out = layout.buildDirectory.dir("generated/buildinfo")
    val apiId = localProps.getProperty("TG_API_ID") ?: "0"
    val apiHash = localProps.getProperty("TG_API_HASH") ?: ""
    inputs.property("apiId", apiId)
    inputs.property("apiHash", apiHash)
    inputs.property("version", desktopVersion)
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile.resolve("com/tmplayer/desktop").apply { mkdirs() }
        dir.resolve("BuildInfo.kt").writeText(
            """
            |package com.tmplayer.desktop
            |
            |internal object BuildInfo {
            |    const val VERSION = "$desktopVersion"
            |    const val TG_API_ID = $apiId
            |    const val TG_API_HASH = "$apiHash"
            |}
            |""".trimMargin(),
        )
    }
}
kotlin.sourceSets.main { kotlin.srcDir(generateBuildInfo) }

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(libs.cmp.material3)
    implementation(libs.cmp.material.icons)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.appdirs)
    implementation(libs.zxing.core)
    implementation(libs.mediamp.api)
    implementation(libs.mediamp.mpv)
    runtimeOnly(libs.mediamp.runtime.linux.x64)
    runtimeOnly(libs.mediamp.runtime.windows.x64)
    runtimeOnly(libs.mediamp.runtime.macos.arm64)
    runtimeOnly(libs.mediamp.runtime.macos.x64)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

compose.desktop {
    application {
        mainClass = "com.tmplayer.desktop.MainKt"
        jvmArgs += listOf("-Dsun.java2d.uiScale.enabled=true")
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg)
            packageName = "TMPlayer"
            // jpackage wants a plain x.y.z.
            packageVersion = desktopVersion.substringBefore('-')
            vendor = "TMPlayer"
            description = "Unofficial Telegram media player"
            licenseFile.set(rootProject.file("LICENSE"))
            modules("java.naming", "java.sql", "jdk.unsupported", "java.management")
            linux { menuGroup = "AudioVideo" }
            windows {
                menu = true
                perUserInstall = true
                upgradeUuid = "6f1d3c0e-2b8a-4b7e-9d2c-7a1e5f4c3b21"
            }
            macOS { bundleID = "com.tmplayer.desktop" }
        }
    }
}
