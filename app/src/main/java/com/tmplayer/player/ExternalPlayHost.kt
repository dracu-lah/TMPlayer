package com.tmplayer.player

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SettingsStore
import com.tmplayer.ui.components.ChoiceLine
import com.tmplayer.ui.components.ChoiceList
import com.tmplayer.ui.components.ChoiceSheet
import com.tmplayer.ui.components.FocusOnOpen
import com.tmplayer.ui.i18n.LocalStrings

/**
 * The windows of the default player (PR4), drawn from wherever the main screen is: the chooser
 * ([ExternalChooserSheet]), the offer after an app closed right away, and the plumbing the
 * router needs (the result launcher, the screen's coming and going). Put once in the main screen;
 * every other caller goes through [ExternalPlayRouter].
 *
 * [playHere] starts TMPlayer's own player for a video (the main screen's `play`), which the
 * router calls for "Play in TMPlayer", Just once on TMPlayer, and every fall back.
 */
@Composable
fun ExternalPlayHost(settings: SettingsStore, playHere: (MediaItem, String) -> Unit) {
    val context = LocalContext.current
    val play by rememberUpdatedState(playHere)
    val results = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        ExternalPlayRouter.onResult(context, settings, result.data)
    }
    DisposableEffect(results) {
        ExternalPlayRouter.launcher = { intent: Intent -> results.launch(intent) }
        ExternalPlayRouter.playBuiltIn = { item, chat -> play(item, chat) }
        onDispose {
            ExternalPlayRouter.launcher = null
            ExternalPlayRouter.playBuiltIn = null
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { ExternalPlayRouter.onStopped() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { ExternalPlayRouter.onResumed(context) }

    val chooser by ExternalPlayRouter.chooser.collectAsStateWithLifecycle()
    chooser?.let { request ->
        ExternalChooserSheet(
            apps = request.apps,
            gone = request.gone?.let { gone -> ExternalPlay.nameOf(gone.packageName) },
            onChoose = { choice, always -> ExternalPlayRouter.chosen(context, settings, request, choice, always) },
            onDismiss = ExternalPlayRouter::dismissChooser,
        )
    }

    val closed by ExternalPlayRouter.closed.collectAsStateWithLifecycle()
    closed?.let { offer ->
        val s = LocalStrings.current
        val first = remember { FocusRequester() }
        ChoiceSheet(
            title = s.externalClosedTitle(app = offer.appLabel),
            note = s.externalClosedNote,
            onDismiss = ExternalPlayRouter::dismissClosed,
            onClose = ExternalPlayRouter::dismissClosed,
        ) {
            ChoiceList {
                ChoiceLine(
                    title = s.externalPlayInTmplayer,
                    modifier = Modifier.focusRequester(first),
                    onClick = ExternalPlayRouter::playHereFromClosed,
                )
                Spacer(Modifier.size(4.dp))
            }
            FocusOnOpen(first)
        }
    }
}
