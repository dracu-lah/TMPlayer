package com.tmplayer.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.tmplayer.data.decodeImage
import com.tmplayer.ui.theme.LocalDarkTheme

/**
 * The screenshot for a page on the desktop: a WebP on the classpath, `onboarding/<page>-<light|dark>.webp`,
 * taken by the desktop promo fixture (`TMPLAYER_PROMO=<dir> ./gradlew :desktop:test --tests '*PromoShots*'`)
 * from the real screens over made-up chats, so the tour shows the window the viewer is about to use.
 */
@Composable
fun OnboardingImage(page: OnboardingPage, modifier: Modifier = Modifier) {
    val dark = LocalDarkTheme.current
    val image = remember(page, dark) { load(page, dark) } ?: return
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier.aspectRatio(image.width.toFloat() / image.height).onboardingFrame(),
    )
}

/** The name of a page's picture on the classpath, for the fixture that writes it and the tour that reads it. */
fun onboardingImageName(page: OnboardingPage, dark: Boolean): String =
    "${page.name.lowercase()}-${if (dark) "dark" else "light"}.webp"

private fun load(page: OnboardingPage, dark: Boolean): ImageBitmap? {
    if (!page.illustrated) return null
    val bytes = OnboardingPage::class.java.getResourceAsStream("/onboarding/${onboardingImageName(page, dark)}")
        ?.use { it.readBytes() } ?: return null
    return decodeImage(bytes)
}
