package com.tmplayer.data

import android.content.Context

// Android's form factor rules (ui/src/androidMain) on iOS: an iPhone or iPad is always the touch
// form. The layout choice is kept so Settings reads back what was chosen, but TV is never applied.

enum class InterfaceChoice(val key: String) {
    Auto("auto"),
    Tv("tv"),
    Phone("phone"),
    ;

    companion object {
        fun of(key: String?): InterfaceChoice = entries.firstOrNull { it.key == key } ?: Auto
    }
}

object FormFactor {
    private var chosen = InterfaceChoice.Auto
    private var nudgeOff = false

    fun isTv(context: Context): Boolean = false

    /** Android's TV resources for a device set to the TV layout; iOS has the touch layout only. */
    fun uiModeOverride(context: Context): android.content.res.Configuration? = null
    fun form(context: Context): DeviceForm = DeviceForm.Phone
    fun override(value: Boolean) = Unit
    fun choice(context: Context): InterfaceChoice = chosen
    fun setChoice(context: Context, choice: InterfaceChoice) {
        chosen = choice
    }
    fun autoIsTv(context: Context): Boolean = false
    fun guessed(context: Context): Boolean = false
    fun desktopLike(context: Context): Boolean = false
    fun describe(context: Context): String = "iOS, ${platform.UIKit.UIDevice.currentDevice.model}, touch"
    fun nudgeNever(context: Context): Boolean = nudgeOff
    fun setNudgeNever(context: Context) {
        nudgeOff = true
    }
}

object DeviceQuirks {
    fun isFireTv(context: Context): Boolean = false
    fun remote(context: Context): RemoteQuirks = RemoteQuirks.of(FireOs.Kind.None, false, android.os.Build.VERSION.SDK_INT)
    fun trickplayMemory(context: Context): Boolean = true

    /** The promo fixture's Fire OS answer: an iPhone or iPad is never a Fire device. */
    fun override(kind: FireOs.Kind) = Unit
}
