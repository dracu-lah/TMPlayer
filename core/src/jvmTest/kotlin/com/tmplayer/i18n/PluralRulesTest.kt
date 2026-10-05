package com.tmplayer.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluralRulesTest {

    private fun check(language: String, expected: Map<String, List<Number>>) {
        for ((category, numbers) in expected) for (n in numbers) {
            assertEquals("$language $n", category, PluralRules.select(language, n))
        }
    }

    @Test
    fun `english, german and italian count one only for a whole 1`() {
        for (language in listOf("en", "de", "it")) {
            check(language, mapOf("one" to listOf(1, 1L), "other" to listOf(0, 2, 5, 11, 21, 101, 1.5, 1.0)))
        }
    }

    @Test
    fun `spanish, portuguese, french and italian have a many for exact millions`() {
        for (language in listOf("es", "pt", "fr", "it")) {
            check(language, mapOf("many" to listOf(1_000_000, 2_000_000L), "other" to listOf(1_000_001, 100, 1_500_000.5)))
        }
    }

    @Test
    fun `spanish one is exactly 1`() {
        check("es", mapOf("one" to listOf(1, 1.0), "other" to listOf(0, 2, 0.5, 1.5, 21)))
    }

    @Test
    fun `portuguese and french count 0 and 1 and the fractions between as one`() {
        for (language in listOf("pt", "pt-BR", "fr")) {
            check(language, mapOf("one" to listOf(0, 1, 0.5, 1.5), "other" to listOf(2, 2.5, 10, 100)))
        }
    }

    @Test
    fun `russian and ukrainian have one, few and many by the last digits`() {
        for (language in listOf("ru", "uk")) {
            check(
                language,
                mapOf(
                    "one" to listOf(1, 21, 31, 101, 1001),
                    "few" to listOf(2, 3, 4, 22, 23, 24, 102, 1004),
                    "many" to listOf(0, 5, 9, 10, 11, 12, 13, 14, 15, 19, 20, 25, 100, 111, 112, 114),
                    "other" to listOf(1.5, 2.5, 0.1),
                ),
            )
        }
    }

    @Test
    fun `arabic has zero, one, two, few, many and other`() {
        check(
            "ar",
            mapOf(
                "zero" to listOf(0),
                "one" to listOf(1),
                "two" to listOf(2),
                "few" to listOf(3, 7, 10, 103, 110, 1003),
                "many" to listOf(11, 26, 99, 111, 199, 1011),
                "other" to listOf(100, 101, 102, 200, 1000, 0.5, 2.5),
            ),
        )
    }

    @Test
    fun `turkish and malayalam count one for 1 only`() {
        for (language in listOf("tr", "ml")) {
            check(language, mapOf("one" to listOf(1, 1.0), "other" to listOf(0, 2, 11, 21, 0.5)))
        }
    }

    @Test
    fun `hindi counts 0 and 1 and the fractions below 1 as one`() {
        check("hi", mapOf("one" to listOf(0, 1, 0.5, 1.0), "other" to listOf(2, 1.5, 10, 21)))
    }

    @Test
    fun `chinese, japanese, korean, vietnamese and indonesian have one form`() {
        for (language in listOf("zh", "zh-CN", "ja", "ko", "vi", "id", "in")) {
            check(language, mapOf("other" to listOf(0, 1, 2, 5, 11, 21, 100, 1_000_000, 1.5)))
        }
    }

    @Test
    fun `every shipped language has its categories listed and ends in other`() {
        for (tag in Languages.tags) {
            val categories = PluralRules.categories(tag)
            assertEquals(tag, "other", categories.last())
            for (n in 0..1200) {
                val category = PluralRules.select(tag, n)
                assertTrue("$tag $n gave $category", category in categories)
            }
        }
        assertEquals(6, PluralRules.categories("ar").size)
        assertEquals(listOf("one", "few", "many", "other"), PluralRules.categories("uk"))
    }

    @Test
    fun `negative numbers take the category of their absolute value`() {
        assertEquals("one", PluralRules.select("en", -1))
        assertEquals("few", PluralRules.select("ru", -3))
    }
}
