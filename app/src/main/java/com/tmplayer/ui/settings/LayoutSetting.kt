package com.tmplayer.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.tmplayer.FormRestart
import com.tmplayer.data.FormFactor
import com.tmplayer.data.InterfaceChoice
import com.tmplayer.i18n.Strings
import com.tmplayer.ui.components.ChoiceLine
import com.tmplayer.ui.components.ChoiceList
import com.tmplayer.ui.components.ChoiceSheet
import com.tmplayer.ui.components.FocusOnOpen
import com.tmplayer.ui.components.TmButton
import com.tmplayer.ui.components.TmSecondaryButton
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.components.tvHoldsFocus
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.LocalOnFloating
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.floatingSurface

/** The word for a layout: "TV" or "Phone and tablet". */
internal fun Strings.layoutName(tv: Boolean): String = if (tv) interfaceLayoutTv else interfaceLayoutPhone

/** Settings, Appearance, Layout's second line: what is picked, and for Automatic what that came to. */
internal fun Strings.layoutRowDetail(choice: InterfaceChoice, autoTv: Boolean): String = when (choice) {
    InterfaceChoice.Auto -> interfaceLayoutAutoNow(layout = layoutName(autoTv))
    InterfaceChoice.Tv -> interfaceLayoutTv
    InterfaceChoice.Phone -> interfaceLayoutPhone
}

/**
 * Settings, Appearance, Layout (HX78): Automatic, TV or Phone and tablet. A different pick saves
 * it and restarts TMPlayer in that layout ([FormRestart]), as the note above the lines says.
 * Reached by touch in either layout and by the remote in either, so a box shown the wrong one
 * can always get out.
 */
@Composable
fun LayoutSheet(onClose: () -> Unit) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val current = remember { FormFactor.choice(context) }
    val autoTv = remember { FormFactor.autoIsTv(context) }
    val focus = remember { FocusRequester() }
    ChoiceSheet(title = s.interfaceLayout, note = s.interfaceLayoutNote, onDismiss = onClose, onClose = onClose) {
        ChoiceList {
            InterfaceChoice.entries.forEach { choice ->
                val selected = choice == current
                ChoiceLine(
                    title = when (choice) {
                        InterfaceChoice.Auto -> s.interfaceLayoutAuto
                        InterfaceChoice.Tv -> s.interfaceLayoutTv
                        InterfaceChoice.Phone -> s.interfaceLayoutPhone
                    },
                    detail = if (choice == InterfaceChoice.Auto) s.interfaceLayoutAutoDetail(layout = s.layoutName(autoTv)) else null,
                    selected = selected,
                    modifier = if (selected) Modifier.focusRequester(focus) else Modifier,
                ) {
                    if (choice == current) {
                        onClose()
                    } else {
                        val activity = context.findActivity()
                        if (activity == null) {
                            FormFactor.setChoice(context, choice)
                            onClose()
                        } else {
                            FormRestart.switchTo(activity, choice)
                        }
                    }
                }
            }
        }
        FocusOnOpen(focus)
    }
}

/**
 * The layout nudge (HX78): a remote pressed on the touch layout ([toTv]), or a finger on a TV
 * layout the app only guessed, offers the other one. Switch restarts into it; Not now lets it be
 * for this run; Don't ask again for good. It takes the remote on Switch as it comes up, on either
 * layout, because the press that raised it came from a remote.
 */
@Composable
fun LayoutNudgeCard(toTv: Boolean, onSwitch: () -> Unit, onLater: () -> Unit, onNever: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val touch = isTouch()
    val first = remember { FocusRequester() }
    BackHandler(onBack = onLater)
    Column(
        modifier
            .tvHoldsFocus(first)
            .widthIn(max = if (touch) 520.dp else 560.dp)
            .floatingSurface()
            .padding(if (touch) 16.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (toTv) TmIcons.Tv else TmIcons.Phone, contentDescription = null, tint = Tone.accent, modifier = Modifier.size(24.dp))
            Text(
                if (toTv) s.interfaceNudgeTitle else s.interfaceNudgeTouchTitle,
                style = MaterialTheme.typography.titleMedium,
                color = Tone.text,
            )
        }
        Text(
            if (toTv) s.interfaceNudgeBody else s.interfaceNudgeTouchBody,
            style = MaterialTheme.typography.bodyMedium,
            color = Tone.muted,
        )
        CompositionLocalProvider(LocalOnFloating provides true) {
            // Wraps whole buttons on a narrow phone rather than breaking a word inside one.
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TmSecondaryButton(onClick = onNever) { ButtonWords(s.interfaceNudgeNever) }
                TmSecondaryButton(onClick = onLater) { ButtonWords(s.interfaceNudgeNotNow) }
                TmButton(onClick = onSwitch, modifier = Modifier.focusRequester(first)) { ButtonWords(s.interfaceNudgeSwitch) }
            }
        }
    }
    // tvHoldsFocus does nothing on the touch layout; the remote that raised the card still needs
    // to land on it there.
    if (touch) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
    }
}

