package com.tmplayer.data

import com.tmplayer.json.JSONObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What "Report a problem" and "Request a feature" (HX64) send, and how a stored draft and the inbox's answer are read. */
class FeedbackReportTest {

    private val facts = Feedback.Facts("1.40.0", Feedback.Platform.AndroidTv, "Xiaomi MIBOX4, Android 9", "de")
    private val id = "0123456789abcdef0123456789abcdef"

    @Test
    fun `the JSON holds what was typed and the facts, and nothing else`() {
        val report = assertNotNull(FeedbackReport.of(id, FeedbackReport.Kind.Problem, "  It stops.\r\nEvery time.  ", "", null, facts))
        val json = JSONObject(report.toJson())
        assertEquals(setOf("v", "id", "kind", "message", "app"), json.keySet())
        assertEquals("It stops.\nEvery time.", json.getString("message"))
        assertEquals("problem", json.getString("kind"))
        val app = json.getJSONObject("app")
        assertEquals("Android TV or Google TV", app.getString("platform"))
        assertEquals("Xiaomi MIBOX4, Android 9", app.getString("device"))
        assertEquals(setOf("version", "platform", "device", "language"), app.keySet())
    }

    @Test
    fun `a contact and a logs code go only when given, and a logs value that is not a code never goes`() {
        val with = assertNotNull(FeedbackReport.of(id, FeedbackReport.Kind.Feature, "Dark mode please", " me@example.org ", "TMP-7K3Q", facts))
        val json = JSONObject(with.toJson())
        assertEquals("me@example.org", json.getString("contact"))
        assertEquals("TMP-7K3Q", json.getString("logs"))
        assertEquals("feature", json.getString("kind"))
        val bad = assertNotNull(FeedbackReport.of(id, FeedbackReport.Kind.Feature, "x", "  ", "https://evil.example", facts))
        assertFalse(JSONObject(bad.toJson()).has("logs"))
        assertFalse(JSONObject(bad.toJson()).has("contact"))
    }

    @Test
    fun `a blank or too long message, a long contact or a bad id is never sent, and never cut`() {
        assertNull(FeedbackReport.of(id, FeedbackReport.Kind.Problem, " \n\t ", "", null, facts))
        assertNull(FeedbackReport.of(id, FeedbackReport.Kind.Problem, "x".repeat(FeedbackReport.MAX_MESSAGE + 1), "", null, facts))
        assertNotNull(FeedbackReport.of(id, FeedbackReport.Kind.Problem, "x".repeat(FeedbackReport.MAX_MESSAGE), "", null, facts))
        assertNull(FeedbackReport.of(id, FeedbackReport.Kind.Problem, "ok", "c".repeat(FeedbackReport.MAX_CONTACT + 1), null, facts))
        assertNull(FeedbackReport.of("nope", FeedbackReport.Kind.Problem, "ok", "", null, facts))
        assertEquals(FeedbackReport.Problem.Empty, FeedbackReport.problem("   ", ""))
        assertEquals(FeedbackReport.Problem.TooLong, FeedbackReport.problem("y".repeat(4_001), ""))
        assertEquals(FeedbackReport.Problem.ContactTooLong, FeedbackReport.problem("ok", "z".repeat(121)))
        assertNull(FeedbackReport.problem("ok", ""))
    }

    @Test
    fun `control characters and turn around marks go, line breaks stay, a contact is one line`() {
        val report = assertNotNull(
            FeedbackReport.of(id, FeedbackReport.Kind.Problem, "one\u0000\u202e\ntwo\n\n\n\n\n\nthree", "a\nb\u0007c", null, facts),
        )
        assertEquals("one\ntwo\n\n\nthree", report.message)
        assertEquals("a bc", report.contact)
    }

    @Test
    fun `a report reads back as itself, and garbage reads as none`() {
        val report = assertNotNull(FeedbackReport.of(id, FeedbackReport.Kind.Feature, "Idea", "@me", "TMP-7K3Q", facts))
        assertEquals(report, FeedbackReport.parse(report.toJson()))
        for (text in listOf("", "{", "[]", "null", "{\"v\":1}", report.toJson().replace("\"v\":1", "\"v\":2"), "x".repeat(20_000))) {
            assertNull(FeedbackReport.parse(text), text.take(40))
        }
    }

