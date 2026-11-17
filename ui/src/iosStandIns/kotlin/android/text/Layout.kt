package android.text

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import kotlin.math.ceil
import kotlin.math.max

/** Android's laid out text: lines, their measures, drawing. */
abstract class Layout internal constructor(val text: CharSequence, val width: Int, val alignment: Alignment) {
    enum class Alignment { ALIGN_NORMAL, ALIGN_OPPOSITE, ALIGN_CENTER }

    abstract val lineCount: Int

    abstract val height: Int

    abstract fun getLineTop(line: Int): Int

    abstract fun getLineBottom(line: Int): Int

    abstract fun getLineWidth(line: Int): Float

    abstract fun getLineLeft(line: Int): Float

    fun getLineRight(line: Int): Float = getLineLeft(line) + getLineWidth(line)

    abstract fun draw(canvas: Canvas)
}

/**
 * Android's StaticLayout, for what draws spanned text on iOS (Media3's SubtitleView): the text
 * broken into lines no wider than [width] at spaces (a word longer than a line at its characters),
 * a new line at each '\n', each line placed by [align]; [StyleSpan], [AbsoluteSizeSpan] and
 * [RelativeSizeSpan] measured, colours, underlines and strike-throughs drawn. Lines stand
 * ascent to descent apart, the first from the font's top and the last to its bottom when
 * [includePad], times [spacingMult] plus [spacingAdd], as Android spaces them.
 */
