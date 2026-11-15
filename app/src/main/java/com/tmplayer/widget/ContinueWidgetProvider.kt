package com.tmplayer.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.tmplayer.data.FormFactor
import com.tmplayer.platform.Background
import com.tmplayer.platform.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The "Continue watching" home screen widget (phone only): the newest unfinished video with its
 * poster, title and a progress bar. A tap opens the player at the resume point, or the app itself
 * when there is nothing to play (nothing to continue, signed out, locked). What is shown is
 * [com.tmplayer.data.ContinueWidget]'s decision; this class only receives the system's calls.
 *
 * Exposed to other apps, as every widget provider must be: this receiver, answering the widget
 * update broadcast and the language change. It takes no data from the intent: whatever a caller
 * sends, the answer is a redraw from the app's own settings, which is the same thing every time.
 *
 * The system refresh ([onUpdate]) draws what is on disk and asks nothing of Telegram or the
 * network, so it is cheap on a cold process: at most every 30 minutes, which Android sets as the
 * floor and does not use to wake the device. The sooner redraws while the app runs are
 * [ContinueWidgetFollower]'s.
 */
class ContinueWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        refresh(context)
    }

    /** A resize or a move to another screen size: the layout is chosen again. */
    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?) {
        refresh(context)
    }

    override fun onEnabled(context: Context) {
        ContinueWidgetFollower.present.value = true
    }

    /** The last widget was removed: the follower goes quiet and the picture kept for it is deleted. */
    override fun onDisabled(context: Context) {
        ContinueWidgetFollower.present.value = false
        WidgetArt.clear(context)
    }

    override fun onReceive(context: Context, intent: Intent?) {
        // Not on a television: the receiver is switched off there ([WidgetAvailability]), and a box
        // that is not caught by that still gets nothing from here.
        if (FormFactor.isTv(context)) return
        super.onReceive(context, intent)
        // The language changed on the device: the text on the tiles is built in it.
        if (intent?.action == Intent.ACTION_LOCALE_CHANGED) refresh(context)
    }

    private fun refresh(context: Context) {
        val app = context.applicationContext
        // Null outside a real broadcast (a test calling the receiver directly).
        val pending = runCatching { goAsync() }.getOrNull()
        Background.scope.launch {
            try {
                // A receiver has about ten seconds; a draw from disk takes a fraction of one.
                withTimeoutOrNull(REFRESH_TIMEOUT_MS) { ContinueWidgetFollower.refresh(app) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w("ContinueWidget", "refresh failed", e)
            } finally {
                pending?.finish()
            }
        }
    }

    private companion object {
        const val REFRESH_TIMEOUT_MS = 8_000L
    }
}
