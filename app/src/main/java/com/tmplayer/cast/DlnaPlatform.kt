package com.tmplayer.cast

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.tmplayer.data.startForegroundServiceCompat
import java.net.Inet4Address

/**
 * What a DLNA cast needs from Android, kept apart from [DlnaCastSupport] so its logic is tested
 * without a device: the multicast lock the Wi-Fi chip wants before it passes SSDP replies, whether
 * there is a local network at all, and the foreground service that keeps the cast server alive
 * with the screen off ([DlnaCastService]).
 */
interface DlnaPlatform {
    /** Held only while the picker is open; [releaseMulticast] when it closes or discovery ends. */
    fun holdMulticast()
    fun releaseMulticast()

    /** A Wi-Fi, Ethernet or hotspot network with a private IPv4 address to find a TV on. */
    fun hasLan(): Boolean

    /** A cast is playing or paused: the foreground service, the Wi-Fi lock and the wake lock, until [releaseKeepAlive]. */
    fun keepAlive(title: String, device: String)
    fun releaseKeepAlive()
}

class AndroidDlnaPlatform(context: Context) : DlnaPlatform {
    private val app = context.applicationContext
    private var multicast: WifiManager.MulticastLock? = null

    @Synchronized
    override fun holdMulticast() {
        if (multicast?.isHeld == true) return
        multicast = runCatching {
            val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifi.createMulticastLock("tmplayer-dlna").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Log.w(TAG, "No multicast lock", it) }.getOrNull()
    }

    @Synchronized
    override fun releaseMulticast() {
        runCatching { multicast?.takeIf { it.isHeld }?.release() }
        multicast = null
    }

    override fun hasLan(): Boolean =
        com.tmplayer.cast.LanAddress.candidates().any { it.address is Inet4Address && CastServer.isLocalNetwork(it.address) }

    override fun keepAlive(title: String, device: String) {
        runCatching { app.startForegroundServiceCompat(DlnaCastService.intent(app, title, device)) }
            .onFailure { Log.w(TAG, "The cast service could not start; the cast lives while the screen is on", it) }
    }

    override fun releaseKeepAlive() {
        runCatching { app.stopService(DlnaCastService.intent(app, "", "")) }
    }

    private companion object {
        const val TAG = "TMPlayerDlna"
    }
}
