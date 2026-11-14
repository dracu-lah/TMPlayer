package com.tmplayer.data

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every row of `resources/names/corpus.tsv` through [MediaName], one test, one diff (N2). The file
 * is the memory of every name shape the parser was taught: a row is added with the report that
 * showed the shape, and the parser is changed until the whole file is green.
 *
 * `ambiguous.tsv` (a name with two defensible readings) and `known-gaps.tsv` (a certain answer the
 * parser does not give yet) are read and printed with what the parser says now, but never
 * asserted: the test output is the list of what is left to decide or to fix.
 */
class NameGoldenCorpusTest {

    private data class Row(
        val line: Int,
        val raw: String,
        val title: String,
        val year: Int?,
        val season: Int?,
        val episode: Int?,
        val part: Int?,
        val extra: ExtraKind?,
        /** Only in the files that are not asserted: why the row is there. */
        val note: String,
    ) {
        fun expected(): String = describe(title, year, season, episode, part, extra)
    }

    private fun read(parsed: ParsedName) =
        describe(parsed.title, parsed.year, parsed.season, parsed.episode, parsed.part, parsed.extra)

    /** "മ" in the file is the character; nothing else is escaped. */
    private fun unescaped(text: String): String =
        Regex("""\\u([0-9a-fA-F]{4})""").replace(text) { it.groupValues[1].toInt(16).toChar().toString() }

    private fun rows(name: String): List<Row> {
        val stream = javaClass.getResourceAsStream("/names/$name") ?: fail("names/$name is missing from the test resources")
        val out = ArrayList<Row>()
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEachIndexed { index, line ->
                if (line.isBlank() || line.startsWith("#")) return@forEachIndexed
                val cells = line.split('\t')
                fun cell(at: Int) = cells.getOrNull(at)?.trim().orEmpty()
                fun number(at: Int) = cell(at).takeIf { it.isNotEmpty() }?.let {
                    it.toIntOrNull() ?: fail("$name line ${index + 1}: \"$it\" is not a number")
                }
                val extra = cell(6).takeIf { it.isNotEmpty() }?.let { word ->
                    ExtraKind.entries.firstOrNull { it.name == word } ?: fail("$name line ${index + 1}: \"$word\" is not an extra")
                }
                out += Row(
                    line = index + 1, raw = unescaped(cells[0]), title = unescaped(cell(1)),
                    year = number(2), season = number(3), episode = number(4), part = number(5), extra = extra,
                    note = cell(7),
                )
            }
        }
        return out
    }

    private fun parse(raw: String) = MediaName.parse(raw, maxYear = 2027)

    @Test
    fun `every row of the corpus reads as written`() {
        val rows = rows("corpus.tsv")
        assertTrue(rows.size >= 50, "the corpus has only ${rows.size} rows: is the file being read?")
        val failures = rows.mapNotNull { row ->
            val got = read(parse(row.raw))
            if (got == row.expected()) null else "line ${row.line}: ${row.raw.show()}\n    expected ${row.expected()}\n    got      $got"
        }
        if (failures.isNotEmpty()) {
            fail("${failures.size} of ${rows.size} names read differently from corpus.tsv:\n" + failures.joinToString("\n"))
        }
    }

    @Test
    fun `the corpus has no row twice`() {
        val twice = rows("corpus.tsv").groupBy { it.raw }.filter { it.value.size > 1 }.keys
        assertTrue(twice.isEmpty(), "rows in corpus.tsv more than once: $twice")
    }

    @Test
    fun `ambiguous names and known gaps are listed, not asserted`() {
        for (file in listOf("ambiguous.tsv", "known-gaps.tsv")) {
            val rows = rows(file)
            println("== $file: ${rows.size} names, not asserted ==")
            for (row in rows) {
                val got = read(parse(row.raw))
                val agrees = got == row.expected()
                val mark = when {
                    !agrees -> "reads"
                    file == "known-gaps.tsv" -> "NOW READS AS WANTED, move it to corpus.tsv:"
                    else -> "reads the likely way:"
                }
                println("  line ${row.line}: ${row.raw.show()}\n    $mark $got\n    wanted ${row.expected()}  (${row.note})")
            }
        }
    }

    private fun String.show(): String = "\"" + map { if (it.code < 0x20 || it.code in 0x200b..0x200f || it.code in 0xfe00..0xfe0f) "\\u%04x".format(it.code) else it.toString() }.joinToString("") + "\""
}

private fun describe(title: String, year: Int?, season: Int?, episode: Int?, part: Int?, extra: ExtraKind?): String =
    "\"$title\"" + listOf("year" to year, "season" to season, "episode" to episode, "part" to part, "extra" to extra)
        .filter { it.second != null }.joinToString("") { " ${it.first}=${it.second}" }
