package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.use
import com.tmplayer.ui.components.BrandLayout
import com.tmplayer.ui.components.BrandLockup
import com.tmplayer.ui.components.BrandSize
import com.tmplayer.ui.components.LocalBrandBadge
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The desktop's Home corner lockup (HX60: it was too big). Its size is the shared
 * [BrandSize.Desktop] token, and at 100%, 150% and 200% scale it takes the same dp, so the
 * pixels grow with the scale and never more than a small share of the smallest window.
 */
class HomeBrandScaleTest {

    @Test
    fun `home corner uses the shared desktop token`() {
        assertSame(BrandSize.Desktop, HOME_BRAND)
        assertTrue(BrandSize.Desktop.mark.value > BrandSize.Small.mark.value)
        assertTrue(BrandSize.Desktop.mark.value < 40f)
    }

    @Test
    fun `lockup keeps its dp size at 100, 150 and 200 percent and stays small in the smallest window`() {
        val base = measure(1f)
        for (scale in listOf(1.5f, 2f)) {
            val size = measure(scale)
            assertEquals("width at $scale", base.width * scale, size.width.toFloat(), 2f * scale)
            assertEquals("height at $scale", base.height * scale, size.height.toFloat(), 2f * scale)
        }
        // The smallest window the shell allows is about 640 x 400 dp at 200%: the corner lockup
        // is at most a tenth of its height and a third of its width.
        assertTrue("height ${base.height} dp", base.height <= 40)
        assertTrue("width ${base.width} dp", base.width <= 640 / 3)
    }

    private fun measure(scale: Float): IntSize {
        var size = IntSize.Zero
        ImageComposeScene((900 * scale).toInt(), (500 * scale).toInt(), Density(scale)) {
            TmMaterialTheme(dark = true) {
                CompositionLocalProvider(LocalBrandBadge provides null) {
                    Box {
                        BrandLockup(
                            size = HOME_BRAND,
                            layout = BrandLayout.Row,
                            badge = false,
                            modifier = Modifier.onSizeChanged { size = it },
                        )
                    }
                }
            }
        }.use { it.render(0) }
        // Back to dp-comparable pixels at scale 1 is done by the caller, which scales the base.
        return size
    }
}
