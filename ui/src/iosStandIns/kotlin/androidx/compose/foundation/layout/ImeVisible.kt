package androidx.compose.foundation.layout

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity

/** Whether the on-screen keyboard is up, as Android's WindowInsets.isImeVisible says. */
@ExperimentalLayoutApi
val WindowInsets.Companion.isImeVisible: Boolean
    @Composable get() = WindowInsets.ime.getBottom(LocalDensity.current) > 0
