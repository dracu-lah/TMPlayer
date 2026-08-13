package com.tmplayer.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.R

/**
 * The app's own mark, the same drawing the launcher icon is made of.
 *
 * `ic_logo` is full bleed: it carries its own dark plate and rounded corners, so it needs no
 * background and no padding, and that is why this is an [Image] of a vector rather than a tinted
 * [androidx.compose.material3.Icon]. The corner clip keeps the plate's radius proportional when the
 * mark is drawn much smaller than the 192dp it was authored at.
 *
 * Deliberately kept out of ordinary screens: a logo on every pane is wallpaper.
 */
@Composable
fun AppMark(size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_logo),
        // Always accompanied by the app's name in text, so reading it twice would only be noise.
        contentDescription = null,
        modifier = modifier.size(size).clip(RoundedCornerShape(size * CORNER)),
    )
}

/** The source tile's corner radius as a fraction of its side, so it survives any scaling. */
private const val CORNER = 42f / 1024f

/** What the mark measures where it introduces the app, sized for the device holding it. */
object MarkSize {
    val Hero = 72.dp
    val HeroTouch = 56.dp
    val Inline = 40.dp
}
