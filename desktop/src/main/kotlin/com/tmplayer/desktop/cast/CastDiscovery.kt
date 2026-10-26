package com.tmplayer.desktop.cast

import com.tmplayer.platform.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** A Chromecast, Google TV or TV with Cast built in, as its mDNS record names it. */
data class CastDevice(
    /** The device's own id (the record's "id"), steady across restarts and address changes. */
    val id: String,
    /** "Living room TV": what the viewer called it ("fn"). */
    val name: String,
    /** "Chromecast", "Google TV Streamer", "BRAVIA 4K VH2" ("md"). */
    val model: String,
    val address: InetAddress,
    val port: Int = 8009,
    /** What it is doing now, when it says ("rs"): "YouTube", "Netflix", empty when idle. */
    val status: String = "",
    /** The capability bits ("ca"); bit 0 video out, bit 2 audio out. */
    val capabilities: Int = 0,
) {
    /** A speaker or speaker group: no picture, so not offered for a video. */
    val audioOnly: Boolean get() = capabilities != 0 && (capabilities and 0x01) == 0
}

/**
 * Finds Cast receivers on the local network: an mDNS question for `_googlecast._tcp.local`, asked
 * every few seconds while the picker is open, and every answer kept as a [CastDevice] until it has
 * not answered for a minute.
 *
 * Asked two ways at once, since either can be blocked: from the mDNS port itself on every
 * interface (shared with the system's own responder where the OS allows it), and from an ordinary
 * port with the "unicast reply" bit set, which a receiver answers directly.
 */
class CastDiscovery {

    private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())
    val devices: StateFlow<List<CastDevice>> = _devices.asStateFlow()

    private val seen = HashMap<String, Pair<CastDevice, Long>>()
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.tmplayer.platform.JobFailures.handler("CastDiscovery"))

    /** Starts asking; [stop] ends it. Calling it again while running does nothing. */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            val sockets = openSockets()
            try {
                sockets.forEach { socket -> launch { listen(socket) } }
                var round = 0
                while (isActive) {
                    val query = MdnsPacket.query(SERVICE)
                    for (socket in sockets) {
                        runCatching { socket.send(DatagramPacket(query, query.size, InetSocketAddress(GROUP, PORT))) }
                    }
                    prune()
                    // Quickly at first, as the picker opens; then at a walk.
                    delay(if (round++ < 3) 1_000 else 4_000)
                }
            } finally {
                sockets.forEach { runCatching { it.close() } }
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    private fun openSockets(): List<DatagramSocket> {
        val out = ArrayList<DatagramSocket>()
        runCatching {
            val multicast = MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
                soTimeout = 1000
                timeToLive = 255
            }
            var joined = 0
            for (nif in interfaces()) {
                if (runCatching { multicast.joinGroup(InetSocketAddress(GROUP, PORT), nif) }.isSuccess) joined++
            }
            if (joined > 0) out += multicast else multicast.close()
        }.onFailure { Logger.i(TAG, "mDNS port not shared: ${it.message}") }
        runCatching {
            out += DatagramSocket(0).apply { soTimeout = 1000 }
        }
        return out
    }

    private fun interfaces(): List<NetworkInterface> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter {
            runCatching { it.isUp && !it.isLoopback && it.supportsMulticast() && it.inetAddresses.toList().any { a -> a is Inet4Address } }.getOrDefault(false)
        }
    }.getOrDefault(emptyList())

    private suspend fun listen(socket: DatagramSocket) {
        val buffer = ByteArray(9000)
        while (scope.isActive && job?.isActive == true && !socket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                break
            }
            val records = runCatching { MdnsPacket.parse(packet.data.copyOf(packet.length)) }.getOrNull() ?: continue
            MdnsPacket.devices(records, packet.address).forEach(::note)
        }
    }

    @Synchronized
    private fun note(device: CastDevice) {
        if (device.audioOnly) return
        seen[device.id] = device to System.currentTimeMillis()
        publish()
    }

    @Synchronized
    private fun prune() {
        val now = System.currentTimeMillis()
        if (seen.values.removeAll { now - it.second > STALE_MS }) publish()
    }

    private fun publish() {
        _devices.value = seen.values.map { it.first }.sortedBy { it.name.lowercase() }
    }

    companion object {
        private const val TAG = "CastDiscovery"
        const val SERVICE = "_googlecast._tcp.local"
        private val GROUP: InetAddress = InetAddress.getByName("224.0.0.251")
        private const val PORT = 5353
        private const val STALE_MS = 60_000L
    }
}

/** The few parts of DNS that mDNS service discovery needs: a PTR question, and the answers to it. */
object MdnsPacket {

    const val PTR = 12
    const val SRV = 33
    const val TXT = 16
    const val A = 1

    sealed interface Record {
        val name: String
    }

