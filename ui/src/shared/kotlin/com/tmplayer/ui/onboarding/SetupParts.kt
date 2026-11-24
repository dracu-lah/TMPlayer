package com.tmplayer.ui.onboarding

import com.tmplayer.ui.components.pressScale
import com.tmplayer.ui.theme.collectIsFocusVisibleAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.tmplayer.data.DeviceFit
import com.tmplayer.data.DeviceForm
import com.tmplayer.data.Recommendation
import com.tmplayer.data.SettingsStore
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.ui.components.deviceForm
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.theme.Caution
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import com.tmplayer.ui.theme.focusFillOver
import com.tmplayer.ui.theme.focusRing
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// Three seconds in all: a television cold start can take that long to give the window its focus.
internal const val FOCUS_TRIES = 40
internal const val FOCUS_RETRY_MS = 75L


/**
 * An answer's picture, filling [modifier]'s square: the marked shot, or the drawing for the storage
 * and mobile data steps. The setup's cards and Settings' Setup choices show the same one.
 */
@Composable
internal fun ChoicePicture(choice: SetupChoice, modifier: Modifier) {
    when {
        choice.screenshot -> SetupShot(choice, modifier)
        choice.step == SetupStep.MobileData -> MobileDataArt(choice, modifier)
        else -> StorageArt(choice, modifier)
    }
}

/**
 * A round button that fills under the focus like the cards do. [primary] is the accent-filled one:
 * the first run's Next and Done ([StepFrame]), and the low-end device card's Apply. [tonal] keeps a
 * button a secondary one even under the focus (D-2): in the dark theme [Tone.focusFill] is the
 * accent, so a focused plain button reads as a second primary beside Next. A tonal one answers the
 * focus with a lighter fill, the text colour for the ring and the text on [Tone.surfaceHigh].
 */
