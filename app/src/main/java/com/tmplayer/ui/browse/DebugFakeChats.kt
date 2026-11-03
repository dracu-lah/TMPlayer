package com.tmplayer.ui.browse

import com.tmplayer.BuildConfig
import com.tmplayer.data.ChatCountCalls
import com.tmplayer.data.ChatSummary

/**
 * Debug builds only (HX76): lets the Add chats picker run over a fake 3,000 chat account to
 * measure it on a TV. The source, `FakePicker`, exists in the debug build alone and is found by
 * name, so a release has no trace of it and [on] can never be set there.
 */
internal object DebugFakeChats {
    /** Set from the launch intent (`--ez fakechats true`), debug only. */
    @Volatile
    var on: Boolean = false

    private val source: Class<*>? by lazy {
        if (!BuildConfig.DEBUG) null else runCatching { Class.forName("com.tmplayer.ui.browse.FakePicker") }.getOrNull()
    }

    val active: Boolean get() = BuildConfig.DEBUG && on && source != null

    @Suppress("UNCHECKED_CAST")
    fun chats(): List<ChatSummary> = source!!.getMethod("chats").invoke(null) as List<ChatSummary>

    fun counts(): ChatCountCalls = source!!.getMethod("counts").invoke(null) as ChatCountCalls
}
