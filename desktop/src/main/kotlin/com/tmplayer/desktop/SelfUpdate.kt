package com.tmplayer.desktop

import com.tmplayer.desktop.os.OsInfo
import com.tmplayer.platform.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * How this copy of TMPlayer got onto the computer, which decides how it can update itself.
 *
 * [canSelfUpdate] is false where something else owns the update (Flathub and the software centre
 * for the Flatpak, an AUR helper for the Arch package) or where there is no package to install over
 * (the plain tarball, a development run): those keep the "open the release page" notice.
 */
enum class InstallKind(val canSelfUpdate: Boolean, val label: String) {
    WindowsMsi(true, "Windows installer"),
    WindowsPortable(true, "portable zip"),
    AppImage(true, "AppImage"),
    Deb(true, "deb package"),
    Rpm(true, "rpm package"),
    Flatpak(false, "Flatpak"),
    Manual(false, "manual install"),
}

/** What the notice shows while an update is being fetched and put in place. */
sealed interface UpdateProgress {
    data object Idle : UpdateProgress
    data class Downloading(val fraction: Float?) : UpdateProgress
    data object Verifying : UpdateProgress

    /** deb and rpm: the system's password prompt is up. */
    data object Installing : UpdateProgress

    /** In place (or staged to be, on Windows): a restart finishes it. */
    data class Ready(val version: String) : UpdateProgress
    data class Failed(val message: String) : UpdateProgress
}

/**
 * Updates the desktop app in place from a GitHub release, for the installs that can
 * ([InstallKind.canSelfUpdate]):
 *
 * - **MSI**: the new MSI is downloaded, then, once the app has quit, `msiexec /passive` installs it
 *   over this one (it is per user and keeps one upgrade code, so no administrator prompt) and the
 *   app starts again.
 * - **Portable zip**: unpacked beside the current folder; once the app has quit the folders swap
 *   and the new one starts.
 * - **AppImage**: the new file replaces the old one straight away (the running copy keeps its
 *   mount), and a restart runs it.
 * - **deb, rpm**: installed with `pkexec`, so the system asks for the password, then a restart.
 *
 * Every download is checked against the release's `SHA256SUMS` before anything is installed: the
 * packages are not code signed, so the checksum is what says the file is the one CI built.
 */
