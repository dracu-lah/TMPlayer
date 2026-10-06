package com.tmplayer.player

import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.ui.components.ChoiceLine
import com.tmplayer.ui.components.ChoiceList
import com.tmplayer.ui.components.ChoiceSheet
import com.tmplayer.ui.components.FocusOnOpen
import com.tmplayer.ui.components.LocalChoiceFullScreen
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone

/**
 * The player's speed, picture shape, sleep timer and playback details, drawn as the language picker's modal
 * ([ChoiceSheet]) over the dimmed video, as the subtitle and audio pickers are.
 *
 * The television's More menu shows the speed and sleep timer as pages of its own ([PlayerTvMenu]),
 * so Back steps back to the menu; this host opens them on their own, from the phone's overflow,
 * and the details on both. Like the More menu it is a [ComposeView] laid over the player that
 * draws nothing in the player's window: each sheet is a dialog window of its own, which takes the
 * remote's keys and the touches away from the player while it is up.
 */
class PlayerSheets(
    activity: FragmentActivity,
    root: ViewGroup,
    private val onClosed: () -> Unit,
) {
    private sealed interface Sheet {
        class Speed(val current: Float, val onPick: (Float) -> Unit) : Sheet
        class Shape(val current: VideoScale, val onPick: (VideoScale) -> Unit) : Sheet
        class Sleep(val running: String?, val chosen: Int?, val onPick: (Int?) -> Unit) : Sheet
        class Details(val title: String, val lines: List<String>) : Sheet
    }

    private val sheet = mutableStateOf<Sheet?>(null)

    val isOpen: Boolean get() = sheet.value != null

    init {
        val view = ComposeView(activity).apply {
            // Not named Content: inside apply that resolves to ComposeView.Content (see PlayerTvMenu).
            setContent { TMPlayerTheme { SheetContent() } }
        }
        root.addView(view, ViewGroup.LayoutParams(0, 0))
    }

    fun showSpeed(current: Float, onPick: (Float) -> Unit) {
        sheet.value = Sheet.Speed(current, onPick)
    }

    fun showShape(current: VideoScale, onPick: (VideoScale) -> Unit) {
        sheet.value = Sheet.Shape(current, onPick)
    }

    /** [running] is the running timer's detail, [chosen] the length it was started with. */
    fun showSleep(running: String?, chosen: Int?, onPick: (Int?) -> Unit) {
        sheet.value = Sheet.Sleep(running, chosen, onPick)
    }

    fun showDetails(title: String, lines: List<String>) {
        sheet.value = Sheet.Details(title, lines)
    }

    private fun close() {
        if (sheet.value == null) return
        sheet.value = null
        onClosed()
    }

    @Composable
    private fun SheetContent() {
        when (val shown = sheet.value) {
            null -> Unit
            is Sheet.Speed -> SpeedSheet(
                current = shown.current,
                onPick = { close(); shown.onPick(it) },
                onDismiss = ::close,
                onClose = ::close,
            )
            is Sheet.Shape -> ShapeSheet(
                current = shown.current,
                onPick = { close(); shown.onPick(it) },
                onDismiss = ::close,
                onClose = ::close,
            )
            is Sheet.Sleep -> SleepSheet(
                running = shown.running,
                chosen = shown.chosen,
                onPick = { close(); shown.onPick(it) },
                onDismiss = ::close,
                onClose = ::close,
            )
            is Sheet.Details -> PlaybackDetailsSheet(shown.title, shown.lines, onClose = ::close)
        }
    }
}

/**
 * The playback speeds, one ticked: the one playing. Back is [onDismiss] (a page of the More menu
 * steps back with it), Close is [onClose]. The remote starts on the speed in force.
 *
 * Full screen on a phone they are a grid of tiles instead of a list, all seven in one row on a
 * landscape phone and four to a row upright, so every speed is one tap with nothing to scroll.
 */
@Composable
internal fun SpeedSheet(current: Float, onPick: (Float) -> Unit, onDismiss: () -> Unit, onClose: () -> Unit) {
    val s = LocalStrings.current
    val touch = isTouch()
    val focus = remember { FocusRequester() }
    val focusOn = PlaybackSpeed.CHOICES.firstOrNull { it == current } ?: PlaybackSpeed.CHOICES.first()
    ChoiceSheet(title = s.playerPlaybackSpeed, onDismiss = onDismiss, onClose = onClose, ignoreRelease = !touch, fullScreen = true) {
        if (LocalChoiceFullScreen.current) {
            SpeedTiles(current, onPick)
            return@ChoiceSheet
        }
        ChoiceList {
            PlaybackSpeed.CHOICES.forEach { choice ->
                val selected = choice == current
                ChoiceLine(
                    title = PlaybackSpeed.label(choice),
                    detail = if (selected) s.playerSpeedCurrent else null,
                    selected = selected,
                    modifier = if (choice == focusOn) Modifier.focusRequester(focus) else Modifier,
                ) { onPick(choice) }
            }
        }
        FocusOnOpen(focus)
    }
}

