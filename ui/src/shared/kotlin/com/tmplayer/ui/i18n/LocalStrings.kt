package com.tmplayer.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.tmplayer.i18n.Messages
import com.tmplayer.i18n.Translator

/**
 * The UI language's strings for a composable: `val s = LocalStrings.current`, then
 * `Text(s.commonRetry)` or `Text(s.browseVideosCount(n))`. Fed by [Translator.active] through
 * [ProvideStrings], which both themes wrap every tree in, so a language change recomposes in
 * place with no restart. Outside Compose, read `L` instead.
 */
val LocalStrings = staticCompositionLocalOf<Messages> { Translator.messages }

/**
 * Provides [LocalStrings] from [Translator.active] to [content], and the layout direction that
 * goes with the language: right to left for Arabic, left to right for the rest. The direction
 * follows the app's language rather than the device's, so an English app on an Arabic phone reads
 * left to right and an Arabic app on an English computer reads right to left.
 */
@Composable
fun ProvideStrings(content: @Composable () -> Unit) {
    val messages by Translator.active.collectAsState()
    CompositionLocalProvider(
        LocalStrings provides messages,
        LocalLayoutDirection provides layoutDirection(messages.rtl),
        content = content,
    )
}

/** The layout direction for a language that is, or is not, written right to left. */
fun layoutDirection(rtl: Boolean): LayoutDirection = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr

