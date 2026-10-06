package com.tmplayer.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import com.tmplayer.desktop.os.NativeInventory
import com.tmplayer.desktop.os.OsInfo
import com.tmplayer.desktop.player.MpvNatives
import com.tmplayer.i18n.Icu
import com.tmplayer.i18n.Languages
import com.tmplayer.i18n.Translator
import org.openani.mediamp.mpv.MPVHandle
import java.io.File

/**
 * `TMPlayer --self-test`: loads every native library the app depends on and every UI language's
 * catalog, prints what it found and exits 0, or 1 if anything failed to load. No window, no Telegram account, no network.
 *
 * CI runs it against the packaged app (the Linux app image, the AppImage, the Flatpak, the
 * installed MSI and the portable zip), so a release can never ship an installer whose natives do
 * not load on the OS it is for. `--self-test=<file>` also writes the report to that file, for the
 * Windows launcher, which is a GUI program and may have nowhere to print to.
 */
object SelfTest {

    const val FLAG = "--self-test"

    fun matches(arg: String): Boolean = arg == FLAG || arg.startsWith("$FLAG=")

    fun run(arg: String): Int {
        // Nothing here draws, and a headless toolkit means no display is needed on CI.
        System.setProperty("java.awt.headless", "true")
        val out = StringBuilder()
        fun say(line: String) {
            println(line)
            out.appendLine(line)
        }

        say("TMPlayer ${BuildInfo.VERSION} self-test")
        var failed = 0
        fun step(name: String, block: () -> String) {
            val result = runCatching(block)
            result.onSuccess { say("PASS $name: $it") }
            result.onFailure {
                failed++
                say("FAIL $name: ${it::class.java.name}: ${it.message}")
                it.cause?.let { c -> say("     caused by ${c::class.java.name}: ${c.message}") }
            }
        }

        if (OsInfo.isWindows) step("msvc runtime") { WindowsRuntime.preload() }
        step("tdlib") { tdlibVersion() }
        if (OsInfo.isWindows) step("msvc runtime origin") { WindowsRuntime.checkOrigin() }
        step("libmpv") { mpvVersions() }
        step("jna") { "${Native.VERSION}, native ${Native.VERSION_NATIVE}, pointer size ${Native.POINTER_SIZE}" }
        step("i18n") { catalogs() }
        step("skiko") {
            org.jetbrains.skiko.Library.load()
            "loaded"
        }
        NativeInventory.report().forEach { say("info $it") }
        say(if (failed == 0) "SELF-TEST PASSED" else "SELF-TEST FAILED ($failed)")

        arg.substringAfter("=", "").takeIf { it.isNotBlank() }?.let { path ->
            runCatching { File(path).writeText(out.toString()) }
        }
        return if (failed == 0) 0 else 1
    }

    /**
     * Loads libtdjsonjava through tdl-coroutines' own loader (the JsonClient constructor, the same
     * path the app's first TdlClient takes) and asks it a synchronous question. JsonClient is
     * Kotlin internal to tdl-coroutines, hence the reflection.
     */
    private fun tdlibVersion(): String {
        val type = Class.forName("org.drinkless.tdlib.JsonClient")
        val client = type.getDeclaredConstructor().newInstance()
        val exec = type.getMethod("execute", String::class.java)
        fun execute(json: String): String? = exec.invoke(client, json) as String?
        execute("""{"@type":"setLogVerbosityLevel","new_verbosity_level":0}""")
        val version = execute("""{"@type":"getOption","name":"version"}""")
        val commit = execute("""{"@type":"getOption","name":"commit_hash"}""")
        val v = VALUE.find(version.orEmpty())?.groupValues?.get(1)
            ?: error("unexpected answer to getOption version: $version")
        val c = VALUE.find(commit.orEmpty())?.groupValues?.get(1)?.take(10).orEmpty()
        return "TDLib $v ($c)"
    }

