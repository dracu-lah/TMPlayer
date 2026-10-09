package com.tmplayer.ui.theme

import androidx.compose.runtime.Composable

/** Android ships Noto for every script the app speaks, so there is nothing to add here. */
@Composable
internal fun ProvideFallbackFonts(content: @Composable () -> Unit) = content()
