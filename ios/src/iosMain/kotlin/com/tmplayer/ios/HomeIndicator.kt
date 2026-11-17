package com.tmplayer.ios

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/**
 * The height of the home indicator's strip at the foot of the window (Android's navigation bar
 * inset; 34 points on an iPhone 13, 20 on an iPad). The lists that run to the foot of the window
 * on iOS keep this much more room after their last row, so it ends above the indicator, while
 * the rows before it scroll under it as iOS's own lists do (gradle/ios-apple-variant.gradle.kts).
 */
@Composable
fun homeIndicatorInset(): Dp = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }
