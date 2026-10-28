import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

// TMPlayer for Windows and Linux (Part B of the 2026-10-03 plan): a Compose Desktop front
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

// The version the installers carry, under the release numbering (docs/RELEASING.md, and
// ReleaseVersion.installerVersion in :core, which a desktop test holds this to): major.minor and
// patch*100 + stage, where a test version vX.Y.Z-beta.N is stage N, the release 99 and anything
// else (-dev, -ci.N, -test.N) 0. 1.28.2-beta.1 is 1.28.201 and 1.28.2 is 1.28.299, so Windows
// Installer, which compares only three fields, the third up to 65535, orders a test version
// between the release before it and its own release. jpackage wants a plain x.y.z.
val installerVersion: String = run {
    val name = desktopVersion.trim().removePrefix("v").substringBefore('+')
    val fields = name.substringBefore('-').split('.').map { it.toIntOrNull() }
    require(fields.size == 3 && fields.all { it != null }) { "desktopVersion $desktopVersion is not x.y.z or x.y.z-suffix" }
    val (major, minor, patch) = fields.map { it!! }
    val suffix = name.substringAfter('-', "")
    val stage = when {
        suffix.isEmpty() -> 99
        else -> Regex("""beta\.(\d+)""").matchEntire(suffix)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..98 } ?: 0
    }
    require(major <= 255 && minor <= 255 && patch * 100 + stage <= 65535) { "desktopVersion $desktopVersion does not fit an MSI version" }
    "$major.$minor.${patch * 100 + stage}"
}
val packagingDir = layout.projectDirectory.dir("packaging")

// The same local.properties the Android build reads, for the same Telegram API credentials.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Credentials and version reach the app as a generated object, the desktop's BuildConfig.
val generateBuildInfo = tasks.register("generateBuildInfo") {
    val out = layout.buildDirectory.dir("generated/buildinfo")
    val apiId = localProps.getProperty("TG_API_ID") ?: "0"
    val apiHash = localProps.getProperty("TG_API_HASH") ?: ""
    // Optional: without it the desktop hides online subtitles, as the Android build does.
    val openSubtitlesKey = localProps.getProperty("OPENSUBTITLES_API_KEY") ?: System.getenv("OPENSUBTITLES_API_KEY") ?: ""
    inputs.property("apiId", apiId)
    inputs.property("apiHash", apiHash)
    inputs.property("openSubtitlesKey", openSubtitlesKey)
    // Optional: without it the desktop does not offer MSone's subtitles, as on Android.
    val msoneKey = localProps.getProperty("MSONE_GATEWAY_KEY") ?: System.getenv("MSONE_GATEWAY_KEY") ?: ""
    inputs.property("msoneKey", msoneKey)
    // Optional: without it films get no posters, as on Android; shows still come from TVmaze.
    val tmdbKey = localProps.getProperty("TMDB_API_KEY") ?: System.getenv("TMDB_API_KEY") ?: ""
    inputs.property("tmdbKey", tmdbKey)
    // Optional: without it a detail page shows the provider's own score alone, as on Android.
    val omdbKey = localProps.getProperty("OMDB_API_KEY") ?: System.getenv("OMDB_API_KEY") ?: ""
    inputs.property("omdbKey", omdbKey)
    // Optional: without it the desktop has nowhere to send a crash report, and Settings and the
    // setup never offer the switch, as on Android. The desktop's own Sentry project first
    // (tmplayer-desktop), else the phone's; a blank value counts as none.
    val sentryDsn = listOf("SENTRY_DSN_DESKTOP", "SENTRY_DSN")
        .flatMap { listOf(localProps.getProperty(it), System.getenv(it)) }
        .firstOrNull { !it.isNullOrBlank() }
        .orEmpty()
    inputs.property("sentryDsn", sentryDsn)
    inputs.property("version", desktopVersion)
    inputs.property("installerVersion", installerVersion)
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile.resolve("com/tmplayer/desktop").apply { mkdirs() }
        dir.resolve("BuildInfo.kt").writeText(
            """
            |package com.tmplayer.desktop
            |
            |internal object BuildInfo {
            |    const val VERSION = "$desktopVersion"
            |    const val INSTALLER_VERSION = "$installerVersion"
            |    const val TG_API_ID = $apiId
            |    const val TG_API_HASH = "$apiHash"
            |    const val OPENSUBTITLES_API_KEY = "$openSubtitlesKey"
            |    const val MSONE_GATEWAY_KEY = "$msoneKey"
            |    const val TMDB_API_KEY = "$tmdbKey"
            |    const val OMDB_API_KEY = "$omdbKey"
            |    const val SENTRY_DSN = "$sentryDsn"
            |}
            |""".trimMargin(),
        )
    }
}
kotlin.sourceSets.main { kotlin.srcDir(generateBuildInfo) }

