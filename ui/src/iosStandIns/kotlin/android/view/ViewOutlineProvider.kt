package android.view

import android.graphics.Outline
import android.graphics.drawable.GradientDrawable

/** What a View's outline is, Android's: its background's shape by default. */
abstract class ViewOutlineProvider {
    abstract fun getOutline(view: View, outline: Outline)

    companion object {
        /** The background's shape: a GradientDrawable's rounded corners, any other its rectangle. */
        val BACKGROUND: ViewOutlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                when (val background = view.background) {
                    null -> outline.setEmpty()
                    is GradientDrawable -> outline.setRoundRect(0, 0, view.width, view.height, background.outlineRadius)
                    else -> outline.setRect(0, 0, view.width, view.height)
                }
            }
        }

        /** The View's own rectangle. */
        val BOUNDS: ViewOutlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRect(0, 0, view.width, view.height)
        }

        /** The View's rectangle less its padding. */
        val PADDED_BOUNDS: ViewOutlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRect(view.paddingLeft, view.paddingTop, view.width - view.paddingRight, view.height - view.paddingBottom)
        }
    }
}
