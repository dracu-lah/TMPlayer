package androidx.media3.effect

import android.content.Context
import androidx.media3.common.Effect
import androidx.media3.common.util.Size

// Media3's video effects, run on Android in its own OpenGL pipeline. iOS has none: what the player
// asks of them (3D, a shape) is the engine's to draw, so these are types for the player's code as
// upstream wrote it, never run (docs/IOS.md, layer 4).

/** A video effect drawn with OpenGL, Media3's. */
interface GlEffect : Effect {
    fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram

    fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false
}

/** A step of Media3's OpenGL pipeline. */
interface GlShaderProgram {
    fun release()
}

/** Media3's base for an OpenGL step: the output size, then each frame drawn. */
abstract class BaseGlShaderProgram(
    @Suppress("UNUSED_PARAMETER") useHighPrecisionColorComponents: Boolean,
    @Suppress("UNUSED_PARAMETER") texturePoolCapacity: Int,
) : GlShaderProgram {
    abstract fun configure(inputWidth: Int, inputHeight: Int): Size

    abstract fun drawFrame(inputTexId: Int, presentationTimeUs: Long)

    override fun release() = Unit
}

/** Media3's turn of the picture, which the engine draws on iOS: only the angle is kept. */
class ScaleAndRotateTransformation private constructor(val rotationDegrees: Float) : Effect {
    class Builder {
        private var degrees = 0f

        fun setRotationDegrees(rotationDegrees: Float): Builder = apply { degrees = rotationDegrees }

        fun build(): ScaleAndRotateTransformation = ScaleAndRotateTransformation(degrees)
    }
}

/** Media3's shape for the frames: a size and how the picture fits it. */
class Presentation private constructor(val width: Int, val height: Int, val aspectRatio: Float, val layout: Int) : Effect {
    companion object {
        const val LAYOUT_SCALE_TO_FIT = 0
        const val LAYOUT_SCALE_TO_FIT_WITH_CROP = 1
        const val LAYOUT_STRETCH_TO_FIT = 2

        fun createForWidthAndHeight(width: Int, height: Int, layout: Int): Presentation = Presentation(width, height, -1f, layout)

        fun createForAspectRatio(aspectRatio: Float, layout: Int): Presentation = Presentation(-1, -1, aspectRatio, layout)

        fun createForHeight(height: Int): Presentation = Presentation(-1, height, -1f, LAYOUT_SCALE_TO_FIT)
    }
}
