package com.tmplayer.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.widget.RemoteViews
import com.tmplayer.MainActivity
import com.tmplayer.R
import com.tmplayer.data.ContinueWidget
import com.tmplayer.data.ContinueWidget.Size
import com.tmplayer.data.ContinueWidget.Tile
import com.tmplayer.data.WatchNextPublisher
import com.tmplayer.i18n.L

/**
 * Draws a [Tile] as the widget's [RemoteViews]. Nothing is decided here: the tile says what to
 * show ([ContinueWidget.decide]) and the size says which layout.
 *
 * A launcher inflates these in its own process from this app's resources, so the colours follow
 * the system's night mode and the text is whatever language the app held when it was built;
 * [ContinueWidgetProvider] builds them again when the language changes.
 */
internal object ContinueWidgetViews {

    fun layoutFor(size: Size): Int = when (size) {
        Size.Compact -> R.layout.widget_continue_compact
        Size.Regular -> R.layout.widget_continue_regular
    }

    /** [poster] is only drawn on a video tile in the regular layout; anything else gets the plate. */
    fun build(context: Context, tile: Tile, size: Size, poster: Bitmap?): RemoteViews {
        // profile-notifications: the widget's tile, Locked while the profile is hidden (ContinueWidget.lockedBy)
        val views = RemoteViews(context.packageName, layoutFor(size))
        val regular = size == Size.Regular
        when (tile) {
            is Tile.Video -> {
                views.setTextViewText(R.id.widget_title, tile.title)
                views.setTextViewText(R.id.widget_detail, detail(tile))
                views.setViewVisibility(R.id.widget_progress, View.VISIBLE)
                views.setProgressBar(R.id.widget_progress, 100, tile.percent, false)
                views.setContentDescription(
                    R.id.widget_root,
                    L.widgetPlayDescription(title = tile.title, percent = tile.percent),
                )
            }
            Tile.Nothing -> plain(views, L.widgetNothing, L.widgetOpenHint)
            Tile.SignIn -> plain(views, L.widgetSignIn, L.widgetOpenHint)
            Tile.Locked -> plain(views, L.widgetLocked, L.widgetLockedHint)
        }
        if (regular) {
            if (tile is Tile.Video && poster != null) {
                views.setImageViewBitmap(R.id.widget_poster, poster)
            } else {
                views.setImageViewResource(R.id.widget_poster, R.drawable.widget_plate)
            }
        } else {
            // The strip is one line of text and a bar: the detail line has no room under the title.
            views.setViewVisibility(R.id.widget_detail, View.GONE)
        }
        views.setOnClickPendingIntent(R.id.widget_root, openIntent(context, tile))
        return views
    }

    private fun plain(views: RemoteViews, title: String, detail: String) {
        views.setTextViewText(R.id.widget_title, title)
        views.setTextViewText(R.id.widget_detail, detail)
        views.setViewVisibility(R.id.widget_progress, View.GONE)
        views.setContentDescription(R.id.widget_root, "$title. $detail")
    }

    /** The chat and the time left, in the app's language: "Nature  ·  22m left". Just the chat when the length is unknown. */
    internal fun detail(tile: Tile.Video): String {
        val chat = tile.video.chatTitle
        if (tile.remainingSec <= 0L) return chat
        val time = L.messages.formatter.duration(tile.remainingSec)
        return if (chat.isBlank()) time else L.continueLeft(chat = chat, time = time)
    }

    /**
     * What a tap does. A video opens the player at its resume point through the same launch the TV's
     * "Play next" row uses ([WatchNextPublisher.intentFor]); every other tile opens the app itself,
     * which is where the sign in and the locks are.
     */
    internal fun intentFor(context: Context, tile: Tile): Intent {
        val base = if (tile is Tile.Video) {
            WatchNextPublisher.intentFor(context, tile.video)
        } else {
            Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        }
        return base.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun openIntent(context: Context, tile: Tile): PendingIntent {
        val code = if (tile is Tile.Video) REQUEST_PLAY else REQUEST_OPEN
        return PendingIntent.getActivity(
            context,
            code,
            intentFor(context, tile),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private const val REQUEST_PLAY = 7_201
    private const val REQUEST_OPEN = 7_202
}
