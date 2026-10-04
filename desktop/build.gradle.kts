import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Properties
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

// TMPlayer for Windows, Linux and macOS (Part B of the 2026-10-03 plan): a Compose Desktop front
// end over :core, playing through libmpv (mediamp).
//
// Release packaging: the MSI comes straight from the Compose plugin (jpackage). The Linux AppImage
// and tarball are built by desktop/packaging/linux/package.sh over createDistributable's app
// image, because jpackage's own .desktop file cannot carry StartupWMClass or the full category
// list. The plugin's deb and rpm still work for local builds, but are not released.

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin { jvmToolchain(21) }

// The release passes the tag's x.y.z (-PdesktopVersion=1.18.0). Anything else is a development
// build, numbered below every release so that its MSI never blocks the upgrade to a real one.
val desktopVersion: String = providers.gradleProperty("desktopVersion").getOrElse("1.0.0-dev")
val packagingDir = layout.projectDirectory.dir("packaging")

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

val hostOs: String = System.getProperty("os.name").lowercase()
val hostArm: Boolean = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
val mpvRuntime = when {
    hostOs.contains("win") -> libs.mediamp.runtime.windows.x64
    hostOs.contains("mac") -> if (hostArm) libs.mediamp.runtime.macos.arm64 else libs.mediamp.runtime.macos.x64
    else -> libs.mediamp.runtime.linux.x64
}

// Every package is xz, LZMA or cab compressed on the outside, which cannot squeeze a jar that is
// already deflated inside. So each external runtime jar is rewritten with its entries stored, and
// the outer compressor then sees the raw bytes: the Linux tarball drops from 176 MB to about 77 MB.
// The same pass also does two things to specific jars:
//  - tdl-coroutines carries TDLib for six OS and CPU pairs (about 330 MB unpacked). Each installer
//    only ever loads its own, so the other five are removed: about 85 MB off every package.
//  - On a Linux host, the ELF .so entries (libtdjsonjava, libmpv and FFmpeg) are stripped of
//    symbols that nothing reads at runtime. Windows DLLs and macOS dylibs are left alone.
// A signed jar (META-INF/*.SF) is only ever stored, never changed, so its signature stays valid.
// The transform runs per host OS, since the kept TDLib and the strip step both depend on it, and
// the installers are built on the matching host. The self-test proves the TDLib that remains loads.
val tdlibKeep = when {
    hostOs.contains("win") -> "windows/x64/"
    hostOs.contains("mac") -> if (hostArm) "macos/arm64/" else "macos/x64/"
    else -> if (hostArm) "linux/arm64/" else "linux/x64/"
}
// The name is the transform's cache identity: change it whenever the transform's output changes.
val jarsStored: Attribute<Boolean> = Attribute.of("com.tmplayer.jarsStoredV2", Boolean::class.javaObjectType)

abstract class StoreRuntimeJars : TransformAction<StoreRuntimeJars.Params> {
    interface Params : TransformParameters {
        @get:Input
        val keep: Property<String>

        @get:Input
        val stripElf: Property<Boolean>
    }

    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val input: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val jar = input.get().asFile
        if (!jar.name.endsWith(".jar")) {
            outputs.file(input)
            return
        }
        val tdlib = jar.name.startsWith("tdl-coroutines-jvm")
        val keep = parameters.keep.get()
        val native = Regex("^(linux|macos|windows)/")
        val strip = parameters.stripElf.get()
        val tmp = Files.createTempDirectory("tmplayer-jar").toFile()
        try {
            ZipFile(jar).use { zin ->
                val signed = zin.entries().asSequence().any {
                    it.name.startsWith("META-INF/") && it.name.endsWith(".SF")
                }
                ZipOutputStream(outputs.file(jar.name).outputStream().buffered()).use { zout ->
                    zout.setLevel(Deflater.NO_COMPRESSION)
                    // The same library appears under several names in a jar, so strip each once.
                    val stripped = HashMap<Long, ByteArray>()
                    for (entry in zin.entries()) {
                        if (tdlib && native.containsMatchIn(entry.name) && !entry.isDirectory && !entry.name.startsWith(keep)) continue
                        var bytes = if (entry.isDirectory) ByteArray(0) else zin.getInputStream(entry).use { it.readBytes() }
                        if (strip && !signed && !entry.isDirectory && bytes.size > 4 &&
                            bytes[0] == 0x7f.toByte() && bytes[1] == 'E'.code.toByte() &&
                            bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()
                        ) {
                            val key = entry.crc * 31 + bytes.size
                            bytes = stripped.getOrPut(key) { stripElf(bytes, tmp) }
                        }
                        val crc = CRC32().apply { update(bytes) }
                        zout.putNextEntry(
                            ZipEntry(entry.name).apply {
                                time = entry.time
                                if (!entry.isDirectory) {
                                    method = ZipEntry.STORED
                                    size = bytes.size.toLong()
                                    compressedSize = bytes.size.toLong()
                                    this.crc = crc.value
                                } else {
                                    method = ZipEntry.STORED
                                    size = 0
                                    compressedSize = 0
                                    this.crc = 0
                                }
                            },
                        )
                        zout.write(bytes)
                        zout.closeEntry()
                    }
                }
            }
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** Runs `strip --strip-unneeded` over a copy, and keeps the original if strip is missing or fails. */
    private fun stripElf(bytes: ByteArray, dir: File): ByteArray {
        val f = File(dir, "lib.so")
        f.writeBytes(bytes)
        return try {
            val p = ProcessBuilder("strip", "--strip-unneeded", f.path).redirectErrorStream(true).start()
            p.inputStream.readBytes()
            if (p.waitFor() == 0 && f.length() in 1 until bytes.size.toLong()) f.readBytes() else bytes
        } catch (e: IOException) {
            bytes
        }
    }
}

dependencies {
    attributesSchema { attribute(jarsStored) }
    artifactTypes.getByName("jar") { attributes.attribute(jarsStored, false) }
    registerTransform(StoreRuntimeJars::class) {
        from.attribute(jarsStored, false).attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "jar")
        to.attribute(jarsStored, true).attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "jar")
        parameters.keep.set(tdlibKeep)
        parameters.stripElf.set(!hostOs.contains("win") && !hostOs.contains("mac"))
    }
}
configurations.named("runtimeClasspath") {
    attributes.attribute(jarsStored, true)
    // The extended icon set (38 MB) only arrives through mediamp, which never touches it. The app
    // draws from material-icons-core and TmIcons.
    exclude(group = "org.jetbrains.compose.material", module = "material-icons-extended")
    exclude(group = "org.jetbrains.compose.material", module = "material-icons-extended-desktop")
    exclude(group = "androidx.compose.material", module = "material-icons-extended")
    exclude(group = "androidx.compose.material", module = "material-icons-extended-desktop")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":ui"))
    implementation(compose.desktop.currentOs)
    implementation(libs.cmp.material3)
    implementation(libs.cmp.material.icons)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.appdirs)
    implementation(libs.zxing.core)
    implementation(libs.mediamp.api)
    implementation(libs.mediamp.mpv)
    // libmpv and its FFmpeg for the OS being built on, and only that one: installers are made on
    // the matching host anyway, and each runtime is 30 to 75 MB the other two would never load.
    runtimeOnly(mpvRuntime)
    implementation(libs.dbus.java.core)
    implementation(libs.dbus.java.transport.native.unixsocket)
    implementation(libs.jna)
    implementation(libs.jna.platform)
    implementation(libs.filekit.dialogs.compose)
    runtimeOnly(libs.slf4j.nop)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // ComposeUiTest: the browse keyboard and the player's mouse driven off screen.
    testImplementation(compose.desktop.uiTestJUnit4)
}

// The player on its own, over a file on disk, for working on it without a Telegram account:
// ./gradlew :desktop:runPlayerDev --args="--file /path/to/clip.mkv"
tasks.register<JavaExec>("runPlayerDev") {
    group = "application"
    description = "Plays a local video through the desktop PlayerScreen (pass --args=\"--file <path>\")."
    mainClass.set("com.tmplayer.desktop.player.DevPlayerMainKt")
    classpath = project.extensions.getByType<JavaPluginExtension>().sourceSets["main"].runtimeClasspath
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    jvmArgs("-Dsun.java2d.uiScale.enabled=true")
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
            copyright = "GPL-3.0-or-later"
            licenseFile.set(rootProject.file("LICENSE"))
            modules("java.naming", "java.sql", "jdk.unsupported", "java.management")
            linux {
                packageName = "tmplayer"
                iconFile.set(packagingDir.file("icons/tmplayer.png"))
                menuGroup = "AudioVideo;Video;Player;"
                appCategory = "video"
                debMaintainer = "TMPlayer <noreply@github.com>"
                rpmLicenseType = "GPLv3+"
                shortcut = true
            }
            windows {
                iconFile.set(packagingDir.file("icons/tmplayer.ico"))
                // Start menu folder, desktop shortcut, installed under %LOCALAPPDATA% without an
                // administrator prompt. The upgrade code never changes: it is what lets a newer
                // MSI replace an older one instead of installing beside it.
                menu = true
                menuGroup = "TMPlayer"
                shortcut = true
                perUserInstall = true
                dirChooser = false
                upgradeUuid = "6f1d3c0e-2b8a-4b7e-9d2c-7a1e5f4c3b21"
            }
            macOS {
                bundleID = "com.tmplayer.desktop"
                iconFile.set(packagingDir.file("icons/tmplayer.icns"))
            }
        }
    }
}
