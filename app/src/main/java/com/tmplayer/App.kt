package com.tmplayer

import android.app.Application
import com.tmplayer.data.Td
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // TDLib takes a moment to open its database; starting here means the login screen is
        // already showing a QR code by the time the user has finished reading the first line.
        Td.start(this)
    }

    companion object {
        /**
         * For work that has to finish even as a screen is going away — saving where playback
         * stopped, trimming the cache — where a lifecycle scope would be cancelled too early.
         */
        val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