class StaticLayout(
    source: CharSequence,
    private val paint: Paint,
    width: Int,
    align: Alignment,
    private val spacingMult: Float,
    private val spacingAdd: Float,
    private val includePad: Boolean,
) : Layout(source, width, align) {
    /** A stretch of the text drawn one way. */
    private class Run(val start: Int, val end: Int, val paint: Paint, val foreground: Int?, val background: Int?, val underline: Boolean, val strike: Boolean)

    private class Line(val start: Int, val end: Int, val width: Float, val top: Int, val bottom: Int, val baseline: Float)

    private val runs: List<Run> = runsOf(source)
    private val lines: List<Line> = breakLines()

    override val lineCount: Int get() = lines.size

    override val height: Int get() = lines.lastOrNull()?.bottom ?: 0

    override fun getLineTop(line: Int): Int = lines[line].top

    override fun getLineBottom(line: Int): Int = lines[line].bottom

    override fun getLineWidth(line: Int): Float = lines[line].width

    override fun getLineLeft(line: Int): Float = when (alignment) {
        Alignment.ALIGN_NORMAL -> 0f
        Alignment.ALIGN_CENTER -> (width - lines[line].width) / 2f
        Alignment.ALIGN_OPPOSITE -> width - lines[line].width
    }

    override fun draw(canvas: Canvas) {
        val shape = Paint()
        lines.forEachIndexed { index, line ->
            var x = getLineLeft(index)
            for (run in runs) {
                val start = max(run.start, line.start)
                val end = minOf(run.end, visibleEnd(line))
                if (start >= end) continue
                val piece = text.subSequence(start, end).toString()
                val w = run.paint.measureText(piece)
                run.background?.let {
                    shape.color = it
                    canvas.drawRect(x, line.top.toFloat(), x + w, line.bottom.toFloat(), shape)
                }
                // The paint's look as it is now (colour, fill or stroke, shadow), a span's colour over it.
                run.paint.takeLook(paint)
                run.foreground?.let { run.paint.color = it }
                canvas.drawText(piece, x, line.baseline, run.paint)
                if (run.underline || run.strike) {
                    shape.color = run.paint.color
                    val thick = max(1f, run.paint.textSize / 18f)
                    if (run.underline) canvas.drawRect(x, line.baseline + thick, x + w, line.baseline + 2 * thick, shape)
                    if (run.strike) {
                        val middle = line.baseline + (run.paint.ascent() + run.paint.descent()) / 2f
                        canvas.drawRect(x, middle - thick / 2, x + w, middle + thick / 2, shape)
                    }
                }
                x += w
            }
        }
    }

    // A line's text without the spaces it ends with, which take no room at its end.
    private fun visibleEnd(line: Line): Int {
        var end = line.end
        while (end > line.start && (text[end - 1] == ' ' || text[end - 1] == '\n')) end--
        return end
    }

    private fun measure(start: Int, end: Int): Float {
        var width = 0f
        for (run in runs) {
            val a = max(run.start, start)
            val b = minOf(run.end, end)
            if (a < b) width += run.paint.measureText(text.subSequence(a, b).toString())
        }
        return width
    }

    private fun breakLines(): List<Line> {
        val out = ArrayList<IntArray>()
        var paragraph = 0
        while (paragraph <= text.length) {
            val newline = text.indexOf('\n', paragraph).let { if (it < 0) text.length else it }
            breakParagraph(paragraph, newline, out)
            // The '\n' belongs to the line it ends.
            if (newline < text.length && out.isNotEmpty()) out[out.size - 1][1] = newline + 1
            paragraph = newline + 1
            if (newline >= text.length) break
        }
        var top = 0f
        return out.mapIndexed { index, (start, end) ->
            val metrics = metricsOf(start, end)
            val ascent = if (includePad && index == 0) metrics.top else metrics.ascent
            val descent = if (includePad && index == out.size - 1) metrics.bottom else metrics.descent
            val height = (descent - ascent) * spacingMult + spacingAdd
            val lineTop = top
            top += height
            Line(start, end, measure(start, visibleEndOf(start, end)), lineTop.toInt(), top.toInt(), lineTop - ascent)
        }
    }

    private fun visibleEndOf(start: Int, end: Int): Int {
        var e = end
        while (e > start && (text[e - 1] == ' ' || text[e - 1] == '\n')) e--
        return e
    }

    // Greedy, as Android's simple break strategy: words at spaces, a word wider than the line at its characters.
    private fun breakParagraph(start: Int, end: Int, out: MutableList<IntArray>) {
        if (start == end) {
            out += intArrayOf(start, end)
            return
        }
        var lineStart = start
        var i = start
        while (i < end) {
            var wordEnd = i
            while (wordEnd < end && text[wordEnd] != ' ') wordEnd++
            while (wordEnd < end && text[wordEnd] == ' ') wordEnd++
            val fits = measure(lineStart, visibleEndOf(lineStart, wordEnd)) <= width
            if (!fits && i > lineStart) {
                out += intArrayOf(lineStart, i)
                lineStart = i
                continue
            }
            if (!fits) {
                // One word wider than the line: as many characters as fit, at least one.
                var cut = i + 1
                while (cut < wordEnd && measure(lineStart, cut + 1) <= width) cut++
                out += intArrayOf(lineStart, cut)
                lineStart = cut
                i = cut
                continue
            }
            i = wordEnd
        }
        if (lineStart < end || out.isEmpty()) out += intArrayOf(lineStart, end)
    }

    private fun metricsOf(start: Int, end: Int): Paint.FontMetrics {
        val metrics = Paint.FontMetrics()
        var any = false
        for (run in runs) {
            if (run.start >= end || run.end <= start) continue
            val m = run.paint.getFontMetrics()
            if (!any) {
                metrics.top = m.top
                metrics.ascent = m.ascent
                metrics.descent = m.descent
                metrics.bottom = m.bottom
                any = true
            } else {
                metrics.top = minOf(metrics.top, m.top)
                metrics.ascent = minOf(metrics.ascent, m.ascent)
                metrics.descent = max(metrics.descent, m.descent)
                metrics.bottom = max(metrics.bottom, m.bottom)
            }
        }
        if (!any) paint.getFontMetrics(metrics)
        return metrics
    }

    private fun runsOf(source: CharSequence): List<Run> {
        val spans = (source as? Spanned)?.spanRanges.orEmpty()
        val cuts = mutableListOf(0, source.length)
        for (span in spans) {
            cuts += span.start.coerceIn(0, source.length)
            cuts += span.end.coerceIn(0, source.length)
        }
        val bounds = cuts.distinct().sorted()
        if (source.isEmpty()) return listOf(Run(0, 0, Paint(paint), null, null, false, false))
        return bounds.zipWithNext().filter { (a, b) -> a < b }.map { (a, b) ->
            val over = spans.filter { it.start <= a && it.end >= b }
            var style = paint.typeface?.style ?: Typeface.NORMAL
            var size = paint.textSize
            var foreground: Int? = null
            var background: Int? = null
            var underline = false
            var strike = false
            for (span in over) {
                when (val what = span.what) {
                    is StyleSpan -> style = style or what.style
                    is AbsoluteSizeSpan -> size = if (what.dip) what.size * android.content.res.ResourceFiles.density else what.size.toFloat()
                    is RelativeSizeSpan -> size *= what.sizeChange
                    is ForegroundColorSpan -> foreground = what.foregroundColor
                    is BackgroundColorSpan -> background = what.backgroundColor
                    is UnderlineSpan -> underline = true
                    is StrikethroughSpan -> strike = true
                }
            }
            val runPaint = Paint(paint)
            if (style != (paint.typeface?.style ?: Typeface.NORMAL)) runPaint.typeface = Typeface.create(paint.typeface, style)
            runPaint.textSize = size
            runPaint.textAlign = Paint.Align.LEFT
            Run(a, b, runPaint, foreground, background, underline, strike)
        }
    }

    companion object {
        /** The widest line of [source] laid out without a width limit, Android's. */
        fun getDesiredWidth(source: CharSequence, paint: Paint): Float {
            val layout = StaticLayout(source, paint, Int.MAX_VALUE, Alignment.ALIGN_NORMAL, 1f, 0f, true)
            return (0 until layout.lineCount).maxOfOrNull { layout.getLineWidth(it) } ?: 0f
        }

        internal fun ceilWidth(value: Float): Int = ceil(value).toInt()
    }
}
