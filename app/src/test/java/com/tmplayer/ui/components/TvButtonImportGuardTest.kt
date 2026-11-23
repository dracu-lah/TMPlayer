package com.tmplayer.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * TV Material's clickables grow 1.1 times under focus unless told not to (WP-03: the About page's
 * Buy me a coffee button spilled past its list, and every `TmButton` on a television grew past its
 * column). So the only place that may use one is [TmButton].kt, which names `scale =` on every call,
 * and every other screen goes through `TmButton`, `TmSecondaryButton` or a row of its own.
 *
 * Reads `app/src/main` (and the shared `ui` sources, which never import TV Material) as text, so
 * it needs no device: an import, or a fully written name, of a TV Material `Button`, `Surface`,
 * `Card`, `ListItem`, `Chip` or `Tab` anywhere else fails.
 */
class TvButtonImportGuardTest {

    private val clickables = listOf(
        "Button", "OutlinedButton", "FilledTonalButton", "IconButton", "OutlinedIconButton", "FilledTonalIconButton",
        "Surface", "Card", "ClassicCard", "StandardCard", "WideCard", "CompactCard", "ListItem", "DenseListItem",
        "Chip", "AssistChip", "FilterChip", "InputChip", "SuggestionChip", "Tab", "TabRow", "NavigationDrawerItem",
    )

    private val usage = Regex("""androidx\.tv\.material3\.(${clickables.joinToString("|")}|\*)(?![A-Za-z0-9_])""")

    private fun sources(): List<File> {
        val roots = listOf(File("src/main"), File("../ui/src"), File("../desktop/src"))
        return roots.filter { it.isDirectory }.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
    }

    /** The lines of [text] that name a TV Material clickable. */
    private fun offences(text: String): List<String> =
        text.lineSequence().map { it.trim() }.filter { usage.containsMatchIn(it) && !it.startsWith("*") && !it.startsWith("//") }.toList()

    @Test
    fun noTvMaterialClickableOutsideTmButton() {
        val all = sources()
        assertTrue("the app's sources are found from ${File(".").absoluteFile}", all.any { it.name == "TmButton.kt" })
        val bad = all.filter { it.name != "TmButton.kt" }.flatMap { f -> offences(f.readText()).map { "${f.path}: $it" } }
        assertEquals("a TV Material clickable outside TmButton.kt grows 1.1 under focus; use TmButton or a row:\n" + bad.joinToString("\n"), emptyList<String>(), bad)
    }

    @Test
    fun everyTvButtonCallPassesAScale() {
        val text = sources().first { it.name == "TmButton.kt" }.readText()
        val calls = callsOf(text, "TvButton")
        assertTrue("TmButton.kt has its TV button call", calls.isNotEmpty())
        for (call in calls) assertTrue("a TvButton call without scale = grows under focus:\n$call", Regex("""\bscale\s*=""").containsMatchIn(call))
    }

    @Test
    fun theGuardSeesWhatItShould() {
        assertTrue(offences("import androidx.tv.material3.Button").isNotEmpty())
        assertTrue(offences("        androidx.tv.material3.Button(").isNotEmpty())
        assertTrue(offences("import androidx.tv.material3.Card").isNotEmpty())
        assertTrue("a wildcard import brings every clickable in", offences("import androidx.tv.material3.*").isNotEmpty())
        assertTrue("ButtonDefaults only names colours", offences("import androidx.tv.material3.ButtonDefaults").isEmpty())
        assertTrue("Text is not a clickable", offences("import androidx.tv.material3.Text").isEmpty())
        val unscaled = callsOf("TvButton(onClick = a, modifier = b, content = { })", "TvButton")
        assertEquals(1, unscaled.size)
        assertTrue(Regex("""\bscale\s*=""").containsMatchIn(unscaled.single()).not())
    }

    /** Each `name(...)` call in [text], to its closing parenthesis (the import line and a name inside a longer one are not calls). */
    private fun callsOf(text: String, name: String): List<String> {
        val out = mutableListOf<String>()
        var from = 0
        while (true) {
            val at = text.indexOf("$name(", from)
            if (at < 0) break
            from = at + name.length
            val before = text.getOrNull(at - 1)
            if (before != null && (before.isLetterOrDigit() || before == '_')) continue
            var depth = 0
            var end = at + name.length
            while (end < text.length) {
                when (text[end]) {
                    '(' -> depth++
                    ')' -> { depth--; if (depth == 0) break }
                }
                end++
            }
            out += text.substring(at, minOf(end + 1, text.length))
        }
        return out
    }
}
