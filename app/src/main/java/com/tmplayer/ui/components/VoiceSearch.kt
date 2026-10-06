package com.tmplayer.ui.components

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.tmplayer.data.DeviceQuirks
import com.tmplayer.data.VoiceRoute

/**
 * Dictation through whatever speech recogniser the device already has, which on this stick is the
 * mic in the remote.
 *
 * Handing the job to the system recogniser rather than driving `SpeechRecognizer` ourselves means
 * no `RECORD_AUDIO` permission: the audio is captured by the recogniser app, and TMPlayer only
 * ever receives the finished text.
 *
 * On Fire OS there is no recogniser an app can use (see [com.tmplayer.data.RemoteQuirks]), so the
 * button calls [onKeyboard] instead, which opens the system keyboard: dictation lives there on a
 * Fire device. Returns null when neither route exists, so callers can leave the mic button out
 * entirely instead of offering a button that does nothing.
 */
@Composable
fun rememberVoiceSearch(
    prompt: String,
    onKeyboard: (() -> Unit)? = null,
    onResult: (String) -> Unit,
): (() -> Unit)? {
    val context = LocalContext.current

    val route = remember {
        val probe = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        val installed = probe.resolveActivity(context.packageManager) != null
        DeviceQuirks.remote(context).voiceRoute(installed)
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return@rememberLauncherForActivityResult
        onResult(spoken)
    }

    return when (route) {
        VoiceRoute.None -> null
        VoiceRoute.Keyboard -> onKeyboard
        VoiceRoute.Recognizer -> {
            {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
                }
                // A missing recogniser at this point means it was uninstalled since the probe;
                // swallowing it keeps a dead mic button from taking the whole screen down.
                runCatching { launcher.launch(intent) }
            }
        }
    }
}