/**
 * The speeds as tiles in the middle of the room the sheet has, the one playing filled with the
 * accent, the rest a step above the panel.
 */
@Composable
private fun ColumnScope.SpeedTiles(current: Float, onPick: (Float) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
        val count = PlaybackSpeed.CHOICES.size
        val perRow = if (maxWidth >= (TILE_MIN + TILE_GAP) * count) count else 4
        val tile = (maxWidth - TILE_GAP * (perRow - 1)) / perRow
        Column(verticalArrangement = Arrangement.spacedBy(TILE_GAP)) {
            PlaybackSpeed.CHOICES.chunked(perRow).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                    row.forEach { choice ->
                        val selected = choice == current
                        val shape = RoundedCornerShape(Corner.Large)
                        Column(
                            Modifier
                                .width(tile)
                                .height(TILE_HEIGHT)
                                .clip(shape)
                                .background(if (selected) Tone.accent else FloatingTone.control)
                                .selectable(selected = selected, role = Role.RadioButton) { onPick(choice) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                PlaybackSpeed.label(choice),
                                style = MaterialTheme.typography.headlineSmall,
                                color = if (selected) Tone.onAccent else Tone.text,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val TILE_HEIGHT = 112.dp
private val TILE_MIN = 88.dp
private val TILE_GAP = 12.dp

/**
 * The picture shapes, one ticked: fit, crop and stretch, as the pickers' panel draws a choice of
 * one of a few. The pinch on a phone stays as the shortcut; this is the route that can be found.
 */
@Composable
internal fun ShapeSheet(current: VideoScale, onPick: (VideoScale) -> Unit, onDismiss: () -> Unit, onClose: () -> Unit) {
    val s = LocalStrings.current
    val touch = isTouch()
    val focus = remember { FocusRequester() }
    ChoiceSheet(title = s.playerPictureShape, onDismiss = onDismiss, onClose = onClose, ignoreRelease = !touch, fullScreen = true) {
        ChoiceList {
            VideoScale.entries.forEach { choice ->
                ChoiceLine(
                    title = choice.label,
                    selected = choice == current,
                    modifier = if (choice == current) Modifier.focusRequester(focus) else Modifier,
                ) { onPick(choice) }
            }
        }
        FocusOnOpen(focus)
    }
}

/**
 * The sleep timer's lengths, with Off first: Off is ticked while no timer runs, the length a
 * running timer was started with otherwise, and the time it has left is the note under the title.
 * [onPick] gets minutes, [SleepTimer.END_OF_VIDEO], or null for off.
 */
@Composable
internal fun SleepSheet(
    running: String?,
    chosen: Int?,
    onPick: (Int?) -> Unit,
    onDismiss: () -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalStrings.current
    val touch = isTouch()
    val focus = remember { FocusRequester() }
    val ticked = if (running == null) null else chosen
    val focusOn: Int? = ticked?.takeIf { it in SleepTimer.CHOICES }
    ChoiceSheet(title = s.playerSleepTimer, note = running, onDismiss = onDismiss, onClose = onClose, ignoreRelease = !touch, fullScreen = true) {
        ChoiceList {
            ChoiceLine(
                title = s.commonOff,
                detail = if (running != null) s.playerKeepPlaying else null,
                selected = running == null,
                modifier = if (focusOn == null) Modifier.focusRequester(focus) else Modifier,
            ) { if (running == null) onClose() else onPick(null) }
            SleepTimer.CHOICES.forEach { minutes ->
                ChoiceLine(
                    title = SleepTimer.label(minutes),
                    selected = running != null && minutes == ticked,
                    modifier = if (minutes == focusOn) Modifier.focusRequester(focus) else Modifier,
                ) { onPick(minutes) }
            }
        }
        FocusOnOpen(focus)
    }
}

/**
 * What is actually playing, a line a figure, in the pickers' panel. Nothing in it is chosen, so
 * the remote starts on Close, and Back or Close shuts it.
 */
@Composable
internal fun PlaybackDetailsSheet(title: String, lines: List<String>, onClose: () -> Unit) {
    val touch = isTouch()
    val closeFocus = remember { FocusRequester() }
    ChoiceSheet(title = title, onDismiss = onClose, onClose = onClose, closeFocus = closeFocus, ignoreRelease = !touch, fullScreen = true) {
        ChoiceList {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                lines.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodyLarge, color = Tone.text)
                }
            }
        }
        FocusOnOpen(closeFocus)
    }
}
