package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.components.MediaArt
import com.tmplayer.ui.components.UiState
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.theme.Tone

/** One screen's worth of [UiState]: a spinner, an error with Try again, an empty line, or content. */
@Composable
fun <T> StateBox(
    state: UiState<T>,
    onRetry: (() -> Unit)? = null,
    content: @Composable (T) -> Unit,
) {
    when (state) {
        is UiState.Content -> content(state.value)
        is UiState.Loading -> Centred {
            CircularProgressIndicator()
            Text(state.label, color = Tone.muted)
        }
        is UiState.Empty -> Centred {
            Text(state.message, color = Tone.muted, textAlign = TextAlign.Center)
        }
        is UiState.Error -> Centred {
            Text(state.message, textAlign = TextAlign.Center)
            if (onRetry != null) OutlinedButton(onClick = onRetry) { Text("Try again") }
        }
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
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    BackHandler(enabled = focused || query.isNotEmpty()) {
        onQuery("")
        focusManager.release()
    }
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear search")
                }
            }
        },
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier
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
    )
}

private fun FocusManager.release() = clearFocus(force = true)

/** A page title with an optional line under it and actions to the right. */
@Composable
fun PageHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier.padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
        }
        actions()
    }
}

/** A chat's picture, or its first letter on the surface colour while there is none. */
@Composable
fun ChatAvatar(miniThumbnail: ByteArray?, photoFileId: Int, title: String, size: Dp) {
    MediaArt(miniThumbnail, photoFileId, Modifier.size(size).clip(CircleShape)) {
        Text(title.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = Tone.muted)
    }
}