class SelfUpdate(
    val kind: InstallKind = detect(),
    private val workDir: File = DesktopPaths.updatesDir,
) {
    private val _progress = MutableStateFlow<UpdateProgress>(UpdateProgress.Idle)
    val progress: StateFlow<UpdateProgress> = _progress.asStateFlow()

    /** Set by the window: quits the way closing it does (window state saved, process ended). */
    @Volatile
    var quit: () -> Unit = {}

    /** Runs after the app has gone, on Windows: the install itself. */
    private var pendingHelper: List<String>? = null

    /** Whether [release] has a package this install can update itself from. */
    fun canUpdateTo(release: LatestRelease): Boolean =
        kind.canSelfUpdate && assetFor(kind, release.assetNames) != null && checksumsName(release.assetNames) != null

    /** Downloads, verifies and installs (or stages) [release]. Progress lands in [progress]. */
    suspend fun update(release: LatestRelease) = withContext(Dispatchers.IO) {
        if (_progress.value.let { it is UpdateProgress.Downloading || it is UpdateProgress.Verifying || it is UpdateProgress.Installing }) {
            return@withContext
        }
        val result = runCatching { run(release) }
        result.onFailure {
            Logger.w(TAG, "update failed: ${it.message}")
            _progress.value = UpdateProgress.Failed(it.message ?: "The update did not finish")
        }
    }

    /** "Restart now": hands over to the staged install (Windows) or simply relaunches. */
    fun restart() {
        val helper = pendingHelper ?: relaunchCommand()
        if (helper != null) {
            runCatching { startDetached(helper) }.onFailure { Logger.w(TAG, "could not start the restart helper: ${it.message}") }
        }
        quit()
    }

    fun reset() {
        if (_progress.value is UpdateProgress.Failed) _progress.value = UpdateProgress.Idle
    }

    private fun run(release: LatestRelease) {
        val assetName = assetFor(kind, release.assetNames) ?: error("This release has no ${kind.label}")
        val sumsName = checksumsName(release.assetNames) ?: error("This release has no checksum list")
        val assetUrl = release.assetUrls[assetName] ?: error("No download link for $assetName")
        val sumsUrl = release.assetUrls[sumsName] ?: error("No download link for $sumsName")

        workDir.mkdirs()
        workDir.listFiles()?.forEach { if (it.name != assetName) it.deleteRecursively() }
        _progress.value = UpdateProgress.Downloading(null)
        val sums = String(httpGet(sumsUrl).readBytes())
        val expected = expectedSha256(sums, assetName) ?: error("$assetName is not in the checksum list")
        val file = File(workDir, assetName)
        download(assetUrl, file)

        _progress.value = UpdateProgress.Verifying
        val actual = sha256(file)
        if (!actual.equals(expected, ignoreCase = true)) {
            file.delete()
            error("The download did not match its checksum. Nothing was installed")
        }

        when (kind) {
            InstallKind.WindowsMsi -> stageMsi(file)
            InstallKind.WindowsPortable -> stagePortable(file)
            InstallKind.AppImage -> replaceAppImage(file)
            InstallKind.Deb, InstallKind.Rpm -> installPackage(file)
            else -> error("This install updates elsewhere")
        }
        _progress.value = UpdateProgress.Ready(release.version)
    }

    private fun stageMsi(msi: File) {
        val exe = appLauncher() ?: error("Could not find TMPlayer.exe")
        val script = File(workDir, "update.ps1")
        script.writeText(
            """
            |${'$'}ErrorActionPreference = 'SilentlyContinue'
            |Wait-Process -Id ${OsInfo.pid} -Timeout 60
            |Start-Process msiexec.exe -ArgumentList '/i', '"${psQuote(msi.absolutePath)}"', '/passive', '/norestart' -Wait
            |Start-Process '${psQuote(exe.absolutePath)}'
            |""".trimMargin(),
        )
        pendingHelper = powershell(script)
    }

    private fun stagePortable(zip: File) {
        val exe = appLauncher() ?: error("Could not find TMPlayer.exe")
        val current = exe.parentFile ?: error("Could not find the TMPlayer folder")
        val parent = current.parentFile ?: error("Could not find the TMPlayer folder")
        if (!parent.canWrite()) error("${parent.absolutePath} is not writable")
        val staged = File(parent, current.name + ".new")
        staged.deleteRecursively()
        unzip(zip, staged)
        // The zip holds one TMPlayer folder.
        val fresh = staged.listFiles()?.singleOrNull { it.isDirectory } ?: error("The zip did not hold a TMPlayer folder")
        val old = File(parent, current.name + ".old")
        val script = File(workDir, "update.ps1")
        script.writeText(
            """
            |${'$'}ErrorActionPreference = 'Stop'
            |Wait-Process -Id ${OsInfo.pid} -Timeout 60 -ErrorAction SilentlyContinue
            |Start-Sleep -Milliseconds 500
            |if (Test-Path '${psQuote(old.absolutePath)}') { Remove-Item -Recurse -Force '${psQuote(old.absolutePath)}' }
            |Rename-Item '${psQuote(current.absolutePath)}' '${psQuote(old.name)}'
            |Move-Item '${psQuote(fresh.absolutePath)}' '${psQuote(current.absolutePath)}'
            |Remove-Item -Recurse -Force '${psQuote(staged.absolutePath)}' -ErrorAction SilentlyContinue
            |Remove-Item -Recurse -Force '${psQuote(old.absolutePath)}' -ErrorAction SilentlyContinue
            |Start-Process '${psQuote(File(current, exe.name).absolutePath)}'
            |""".trimMargin(),
        )
        pendingHelper = powershell(script)
    }

    private fun replaceAppImage(downloaded: File) {
        val target = File(System.getenv("APPIMAGE") ?: error("Could not find the AppImage"))
        val dir = target.absoluteFile.parentFile ?: error("Could not find the AppImage's folder")
        if (!dir.canWrite()) error("${dir.absolutePath} is not writable, so the AppImage cannot be replaced")
        val next = File(dir, ".${target.name}.new")
        downloaded.copyTo(next, overwrite = true)
        next.setExecutable(true, false)
        // A rename over the old file: the running copy keeps its mount of the old one.
        if (!next.renameTo(target)) {
            next.delete()
            error("Could not replace ${target.absolutePath}")
        }
        downloaded.delete()
    }

    private fun installPackage(pkg: File) {
        if (!OsInfo.onPath("pkexec")) error("pkexec is not installed. Install the package with your package manager")
        val command = when (kind) {
            InstallKind.Deb -> when {
                OsInfo.onPath("apt-get") -> listOf("apt-get", "install", "-y", "--allow-downgrades", pkg.absolutePath)
                else -> listOf("dpkg", "-i", pkg.absolutePath)
            }
            else -> when {
                OsInfo.onPath("dnf") -> listOf("dnf", "install", "-y", pkg.absolutePath)
                OsInfo.onPath("zypper") -> listOf("zypper", "--non-interactive", "install", "--allow-unsigned-rpm", pkg.absolutePath)
                else -> listOf("rpm", "-U", pkg.absolutePath)
            }
        }
        _progress.value = UpdateProgress.Installing
        val process = ProcessBuilder(listOf("pkexec") + command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(15, TimeUnit.MINUTES)) {
            process.destroy()
            error("The install took too long")
        }
        when (process.exitValue()) {
            0 -> pkg.delete()
            126, 127 -> error("The password prompt was closed, or no polkit agent is running. Nothing was installed")
            else -> {
                Logger.w(TAG, "package install failed: $output")
                error("The package manager stopped: ${output.lines().lastOrNull { it.isNotBlank() } ?: "exit ${process.exitValue()}"}")
            }
        }
    }

    /** Starts this app again once the current process is gone (Linux; Windows uses its script). */
    private fun relaunchCommand(): List<String>? {
        if (!OsInfo.isLinux) return null
        val target = System.getenv("APPIMAGE") ?: appLauncher()?.absolutePath ?: return null
        val waitThenRun = "while kill -0 ${OsInfo.pid} 2>/dev/null; do sleep 0.3; done; exec \"\$0\""
        return listOf("/bin/sh", "-c", waitThenRun, target)
    }

    private fun download(url: String, target: File) {
        val part = File(target.parentFile, target.name + ".part")
        val connection = open(url)
        try {
            val total = connection.contentLengthLong.takeIf { it > 0 }
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastShown = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total != null) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != lastShown) {
                                lastShown = percent
                                _progress.value = UpdateProgress.Downloading(done.toFloat() / total)
                            }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        target.delete()
        if (!part.renameTo(target)) error("Could not save the download")
    }

    private fun httpGet(url: String) = open(url).inputStream

    private fun open(url: String): HttpURLConnection {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "TMPlayer-desktop/${BuildInfo.VERSION}")
        if (connection.responseCode !in 200..299) error("GitHub answered ${connection.responseCode}")
        return connection
    }

    companion object {
        private const val TAG = "SelfUpdate"

        /** Dropped into the portable zip's folder by CI, so the app knows it is not the MSI. */
        const val PORTABLE_MARKER = "portable.txt"

        /** The jpackage launcher this app was started from, when it was. */
        fun appLauncher(): File? = System.getProperty("jpackage.app-path")?.let(::File)?.takeIf { it.isFile }

        fun detect(): InstallKind {
            val launcher = appLauncher()
            return detect(
                os = OsInfo.osTag,
                env = System.getenv(),
                launcher = launcher,
                portableMarker = launcher?.parentFile?.let { File(it, PORTABLE_MARKER).isFile } ?: false,
                flatpakInfo = File("/.flatpak-info").isFile,
                dpkgOwns = File("/var/lib/dpkg/info/tmplayer.list").isFile,
                rpmOwns = { rpmOwnsTmplayer() },
            )
        }

        internal fun detect(
            os: String,
            env: Map<String, String>,
            launcher: File?,
            portableMarker: Boolean,
            flatpakInfo: Boolean,
            dpkgOwns: Boolean,
            rpmOwns: () -> Boolean,
        ): InstallKind = when (os) {
            "windows" -> when {
                launcher == null -> InstallKind.Manual
                portableMarker -> InstallKind.WindowsPortable
                else -> InstallKind.WindowsMsi
            }
            "linux" -> when {
                flatpakInfo || env.containsKey("FLATPAK_ID") -> InstallKind.Flatpak
                env["APPIMAGE"].isNullOrBlank().not() -> InstallKind.AppImage
                launcher == null || !launcher.invariantSeparatorsPath.startsWith("/opt/tmplayer/") -> InstallKind.Manual
                dpkgOwns -> InstallKind.Deb
                rpmOwns() -> InstallKind.Rpm
                else -> InstallKind.Manual
            }
            else -> InstallKind.Manual
        }

        private fun rpmOwnsTmplayer(): Boolean = runCatching {
            if (!OsInfo.onPath("rpm")) return false
            val p = ProcessBuilder("rpm", "-q", "tmplayer").redirectErrorStream(true).start()
            p.inputStream.readBytes()
            p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
        }.getOrDefault(false)

        /** The release asset [kind] installs from, by the names CI gives them. */
        fun assetFor(kind: InstallKind, names: List<String>): String? {
            val suffix = when (kind) {
                InstallKind.WindowsMsi -> "-windows-x64.msi"
                InstallKind.WindowsPortable -> "-windows-x64-portable.zip"
                InstallKind.AppImage -> "-x86_64.appimage"
                InstallKind.Deb -> "_amd64.deb"
                InstallKind.Rpm -> ".x86_64.rpm"
                else -> return null
            }
            return names.firstOrNull { it.lowercase(Locale.ROOT).endsWith(suffix) }
        }

        fun checksumsName(names: List<String>): String? =
            names.firstOrNull { it.startsWith("SHA256SUMS") && it.endsWith(".txt") }

        /** The hash `sha256sum` wrote for [name] ("<hash>  <name>", or "<hash> *<name>" in binary mode). */
        fun expectedSha256(sums: String, name: String): String? = sums.lineSequence()
            .map { it.trim() }
            .mapNotNull { line ->
                val hash = line.substringBefore(' ')
                val file = line.substringAfter(' ').trim().removePrefix("*")
                if (file == name && hash.length == 64) hash else null
            }
            .firstOrNull()

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** A path inside a single quoted PowerShell string. */
        internal fun psQuote(path: String) = path.replace("'", "''")

        private fun powershell(script: File) = listOf(
            "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script.absolutePath,
        )

        private fun startDetached(command: List<String>) {
            ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }

        private fun unzip(zip: File, into: File) {
            val root = into.canonicalFile
            java.util.zip.ZipFile(zip).use { z ->
                for (entry in z.entries()) {
                    val out = File(root, entry.name).canonicalFile
                    // A zip entry may not climb out of the folder it is unpacked into.
                    if (!out.path.startsWith(root.path + File.separator) && out != root) error("Unsafe path in zip: ${entry.name}")
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        z.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                    }
                }
            }
        }
    }
}
