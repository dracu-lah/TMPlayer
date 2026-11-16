package com.tmplayer.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme as M3
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.ui.components.dpadLeavesField
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.components.liveMessage
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone

/**
 * The field every sign-in pane types into, stated once so that fields sitting beside each other
 * cannot stop matching.
 *
 * A phone gets the platform's own outlined field, floating label and all, because a sign-in is the
 * one screen where looking like every other Android sign-in is the point. The television keeps the
 * dark rounded box, which is sized for a remote and for a room.
 */
@Composable
fun PaneField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    /** Drawn inside the field, ahead of the text: the "+" on a dial code. */
    prefix: String? = null,
    /**
     * What the field is called once something has been typed into it. Defaults to the placeholder,
     * which is the right answer everywhere except the dial code, whose placeholder is "00".
     */
    label: String = placeholder,
    /**
     * An icon button at the end of the field on a phone: the password's show and hide. A remote
     * cannot reach into a field, so a television gets its own button beside the form instead.
     */
    trailing: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    /** False while the field is not ready to be typed into: the sign in still finding the country. */
    enabled: Boolean = true,
) {
    // The text and caret held here, so a number cut to digits or a code cut to its length keeps
    // the caret where the typing was (HeldText).
    val held = com.tmplayer.ui.components.rememberHeldText(value)
    if (isTouch()) {
        OutlinedTextField(
            value = held.value,
            onValueChange = { held.edit(it, onValueChange) },
            enabled = enabled,
            singleLine = true,
            label = { M3Text(label) },
            // Both label and placeholder: the placeholder shows the shape of the answer, and a
            // floating label leaves room for it.
            placeholder = { M3Text(placeholder) },
            prefix = prefix?.let { { M3Text(it) } },
            trailingIcon = trailing,
            isError = isError,
            textStyle = M3.typography.titleMedium,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            shape = M3.shapes.medium,
            modifier = modifier,
        )
        return
    }

    Box(
        modifier
            .height(FIELD_HEIGHT)
            .clip(RoundedCornerShape(Corner.Medium))
            .background(Tone.surface)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        // Closed, the field reads as the TV buttons do when they cannot be pressed: muted words.
        val ink = if (enabled) Tone.text else Tone.muted
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (prefix != null) {
                Text(prefix, style = MaterialTheme.typography.titleLarge, color = ink)
            }
            BasicTextField(
                value = held.value,
                onValueChange = { held.edit(it, onValueChange) },
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge.copy(color = ink),
                cursorBrush = SolidColor(Tone.accent),
                visualTransformation = visualTransformation,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                modifier = Modifier.fillMaxWidth().dpadLeavesField(),
                // Placeholder and field must be stacked, not siblings: emitted flat they are laid
                // out one after the other and the text lands off to the side.
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                placeholder,
                                style = MaterialTheme.typography.titleLarge,
                                color = Tone.muted,
                            )
                        }
                        inner()
                    }
                },
            )
        }
    }
}

/**
 * What went wrong on a sign-in pane, in the danger colour and read out by a screen reader the moment
 * it appears (a rejected code that nobody hears is a button that seems to do nothing).
 */
@Composable
internal fun PaneError(error: String, modifier: Modifier = Modifier) {
    Text(
        error,
        style = MaterialTheme.typography.bodyLarge,
        color = Tone.danger,
        modifier = modifier.liveMessage(),
    )
}

/**
 * The arrow at the top left of a sign-in pane, on a phone.
 *
 * A phone's system Back is a gesture from the edge that plenty of people never learn, and this
 * flow has several panes deep enough to strand somebody on. The remote has a Back button that is
 * unmissable and a focus model that an arrow up here would only get in the way of, so on a
 * television the pane's text buttons remain the way out and this draws nothing.
 */
@Composable
fun BackArrow(onBack: () -> Unit, description: String = LocalStrings.current.commonBack) {
    com.tmplayer.ui.components.IconTip(description, side = com.tmplayer.ui.components.TipSide.Below) {
        IconButton(onClick = onBack, modifier = Modifier.size(BACK_TARGET)) {
            Icon(
                TmIcons.ArrowBack,
                contentDescription = description,
                tint = Tone.text,
            )
        }
    }
}

/**
 * A row that opens something rather than accepting text: the country the number belongs to, drawn
 * as Telegram and WhatsApp draw it, the flag and name with a chevron at the end. While [loading]
 * (the country still being guessed) a shimmer stands where the words go and the row cannot be
 * pressed; the words fade in over it when they arrive.
 */
@Composable
fun PaneChooser(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Words before the chevron, if any. */
    trailing: String? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val pressable = enabled && !loading
    // The shimmer already says "not yet"; dimming it as well would leave nothing to see.
    val dim = if (enabled || loading) 1f else DISABLED_ALPHA
    if (isTouch()) {
        ListItem(
            headlineContent = {
                GuessFade(loading, skeletonWidth = 160.dp) {
                    M3Text(label, style = M3.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (trailing != null) M3Text(trailing, style = M3.typography.labelLarge)
                    Icon(TmIcons.ChevronRight, contentDescription = null, tint = Tone.muted)
                }
            },
            colors = ListItemDefaults.colors(containerColor = Tone.surface),
            modifier = modifier
                .fillMaxWidth()
                .clip(M3.shapes.medium)
                // Spelled out because a ListItem is a row of text until something says otherwise,
                // and a screen reader has no other way to know this row can be pressed.
                .clickable(enabled = pressable, role = Role.Button, onClick = onClick)
                .alpha(dim),
        )
        return
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(FIELD_HEIGHT)
            .clip(RoundedCornerShape(Corner.Medium))
            .background(Tone.surface)
            .clickable(enabled = pressable, role = Role.Button, onClick = onClick)
            .alpha(dim)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GuessFade(loading, skeletonWidth = 200.dp, modifier = Modifier.defaultMinSize(minWidth = 0.dp).weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleLarge, color = Tone.text, maxLines = 1)
            }
            if (trailing != null) Text(trailing, style = MaterialTheme.typography.bodyLarge, color = Tone.muted)
            Icon(TmIcons.ChevronRight, contentDescription = null, tint = Tone.muted)
        }
    }
}

internal val FIELD_HEIGHT = 60.dp

/** Material's own figure for content that cannot be used right now. */
private const val DISABLED_ALPHA = 0.38f

// Material's own 48 dp minimum, spelled out because the icon inside it is 24.
private val BACK_TARGET = 48.dp
