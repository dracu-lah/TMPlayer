package com.tmplayer.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Android's best current answer about whether the wider internet is reachable. */
enum class NetworkStatus {
    Unknown,
    Online,
    Offline,
}

/**
 * Process-wide connectivity, based on a validated network rather than Wi-Fi being switched on.
 *
 * TDLib has its own connection state as well. Screens combine the two because a live TDLib
 * connection is stronger evidence than an occasionally late Android connectivity callback.
 */
object NetworkMonitor {

    private val _status = MutableStateFlow(NetworkStatus.Unknown)
    val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    /**
     * Whether the connection currently in use is one the viewer pays for by the byte.
     *
     * Nothing in the app distinguished the two before, so a multi-gigabyte remux would start
     * pulling over cellular as readily as over the house Wi-Fi, with no warning and no way to say
     * no. Defaults to false: an unknown network is treated as unmetered, because a guess that
     * blocks playback is worse than a guess that allows it.
     */
    private val _metered = MutableStateFlow(false)
    val metered: StateFlow<Boolean> = _metered.asStateFlow()

    @Volatile
    private var started = false

    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true

            val manager = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val initial = manager.activeNetwork?.let(manager::getNetworkCapabilities)
            _status.value = statusOf(initial)
            _metered.value = meteredOf(initial)
            manager.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(
                        network: Network,
                        capabilities: NetworkCapabilities,
                    ) {
                        _status.value = statusOf(capabilities)
                        _metered.value = meteredOf(capabilities)
                    }

                    override fun onLost(network: Network) {
                        _status.value = NetworkStatus.Offline
                        _metered.value = false
                    }
                },
            )
        }
    }

    /** Unknown is allowed to try; only a confirmed offline state should suppress a request. */
    fun canTryInternet(): Boolean = _status.value != NetworkStatus.Offline

    /** Absent capabilities mean an unknown network, which is read as unmetered on purpose. */
    private fun meteredOf(capabilities: NetworkCapabilities?): Boolean =
        capabilities != null &&
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

    private fun statusOf(capabilities: NetworkCapabilities?): NetworkStatus =
        if (
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        ) {
            NetworkStatus.Online
        } else {
            NetworkStatus.Offline
        }
}
