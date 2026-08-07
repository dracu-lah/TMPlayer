package com.tmplayer

import android.app.Application
import com.tmplayer.data.RemoteImages
import com.tmplayer.data.Td
import com.tmplayer.data.Tmdb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // TDLib takes a moment to open its database; starting here means the login screen is
        // already showing a QR code by the time the user has finished reading the first line.
        Td.start(this)
        // Poster art outlives the process: re-fetching every backdrop on each launch would be a
        // visible stall on a stick that is already slow to start.
        RemoteImages.init(cacheDir)
        // The answers behind that art outlive it by the same reasoning: a film's details are the
        // same next week, and asking again costs a request per card on every cold start.
        Tmdb.init(cacheDir)
    }

    companion object {
        /**
         * For work that has to finish even as a screen is going away (saving where playback
         * stopped, trimming the cache), where a lifecycle scope would be cancelled too early.
         */
        val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
