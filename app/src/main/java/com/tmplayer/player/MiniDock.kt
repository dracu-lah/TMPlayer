package com.tmplayer.player

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.tmplayer.data.FormFactor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the browse shell can see and do of a docked player (U8, the phone's mini player; see
 * `docs/mini-player-phone.md`). The player stays in its [PlayerActivity], out of sight in a task
 * of its own, and answers through this; every call is on the main thread, where the player lives.
 */
interface DockedPlayer {
    /** The chat the video is from, for the kids lock. */
    val chatId: Long

    /** The bar's one line: the show or the video, and the episode. */
    val title: String

    /** Playing, or about to (buffering): what the play and pause button shows. */
    val playing: Boolean
    val positionMs: Long
    val durationMs: Long

    /** On a TV through Cast or DLNA: the bar opens the player's cast controls instead. */
    val casting: Boolean

    /** The bar's play or pause; a player waiting for an answer is opened instead. */
    fun togglePlay()

    /** Brings the player's own task to the front, at the same place. False when it is gone. */
    fun expand(context: Context): Boolean

    /** The X or a swipe down: the player is paused and finished, which releases everything it held. */
    fun dismiss()

    /** The browse shell left the screen too: the background audio setting decides now. */
    fun shellHidden()
}

/**
 * The one link between a docked [PlayerActivity] and the browse shell, for the length of the
 * process. Nothing here outlives it: after a process death there is no bar, and no player behind
 * one. The player docks itself as it leaves the screen and undocks as it comes back or goes away,
 * so a reference held here never outlives the activity.
 */
object MiniDock {
    /** On a notification's tap, through the shell: bring the docked player back to the front. */
    const val EXTRA_EXPAND = "com.tmplayer.extra.EXPAND_DOCKED_PLAYER"

    private val state = MutableStateFlow<DockedPlayer?>(null)

    /** The docked player, or null with nothing docked. */
    val docked: StateFlow<DockedPlayer?> = state.asStateFlow()

    /**
     * The setting as the shell last read it, for the launch flags ([PlayerLaunch]); on until
     * read otherwise, as the setting itself is. The player reads the setting again before docking.
     */
    @Volatile
    var enabled: Boolean = true

    fun dock(player: DockedPlayer) {
        state.value = player
    }

    /** Takes [player] off the dock; another player docked since stays. */
    fun undock(player: DockedPlayer) {
        state.compareAndSet(player, null)
    }

    /**
     * The bar's tap or the notification's: the bar goes at once and the player's task comes to
     * the front. A player whose task cannot be found is dismissed, so nothing is left holding a
     * decoder nobody can reach.
     */
    fun expand(context: Context): Boolean {
        val player = state.value ?: return false
        state.value = null
        val shown = runCatching { player.expand(context) }.getOrDefault(false)
        if (!shown) {
            Log.w("TMPlayer", "Mini player: the player's task was not found, closing it")
            runCatching { player.dismiss() }
        }
        return shown
    }

    /** Dismisses the docked player, if there is one. */
    fun dismiss() {
        val player = state.value ?: return
        state.value = null
        runCatching { player.dismiss() }
    }

    fun togglePlay() {
        runCatching { state.value?.togglePlay() }
    }

    /** The shell went out of sight (not for a rotation): passed on to the docked player. */
    fun shellHidden() {
        runCatching { state.value?.shellHidden() }
    }

    /**
     * Brings the task [taskId] of this app to the front, through [ActivityManager.AppTask]
     * (Android 6, no permission for the app's own tasks). False when there is no such task.
     */
    fun moveTaskToFront(context: Context, taskId: Int): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val tasks = runCatching { manager.appTasks }.getOrNull().orEmpty()
        val task = tasks.firstOrNull { runCatching { idOf(it.taskInfo) }.getOrNull() == taskId } ?: return false
        return runCatching { task.moveToFront() }.isSuccess
    }

    private fun idOf(info: ActivityManager.RecentTaskInfo?): Int? = if (info == null) null else
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.taskId else @Suppress("DEPRECATION") info.id
}

/**
 * How the browse shell opens the player. With the mini player on, on a phone, the player opens in
 * a task of its own (its manifest affinity), which is what lets Back leave it without finishing
 * it. Every other launch (the TV, the player's own next episode, the setting off) adds nothing and
 * lands in the caller's task as it always has.
 */
object PlayerLaunch {

    /** Whether a launch from the shell takes a task of its own. */
    fun ownTask(tv: Boolean, enabled: Boolean): Boolean = MiniPlayerState.allowedOn(tv) && enabled

    /** The flags such a launch adds: a fresh task each time, so there are never two players. */
    const val OWN_TASK_FLAGS: Int = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK

    /**
     * [intent] made ready to start from the shell. A docked video goes first (one player, one
     * decoder), and in a task of its own the player brings the shell back as it closes
     * ([PlayerActivity.EXTRA_OUTSIDE_APP_TASK]), the rule the corner window's players follow.
     */
    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    fun fromBrowse(context: Context, intent: Intent): Intent {
        MiniDock.dismiss()
        if (ownTask(FormFactor.isTv(context), MiniDock.enabled)) {
            intent.addFlags(OWN_TASK_FLAGS)
            intent.putExtra(PlayerActivity.EXTRA_OUTSIDE_APP_TASK, true)
        }
        return intent
    }
}
