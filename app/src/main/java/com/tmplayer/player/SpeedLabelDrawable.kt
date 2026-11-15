package com.tmplayer.player

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable

/**
 * The speed button's face while the speed is not 1x: the figure itself ("1.25x") where the icon
 * was. It takes the button's tint like the icon does, including the dark-on-white of a focused
 * button, since the ImageView hands its tint list to whatever drawable it shows.
 */
class SpeedLabelDrawable(private val text: String) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        color = 0xFFFFFFFF.toInt()
    }
    private var tint: ColorStateList? = null

    override fun draw(canvas: Canvas) {
        val box = bounds
        if (box.isEmpty) return
        // As large as the box allows: the height's share first, then squeezed to the width.
        paint.textSize = box.height() * 0.62f
        val wide = paint.measureText(text)
        if (wide > box.width()) paint.textSize *= box.width() / wide
        val baseline = box.exactCenterY() - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, box.exactCenterX(), baseline, paint)
    }

    override fun isStateful(): Boolean = tint?.isStateful == true

    override fun setTintList(tint: ColorStateList?) {
        this.tint = tint
        applyTint(state)
        invalidateSelf()
    }

    override fun onStateChange(state: IntArray): Boolean {
        val changed = applyTint(state)
        if (changed) invalidateSelf()
        return changed
    }

    private fun applyTint(state: IntArray): Boolean {
        val list = tint ?: return false
        val colour = list.getColorForState(state, list.defaultColor)
        if (colour == paint.color) return false
        paint.color = colour
        return true
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
