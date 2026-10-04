package com.tmplayer.player

import android.view.ViewGroup
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.fragment.app.FragmentActivity
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.R
import com.tmplayer.ui.components.MenuAction
import com.tmplayer.ui.components.TmIcons
import com.tmplayer.ui.components.PhonePad
import com.tmplayer.ui.components.TvMenu
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.focusRing

/**
 * The television player's More menu, drawn in Compose over a player built from views.
 *
 * The player is a views activity, and the menus everywhere else on the television are [TvMenu],
 * so rather than a second look for the same job this hosts a [TvMenu] in a [ComposeView] laid
 * over the player. The menu draws in a dialog window of its own, which also takes the remote's
 * keys away from the activity while it is up: nothing behind it seeks or pauses by accident.
 *
 * Three pages: the list itself ([PlayerMenu.tvEntries]), the speeds, and the remote's keys.
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
    private val onEntry: (PlayerMenuEntry) -> Unit,
    private val onSpeed: (Float) -> Unit,
    private val onClosed: () -> Unit,
) {
    private enum class Page { Closed, Main, Speed, Keys }

    private val page = mutableStateOf(Page.Closed)

    val isOpen: Boolean get() = page.value != Page.Closed

    init {
        val view = ComposeView(activity).apply {
            setContent { TMPlayerTheme { Content() } }
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
    private fun Content() {
        when (page.value) {
            Page.Closed -> Unit
            Page.Main -> TvMenu(
                title = "More",
                subtitle = title().ifBlank { null },
                actions = PlayerMenu.tvEntries(pictureInPicture(), saveToDownloads()).map { action(it) },
                onDismiss = ::close,
            )
            Page.Speed -> TvMenu(
                title = "Playback speed",
                actions = PlaybackSpeed.CHOICES.map { choice ->
                    val current = choice == speed()
                    MenuAction(
                        label = PlaybackSpeed.label(choice),
                        icon = if (current) Icons.Filled.Check else ImageVector.vectorResource(R.drawable.ic_speed),
                        detail = if (current) "Playing at this speed" else null,
                    ) {
                        onSpeed(choice)
                        close()
                    }
                },
                onDismiss = { page.value = Page.Main },
            )
            Page.Keys -> RemoteKeysSheet(onDismiss = { page.value = Page.Main })
        }
    }

    @Composable
    private fun action(entry: PlayerMenuEntry): MenuAction = when (entry) {
        PlayerMenuEntry.PlaybackDetails -> MenuAction("Playback details", Icons.Filled.Info) { choose(entry) }
        PlayerMenuEntry.StartOver -> MenuAction("Start over", Icons.Filled.Refresh) { choose(entry) }
        PlayerMenuEntry.Speed -> MenuAction(
            label = "Speed",
            icon = ImageVector.vectorResource(R.drawable.ic_speed),
            detail = PlaybackSpeed.label(speed()),
        ) { page.value = Page.Speed }
        PlayerMenuEntry.SaveToDownloads -> MenuAction(
            label = "Save to Downloads",
            icon = TmIcons.Download,
            detail = "Kept until you delete it",
        ) { choose(entry) }
        PlayerMenuEntry.PictureInPicture ->
            MenuAction("Picture in picture", ImageVector.vectorResource(R.drawable.ic_pip)) { choose(entry) }
        PlayerMenuEntry.OpenInAnotherApp ->
            MenuAction("Open in another app", Icons.AutoMirrored.Filled.ExitToApp) { choose(entry) }
        PlayerMenuEntry.RemoteKeys ->
            MenuAction("Remote keys", Icons.Filled.Info, detail = "What each key does here") { page.value = Page.Keys }
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
    val first = remember { FocusRequester() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.82f)),
            contentAlignment = Alignment.Center,
        ) {
            val panel = min(maxWidth - PhonePad.Side * 2, KEYS_MAX)
            Column(
                Modifier
                    .width(panel)
                    .clip(RoundedCornerShape(Corner.ExtraLarge))
                    .background(Tone.surface)
                    .border(1.dp, Tone.muted.copy(alpha = 0.25f), RoundedCornerShape(Corner.ExtraLarge))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "Remote keys",
                    style = MaterialTheme.typography.titleLarge,
                    color = Tone.text,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                )
                RemoteKeys.ROWS.forEachIndexed { index, (key, does) ->
                    KeyRow(key, does, if (index == 0) Modifier.focusRequester(first) else Modifier)
                }
                Text(
                    "Press Back to close this.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Tone.muted,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )
            }
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
