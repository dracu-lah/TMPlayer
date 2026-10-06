package com.tmplayer.player

import android.view.ViewGroup
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.fragment.app.FragmentActivity
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.R
import com.tmplayer.ui.components.FloatingWindow
import com.tmplayer.ui.components.MenuAction
import com.tmplayer.ui.components.PhonePad
import com.tmplayer.ui.components.TmIcons
import com.tmplayer.ui.components.TvMenu
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.floatingSurface
import com.tmplayer.ui.theme.focusRing

/**
 * The television player's More menu, drawn in Compose over a player built from views.
 *
 * The player is a views activity, and the menus everywhere else on the television are [TvMenu],
 * so rather than a second look for the same job this hosts a [TvMenu] in a [ComposeView] laid
 * over the player. The menu draws in a dialog window of its own, which also takes the remote's
 * keys away from the activity while it is up: nothing behind it seeks or pauses by accident.
 *
 * Four pages: the list itself ([PlayerMenu.tvEntries]), the speeds, the sleep timer's lengths,
 * and the remote's keys.
 * The activity keeps every action; this only shows the lines and reports which one was chosen.
 */
class PlayerTvMenu(
    activity: FragmentActivity,
    root: ViewGroup,
    private val title: () -> String,
    private val pictureInPicture: () -> Boolean,
    private val speed: () -> Float,
    /** Whether Save to Downloads is offered, asked each time the menu opens. */
    private val saveToDownloads: () -> Boolean = { false },
    /** Whether Mark as watched is offered at all, and whether the video is on the list already. */
    private val markWatched: () -> Boolean = { false },
    private val watched: () -> Boolean = { false },
    /** Whether Open in another app is offered, asked each time the menu opens. */
    private val openInAnotherApp: () -> Boolean = { true },
    /** Whether volume boost is on, for the line's detail. */
    private val volumeBoost: () -> Boolean = { false },
    /** The running sleep timer's detail ("23 minutes left"), or null when there is none. */
    private val sleepTimer: () -> String? = { null },
    /** A sleep timer chosen from its page: minutes, [SleepTimer.END_OF_VIDEO], or null for off. */
    private val onSleepTimer: (Int?) -> Unit = {},
    /** The episode steps' labels ("Next S01E03"), or null where the chat has no such episode. */
    private val nextEpisode: () -> String? = { null },
    private val previousEpisode: () -> String? = { null },
    private val onEntry: (PlayerMenuEntry) -> Unit,
    private val onSpeed: (Float) -> Unit,
    private val onClosed: () -> Unit,
) {
    private enum class Page { Closed, Main, Speed, Sleep, Keys }

    private val page = mutableStateOf(Page.Closed)

    val isOpen: Boolean get() = page.value != Page.Closed

    init {
        val view = ComposeView(activity).apply {
            // Not named Content: inside apply that resolves to ComposeView.Content, which draws
            // this same lambda again, and the player died of a stack overflow on every TV.
            setContent { TMPlayerTheme { MenuContent() } }
        }
        // Nothing of its own is drawn in the player's window, so it takes no room there; the
        // dialogs it opens are windows of their own.
        root.addView(view, ViewGroup.LayoutParams(0, 0))
    }

    fun open() {
        page.value = Page.Main
    }

    private fun close() {
        page.value = Page.Closed
        onClosed()
    }

    @Composable
    private fun MenuContent() {
        val s = LocalStrings.current
        when (page.value) {
            Page.Closed -> Unit
            Page.Main -> TvMenu(
                title = s.commonMore,
                subtitle = title().ifBlank { null },
                actions = PlayerMenu.tvEntries(
                    pictureInPicture(),
                    saveToDownloads(),
                    markWatched(),
                    openInAnotherApp(),
                    nextEpisode = nextEpisode() != null,
                    previousEpisode = previousEpisode() != null,
                ).map { action(it) },
                onDismiss = ::close,
            )
            Page.Speed -> TvMenu(
                title = s.playerPlaybackSpeed,
                actions = PlaybackSpeed.CHOICES.map { choice ->
                    val current = choice == speed()
                    MenuAction(
                        label = PlaybackSpeed.label(choice),
                        icon = if (current) Icons.Filled.Check else ImageVector.vectorResource(R.drawable.ic_speed),
                        detail = if (current) s.playerSpeedCurrent else null,
                    ) {
                        onSpeed(choice)
                        close()
                    }
                },
                onDismiss = { page.value = Page.Main },
            )
            Page.Sleep -> TvMenu(
                title = s.playerSleepTimer,
                subtitle = sleepTimer(),
                actions = buildList {
                    if (sleepTimer() != null) {
                        add(MenuAction(s.playerTurnOff, Icons.Filled.Close, detail = s.playerKeepPlaying) {
                            onSleepTimer(null)
                            close()
                        })
                    }
                    SleepTimer.CHOICES.forEach { minutes ->
                        add(
                            MenuAction(SleepTimer.label(minutes), TmIcons.Clock) {
                                onSleepTimer(minutes)
                                close()
                            },
                        )
                    }
                },
                onDismiss = { page.value = Page.Main },
            )
            Page.Keys -> RemoteKeysSheet(onDismiss = { page.value = Page.Main })
        }
    }

    @Composable
    private fun action(entry: PlayerMenuEntry): MenuAction {
        val s = LocalStrings.current
        return when (entry) {
            PlayerMenuEntry.NextEpisode -> MenuAction(
                label = nextEpisode() ?: s.playerNextEpisode,
                icon = ImageVector.vectorResource(R.drawable.ic_player_next),
            ) { choose(entry) }
            PlayerMenuEntry.PreviousEpisode -> MenuAction(
                label = previousEpisode() ?: s.playerPreviousEpisode,
                icon = ImageVector.vectorResource(R.drawable.ic_player_previous),
            ) { choose(entry) }
            PlayerMenuEntry.PlaybackDetails -> MenuAction(s.playerPlaybackDetails, Icons.Filled.Info) { choose(entry) }
            PlayerMenuEntry.StartOver -> MenuAction(s.playerStartOver, Icons.Filled.Refresh) { choose(entry) }
            PlayerMenuEntry.Speed -> MenuAction(
                label = s.playerSpeed,
                icon = ImageVector.vectorResource(R.drawable.ic_speed),
                detail = PlaybackSpeed.label(speed()),
            ) { page.value = Page.Speed }
            PlayerMenuEntry.VolumeBoost -> MenuAction(
                label = s.playerVolumeBoost,
                icon = ImageVector.vectorResource(R.drawable.ic_volume),
                detail = if (volumeBoost()) s.playerVolumeBoostOnDetail else s.commonOff,
            ) { choose(entry) }
            PlayerMenuEntry.SleepTimer -> MenuAction(
                label = s.playerSleepTimer,
                icon = TmIcons.Clock,
                detail = sleepTimer() ?: s.commonOff,
            ) { page.value = Page.Sleep }
            PlayerMenuEntry.SaveToDownloads -> MenuAction(
                label = s.playerSaveToDownloads,
                icon = TmIcons.Download,
                detail = s.playerSaveToDownloadsDetail,
            ) { choose(entry) }
            PlayerMenuEntry.MarkWatched -> if (watched()) {
                MenuAction(
                    label = s.playerMarkUnwatched,
                    icon = Icons.Filled.Close,
                    detail = s.playerMarkUnwatchedDetail,
                ) { choose(entry) }
            } else {
                MenuAction(
                    label = s.playerMarkWatched,
                    icon = Icons.Filled.Check,
                    detail = s.playerMarkWatchedDetail,
                ) { choose(entry) }
            }
            PlayerMenuEntry.PictureInPicture ->
                MenuAction(s.playerPictureInPicture, ImageVector.vectorResource(R.drawable.ic_pip)) { choose(entry) }
            PlayerMenuEntry.OpenInAnotherApp ->
                MenuAction(s.playerOpenInAnotherApp, Icons.AutoMirrored.Filled.ExitToApp) { choose(entry) }
            PlayerMenuEntry.RemoteKeys ->
                MenuAction(s.playerRemoteKeys, Icons.Filled.Info, detail = s.playerRemoteKeysDetail) { page.value = Page.Keys }
        }
    }

    /** Closes the menu first, so whatever the entry opens (a dialog, another app) is on top. */
    private fun choose(entry: PlayerMenuEntry) {
        close()
        onEntry(entry)
    }
}

