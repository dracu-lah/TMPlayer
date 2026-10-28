package com.tmplayer.player

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * 3D video on the phone and the television: one Media3 shader pass that draws a 3D file's two
 * eyes as one anaglyph, as one eye alone, or side by side for a VR headset ([Stereo3d]).
 *
 * The decoder stays the device's own and the frames stay on the graphics chip, so a 1 GB stick
 * pays one texture pass a frame, and only while 3D is on: the player builds the effects pipeline
 * only for a video that asks for it ([PlayerActivity]). The subtitles are drawn by their own view
 * over the picture, so they stay sharp and readable through the glasses.
 *
 * For a headset the pair is drawn at the screen's own size, [boxWidth] by [boxHeight], with
 * each eye given the picture shape ([shape]) inside its half: Media3's last stage fits whatever
 * it is handed into the surface with its aspect kept, so a shape chosen on the view alone came
 * back letterboxed (HX16). Zero for either keeps the pair at its eyes' own shape.
 */
@UnstableApi
class Stereo3dEffect(
    private val stereo: Stereo3d,
    private val shape: VideoScale = VideoScale.Fit,
    private val boxWidth: Int = 0,
    private val boxHeight: Int = 0,
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        Program(stereo, shape, boxWidth, boxHeight, useHdr)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = !stereo.on

    private class Program(
        private val stereo: Stereo3d,
        private val shape: VideoScale,
        private val boxWidth: Int,
        private val boxHeight: Int,
        useHdr: Boolean,
    ) :
        BaseGlShaderProgram(/* useHighPrecisionColorComponents = */ useHdr, /* texturePoolCapacity = */ 1) {

        private var program: GlProgram? = null

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            // The shader is written for this frame size: the seam guard is one source pixel.
            program?.delete()
            // A headset's pair shaped for the screen: each eye placed in its own half.
            val boxed = stereo.view == Stereo3d.View.Vr && boxWidth > 0 && boxHeight > 0
            val (outWidth, outHeight) = if (boxed) {
                Stereo3d.boxedSize(boxWidth, boxHeight)
            } else {
                Stereo3d.outputSize(inputWidth, inputHeight, stereo)
            }
            val eyeShare = if (boxed) {
                val (eyeWidth, eyeHeight) = Stereo3d.outputSize(inputWidth, inputHeight, stereo.layout)
                val eye = if (eyeHeight > 0) eyeWidth.toDouble() / eyeHeight else 0.0
                shape.boxShare(outWidth / 2, outHeight, eye).let { (x, y) -> x.toFloat() to y.toFloat() }
            } else {
                null
            }
            val fragment = buildString {
                append("precision mediump float;\n")
                append("uniform sampler2D uTexSampler;\n")
                append("varying vec2 vTexSamplingCoord;\n")
                append("vec4 eyes(vec2 p) {\n")
                append(
                    Stereo3d.glslBody(
                        stereo,
                        sample = "texture2D",
                        pos = "p",
                        yDown = false,
                        texelX = 1f / inputWidth.coerceAtLeast(1),
                        texelY = 1f / inputHeight.coerceAtLeast(1),
                        eyeShare = eyeShare,
                    ).replace("texture2D(", "texture2D(uTexSampler, ").prependIndent("    "),
                )
                append("\n}\n")
                append("void main() { gl_FragColor = eyes(vTexSamplingCoord); }\n")
            }
            program = try {
                GlProgram(VERTEX, fragment).apply {
                    setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
                }
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
            return Size(outWidth.coerceAtLeast(1), outHeight.coerceAtLeast(1))
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            val glProgram = program ?: return
            try {
                glProgram.use()
                glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex = */ 0)
                glProgram.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        override fun release() {
            super.release()
            try {
                program?.delete()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
            program = null
        }
    }

    private companion object {
        const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
    gl_Position = aFramePosition;
    vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
}
"""
    }
}

/** "Side by side, Red and cyan": the menu line's detail while 3D is on, or null while it is off. */
fun Stereo3d.detailText(): String? = if (!on) null else com.tmplayer.i18n.L.playerStereoDetail(
    layout = when (layout) {
        Stereo3d.Layout.SideBySide -> com.tmplayer.i18n.L.playerStereoSideBySide
        Stereo3d.Layout.RowInterleaved -> com.tmplayer.i18n.L.playerStereoRows
        Stereo3d.Layout.ColumnInterleaved -> com.tmplayer.i18n.L.playerStereoColumns
        else -> com.tmplayer.i18n.L.playerStereoTopBottom
    },
    glasses = view.label(),
)

/** The glasses' name, as the page lists them. */
fun Stereo3d.View.label(): String = when (this) {
    Stereo3d.View.RedCyan -> com.tmplayer.i18n.L.playerStereoRedCyan
    Stereo3d.View.GreenMagenta -> com.tmplayer.i18n.L.playerStereoGreenMagenta
    Stereo3d.View.YellowBlue -> com.tmplayer.i18n.L.playerStereoYellowBlue
    Stereo3d.View.OneEye -> com.tmplayer.i18n.L.playerStereoOneEye
    Stereo3d.View.Vr -> com.tmplayer.i18n.L.playerStereoVr
}
