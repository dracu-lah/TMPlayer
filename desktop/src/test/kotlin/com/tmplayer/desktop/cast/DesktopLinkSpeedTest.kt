package com.tmplayer.desktop.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

/** The OS's word on the link, from fake files and commands: no `iw`, PowerShell or sysfs is needed. */
class DesktopLinkSpeedTest {

    private class Fs(val files: Map<String, String> = emptyMap(), val dirs: Set<String> = emptySet()) : SysFiles {
        override fun read(path: String) = files[path]
        override fun exists(path: String) = path in files || path in dirs
    }

    private class Commands(val answer: String?) : CommandRunner {
        val calls = ArrayList<Pair<List<String>, Map<String, String>>>()
        override fun run(args: List<String>, env: Map<String, String>): String? {
            calls += args to env
            return answer
        }
    }

    private val address = InetAddress.getLoopbackAddress()

    private fun speed(os: DesktopOs, fs: SysFiles = Fs(), commands: CommandRunner = Commands(null), iface: NetIface? = NetIface("eth0", "Realtek PCIe GbE"), clock: () -> Long = { 0 }) =
        DesktopLinkSpeed({ address }, os, fs, commands, { iface }, clock)

    @Test
    fun `linux ethernet reads sysfs and counts nine tenths`() {
        val fs = Fs(files = mapOf("/sys/class/net/eth0/speed" to "1000\n"))
        assertEquals(900_000, speed(DesktopOs.Linux, fs).effectiveKbps())
    }

    @Test
    fun `linux ethernet that says minus one is unknown`() {
        val fs = Fs(files = mapOf("/sys/class/net/eth0/speed" to "-1\n"))
        assertNull(speed(DesktopOs.Linux, fs).effectiveKbps())
    }

    @Test
    fun `linux wifi asks iw and counts half`() {
        val fs = Fs(dirs = setOf("/sys/class/net/wlp3s0/wireless"))
        val commands = Commands("Connected to aa:bb\n\trx bitrate: 400.0 MBit/s\n\ttx bitrate: 300.0 MBit/s\n")
        assertEquals(150_000, speed(DesktopOs.Linux, fs, commands, NetIface("wlp3s0", "wlp3s0")).effectiveKbps())
        assertEquals(listOf("iw", "dev", "wlp3s0", "link"), commands.calls.single().first)
    }

    @Test
    fun `linux wifi without iw is unknown, not guessed`() {
        val fs = Fs(dirs = setOf("/sys/class/net/wlp3s0/wireless"))
        assertNull(speed(DesktopOs.Linux, fs, Commands(null), NetIface("wlp3s0", "wlp3s0")).effectiveKbps())
    }

    @Test
    fun `windows asks powershell with the adapter in the environment, never in the command`() {
        val commands = Commands("1000000000\r\n")
        val description = "Intel(R) Ethernet'; Remove-Item C:\\ -Recurse #"
        assertEquals(900_000, speed(DesktopOs.Windows, commands = commands, iface = NetIface("eth3", description)).effectiveKbps())
        val (args, env) = commands.calls.single()
        assertEquals(mapOf("TM_IFACE" to description), env)
        assertEquals(false, args.any { description in it })
    }

    @Test
    fun `windows wifi adapter counts half`() {
        assertEquals(433_350, speed(DesktopOs.Windows, commands = Commands("866700000"), iface = NetIface("wlan0", "Intel(R) Wi-Fi 6 AX201")).effectiveKbps())
    }

    @Test
    fun `mac ethernet reads the media line, mac wifi is unknown`() {
        assertEquals(900_000, speed(DesktopOs.Mac, commands = Commands("en5: flags\n\tmedia: autoselect (1000baseT <full-duplex>)"), iface = NetIface("en5", "en5")).effectiveKbps())
        assertNull(speed(DesktopOs.Mac, commands = Commands("en0: flags\n\tmedia: autoselect"), iface = NetIface("en0", "en0")).effectiveKbps())
    }

    @Test
    fun `an unknown interface, an odd name or another os is unknown`() {
        assertNull(speed(DesktopOs.Linux, iface = null).effectiveKbps())
        assertNull(speed(DesktopOs.Linux, Fs(files = mapOf("/sys/class/net/../speed" to "1000")), iface = NetIface("../..", "x")).effectiveKbps())
        assertNull(speed(DesktopOs.Other, Fs(files = mapOf("/sys/class/net/eth0/speed" to "1000"))).effectiveKbps())
    }

    @Test
    fun `a command that throws is unknown and the answer is kept for a minute`() {
        var now = 0L
        val fs = Fs(files = mapOf("/sys/class/net/eth0/speed" to "100"))
        val calls = intArrayOf(0)
        val counting = object : SysFiles {
            override fun read(path: String): String? {
                calls[0]++
                return fs.read(path)
            }
            override fun exists(path: String) = fs.exists(path)
        }
        val s = DesktopLinkSpeed({ address }, DesktopOs.Linux, counting, CommandRunner { _, _ -> error("boom") }, { NetIface("eth0", "") }, { now })
        assertEquals(90_000, s.effectiveKbps())
        now += 30_000
        s.effectiveKbps()
        assertEquals(1, calls[0])
        now += 31_000
        s.effectiveKbps()
        assertEquals(2, calls[0])
        val thrower = DesktopLinkSpeed({ address }, DesktopOs.Mac, Fs(), CommandRunner { _, _ -> error("boom") }, { NetIface("en0", "") })
        assertNull(runCatching { thrower.effectiveKbps() }.getOrNull())
    }
}
