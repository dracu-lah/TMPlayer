package com.tmplayer.desktop

import com.tmplayer.data.Connectivity
import com.tmplayer.data.NetworkStatus
import com.tmplayer.platform.Background
import com.tmplayer.platform.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Whether this computer has a network at all, for the offline banner.
 *
 * The JVM has no "network changed" event and no portable notion of a validated connection, so this
 * looks at the network interfaces every few seconds: one that is up (which on Linux and macOS also
 * means it has a carrier), is not the loopback, and holds an address that is neither loopback nor
 * link local means Online; none means Offline. It can only err towards Online (a VPN or a virtual
 * adapter that stays up with the cable out), which is the safe side: the banner says Offline only
 * when this says so *and* TDLib has no connection either, the same rule the phone uses.
 *
 * Nothing on the desktop is metered as far as the JVM can tell.
 */
object DesktopConnectivity : Connectivity {

    private val _status = MutableStateFlow(NetworkStatus.Unknown)
    override val status: StateFlow<NetworkStatus> = _status.asStateFlow()
    override val metered: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

    private var watcher: Job? = null

    /** Starts the look every few seconds, once per process; later calls do nothing. */
    @Synchronized
    fun start() {
        if (watcher != null) return
        watcher = Background.scope.launch {
            while (isActive) {
                _status.value = runCatching { statusOf(interfaces()) }
                    .onFailure { Logger.w(TAG, "could not list the network interfaces: ${it.message}") }
                    .getOrDefault(NetworkStatus.Unknown)
                delay(POLL_MS)
            }
        }
    }

    /** One network interface as far as the decision goes; a value so the rule can be tested. */
    internal data class Interface(val up: Boolean, val loopback: Boolean, val addresses: List<InetAddress>)

    /** Online when any interface could carry traffic beyond this machine, Offline otherwise. */
    internal fun statusOf(interfaces: List<Interface>): NetworkStatus {
        val usable = interfaces.any { iface ->
            iface.up && !iface.loopback && iface.addresses.any { !it.isLoopbackAddress && !it.isLinkLocalAddress }
        }
        return if (usable) NetworkStatus.Online else NetworkStatus.Offline
    }

    private fun interfaces(): List<Interface> =
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map { ni ->
            Interface(
                up = runCatching { ni.isUp }.getOrDefault(false),
                loopback = runCatching { ni.isLoopback }.getOrDefault(false),
                addresses = ni.inetAddresses.toList(),
            )
        }

    private const val TAG = "DesktopConnectivity"
    private const val POLL_MS = 3_000L
}