/**
 * [LayoutNudgeCard] while [LayoutNudge] offers one, at the foot of the screen like the language
 * card. Called from the activity's root box, so it covers sign in and the tour too.
 */
@Composable
fun androidx.compose.foundation.layout.BoxScope.LayoutNudgeHost() {
    val offer by LayoutNudge.offer.collectAsState()
    val toTv = offer ?: return
    val context = LocalContext.current
    val tv = FormFactor.isTv(context)
    LayoutNudgeCard(
        toTv = toTv,
        onSwitch = {
            LayoutNudge.dismiss()
            val activity = context.findActivity()
            val choice = if (toTv) InterfaceChoice.Tv else InterfaceChoice.Phone
            if (activity != null) FormRestart.switchTo(activity, choice) else FormFactor.setChoice(context, choice)
        },
        onLater = { LayoutNudge.dismiss() },
        onNever = {
            FormFactor.setNudgeNever(context)
            LayoutNudge.dismiss()
        },
        modifier = Modifier
            .align(if (tv) Alignment.BottomEnd else Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(if (tv) 40.dp else 16.dp),
    )
}

/** A button's words, in the library the button is drawn with (see Support.kt). */
@Composable
private fun ButtonWords(text: String) {
    if (isTouch()) androidx.compose.material3.Text(text) else Text(text)
}

internal fun Context.findActivity(): Activity? {
    var at: Context? = this
    while (at is ContextWrapper) {
        if (at is Activity) return at
        at = at.baseContext
    }
    return null
}

/**
 * When to raise [LayoutNudgeCard] (HX78), fed from MainActivity's key and touch dispatch.
 *
 * Toward the TV layout: the first D-pad press of a run on the touch layout, before any touch,
 * with Automatic picked, on a device that is not a desktop. Toward the touch layout: a finger on
 * the TV layout the app only guessed (not a certified TV, not the user's pick). At most once per
 * run either way, never after Don't ask again. A mouse or an air mouse is not a finger.
 */
object LayoutNudge {

    /** True to offer the TV layout, false the touch one, null for no card. */
    val offer = kotlinx.coroutines.flow.MutableStateFlow<Boolean?>(null)

    @Volatile private var touched = false
    @Volatile private var asked = false

    fun onKey(context: Context, event: android.view.KeyEvent) {
        if (asked || event.action != android.view.KeyEvent.ACTION_DOWN) return
        if (event.keyCode !in DPAD_KEYS) return
        if (touched || FormFactor.isTv(context)) return
        if (!eligible(context) || FormFactor.desktopLike(context)) return
        asked = true
        offer.value = true
    }

    fun onTouch(context: Context, event: android.view.MotionEvent) {
        if (event.actionMasked != android.view.MotionEvent.ACTION_DOWN) return
        if (!event.isFromSource(android.view.InputDevice.SOURCE_TOUCHSCREEN)) return
        touched = true
        if (asked || !FormFactor.isTv(context) || !FormFactor.guessed(context)) return
        if (!eligible(context)) return
        asked = true
        offer.value = false
    }

    fun dismiss() {
        offer.value = null
    }

    private fun eligible(context: Context): Boolean =
        FormFactor.choice(context) == InterfaceChoice.Auto && !FormFactor.nudgeNever(context)

    private val DPAD_KEYS = setOf(
        android.view.KeyEvent.KEYCODE_DPAD_UP,
        android.view.KeyEvent.KEYCODE_DPAD_DOWN,
        android.view.KeyEvent.KEYCODE_DPAD_LEFT,
        android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
        android.view.KeyEvent.KEYCODE_DPAD_CENTER,
    )
}
