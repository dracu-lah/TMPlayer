package androidx.media3.ui

import android.content.Context
import android.content.res.ResourceFiles
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import com.tmplayer.ios.media3.EngineExoPlayer
import kotlin.math.abs

// Media3's player views on iOS, built as Media3 builds them: PlayerView holds a content frame with
// the video's shape (AspectRatioFrameLayout), a SurfaceView filling it and a subtitle view over it.
// The video engine's own view is put exactly over the SurfaceView by the host
// (com.tmplayer.ios.AndroidViewHost), so the shape Android's player chooses is the shape drawn.

/** Media3 UI's resource ids that apps look up in a PlayerView. */
object R {
    object id {
        val exo_content_frame: Int get() = ResourceFiles.idOf("exo_content_frame")
        val exo_shutter: Int get() = ResourceFiles.idOf("exo_shutter")
        val exo_subtitles: Int get() = ResourceFiles.idOf("exo_subtitles")
        val exo_overlay: Int get() = ResourceFiles.idOf("exo_overlay")
    }
}

/**
 * A frame held to a width-to-height ratio, Media3's AspectRatioFrameLayout: fit inside, fixed
 * width or height, fill or zoom, the same algorithm as Media3's, which tells its listener the
 * ratio it aimed at.
 */
open class AspectRatioFrameLayout(context: Context?, attrs: AttributeSet?) : FrameLayout(context, attrs, 0) {
    constructor(context: Context?) : this(context, null)

    /** Media3's listener, told the ratio aimed at, the view's own and whether they differ. */
    fun interface AspectRatioListener {
        fun onAspectRatioUpdated(targetAspectRatio: Float, naturalAspectRatio: Float, aspectRatioMismatch: Boolean)
    }

    private var videoAspectRatio = 0f
    private var listener: AspectRatioListener? = null

    var resizeMode: Int = RESIZE_MODE_FIT
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    fun setAspectRatio(widthHeightRatio: Float) {
        if (videoAspectRatio == widthHeightRatio) return
        videoAspectRatio = widthHeightRatio
        requestLayout()
    }

    fun setAspectRatioListener(listener: AspectRatioListener?) {
        this.listener = listener
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (videoAspectRatio <= 0f) return
        var width = measuredWidth
        var height = measuredHeight
        if (width <= 0 || height <= 0) return
        val viewAspectRatio = width.toFloat() / height
        val aspectDeformation = videoAspectRatio / viewAspectRatio - 1
        if (abs(aspectDeformation) <= MAX_ASPECT_RATIO_DEFORMATION_FRACTION) {
            listener?.onAspectRatioUpdated(videoAspectRatio, viewAspectRatio, false)
            return
        }
        when (resizeMode) {
            RESIZE_MODE_FIXED_WIDTH -> height = (width / videoAspectRatio).toInt()
            RESIZE_MODE_FIXED_HEIGHT -> width = (height * videoAspectRatio).toInt()
            RESIZE_MODE_ZOOM -> if (aspectDeformation > 0) width = (height * videoAspectRatio).toInt() else height = (width / videoAspectRatio).toInt()
            RESIZE_MODE_FIT -> if (aspectDeformation > 0) height = (width / videoAspectRatio).toInt() else width = (height * videoAspectRatio).toInt()
            RESIZE_MODE_FILL -> Unit
        }
        listener?.onAspectRatioUpdated(videoAspectRatio, viewAspectRatio, true)
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    companion object {
        const val RESIZE_MODE_FIT = 0
        const val RESIZE_MODE_FIXED_WIDTH = 1
        const val RESIZE_MODE_FIXED_HEIGHT = 2
        const val RESIZE_MODE_FILL = 3
        const val RESIZE_MODE_ZOOM = 4

        private const val MAX_ASPECT_RATIO_DEFORMATION_FRACTION = 0.01f
    }
}

/**
 * Cues drawn over the picture, Media3's SubtitleView with its canvas output: each text cue laid
 * out and drawn as Media3's SubtitlePainter does, in the style set (CaptionStyleCompat), at the
 * text size set, with the cue's own place, alignment, spans and size. Picture cues are the
 * engine's to draw on iOS (docs/IOS.md, layer 4 step 6), so none comes here.
 */
open class SubtitleView(context: Context?, attrs: AttributeSet?) : View(context, attrs, 0) {
    constructor(context: Context?) : this(context, null)