    /** Unpacks the mediamp runtime as the player does, creates and initialises an mpv handle. */
    private fun mpvVersions(): String {
        MpvNatives.prepare()
        val h = MPVHandle(Unit)
        try {
            h.option("vo", "null")
            h.option("ao", "null")
            h.option("terminal", "no")
            check(h.initialize()) { "mpv_initialize failed" }
            val mpv = h.getPropertyString("mpv-version")
            val ffmpeg = h.getPropertyString("ffmpeg-version")
            return "$mpv, FFmpeg $ffmpeg"
        } finally {
            h.destroy()
        }
    }

    /**
     * Reads every UI language's catalog off the classpath the way the app does, and fails if one
     * does not parse, carries a message that is not valid ICU, or names a key English does not
     * have. A language with no catalog yet is fine: it reads in English.
     */
    private fun catalogs(): String {
        val english = Translator.resource(Languages.ENGLISH) ?: error("i18n/en.json is not in the app")
        val keys = Translator.parse(english).onEach { (_, text) -> Icu.parse(text) }.keys
        val others = Languages.tags.filter { it != Languages.ENGLISH }
        val present = others.mapNotNull { tag ->
            val json = Translator.resource(tag) ?: return@mapNotNull null
            val entries = Translator.parse(json)
            val stray = entries.keys - keys
            check(stray.isEmpty()) { "$tag has keys English does not: ${stray.take(3)}" }
            entries.forEach { (key, text) -> runCatching { Icu.parse(text) }.getOrElse { throw IllegalStateException("$tag $key: ${it.message}") } }
            check(Translator.load(tag).tag == tag)
            "$tag ${entries.size}"
        }
        Translator.load(Languages.PSEUDO)
        return "${keys.size} English keys; ${present.size} of ${others.size} translations present" +
            present.joinToString(prefix = if (present.isEmpty()) "" else " (", postfix = if (present.isEmpty()) "" else ")")
    }

    private val VALUE = Regex(""""value"\s*:\s*"([^"]*)"""")
}

/**
 * TDLib's Windows build links against the Microsoft C++ runtime (MSVCP140, VCRUNTIME140 and
 * VCRUNTIME140_1), which a clean Windows does not have unless some other program installed the
 * Visual C++ Redistributable. The JDK ships those three DLLs in its own `bin`, and jlink copies
 * them into the bundled runtime; loading them by full path first means TDLib's imports resolve to
 * the copies inside the app instead of failing on a machine without the redistributable.
 */
object WindowsRuntime {

    private val NAMES = listOf("vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll")

    /** Loads the runtime DLLs from the bundled JVM's `bin`; says which were there. */
    fun preload(): String {
        if (!OsInfo.isWindows) return "not Windows"
        val bin = File(System.getProperty("java.home"), "bin")
        val loaded = NAMES.filter { name ->
            val f = File(bin, name)
            f.isFile && runCatching { System.load(f.absolutePath) }.isSuccess
        }
        return "preloaded ${loaded.joinToString().ifEmpty { "nothing" }} from $bin"
    }

    /** Fails unless every runtime DLL TDLib imports came from inside the app. */
    fun checkOrigin(): String {
        val home = File(System.getProperty("java.home")).canonicalPath.lowercase()
        val origins = NAMES.associateWith { moduleFile(it) }
        val outside = origins.filter { (_, path) -> path == null || !path.lowercase().startsWith(home) }
        check(outside.isEmpty()) {
            "not from the bundled runtime: " + outside.entries.joinToString { "${it.key}=${it.value ?: "not loaded"}" }
        }
        return origins.values.joinToString()
    }

    private fun moduleFile(name: String): String? {
        val k = Native.load("kernel32", Kernel32::class.java)
        val handle = k.GetModuleHandleW(WString(name)) ?: return null
        val buf = CharArray(1024)
        val n = k.GetModuleFileNameW(handle, buf, buf.size)
        return if (n > 0) File(String(buf, 0, n)).canonicalPath else null
    }

    @Suppress("FunctionName")
    private interface Kernel32 : StdCallLibrary {
        fun GetModuleHandleW(name: WString): Pointer?
        fun GetModuleFileNameW(module: Pointer, buffer: CharArray, size: Int): Int
    }
}
