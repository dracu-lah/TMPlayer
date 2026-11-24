package com.tmplayer.data

/**
 * iOS has no launcher shortcuts from the shared code (Home Screen quick actions are a later job of
 * the Swift shell); the same name as Android's, which the shell's composition calls (WP-42).
 */
object LauncherShortcuts {
    const val OPEN_SEARCH = "search"
    const val OPEN_CONTINUE = "continue"

    fun supported(): Boolean = false

    fun planIds(hidden: Boolean, lastChatId: Long, lastChatTitle: String?): List<String> = emptyList()

    fun hiddenFor(lock: AppLockSettings?, profileHidden: Boolean, profileHasPin: Boolean, mayWrite: Boolean): Boolean = true

    @Suppress("UNUSED_PARAMETER")
    fun follow(context: Any?, scope: kotlinx.coroutines.CoroutineScope): kotlinx.coroutines.Job = kotlinx.coroutines.Job()

    @Suppress("UNUSED_PARAMETER")
    fun publish(context: Any?, lastChatId: Long, lastChatTitle: String?, hidden: Boolean) = Unit
}
