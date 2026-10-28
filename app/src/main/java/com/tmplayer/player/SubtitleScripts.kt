package com.tmplayer.player

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import androidx.media3.common.text.Cue
import com.tmplayer.platform.Logger

/**
 * A bundled font for subtitles in a script the system has no font for.
 *
 * Android has shipped Noto for every script OpenSubtitles and SubDL serve since 5.0, so the system
 * draws them. Malayalam is the one bundled anyway: MSone's subtitles are all Malayalam, and a
 * trimmed television build that dropped its font would show every line as boxes. The file is cut
 * by ui/fonts/subtitle-fonts.py (Noto Sans Malayalam, SIL Open Font License 1.1). Lines in other
 * scripts keep the system's fonts, and the Latin in a Malayalam line still comes from the system,
 * since a typeface falls back to the system's for any character it lacks.
 */
object SubtitleScripts {

    private class Script(val range: IntRange, val asset: String, val probe: String)

    private val BUNDLED = listOf(
        Script(0x0D00..0x0D7F, "fonts/NotoSansMalayalam-Regular.ttf", "ക്ക"),
    )

    /** Whether the system draws each script, asked once per process. */
    private val systemHas = HashMap<Script, Boolean>()
    private val loaded = HashMap<Script, Typeface?>()

    /**
     * The bundled typeface [cues] need, or null when the system has a font for all of them (the
     * usual case). Cheap: a cue's text is read only until the first character of a bundled script.
     */
    fun typefaceFor(context: Context, cues: List<Cue>): Typeface? {
        for (cue in cues) {
            val text = cue.text ?: continue
            for (i in text.indices) {
                val c = text[i].code
                val script = BUNDLED.firstOrNull { c in it.range } ?: continue
                if (systemHas.getOrPut(script) { Paint().hasGlyph(script.probe) }) return null
                return loaded.getOrPut(script) {
                    runCatching { Typeface.createFromAsset(context.assets, script.asset) }
                        .onFailure { Logger.w("SubtitleScripts", "No bundled ${script.asset}", it) }
                        .getOrNull()
                }
            }
        }
        return null
    }
}
