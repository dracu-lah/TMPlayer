package com.tmplayer.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * For work that has to finish even as a screen is going away (saving where playback stopped,
 * writing the chat list snapshot, trimming the cache), where a screen's scope would be cancelled
 * too early. One per process, on either platform; Android's `App.backgroundScope` is this one.
 */
object Background {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