    private var cues: List<Cue> = emptyList()
    private var style: CaptionStyleCompat = CaptionStyleCompat.DEFAULT
    private var textSize = DEFAULT_TEXT_SIZE_FRACTION
    private var textSizeType = Cue.TEXT_SIZE_TYPE_FRACTIONAL
    private var bottomPaddingFraction = DEFAULT_BOTTOM_PADDING_FRACTION
    private var applyEmbeddedStyles = true
    private var applyEmbeddedFontSizes = true
    private val painter = SubtitlePainter()

    fun setCues(cues: List<Cue>?) {
        this.cues = cues.orEmpty()
        invalidate()
    }

    fun setStyle(style: CaptionStyleCompat) {
        this.style = style
        invalidate()
    }

    fun setFractionalTextSize(fractionOfHeight: Float) = setFractionalTextSize(fractionOfHeight, false)

    fun setFractionalTextSize(fractionOfHeight: Float, ignorePadding: Boolean) {
        textSize = fractionOfHeight
        textSizeType = if (ignorePadding) Cue.TEXT_SIZE_TYPE_FRACTIONAL_IGNORE_PADDING else Cue.TEXT_SIZE_TYPE_FRACTIONAL
        invalidate()
    }

    fun setFixedTextSize(unit: Int, size: Float) {
        textSize = android.util.TypedValue.applyDimension(unit, size, resources.displayMetrics)
        textSizeType = Cue.TEXT_SIZE_TYPE_ABSOLUTE
        invalidate()
    }

    fun setBottomPaddingFraction(bottomPaddingFraction: Float) {
        this.bottomPaddingFraction = bottomPaddingFraction
        invalidate()
    }

    fun setApplyEmbeddedStyles(applyEmbeddedStyles: Boolean) {
        this.applyEmbeddedStyles = applyEmbeddedStyles
        invalidate()
    }

    fun setApplyEmbeddedFontSizes(applyEmbeddedFontSizes: Boolean) {
        this.applyEmbeddedFontSizes = applyEmbeddedFontSizes
        invalidate()
    }

    fun setUserDefaultStyle() = setStyle(CaptionStyleCompat.DEFAULT)

    fun setUserDefaultTextSize() = setFractionalTextSize(DEFAULT_TEXT_SIZE_FRACTION)

    // Media3's CanvasSubtitleOutput.dispatchDraw: the cue box is the area inside the padding.
    override fun onDraw(canvas: Canvas) {
        if (cues.isEmpty()) return
        val left = paddingLeft
        val top = paddingTop
        val right = width - paddingRight
        val bottom = height - paddingBottom
        if (bottom <= top || right <= left) return
        val heightInside = bottom - top
        val defaultTextSizePx = resolveTextSize(textSizeType, textSize, height, heightInside)
        if (defaultTextSizePx <= 0f) return
        for (cue in cues) {
            val shown = withPreferences(cue)
            val cueTextSizePx = resolveTextSize(shown.textSizeType, shown.textSize, height, heightInside)
            painter.draw(shown, style, defaultTextSizePx, cueTextSizePx, bottomPaddingFraction, canvas, left, top, right, bottom)
        }
    }

    // Media3's getCuesWithStylingPreferencesApplied: the cue's own styles and sizes, unless turned
    // off; without its styles a cue is its plain text, without a window colour or size of its own.
    private fun withPreferences(cue: Cue): Cue {
        if (applyEmbeddedStyles && applyEmbeddedFontSizes) return cue
        if (!applyEmbeddedStyles) {
            return Cue.Builder().setText(cue.text?.toString()).setTextAlignment(cue.textAlignment)
                .setLine(cue.line, cue.lineType).setLineAnchor(cue.lineAnchor)
                .setPosition(cue.position).setPositionAnchor(cue.positionAnchor).setSize(cue.size).build()
        }
        val builder = cue.buildUpon().setTextSize(Cue.DIMEN_UNSET, Cue.TYPE_UNSET)
        val text = cue.text
        if (text is android.text.Spanned) {
            val sized = android.text.SpannableStringBuilder(text)
            for (span in sized.spanRanges) {
                if (span.what is android.text.style.AbsoluteSizeSpan || span.what is android.text.style.RelativeSizeSpan) sized.removeSpan(span.what)
            }
            builder.setText(sized)
        }
        return builder.build()
    }

