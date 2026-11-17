package android.view

/**
 * The screen, Android's Display: what HDR it shows, from iOS. A screen with extended dynamic range
 * (UIScreen.potentialEDRHeadroom above 1) shows HDR10, HLG and Dolby Vision, as Apple's do.
 */
class Display internal constructor() {
    class HdrCapabilities internal constructor(val supportedHdrTypes: IntArray) {
        companion object {
            const val HDR_TYPE_DOLBY_VISION = 1
            const val HDR_TYPE_HDR10 = 2
            const val HDR_TYPE_HLG = 3
            const val HDR_TYPE_HDR10_PLUS = 4
        }
    }

    val displayId: Int get() = DEFAULT_DISPLAY

    val hdrCapabilities: HdrCapabilities?
        get() {
            val headroom = platform.UIKit.UIScreen.mainScreen.potentialEDRHeadroom
            val types = if (headroom > 1.0) {
                intArrayOf(HdrCapabilities.HDR_TYPE_DOLBY_VISION, HdrCapabilities.HDR_TYPE_HDR10, HdrCapabilities.HDR_TYPE_HLG)
            } else {
                IntArray(0)
            }
            return HdrCapabilities(types)
        }

    val isHdr: Boolean get() = hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true

    val refreshRate: Float get() = platform.UIKit.UIScreen.mainScreen.maximumFramesPerSecond.toFloat()

    companion object {
        const val DEFAULT_DISPLAY = 0

        internal val Main = Display()
    }
}
