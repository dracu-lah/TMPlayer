package com.tmplayer.widget

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.tmplayer.data.FormFactor
import com.tmplayer.platform.Logger

/**
 * Whether the Continue watching widget's receiver exists on this device.
 *
 * The manifest holds one constant value (enabled): a resource the manifest names cannot vary by
 * configuration (lint's ManifestResource, which failed `lintVitalRelease`), so the old
 * `values-television/bools.xml` override was never used. A television has no widget host, so the
 * receiver is switched off there once, at start, and switched back to the manifest's value on any
 * other layout (the Interface setting can move a device from one to the other).
 */
object WidgetAvailability {

    /**
     * The state to set, or null when the receiver already is where it should be.
     *
     * Not on a television: [PackageManager.COMPONENT_ENABLED_STATE_DISABLED]. Elsewhere:
     * [PackageManager.COMPONENT_ENABLED_STATE_DEFAULT], the manifest's own "enabled". A state the
     * receiver already has (an enabled one counts as the default) is left alone, so a start costs
     * one read and no write.
     */
    fun change(current: Int, television: Boolean): Int? = when {
        television && current != PackageManager.COMPONENT_ENABLED_STATE_DISABLED ->
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        !television && current == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ->
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        else -> null
    }

    /** Applies [change] to the receiver. Never throws: a failure only leaves the widget as it was. */
    fun sync(context: Context, television: Boolean = FormFactor.isTv(context)) {
        try {
            val packages = context.packageManager
            val receiver = ComponentName(context, ContinueWidgetProvider::class.java)
            val next = change(packages.getComponentEnabledSetting(receiver), television) ?: return
            packages.setComponentEnabledSetting(receiver, next, PackageManager.DONT_KILL_APP)
            Logger.i("tm-widget", "Continue watching widget receiver ${if (television) "disabled on a television" else "enabled"}")
        } catch (e: Exception) {
            Logger.w("tm-widget", "Could not set the widget receiver's state: ${e.message}")
        }
    }
}