    data class Ptr(override val name: String, val target: String) : Record
    data class Srv(override val name: String, val port: Int, val target: String) : Record
    data class Txt(override val name: String, val entries: Map<String, String>) : Record
    data class Address(override val name: String, val address: InetAddress) : Record

    /** A question for [service]'s PTR records, asking for a unicast reply as well. */
    fun query(service: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        writeName(out, service)
        out.write(0); out.write(PTR)
        out.write(0x80); out.write(0x01) // IN, with the QU bit
        return out.toByteArray()
    }

    fun writeName(out: ByteArrayOutputStream, name: String) {
        for (label in name.trimEnd('.').split('.')) {
            val b = label.toByteArray(Charsets.UTF_8)
            out.write(b.size)
            out.write(b)
        }
        out.write(0)
    }

    /** Every answer, authority and additional record in a response. */
    fun parse(p: ByteArray): List<Record> {
        if (p.size < 12) return emptyList()
        val flags = u16(p, 2)
        if (flags and 0x8000 == 0) return emptyList() // a question, not an answer
        val questions = u16(p, 4)
        val total = u16(p, 6) + u16(p, 8) + u16(p, 10)
        var at = 12
        repeat(questions) {
            at = readName(p, at).second + 4
        }
        val out = ArrayList<Record>()
        repeat(total) {
            if (at + 10 > p.size) return out
            val (name, after) = readName(p, at)
            at = after
            val type = u16(p, at)
            val length = u16(p, at + 8)
            val data = at + 10
            if (data + length > p.size) return out
            when (type) {
                PTR -> out += Ptr(name, readName(p, data).first)
                SRV -> out += Srv(name, u16(p, data + 4), readName(p, data + 6).first)
                TXT -> out += Txt(name, txt(p, data, data + length))
                A -> if (length == 4) out += Address(name, InetAddress.getByAddress(p.copyOfRange(data, data + 4)))
            }
            at = data + length
        }
        return out
    }

    /** The receivers [records] describe; [from] stands in for an address the packet left out. */
    fun devices(records: List<Record>, from: InetAddress?): List<CastDevice> {
        val instances = records.filterIsInstance<Ptr>().filter { it.name.equals(CastDiscovery.SERVICE, ignoreCase = true) }.map { it.target } +
            records.filterIsInstance<Srv>().map { it.name }
        return instances.distinct().mapNotNull { instance ->
            val txt = records.filterIsInstance<Txt>().firstOrNull { it.name.equals(instance, ignoreCase = true) }?.entries ?: return@mapNotNull null
            val srv = records.filterIsInstance<Srv>().firstOrNull { it.name.equals(instance, ignoreCase = true) }
            val address = srv?.let { s -> records.filterIsInstance<Address>().firstOrNull { it.name.equals(s.target, ignoreCase = true) }?.address }
                ?: from ?: return@mapNotNull null
            val id = txt["id"]?.takeIf { it.isNotBlank() } ?: instance
            CastDevice(
                id = id,
                name = txt["fn"]?.takeIf { it.isNotBlank() } ?: instance.substringBefore("._googlecast"),
                model = txt["md"].orEmpty(),
                address = address,
                port = srv?.port ?: 8009,
                status = txt["rs"].orEmpty(),
                capabilities = txt["ca"]?.toIntOrNull() ?: 0,
            )
        }
    }

    private fun txt(p: ByteArray, from: Int, to: Int): Map<String, String> {
        val out = HashMap<String, String>()
        var at = from
        while (at < to) {
            val n = p[at].toInt() and 0xFF
            at++
            if (at + n > to) break
            val entry = String(p, at, n, Charsets.UTF_8)
            at += n
            val eq = entry.indexOf('=')
            if (eq > 0) out[entry.substring(0, eq).lowercase()] = entry.substring(eq + 1)
        }
        return out
    }

    /** A name at [start] (following compression pointers), and where the record goes on after it. */
    fun readName(p: ByteArray, start: Int): Pair<String, Int> {
        val labels = ArrayList<String>()
        var at = start
        var end = -1
        var jumps = 0
        while (at < p.size) {
            val n = p[at].toInt() and 0xFF
            if (n == 0) {
                at++
                break
            }
            if (n and 0xC0 == 0xC0) {
                if (at + 1 >= p.size) break
                if (end < 0) end = at + 2
                at = ((n and 0x3F) shl 8) or (p[at + 1].toInt() and 0xFF)
                if (++jumps > 32) break
                continue
            }
            if (at + 1 + n > p.size) break
            labels += String(p, at + 1, n, Charsets.UTF_8)
            at += 1 + n
        }
        return labels.joinToString(".") to (if (end >= 0) end else at)
    }

    private fun u16(p: ByteArray, at: Int): Int = ((p[at].toInt() and 0xFF) shl 8) or (p[at + 1].toInt() and 0xFF)
}
