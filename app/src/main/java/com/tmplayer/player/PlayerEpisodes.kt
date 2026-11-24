package com.tmplayer.player

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.tmplayer.ui.components.PhoneSheet
import com.tmplayer.ui.components.keepScrollInSheet
import com.tmplayer.ui.components.rememberLessMotion
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.floatingBorder
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
import androidx.fragment.app.FragmentActivity
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.WatchPoint
import com.tmplayer.ui.browse.SeriesWatch
import com.tmplayer.ui.components.FloatingWindow
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.player.EpisodesActions
import com.tmplayer.ui.player.EpisodesPanel
import com.tmplayer.ui.player.EpisodesState
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import kotlinx.coroutines.flow.Flow

/**
 * The player's episode list (CP40) over the dimmed video: the shared [EpisodesPanel] in a bottom
 * sheet ([PhoneSheet], as the More panel is) on a phone held upright, and in a panel at the end,
 * about a third of the width, on a television, a tablet and a phone on its side (W15).
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
    private val actions: EpisodesActions,
    private val onClosed: () -> Unit,
) {
    private val shown = mutableStateOf<EpisodesState?>(null)

    val isOpen: Boolean get() = shown.value != null

    init {
        val view = ComposeView(activity).apply {
            // Not named Content: inside apply that resolves to ComposeView.Content (see PlayerMorePanel).
            setContent { TMPlayerTheme { ListContent() } }
        }
        root.addView(view, ViewGroup.LayoutParams(0, 0))
    }

    fun open(state: EpisodesState) {
        shown.value = state
    }

    /** New answers for a list already up (autoplay switched). */
    fun update(state: EpisodesState) {
        if (shown.value != null) shown.value = state
    }

    fun close() {
        if (shown.value == null) return
        shown.value = null
        onClosed()
    }

    @OptIn(ExperimentalMaterial3Api::class)
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
        val upright = screen.screenHeightDp > screen.screenWidthDp
        if (phone && upright) {
            // A phone held upright: the bottom sheet, as the player's More panel is. The video stays
            // playing behind it; swiping it down, the scrim and Back all close it.
            PhoneSheet(onDismissRequest = ::close) {
                EpisodesPanel(
                    state = state,
                    watch = watch,
                    actions = actions,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((screen.screenHeightDp * SHEET_HEIGHT).dp)
                        .keepScrollInSheet(),
                    contentPadding = 16.dp,
                    trailing = { CloseButton() },
                )
            }
        } else {
            // A television, a tablet and a phone on its side: a panel at the end, about a third of the
            // width, over the dimmed video. It slides in on 200 ms (none under reduced motion), Back
            // closes it, and the remote stays inside it.
            FloatingWindow(onDismiss = ::close, immersive = phone) {
                SidePanel(state, watch, touch, phone)
            }
        }
    }

    @Composable
    private fun BoxWithConstraintsScope.SidePanel(state: EpisodesState, watch: SeriesWatch, touch: Boolean, phone: Boolean) {
        val still = rememberLessMotion()
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val slide = remember { Animatable(if (still) 0f else 1f) }
        LaunchedEffect(Unit) {
            if (slide.value > 0f) slide.animateTo(0f, tween(SLIDE_MS, easing = Motion.Standard))
        }
        val width = (maxWidth * PANEL_SHARE).coerceIn(PANEL_MIN, PANEL_MAX).coerceAtMost(maxWidth * 0.92f)
        val shape = RoundedCornerShape(topStart = Corner.ExtraLarge, bottomStart = Corner.ExtraLarge)
        // Outside the panel a tap closes it, as it does in the More panel.
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { close() } })
        // A television overscans: the whole panel (its surface, so its cards and focus rings) sits inside
        // the safe area, like the account's side sheet does (Tv.SafeH at the end, Tv.SafeV top and
        // bottom). The scrim above still fills the screen. A tablet and a phone have no overscan.
        val tvSafe = !touch
        val endInset = if (tvSafe) Tv.SafeH else 0.dp
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .graphicsLayer { translationX = slide.value * size.width * (if (rtl) -1f else 1f) }
                .width(width + endInset)
                .fillMaxHeight()
                .then(if (tvSafe) Modifier.padding(end = Tv.SafeH, top = Tv.SafeV, bottom = Tv.SafeV) else Modifier),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(if (tvSafe) RoundedCornerShape(Corner.ExtraLarge) else shape)
                    .background(FloatingTone.sheet)
                    .border(floatingBorder(), if (tvSafe) RoundedCornerShape(Corner.ExtraLarge) else shape)
                    // A tap on the panel's own blank space is not a tap outside it.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .then(
                        if (phone) {
                            Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End))
                        } else {
                            Modifier
                        },
                    )
                    .padding(start = 20.dp, end = if (touch) 8.dp else 16.dp, top = 12.dp),
            ) {
                EpisodesPanel(
                    state = state,
                    watch = watch,
                    actions = actions,
                    // A tablet and a phone keep their close button; a remote has Back.
                    trailing = { if (touch) CloseButton() },
                )
            }
        }
    }

    @Composable
    private fun CloseButton() {
        com.tmplayer.ui.components.IconTip(LocalStrings.current.commonClose, side = com.tmplayer.ui.components.TipSide.Below) {
            IconButton(onClick = ::close) {
                Icon(TmIcons.Close, contentDescription = LocalStrings.current.commonClose, tint = Tone.text)
            }
        }
    }

    private companion object {
        /** Below this short side a touch screen is a phone, and the list fills it. */
        const val PHONE_BELOW = 600

        /** The side panel: about 32 percent of the width, between these, as the account's side sheet is. */
        const val PANEL_SHARE = 0.32f
        val PANEL_MIN = 340.dp
        val PANEL_MAX = 480.dp
        const val SLIDE_MS = 200

        /** The phone sheet's height, as a share of the screen. */
        const val SHEET_HEIGHT = 0.82f
    }
}