    companion object {
        const val DEFAULT_TEXT_SIZE_FRACTION = 0.0533f
        const val DEFAULT_BOTTOM_PADDING_FRACTION = 0.08f
        const val VIEW_TYPE_CANVAS = 1
        const val VIEW_TYPE_WEB = 2

        // Media3's SubtitleViewUtils.resolveTextSize.
        internal fun resolveTextSize(type: Int, size: Float, rawViewHeight: Int, viewHeightMinusPadding: Int): Float {
            if (size == Cue.DIMEN_UNSET) return Cue.DIMEN_UNSET
            return when (type) {
                Cue.TEXT_SIZE_TYPE_ABSOLUTE -> size
                Cue.TEXT_SIZE_TYPE_FRACTIONAL -> size * viewHeightMinusPadding
                Cue.TEXT_SIZE_TYPE_FRACTIONAL_IGNORE_PADDING -> size * rawViewHeight
                else -> Cue.DIMEN_UNSET
            }
        }
    }
}

/**
 * Media3's SubtitlePainter for a text cue: the text wrapped to the cue box's width less an inner
 * padding of an eighth of the text size (times the cue's size when it has one), placed at the
 * cue's position and line with their anchors or else centred above the bottom padding, its window
 * and background drawn in the style's colours, its edge (outline, drop shadow, raised, depressed)
 * under it.
 */
internal class SubtitlePainter {
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val windowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val twoDp = kotlin.math.round(2f * ResourceFiles.density)

    // What the layout below was made from, as Media3 keeps it to lay out again only on a change.
    private var cueText: CharSequence? = null
    private var made: List<Any?>? = null
    private var textLayout: android.text.StaticLayout? = null
    private var edgeLayout: android.text.StaticLayout? = null
    private var textLeft = 0
    private var textTop = 0
    private var textPaddingX = 0

    fun draw(
        cue: Cue,
        style: CaptionStyleCompat,
        defaultTextSizePx: Float,
        cueTextSizePx: Float,
        bottomPaddingFraction: Float,
        canvas: Canvas,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        val text = cue.text
        if (cue.bitmap != null || text.isNullOrEmpty()) return
        val windowColor = if (cue.windowColorSet) cue.windowColor else style.windowColor
        val inputs = listOf(
            text.toString(), (text as? android.text.Spanned)?.spanRanges?.map { listOf(it.what, it.start, it.end) },
            cue.textAlignment, cue.line, cue.lineType, cue.lineAnchor, cue.position, cue.positionAnchor, cue.size,
            style, windowColor, defaultTextSizePx, cueTextSizePx, bottomPaddingFraction, left, top, right, bottom,
        )
        if (inputs != made) {
            made = inputs
            cueText = text
            textPaint.typeface = style.typeface
            setupTextLayout(cue, style, defaultTextSizePx, cueTextSizePx, bottomPaddingFraction, left, top, right, bottom)
        }
        drawTextLayout(canvas, style, windowColor)
    }