/**
 * The remote's keys, as a panel in the same style as [TvMenu].
 *
 * Each row takes focus without doing anything when pressed, which is what lets the D-pad walk a
 * table taller than the screen: focus moving down scrolls the row it lands on into view.
 */
@Composable
private fun RemoteKeysSheet(onDismiss: () -> Unit) {
    val s = LocalStrings.current
    val first = remember { FocusRequester() }
    FloatingWindow(onDismiss = onDismiss) {
        val panel = min(maxWidth - PhonePad.Side * 2, KEYS_MAX)
        Column(
            Modifier
                .width(panel)
                .floatingSurface(FloatingTone.sheet)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                s.playerRemoteKeys,
                style = MaterialTheme.typography.titleLarge,
                color = Tone.text,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
            RemoteKeys.ROWS.forEachIndexed { index, (key, does) ->
                KeyRow(key, does, if (index == 0) Modifier.focusRequester(first) else Modifier)
            }
            Text(
                s.playerRemoteKeysClose,
                style = MaterialTheme.typography.bodySmall,
                color = Tone.muted,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            )
        }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    }
}

@Composable
private fun KeyRow(key: String, does: String, modifier: Modifier) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val background by animateColorAsState(
        targetValue = if (focused) Tone.surfaceHigh else Color.Transparent,
        animationSpec = tween(140),
        label = "keyRow",
    )
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.Medium))
            .background(background)
            .focusRing(focused, RoundedCornerShape(Corner.Medium))
            .focusable(interactionSource = interactions)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(key, style = MaterialTheme.typography.titleMedium, color = Tone.text)
        Text(does, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
    }
}

/** As wide as the key table gets: wider than the menu, because each row is a sentence. */
private val KEYS_MAX = 640.dp
