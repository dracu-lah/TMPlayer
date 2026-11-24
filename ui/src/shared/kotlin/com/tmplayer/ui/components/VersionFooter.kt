package com.tmplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.data.Updates
import com.tmplayer.data.VersionInfo
import com.tmplayer.data.VersionLine
import com.tmplayer.data.VersionState
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.focusRingOnTv
import kotlinx.coroutines.delay

/** The tag of the footer's copy target, for tests. */
const val VERSION_FOOTER_TAG = "versionFooter"

/** The tag of the Settings line, for tests. */
const val VERSION_NOTE_TAG = "versionNote"

/** How long the footer says "Version details copied" before the version returns. */
private const val COPIED_MS = 2_000L

/**
 * The update state beside the version, read from the one check the app already runs
 * ([Updates.state], [Updates.answeredAt]); no request of its own (WP-104).
 */
@Composable
fun rememberVersionState(): VersionState {
    val update by Updates.state.collectAsState()
    val answered by Updates.answeredAt.collectAsState()
    return VersionLine.state(update, Updates.enabled, answered)
}

/** The words for [state], or null when the version shows alone. */
@Composable
fun versionStateText(state: VersionState): String? {
    val s = LocalStrings.current
    return when (state) {
        VersionState.None -> null
        VersionState.Off -> s.updateStateOff
        VersionState.Checking -> s.updateChecking
        VersionState.UpToDate -> s.updateStateUpToDate
        VersionState.Failed -> s.updateStateFailed
        is VersionState.Available -> s.updateStateAvailable(state.version)
    }
}

/**
 * What a press on a version does: puts "TMPlayer <version> (<build>) <device>" on the clipboard
 * and says [copied] for two seconds (WP-104). A second press inside the two seconds copies again
 * and restarts the wait; [copied] stays true, so the node a reader watches changes once. Nothing is
 * claimed when the clipboard refuses.
 */
class VersionCopier internal constructor(
    private val info: VersionInfo,
    private val put: (String) -> Boolean,
) {
    var copied by mutableStateOf(false)
        private set
    internal var presses by mutableIntStateOf(0)
        private set

    fun copy() {
        val text = VersionLine.copyText(info.version, info.build, info.device)
        if (runCatching { put(text) }.getOrDefault(false)) {
            copied = true
            presses++
        }
    }

    internal fun done() {
        copied = false
    }
}

/** A [VersionCopier] over Compose's clipboard, the same call on Android, the desktop and iOS. */
@Composable
fun rememberVersionCopier(info: VersionInfo, copy: ((String) -> Boolean)? = null): VersionCopier {
    val clipboard = LocalClipboardManager.current
    val copier = remember(info, clipboard, copy) {
        VersionCopier(info, copy ?: { text -> clipboard.setText(AnnotatedString(text)); true })
    }
    LaunchedEffect(copier.presses) {
        if (copier.presses > 0) {
            delay(COPIED_MS)
            copier.done()
        }
    }
    return copier
}

/**
 * The version, the build and the update state in one line, which a press copies for a support
 * request and says so aloud (WP-104).
 *
 * The line is a [FlowRow]: it wraps at a large font and mirrors in RTL. While the state is
 * [VersionState.Available] and [onUpdate] is given, its words are a button of their own that opens
 * the update dialog; the copy target is then the version alone. The copy target is always one node
 * (its words swap for "Version details copied" in a polite live region), so a remote's focus on it
 * survives the swap.
 *
 * [tv] draws the television's row look (the fill and ring a remote needs); [focusRequester] lets
 * the menu place focus on it. Elsewhere it is a 48 dp tap target with the touch ripple.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VersionFooter(
    info: VersionInfo,
    modifier: Modifier = Modifier,
    state: VersionState = rememberVersionState(),
    onUpdate: (() -> Unit)? = null,
    tv: Boolean = false,
    focusRequester: FocusRequester? = null,
    /** Puts the text on the clipboard for a platform whose clipboard is not Compose's; null uses Compose's. */
    copy: ((String) -> Boolean)? = null,
) {
    val s = LocalStrings.current
    val copier = rememberVersionCopier(info, copy)
    val stateText = versionStateText(state)
    val split = onUpdate != null && state is VersionState.Available && !tv
    val shape = RoundedCornerShape(Corner.Medium)
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val lineColor = if (tv && focused) Tone.onFocusFill else Tone.muted

    val target = Modifier
        .heightIn(min = 48.dp)
        .then(if (tv) Modifier.clip(shape).background(if (focused) Tone.focusFill else Tone.surfaceHigh).focusRingOnTv(focused, shape) else Modifier)
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .then(
            if (tv) {
                Modifier.clickable(interactionSource = interactions, indication = null, role = Role.Button, onClickLabel = s.aboutVersionCopy, onClick = copier::copy)
            } else {
                Modifier.clickable(role = Role.Button, onClickLabel = s.aboutVersionCopy, onClick = copier::copy)
            },
        )
        .padding(horizontal = if (tv) 18.dp else 14.dp, vertical = 8.dp)

    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalArrangement = Arrangement.Center,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (copier.copied) {
                s.aboutVersionCopied
            } else {
                buildString {
                    append(s.aboutVersionBuild(VersionLine.shown(info.version), info.build))
                    if (stateText != null && !split) append(", ").append(stateText)
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = lineColor,
            modifier = (if (split && !copier.copied) Modifier else Modifier.fillMaxWidth())
                .then(target)
                // Polite: said once when the text becomes the confirmation, not on every show.
                .liveMessage()
                .testTag(VERSION_FOOTER_TAG),
        )
        if (split && !copier.copied && stateText != null) {
            Text(
                stateText,
                style = MaterialTheme.typography.bodyMedium,
                color = Tone.caution,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .then(if (onUpdate != null) Modifier.clickable(role = Role.Button, onClick = onUpdate) else Modifier)
                    .padding(horizontal = 14.dp, vertical = 14.dp),
            )
        }
    }
}

/**
 * The same line at the top of Settings: quiet, and a press copies it when [copyable]. Not a stop
 * for the remote on a television ([copyable] false: the account menu's footer is the copy target
 * there and the Settings rows keep their order), where it stays on one line.
 */
@Composable
fun VersionNote(
    info: VersionInfo,
    modifier: Modifier = Modifier,
    state: VersionState = rememberVersionState(),
    copyable: Boolean = true,
    onUpdate: (() -> Unit)? = null,
) {
    if (copyable) {
        VersionFooter(info, modifier.testTag(VERSION_NOTE_TAG), state, onUpdate)
        return
    }
    val s = LocalStrings.current
    val stateText = versionStateText(state)
    Text(
        buildString {
            append(s.aboutVersionBuild(VersionLine.shown(info.version), info.build))
            if (stateText != null) append(", ").append(stateText)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = Tone.muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(vertical = 4.dp).testTag(VERSION_NOTE_TAG),
    )
}
