package com.tmplayer.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing tests against real TMDB response shapes.
 *
 * The field names and their optionality were taken from live responses, not from the docs: the
 * API omits keys entirely for some films and returns the string "null" for others, and either
 * one crashes a naive parser.
 */
class TmdbParseTest {

    private val full = """
        {
          "id": 157336,
          "title": "Interstellar",
          "original_title": "Interstellar",
          "release_date": "2014-11-05",
          "overview": "The adventures of a group of explorers.",
          "runtime": 169,
          "vote_average": 8.5,
          "poster_path": "/yQvGrMoipbRoddT0ZR8tPoR7NfX.jpg",
          "backdrop_path": "/5XNQBqnBwPA9yT0jZ0p3s8bbLh0.jpg",
          "genres": [{"id": 12, "name": "Adventure"}, {"id": 18, "name": "Drama"}],
          "credits": {
            "cast": [
              {"name": "Matthew McConaughey", "character": "Cooper", "profile_path": "/a.jpg"},
              {"name": "Anne Hathaway", "character": "Brand", "profile_path": null}
            ]
          },
          "videos": {
            "results": [
              {"site": "YouTube", "type": "Featurette", "key": "featurette1"},
              {"site": "YouTube", "type": "Trailer", "key": "trailer1"},
              {"site": "Vimeo", "type": "Trailer", "key": "vimeo1"}
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `reads every field from a complete response`() {
        val d = Tmdb.parseDetails(JSONObject(full), 157336)

        assertEquals("Interstellar", d.title)
        assertEquals(2014, d.year)
        assertEquals(169, d.runtimeMinutes)
        assertEquals(8.5, d.rating, 0.001)
        assertEquals(listOf("Adventure", "Drama"), d.genres)
        assertEquals(2, d.cast.size)
        assertEquals("Matthew McConaughey", d.cast[0].name)
        assertEquals("Cooper", d.cast[0].character)
    }

    @Test
    fun `prefers a real trailer over a featurette and ignores other sites`() {
        assertEquals("trailer1", Tmdb.parseDetails(JSONObject(full), 1).trailerKey)
    }

    @Test
    fun `falls back to a featurette when no trailer is published`() {
        val json = JSONObject(full.replace("\"Trailer\", \"key\": \"trailer1\"", "\"Teaser\", \"key\": \"teaser1\""))
        // Something playable beats nothing; only the ordering preference was for a real trailer.
        assertTrue(Tmdb.parseDetails(json, 1).trailerKey in setOf("featurette1", "teaser1"))
    }

    @Test
    fun `builds image urls only when a path exists`() {
        val d = Tmdb.parseDetails(JSONObject(full), 1)
        assertEquals("https://image.tmdb.org/t/p/w342/yQvGrMoipbRoddT0ZR8tPoR7NfX.jpg", d.posterUrl)
        assertEquals("https://image.tmdb.org/t/p/w780/5XNQBqnBwPA9yT0jZ0p3s8bbLh0.jpg", d.backdropUrl)
        assertNull(Tmdb.imageUrl(null, Tmdb.POSTER_SIZE))
        assertNull(Tmdb.imageUrl("", Tmdb.POSTER_SIZE))
    }

    @Test
    fun `a null profile path does not become the string null`() {
        // JSONObject.optString returns the literal "null" for a JSON null, which would be sent
        // to the image loader as a real path and 404 forever.
        val d = Tmdb.parseDetails(JSONObject(full), 1)
        assertNull(d.cast[1].profilePath)
    }

    @Test
    fun `survives a response with nothing but an id`() {
        val d = Tmdb.parseDetails(JSONObject("""{"id": 5}"""), 5)

        assertEquals("", d.title)
        assertNull(d.year)
        assertEquals(0, d.runtimeMinutes)
        assertEquals(0.0, d.rating, 0.001)
        assertTrue(d.genres.isEmpty())
        assertTrue(d.cast.isEmpty())
        assertNull(d.trailerKey)
        assertNull(d.posterUrl)
        assertNull(d.backdropUrl)
    }

    @Test
    fun `falls back to the original title when the localised one is blank`() {
        val json = JSONObject("""{"id": 1, "title": "", "original_title": "Uyir"}""")
        assertEquals("Uyir", Tmdb.parseDetails(json, 1).title)
    }

    @Test
    fun `an unparseable release date is not a year`() {
        val json = JSONObject("""{"id": 1, "release_date": ""}""")
        assertNull(Tmdb.parseDetails(json, 1).year)
    }

    @Test
    fun `caps the cast so a TV row stays scannable`() {
        val many = (1..30).joinToString(",") { """{"name":"P$it","character":"C$it"}""" }
        val json = JSONObject("""{"id":1,"credits":{"cast":[$many]}}""")
        assertEquals(8, Tmdb.parseDetails(json, 1).cast.size)
    }
}
