package com.tmplayer.ui.components

import androidx.activity.compose.BackHandler

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Focus
import com.tmplayer.ui.theme.Floating
import com.tmplayer.ui.theme.Tone

/**
 * A search box sized for a remote and a sofa.
 *
 * It is an ordinary focusable target, so the D-pad reaches it like any other control and the TV's
 * on-screen keyboard opens on click. The border turns accent on focus because on a 10-foot screen
 * a cursor alone is not a visible enough "you are typing here".
 */
@Composable
fun TvSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSubmit: (() -> Unit)? = null,
    /**
     * Raised by one each time something other than a press asks for the keyboard: the voice
     * button on Fire OS, where dictation is the keyboard's (see [rememberVoiceSearch]).
     */
    editRequests: Int = 0,
) {
    // Fire TV's remote microphone dictates into the system keyboard while it is up, and nothing
    // on screen says so; the empty field says it instead.
    val context = androidx.compose.ui.platform.LocalContext.current
    val dictationHint = if (com.tmplayer.data.DeviceQuirks.isFireTv(context)) {
        com.tmplayer.ui.i18n.LocalStrings.current.tvVoiceHintFire
    } else {
        null
    }
    // Focus alone must not start editing. On a remote the D-pad passes through this box on the
    // way to everything below it, and a keyboard that throws itself over the screen each time
    // makes the row impossible to move past. Editing begins on a deliberate press.
    var editing by remember { mutableStateOf(false) }
    var everFocused by remember { mutableStateOf(false) }
    // Set around the deliberate clear-and-reclaim focus dance below (HX49), so the field's own
    // onFocusChanged does not read that blip as the viewer leaving and close editing.
    var reclaimingFocus by remember { mutableStateOf(false) }
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val field = remember { FocusRequester() }
    val box = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val active = focused || editing

    LaunchedEffect(editRequests) {
        if (editRequests > 0) editing = true
    }

    // Focus must never be left on a node that is about to leave composition. When it is, Compose
    // clears focus from the whole window, and the window hands it to the first thing on screen:
    // the top of the side rail. So the box stays focusable while the text field is up (the
    // field's focus sits inside it), and on the way out the box takes focus back before the field
    // is removed: press Back and then Down and the remote carries on from here.
    fun stopEditing() {
        keyboard?.hide()
        runCatching { box.requestFocus() }
        editing = false
    }

    Row(
        modifier
            .focusRequester(box)
            .height(56.dp)
            .clip(CircleShape)
            // Focus has to be as loud here as everywhere else on this screen, where a focused
            // control either fills with Accent or draws a 3dp border.
            .background(if (active) Tone.surfaceHigh else Tone.surface)
            .border(
                width = if (active) Focus.Edge else Floating.Border,
                color = if (active) Tone.accent else Tone.outline,
                shape = CircleShape,
            )
            .clickable(
                interactionSource = interactions,
                indication = null,
            ) { editing = true }
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MagnifierGlyph(if (active) Tone.accent else Tone.muted)
        Spacer(Modifier.width(14.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (editing) {
                val held = com.tmplayer.ui.components.rememberHeldText(value)
                // HX49: a text from elsewhere (Clear, a recent search tapped) can shrink the field
                // while the IME still holds a focus-rect query built for the old, longer text, a
                // known class of Compose foundation crash. Dropping focus and taking it back gives
                // the IME a fresh layout to ask about instead of a stale one.
                LaunchedEffect(held.value.text) {
                    if (held.externalChange && focused) {
                        reclaimingFocus = true
                        focusManager.clearFocus(force = true)
                        runCatching { field.requestFocus() }
                        reclaimingFocus = false
                    }
                }
                BasicTextField(
                    value = held.value,
                    onValueChange = { held.edit(it, onValueChange) },
                    singleLine = true,
                    // BasicTextField does not read LocalContentColor: without an explicit colour
                    // it paints black, which is invisible on this background.
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Tone.text),
                    cursorBrush = SolidColor(Tone.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            stopEditing()
                            onSubmit?.invoke()
                        },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(field)
                        // While the TV keyboard is up it takes the remote's keys before the app
                        // sees them, so a key that arrives here means the keyboard is down (Back
                        // closed it). A text field keeps the D-pad for its cursor, which in an
                        // empty single line field goes nowhere: focus was trapped, with Back and
                        // OK eaten too. The arrows leave the field, OK brings the keyboard back
                        // and Back ends editing.
                        .onPreviewKeyEvent { event ->
                            val direction = when (event.key) {
                                Key.DirectionUp -> FocusDirection.Up
                                Key.DirectionDown -> FocusDirection.Down
                                Key.DirectionLeft -> FocusDirection.Left
                                Key.DirectionRight -> FocusDirection.Right
                                else -> null
                            }
                            when {
                                direction != null -> {
                                    if (event.type == KeyEventType.KeyDown) focusManager.moveFocus(direction)
                                    true
                                }
                                event.key == Key.Back -> {
                                    if (event.type == KeyEventType.KeyUp) stopEditing()
                                    true
                                }
                                event.key == Key.DirectionCenter -> {
                                    if (event.type == KeyEventType.KeyUp) keyboard?.show()
                                    true
                                }
                                else -> false
                            }
                        }
                        .onFocusChanged { state ->
                            // onFocusChanged fires once with isFocused = false as the field is
                            // first composed, before the focus request lands. Acting on that
                            // would close editing again and the keyboard would never appear.
                            if (state.isFocused) {
                                everFocused = true
                            } else if (everFocused && !reclaimingFocus) {
                                editing = false
                            }
                        },
                )
                if (value.isEmpty() && dictationHint != null) {
                    Text(
                        dictationHint,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Tone.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                LaunchedEffect(Unit) {
                    everFocused = false
                    runCatching { field.requestFocus() }
                    keyboard?.show()
                }
                BackHandler { stopEditing() }
            } else {
                Text(
                    value.ifEmpty { placeholder },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (value.isEmpty()) Tone.muted else Tone.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The search field's magnifier, the same glyph as every other search in the app. */
@Composable
private fun MagnifierGlyph(color: androidx.compose.ui.graphics.Color) {
    Icon(TmIcons.Search, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
}
