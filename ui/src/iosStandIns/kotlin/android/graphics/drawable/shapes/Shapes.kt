package android.graphics.drawable.shapes

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/** An outline a ShapeDrawable fills, sized to it, Android's. */
abstract class Shape {
    var width: Float = 0f
        private set
    var height: Float = 0f
        private set

    fun resize(width: Float, height: Float) {
        this.width = width
        this.height = height
        onResize(width, height)
    }

    protected open fun onResize(width: Float, height: Float) {}

    abstract fun draw(canvas: Canvas, paint: Paint)
}

/** The whole rectangle. */
open class RectShape : Shape() {
    override fun draw(canvas: Canvas, paint: Paint) {
        canvas.drawRect(RectF(0f, 0f, width, height), paint)
    }
}

/** A rectangle with rounded corners: eight radii, x and y for each corner from the top left. */
open class RoundRectShape(private val outerRadii: FloatArray?, inset: RectF?, innerRadii: FloatArray?) : RectShape() {
    override fun draw(canvas: Canvas, paint: Paint) {
        val r = outerRadii?.firstOrNull() ?: 0f
        canvas.drawRoundRect(RectF(0f, 0f, width, height), r, r, paint)
    }
}