// The Android promo build's demo library (plain Kotlin over :core), for the desktop's promo shots.
kotlin.sourceSets.test { kotlin.srcDir("../app/src/promo/shared") }

val hostOs: String = System.getProperty("os.name").lowercase()
val hostArm: Boolean = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
val hostMac: Boolean = hostOs.contains("mac")
val mpvRuntime = when {
    hostOs.contains("win") -> libs.mediamp.runtime.windows.x64
    // Apple Silicon only: the DMG is built on an arm64 runner, and Intel Macs are not offered.
    hostMac -> libs.mediamp.runtime.macos.arm64
    else -> libs.mediamp.runtime.linux.x64
}

// Every package is xz, LZMA or cab compressed on the outside, which cannot squeeze a jar that is
// already deflated inside. So each external runtime jar is rewritten with its entries stored, and
// the outer compressor then sees the raw bytes: the Linux tarball drops from 176 MB to about 77 MB.
// The same pass also does two things to specific jars:
//  - tdl-coroutines carries TDLib for six OS and CPU pairs (about 330 MB unpacked). Each installer
//    only ever loads its own, so the other five are removed: about 85 MB off every package.
//  - On a Linux host, the ELF .so entries (libtdjsonjava, libmpv and FFmpeg) are stripped of
//    symbols that nothing reads at runtime. Windows DLLs are left alone.
//  - On a Mac host, the kept libtdjsonjava.dylib has its references to the build machine's
//    Homebrew (libjvm, libssl, libcrypto by absolute /opt/homebrew paths, upstream
//    g000sha256/tdl-coroutines#163) rewritten to @rpath/<name> and is signed again ad hoc. The
//    app carries the OpenSSL they then resolve to (macOpenSsl below, loaded by MacOpenSsl).
// A signed jar (META-INF/*.SF) is only ever stored, never changed, so its signature stays valid.
// The transform runs per host OS, since the kept TDLib and the strip step both depend on it, and
// the installers are built on the matching host. The self-test proves the TDLib that remains loads.
val tdlibKeep = when {
    hostOs.contains("win") -> "windows/x64/"
    hostMac -> if (hostArm) "macos/arm64/" else "macos/x64/"
    else -> if (hostArm) "linux/arm64/" else "linux/x64/"
}
// The name is the transform's cache identity: change it whenever the transform's output changes.
val jarsStored: Attribute<Boolean> = Attribute.of("com.tmplayer.jarsStoredV3", Boolean::class.javaObjectType)

abstract class StoreRuntimeJars : TransformAction<StoreRuntimeJars.Params> {
    interface Params : TransformParameters {
        @get:Input
        val keep: Property<String>

        @get:Input
        val stripElf: Property<Boolean>

        @get:Input
        val patchMachO: Property<Boolean>
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
        val patchMac = parameters.patchMachO.get()
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
                        if (tdlib && patchMac && entry.name.endsWith(".dylib")) bytes = portableMachO(bytes, tmp)
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

    /**
     * Points every reference to a library outside the system (/usr/lib, /System) at @rpath/<name>
     * and signs the result ad hoc again, as Apple Silicon requires. Fails the build rather than
     * ship a library that only loads on a Mac with the build machine's Homebrew.
     */
    private fun portableMachO(bytes: ByteArray, dir: File): ByteArray {
        val f = File(dir, "lib.dylib")
        f.writeBytes(bytes)
        val foreign = machORefs(f, dir).filter { it.startsWith("/") && !it.startsWith("/usr/lib/") && !it.startsWith("/System/") }
        if (foreign.isEmpty()) return bytes
        run(dir, "install_name_tool", *foreign.flatMap { listOf("-change", it, "@rpath/" + it.substringAfterLast('/')) }.toTypedArray(), f.path)
        run(dir, "codesign", "--force", "--sign", "-", f.path)
        val left = machORefs(f, dir).filter { it in foreign }
        check(left.isEmpty()) { "still linked by absolute path: $left" }
        return f.readBytes()
    }

    /** The libraries a Mach-O file loads, by `otool -L` (its own install name included). */
    private fun machORefs(f: File, dir: File): List<String> =
        run(dir, "otool", "-L", f.path).lines().drop(1).map { it.trim().substringBefore(" (") }.filter { it.isNotEmpty() }

    private fun run(dir: File, vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().decodeToString()
        check(p.waitFor() == 0) { "${cmd.first()} failed: $out" }
        return out
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
        parameters.stripElf.set(!hostOs.contains("win") && !hostMac)
        parameters.patchMachO.set(hostMac)
    }
}
configurations.named("runtimeClasspath") {
    attributes.attribute(jarsStored, true)
    // The extended icon set (38 MB) only arrives through mediamp, which never touches it. The app
    // draws its own icons, TmIcons.
    exclude(group = "org.jetbrains.compose.material", module = "material-icons-extended")
    exclude(group = "org.jetbrains.compose.material", module = "material-icons-extended-desktop")
    exclude(group = "androidx.compose.material", module = "material-icons-extended")
    exclude(group = "androidx.compose.material", module = "material-icons-extended-desktop")
    // mediamp's published desktop modules also list Compose's UI test kit as a runtime dependency,
    // which drags JUnit, Hamcrest and kotlinx-coroutines-test into the package. None of its classes
    // reference them, and coroutines-test registers a ServiceLoader handler that, once ProGuard has
    // removed its class, kills the app on the first uncaught coroutine error. The tests resolve
    // their own copies through testRuntimeClasspath.
    exclude(group = "org.jetbrains.compose.ui", module = "ui-test")
    exclude(group = "org.jetbrains.compose.ui", module = "ui-test-desktop")
    exclude(group = "org.jetbrains.compose.ui", module = "ui-test-junit4")
    exclude(group = "org.jetbrains.compose.ui", module = "ui-test-junit4-desktop")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-test")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-test-jvm")
    exclude(group = "junit", module = "junit")
    exclude(group = "org.hamcrest")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":ui"))
    implementation(compose.desktop.currentOs)
    implementation(libs.cmp.material3)
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
    // The Cast protocol's messages are JSON (CP73). Already on the runtime classpath through
    // :core's jvm target; named here so the cast client compiles against it.
    implementation(libs.json)
    // Crash reports, only once the viewer turns them on (DesktopCrashReports): the plain JVM SDK,
    // with no integration of its own beyond the uncaught exception handler.
    implementation(libs.sentry)
    runtimeOnly(libs.slf4j.nop)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // ComposeUiTest: the browse keyboard and the player's mouse driven off screen.
    testImplementation(libs.cmp.ui.test.junit4)
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

