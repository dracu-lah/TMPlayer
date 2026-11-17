package com.tmplayer.cast

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.tmplayer.ui.components.AddTvDialog
import com.tmplayer.ui.components.showMessage
import com.tmplayer.ui.i18n.LocalStrings

/** Whether Settings shows the "Add a TV by its address" row: Android casts, iOS has no casting (CastOnIos.kt says false). */
const val ADD_TV_ENTRY_AVAILABLE: Boolean = true

/**
 * "Add a TV by its address" outside the Cast picker (Settings, Playback): the same [AddTvDialog]
 * the picker's last line opens, for a TV that discovery never finds, so a person does not have to
 * start a cast to reach it. Returns what the row calls; the dialog is drawn by this call.
 *
 * A second press while the dialog is open, or while Android's local network question is up, does
 * nothing more. Where Android 17 asks for the local network permission it is asked first, and a
 * refusal says so instead of opening a dialog that could only fail. A phone with no casting at
 * all (no DLNA store) says that casting needs Wi-Fi rather than doing nothing.
 */
@Composable
fun rememberAddTvEntry(): () -> Unit {
    val context = LocalContext.current
    val s = LocalStrings.current
    var adding by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        asking = false
        if (ok) adding = true else showMessage(context, s.castLocalNetworkDenied)
    }
    val manual = if (adding) Cast.support(context).manualTvs else null
    if (manual != null) {
        val saved by manual.saved.collectAsState()
        AddTvDialog(saved = saved, onAdd = manual::add, onRemove = manual::remove, onDismiss = { adding = false })
    }
    return {
        if (!adding && !asking) {
            when {
                Cast.support(context).manualTvs == null -> showMessage(context, s.castWifiOnly)
                LocalNetworkPermission.granted(context) -> adding = true
                else -> {
                    asking = true
                    runCatching { ask.launch(LocalNetworkPermission.NAME) }.onFailure {
                        asking = false
                        showMessage(context, s.castLocalNetworkDenied)
                    }
                }
            }
        }
    }
}
