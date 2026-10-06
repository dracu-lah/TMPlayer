package com.tmplayer.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.WhatsNew
import com.tmplayer.i18n.LanguageNotice
import com.tmplayer.i18n.Languages
import com.tmplayer.i18n.Strings
import com.tmplayer.i18n.Translator
import com.tmplayer.ui.onboarding.Onboarding
import com.tmplayer.ui.onboarding.systemLanguageName
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The Settings language row's second line: the language picked, named in itself, or "System
 * default" with the language that currently resolves to.
 */
fun Strings.languageRowDetail(saved: String): String =
    if (saved.isBlank()) {
        settingsLanguageDetailSystem(systemLanguageName() ?: Languages.find(Languages.ENGLISH)!!.name)
    } else if (saved.equals(Languages.PSEUDO, ignoreCase = true)) {
        Onboarding.PSEUDO_NAME
    } else {
        Languages.find(saved)?.name ?: saved
    }

/**
 * The one-time "Now in Español" card's state, the same on every device: [language] is the tag
 * to announce, or null when there is nothing to say, and [dismiss] remembers it was said. See
 * [LanguageNotice] for the rule.
 */
class LanguageNoticeState internal constructor(
    val language: String?,
    val dismiss: () -> Unit,
)

@Composable
fun rememberLanguageNotice(settings: SettingsStore): LanguageNoticeState {
    val scope = rememberCoroutineScope()
    val messages by Translator.active.collectAsState()
    // Null until read, so the card cannot flash up for the frame before the stored values arrive.
    val saved by settings.language.collectAsState(initial = null)
    val announced by settings.languageAnnounced.collectAsState(initial = null)
    val active = messages.tag
    val show = saved != null && announced != null &&
        LanguageNotice.shouldShow(saved!!, active, announced!!, Translator.hasCatalog(active))
    return LanguageNoticeState(
        language = if (show) active else null,
        dismiss = { scope.launch { settings.setLanguageAnnounced(active) } },
    )
}

/**
 * Whether the "What's new" sheet is up. Decided once per launch from the last version this
 * install opened and [installed]; the version is remembered as soon as it is read, so a crash
 * with the sheet open does not bring it back. [hold] keeps it waiting (behind the tour, or
 * while signed out).
 */
class WhatsNewState internal constructor() {
    var showing by mutableStateOf(false)
}

@Composable
fun rememberWhatsNew(settings: SettingsStore, installed: String, hold: Boolean = false): WhatsNewState {
    val state = remember { WhatsNewState() }
    var decided by remember { mutableStateOf(false) }
    LaunchedEffect(hold) {
        if (hold || decided) return@LaunchedEffect
        decided = true
        val launch = runCatching { WhatsNew.onLaunch(settings.lastSeenVersion.first(), installed) }.getOrNull()
            ?: return@LaunchedEffect
        launch.remember?.let { runCatching { settings.setLastSeenVersion(it) } }
        if (launch.show) state.showing = true
    }
    return state
}
