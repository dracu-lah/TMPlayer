package androidx.media3.ui

import android.graphics.Color
import android.graphics.Typeface

/** A subtitle style, Media3's: text and box colours, the outline kind and the typeface. */
class CaptionStyleCompat(
    val foregroundColor: Int,
    val backgroundColor: Int,
    val windowColor: Int,
    val edgeType: Int,
    val edgeColor: Int,
    val typeface: Typeface?,
) {
    companion object {
        const val EDGE_TYPE_NONE = 0
        const val EDGE_TYPE_OUTLINE = 1
        const val EDGE_TYPE_DROP_SHADOW = 2
        const val EDGE_TYPE_RAISED = 3
        const val EDGE_TYPE_DEPRESSED = 4

        /** A colour that means "the track's own", Media3's. */
        const val USE_TRACK_COLOR_SETTINGS = 1

        /** Media3's default: white on black, no window, no edge. */
        val DEFAULT = CaptionStyleCompat(Color.WHITE, Color.BLACK, Color.TRANSPARENT, EDGE_TYPE_NONE, Color.WHITE, null)
    }
}
