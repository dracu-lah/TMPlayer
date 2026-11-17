package android.util

import kotlinx.cinterop.useContents

/** Android's units for a dimension, and their size in pixels. */
object TypedValue {
    const val COMPLEX_UNIT_PX = 0
    const val COMPLEX_UNIT_DIP = 1
    const val COMPLEX_UNIT_SP = 2
    const val COMPLEX_UNIT_PT = 3
    const val COMPLEX_UNIT_IN = 4
    const val COMPLEX_UNIT_MM = 5

    fun applyDimension(unit: Int, value: Float, metrics: DisplayMetrics): Float = when (unit) {
        COMPLEX_UNIT_DIP -> value * metrics.density
        COMPLEX_UNIT_SP -> value * metrics.scaledDensity
        COMPLEX_UNIT_PT -> value * metrics.xdpi / 72f
        COMPLEX_UNIT_IN -> value * metrics.xdpi
        COMPLEX_UNIT_MM -> value * metrics.xdpi / 25.4f
        else -> value
    }
}

/** The screen's density and size in pixels, Android's; one dp is one iOS point. */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class DisplayMetrics {
    var density: Float = android.content.res.ResourceFiles.density
    var scaledDensity: Float = android.content.res.ResourceFiles.scaledDensity
    var densityDpi: Int = (160 * density).toInt()
    var xdpi: Float = 160f * density
    var ydpi: Float = 160f * density
    var widthPixels: Int = platform.UIKit.UIScreen.mainScreen.bounds.useContents { (size.width * density).toInt() }
    var heightPixels: Int = platform.UIKit.UIScreen.mainScreen.bounds.useContents { (size.height * density).toInt() }

    companion object {
        const val DENSITY_DEFAULT = 160
    }
}
