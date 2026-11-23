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
 * - Each round asks for both `urn:schemas-upnp-org:device:MediaRenderer:1` and `ssdp:all` (an old TV
 *   may answer only one of them); what an `ssdp:all` answer names is fetched only when it can be a
 *   renderer ([SsdpMessage.mayBeRenderer]), once per address, and a description that is not a
 *   renderer is not asked for again in this run.
 * - A row lives as long as the device's max-age says (every word from it, an answer or an alive,
 *   renews it) or until its byebye; manual rows stay. A TV that changes address is the same row
 *   (its UDN) at the new address.
 * - Not [live]: it stops [quietStopMs] after the last new device, [giveUpMs] after the start if
 *   there was none, and on [stop] (the picker closing). [Listener.onFinished] hears the first two.
 *   [live] (the picker): it goes on listening for NOTIFY and searching every 30 seconds until [stop],
 *   so a TV switched on with the picker open appears; [Listener.onSettled] hears the first quiet spell.
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
    /** Stays on until [stop] (see above) instead of ending after a quiet spell. */
    private val live: Boolean = false,
    /** Description links of the TVs added by their address, asked for at the start. */
    private val manual: () -> List<String> = { emptyList() },
) {
    interface Listener {
        fun onFound(device: DlnaDevice)
        fun onGone(udn: String)

        /** Discovery ended by itself; [found] is how many rows there are. Not called after [stop]. */
        fun onFinished(found: Int)

        /** A [live] discovery has been quiet for a while (the first sweep is over) and keeps listening. */
        fun onSettled(found: Int) = Unit
    }

    /** What a description address answered with. */
    sealed interface Fetch {
        class Text(val text: String) : Fetch

        /** An answer that is not 200 (a redirect included: it is never followed). */
        class Status(val code: Int) : Fetch

        /** Nothing: refused, timed out, cut, or not HTTP at all. */
        data object Failed : Fetch

        /** More than [Xml.DESCRIPTION_MAX]. */
        data object TooBig : Fetch
    }

    private class Net(val candidate: LanAddress.Candidate, val socket: MulticastSocket)

    private val running = AtomicBoolean(false)
    private val devices = ConcurrentHashMap<String, DlnaDevice>()
    private val fetching: MutableSet<String> = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** Locations that were fetched and are no renderer (a router, a printer): not asked again this run. */
    private val notRenderers: MutableSet<String> = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** UDN to when its row expires (the clock's ms); manual rows are not in it. */
    private val expires = ConcurrentHashMap<String, Long>()
    private val nets = ArrayList<Net>()
    private var notifySocket: MulticastSocket? = null
    private var timers: ScheduledExecutorService? = null
    private var pool: java.util.concurrent.ExecutorService? = null
    private val threads = AtomicInteger()

    @Volatile
    private var startedAt = 0L

    @Volatile
    private var lastNewAt = 0L

    @Volatile
    private var settled = false

    private val ticks = AtomicInteger()

    val isRunning: Boolean get() = running.get()

    /** The rows so far. */
    fun devices(): List<DlnaDevice> = devices.values.sortedBy { it.name.lowercase() }

    @Synchronized
    fun start() {
        if (!running.compareAndSet(false, true)) return
        devices.clear()
        fetching.clear()
        notRenderers.clear()
        expires.clear()
        settled = false
        ticks.set(0)
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
        // Once a live search has settled it asks only every SLOW_EVERY rounds; the NOTIFYs carry the rest.
        timer.scheduleWithFixedDelay({ if (!settled || ticks.incrementAndGet() % SLOW_EVERY == 0) search() }, repeatMs, repeatMs, TimeUnit.MILLISECONDS)
        timer.scheduleWithFixedDelay({ checkQuiet() }, CHECK_MS, CHECK_MS, TimeUnit.MILLISECONDS)
        for (location in manual().take(MAX_DEVICES)) startManual(location)
    }

    /** A saved link: checked again (the file can be edited), then fetched like any other. */
    private fun startManual(location: String) {
        val target = ManualAddress.savedTarget(location, allowLoopback) ?: return
        if (!fetching.add(location)) return
        try {
            pool?.execute { fetchAndAdd(location, target.host, manual = true) } ?: fetching.remove(location)
        } catch (_: Exception) {
            fetching.remove(location)
        }
    }

    /** Puts a TV that [ManualAddress.resolve] found in the rows, so it is listed and kept. */
    fun adopt(device: DlnaDevice) {
        if (!running.get() || devices.size >= MAX_DEVICES && devices[device.udn] == null) return
        devices[device.udn] = device
        if (!device.manual) expires[device.udn] = clock() + TTL_DEFAULT_MS
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
        sweep(now)
        val quiet = lastNewAt != 0L && now - lastNewAt >= quietStopMs
        val gaveUp = lastNewAt == 0L && now - startedAt >= giveUpMs
        if (!quiet && !gaveUp) return
        if (!live) return finish()
        if (!settled && running.get()) {
            settled = true
            runCatching { listener.onSettled(devices.size) }.onFailure { Logger.w(TAG, "Listener failed", it) }
        }
    }

    /** Takes the rows whose max-age ran out (the TV was switched off without a byebye). */
    private fun sweep(now: Long) {
        for ((udn, at) in expires.entries.toList()) {
            if (now < at) continue
            expires.remove(udn, at)
            if (devices[udn]?.manual == true) continue
            if (devices.remove(udn) != null) {
                runCatching { listener.onGone(udn) }.onFailure { Logger.w(TAG, "Listener failed", it) }
            }
        }
    }

    private fun search() {
        for (target in Ssdp.SEARCH_TARGETS) {
            val packet = Ssdp.search(target)
            for (net in nets.toList()) {
                try {
                    net.socket.send(DatagramPacket(packet, packet.size, searchTarget))
                } catch (e: Exception) {
                    if (running.get()) Logger.i(TAG, "Search on ${net.candidate.interfaceName} failed: ${e.message}")
                }
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
            expires.remove(udn)
            if (devices.remove(udn) != null) listener.onGone(udn)
            return
        }
        if (!message.mayBeRenderer) return
        val location = message.location ?: return
        // A word from a device renews its row, whichever of its UDNs (root or renderer) it spoke as.
        val known = devices[udn] ?: devices.values.firstOrNull { it.location == location }
        if (known != null && known.location == location) {
            if (!known.manual) expires[known.udn] = clock() + ttlMs(message.maxAgeSeconds)
            return
        }
        if (location in notRenderers) return
        if (devices.size >= MAX_DEVICES && known == null) return
        if (!acceptable(location, from)) {
            Logger.i(TAG, "Ignored a LOCATION that is not on this network: ${from.hostAddress}")
            return
        }
        if (!fetching.add(location)) return
        val fetchPool = pool ?: return
        val ttl = ttlMs(message.maxAgeSeconds)
        try {
            fetchPool.execute { fetchAndAdd(location, from, ttlMs = ttl) }
        } catch (_: Exception) {
            fetching.remove(location)
        }
    }

    private fun ttlMs(maxAgeSeconds: Int?): Long =
        (maxAgeSeconds ?: Ssdp.DEFAULT_MAX_AGE_SECONDS).coerceIn(MIN_TTL_S, MAX_TTL_S) * 1000L

    private fun fetchAndAdd(location: String, from: InetAddress, manual: Boolean = false, ttlMs: Long = TTL_DEFAULT_MS) {
        try {
            val xml = (fetch(location, fetchTimeoutMs) as? Fetch.Text)?.text ?: return
            val description = DeviceDescription.parse(xml, location)
            if (description == null) {
                if (notRenderers.size < MAX_REJECTED) notRenderers += location
                return
            }
            if (!running.get()) return
            val device = DlnaDevice(description, from, location, manual = manual)
            val first = devices.put(device.udn, device) == null
            if (!manual) expires[device.udn] = clock() + ttlMs
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
        private const val SLOW_EVERY = 6
        private const val MAX_REJECTED = 512
        private const val MIN_TTL_S = 20
        private const val MAX_TTL_S = 86_400
        private const val TTL_DEFAULT_MS = Ssdp.DEFAULT_MAX_AGE_SECONDS * 1000L
        private const val CHECK_MS = 500L
        private const val MAX_DATAGRAM = 8 * 1024
        private val IPV4 = Regex("""(\d{1,3}\.){3}\d{1,3}""")

        /** The description at [location] as text, or null for any failure; capped at [Xml.DESCRIPTION_MAX]. */
        fun fetchDescription(location: String, timeoutMs: Int): String? = (fetch(location, timeoutMs) as? Fetch.Text)?.text

        /** One GET, never following a redirect (it could lead off the LAN), capped at [Xml.DESCRIPTION_MAX]. Never throws. */
        fun fetch(location: String, timeoutMs: Int): Fetch {
            var connection: HttpURLConnection? = null
            return try {
                connection = (URL(location).openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    instanceFollowRedirects = false
                    useCaches = false
                    setRequestProperty("User-Agent", Ssdp.USER_AGENT)
                    setRequestProperty("Connection", "close")
                }
                val code = connection.responseCode
                if (code != 200) return Fetch.Status(code)
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                connection.inputStream.use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        if (out.size() > Xml.DESCRIPTION_MAX) return Fetch.TooBig
                    }
                }
                Fetch.Text(Xml.decode(out.toByteArray(), connection.contentType))
            } catch (e: Exception) {
                Fetch.Failed
            } finally {
                connection?.disconnect()
            }
        }
    }
}
