package com.tmplayer.player

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.fragment.app.FragmentActivity
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.WatchPoint
import com.tmplayer.ui.browse.SeriesWatch
import com.tmplayer.ui.components.FloatingWindow
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.player.EpisodesActions
import com.tmplayer.ui.player.EpisodesPanel
import com.tmplayer.ui.player.EpisodesState
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import com.tmplayer.ui.theme.floatingSurface
import kotlinx.coroutines.flow.Flow

/**
 * The player's episode list (CP40) over the dimmed video: the shared [EpisodesPanel] in a window of
 * its own, as [PlayerSheets] hosts the pickers. Full screen on a phone, which a landscape phone
 * needs to show more than two episodes; a panel inside the overscan on a television and a tablet.
 *
 * Back closes it first, as the window's dismissal. The activity keeps every action; this draws the
 * list from [state] and reports what was pressed.
 *
 * [progress] and [watched] are the stores' flows, so a row marked watched here ticks at once.
 */
class PlayerEpisodes(
    activity: FragmentActivity,
    root: ViewGroup,
    private val progress: Flow<Map<String, WatchPoint>>,
    private val watched: Flow<Map<String, *>>,
    private val position: () -> Long,
    private val actions: EpisodesActions,
    private val onClosed: () -> Unit,
) {
    private val shown = mutableStateOf<EpisodesState?>(null)

    val isOpen: Boolean get() = shown.value != null

    init {
        val view = ComposeView(activity).apply {
            // Not named Content: inside apply that resolves to ComposeView.Content (see PlayerTvMenu).
            setContent { TMPlayerTheme { ListContent() } }
        }
        root.addView(view, ViewGroup.LayoutParams(0, 0))
    }

    fun open(state: EpisodesState) {
        shown.value = state
    }

    /** New answers for a list already up (autoplay switched, the order changed, the intro marked). */
    fun update(state: EpisodesState) {
        if (shown.value != null) shown.value = state
    }

    fun close() {
        if (shown.value == null) return
        shown.value = null
        onClosed()
    }

    @Composable
    private fun ListContent() {
        val state = shown.value ?: return
        val points by progress.collectAsState(initial = emptyMap())
        val done by watched.collectAsState(initial = emptyMap<String, Any>())
        val watch = remember(points, done) {
            SeriesWatch(
                point = { points[SettingsStore.progressKey(it.chatId, it.messageId)] },
                finished = { SettingsStore.progressKey(it.chatId, it.messageId) in done },
            )
        }
        val touch = isTouch()
        val screen = LocalConfiguration.current
        val phone = touch && minOf(screen.screenWidthDp, screen.screenHeightDp) < PHONE_BELOW
        FloatingWindow(onDismiss = ::close, immersive = phone) {
            if (phone) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(FloatingTone.dialog)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical))
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
                ) {
                    EpisodesPanel(
                        state = state,
                        watch = watch,
                        actions = actions,
                        position = position,
                        modifier = Modifier.padding(top = 4.dp),
                        contentPadding = 16.dp,
                        trailing = { CloseButton() },
                    )
                }
            } else {
                val width = min(maxWidth - Tv.SafeH * 2, PANEL_MAX)
                Box(
                    Modifier
                        .width(width)
                        .height(maxHeight - Tv.SafeV * 2)
                        .floatingSurface(FloatingTone.sheet)
                        .padding(start = 24.dp, end = 16.dp, top = 16.dp),
                ) {
                    EpisodesPanel(
                        state = state,
                        watch = watch,
                        actions = actions,
                        position = position,
                        // A tablet keeps its close button; a remote has Back.
                        trailing = { if (touch) CloseButton() },
                    )
                }
            }
        }
    }

    @Composable
    private fun CloseButton() {
        IconButton(onClick = ::close) {
            Icon(Icons.Filled.Close, contentDescription = LocalStrings.current.commonClose, tint = Tone.text)
        }
    }

    private companion object {
        /** Below this short side a touch screen is a phone, and the list fills it. */
        const val PHONE_BELOW = 600

        val PANEL_MAX = 1100.dp
    }
}
