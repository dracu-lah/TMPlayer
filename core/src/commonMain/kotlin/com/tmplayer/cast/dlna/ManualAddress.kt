package com.tmplayer.cast.dlna

import com.tmplayer.cast.CastServer
import com.tmplayer.platform.Logger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * "Add a TV by its address": what a person may type, and what is done with it.
 *
 * Accepted: an IPv4 address (`192.168.1.20`), with a port (`192.168.1.20:49152`), or a description
 * link (`http://192.168.1.20:49152/description.xml`). Only addresses on a private network (10/8,
 * 172.16/12, 192.168/16, the 100.64/10 range some routers hand out) or link local (169.254/16) are
 * allowed: a link pasted from a web page must never make the phone fetch something on the internet
 * or open a connection to a stranger (SSRF). So there is no name lookup either (a host name is
 * refused, nothing is resolved), no IPv6 (the cast server and the TVs speak IPv4 on a home network),
 * no `https`, no user name in the link and no redirect: a TV that answers with a redirect is "not a
 * TV" here.
 */
object ManualAddress {

    /** Why an address was turned down before anything was sent. */
    enum class Problem { Empty, TooLong, Garbage, Scheme, Credentials, Hostname, Ipv6, Port, NotLocal }

    sealed interface Parsed {
        /** [path] is the description's path when the link had one (`/description.xml`), else null. */
        data class Target(val host: Inet4Address, val port: Int?, val path: String?) : Parsed {
            val hostText: String get() = host.hostAddress ?: ""

            /** The address as a message names it: `192.168.1.20` or `192.168.1.20:49152`. */
            val display: String get() = hostText + (port?.let { ":$it" } ?: "")
        }

        data class Refused(val problem: Problem) : Parsed
    }

    private const val MAX_INPUT = 300
    private const val MAX_PATH = 200
    // [0-9], not \d: on Android \d also matches digits of other scripts that Int parsing refuses.
    private val IPV4 = Regex("""([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})""")
    private val NAME = Regex("""[A-Za-z0-9._-]+""")

    /** Ports and paths tried when only an IP address was given and the TV did not answer the search. */
    private val GUESS_PORTS = listOf(49152, 49153)
    private val GUESS_PATHS = listOf("/description.xml", "/rootDesc.xml", "/dd.xml", "/DeviceDescription.xml", "/xml/device_description.xml")

