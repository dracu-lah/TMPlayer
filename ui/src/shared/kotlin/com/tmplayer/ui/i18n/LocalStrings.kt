package com.tmplayer.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.tmplayer.i18n.Messages
import com.tmplayer.i18n.Translator

/**
 * The UI language's strings for a composable: `val s = LocalStrings.current`, then
 * `Text(s.commonRetry)` or `Text(s.browseVideosCount(n))`. Fed by [Translator.active] through
 * [ProvideStrings], which both themes wrap every tree in, so a language change recomposes in
 * place with no restart. Outside Compose, read `L` instead.
 */
val LocalStrings = staticCompositionLocalOf<Messages> { Translator.messages }

/** Provides [LocalStrings] from [Translator.active] to [content]. */
@Composable
fun ProvideStrings(content: @Composable () -> Unit) {
    val messages by Translator.active.collectAsState()
    CompositionLocalProvider(LocalStrings provides messages, content = content)
}

