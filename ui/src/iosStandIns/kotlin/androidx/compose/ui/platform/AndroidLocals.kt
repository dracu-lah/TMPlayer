package androidx.compose.ui.platform

// The Android-only CompositionLocals Compose offers there, for Android's screens on iOS.

import android.content.Context
import android.content.res.Configuration
import android.view.View
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import platform.UIKit.UIScreen
import kotlinx.cinterop.useContents

/** The iOS app's one Activity, the Context every screen sees unless the shell provides another. */
object IosAppContext : android.app.Activity()

val LocalContext = staticCompositionLocalOf<Context> { IosAppContext }

/** The screen's size in points, which are Compose's dp on iOS; the shell provides the window's. */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
val LocalConfiguration = compositionLocalOf {
    Configuration().apply {
        UIScreen.mainScreen.bounds.useContents {
            screenWidthDp = size.width.toInt()
            screenHeightDp = size.height.toInt()
        }
        smallestScreenWidthDp = minOf(screenWidthDp, screenHeightDp)
        orientation = if (screenWidthDp > screenHeightDp) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
    }
}

val LocalView = staticCompositionLocalOf { View(IosAppContext) }
