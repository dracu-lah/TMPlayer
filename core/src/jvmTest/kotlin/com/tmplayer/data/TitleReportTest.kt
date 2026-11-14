package com.tmplayer.data

import com.tmplayer.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TitleReportTest {

    private fun report(name: String, title: String = "Night Train", chat: String? = null, includeChat: Boolean = false) =
        assertNotNull(TitleReport.of(name, title, chat, includeChat), "no report for \"$name\"")

    @Test
    fun `a plain name and title are sent as they are`() {
        val report = report("Night.Train.2024.1080p.mkv", "Night Train")
        assertEquals("Night.Train.2024.1080p.mkv", report.fileName)
        assertEquals("Night Train", report.title)
        assertNull(report.chatName)
    }

    @Test
    fun `a phone number inside a name is gone before the viewer sees it`() {
        for (number in listOf("+91 98765 43210", "+919876543210", "9876543210", "(555) 123-4567", "98765-43210", "+44 20 7946 0958")) {
            val scrubbed = report("Night Train $number 1080p.mkv").fileName
            assertEquals("Night Train 1080p.mkv", scrubbed, "number $number")
        }
        assertEquals("Night_Train_.mkv", report("Night_Train_+919876543210.mkv").fileName.replace(" ", ""))
    }

    @Test
    fun `a year, a resolution and an air date are not phone numbers`() {
        for (name in listOf(
            "Night Train 2024 1080p.mkv", "Night.Train.20241080p.mkv", "Show 2024-10-14 1080p.mkv",
            "Show 14.10.2024 720p.mkv", "Night Train S01E02 2160p x265 10bit.mkv", "Night Train 1999.mkv",
        )) {
            assertEquals(name, report(name).fileName)
        }
    }

    @Test
    fun `a username inside a name is replaced`() {
        assertEquals("@user Night Train.mkv", report("@CreatorClips Night Train.mkv").fileName)
        assertEquals("Night Train @user.mp4", report("Night Train @cinema_company.mp4").fileName)
        assertEquals("@user.mp4", report("@CC_Malappuram_Haji.mp4").fileName)
        assertFalse(report("Night Train @some_one_else 1080p.mkv").fileName.contains("some"))
        // The viewer's own title is scrubbed the same way.
        assertEquals("Night Train @user", report("a.mkv", "Night Train @someone").title)
    }

    @Test
    fun `an e-mail address and a telegram link go too`() {
        assertEquals("Night Train 1080p.mkv", report("Night Train jane.doe@example.com 1080p.mkv").fileName)
        assertEquals("Night Train 1080p.mkv", report("Night Train https://t.me/somechannel/123 1080p.mkv").fileName)
        assertEquals("Night Train 1080p.mkv", report("Night Train t.me/somechannel 1080p.mkv").fileName)
    }

    @Test
    fun `scrubbing twice changes nothing more`() {
        for (name in listOf("Night Train +91 98765 43210 @someone a@b.co.mkv", "@CC_Night.mkv", "plain.mkv", "@@@ ++ 12")) {
            val once = TitleReport.scrub(name)
            assertEquals(once, TitleReport.scrub(once), name)
        }
    }

    @Test
    fun `a huge name is cut to the cap and never through a pair`() {
        val huge = report("a".repeat(100_000) + ".mkv", "b".repeat(100_000))
        assertEquals(TitleReport.MAX_NAME, huge.fileName.length)
        assertEquals(TitleReport.MAX_TITLE, huge.title.length)
        // A pair that straddles the cap is dropped whole rather than left as half.
        val emoji = report("x".repeat(TitleReport.MAX_NAME - 1) + "😀😀")
        assertFalse(emoji.fileName.last().isHighSurrogate())
        assertTrue(emoji.fileName.length <= TitleReport.MAX_NAME)
        assertTrue(huge.toJson().length < TitleReport.MAX_JSON)
        val chat = report("a.mkv", chat = "c".repeat(10_000), includeChat = true)
        assertEquals(TitleReport.MAX_CHAT, chat.chatName?.length)
    }

    @Test
    fun `an empty name or an empty title is no report`() {
        assertNull(TitleReport.of("", "Night Train"))
        assertNull(TitleReport.of("   ", "Night Train"))
        assertNull(TitleReport.of("Night.Train.mkv", ""))
        assertNull(TitleReport.of("Night.Train.mkv", " \n "))
        // Nothing is left of a name that was only a number.
        assertNull(TitleReport.of("+91 98765 43210", "Night Train"))
    }

    @Test
    fun `the same pair twice has one hash, whatever the case or spacing`() {
        val a = report("Night.Train.2024.mkv", "Night Train")
        val b = report("Night.Train.2024.mkv", "Night Train")
        assertEquals(a.hash, b.hash)
        assertEquals(a, b)
        assertEquals(a.hash, report(" NIGHT.TRAIN.2024.MKV ", "night   train").hash)
        // The chat is no part of it: one pair from two chats is one report.
        assertEquals(a.hash, report("Night.Train.2024.mkv", "Night Train", "Some chat", includeChat = true).hash)
        assertEquals(16, a.hash.length)
    }

    @Test
    fun `another title or another name has another hash`() {
        val a = report("Night.Train.2024.mkv", "Night Train")
        assertNotEquals(a.hash, report("Night.Train.2024.mkv", "Night Train 2").hash)
        assertNotEquals(a.hash, report("Night.Train.2025.mkv", "Night Train").hash)
        // The name and the title do not run together: ("ab", "c") is not ("a", "bc").
        assertNotEquals(report("ab", "c").hash, report("a", "bc").hash)
    }

    @Test
    fun `the chat goes only when it is ticked`() {
        assertNull(report("a.mkv", chat = "Family videos").chatName)
        assertNull(report("a.mkv", chat = "Family videos", includeChat = false).chatName)
        assertEquals("Family videos", report("a.mkv", chat = "Family videos", includeChat = true).chatName)
        assertNull(report("a.mkv", chat = null, includeChat = true).chatName)
        assertNull(report("a.mkv", chat = "  ", includeChat = true).chatName)
        // A chat named after a person's number is scrubbed as well.
        assertEquals("@user", report("a.mkv", chat = "@someone", includeChat = true).chatName)
        assertFalse(report("a.mkv", chat = "Family videos").toJson().contains("Family"))
    }

    @Test
    fun `the json holds only the pair, the hash and the version`() {
        val json = JSONObject(report("Night.Train.mkv", "Night Train").toJson())
        assertEquals(setOf("v", "name", "title", "hash"), json.keySet())
        val ticked = JSONObject(report("Night.Train.mkv", "Night Train", "Family", includeChat = true).toJson())
        assertEquals(setOf("v", "name", "title", "chat", "hash"), ticked.keySet())
    }

    @Test
    fun `a report written out reads back the same`() {
        for (name in listOf("Night.Train.2024.mkv", "മിഥുനം.mp4", "Quote \" and \\ and \n.mkv")) {
            val original = report(name, "A \"title\"", "Chat", includeChat = true)
            assertEquals(original, TitleReport.parse(original.toJson()), name)
        }
    }

    @Test
    fun `reading a report scrubs it again and ignores the hash it carries`() {
        val sent = """{"v":1,"name":"Night Train +91 98765 43210 @someone.mkv","title":"Night Train","hash":"not-a-hash"}"""
        val read = assertNotNull(TitleReport.parse(sent))
        assertEquals("Night Train @user.mkv", read.fileName)
        assertEquals(16, read.hash.length)
    }

    @Test
    fun `garbage is no report`() {
        for (text in listOf(
            "", " ", "{", "[]", "null", "\"x\"", "{}", """{"name":"a"}""", """{"title":"a"}""",
            """{"name":1,"title":"a"}""", """{"name":"a","title":[]}""", """{"name":null,"title":"a"}""",
            """{"name":"","title":"a"}""", """{"name":"a","title":"  "}""", "x".repeat(10_000),
            """{"name":"a","title":"b",""",
        )) {
            assertNull(TitleReport.parse(text), "\"${text.take(40)}\"")
        }
        // A chat of the wrong type is no chat, and the report itself still reads.
        assertNull(TitleReport.parse("""{"name":"a","title":"b","chat":5}""")?.chatName)
    }
}
