package com.tmplayer.desktop.cast

import com.tmplayer.cast.LinkSpeedSource
import com.tmplayer.cast.LinkSpeeds
import com.tmplayer.platform.Logger
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class DesktopOs {
    Linux, Windows, Mac, Other;

    companion object {
        fun current(): DesktopOs {
            val name = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
            return when {
                name.contains("win") -> Windows
                name.contains("mac") || name.contains("darwin") -> Mac
                name.contains("linux") || name.contains("nux") -> Linux
                else -> Other
            }
        }
    }
}

/** Runs a short command and gives what it printed; null when it is not there, fails or takes too long. The tests give a fake. */
fun interface CommandRunner {
    fun run(args: List<String>, env: Map<String, String>): String?

    companion object {
        private const val TAG = "CommandRunner"
        private const val MAX_OUTPUT = 16 * 1024
        private const val TIMEOUT_MS = 3_000L

        /** The real one: output cut at 16 KB, killed after 3 seconds. */
        val Default = CommandRunner { args, env ->
            runCatching {
                val pb = ProcessBuilder(args).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.PIPE)
                pb.environment().putAll(env)
                val process = pb.start()
                runCatching { process.outputStream.close() }
                val out = java.io.ByteArrayOutputStream()
                val reader = Thread {
                    runCatching {
                        val buffer = ByteArray(2048)
                        process.inputStream.use { input ->
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                if (out.size() < MAX_OUTPUT) out.write(buffer, 0, minOf(n, MAX_OUTPUT - out.size()))
                            }
                        }
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
                if (!process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    reader.join(500)
                    return@runCatching null
                }
                reader.join(1_000)
                if (process.exitValue() == 0) out.toString(Charsets.UTF_8) else null
            }.onFailure { Logger.i(TAG, "${args.firstOrNull()} did not run: ${it.message}") }.getOrNull()
        }
    }
}

/** The files of the OS the readers look at (`/sys`); a fake in the tests. */
interface SysFiles {
    fun read(path: String): String?
    fun exists(path: String): Boolean

    /** The names in a folder, or none. */
    fun list(path: String): List<String> = emptyList()

    companion object {
        val Default = object : SysFiles {
            override fun read(path: String): String? = runCatching { File(path).takeIf { it.isFile }?.readText()?.take(4096) }.getOrNull()
            override fun exists(path: String): Boolean = runCatching { File(path).exists() }.getOrDefault(false)
            override fun list(path: String): List<String> = runCatching { File(path).list()?.sorted()?.take(64) }.getOrNull().orEmpty()
        }
    }
}

/** The network interface a local address belongs to: its short name ("wlp3s0", "en0") and what the OS calls it ("Intel(R) Wi-Fi 6"). */
data class NetIface(val name: String, val description: String)

/**
 * The speed of the link from this computer to the receiver, for the transcode's bitrate, from what the OS
 * says about the interface the cast server is bound to:
 *
 * - Linux: Ethernet from `/sys/class/net/<if>/speed`, Wi-Fi from `iw dev <if> link` (the `tx` and `rx`
 *   bitrates); without `iw`, nothing.
 * - Windows: PowerShell's `Get-NetAdapter` ReceiveLinkSpeed of the adapter with that description.
 * - macOS: Ethernet from `ifconfig <if>`'s media line; Wi-Fi says no rate there (the `airport` tool is gone), so nothing.
 *
 * A Wi-Fi rate counts for half and a cable for 90 % of it ([LinkSpeeds]). Anything missing is null, and
 * the plan then keeps its default until the cast server has measured the receiver. Read once a minute.
 */
class DesktopLinkSpeed(
    private val local: () -> InetAddress?,
    private val os: DesktopOs = DesktopOs.current(),
    private val files: SysFiles = SysFiles.Default,
    private val commands: CommandRunner = CommandRunner.Default,
    private val iface: (InetAddress) -> NetIface? = ::interfaceOf,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : LinkSpeedSource {

    private var cached: Pair<Long, Int?>? = null

    @Synchronized
    override fun effectiveKbps(): Int? {
        val now = clock()
        cached?.takeIf { now - it.first < CACHE_MS }?.let { return it.second }
        val read = runCatching { read() }.getOrNull()
        cached = now to read
        return read
    }

    private fun read(): Int? {
        val address = local() ?: return null
        val net = iface(address) ?: return null
        if (!net.name.matches(SAFE_NAME)) return null
        return when (os) {
            DesktopOs.Linux -> {
                if (files.exists("/sys/class/net/${net.name}/wireless")) {
                    LinkSpeeds.iwMbps(commands.run(listOf("iw", "dev", net.name, "link"), emptyMap()))?.let(LinkSpeeds::wifiEffectiveKbps)
                } else {
                    LinkSpeeds.sysfsMbps(files.read("/sys/class/net/${net.name}/speed"))?.let(LinkSpeeds::wiredEffectiveKbps)
                }
            }
            DesktopOs.Windows -> {
                // The description goes in through the environment, never into the command line.
                val script = "Get-NetAdapter | Where-Object { \$_.InterfaceDescription -eq \$env:TM_IFACE } | ForEach-Object { \$_.ReceiveLinkSpeed }"
                val mbps = LinkSpeeds.windowsBpsToMbps(
                    commands.run(listOf("powershell", "-NoProfile", "-NonInteractive", "-Command", script), mapOf("TM_IFACE" to net.description)),
                ) ?: return null
                if (LinkSpeeds.looksWireless(net.description)) LinkSpeeds.wifiEffectiveKbps(mbps) else LinkSpeeds.wiredEffectiveKbps(mbps)
            }
            DesktopOs.Mac -> LinkSpeeds.ifconfigMbps(commands.run(listOf("ifconfig", net.name), emptyMap()))?.let(LinkSpeeds::wiredEffectiveKbps)
            DesktopOs.Other -> null
        }
    }

    private companion object {
        const val CACHE_MS = 60_000L
        val SAFE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,31}")

        fun interfaceOf(address: InetAddress): NetIface? = runCatching {
            NetworkInterface.getByInetAddress(address)?.let { NetIface(it.name, it.displayName.orEmpty()) }
        }.getOrNull()
    }
}
