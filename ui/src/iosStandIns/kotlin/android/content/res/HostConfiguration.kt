package android.content.res

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.UIKit.UIApplication
import platform.UIKit.UIFontMetrics
import platform.UIKit.UIScreen
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIWindow

/**
 * What Android's `Configuration` says, read from the iOS host instead of the fixed answers it gave
 * until WP-64 (font scale 1, width 0, always portrait). One dp is one iOS point, as Compose draws.
 *
 * The three small functions are pure (the text scale from what Dynamic Type makes of a size, the
 * orientation and the smallest width from a size), so a test checks them without a screen.
 */
internal object HostConfiguration {
    /** The size Dynamic Type's body style is measured against: 100 points scaled by the user's text size. */
    private const val PROBE = 100.0

    /** Android's `fontScale`: 1 at the default text size, 1.12 at Large Larger Text steps, and so on. */
    fun fontScaleFor(scaledProbe: Double): Float =
        if (scaledProbe.isNaN() || scaledProbe <= 0.0) 1f else (scaledProbe / PROBE).toFloat().coerceIn(0.5f, 4f)

    /** Android's `ORIENTATION_LANDSCAPE` when the window is wider than tall, `ORIENTATION_PORTRAIT` otherwise. */
    fun orientationFor(widthDp: Int, heightDp: Int): Int =
        if (widthDp > heightDp) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT

    /** The shorter side, which is what Android's tablet test (600 dp) reads. */
    fun smallestWidthFor(widthDp: Int, heightDp: Int): Int = minOf(widthDp, heightDp)

    /** The text size the user chose in Settings, as a multiplier (Dynamic Type, Larger Text included). */
    val fontScale: Float
        get() = fontScaleFor(UIFontMetrics.defaultMetrics.scaledValueForValue(PROBE))

    /** The app's window (not the screen: Split View and Slide Over give an iPad app less), in points. */
    @OptIn(ExperimentalForeignApi::class)
    private fun windowSize(): Pair<Int, Int> {
        val window = UIApplication.sharedApplication.windows.firstOrNull { (it as? UIWindow)?.isKeyWindow() == true } as? UIWindow
        val size = if (window != null) {
            window.bounds.useContents { size.width to size.height }
        } else {
            UIScreen.mainScreen.bounds.useContents { size.width to size.height }
        }
        return size.first.toInt() to size.second.toInt()
    }

    /** A fresh `Configuration` filled from the host; asked again each time, as the window can turn or be resized. */
    fun read(): Configuration {
        val config = Configuration()
        val (width, height) = windowSize()
        config.screenWidthDp = width
        config.screenHeightDp = height
        config.smallestScreenWidthDp = smallestWidthFor(width, height)
        config.orientation = orientationFor(width, height)
        config.fontScale = fontScale
        config.densityDpi = (160 * ResourceFiles.density).toInt()
        config.uiMode = Configuration.UI_MODE_TYPE_NORMAL or
            if (UIScreen.mainScreen.traitCollection.userInterfaceStyle == UIUserInterfaceStyle.UIUserInterfaceStyleDark) {
                Configuration.UI_MODE_NIGHT_YES
            } else {
                Configuration.UI_MODE_NIGHT_NO
            }
        return config
    }
}
