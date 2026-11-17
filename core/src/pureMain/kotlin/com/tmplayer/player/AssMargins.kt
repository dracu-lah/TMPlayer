package com.tmplayer.player

/**
 * Brings an ASS track's bottom lines down to where plain subtitles sit, for mpv.
 *
 * mpv draws an ASS track at the margins its author gave it, and `sub-pos` only moves the lines up
 * or down from there. A track whose dialogue style keeps its lines a sixth of the picture up (a
 * MarginV of 40 on a 288 line script) stayed there with the position at Bottom (issue 36), while
 * the phone and the television, whose Media3 ignores the margin, drew the same lines at the
 * bottom. This reads the track's header (mpv's `sub-ass-extradata`) and names, for
 * `sub-ass-style-overrides`, every bottom aligned style whose margin is above mpv's own margin for
 * plain lines, with that margin in the script's own units. Raised and High then lift these lines
 * as far as plain ones.
 *
 * Only bottom aligned styles with a larger margin are touched: top and middle styles, signs placed
 * with `\pos`, a line's own margin and a smaller margin stay as the author made them. mpv applies
 * the overrides to ASS tracks only, never to a plain track it converted.
 *
 * The header is outside data: anything missing, odd or absurd is skipped, never thrown.
 */
object AssMargins {

    /** mpv's `sub-margin-y` default for plain lines: pixels of a 720 line frame. */
    const val PLAIN_MARGIN_OF_720 = 22

    /** libass's script height when the header names none. */
    const val DEFAULT_PLAY_RES_Y = 288

    /** As much of a header as is read; styles come before any embedded font. */
    const val MAX_HEADER_CHARS = 256 * 1024

    /** As many styles as are named; a real script has a few dozen at most. */
    const val MAX_STYLES = 64

    /** A script height past this is garbage, not a script. */
    private const val MAX_PLAY_RES = 100_000

    /**
     * The value for `sub-ass-style-overrides`: "Default.MarginV=9,Top Line.MarginV=9", or "" when
     * no style needs moving (or [header] is not an ASS header), which clears an earlier track's.
     */
    fun bottomOverrides(header: String?): String {
        if (header.isNullOrBlank()) return ""
        val text = if (header.length > MAX_HEADER_CHARS) header.substring(0, MAX_HEADER_CHARS) else header
        var playResX: Int? = null
        var playResY: Int? = null
        var section = ""
        var format: List<String>? = null
        val styles = mutableListOf<List<String>>()
        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("﻿")
            if (line.startsWith("[")) {
                section = line.lowercase()
                if (section == "[events]" || section == "[fonts]" || section == "[graphics]") break
                continue
            }
            val key = line.substringBefore(':', "").trim().lowercase()
            val value = line.substringAfter(':', "").trim()
            when {
                section == "[script info]" && key == "playresx" -> playResX = playRes(value)
                section == "[script info]" && key == "playresy" -> playResY = playRes(value)
                section.endsWith("styles]") && key == "format" ->
                    format = value.split(',').map { it.trim().lowercase() }
                section.endsWith("styles]") && key == "style" && styles.size < MAX_STYLES * 4 -> {
                    val fields = format ?: continue
                    styles += value.split(',', limit = fields.size).map { it.trim() }
                }
            }
        }
        val fields = format ?: return ""
        val nameAt = fields.indexOf("name")
        val alignAt = fields.indexOf("alignment")
        val marginAt = fields.indexOf("marginv")
        if (nameAt < 0 || alignAt < 0 || marginAt < 0) return ""
        val height = playResY ?: playResX?.let { if (it == 1280) 1024 else it * 3 / 4 }?.takeIf { it > 0 } ?: DEFAULT_PLAY_RES_Y
        val target = ((height.toLong() * PLAIN_MARGIN_OF_720 + 360) / 720).toInt()
        val names = LinkedHashSet<String>()
        for (style in styles) {
            val name = style.getOrNull(nameAt) ?: continue
            if (!nameable(name)) continue
            // Bottom is 1 to 3 in both the ASS numpad and the old SSA numbering.
            val align = style.getOrNull(alignAt)?.toIntOrNull() ?: continue
            if (align !in 1..3) continue
            val margin = style.getOrNull(marginAt)?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: continue
            if (margin <= target) continue
            names += name
            if (names.size == MAX_STYLES) break
        }
        return names.joinToString(",") { "$it.MarginV=$target" }
    }

    private fun playRes(value: String): Int? = value.toIntOrNull()?.takeIf { it in 1..MAX_PLAY_RES }

    /** A name mpv's list and libass's `Style.Param=Value` can carry as it is. */
    private fun nameable(name: String): Boolean =
        name.isNotEmpty() && name.length <= 128 && name.none { it == ',' || it == '=' || it == '\\' || it == '"' || it == '\'' || it.code < 0x20 }
}
