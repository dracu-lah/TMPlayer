package com.tmplayer.ios.media3

import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import androidx.media3.common.text.Cue

/**
 * The text subtitle an engine hands over (EngineReports.subtitleText: ASS event markup, which is
 * how mpv hands SubRip too) as the cues Media3's own decoders would have made of it, for
 * upstream's SubtitleView to draw as on Android.
 *
 * mpv joins the events due together with a real newline; each event's own line breaks are `\N`.
 * Events with the same alignment stand in one cue, one under another, as libass and Media3's
 * SubtitleView stack them.
 *
 * SubRip ([ass] false) as Media3's SubripDecoder: its markup as spans (`{\i1}` italic, `{\b1}`
 * bold, `{\u1}` underline, `{\s1}` struck through, `{\c&HBBGGRR&}` a colour, `{\r}` back to
 * plain), and the first `{\anN}` placing the cue at 8% from the edges it names.
 *
 * ASS as Media3's SsaDecoder: override tags dropped but the last `{\anN}` (or the older `{\aN}`),
 * which places the cue 5% from the edges and aligns its text that way. Media3 also reads the
 * script's styles (their alignment, colours, size) and `\pos`, which an engine does not hand out:
 * a line without an override is placed as most scripts' default style places it, bottom centre.
 */
internal object EngineCues {
    /** The cues for [text], none for null or empty. */
    fun of(text: String?, ass: Boolean): List<Cue> {
        if (text.isNullOrEmpty()) return emptyList()
        val events = text.split('\n').filter { it.isNotEmpty() }.map { event(it, ass) }
        val cues = ArrayList<Cue>()
        var i = 0
        while (i < events.size) {
            // Consecutive events placed alike, one cue.
            var j = i + 1
            while (j < events.size && events[j].alignment == events[i].alignment) j++
            val joined = SpannableStringBuilder()
            for (k in i until j) {
                if (k > i) joined.append('\n')
                joined.append(events[k].text)
            }
            if (joined.toString().isNotBlank()) cues += cue(joined, events[i].alignment, ass)
            i = j
        }
        return cues
    }

    private class Event(val text: CharSequence, val alignment: Int)