    private fun setupTextLayout(
        cue: Cue,
        style: CaptionStyleCompat,
        defaultTextSizePx: Float,
        cueTextSizePx: Float,
        bottomPaddingFraction: Float,
        parentLeft: Int,
        parentTop: Int,
        parentRight: Int,
        parentBottom: Int,
    ) {
        textLayout = null
        edgeLayout = null
        val source = cueText ?: return
        val cueText = android.text.SpannableStringBuilder(source)
        val parentWidth = parentRight - parentLeft
        val parentHeight = parentBottom - parentTop
        textPaint.textSize = defaultTextSizePx
        val paddingX = (defaultTextSizePx * INNER_PADDING_RATIO + 0.5f).toInt()
        var availableWidth = parentWidth - paddingX * 2
        if (cue.size != Cue.DIMEN_UNSET) availableWidth = (availableWidth * cue.size).toInt()
        if (availableWidth <= 0) return
        if (cueTextSizePx > 0) {
            cueText.setSpan(android.text.style.AbsoluteSizeSpan(cueTextSizePx.toInt()), 0, cueText.length, android.text.Spanned.SPAN_PRIORITY)
        }
        // The outline's copy has no colours of its own: they would cover the edge colour.
        val cueTextEdge = android.text.SpannableStringBuilder(cueText)
        if (style.edgeType == CaptionStyleCompat.EDGE_TYPE_OUTLINE) {
            for (span in cueTextEdge.spanRanges) if (span.what is android.text.style.ForegroundColorSpan) cueTextEdge.removeSpan(span.what)
        }
        // One pass for no edge and a drop shadow; otherwise the background goes in the edge's
        // pass only, or it would cover the edge.
        if (Color.alpha(style.backgroundColor) > 0) {
            val background = android.text.style.BackgroundColorSpan(style.backgroundColor)
            if (style.edgeType == CaptionStyleCompat.EDGE_TYPE_NONE || style.edgeType == CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW) {
                cueText.setSpan(background, 0, cueText.length, android.text.Spanned.SPAN_PRIORITY)
            } else {
                cueTextEdge.setSpan(background, 0, cueTextEdge.length, android.text.Spanned.SPAN_PRIORITY)
            }
        }
        val alignment = cue.textAlignment ?: android.text.Layout.Alignment.ALIGN_CENTER
        var layout = android.text.StaticLayout(cueText, textPaint, availableWidth, alignment, SPACING_MULT, SPACING_ADD, true)
        val textHeight = layout.height
        var textWidth = 0
        for (i in 0 until layout.lineCount) textWidth = maxOf(kotlin.math.ceil(layout.getLineWidth(i)).toInt(), textWidth)
        if (cue.size != Cue.DIMEN_UNSET && textWidth < availableWidth) textWidth = availableWidth
        textWidth += paddingX * 2
        var left: Int
        val right: Int
        if (cue.position != Cue.DIMEN_UNSET) {
            val anchor = kotlin.math.round(parentWidth * cue.position).toInt() + parentLeft
            left = when (cue.positionAnchor) {
                Cue.ANCHOR_TYPE_END -> anchor - textWidth
                Cue.ANCHOR_TYPE_MIDDLE -> (anchor * 2 - textWidth) / 2
                else -> anchor
            }
            left = maxOf(left, parentLeft)
            right = minOf(left + textWidth, parentRight)
        } else {
            left = (parentWidth - textWidth) / 2 + parentLeft
            right = left + textWidth
        }
        textWidth = right - left
        if (textWidth <= 0) return
        var top: Int
        if (cue.line != Cue.DIMEN_UNSET) {
            if (cue.lineType == Cue.LINE_TYPE_FRACTION) {
                val anchor = kotlin.math.round(parentHeight * cue.line).toInt() + parentTop
                top = when (cue.lineAnchor) {
                    Cue.ANCHOR_TYPE_END -> anchor - textHeight
                    Cue.ANCHOR_TYPE_MIDDLE -> (anchor * 2 - textHeight) / 2
                    else -> anchor
                }
            } else {
                val firstLineHeight = layout.getLineBottom(0) - layout.getLineTop(0)
                top = if (cue.line >= 0) {
                    kotlin.math.round(cue.line * firstLineHeight).toInt() + parentTop
                } else {
                    kotlin.math.round((cue.line + 1) * firstLineHeight).toInt() + parentBottom - textHeight
                }
            }
            if (top + textHeight > parentBottom) top = parentBottom - textHeight else if (top < parentTop) top = parentTop
        } else {
            top = parentBottom - textHeight - (parentHeight * bottomPaddingFraction).toInt()
        }
        layout = android.text.StaticLayout(cueText, textPaint, textWidth, alignment, SPACING_MULT, SPACING_ADD, true)
        textLayout = layout
        edgeLayout = android.text.StaticLayout(cueTextEdge, textPaint, textWidth, alignment, SPACING_MULT, SPACING_ADD, true)
        textLeft = left
        textTop = top
        textPaddingX = paddingX
    }

