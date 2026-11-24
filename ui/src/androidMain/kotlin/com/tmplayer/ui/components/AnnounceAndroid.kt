package com.tmplayer.ui.components

/**
 * Nothing on Android: the status line is a polite live region, which TalkBack reads when it
 * changes. `View.announceForAccessibility` is deprecated for exactly that reason.
 */
@Suppress("UNUSED_PARAMETER")
internal fun platformAnnounce(text: String) = Unit
