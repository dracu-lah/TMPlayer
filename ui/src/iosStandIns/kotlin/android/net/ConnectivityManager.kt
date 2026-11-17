package android.net

/**
 * Android's ConnectivityManager as far as the player reads it: whether the line costs by the byte.
 * iOS's path monitor knows (cellular, a hotspot, Low Data Mode); it is :ios's NetworkMonitor, which
 * sets [meteredNow] when it starts. Until it does, a line is not metered, as Android's unknown reads.
 */
class ConnectivityManager internal constructor() {
    val isActiveNetworkMetered: Boolean get() = meteredNow()

    companion object {
        /** Set by the network monitor; the answer to [isActiveNetworkMetered]. */
        var meteredNow: () -> Boolean = { false }

        internal val Shared = ConnectivityManager()
    }
}