    private fun drawTextLayout(canvas: Canvas, style: CaptionStyleCompat, windowColor: Int) {
        val layout = textLayout ?: return
        val edge = edgeLayout ?: return
        val save = canvas.save()
        canvas.translate(textLeft.toFloat(), textTop.toFloat())
        if (Color.alpha(windowColor) > 0) {
            windowPaint.color = windowColor
            canvas.drawRect(-textPaddingX.toFloat(), 0f, layout.width + textPaddingX.toFloat(), layout.height.toFloat(), windowPaint)
        }
        when (style.edgeType) {
            CaptionStyleCompat.EDGE_TYPE_OUTLINE -> {
                textPaint.strokeJoin = Paint.Join.ROUND
                textPaint.strokeWidth = twoDp
                textPaint.color = style.edgeColor
                textPaint.style = Paint.Style.FILL_AND_STROKE
                edge.draw(canvas)
            }
            CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW -> textPaint.setShadowLayer(twoDp, twoDp, twoDp, style.edgeColor)
            CaptionStyleCompat.EDGE_TYPE_RAISED, CaptionStyleCompat.EDGE_TYPE_DEPRESSED -> {
                val raised = style.edgeType == CaptionStyleCompat.EDGE_TYPE_RAISED
                val up = if (raised) Color.WHITE else style.edgeColor
                val down = if (raised) style.edgeColor else Color.WHITE
                val offset = twoDp / 2f
                textPaint.color = style.foregroundColor
                textPaint.style = Paint.Style.FILL
                textPaint.setShadowLayer(twoDp, -offset, -offset, up)
                edge.draw(canvas)
                textPaint.setShadowLayer(twoDp, offset, offset, down)
            }
            else -> Unit
        }
        textPaint.color = style.foregroundColor
        textPaint.style = Paint.Style.FILL
        layout.draw(canvas)
        textPaint.setShadowLayer(0f, 0f, 0f, 0)
        canvas.restoreToCount(save)
    }

    private companion object {
        const val INNER_PADDING_RATIO = 0.125f
        const val SPACING_MULT = 1f
        const val SPACING_ADD = 0f
    }
}

/**
 * Media3's PlayerView: a content frame with the video's shape, a SurfaceView in it showing the
 * player's video (the engine's view on iOS) and Media3's subtitle view. The frame takes the shape
 * of each video size the player reports, as Media3's does; Android's player turns the rest off.
 */
open class PlayerView(context: Context?, attrs: AttributeSet?) : FrameLayout(context, attrs, 0) {
    constructor(context: Context?) : this(context, null)

    private val contentFrame = AspectRatioFrameLayout(context).also { it.id = R.id.exo_content_frame }
    private val surface = SurfaceView(context)
    private val subtitles = SubtitleView(context).also { it.id = R.id.exo_subtitles }

    init {
        contentFrame.addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(contentFrame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        addView(subtitles, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    var useController: Boolean = true

    val videoSurfaceView: View? get() = surface

    val subtitleView: SubtitleView? get() = subtitles

    var resizeMode: Int
        get() = contentFrame.resizeMode
        set(value) {
            contentFrame.resizeMode = value
        }

    fun setShowBuffering(showBuffering: Int) = Unit

    fun setKeepContentOnPlayerReset(keep: Boolean) = Unit

    fun setShutterBackgroundColor(color: Int) = Unit

    private val listener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) = followShape(videoSize)

        override fun onCues(cueGroup: CueGroup) = subtitles.setCues(cueGroup.cues)
    }

    var player: Player? = null
        set(value) {
            if (field === value) return
            field?.removeListener(listener)
            field = value
            value?.addListener(listener)
            val engine = value as? EngineExoPlayer
            engine?.fillView()
            surface.nativeView = engine?.engineSurface()
            // The frame on screen, for PixelCopy (saving a frame): the engine's own capture.
            surface.readFrame = engine?.let { exo -> { done -> exo.captureFrame(done) } }
            value?.getVideoSize()?.let(::followShape)
        }

    private fun followShape(size: VideoSize) {
        if (size.width <= 0 || size.height <= 0) return
        contentFrame.setAspectRatio(size.width * size.pixelWidthHeightRatio / size.height)
    }

    companion object {
        const val SHOW_BUFFERING_NEVER = 0
        const val SHOW_BUFFERING_WHEN_PLAYING = 1
        const val SHOW_BUFFERING_ALWAYS = 2
    }
}
