package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import com.tmplayer.ui.theme.trackedFor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import com.tmplayer.ui.components.TmAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.components.EmptyArt
import com.tmplayer.ui.components.MediaArt
import com.tmplayer.ui.components.SlowAnswer
import com.tmplayer.ui.components.StateAction
import com.tmplayer.ui.components.UiState
import androidx.compose.material3.SuggestionChip
import kotlinx.coroutines.delay
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.theme.Tone

/**
 * One screen's worth of [UiState]: a spinner (with a first-time tip, and "Telegram is slow to
 * answer" once the wait runs long), an error with Try again and Send logs ([SendLogsActions]), an
 * empty line with its one action, or content.
 *
 * With [skeleton], the wait draws placeholders shaped like the content instead of the spinner, so
 * nothing moves when it arrives; the tip and the slow line then sit over the foot of them.
 */
@Composable
fun <T> StateBox(
    state: UiState<T>,
    onRetry: (() -> Unit)? = null,
    onAction: ((StateAction) -> Unit)? = null,
    slowAfterMs: Long = SlowAnswer.AFTER_MS,
    skeleton: (@Composable () -> Unit)? = null,
    content: @Composable (T) -> Unit,
) {
    val s = LocalStrings.current
    when (state) {
        is UiState.Content -> content(state.value)
        is UiState.Loading -> if (skeleton != null) {
            Box(Modifier.fillMaxSize()) {
                skeleton()
                Column(
                    Modifier.align(Alignment.BottomCenter).padding(24.dp).widthIn(max = 480.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.tip?.let {
                        Surface(shape = MaterialTheme.shapes.medium, color = Tone.surfaceHigh, tonalElevation = 2.dp) {
                            Text(it, color = Tone.muted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.padding(12.dp))
                        }
                    }
                    if (onRetry != null) SlowAnswerLine(state, onRetry, slowAfterMs)
                }
            }
        } else Centred {
            CircularProgressIndicator()
            Text(state.label, color = Tone.muted)
            state.tip?.let { Text(it, color = Tone.muted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
            if (onRetry != null) SlowAnswerLine(state, onRetry, slowAfterMs)
        }
        is UiState.Empty -> Centred {
            EmptyArt(state.picture, modifier = Modifier.padding(bottom = 4.dp))
            Text(state.message, color = Tone.muted, textAlign = TextAlign.Center)
            val action = state.action
            if (action != null && onAction != null) OutlinedButton(onClick = { onAction(action) }) { Text(action.label) }
        }
        is UiState.Error -> Centred {
            EmptyArt(TmIcons.Warning, tone = Tone.danger, modifier = Modifier.padding(bottom = 4.dp))
            Text(state.message, textAlign = TextAlign.Center)
            if (onRetry != null) OutlinedButton(onClick = onRetry) { Text(s.commonTryAgain) }
            // The logs, for when trying again is not enough: saved and shown, never sent.
            SendLogsActions()
        }
    }
}

/** Nothing until the wait for [key] passes [afterMs], then the line and a Retry button. */
@Composable
internal fun SlowAnswerLine(key: Any, onRetry: () -> Unit, afterMs: Long = SlowAnswer.AFTER_MS) {
    var slow by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        delay(afterMs)
        slow = true
    }
    if (!slow) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(SlowAnswer.MESSAGE)
        OutlinedButton(onClick = onRetry) { Text(SlowAnswer.RETRY) }
    }
}

/**
 * The recent searches as a row of chips under a page header, with Clear at the end: shown while
 * the search field is empty, since a chip is there to save typing a search again.
 */
@Composable
fun RecentSearchRow(searches: List<String>, onPick: (String) -> Unit, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    if (searches.isEmpty()) return
    Row(
        modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(s.browseRecentSearches, style = MaterialTheme.typography.bodySmall, color = Tone.muted)
        for (recent in searches) {
            SuggestionChip(
                onClick = { onPick(recent) },
                label = { Text(recent, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                icon = { Icon(TmIcons.History, contentDescription = null, modifier = Modifier.size(18.dp)) },
                // Long searches share what the row has left, cut short, so Clear stays on screen.
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        TextButton(onClick = onClear) { Text(s.commonClear) }
    }
}

/**
 * A page or a list with nothing in it: the empty pages' picture ([EmptyArt], as the phone and the
 * television draw it) over one muted line, centred.
 */
@Composable
fun EmptyNote(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    /** The one way on, drawn as a button under the line, as [StateBox] draws an empty state's action. */
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Centred {
        EmptyArt(icon, modifier = Modifier.padding(bottom = 4.dp))
        Text(text, color = Tone.muted, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) OutlinedButton(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
fun Centred(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 480.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) { content() }
    }
}

/**
 * The page's search field. "/" and Ctrl+F land here through [focus]; Esc first clears it and lets
 * go of the focus, and only a second Esc goes back a page. [onDown] is the Down arrow, which moves
 * into the results below.
 */
@Composable
fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
    placeholder: String,
    focus: FocusRequester,
    modifier: Modifier = Modifier,
    onDown: (() -> Unit)? = null,
) {
    val s = LocalStrings.current
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    BackHandler(enabled = focused || query.isNotEmpty()) {
        onQuery("")
        focusManager.release()
    }
    // A pill of one fixed height, the chat bar's search look, rather than Material's 56 dp outlined
    // field: the clear button's slot is always kept, so typing never changes its size or moves it.
    val shape = CircleShape
    Row(
        modifier
            .height(SEARCH_HEIGHT)
            .clip(shape)
            .background(Tone.surfaceHigh)
            .border(if (focused) 1.5.dp else 1.dp, if (focused) Tone.accent else Tone.outline.copy(alpha = 0.5f), shape)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TmIcons.Search, contentDescription = null, tint = Tone.muted, modifier = Modifier.size(18.dp))
        val held = com.tmplayer.ui.components.rememberHeldText(query)
        BasicTextField(
            value = held.value,
            onValueChange = { held.edit(it, onQuery) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Tone.text),
            cursorBrush = SolidColor(Tone.accent),
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
                .focusRequester(focus)
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    // Down from the search field goes to the results, the way a browser's does.
                    if (onDown != null && event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                        onDown()
                        true
                    } else {
                        false
                    }
                },
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = Tone.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    field()
                }
            },
        )
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            if (query.isNotEmpty()) {
                BarTooltip(s.browseClearSearch, null, below = true) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable(role = Role.Button, onClickLabel = s.browseClearSearch) { onQuery("") },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(TmIcons.Close, contentDescription = s.browseClearSearch, tint = Tone.muted, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

/** The search field's one height, a header button's, so the heading's row keeps its own. */
private val SEARCH_HEIGHT = 40.dp

private fun FocusManager.release() = clearFocus(force = true)

/**
 * How far a page's content sits in from the sides of the window: 24 dp, and 32 dp once the window
 * is wider than 1440 dp, where 24 starts to look like the page forgot its margins. The heading,
 * the chips under it and the list or grid below all read it, so they start on one line.
 */
@Composable
fun pageMargin(): Dp {
    val window = LocalWindowInfo.current.containerSize
    val wide = with(LocalDensity.current) { window.width.toDp() } > WIDE_WINDOW
    return if (wide) 32.dp else 24.dp
}

/** The window width past which [pageMargin] widens. */
private val WIDE_WINDOW = 1440.dp

/**
 * A page title with an optional line under it and actions to the right. The title is the type
 * scale's display size, the one step above the headlines kept for a hero and a page's own name.
 *
 * [onArt] is for a header standing on a picture under [com.tmplayer.ui.theme.Scrims.headerTop]
 * (Home over its hero): the words and the action icons go to [Tone.onArt] in either theme.
 *
 * Where the app draws its own window buttons ([LocalWindowChrome]), they end the row, and the
 * row's empty space moves the window (a double click maximizes it).
 */
@Composable
fun PageHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    /** The [subtitle] is on its way: a placeholder holds its line, so the page does not jump. */
    subtitlePending: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    onArt: Boolean = false,
    actions: @Composable () -> Unit = {},
) {
    val ink = if (onArt) Tone.onArt else LocalContentColor.current
    val chrome = LocalWindowChrome.current
    // A header scrolled out of sight hands the window buttons back to the window (see
    // WindowControlsFallback), so they never scroll away with it.
    var inSight by remember { mutableStateOf(true) }
    CompositionLocalProvider(LocalContentColor provides ink) {
        Row(
            modifier
                .then(
                    if (chrome == null) {
                        Modifier
                    } else {
                        Modifier.onGloballyPositioned { inSight = it.boundsInWindow().height >= it.size.height / 2f }
                    },
                )
                .windowDrag()
                .padding(horizontal = pageMargin(), vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val heading: @Composable (Modifier) -> Unit = { headingModifier ->
                Column(headingModifier) {
                    Text(
                        title,
                        style = MaterialTheme.typography.displaySmall.trackedFor(title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = if (onArt) Tone.onArtMuted else Tone.muted)
                    } else if (subtitlePending) {
                        SubtitleSkeleton()
                    }
                }
            }
            val end: @Composable () -> Unit = {
                actions()
                // The big-screen mode keeps them on its bar at the top (TvTopBar).
                if (chrome != null && !BigScreen.on) {
                    if (inSight) RegisterWindowControls()
                    WindowControls()
                }
            }
            leading?.invoke()
            heading(Modifier.weight(1f))
            end()
        }
    }
}

/**
 * The prompt before anything hard to undo, the desktop's twin of the Android `TvConfirm`: a title,
 * what will happen, an optional line on what will not, and Cancel beside the confirm button. Focus
 * starts on Cancel, so an Enter pressed out of habit is the cheap mistake; Esc and a click outside
 * cancel too.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    detail: String? = null,
    destructive: Boolean = true,
    cancelLabel: String? = null,
    /** Drawn under the message and detail, for a choice that belongs to the question, such as a tick box. */
    extra: (@Composable () -> Unit)? = null,
    /** Focus starts on confirm instead, for an invitation where the confirm is the safe press. */
    focusConfirm: Boolean = false,
) {
    val s = LocalStrings.current
    val cancelFocus = remember { FocusRequester() }
    val confirmFocus = remember { FocusRequester() }
    TmAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message)
                if (detail != null) Text(detail, color = Tone.muted, style = MaterialTheme.typography.bodySmall)
                extra?.invoke()
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.focusRequester(confirmFocus)) {
                Text(confirmLabel, color = if (destructive) Tone.danger else Tone.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusRequester(cancelFocus)) { Text(cancelLabel ?: s.commonCancel) }
        },
    )
    LaunchedEffect(Unit) { runCatching { (if (focusConfirm) confirmFocus else cancelFocus).requestFocus() } }
}

/** A chat's picture, or its first letter on the surface colour while there is none. */
@Composable
fun ChatAvatar(miniThumbnail: ByteArray?, photoFileId: Int, title: String, size: Dp) {
    MediaArt(miniThumbnail, photoFileId, Modifier.size(size).clip(CircleShape)) {
        Text(title.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = Tone.muted)
    }
}
