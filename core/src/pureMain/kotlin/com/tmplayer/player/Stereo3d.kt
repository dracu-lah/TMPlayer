package com.tmplayer.player

/**
 * Watching a 3D video on an ordinary screen: the two pictures a 3D file carries in each frame,
 * one per eye, drawn as one anaglyph for paper glasses, or as one eye's picture alone.
 *
 * A 3D file keeps the eyes side by side (left half and right half) or one above the other, at
 * full size (a 3840x1080 frame, each eye 1920x1080) or squeezed into an ordinary frame (1920x1080,
 * each eye 960x1080 stretched back on playback): what [outputSize] tells apart by the frame's
 * shape, so the picture comes out the shape of one eye either way. A passive 3D television's
 * files take turns instead, line by line or column by column, each eye in every other one.
 *
 * Drawn for a VR headset (a phone in a viewer) or a 3D television in its side by side mode, the
 * two eyes come out next to each other, each in its own half and at its own shape, whatever
 * the file's layout: so a top and bottom or interleaved file plays there too.
 *
 * The colours are Eric Dubois' least squares anaglyphs, as FFmpeg's `stereo3d` filter carries them
 * (`arcd`, `agmd`, `aybd`): less ghosting and truer colour than taking one channel from each eye.
 * The player menu's page, its choices and their order follow a friend's dashboard player that
 * asked for the feature (DestinyTgRestrict, GPL 3), and Kodi's and VLC's 3D menus.
 *
 * Kept free of any platform so the phone, the television (a Media3 shader) and the desktop (an mpv
 * shader) draw from the same tables, and the rules can be tested without a screen.
 */
