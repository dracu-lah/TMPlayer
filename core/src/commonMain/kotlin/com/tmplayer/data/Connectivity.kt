package com.tmplayer.data

import kotlinx.coroutines.flow.StateFlow

/** The platform's best current answer about whether the wider internet is reachable. */
enum class NetworkStatus {
    Unknown,
    Online,
    Offline,
}

/**
 * Process-wide connectivity, as each platform can tell it.
 *
 * Android's `NetworkMonitor` reads a validated network and the metered flag; a desktop with nothing
 * better to go on reports online and unmetered. TDLib has its own connection state as well, and
 * screens combine the two.
 */
interface Connectivity {
    val status: StateFlow<NetworkStatus>

    /**
     * Whether the connection in use is one the viewer pays for by the byte. An unknown network
     * counts as unmetered, because a guess that blocks playback is worse than one that allows it.
     */
    val metered: StateFlow<Boolean>

    /** Unknown is allowed to try; only a confirmed offline state should suppress a request. */
    fun canTryInternet(): Boolean = status.value != NetworkStatus.Offline
}
