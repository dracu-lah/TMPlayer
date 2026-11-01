package com.tmplayer.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.tmplayer.data.decodeImage
import com.tmplayer.ui.theme.LocalDarkTheme

/**
 * The picture of an answer on the desktop: a WebP on the classpath, `onboarding/<name>`, cropped
 * and marked by `scripts/annotate-shot.py` from the desktop promo fixture's shots
 * (`TMPLAYER_PROMO=<dir> ./gradlew :desktop:test --tests '*PromoShots*'`), so the setup shows the
 * window the viewer is about to use.
 */
@Composable
internal fun SetupShot(choice: SetupChoice, modifier: Modifier = Modifier) {
    val dark = LocalDarkTheme.current
    val image = remember(choice, dark) { load(choice, dark) } ?: return
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

/** The file name of an answer's picture, for the fixture that writes it and the setup that reads it. */
fun setupImageName(choice: SetupChoice, dark: Boolean): String? {
    val shot = when (choice) {
        SetupChoice.PostersOn -> "posters-on"
        SetupChoice.PostersOff -> "posters-off"
        SetupChoice.FullQuality -> "performance-full"
        SetupChoice.Lighter -> "performance-lighter"
        SetupChoice.KeepWhilePlaying, SetupChoice.LowStorage, SetupChoice.SaveData, SetupChoice.NoLimits,
        SetupChoice.ApplyRecommended, SetupChoice.NotNow, SetupChoice.CrashReportsOff, SetupChoice.CrashReportsOn -> return null
    }
    return "$shot-${if (dark) "dark" else "light"}.webp"
}

private fun load(choice: SetupChoice, dark: Boolean): ImageBitmap? {
    val name = setupImageName(choice, dark) ?: return null
    val bytes = SetupChoice::class.java.getResourceAsStream("/onboarding/$name")?.use { it.readBytes() } ?: return null
    return decodeImage(bytes)
}

/**
 * A tour page's picture on the desktop, `onboarding/tour-<page>-light|dark.webp`, made the same way
 * as the answers' pictures. Shown whole, since the badges over it are placed by fractions of it.
 */
@Composable
internal fun TourShot(page: TourPage, modifier: Modifier = Modifier) {
    val image = tourImage(page) ?: return
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}

/** The size of a tour page's picture, for laying out the badges over it; null when it has none. */
@Composable
internal fun tourShotSize(page: TourPage): androidx.compose.ui.geometry.Size? =
    tourImage(page)?.let { androidx.compose.ui.geometry.Size(it.width.toFloat(), it.height.toFloat()) }

/** The file name of a tour page's picture on the desktop; null for a page the desktop does not get. */
fun tourImageName(page: TourPage, dark: Boolean): String? =
    if (page == TourPage.TvHome) null else "tour-${page.imageName}-${if (dark) "dark" else "light"}.webp"

@Composable
private fun tourImage(page: TourPage): ImageBitmap? {
    val dark = LocalDarkTheme.current
    return remember(page, dark) {
        val name = tourImageName(page, dark) ?: return@remember null
        val bytes = TourPage::class.java.getResourceAsStream("/onboarding/$name")?.use { it.readBytes() }
        bytes?.let(::decodeImage)
    }
}