// ProGuard writes every jar it produces deflated again, which would undo StoreRuntimeJars for the
// release image. So each output jar is rewritten with its entries stored, as the transform does.
tasks.matching { it.name == "proguardReleaseJars" }.configureEach {
    doLast {
        outputs.files.asFileTree.matching { include("**/*.jar") }.forEach { jar ->
            val tmp = File(jar.path + ".stored")
            ZipFile(jar).use { zin ->
                ZipOutputStream(tmp.outputStream().buffered()).use { zout ->
                    for (entry in zin.entries()) {
                        val bytes = if (entry.isDirectory) ByteArray(0) else zin.getInputStream(entry).use { it.readBytes() }
                        zout.putNextEntry(
                            ZipEntry(entry.name).apply {
                                time = entry.time
                                method = ZipEntry.STORED
                                size = bytes.size.toLong()
                                compressedSize = bytes.size.toLong()
                                crc = CRC32().apply { update(bytes) }.value
                            },
                        )
                        zout.write(bytes)
                        zout.closeEntry()
                    }
                }
            }
            Files.move(tmp.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

// Every class of the app's own code in ProGuard's jars must load and link with the verifier on
// (ClassLinkCheck). ProGuard computes each method's stack map frames again, and in 1.28.1 it wrote
// frames the JVM refused for SeriesKt (HX1): the desktop app crashed on the first show it drew.
// Run on the jars alone, as the app's classpath, before any release image or installer is made;
// the packaged app's --self-test checks the same again on its bundled runtime.
val verifyReleaseClasses = tasks.register<JavaExec>("verifyReleaseClasses") {
    group = "verification"
    description = "Links every com.tmplayer class of the ProGuard output with the bytecode verifier on."
    dependsOn("proguardReleaseJars")
    mainClass.set("com.tmplayer.desktop.ClassLinkCheck")
    classpath(provider { tasks.getByName("proguardReleaseJars").outputs.files.asFileTree.matching { include("**/*.jar") } })
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    jvmArgs("-Djava.awt.headless=true")
    val marker = layout.buildDirectory.file("verify-release-classes/passed")
    outputs.file(marker)
    doLast { marker.get().asFile.writeText("passed\n") }
}
tasks.matching { it.name == "createReleaseDistributable" || it.name.startsWith("packageRelease") }.configureEach {
    dependsOn(verifyReleaseClasses)
}

// LICENSE, the OpenSSL permission, the third-party notices and the fonts' OFL travel inside the
// app image, so the MSI, the tarball, the AppImage and the AUR package all carry them without each
// packaging step copying them again. Compose copies appResourcesRootDir/common into the image's app/resources
// (lib/app/resources on Linux) and names that folder in the compose.application.resources.dir
// system property, which is where the About screen reads them from.
val legalResources = layout.buildDirectory.dir("legal-resources")
val syncLegalResources = tasks.register<Sync>("syncLegalResources") {
    from(
        rootProject.file("LICENSE"),
        rootProject.file("LICENSE-OPENSSL-EXCEPTION.md"),
        rootProject.file("THIRD_PARTY_NOTICES.md"),
    )
    // The OFL text and copyright lines of the Noto fonts :ui bundles for scripts the system lacks.
    from(project(":ui").file("src/jvmMain/resources/fonts/OFL.txt")) { rename { "OFL-Noto.txt" } }
    into(legalResources.map { it.dir("common") })
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(syncLegalResources) }

// The OpenSSL 3 that macOS TDLib links (see StoreRuntimeJars and MacOpenSsl): Homebrew's openssl@3
// on the Mac that builds the DMG (or -PmacOpenSsl=<its prefix>), copied into the macos-arm64
// resources, which Compose merges into the app's resources on a Mac only. Each library gets an
// @rpath install name, libssl finds libcrypto by that name, nothing points into Homebrew any more,
// and both are signed again ad hoc.
val macOpenSsl = tasks.register("macOpenSsl") {
    onlyIf { hostMac }
    val out = legalResources.map { it.dir("macos-arm64") }
    val prefix = providers.gradleProperty("macOpenSsl")
    outputs.dir(out)
    doLast {
        fun run(vararg cmd: String): String {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val text = p.inputStream.readBytes().decodeToString()
            check(p.waitFor() == 0) { "${cmd.joinToString(" ")} failed: $text" }
            return text
        }
        val lib = File(prefix.orNull ?: run("brew", "--prefix", "openssl@3").trim(), "lib")
        val dir = out.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        val names = listOf("libcrypto.3.dylib", "libssl.3.dylib")
        names.forEach { name ->
            val target = File(dir, name)
            // The real file, not Homebrew's symlink to it, and writable for install_name_tool.
            File(lib, name).canonicalFile.copyTo(target, overwrite = true)
            target.setWritable(true)
            val refs = run("otool", "-L", target.path).lines().drop(2).map { it.trim().substringBefore(" (") }.filter { it.isNotEmpty() }
            val changes = refs.filter { it.startsWith("/") && !it.startsWith("/usr/lib/") && !it.startsWith("/System/") }
                .flatMap { listOf("-change", it, "@rpath/" + it.substringAfterLast('/')) }
            run("install_name_tool", "-id", "@rpath/$name", *changes.toTypedArray(), target.path)
            run("codesign", "--force", "--sign", "-", target.path)
            val left = run("otool", "-L", target.path).lines().drop(1).map { it.trim() }
                .filter { it.startsWith("/") && !it.startsWith("/usr/lib/") && !it.startsWith("/System/") }
            check(left.isEmpty()) { "$name still links $left" }
        }
    }
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(macOpenSsl) }

compose.desktop {
    application {
        mainClass = "com.tmplayer.desktop.MainKt"
        // apple.awt.application.appearance lets the macOS title bar follow the system's light or dark
        // look; other systems ignore it.
        jvmArgs += listOf("-Dsun.java2d.uiScale.enabled=true", "-Dapple.awt.application.appearance=system")
        // The release image (createReleaseDistributable, packageReleaseMsi) runs ProGuard over the
        // classpath to drop code nothing reaches: Compose, Material, coroutines and the big
        // libraries are mostly unused. Names are kept and optimisation is off, because the risk is in
        // reflection and JNI, not in size. desktop/proguard-rules.pro says what must survive.
        buildTypes.release.proguard {
            isEnabled.set(true)
            obfuscate.set(false)
            optimize.set(false)
            configurationFiles.from(project.file("proguard-rules.pro"))
        }
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi, TargetFormat.Exe)
            packageName = "TMPlayer"
            // jpackage wants a plain x.y.z. The deb and rpm (not published) take the release's own;
            // the MSI and macOS's CFBundleVersion take the installer version, below.
            packageVersion = desktopVersion.removePrefix("v").substringBefore('-').substringBefore('+')
            vendor = "TMPlayer"
            description = "Unofficial Telegram media player"
            copyright = "GPL-3.0-or-later"
            licenseFile.set(rootProject.file("LICENSE"))
            appResourcesRootDir.set(legalResources)
            // What the runtime reaches beyond the modules Compose already adds. Evidence is jdeps
            // over the release image's jars plus suggestRuntimeModules: jdk.security.auth is dbus-java's
            // UnixSystem credentials, jdk.net its unix socket options, jdk.unsupported the protobuf
            // Unsafe in datastore. Nothing references java.sql or java.management, and java.naming
            // still arrives on its own because jdk.security.auth requires it.
            modules("jdk.unsupported", "jdk.security.auth", "jdk.net")
            linux {
                packageName = "tmplayer"
                iconFile.set(packagingDir.file("icons/tmplayer.png"))
                menuGroup = "AudioVideo;Video;Player;"
                appCategory = "video"
                debMaintainer = "TMPlayer <noreply@github.com>"
                rpmLicenseType = "GPLv3+"
                shortcut = true
            }
            // The DMG (a preview, Apple Silicon, from 1.26.0) is not made by jpackage: the release
            // job signs the app image ad hoc and packs it with hdiutil (.github/actions/desktop-macos),
            // so this only shapes the app bundle. macOS 26 is the floor because the TDLib in
            // tdl-coroutines 15.0.0 is built for it (the mpv runtime's libraries ask for 15), and the
            // floor in Info.plist makes an older Mac say so instead of failing at sign in.
            macOS {
                bundleID = "io.github.dracu-lah.tmplayer"
                iconFile.set(packagingDir.file("icons/tmplayer.icns"))
                appCategory = "public.app-category.video"
                minimumSystemVersion = "26.0"
                dockName = "TMPlayer"
                // CFBundleShortVersionString stays the release's x.y.z (1.28.2 for its test
                // versions too); CFBundleVersion, the build number, orders them (1.28.201, 1.28.299).
                packageBuildVersion = installerVersion
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
                // %LOCALAPPDATA%\TMPlayer App, not %LOCALAPPDATA%\TMPlayer: that one is where the
                // app keeps its sign in, settings and cache, and jpackage's MSI deletes its whole
                // install folder on uninstall, which every upgrade runs first (issue #7). One level:
                // jpackage cannot make a per user MSI into a nested folder such as Programs\TMPlayer.
                installationPath = "TMPlayer App"
                dirChooser = false
                upgradeUuid = "6f1d3c0e-2b8a-4b7e-9d2c-7a1e5f4c3b21"
                // The ProductVersion that orders a test version between the release before it and
                // its own release, so every step (stable to test, test to test, test to stable) is
                // an upgrade. msi-update.ps1 and the CI self-test check for this number.
                msiPackageVersion = installerVersion
                exePackageVersion = installerVersion
            }
        }
    }
}

// Windows MSI cab compression. The jars inside the app image are stored uncompressed, so the
// cabinet inside the MSI is the only place the bytes get squeezed. jpackage's own main.wxs has no
// CompressionLevel on its Media element, which leaves WiX at the mszip default, and jpackage
// offers no hook to change it. Its --resource-dir does let a main.wxs of our own replace the
// built-in one, and Compose passes extra arguments through freeArgs after its own, so desktop/packaging/windows/main.wxs is the stock file for the JDK we build
// with plus CompressionLevel="high" (LZX). Only the MSI tasks get it, and only on a Windows
// host, because that is the only place the WiX step runs.
if (org.gradle.internal.os.OperatingSystem.current().isWindows) {
    tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>()
        .matching { it.targetFormat == TargetFormat.Msi }
        .configureEach {
            freeArgs.addAll(
                "--resource-dir",
                layout.projectDirectory.dir("packaging/windows").asFile.absolutePath,
            )
        }
}