data class Stereo3d(
    val layout: Layout = Layout.Off,
    val view: View = View.RedCyan,
    /** For a file stored right eye first: a red lens that sees the wrong eye shows depth inside out. */
    val swapEyes: Boolean = false,
) {
    enum class Layout {
        Off,
        SideBySide,
        TopBottom,

        /** Every other line is one eye's, the first line the left eye's: a passive 3D TV's format. */
        RowInterleaved,

        /** Every other column is one eye's, the first column the left eye's. */
        ColumnInterleaved,
    }

    /** How the two eyes are shown. */
    enum class View(val key: String) {
        RedCyan("rc"),
        GreenMagenta("gm"),
        YellowBlue("yb"),

        /** One eye's picture alone: a 3D video watched flat, with no glasses. */
        OneEye("2d"),

        /** Both eyes next to each other, each in its own half: a VR headset or a 3D TV. */
        Vr("vr"),
        ;

        companion object {
            fun ofKey(key: String?): View = entries.firstOrNull { it.key == key } ?: RedCyan
        }
    }

    val on: Boolean get() = layout != Layout.Off

    companion object {
        val OFF = Stereo3d()

        /**
         * The 3D layout a file's name or caption declares, or null for none. The tags release
         * names use, as Kodi reads them: SBS, HSBS, Half-SBS, Full-SBS, TAB, HTAB, OU, HOU and
         * the words spelled out. OU on its own is a French word ("or"), and TB or HTB (top and
         * bottom) also reads as a size, so either counts only beside a 3D.
         */
        fun detect(name: String): Layout? {
            val text = name.lowercase()
            if (SIDE_BY_SIDE.containsMatchIn(text)) return Layout.SideBySide
            if (TOP_BOTTOM.containsMatchIn(text)) return Layout.TopBottom
            if (THREE_D.containsMatchIn(text) && (OVER_UNDER.containsMatchIn(text) || TB.containsMatchIn(text))) return Layout.TopBottom
            return null
        }

        /**
         * Whether a frame holds both eyes at full size: twice as wide as a picture would be (side
         * by side) or twice as tall (top and bottom). Otherwise each eye is squeezed into half the
         * frame, the common release, and is stretched back to the whole frame's shape.
         */
        fun fullSize(width: Int, height: Int, layout: Layout): Boolean {
            if (width <= 0 || height <= 0) return false
            val aspect = width.toFloat() / height
            return when (layout) {
                Layout.Off -> false
                // 3840x1080 is 3.56; a 2.39:1 film squeezed side by side is still 2.39.
                Layout.SideBySide -> aspect >= FULL_SBS_FROM
                // 1920x2160 is 0.89; a squeezed one is the film's own 1.78 or wider.
                Layout.TopBottom -> aspect <= FULL_TAB_UNTIL
                // Every eye has every other line or column, stretched back to the frame's shape.
                Layout.RowInterleaved, Layout.ColumnInterleaved -> false
            }
        }

        /** The picture's size once the two eyes are drawn as one, for a [width] by [height] frame. */
        fun outputSize(width: Int, height: Int, layout: Layout): Pair<Int, Int> = when {
            layout == Layout.Off || !fullSize(width, height, layout) -> width to height
            layout == Layout.SideBySide -> (width / 2).coerceAtLeast(1) to height
            else -> width to (height / 2).coerceAtLeast(1)
        }

        /**
         * The picture's size as [stereo] draws it: one eye's, or for [View.Vr] two eyes' side by
         * side, kept within [MAX_VR_WIDTH] so a 4K file's pair stays inside what a TV stick's
         * graphics chip can hold.
         */
        fun outputSize(width: Int, height: Int, stereo: Stereo3d): Pair<Int, Int> {
            val (w, h) = outputSize(width, height, stereo.layout)
            if (!stereo.on || stereo.view != View.Vr) return w to h
            val pair = w * 2
            if (pair <= MAX_VR_WIDTH) return pair to h
            return MAX_VR_WIDTH to (h.toLong() * MAX_VR_WIDTH / pair).toInt().coerceAtLeast(1)
        }

        /**
         * The size a VR pair is drawn at when it is shaped for the screen it fills: the screen's
         * own [boxWidth] by [boxHeight], within [MAX_VR_WIDTH] as [outputSize] keeps it, so the
         * last stage has nothing left to fit and each eye keeps the shape chosen for its half.
         */
        fun boxedSize(boxWidth: Int, boxHeight: Int): Pair<Int, Int> {
            if (boxWidth <= 0 || boxHeight <= 0) return 0 to 0
            if (boxWidth <= MAX_VR_WIDTH) return boxWidth to boxHeight
            return MAX_VR_WIDTH to (boxHeight.toLong() * MAX_VR_WIDTH / boxWidth).toInt().coerceAtLeast(1)
        }

        /**
         * Dubois' matrices, FFmpeg's `ana_coeff` divided out of its 16.16 fixed point: three rows
         * (red, green, blue out), each the left eye's red, green and blue weights, then the right's.
         */
        fun matrix(view: View): FloatArray? = when (view) {
            View.RedCyan -> dubois(
                29884, 32768, 11534, -2818, -5767, -131,
                -2621, -2490, -1049, 24773, 48103, -1180,
                -983, -1376, -328, -4719, -7406, 80347,
            )
            View.GreenMagenta -> dubois(
                -4063, -10354, -2556, 34669, 46203, 1573,
                18612, 43778, 9372, -1049, -983, -4260,
                -983, -1769, 1376, 590, 4915, 61407,
            )
            View.YellowBlue -> dubois(
                69599, -13435, 19595, -1048, -8061, -1114,
                -1704, 59507, 4456, 393, 4063, -1114,
                -2490, -11338, 1442, 6160, 12124, 59703,
            )
            View.OneEye, View.Vr -> null
        }

        private fun dubois(vararg fixed: Int): FloatArray = FloatArray(fixed.size) { fixed[it] / 65536f }

        /**
         * The body of a GLSL function that colours one pixel at [pos] (0 to 1 across the output,
         * y as [yDown] says) from [sample], a function of a texture position returning a vec4.
         * The choices are written in as constants, so a change compiles a new shader rather than
         * branching on every pixel of every frame. [texelX] and [texelY] are one source pixel, to
         * keep each eye's samples from bleeding over the seam into the other, and to find the
         * lines and columns of an interleaved file.
         *
         * [eyeShare] places each eye's picture in its half of a VR pair, the share of the half it
         * covers across and down ([VideoScale.boxShare]): centred, black around it under 1, cut
         * over 1. Null draws each eye over its whole half, as the pair's own shape does.
         */
        fun glslBody(
            stereo: Stereo3d,
            sample: String,
            pos: String,
            yDown: Boolean,
            texelX: Float,
            texelY: Float,
            eyeShare: Pair<Float, Float>? = null,
        ): String {
            val vr = stereo.view == View.Vr
            // For VR each half of the output is a whole picture of its own eye.
            val q = if (vr) "q" else pos
            val (first, second) = eyes(stereo.layout, q, yDown, texelX, texelY)
            val (left, right) = if (stereo.swapEyes) second to first else first to second
            if (vr) {
                return buildString {
                    append("bool second = $pos.x >= 0.5;\n")
                    append("vec2 q = vec2(second ? $pos.x * 2.0 - 1.0 : $pos.x * 2.0, $pos.y);\n")
                    val share = eyeShare?.takeIf { (x, y) -> x > 0f && y > 0f && x.isFinite() && y.isFinite() && (x != 1f || y != 1f) }
                    if (share != null) {
                        append("q = (q - 0.5) / vec2(${glslFloat(share.first)}, ${glslFloat(share.second)}) + 0.5;\n")
                        append("if (q.x < 0.0 || q.x > 1.0 || q.y < 0.0 || q.y > 1.0) return vec4(0.0, 0.0, 0.0, 1.0);\n")
                    }
                    append("if (second) return vec4($sample($right).rgb, 1.0);\n")
                    append("return vec4($sample($left).rgb, 1.0);")
                }
            }
            val m = matrix(stereo.view)
                ?: return "return vec4($sample($left).rgb, 1.0);"
            fun row(r: Int, eye: Int) = (0 until 3).joinToString(", ") { glslFloat(m[r * 6 + eye * 3 + it]) }
            return buildString {
                append("vec3 l = $sample($left).rgb;\n")
                append("vec3 r = $sample($right).rgb;\n")
                append("vec3 c = vec3(\n")
                append("    dot(vec3(${row(0, 0)}), l) + dot(vec3(${row(0, 1)}), r),\n")
                append("    dot(vec3(${row(1, 0)}), l) + dot(vec3(${row(1, 1)}), r),\n")
                append("    dot(vec3(${row(2, 0)}), l) + dot(vec3(${row(2, 1)}), r));\n")
                append("return vec4(clamp(c, 0.0, 1.0), 1.0);")
            }
        }

        /**
         * Where the first stored eye (left, top, or the first line or column) and the second are
         * read for output position [p]. An interleaved eye is read at the middle of its own line
         * or column of the pair [p] falls in, so neither eye's lines blend into the other's.
         */
        private fun eyes(layout: Layout, p: String, yDown: Boolean, texelX: Float, texelY: Float): Pair<String, String> = when (layout) {
            Layout.Off, Layout.SideBySide -> {
                val edge = glslFloat(0.5f - texelX / 2)
                "vec2(min($p.x * 0.5, $edge), $p.y)" to "vec2(max(0.5 + $p.x * 0.5, ${glslFloat(0.5f + texelX / 2)}), $p.y)"
            }
            Layout.TopBottom -> {
                // With y up the top of the picture is the upper half of the texture.
                val top = if (yDown) "$p.y * 0.5" else "0.5 + $p.y * 0.5"
                val bottom = if (yDown) "0.5 + $p.y * 0.5" else "$p.y * 0.5"
                val topClamp = if (yDown) "min($top, ${glslFloat(0.5f - texelY / 2)})" else "max($top, ${glslFloat(0.5f + texelY / 2)})"
                val bottomClamp = if (yDown) "max($bottom, ${glslFloat(0.5f + texelY / 2)})" else "min($bottom, ${glslFloat(0.5f - texelY / 2)})"
                "vec2($p.x, $topClamp)" to "vec2($p.x, $bottomClamp)"
            }
            Layout.RowInterleaved -> {
                // Counted from the top line, the left eye's, whichever way the texture's y runs.
                val lines = pixels(texelY)
                val down = if (yDown) "$p.y" else "(1.0 - $p.y)"
                val pair = "floor(min($down, 0.99999) * ${glslFloat((lines / 2).toFloat())})"
                fun line(middle: Float): String {
                    val at = "($pair * 2.0 + ${glslFloat(middle)}) / ${glslFloat(lines.toFloat())}"
                    return if (yDown) at else "1.0 - $at"
                }
                "vec2($p.x, ${line(0.5f)})" to "vec2($p.x, ${line(1.5f)})"
            }
            Layout.ColumnInterleaved -> {
                val columns = pixels(texelX)
                val pair = "floor(min($p.x, 0.99999) * ${glslFloat((columns / 2).toFloat())})"
                fun column(middle: Float) = "($pair * 2.0 + ${glslFloat(middle)}) / ${glslFloat(columns.toFloat())}"
                "vec2(${column(0.5f)}, $p.y)" to "vec2(${column(1.5f)}, $p.y)"
            }
        }

        /** A frame's lines or columns from one texel's share of it, at least a pair. */
        private fun pixels(texel: Float): Int = if (texel > 0f) kotlin.math.round(1f / texel).toInt().coerceAtLeast(2) else 2

        /**
         * mpv's user shader for the desktop: hooked on the picture after it is turned to RGB and
         * before it is scaled, so hardware decoding stays on and the subtitles, drawn after, stay
         * as they are. The output keeps the frame's size; the shape comes from [aspect] instead.
         */
        fun mpvShader(stereo: Stereo3d, width: Int, height: Int): String {
            val texelX = if (width > 0) 1f / width else 0f
            val texelY = if (height > 0) 1f / height else 0f
            return buildString {
                append("//!HOOK MAIN\n")
                append("//!BIND HOOKED\n")
                append("//!DESC TMPlayer 3D\n")
                append("vec4 hook() {\n")
                append(glslBody(stereo, "HOOKED_tex", "HOOKED_pos", yDown = true, texelX = texelX, texelY = texelY).prependIndent("    "))
                append("\n}\n")
            }
        }

        /** The width over height the picture is shown at, for a [width] by [height] frame. */
        fun aspect(width: Int, height: Int, layout: Layout): Double? = aspect(width, height, Stereo3d(layout))

        /** The same for [stereo] as drawn, two eyes wide for [View.Vr]. */
        fun aspect(width: Int, height: Int, stereo: Stereo3d): Double? {
            if (width <= 0 || height <= 0 || !stereo.on) return null
            val (w, h) = outputSize(width, height, stereo)
            return w.toDouble() / h
        }

        private fun glslFloat(value: Float): String {
            val text = value.toString()
            return if ('.' in text || 'E' in text || 'e' in text) text else "$text.0"
        }

        private const val FULL_SBS_FROM = 2.9f
        private const val FULL_TAB_UNTIL = 1.2f
        private const val MAX_VR_WIDTH = 3840

        // A tag stands between separators or at either end, never inside a longer word.
        private const val B = "(?<![a-z0-9])"
        private const val E = "(?![a-z0-9])"
        private val SIDE_BY_SIDE = Regex("$B(h|half|f|full)?[-. _]?sbs$E|${B}side[-. _]?by[-. _]?side$E")
        private val TOP_BOTTOM = Regex("$B(h|half|f|full)?[-. _]?tab$E|${B}top[-. _]?(and[-. _]?)?bottom$E|${B}over[-. _]?under$E")
        private val OVER_UNDER = Regex("$B(h|half|f|full)?[-. _]?ou$E")
        private val TB = Regex("$B(h|half|f|full)?[-. _]?tb$E")
        private val THREE_D = Regex("${B}3d$E")
    }
}
