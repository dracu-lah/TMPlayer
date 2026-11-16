package com.tmplayer.online

import com.tmplayer.player.Chapter
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipChaptersTest {

    private fun kind(title: String?) = ChapterSkips.kindOf(title)

    @Test
    fun `titles in many languages name their kind`() {
        val table: Map<SkipKind, List<String>> = mapOf(
            SkipKind.Intro to listOf(
                "Intro", "INTRO", "Intro Sequence", "Cabecera", "Apertura", "Générique", "Generique de debut", "Vorspann",
                "Sigla", "Titoli di testa", "Abertura", "Begingeneriek", "Заставка", "Интро", "Czołówka", "Açılış Jeneriği",
                "片头", "片頭曲", "인트로", "المقدمة", "Znělka", "Pembukaan", "Mở đầu",
            ),
            SkipKind.Opening to listOf(
                "Opening", "OP", "OP1", "NCOP2", "Opening Theme", "Opening Credits", "Title Sequence", "オープニング", "오프닝", "Опенинг",
            ),
            SkipKind.Recap to listOf(
                "Recap", "Previously on", "Previously...", "The story so far", "Resumen", "Anteriormente", "Résumé", "Précédemment",
                "Rückblick", "Was bisher geschah", "Riassunto", "Nelle puntate precedenti", "Resumo", "Eerder", "Voorgaande",
                "Рекап", "Ранее", "Poprzednio", "Önceki Bölüm", "前回のあらすじ", "あらすじ", "前情提要", "上集回顾",
                "지난 이야기", "ملخص", "Tidigare", "Minule", "Sebelumnya", "Tóm tắt",
            ),
            SkipKind.Credits to listOf(
                "Credits", "End Credits", "Closing credits", "Outro", "Créditos", "Créditos finais", "Générique de fin", "Abspann",
                "Titoli di coda", "Aftiteling", "Титры", "Napisy końcowe", "Kapanış Jeneriği", "クレジット", "スタッフロール",
                "片尾", "演职员表", "크레딧", "الختام", "Eftertexter", "Rulletekster", "Závěrečné titulky", "Kredit Akhir",
            ),
            SkipKind.Ending to listOf("Ending", "ED", "ED2", "NCED", "Ending Theme", "エンディング", "엔딩", "Эндинг"),
            SkipKind.Preview to listOf(
                "Preview", "Next Time", "Coming up", "Avance", "Próximo capítulo", "Prochainement", "Vorschau", "Nächste Folge",
                "Anteprima", "Prossimamente", "Prévia", "Volgende week", "Анонс", "Zapowiedź", "Sonraki Bölüm", "次回予告",
                "预告", "예고", "الحلقة القادمة", "Nästa avsnitt", "Episode Berikutnya",
            ),
        )
        val wrong = table.flatMap { (expected, titles) ->
            titles.mapNotNull { title ->
                val got = kind(title)
                if (got == expected) null else "$title: wanted $expected, got $got"
            }
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun `every kind is reachable`() {
        val reached = listOf("Intro", "Opening", "Recap", "Credits", "Ending", "Preview").map { kind(it) }.toSet()
        assertEquals(SkipKind.entries.toSet(), reached)
    }

    @Test
    fun `ordinary chapter titles are not skips`() {
        listOf(
            "Chapter 1", "Chapter 12", "Scene 3", "Opening scene", "Escena de apertura", "The Beginning", "Act II", "Finale",
            "Introduction to Algebra", "Part 2", "", " ", "   ", null, "-", "...", "12:00", "Ep. 5", "Main Story",
            "Lesson 1 - Basics", "第一章", "제1장", "Глава 1", "Kapitel 3", "Capítulo 2", "Ceremony", "Speech",
        ).forEach { assertNull("'$it'", kind(it)) }
    }

    @Test
    fun `accents and case do not matter and a decomposed accent is the same letter`() {
        assertEquals(SkipKind.Preview, kind("PRÓXIMO CAPÍTULO"))
        assertEquals(SkipKind.Preview, kind("Próximo capítulo"))
        assertEquals(SkipKind.Credits, kind("  --  CRÉDITOS !! "))
        assertEquals(SkipKind.Recap, kind("rueckblick"))
    }

    @Test
    fun `a word inside another word is not a match`() {
        assertNull(kind("Opera"))
        assertNull(kind("Editor"))
        assertNull(kind("Introspection"))
        assertNull(kind("Recapture"))
    }

    @Test
    fun `chapters become segments that end at the next chapter`() {
        val chapters = listOf(
            Chapter(0, "Recap"),
            Chapter(45_000, "Opening"),
            Chapter(135_000, "Part 1"),
            Chapter(1_300_000, "Ending"),
            Chapter(1_390_000, "Preview"),
        )
        val got = ChapterSkips.segments(chapters, 1_440_000)
        assertEquals(
            listOf(
                SkipSegment(SkipKind.Recap, 0, 45_000, SkipSource.Chapter),
                SkipSegment(SkipKind.Opening, 45_000, 135_000, SkipSource.Chapter),
                SkipSegment(SkipKind.Ending, 1_300_000, 1_390_000, SkipSource.Chapter),
                SkipSegment(SkipKind.Preview, 1_390_000, 1_440_000, SkipSource.Chapter),
            ),
            got,
        )
    }

    @Test
    fun `too short, too long and open ended stretches are not skips`() {
        // Four seconds, and over two fifths of a 20 minute file.
        assertEquals(emptyList<SkipSegment>(), ChapterSkips.segments(listOf(Chapter(0, "Intro"), Chapter(4_000, "Story")), 1_200_000))
        assertEquals(emptyList<SkipSegment>(), ChapterSkips.segments(listOf(Chapter(0, "Intro"), Chapter(600_000, "Story")), 1_200_000))
        // The last chapter of a file of unknown length: credits run to the end, an intro cannot.
        assertEquals(
            listOf(SkipSegment(SkipKind.Credits, 900_000, null, SkipSource.Chapter)),
            ChapterSkips.segments(listOf(Chapter(900_000, "Credits")), 0),
        )
        assertEquals(emptyList<SkipSegment>(), ChapterSkips.segments(listOf(Chapter(900_000, "Intro")), 0))
        // Chapters out of order, negative, past the end, with no title.
        val messy = listOf(Chapter(90_000, "Story"), Chapter(-5, "Intro"), Chapter(5_000_000, "Credits"), Chapter(0, null), Chapter(10_000, "Intro"))
        assertEquals(
            listOf(SkipSegment(SkipKind.Intro, 10_000, 90_000, SkipSource.Chapter)),
            ChapterSkips.segments(messy, 1_200_000),
        )
    }

    @Test
    fun `garbage titles and chapter lists never throw`() {
        val random = Random(7)
        val nasty = listOf(
            "\u0000", "\uD800", "😀".repeat(100), "a".repeat(100_000), "OP".repeat(5_000), "\n\t ", "%%%", "0", "-1",
            String(CharArray(500) { (random.nextInt(0x20, 0x2FFF)).toChar() }),
            "ｉｎｔｒｏ", "İNTRO", "ı", "ß".repeat(300),
        )
        nasty.forEach { kind(it) }
        repeat(300) {
            val titles = String(CharArray(random.nextInt(0, 40)) { random.nextInt(0x20, 0xD7FF).toChar() })
            kind(titles)
            ChapterSkips.segments(
                List(random.nextInt(0, 30)) { Chapter(random.nextLong(-1_000, 3_000_000), if (random.nextBoolean()) titles else nasty.random(random)) },
                random.nextLong(-10, 4_000_000),
            )
        }
        ChapterSkips.segments(List(100_000) { Chapter(it * 1000L, "Intro") }, 1_000_000_000L)
    }
}
