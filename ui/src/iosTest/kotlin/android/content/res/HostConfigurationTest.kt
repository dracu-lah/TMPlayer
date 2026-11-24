package android.content.res

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The stand-in `Configuration` follows the host (WP-64): the font scale from what Dynamic Type makes
 * of a size, the orientation and the smallest width from the window, and `scaledDensity` (what an sp
 * is worth in pixels) from the density times that scale, so Larger Text reaches the player's Views.
 */
class HostConfigurationTest {
    @Test
    fun fontScaleIsTheScaledProbeOverTheProbe() {
        assertEquals(1f, HostConfiguration.fontScaleFor(100.0))
        assertEquals(1.35f, HostConfiguration.fontScaleFor(135.0))
        assertEquals(3.12f, HostConfiguration.fontScaleFor(312.0))
    }

    @Test
    fun anAnswerThatIsNotUsableIsTheDefaultSize() {
        assertEquals(1f, HostConfiguration.fontScaleFor(Double.NaN))
        assertEquals(1f, HostConfiguration.fontScaleFor(0.0))
        assertEquals(1f, HostConfiguration.fontScaleFor(-5.0))
        assertEquals(4f, HostConfiguration.fontScaleFor(1_000_000.0))
    }

    @Test
    fun theWindowsShapeGivesTheOrientationAndTheSmallestWidth() {
        assertEquals(Configuration.ORIENTATION_PORTRAIT, HostConfiguration.orientationFor(393, 852))
        assertEquals(Configuration.ORIENTATION_LANDSCAPE, HostConfiguration.orientationFor(852, 393))
        assertEquals(Configuration.ORIENTATION_PORTRAIT, HostConfiguration.orientationFor(600, 600))
        assertEquals(393, HostConfiguration.smallestWidthFor(852, 393))
        assertEquals(393, HostConfiguration.smallestWidthFor(393, 852))
    }

    @Test
    fun scaledDensityFollowsTheTextSizeAndDensity() {
        val config = Resources.Shared.configuration
        assertTrue(config.fontScale > 0f)
        assertEquals(ResourceFiles.density * HostConfiguration.fontScale, ResourceFiles.scaledDensity)
    }
}
