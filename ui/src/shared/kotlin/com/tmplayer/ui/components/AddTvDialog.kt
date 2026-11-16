package com.tmplayer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tmplayer.cast.dlna.ManualAddress
import com.tmplayer.i18n.Strings
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A TV that was added by its address and is remembered: [key] is its link, what [AddTvDialog]'s `onRemove` is given. */
data class SavedTv(val key: String, val name: String, val address: String)

/**
 * "Add a TV by its address" on the phone and the desktop: one field for an IP address or a
 * description link, Add TV (or the keyboard's Done) asks the TV who it is, a working line while it
 * does, and a plain sentence when it cannot (an address that is not on this network, a name instead
 * of an address, a TV that is off or answers with something that is not a TV). Under it the TVs
 * remembered from before, each with a Remove. The dialog closes once a TV was found; [onAdd] saves
 * it and puts it in the picker's list, and runs off the main thread.
 */
@Composable
fun AddTvDialog(
    saved: List<SavedTv>,
    onAdd: (String) -> ManualAddress.Outcome,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    var text by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    // The field is not attached the moment the dialog opens: ask again until it takes the keyboard.
    LaunchedEffect(Unit) {
        repeat(10) {
            if (runCatching { focus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            delay(50)
        }
    }

    val submit: () -> Unit = {
        if (!working) {
            working = true
            error = null
            val typed = text
            scope.launch { addTv(s, typed, onAdd, onDismiss, { error = it }, { working = false }) }
        }
    }
    TmAlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(s.castAddEntry, maxLines = 2) },
        text = {
            Column(
                Modifier.heightIn(max = dialogBodyMax(420.dp)).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    singleLine = true,
                    enabled = !working,
                    label = { Text(s.castAddAddressHint) },
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(INPUT_TAG),
                )
                if (working) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(s.castAddWorking, fontSize = 14.sp)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp, modifier = Modifier.liveMessage().testTag(ERROR_TAG)) }
                if (saved.isNotEmpty()) {
                    Text(s.castAddSaved, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    saved.forEach { tv ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(tv.name.ifBlank { tv.address }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (tv.name.isNotBlank()) {
                                    Text(tv.address, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            val label = s.castAddRemove(tv.name.ifBlank { tv.address })
                            IconButton(onClick = { onRemove(tv.key) }, modifier = Modifier.semantics { contentDescription = label }) {
                                Icon(TmIcons.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = submit, enabled = !working && text.isNotBlank()) { Text(s.castAddConfirm) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text(s.commonCancel) } },
    )
}

internal const val INPUT_TAG = "add-tv-input"
internal const val ERROR_TAG = "add-tv-error"

private suspend fun addTv(
    s: Strings,
    typed: String,
    onAdd: (String) -> ManualAddress.Outcome,
    onDone: () -> Unit,
    showError: (String) -> Unit,
    finished: () -> Unit,
) {
    val outcome = try {
        withContext(Dispatchers.IO) { onAdd(typed) }
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        // Whatever failed, the dialog says it could not and stays, never a crash.
        ManualAddress.Outcome.Silent(typed.trim().take(60))
    }
    finished()
    if (outcome is ManualAddress.Outcome.Found) onDone() else showError(addTvMessage(s, outcome))
}

/** The sentence for an address that did not become a TV. */
fun addTvMessage(s: Strings, outcome: ManualAddress.Outcome): String = when (outcome) {
    is ManualAddress.Outcome.Found -> ""
    is ManualAddress.Outcome.Silent -> s.castAddErrorSilent(address = outcome.address)
    is ManualAddress.Outcome.Broken -> s.castAddBroken(address = outcome.address)
    is ManualAddress.Outcome.Invalid -> when (outcome.problem) {
        ManualAddress.Problem.NotLocal -> s.castAddErrorPublic
        ManualAddress.Problem.Hostname, ManualAddress.Problem.Ipv6 -> s.castAddErrorName
        else -> s.castAddErrorInvalid
    }
}
