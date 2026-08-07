package com.tmplayer.ui.components

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Dictation through whatever speech recogniser the TV already has — on this stick, the mic in
 * the remote.
 *
 * Handing the job to the system recogniser rather than driving `SpeechRecognizer` ourselves means
 * no `RECORD_AUDIO` permission: the audio is captured by the recogniser app, and TMPlayer only
 * ever receives the finished text.
 *
 * Returns null when no recogniser is installed, so callers can leave the mic button out entirely
 * instead of offering a button that does nothing.
 */
@Composable
fun rememberVoiceSearch(prompt: String, onResult: (String) -> Unit): (() -> Unit)? {
    val context = LocalContext.current

    val available = remember {
        val probe = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        probe.resolveActivity(context.packageManager) != null
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

    if (!available) return null

    return {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        }
        // A missing recogniser at this point means it was uninstalled since the probe; swallowing
        // it keeps a dead mic button from taking the whole screen down.
        runCatching { launcher.launch(intent) }
    }
}
