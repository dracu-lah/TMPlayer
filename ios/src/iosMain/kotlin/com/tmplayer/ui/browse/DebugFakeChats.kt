package com.tmplayer.ui.browse

import com.tmplayer.data.ChatCountCalls
import com.tmplayer.data.ChatSummary

/** Android's debug builds can show made up chats through a debug-only class; iOS builds never do. */
internal object DebugFakeChats {
    var on: Boolean = false
    val active: Boolean get() = false
    fun chats(): List<ChatSummary> = emptyList()
    fun counts(): ChatCountCalls = throw UnsupportedOperationException("No fake chats outside Android debug builds")
}
