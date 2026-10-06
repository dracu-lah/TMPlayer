package com.tmplayer.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.tmplayer.data.DeviceForm
import com.tmplayer.ui.components.deviceForm
import com.tmplayer.ui.theme.LocalDarkTheme

/**
 * Where the Android pictures come from. They are the app's own drawables, which this library cannot
 * name, so the app hands over the lookup when it starts (App.kt).
 */
object OnboardingImages {
    /** The drawable for [page] on a television or a phone, light or dark; 0 when the page has none. */
    @Volatile
    var drawable: (page: OnboardingPage, tv: Boolean, dark: Boolean) -> Int = { _, _, _ -> 0 }
}

/**
 * The screenshot for a page, in the shape of the device reading it: the landscape shot of a
 * television, or the top of a phone screen, light or dark to match what the reader is looking at.
 */
@Composable
fun OnboardingImage(page: OnboardingPage, modifier: Modifier = Modifier) {
    val tv = deviceForm() == DeviceForm.Tv
    val id = OnboardingImages.drawable(page, tv, LocalDarkTheme.current)
    if (id == 0) return
    Image(
        painter = painterResource(id),
        // The title beside it is the description; a second reading of the same thing is noise.
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier.aspectRatio(if (tv) 16f / 9f else 108f / 140f).onboardingFrame(),
    )
}