@Composable
internal fun SetupButton(
    label: String,
    icon: Boolean = false,
    primary: Boolean = false,
    tonal: Boolean = false,
    modifier: Modifier = Modifier,
    /** False draws the button dimmed, takes no press and no focus (the picker's band button while every chat counts). */
    enabled: Boolean = true,
    /**
     * True while the press is being answered (a save that runs): a 20 dp spinner, a still arc
     * under less motion, goes before [label], which then says what runs. The button keeps its
     * focus and takes no second press; a screen reader hears [label] as its state.
     */
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused = interactions.collectIsFocusVisibleAsState().value
    val touch = deviceForm() == DeviceForm.Phone
    val rest = if (primary) Tone.accent else Tone.surfaceHigh
    Row(
        modifier
            .then(if (enabled) Modifier else Modifier.alpha(0.4f))
            .then(if (busy) Modifier.alpha(0.8f).semantics { stateDescription = label } else Modifier)
            // A finger needs 48 dp to hit (HX56: the picker's Done was 37).
            .then(if (touch) Modifier.heightIn(min = 48.dp) else Modifier)
            // Sinks a little under a finger or a click, and comes back up on release.
            .pressScale(interactions, com.tmplayer.ui.components.Micro.PillPressScale)
            .clip(CircleShape)
            .background(
                when {
                    focused && tonal && !primary -> Tone.text.copy(alpha = TONAL_FOCUS_ALPHA).compositeOver(Tone.surfaceHigh)
                    focused -> focusFillOver(rest)
                    else -> rest
                },
            )
            // A primary button rests in the accent, which is the focus fill too: it keeps the fill and
            // draws the one flush ring in the on-accent ink (focusFillOver), never a second outline.
            .focusRing(focused, CircleShape, rest = rest)
            .then(if (tonal && !primary && focused) Modifier.border(2.dp, Tone.text, CircleShape) else Modifier)
            .clickable(
                interactionSource = interactions,
                indication = if (touch) LocalIndication.current else null,
                role = Role.Button,
                enabled = enabled,
                onClick = { if (!busy) onClick() },
            )
            .padding(horizontal = 18.dp, vertical = 10.dp),
        // Centred: a button given the full width (the crash question's three) keeps its words in the middle.
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = when {
            focused && tonal && !primary -> Tone.text
            focused -> Tone.onFocusFill
            primary -> Tone.onAccent
            else -> Tone.text
        }
        if (icon) {
            Icon(TmIcons.ArrowBack, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        if (busy) {
            // Only a picture of the wait: the label is what is spoken.
            val mark = Modifier.size(20.dp).clearAndSetSemantics { }
            if (com.tmplayer.ui.components.rememberLessMotion()) {
                CircularProgressIndicator(progress = { 0.75f }, modifier = mark, color = color, strokeWidth = 2.dp, trackColor = Color.Transparent)
            } else {
                CircularProgressIndicator(modifier = mark, color = color, strokeWidth = 2.dp)
            }
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/** How much of the text colour a focused [SetupButton] with `tonal` mixes into its fill. */
internal const val TONAL_FOCUS_ALPHA = 0.22f

/**
 * The storage step's picture, drawn rather than shot, since a frame of the player says nothing
 * about what is kept. Over a video's length (the bar), the part kept on the device is filled: up to
 * where it is playing and a little ahead, or with Low storage mode only a short stretch around
 * the playhead. Under it, how full the device gets. Follows the reading direction.
 */
@Composable
private fun StorageArt(choice: SetupChoice, modifier: Modifier) {
    val low = choice == SetupChoice.LowStorage
    val accent = Tone.accent
    val track = Tone.outline
    val ground = Tone.background
    val text = Tone.muted
    Column(
        modifier.padding(14.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(TmIcons.Play, contentDescription = null, tint = text, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Canvas(Modifier.weight(1f).height(26.dp).mirrored()) {
                val h = size.height * 0.42f
                val y = (size.height - h) / 2
                val r = CornerRadius(h / 2, h / 2)
                drawRoundRect(track, Offset(0f, y), Size(size.width, h), r)
                val from = if (low) 0.34f else 0f
                drawRoundRect(accent, Offset(size.width * from, y), Size(size.width * (0.62f - from), h), r)
                val head = Offset(size.width * 0.48f, size.height / 2)
                drawCircle(ground, radius = size.height * 0.42f, center = head)
                drawCircle(accent, radius = size.height * 0.30f, center = head)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(TmIcons.Download, contentDescription = null, tint = text, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Canvas(Modifier.weight(1f).height(40.dp).mirrored()) {
                val r = CornerRadius(size.height * 0.22f, size.height * 0.22f)
                drawRoundRect(track, Offset.Zero, size, r)
                val inset = size.height * 0.14f
                val inner = Size(size.width - inset * 2, size.height - inset * 2)
                drawRoundRect(ground, Offset(inset, inset), inner, r)
                val used = if (low) 0.18f else 0.78f
                drawRoundRect(
                    if (low) accent else Caution,
                    Offset(inset, inset),
                    Size(inner.width * used, inner.height),
                    r,
                )
            }
        }
    }
}

/**
 * The mobile data step's picture, drawn like the storage step's. Over a video's length, the part
 * fetched while it plays on mobile data: a short stretch ahead of the playhead when saving data,
 * or everything to the end without limits. Downloads are not in it: neither answer holds them back
 * (that is Wi-Fi only, in Settings), so a second bar would draw a difference there is not.
 */
@Composable
private fun MobileDataArt(choice: SetupChoice, modifier: Modifier) {
    val save = choice == SetupChoice.SaveData
    val accent = Tone.accent
    val track = Tone.outline
    val ground = Tone.background
    val text = Tone.muted
    Column(
        modifier.padding(14.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(TmIcons.Play, contentDescription = null, tint = text, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Canvas(Modifier.weight(1f).height(26.dp).mirrored()) {
                val h = size.height * 0.42f
                val y = (size.height - h) / 2
                val r = CornerRadius(h / 2, h / 2)
                drawRoundRect(track, Offset(0f, y), Size(size.width, h), r)
                val to = if (save) 0.58f else 1f
                drawRoundRect(if (save) accent else Caution, Offset(0f, y), Size(size.width * to, h), r)
                val head = Offset(size.width * 0.40f, size.height / 2)
                drawCircle(ground, radius = size.height * 0.42f, center = head)
                drawCircle(accent, radius = size.height * 0.30f, center = head)
            }
        }
    }
}

/**
 * The arrow keys move between the answers and down to the buttons. A remote's D-pad does this on
 * its own; a desktop window's arrow keys do not, and Tab alone is a poor way across two cards.
 */
@Composable
internal fun Modifier.arrowsMoveFocus(): Modifier {
    val focusManager = LocalFocusManager.current
    return onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val direction = when (event.key) {
            Key.DirectionLeft -> FocusDirection.Left
            Key.DirectionRight -> FocusDirection.Right
            Key.DirectionUp -> FocusDirection.Up
            Key.DirectionDown -> FocusDirection.Down
            else -> return@onPreviewKeyEvent false
        }
        focusManager.moveFocus(direction)
        true
    }
}

/** Flips a drawing for a right-to-left language, where the bar fills from the right. */
@Composable
private fun Modifier.mirrored(): Modifier =
    if (LocalLayoutDirection.current == LayoutDirection.Rtl) graphicsLayer(scaleX = -1f) else this

private val GAP = 12.dp
private val CARD_PAD = 8.dp

/** The room under a picture for the answer's name, its line and "Recommended". */
private val LABELS = 110.dp
