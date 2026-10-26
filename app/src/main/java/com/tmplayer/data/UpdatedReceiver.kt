package com.tmplayer.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tmplayer.BuildConfig
import com.tmplayer.MainActivity
import com.tmplayer.platform.Background
import com.tmplayer.platform.Logger
import kotlinx.coroutines.launch
import java.io.File

/**
 * Runs in the new version once Android has replaced the old one (`MY_PACKAGE_REPLACED`), which
 * Android delivers to the app's own manifest receiver whatever its background limits (HX5).
 *
 * An update installed in a session on Android 12 and later closes the app with no "Open" button
 * to bring it back. Android 10 and later do not let an app start an activity from the background,
 * this broadcast included, and an exact alarm would need a permission the app has no other use
 * for, so the way back is a notification, "TMPlayer updated to 1.29.0", whose tap opens it. After
 * an install the viewer started, the activity is also asked for directly: where Android allows it
 * (a vendor's build that lets it through) the app simply comes back, and the shell takes the
 * notification down again. On Android 6 to 11 the system installer's own screen has Open, so
 * nothing is posted. Either way the downloaded APK is deleted here, and the shell says "Updated to"
 * once on the next launch ([AfterUpdate.onLaunch]).
 */
class UpdatedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        Background.scope.launch {
            try {
                runCatching { AfterUpdate.afterReplace(context.applicationContext) }
                    .onFailure { Logger.w("UpdatedReceiver", "after the update", it) }
            } finally {
                pending.finish()
            }
        }
    }
}

/** What happens after TMPlayer has updated itself (HX5). */
object AfterUpdate {

    /** The broadcast after the replace: clean up, and bring the app back after a session install. */
    suspend fun afterReplace(context: Context) {
        deleteDownloads(context)
        if (!Updates.enabled) return
        val stored = SettingsStore(context).updateInstallingNow()
        if (!UpdateRestart.notifyAfterReplace(stored, BuildConfig.VERSION_NAME)) return
        val pending = UpdateRestart.Pending.decode(stored) ?: return
        if (pending.foreground) {
            runCatching {
                context.startActivity(
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        if (BackgroundChecks.canNotify(context)) AppNotices.postUpdated(context, BuildConfig.VERSION_NAME)
    }

    /**
     * The shell's first look after a launch: the version to say "Updated to" for, once, or null.
     * Forgets the record when it is done with or stale, and takes the notification down.
     */
    suspend fun onLaunch(context: Context): String? {
        if (!Updates.enabled) return null
        val settings = SettingsStore(context)
        val stored = settings.updateInstallingNow()
        val launch = UpdateRestart.onLaunch(stored, BuildConfig.VERSION_NAME, System.currentTimeMillis())
        if (launch.clear) settings.setUpdateInstalling("")
        if (launch.updatedTo != null) {
            AppNotices.cancelUpdated(context)
            deleteDownloads(context)
        }
        return launch.updatedTo
    }

    /** The APK is installed, or was never going to be: no second copy of the app in the cache. */
    private fun deleteDownloads(context: Context) {
        runCatching { File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() } }
    }
}
