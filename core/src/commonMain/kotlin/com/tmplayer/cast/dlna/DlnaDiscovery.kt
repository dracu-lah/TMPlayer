package com.tmplayer.cast.dlna

import com.tmplayer.cast.CastServer
import com.tmplayer.cast.LanAddress
import com.tmplayer.platform.Logger
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Finds MediaRenderers on the local network while the cast picker is open, and stops by itself.
 *
 * - One search socket per interface [interfaces] gives (`LanAddress` already leaves out VPNs,
 *   tunnels, bridges and containers' adapters), M-SEARCH sent three times at the start and then
 *   every [repeatMs]. NOTIFY alive and byebye are listened for on port 1900 with address reuse,
 *   so where the OS holds the port (Windows' SSDP Discovery may) the search answers alone carry it.
 * - A device is a row by its UDN: the same TV answering on two interfaces, or again after an alive,
 *   is one row. Its description is fetched 4 at a time with a 3 second timeout, once per UDN and
 *   address, and a device without an AVTransport service is no row. At most [MAX_DEVICES] rows.
 * - Nothing outside the LAN is ever fetched (SSRF): a LOCATION must be an IPv4 address, in a
 *   private range, in the subnet of the interface that heard it, and the same address the datagram
 *   came from. No DNS lookups.
 * - It stops [quietStopMs] after the last new device, [giveUpMs] after the start if there was none,
 *   and on [stop] (the picker closing). [Listener.onFinished] hears the first two.
 *
 * Callbacks come from background threads. The constructor's seams are for the tests, which run it
 * on loopback against a fake renderer (unicast to the fake's port instead of the multicast group).
 */
class DlnaDiscovery(
    private val listener: Listener,
    private val interfaces: () -> List<LanAddress.Candidate> = { LanAddress.candidates().filter { it.address is Inet4Address && CastServer.isLocalNetwork(it.address) } },
    private val searchTarget: InetSocketAddress = InetSocketAddress(Ssdp.MULTICAST_ADDRESS, Ssdp.PORT),
    private val listenForNotify: Boolean = true,
    private val notifyPort: Int = Ssdp.PORT,
    private val allowLoopback: Boolean = false,
    private val repeatMs: Long = 5_000,
    private val quietStopMs: Long = 10_000,
    private val giveUpMs: Long = 20_000,
    private val fetchTimeoutMs: Int = 3_000,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    interface Listener {
        fun onFound(device: DlnaDevice)
        fun onGone(udn: String)

        /** Discovery ended by itself; [found] is how many rows there are. Not called after [stop]. */
        fun onFinished(found: Int)
    }

    private class Net(val candidate: LanAddress.Candidate, val socket: MulticastSocket)

    private val running = AtomicBoolean(false)
    private val devices = ConcurrentHashMap<String, DlnaDevice>()
    private val fetching: MutableSet<String> = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val nets = ArrayList<Net>()
    private var notifySocket: MulticastSocket? = null
    private var timers: ScheduledExecutorService? = null
    private var pool: java.util.concurrent.ExecutorService? = null
    private val threads = AtomicInteger()

    @Volatile
    private var startedAt = 0L

    @Volatile
    private var lastNewAt = 0L

    val isRunning: Boolean get() = running.get()

    /** The rows so far. */
    fun devices(): List<DlnaDevice> = devices.values.sortedBy { it.name.lowercase() }

    @Synchronized
    fun start() {
        if (!running.compareAndSet(false, true)) return
        devices.clear()
        fetching.clear()
        startedAt = clock()
        lastNewAt = 0
        val timer = Executors.newSingleThreadScheduledExecutor { r -> daemon(r, "dlna-timer") }
        val fetchPool = Executors.newFixedThreadPool(FETCHES) { r -> daemon(r, "dlna-fetch-${threads.incrementAndGet()}") }
        timers = timer
        pool = fetchPool
        for (candidate in interfaces()) {
            try {
                val socket = MulticastSocket(InetSocketAddress(candidate.address, 0))
                runCatching { socket.networkInterface = NetworkInterface.getByInetAddress(candidate.address) }
                runCatching { socket.timeToLive = 2 }
                val net = Net(candidate, socket)
                nets += net
                daemon({ receiveLoop(net) }, "dlna-search-${candidate.interfaceName}").start()
            } catch (e: Exception) {
                Logger.w(TAG, "No search socket on ${candidate.interfaceName}", e)
            }
        }
        if (listenForNotify) openNotify()
        if (nets.isEmpty()) {
            // No usable interface: nothing to wait for.
            timer.schedule({ finish() }, 0, TimeUnit.MILLISECONDS)
            return
        }
        for (i in 0 until BURST) timer.schedule({ search() }, i * BURST_GAP_MS, TimeUnit.MILLISECONDS)
        timer.scheduleWithFixedDelay({ search() }, repeatMs, repeatMs, TimeUnit.MILLISECONDS)
        timer.scheduleWithFixedDelay({ checkQuiet() }, CHECK_MS, CHECK_MS, TimeUnit.MILLISECONDS)
    }

    /** Ends discovery now (the picker closed). Safe to call twice, and from a listener callback. */
    @Synchronized
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        timers?.shutdownNow()
        pool?.shutdownNow()
        nets.forEach { runCatching { it.socket.close() } }
        nets.clear()
        notifySocket?.let { runCatching { it.close() } }
        notifySocket = null
    }

    private fun finish() {
        val count = devices.size
        if (running.get()) {
            stop()
            // stop() interrupted this timer thread; the listener should not inherit that.
            Thread.interrupted()
            runCatching { listener.onFinished(count) }.onFailure { Logger.w(TAG, "Listener failed", it) }
        }
    }

    private fun checkQuiet() {
        val now = clock()
        val quiet = lastNewAt != 0L && now - lastNewAt >= quietStopMs
        val gaveUp = lastNewAt == 0L && now - startedAt >= giveUpMs
        if (quiet || gaveUp) finish()
    }

    private fun search() {
        val packet = Ssdp.search()
        for (net in nets.toList()) {
            try {
                net.socket.send(DatagramPacket(packet, packet.size, searchTarget))
            } catch (e: Exception) {
                if (running.get()) Logger.i(TAG, "Search on ${net.candidate.interfaceName} failed: ${e.message}")
            }
        }
    }

    private fun openNotify() {
        try {
            val socket = MulticastSocket(null)
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(notifyPort))
            val group = InetAddress.getByName(Ssdp.MULTICAST_ADDRESS)
            for (net in nets) {
                runCatching {
                    val nif = NetworkInterface.getByInetAddress(net.candidate.address) ?: return@runCatching
                    socket.joinGroup(InetSocketAddress(group, 0), nif)
                }
            }
            notifySocket = socket
            daemon({ receiveLoop(null, socket) }, "dlna-notify").start()
        } catch (e: Exception) {
            // The port is held without sharing, or the group cannot be joined: answers still arrive.
            Logger.i(TAG, "Not listening for NOTIFY: ${e.message}")
        }
    }

    private fun receiveLoop(net: Net?, socket: DatagramSocket = net!!.socket) {
        val buffer = ByteArray(MAX_DATAGRAM)
        while (running.get() && !socket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (e: SocketException) {
                break
            } catch (e: Exception) {
                if (running.get()) Logger.i(TAG, "Receive failed: ${e.message}")
                break
            }
            try {
                val from = packet.address
                if (from != null) handle(Ssdp.parse(packet.data, packet.length) ?: continue, from)
            } catch (e: Exception) {
                Logger.w(TAG, "Bad datagram", e)
            }
        }
    }

    private fun handle(message: SsdpMessage, from: InetAddress) {
        val udn = message.udn ?: return
        if (message.kind == SsdpMessage.Kind.ByeBye) {
            // Any of a device's byebyes (root, uuid, type, services) means it is leaving.
            if (devices.remove(udn) != null) listener.onGone(udn)
            return
        }
        if (!message.isRendererType) return
        val location = message.location ?: return
        val known = devices[udn]
        if (known != null && known.location == location) return
        if (devices.size >= MAX_DEVICES && known == null) return
        if (!acceptable(location, from)) {
            Logger.i(TAG, "Ignored a LOCATION that is not on this network: ${from.hostAddress}")
            return
        }
        if (!fetching.add(location)) return
        val fetchPool = pool ?: return
        try {
            fetchPool.execute { fetchAndAdd(location, from) }
        } catch (_: Exception) {
            fetching.remove(location)
        }
    }

    private fun fetchAndAdd(location: String, from: InetAddress) {
        try {
            val xml = fetchDescription(location, fetchTimeoutMs) ?: return
            val description = DeviceDescription.parse(xml, location) ?: return
            if (!running.get()) return
            val device = DlnaDevice(description, from, location)
            val first = devices.put(device.udn, device) == null
            if (first) lastNewAt = clock()
            listener.onFound(device)
        } catch (e: Exception) {
            Logger.w(TAG, "Description failed", e)
        } finally {
            fetching.remove(location)
        }
    }

    /** True when [location] is an IPv4 address, private, in an interface's subnet, and [from] itself. */
    private fun acceptable(location: String, from: InetAddress): Boolean {
        val url = runCatching { URL(location) }.getOrNull() ?: return false
        if (!url.protocol.equals("http", true) || url.port !in 1..65535 && url.port != -1) return false
        val host = url.host
        if (!IPV4.matches(host)) return false
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() as? Inet4Address ?: return false
        if (address != from) return false
        if (address.isLoopbackAddress) return allowLoopback
        if (!CastServer.isLocalNetwork(address)) return false
        return nets.any { LanAddress.sameSubnet(it.candidate.address, address, it.candidate.prefixLength) }
    }

    private fun daemon(r: Runnable, name: String) = Thread(r, name).apply { isDaemon = true }

    companion object {
        private const val TAG = "DlnaDiscovery"
        const val MAX_DEVICES = 64
        private const val FETCHES = 4
        private const val BURST = 3
        private const val BURST_GAP_MS = 300L
        private const val CHECK_MS = 500L
        private const val MAX_DATAGRAM = 8 * 1024
        private val IPV4 = Regex("""(\d{1,3}\.){3}\d{1,3}""")

        /** The description at [location] as text, or null for any failure; capped at [Xml.DESCRIPTION_MAX]. */
        fun fetchDescription(location: String, timeoutMs: Int): String? {
            var connection: HttpURLConnection? = null
            return try {
                connection = (URL(location).openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    instanceFollowRedirects = false
                    useCaches = false
                    setRequestProperty("User-Agent", "TMPlayer")
                    setRequestProperty("Connection", "close")
                }
                if (connection.responseCode != 200) return null
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                connection.inputStream.use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        if (out.size() > Xml.DESCRIPTION_MAX) return null
                    }
                }
                out.toString(Charsets.UTF_8.name())
            } catch (e: Exception) {
                null
            } finally {
                connection?.disconnect()
            }
        }
    }
}