    fun parse(input: String?, allowLoopback: Boolean = false): Parsed {
        val text = input?.trim().orEmpty()
        if (text.isEmpty()) return Parsed.Refused(Problem.Empty)
        if (text.length > MAX_INPUT) return Parsed.Refused(Problem.TooLong)
        if (text.any { it.code < 0x21 || it.code > 0x7E }) return Parsed.Refused(Problem.Garbage)
        var rest = text
        val schemeEnd = rest.indexOf("://")
        if (schemeEnd >= 0) {
            if (!rest.substring(0, schemeEnd).equals("http", ignoreCase = true)) return Parsed.Refused(Problem.Scheme)
            rest = rest.substring(schemeEnd + 3)
        }
        val authorityEnd = rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authorityEnd)
        val tail = rest.substring(authorityEnd).substringBefore('#')
        if ('@' in authority) return Parsed.Refused(Problem.Credentials)
        if (authority.startsWith("[") || authority.count { it == ':' } > 1) return Parsed.Refused(Problem.Ipv6)
        val hostPart = authority.substringBefore(':')
        val portPart = if (':' in authority) authority.substringAfter(':') else null
        val port = when {
            portPart == null -> null
            portPart.isEmpty() || portPart.length > 5 || !portPart.all { it in '0'..'9' } -> return Parsed.Refused(Problem.Port)
            else -> portPart.toInt().takeIf { it in 1..65535 } ?: return Parsed.Refused(Problem.Port)
        }
        if (hostPart.isEmpty()) return Parsed.Refused(Problem.Garbage)
        val octets = IPV4.matchEntire(hostPart)?.groupValues?.drop(1)?.map { it.toIntOrNull() ?: return Parsed.Refused(Problem.Garbage) }
        if (octets == null) {
            return Parsed.Refused(if (NAME.matches(hostPart) && hostPart.any { it.isLetter() }) Problem.Hostname else Problem.Garbage)
        }
        if (octets.any { it > 255 }) return Parsed.Refused(Problem.Garbage)
        val address = runCatching { InetAddress.getByAddress(ByteArray(4) { octets[it].toByte() }) as? Inet4Address }.getOrNull()
            ?: return Parsed.Refused(Problem.Garbage)
        if (address.isAnyLocalAddress || address.isMulticastAddress) return Parsed.Refused(Problem.NotLocal)
        if (address.isLoopbackAddress) {
            if (!allowLoopback) return Parsed.Refused(Problem.NotLocal)
        } else if (!CastServer.isLocalNetwork(address)) {
            return Parsed.Refused(Problem.NotLocal)
        }
        var path: String? = null
        if (tail.isNotEmpty() && tail != "/") {
            path = if (tail.startsWith("/")) tail else "/$tail"
            if (path.length > MAX_PATH) return Parsed.Refused(Problem.TooLong)
        }
        return Parsed.Target(address, port, path)
    }

    /** The description addresses to try for [target], most likely first. */
    fun candidates(target: Parsed.Target): List<String> {
        val host = target.hostText
        target.path?.let { return listOf("http://$host${target.port?.let { p -> ":$p" } ?: ""}$it") }
        val ports = target.port?.let { listOf(it) } ?: GUESS_PORTS
        val paths = if (target.port != null) GUESS_PATHS else GUESS_PATHS.take(2)
        return ports.flatMap { port -> paths.map { "http://$host:$port$it" } }
    }

    /** What happened to an address that was asked about. */
    sealed interface Outcome {
        data class Found(val device: DlnaDevice) : Outcome

        /** Refused before anything was sent. */
        data class Invalid(val problem: Problem) : Outcome

        /** Nothing answered at that address (the TV is off, another network, a firewall). */
        data class Silent(val address: String) : Outcome

        /** Something answered, and it is not a renderer TMPlayer can cast to (or its description is broken). */
        data class Broken(val address: String) : Outcome
    }

    /**
     * Asks the TV at [input] who it is. A unicast M-SEARCH to port 1900 first (a TV that answers
     * gives its own LOCATION, which must be on the same address), then the guessed or given
     * description addresses, up to 4 at a time. Blocks for a few seconds at most; call it off the
     * main thread. Never throws.
     */
    fun resolve(
        input: String,
        timeoutMs: Int = 3_000,
        allowLoopback: Boolean = false,
        ssdpPort: Int = Ssdp.PORT,
        fetch: (String, Int) -> DlnaDiscovery.Fetch = { location, timeout -> DlnaDiscovery.fetch(location, timeout) },
    ): Outcome {
        val target = when (val p = parse(input, allowLoopback)) {
            is Parsed.Refused -> return Outcome.Invalid(p.problem)
            is Parsed.Target -> p
        }
        return try {
            val locations = LinkedHashSet<String>()
            if (target.path == null) locations += askTv(target, ssdpPort, timeoutMs.coerceAtMost(1_500))
            locations += candidates(target)
            describe(target, locations.toList(), timeoutMs, fetch)
        } catch (e: Exception) {
            Logger.w("ManualAddress", "Resolve failed", e)
            Outcome.Silent(target.display)
        }
    }

    /** A saved link, checked again when it is loaded (the file may have been edited): its target, or null. */
    fun savedTarget(location: String, allowLoopback: Boolean = false): Parsed.Target? =
        (parse(location, allowLoopback) as? Parsed.Target)?.takeIf { it.path != null }

    /** The LOCATION of a unicast answer when it is a link on [host] itself, else null. */
    fun locationOn(host: String, location: String?): String? {
        val parsed = parse(location, allowLoopback = true) as? Parsed.Target ?: return null
        return location?.trim()?.takeIf { parsed.hostText == host && parsed.path != null && it.startsWith("http://", ignoreCase = true) }
    }

    private fun askTv(target: Parsed.Target, ssdpPort: Int, waitMs: Int): List<String> {
        val found = ArrayList<String>()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 250
                val to = InetSocketAddress(target.host, ssdpPort)
                for (st in Ssdp.SEARCH_TARGETS) {
                    val bytes = Ssdp.search(st)
                    socket.send(DatagramPacket(bytes, bytes.size, to))
                }
                val buffer = ByteArray(8 * 1024)
                val end = System.nanoTime() + waitMs * 1_000_000L
                while (System.nanoTime() < end && found.size < 4) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    if (packet.address != target.host) continue
                    val message = Ssdp.parse(packet.data, packet.length) ?: continue
                    locationOn(target.hostText, message.location)?.let { if (it !in found) found += it }
                }
            }
        } catch (e: Exception) {
            Logger.i("ManualAddress", "No unicast answer: ${e.message}")
        }
        return found
    }

    private class Attempt(val location: String, val result: DlnaDiscovery.Fetch, val description: DeviceDescription?)

    private fun describe(target: Parsed.Target, locations: List<String>, timeoutMs: Int, fetch: (String, Int) -> DlnaDiscovery.Fetch): Outcome {
        val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "dlna-manual").apply { isDaemon = true } }
        try {
            val tasks = locations.take(12).map { location ->
                Callable {
                    val result = fetch(location, timeoutMs)
                    val description = (result as? DlnaDiscovery.Fetch.Text)?.let { DeviceDescription.parse(it.text, location) }
                    Attempt(location, result, description)
                }
            }
            val done = pool.invokeAll(tasks, (timeoutMs * 3L).coerceAtMost(15_000), TimeUnit.MILLISECONDS)
            var answered = false
            for (future in done) {
                val attempt = runCatching { future.get() }.getOrNull() ?: continue
                if (attempt.description != null) {
                    return Outcome.Found(DlnaDevice(attempt.description, target.host, attempt.location, manual = true))
                }
                if (attempt.result !is DlnaDiscovery.Fetch.Failed) answered = true
            }
            return if (answered) Outcome.Broken(target.display) else Outcome.Silent(target.display)
        } finally {
            pool.shutdownNow()
        }
    }
}
