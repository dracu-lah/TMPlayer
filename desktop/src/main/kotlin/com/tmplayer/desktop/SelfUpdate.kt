package com.tmplayer.desktop

import com.tmplayer.data.Release
import com.tmplayer.data.UpdateWords
import com.tmplayer.desktop.os.OsInfo
import com.tmplayer.platform.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * How this copy of TMPlayer got onto the computer, which decides how it can update itself.
 *
 * [canSelfUpdate] is false where something else owns the update (an AUR helper for the Arch
 * package) or where there is no package to install over (the Flatpak bundle, the plain tarball, a
 * development run): those keep the "open the release page" notice.
 *
 * [movesTo] is set for the formats releases no longer carry (the portable zip, the deb, the rpm
 * and the Flatpak, dropped after 1.21.0): the download to take instead, once a release without
 * this one comes out. See [SelfUpdate.retiredLine].
 */
enum class InstallKind(val canSelfUpdate: Boolean, val label: String, val movesTo: String? = null) {
    WindowsMsi(true, "Windows installer"),
    WindowsPortable(true, "portable zip", movesTo = "Windows installer (the .msi)"),
    AppImage(true, "AppImage"),
    Deb(true, "deb package", movesTo = "AppImage"),
    Rpm(true, "rpm package", movesTo = "AppImage"),
    Flatpak(false, "Flatpak", movesTo = "AppImage"),
    Manual(false, "manual install"),
}

/** What the update popup shows while an update is being fetched and put in place. */
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
 * The portable zip, deb and rpm paths only run against a release that still carries those files
 * (1.21.0 and older). Later releases do not, and the popup says so ([retiredLine]) instead.
 *
 * Every download is checked against its SHA-256 before anything is installed, from the update feed
 * or, failing that, the release's `SHA256SUMS`: the packages are not code signed, so the checksum
 * is what says the file is the one CI built.
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

    /** Whether [release] has a package this install can update itself from, and a hash to check it by. */
    fun canUpdateTo(release: Release): Boolean {
        if (!kind.canSelfUpdate) return false
        val asset = assetFor(kind)?.let(release.assets::get) ?: return false
        return asset.sha256 != null || release.checksumsUrl != null
    }

    /** Downloads, verifies and installs (or stages) [release]. Progress lands in [progress]. */
    suspend fun update(release: Release) = withContext(Dispatchers.IO) {
        if (_progress.value.let { it is UpdateProgress.Downloading || it is UpdateProgress.Verifying || it is UpdateProgress.Installing }) {
            return@withContext
        }
        val result = runCatching { run(release) }
        result.onFailure {
            Logger.w(TAG, "update failed: ${it.message}")
            // A dropped connection reads the same whichever request it was; the rest are this
            // class's own sentences.
            val message = if (it is IOException) UNREACHABLE else it.message ?: "The update did not finish."
            _progress.value = UpdateProgress.Failed(message)
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

    private fun run(release: Release) {
        val asset = assetFor(kind)?.let(release.assets::get)
            ?: error(retiredLine(kind, release) ?: "This release has no ${kind.label}.")
        val assetName = asset.name

        workDir.mkdirs()
        workDir.listFiles()?.forEach { if (it.name != assetName) it.deleteRecursively() }
        _progress.value = UpdateProgress.Downloading(null)
        val expected = asset.sha256
            ?: release.checksumsUrl?.let { expectedSha256(String(httpGet(it).readBytes()), assetName) }
            ?: error("This release has no checksum for $assetName.")
        val file = File(workDir, assetName)
        download(asset.url, file)

        _progress.value = UpdateProgress.Verifying
        val actual = sha256(file)
        if (!actual.equals(expected, ignoreCase = true)) {
            file.delete()
            error(DAMAGED)
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
        if (connection.responseCode !in 200..299) throw IOException("GitHub answered ${connection.responseCode}")
        return connection
    }

    companion object {
        private const val TAG = "SelfUpdate"

        const val UNREACHABLE = UpdateWords.UNREACHABLE
        const val DAMAGED = UpdateWords.DAMAGED

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

        /**
         * The update feed's key for the package [kind] installs from (see `site/latest.json`).
         * The portable zip, deb and rpm keys stay so a release that still carries them (1.21.0
         * and older) works as before; newer releases simply have no such key.
         */
        fun assetFor(kind: InstallKind): String? = when (kind) {
            InstallKind.WindowsMsi -> "windows-x64-msi"
            InstallKind.WindowsPortable -> "windows-x64-portable"
            InstallKind.AppImage -> "linux-x64-appimage"
            InstallKind.Deb -> "linux-x64-deb"
            InstallKind.Rpm -> "linux-x64-rpm"
            else -> null
        }

        /** The feed key that says [release] still publishes [kind]'s format, whether or not it self updates. */
        private fun publishedKey(kind: InstallKind): String? =
            if (kind == InstallKind.Flatpak) "linux-x64-flatpak" else assetFor(kind)

        /**
         * What the update popup says instead of offering an update when [release] no longer
         * carries this install's format at all: which download replaces it, and whether the
         * viewer's sign in and settings come along. Null when the format is still published (or
         * was never retired), so the ordinary lines apply.
         *
         * The portable zip, deb and rpm keep their data in the same per user folders the MSI and
         * the AppImage use, so moving over keeps everything. The Flatpak's sandbox keeps its data
         * under ~/.var/app, which an AppImage does not look in.
         */
        fun retiredLine(kind: InstallKind, release: Release): String? {
            val next = kind.movesTo ?: return null
            if (publishedKey(kind)?.let(release.assets::containsKey) == true) return null
            val what = "The ${kind.label} is no longer published, so this copy cannot update itself."
            return when (kind) {
                InstallKind.WindowsPortable ->
                    "$what Take the $next from the release page and delete this folder. Your sign in and settings carry over."
                InstallKind.Deb, InstallKind.Rpm ->
                    "$what Remove it with your package manager, then take the $next from the release page. Your sign in and settings carry over."
                else ->
                    "$what Take the $next from the release page. It keeps its data outside the Flatpak, so you sign in again there; INSTALL.md says how to bring your settings."
            }
        }

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
