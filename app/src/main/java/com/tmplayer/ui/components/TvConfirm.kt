package com.tmplayer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.MaterialTheme as M3MaterialTheme
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.ui.components.TmAlertDialog
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Danger
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.floatingSurface

/**
 * The one prompt shape used everywhere something is about to be deleted, signed out of, or
 * otherwise made hard to undo.
 *
 * Focus starts on Cancel. On a remote the confirm button is one careless press away, and the
 * cheap mistake should be the default.
 */
@Composable
fun TvConfirm(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    detail: String? = null,
    cancelLabel: String = LocalStrings.current.commonCancel,
    destructive: Boolean = true,
    icon: ImageVector = if (destructive) TmIcons.Warning else TmIcons.Info,
    /** Drawn under the message and detail, for a choice that belongs to the question, such as a tick box. */
    extra: (@Composable () -> Unit)? = null,
    /** Focus starts on confirm instead, for an invitation where the confirm is the safe press. */
    focusConfirm: Boolean = false,
    /** The outside service the question is about (HX93): its badge replaces the icon, drawn only. */
    service: Service? = null,
    /**
     * A field in [extra] that takes the remote first (WP-11): a key to type. The question is the
     * field, so the remote starts there instead of on a button.
     */
    focusField: FocusRequester? = null,
) {
    val cancelFocus = remember { FocusRequester() }
    val confirmFocus = remember { FocusRequester() }
    val touch = isTouch()

    // A phone gets Material's own dialog, which brings for nothing what the panel below cannot: a
    // tap on the scrim dismisses it, the predictive-back animation works, and a screen reader
    // announces it as a dialog rather than as a column of text that appeared.
    if (touch) {
        TmAlertDialog(
            onDismissRequest = onDismiss,
            // No colours stated: the scheme already answers the container, the title, the body and
            // the error red a destructive press is entitled to.
            icon = {
                if (service != null) {
                    ServiceBadge(service, size = ServiceBadgeSize.Row)
                } else {
                    M3Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (destructive) {
                            M3MaterialTheme.colorScheme.error
                        } else {
                            M3MaterialTheme.colorScheme.primary
                        },
                    )
                }
            },
            title = { M3Text(title) },
            text = {
                // Scrolls when it has to. Material's dialog does not scroll its text by itself, so
                // a phone on its side (about 360 dp tall) clipped the detail line under the icon,
                // the title and the message, and at a large font size the message as well.
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    M3Text(message, style = M3MaterialTheme.typography.bodyMedium)
                    if (detail != null) {
                        M3Text(
                            detail,
                            style = M3MaterialTheme.typography.bodySmall,
                            color = M3MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    extra?.invoke()
                }
            },
            // A press that cannot be taken back stays a text button in the error colour: filling
            // it would make the loud option the one the thumb lands on by habit.
            confirmButton = {
                if (destructive) {
                    TextButton(
                        onClick = onConfirm,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = M3MaterialTheme.colorScheme.error,
                        ),
                    ) { M3Text(confirmLabel) }
                } else {
                    Button(onClick = onConfirm) { M3Text(confirmLabel) }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { M3Text(cancelLabel) }
            },
        )
        return
    }

    // In its own window, so every prompt sits over the whole app, the navigation rail included.
    FloatingWindow(onDismiss = onDismiss) {
        // Sized to the words, not to the screen. These prompts carry one line of question and
        // two buttons; a panel wide enough for a paragraph just leaves the viewer's eye
        // travelling across empty space to find the answer.
        val panel = min(maxWidth - PhonePad.Side * 2, PANEL_MAX)

        Column(
            Modifier
                .width(panel)
                .floatingSurface()
                .padding(horizontal = 32.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (service != null) {
                    ServiceBadge(service, size = ServiceBadgeSize.Credit)
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (destructive) Danger else Tone.accent,
                        modifier = Modifier.size(28.dp),
                    )
                }
                // A Material dialog's headline, as the pickers' (ChoiceSheet) is.
                Text(title, style = MaterialTheme.typography.headlineSmall, color = Tone.text)
            }
            Text(message, style = MaterialTheme.typography.bodyMedium, color = Tone.text)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = Tone.muted)
            }
            extra?.invoke()

            // Cancel is the quiet one and confirm the loud one, stated by which button is used
            // rather than by a colour table each caller passes in. At the bottom end, where a
            // Material dialog keeps its actions and the pickers keep Close.
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                // 48 dp targets: the buttons alone are 40 dp (F-17).
                TmSecondaryButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp).focusRequester(cancelFocus),
                ) {
                    Text(cancelLabel)
                }
                TmButton(onClick = onConfirm, destructive = destructive, modifier = Modifier.heightIn(min = 48.dp).focusRequester(confirmFocus)) {
                    Text(confirmLabel)
                }
            }
        }

        LaunchedEffect(Unit) { runCatching { (focusField ?: if (focusConfirm) confirmFocus else cancelFocus).requestFocus() } }
    }
}

/** As wide as this prompt ever gets, on any screen. */
private val PANEL_MAX = 520.dp


