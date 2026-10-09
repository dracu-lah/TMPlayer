package com.tmplayer.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.platform.Typeface
import com.tmplayer.i18n.Translator
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontVariation
import java.util.Locale

/**
 * Noto subsets for the scripts a desktop may have no font for: Chinese (Simplified and
 * Traditional), Japanese, Korean, Devanagari, Bengali, Malayalam and Arabic, which Persian shares.
 *
 * They are not a font family the text asks for. The text keeps the system's own sans serif, and
 * Skia only reaches these when neither that nor any other installed font has the character: every
 * typeface Compose has loaded sits behind the system fonts in Skia's fallback search. So a desktop
 * with its own CJK or Indic fonts draws with those, and a bare one draws with these instead of
 * boxes. The files are cut by ui/fonts/subset-noto.py; the licence is the SIL Open Font License
 * 1.1, in THIRD_PARTY_NOTICES.md.
 *
 * Each file is a variable font on the weight axis, loaded once and cloned at the weights the app
 * uses, so a SemiBold title in Hindi is drawn SemiBold rather than thickened by Skia.
 */
private object FallbackFonts {
    private val HAN = listOf("NotoSansSC", "NotoSansTC", "NotoSansJP", "NotoSansKR")
    private val OTHERS = listOf("NotoSansDevanagari", "NotoSansBengali", "NotoSansMalayalam", "NotoSansArabicUI")
    private val WEIGHTS = listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)

    /** Each file read once, off the classpath; a missing file is skipped rather than fatal. */
    private val faces: Map<String, List<FontFamily>> by lazy {
        (HAN + OTHERS).associateWith { name ->
            val bytes = FallbackFonts::class.java.getResourceAsStream("/fonts/$name-Subset.ttf")?.use { it.readBytes() }
            val base = bytes?.let { FontMgr.default.makeFromData(Data.makeFromBytes(it)) }
            if (base == null) {
                emptyList()
            } else {
                WEIGHTS.map { weight ->
                    val face = base.makeClone(arrayOf(FontVariation("wght", weight.weight.toFloat())))
                    FontFamily(Typeface(face, alias = "TmFallback-$name-${weight.weight}"))
                }
            }
        }
    }

    /**
     * Skia's fallback takes the first registered font that has the character and does not weigh
     * the language, so the order of the four CJK fonts decides how a Han character is drawn: 直
     * is a different shape in Japanese and in Chinese. The font of the language in use goes first.
     */
    fun inOrder(han: String): List<FontFamily> =
        (listOf(han) + (HAN - han) + OTHERS).flatMap { faces[it].orEmpty() }

    /** Which CJK font leads for [tag], the app's language, or the system's when that is not CJK. */
    fun hanFor(tag: String): String {
        fun of(lang: String, region: String, script: String): String? = when (lang) {
            "ja" -> "NotoSansJP"
            "ko" -> "NotoSansKR"
            "zh" -> if (script == "Hant" || region in setOf("TW", "HK", "MO")) "NotoSansTC" else "NotoSansSC"
            else -> null
        }
        val app = Locale.forLanguageTag(tag)
        val system = Locale.getDefault()
        return of(app.language, app.country, app.script)
            ?: of(system.language, system.country, system.script)
            ?: "NotoSansSC"
    }
}

/**
 * Gives the content a font resolver that has [FallbackFonts] registered, in the order the current
 * language wants. A change between Chinese, Japanese and Korean swaps in a fresh resolver, since
 * one that has registered a font cannot reorder it; any other change keeps the one it has.
 */
@Composable
internal fun ProvideFallbackFonts(content: @Composable () -> Unit) {
    val messages by Translator.active.collectAsState()
    val han = FallbackFonts.hanFor(messages.tag)
    val resolver = remember(han) {
        createFontFamilyResolver().also { resolver ->
            for (family in FallbackFonts.inOrder(han)) runCatching { resolver.resolve(family) }
        }
    }
    CompositionLocalProvider(LocalFontFamilyResolver provides resolver, content = content)
}
