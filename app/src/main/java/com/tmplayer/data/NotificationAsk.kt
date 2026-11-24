package com.tmplayer.data

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Android 13's question about notifications, and the way back after a no (WP-38).
 *
 * The question is asked through the Activity Result API ([launcher], put here by the shell's
 * composition), so the answer is read: the "asked" mark is written when the answer comes, not before
 * it, and a refusal that Android will not ask again about is told apart from a question not yet put
 * ([canAsk]). After two refusals nothing shows when the permission is requested, so the only way
 * back is the system's own settings page for the app ([openSettings]).
 */
object NotificationAsk {
    private const val ONCE_PREFS = "once"
    private const val ASKED = "asked_notifications"

    /** Puts the system's question; null until the shell has composed. */
    @Volatile
    var launcher: (() -> Unit)? = null

    /** True between putting the question and its answer. */
    @Volatile
    var pending = false
        private set

    fun asked(context: Context): Boolean =
        context.getSharedPreferences(ONCE_PREFS, Context.MODE_PRIVATE).getBoolean(ASKED, false)

    private val answerTally = MutableStateFlow(0)

    /**
     * How many answers have come in this process: a screen that shows the question's result (the
     * first run's notifications step) reads it as a value, so an answer that arrives before the
     * screen is composed is not lost.
     */
    val answers: StateFlow<Int> get() = answerTally

    /** The last answer was yes, or null before one came in this process. */
    @Volatile
    var lastGranted: Boolean? = null
        private set

    /** The answer came (either way): it is not asked about again by itself. [granted] is what was chosen, when known. */
    fun answered(context: Context, granted: Boolean? = null) {
        pending = false
        context.getSharedPreferences(ONCE_PREFS, Context.MODE_PRIVATE).edit().putBoolean(ASKED, true).apply()
        lastGranted = granted
        answerTally.value += 1
    }

    /**
     * Whether the system would still show its question: Android 13 or later, not granted, and
     * either never refused or refused once (Android says so through the rationale flag).
     */
    fun canAsk(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return false
        val activity = context.findActivity() ?: return false
        return !asked(context) || activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Asks now if the system will show its question; otherwise nothing (the Settings card is the way back). */
    fun request(context: Context) {
        if (!canAsk(context)) return
        val launch = launcher ?: return
        pending = true
        if (runCatching { launch() }.isFailure) pending = false
    }

    /**
     * The system's notification settings for this app: the page with the switch (Android 8 and
     * later), the app's details page when that one is not there.
     */
    fun openSettings(context: Context) {
        val app = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        if (context !is Activity) {
            app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            details.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(app) }.recoverCatching { context.startActivity(details) }
    }

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }
}
