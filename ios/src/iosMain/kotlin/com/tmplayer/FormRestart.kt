package com.tmplayer

/**
 * Switching between the phone and TV layouts restarts Android's activity in a process of its own.
 * iOS is always the touch layout, so there is nothing to switch to and no such process.
 */
object FormRestart {
    @Suppress("UNUSED_PARAMETER")
    fun switchTo(activity: android.app.Activity, choice: com.tmplayer.data.InterfaceChoice) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun inRestartProcess(app: android.app.Application): Boolean = false
}