    /** One event's text with its spans, and its alignment (numpad, 1 to 9; 0 none). */
    private fun event(markup: String, ass: Boolean): Event {
        val out = SpannableStringBuilder()
        var alignment = 0
        var bold = false
        var italic = false
        var underline = false
        var strike = false
        var color: Int? = null
        var styleFrom = 0
        fun closeStyle() {
            val end = out.length
            if (ass || end <= styleFrom) {
                styleFrom = end
                return
            }
            val style = (if (bold) Typeface.BOLD else 0) or (if (italic) Typeface.ITALIC else 0)
            if (style != 0) out.setSpan(StyleSpan(style), styleFrom, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (underline) out.setSpan(UnderlineSpan(), styleFrom, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (strike) out.setSpan(StrikethroughSpan(), styleFrom, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            color?.let { out.setSpan(ForegroundColorSpan(it), styleFrom, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            styleFrom = end
        }
        var i = 0
        while (i < markup.length) {
            val c = markup[i]
            if (c == '{') {
                val close = markup.indexOf('}', i + 1)
                if (close < 0) {
                    out.append(markup.substring(i))
                    break
                }
                closeStyle()
                for (tag in markup.substring(i + 1, close).split('\\').map { it.trim() }.filter { it.isNotEmpty() }) {
                    val an = tag.removePrefix("an").takeIf { tag.startsWith("an") }?.toIntOrNull()
                    val legacy = tag.removePrefix("a").takeIf { tag.startsWith("a") && !tag.startsWith("an") && !tag.startsWith("alpha") }?.toIntOrNull()
                    val placed = an?.takeIf { it in 1..9 } ?: legacy?.let(::legacyAlignment)
                    if (placed != null) {
                        // SubRip's first alignment counts, ASS's last.
                        if (ass || alignment == 0) alignment = placed
                        continue
                    }
                    if (ass) continue
                    when {
                        tag == "r" -> {
                            bold = false
                            italic = false
                            underline = false
                            strike = false
                            color = null
                        }
                        tag.startsWith("b") && tag.drop(1).all { it.isDigit() } && tag.length > 1 -> bold = (tag.drop(1).toIntOrNull() ?: 0).let { it == 1 || it >= 600 }
                        tag == "i1" || tag == "i0" -> italic = tag == "i1"
                        tag == "u1" || tag == "u0" -> underline = tag == "u1"
                        tag == "s1" || tag == "s0" -> strike = tag == "s1"
                        tag == "c" || tag == "1c" -> color = null
                        tag.startsWith("c&H") || tag.startsWith("1c&H") -> color = assColor(tag.substringAfter("&H").substringBefore('&'))
                    }
                }
                i = close + 1
                continue
            }
            if (c == '\\' && i + 1 < markup.length) {
                when (markup[i + 1]) {
                    'N', 'n' -> {
                        out.append('\n')
                        i += 2
                        continue
                    }
                    'h' -> {
                        out.append(' ')
                        i += 2
                        continue
                    }
                }
            }
            out.append(c)
            i++
        }
        closeStyle()
        return Event(out, alignment)
    }

    /** SSA's older numbering (1 to 3 bottom, 5 to 7 top, 9 to 11 middle) as the numpad's. */
    private fun legacyAlignment(value: Int): Int? = when (value) {
        1, 2, 3 -> value
        5, 6, 7 -> value + 2
        9, 10, 11 -> value - 5
        else -> null
    }

    /** ASS's `BBGGRR` (alpha ignored) as an opaque Android colour. */
    private fun assColor(hex: String): Int? {
        val value = hex.takeLast(6).toLongOrNull(16) ?: return null
        val red = (value and 0xFF).toInt()
        val green = (value shr 8 and 0xFF).toInt()
        val blue = (value shr 16 and 0xFF).toInt()
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun cue(text: CharSequence, alignment: Int, ass: Boolean): Cue {
        val builder = Cue.Builder().setText(text)
        val placed = if (alignment == 0 && ass) DEFAULT_ASS_ALIGNMENT else alignment
        if (placed == 0) return builder.build()
        val positionAnchor = when (placed) {
            1, 4, 7 -> Cue.ANCHOR_TYPE_START
            3, 6, 9 -> Cue.ANCHOR_TYPE_END
            else -> Cue.ANCHOR_TYPE_MIDDLE
        }
        val lineAnchor = when (placed) {
            7, 8, 9 -> Cue.ANCHOR_TYPE_START
            4, 5, 6 -> Cue.ANCHOR_TYPE_MIDDLE
            else -> Cue.ANCHOR_TYPE_END
        }
        val margin = if (ass) SSA_MARGIN else SUBRIP_MARGIN
        fun fraction(anchor: Int) = when (anchor) {
            Cue.ANCHOR_TYPE_START -> margin
            Cue.ANCHOR_TYPE_MIDDLE -> 0.5f
            else -> 1f - margin
        }
        if (ass) {
            builder.setTextAlignment(
                when (positionAnchor) {
                    Cue.ANCHOR_TYPE_START -> Layout.Alignment.ALIGN_NORMAL
                    Cue.ANCHOR_TYPE_END -> Layout.Alignment.ALIGN_OPPOSITE
                    else -> Layout.Alignment.ALIGN_CENTER
                },
            )
        }
        return builder.setPositionAnchor(positionAnchor).setPosition(fraction(positionAnchor))
            .setLineAnchor(lineAnchor).setLine(fraction(lineAnchor), Cue.LINE_TYPE_FRACTION)
            .build()
    }

    /** Media3's SsaDecoder's margin from the edges, and SubripDecoder's. */
    private const val SSA_MARGIN = 0.05f
    private const val SUBRIP_MARGIN = 0.08f

    /** Bottom centre: the alignment of nearly every script's default style. */
    private const val DEFAULT_ASS_ALIGNMENT = 2
}