    @Test
    fun `ids are random hex and never the same twice`() {
        val a = FeedbackReport.newId()
        assertTrue(Regex("[0-9a-f]{32}").matches(a))
        assertNotEquals(a, FeedbackReport.newId())
        assertEquals(FeedbackReport.newId(Random(1)), FeedbackReport.newId(Random(1)))
    }

    @Test
    fun `a draft survives being kept, and a broken one falls back field by field`() {
        val report = assertNotNull(FeedbackReport.of(id, FeedbackReport.Kind.Feature, "Idea", "", null, facts))
        val draft = FeedbackDraft(FeedbackReport.Kind.Feature, "Idea", "@me", includeLogs = true, id = id, logsCode = "TMP-7K3Q", pending = report.toJson())
        assertEquals(draft, FeedbackDraft.decode(draft.encode()))
        assertNull(FeedbackDraft.decode(null))
        assertNull(FeedbackDraft.decode("not json"))
        val odd = assertNotNull(FeedbackDraft.decode("""{"kind":7,"message":["x"],"logs":"yes","id":"short","logsCode":"bad","pending":"{}"}"""))
        assertEquals(FeedbackReport.Kind.Problem, odd.kind)
        assertEquals("", odd.message)
        assertFalse(odd.includeLogs)
        assertTrue(Regex("[0-9a-f]{32}").matches(odd.id))
        assertNull(odd.logsCode)
        assertNull(odd.pending)
        // A pending report of another draft is not this one's.
        val other = draft.copy(id = "f".repeat(32))
        assertNull(assertNotNull(FeedbackDraft.decode(other.encode())).pending)
    }

    @Test
    fun `the inbox's answer is read for its code, its limit and its refusals`() {
        assertEquals(TitleUpload.Outcome.Sent("FB-7K3Q-M4XP"), FeedbackUpload.outcome(201, """{"code":"FB-7K3Q-M4XP","duplicate":false}"""))
        assertEquals(TitleUpload.Outcome.Sent("FB-7K3Q-M4XP", duplicate = true), FeedbackUpload.outcome(200, """{"code":"FB-7K3Q-M4XP","duplicate":true}"""))
        assertEquals(TitleUpload.Outcome.SlowDown(60), FeedbackUpload.outcome(429, null, "nonsense"))
        assertEquals(TitleUpload.Outcome.SlowDown(30), FeedbackUpload.outcome(429, null, "30"))
        assertIs<TitleUpload.Outcome.Failed>(FeedbackUpload.outcome(201, """{"code":"TMP-7K3Q"}"""))
        assertIs<TitleUpload.Outcome.Failed>(FeedbackUpload.outcome(201, "<html>captive portal</html>"))
        assertEquals(TitleUpload.Outcome.Failed(400), FeedbackUpload.outcome(400, """{"error":"message_too_long"}"""))
    }

    @Test
    fun `the feedback address is the build's inbox with its own path, and none without one`() {
        assertEquals("https://inbox.example/v1/feedback", LogUpload.feedbackUrl("https://inbox.example/v1/report"))
        assertEquals("https://inbox.example/v1/feedback", LogUpload.feedbackUrl("https://inbox.example/"))
        assertNull(LogUpload.feedbackUrl(""))
        assertNull(LogUpload.feedbackUrl("http://inbox.example/v1/report"))
        assertNull(LogUpload.feedbackUrl(null))
    }

    @Test
    fun `the feature request page has its template and the platform, and nothing about the device`() {
        val url = Feedback.featureUrl(facts)
        assertTrue(url.startsWith(Feedback.ISSUES + "?template=feature_request.yml"))
        assertTrue(url.contains("platform=TV%20%28Android%20TV%2C%20Google%20TV%2C%20Fire%20TV%29"), url)
        assertFalse(url.contains("MIBOX"))
        assertFalse(Feedback.featureUrl(facts.copy(platform = Feedback.Platform.Other)).contains("platform"))
        assertTrue(Feedback.mailto(facts, feature = true).contains("feature%20idea"))
    }

    @Test
    fun `a message at its cap in any script fits the inbox's body cap`() {
        val report = assertNotNull(
            FeedbackReport.of(id, FeedbackReport.Kind.Problem, "\u591c\"".repeat(FeedbackReport.MAX_MESSAGE / 2), "\u591c".repeat(FeedbackReport.MAX_CONTACT), null, facts),
        )
        assertTrue(report.toJson().encodeToByteArray().size <= FeedbackUpload.MAX_BYTES)
    }
}
