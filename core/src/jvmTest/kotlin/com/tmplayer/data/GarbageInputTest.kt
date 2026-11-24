package com.tmplayer.data

import com.tmplayer.cast.AudioTrack
import com.tmplayer.cast.ByteRange
import com.tmplayer.cast.Codecs
import com.tmplayer.cast.Container
import com.tmplayer.cast.Ffmpeg
import com.tmplayer.cast.FfmpegProgress
import com.tmplayer.cast.MediaProbe
import com.tmplayer.cast.Mp4Probe
import com.tmplayer.cast.VideoTrack
import com.tmplayer.cast.WebVtt
import com.tmplayer.cast.dlna.Didl
import com.tmplayer.cast.dlna.DeviceDescription
import com.tmplayer.cast.dlna.DlnaFeatures
import com.tmplayer.cast.dlna.DlnaPlayback
import com.tmplayer.cast.dlna.SinkCaps
import com.tmplayer.cast.dlna.plan
import com.tmplayer.cast.dlna.Soap
import com.tmplayer.cast.dlna.ManualAddress
import com.tmplayer.cast.dlna.ManualTvStore
import com.tmplayer.cast.dlna.Ssdp
import com.tmplayer.cast.dlna.TransportActions
import com.tmplayer.cast.dlna.UpnpTime
import com.tmplayer.i18n.Decimal
import com.tmplayer.json.JSONObject
import com.tmplayer.online.AniListApi
import com.tmplayer.online.AnimeSkipApi
import com.tmplayer.online.ChapterSkips
import com.tmplayer.online.IdMapApi
import com.tmplayer.online.SkipModes
import com.tmplayer.online.SubMakerApi
import com.tmplayer.online.SubMakerSecret
import com.tmplayer.online.SubMakerTranslator
import com.tmplayer.online.SubMakerUrl
import com.tmplayer.online.SkipSession
import com.tmplayer.online.UserSkipMarks
import com.tmplayer.online.HttpResponse
import com.tmplayer.online.HttpTransport
import com.tmplayer.online.MetaExtras
import com.tmplayer.online.MetaInfo
import com.tmplayer.online.MsoneApi
import com.tmplayer.online.OmdbApi
import com.tmplayer.online.RateLimiter
import com.tmplayer.online.SkipSegment
import com.tmplayer.online.SkipTimesApi
import com.tmplayer.online.SubtitleHit
import com.tmplayer.online.SubtitleText
import com.tmplayer.online.TmdbApi
import com.tmplayer.online.TvMazeApi
import com.tmplayer.online.WikidataApi
import com.tmplayer.platform.IOException
import com.tmplayer.player.ChatSubtitles
import com.tmplayer.player.MkvChapters
import com.tmplayer.player.MkvClusters
import com.tmplayer.player.Mp4Chapters
import com.tmplayer.player.PlaybackTrace
import com.tmplayer.player.PlaybackTraceRing
import com.tmplayer.player.StutterVerdict
import com.tmplayer.player.TimeEntry
import com.tmplayer.platform.KeptLogLines
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every parser of outside data, fed garbage (HX10): text from Telegram, files on disk written by
 * an older or half finished run, and answers from the network. None of them may throw; each one
 * says "nothing here" in its own way (null, empty, a fallback, a failed reply) and the app goes on.
 *
 * The corpus is the shapes that broke parsers elsewhere (truncated JSON, the wrong type in a
 * field, nulls in lists, huge and negative numbers, timestamps that run backwards, lone
 * surrogates, very long and very deep input) plus seeded random text and bytes, so a failure
 * names an input that can be pasted into a test of its own.
 */
class GarbageInputTest {

    private val failures = mutableListOf<String>()

    private fun survive(parser: String, input: Any?, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            val shown = input.toString().take(120).replace("\n", "\\n")
            failures += "$parser on \"$shown\": ${e::class.simpleName}: ${e.message?.take(160)}"
        }
    }

    private fun assertNoFailures() =
        assertTrue(failures.isEmpty(), "Parsers threw on garbage:\n" + failures.distinct().take(40).joinToString("\n"))

    private val corpus: List<String> = listOf(
        // Episode and season ranges in names (T9 take): cut off, doubled, absurd and endless.
        "E01-", "E~~", "S01-S", "S99-S1", "E999999999-E1", "to to to", "S01E01 to", "Ep 1~", "Ep 1, 2, 3, ", "S01E01,,,02",
        "S01E01\u2013", "S01-S03-S05", "Seasons 99-100", "Seasons -", "S00-S00", "E1~2~3~4~5~6~7~8~9~10~11~12~13~14~15~16",
        "1-1-1-".repeat(300), "E01,".repeat(300), "S01E01 to E02 ".repeat(100), "\u0661\u0662-\u0663\u0664",
        "", " ", "\u0000", "﻿", "\uD800", "\uDFFF\uD800", "😀".repeat(50),
        "{", "}", "[", "]", "{}", "[]", "null", "true", "0", "-1", "1e999", "-1e999", "NaN", "\"\"", "\"",
        "{\"a\":", "{\"a\":}", "{\"a\" 1}", "[1,,2]", "{\"a\":1,}", "[null]", "[{}]", "[[]]",
        "{\"tag_name\":null}", "{\"tag_name\":\"v\"}", "{\"tag_name\":\"vX.Y.Z\",\"assets\":{}}",
        "{\"tag_name\":\"v1.2.3\",\"assets\":[null,1,\"x\",{},{\"name\":5,\"size\":\"big\",\"browser_download_url\":[]}]}",
        "[{\"tag_name\":1,\"prerelease\":\"yes\",\"draft\":null}]",
        "{\"version\":\"\",\"files\":null}", "{\"version\":[],\"assets\":\"\"}",
        "{\"results\":\"x\"}", "{\"results\":[1,null,{\"id\":\"x\",\"title\":{},\"release_date\":5}]}",
        "{\"id\":{},\"name\":[],\"first_air_date\":\"20\",\"poster_path\":7,\"vote_average\":\"high\"}",
        "{\"episodes\":[{\"episode_number\":\"one\",\"air_date\":null}]}", "{\"seasons\":5}",
        "{\"seasons\":[{\"season_number\":1,\"episode_count\":1e999}],\"episode_groups\":{\"results\":[null,{\"id\":5,\"type\":\"x\",\"group_count\":1e999,\"episode_count\":-1,\"name\":{}},{\"id\":\"\"}]}}",
        "{\"groups\":[{\"order\":\"x\",\"episodes\":[{\"season_number\":\"1\",\"episode_number\":1e20},null]},null,{\"episodes\":{}},{\"order\":-1,\"episodes\":[{\"season_number\":1,\"episode_number\":1}]}]}",
        "{\"data\":{\"Page\":{\"media\":[null,{\"id\":\"q\",\"title\":null}]}}}", "{\"data\":null}",
        "{\"Response\":\"False\",\"Error\":7}", "{\"Ratings\":[{\"Source\":null,\"Value\":\"%\"}]}",
        "{\"results\":{\"bindings\":[{\"x\":{}}]}}",
        "{\"version\":99,\"settings\":[],\"watched\":{\"a\":\"b\"},\"bookmarks\":[null,{}]}",
        "{\"format\":\"tmplayer-backup\",\"version\":-1}", "{\"format\":\"tmplayer-backup\",\"version\":1e20}",
        "{\"rows\":[{\"items\":[null,{\"chatId\":\"x\"}]}]}", "{\"grids\":{\"1\":[{}]}}",
        "{\"devices\":{}}", "{\"devices\":[{\"engine\":\"vlc\",\"chipsets\":[1e999,null,{},\"\"],\"sdkMin\":-1e999,\"sdkMax\":1e999}]}",
        "[{\"engine\":[],\"decoder\":{},\"models\":\"x\"}]", "[{\"engine\":\"vlc\",\"models\":[\"\",\"tx6\"],\"codecs\":[null],\"sdkMin\":24.5}]",
        "1:2:3", "-->", "a|b|c", "|", "||||", "1|", "|1", "9999999999999999999999", "-9223372036854775808",
        "9223372036854775807", "1.2.3.4.5", "v", "v1", "1.", ".1", "beta.", "1.2.3-beta.", "1.2.3-beta.99999999999",
        "00:00:01,000 --> 00:00:00,000\nbackwards\n",
        "1\n99:99:99,999 --> 00:00:01,000\nx\n\n2\n-1:00:00,000 --> 00:00:02,000\ny",
        "WEBVTT\n\n99:99:99.999 --> -1\n", "1\n99999999999999999999:00:01,000 --> 00:00:02,000\nx\n",
        "WEBVTT\n\n99999999999999999999:00:01.000 --> 00:00:02.000\nx\n",
        "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:01.ab,0:00:02.00,Default,,0,0,0,,x",
        "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:01.0x,99999999999999999999:00:02.00,Default,,0,0,0,,x","WEBVTT\n\n--> \n\n00:00.000 --> 00:00.001\n",
        "WEBVTT\n\nsheet.jpg#xywh=1,2,3\n", "WEBVTT\n\n00:00.000 --> 00:01.000\nsheet.jpg#xywh=99999999999,0,1,1\n",
        "columns=2147483647 frame_count=99", "columns=400000000 frame_count=2147483647 interval=1",
        "1 0 0\n99999999999999999999 1e30 -1e30\n","WEBVTT\n\n00:00.000 --> 00:01.000\nsheet.jpg#xywh=-5,-5,0,0\n",
        "[Script Info]\n[Events]\nFormat: Start\nDialogue: x",
        "[Events]\nFormat: Layer, Start, End, Text\nDialogue: 0,0:00:01.00,0:00:00.00\nDialogue: ,,,,,,,,,,",
        "https://t.me/", "tg://", "tg://resolve?domain=", "t.me/c/", "t.me/c/-1/-1", "t.me/c/1/99999999999999999999",
        "https://t.me/proxy?server=&port=x&secret=", "tg://socks?server=a&port=70000", "%%%", "%E0%A4%A",
        "S01E", "S99999E99999", "S01-S", "S99-S1", "E01-", "E~~", "to to to", "E999999999-E1", "tt", "tt0111161", "imdb.com/title/", "themoviedb.org/tv/",
        "Spanish (", "Hindi (Latin America", ".srt", "S01-S03 ".repeat(5_000), "1080p", "Show.S01E01-E", "Ep.0", "Part 0 of 0", "(", ")", "[[[", "((((((((((",
        "-", "--", "+-1", "1:", ":1", "::", "60:60:60", "-0", "1e3", "١٢٣",
        "\n".repeat(2_000), "a".repeat(100_000), "{\"a\":\"" + "x".repeat(50_000) + "\"}",
        "[".repeat(500) + "]".repeat(500), "{\"a\":".repeat(300) + "1" + "}".repeat(300),
    )

    private val random = Random(20261026)

    private fun randomText(): String {
        val alphabet = "{}[]\":,.-+0123456789eE|/\\\n\t aZ#=>xywh%_é😀\u0000\uD800"
        val length = random.nextInt(0, 200)
        return buildString(length) { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private val texts: List<String> = corpus + List(300) { randomText() }

    private val bytes: List<ByteArray> = listOf(
        ByteArray(0), ByteArray(1), ByteArray(8) { -1 }, ByteArray(16) { 0x7f },
        byteArrayOf(0, 0, 0, 8, 'c'.code.toByte(), 'h'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte()),
        byteArrayOf(0x1F, 0x43, 0xB6.toByte(), 0x75, 0x01, -1, -1, -1, -1, -1, -1, -1),
        byteArrayOf(-1, 0xD8.toByte(), -1, 0xC0.toByte(), 0, 0x11, 8, -1, -1, -1, -1),
        "﻿1\n00:00:01,000 --> 00:00:02,000\nx".encodeToByteArray(),
        byteArrayOf(-1, -2, 0, 0x31), byteArrayOf(-2, -1, 0x31, 0),
    ) + List(300) { ByteArray(random.nextInt(0, 400)).also(random::nextBytes) }

    /** What a renderer or a stranger on UDP 1900 and the LAN's HTTP ports may send (HX40). */
    private val dlnaCorpus: List<String> = listOf(
        "HTTP/1.1 200 OK\r\nLOCATION: \r\nUSN: \r\nST: \r\n\r\n", "NOTIFY * HTTP/1.1\r\nNTS: ssdp:alive\r\n\r\n",
        "NOTIFY * HTTP/1.1\r\nNTS: ssdp:byebye\r\nUSN: uuid:\r\nNT: x\r\n\r\n", "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=99999999999999999999\r\nLOCATION: x\r\nUSN: uuid:a\r\n\r\n",
        "HTTP/1.1 200 OK\n" + "X: y\n".repeat(5_000), "NOTIFY\r\n\r\n", "\r\n\r\n\r\n", "HTTP/1.1 200 OK\r\n:\r\n::\r\n",
        "<root/>", "<?xml version=\"1.0\"?><root><device/></root>", "<root><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType></device></root>",
        "<root><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><UDN>uuid:x</UDN><serviceList><service/></serviceList></device></root>",
        "<root><URLBase>http://[::1</URLBase><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><UDN>uuid:x</UDN><serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>http://a b/c</controlURL></service></serviceList></device></root>",
        "<root><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><UDN>uuid:x</UDN><serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>" + "../".repeat(2_000) + "</controlURL></service></serviceList></device></root>",
        "<!DOCTYPE r [<!ENTITY a \"b\">]><r>&a;</r>", "<?xml version=\"1.0\"?><!DOCTYPE x SYSTEM \"http://127.0.0.1/evil.dtd\"><x/>",
        "<a>".repeat(3_000) + "</a>".repeat(3_000), "<a>" + "x".repeat(300_000) + "</a>", "<a b=\"" + "&amp;".repeat(100_000) + "\"/>", "<a>&#0;&#xD800;&#99999999;</a>",
        "<s:Envelope xmlns:s=\"x\"><s:Body/></s:Envelope>", "<s:Envelope xmlns:s=\"x\"><s:Body><s:Fault><detail><UPnPError><errorCode>99999999999</errorCode></UPnPError></detail></s:Fault></s:Body></s:Envelope>",
        "<s:Envelope xmlns:s=\"x\"><s:Body><u:R xmlns:u=\"y\">" + "<V>1</V>".repeat(500) + "</u:R></s:Body></s:Envelope>",
        "<DIDL-Lite><item><res protocolInfo=\"a:b\">u</res><res/></item></DIDL-Lite>", "<DIDL-Lite><item/></DIDL-Lite>", "<DIDL-Lite/>",
        "http-get:*:video/mp4:DLNA.ORG_PN=", "http-get:*:", ":::::", "*:*:*:*", "http-get:*:;;;;:*,", ",".repeat(10_000), "http-get:*:video/mp4:*,".repeat(20_000),
        "http-get:*:video/mp4:DLNA.ORG_PN=" + "A".repeat(100_000),
        "0:00:00.", "0:00:00.99999999999999999999", "99999:59:59.999", "1:1:1:1", "+", "-", "0:0:0 F1/0", "0:00:01 F9999999999/1", "NOT_IMPLEMENTED",
        "Play,,,", "\\,\\,", "X_DLNA_PS=1/16\\,1/8\\", "PLAY,PAUSE".repeat(50_000),
        "PLAYING", "TRANSITIONING", "STOPPED", "ERROR_OCCURRED", "NO_MEDIA_PRESENT", "\u0000",
        // Typed or pasted into "Add a TV by its address".
        "192.168.1.20", "192.168.1.20:49152", "http://192.168.1.20:49152/description.xml", "http://8.8.8.8/description.xml", "http://[fe80::1%wlan0]:80/d.xml",
        "fe80::1", "tv.local", "http://user:pw@192.168.1.20/d.xml", "ftp://192.168.1.20/", "192.168.1.20:99999", "192.168.1.20:", "999.1.1.1", "1.2.3", "::", ":::",
        "http://192.168.1.20:80/" + "a/".repeat(5_000), "http://" + "9".repeat(5_000), "192.168.1.20 ", "http://192.168.1.20\r\n.evil/x", "0.0.0.0", "224.0.0.251", "127.0.0.1",
        "http://169.254.1.1/d.xml", "http://100.64.1.1:8080/d.xml", "HTTP://10.0.0.5/D.XML",
    )

    /** The work status line reads a failed call's error text for a flood wait (WorkStatus, WP-91). */
    @Test
    fun workStatusNeverThrows() {
        val now = 1_800_000_000_000L
        for (text in texts) {
            survive("WorkStatus", text) {
                // No garbage here names a flood wait, so none of it may become a wait.
                for (network in NetworkStatus.entries) {
                    val status = WorkStatus.ofFailure(text, now, keptAt = text.length.toLong() - 50, network = network)
                    check(status !is WorkStatus.RateLimited) { "a wait from garbage: $status" }
                    check(WorkStatusWords.line(status, now).isNotBlank())
                    check(WorkStatusWords.line(status, Long.MIN_VALUE).isNotBlank())
                }
                check(WorkStatus.RateLimited(text.length.toLong()).secondsLeft(Long.MIN_VALUE) >= 0)
            }
        }
        assertNoFailures()
    }

    /** The player's stored brightness (WP-27): a garbage number is the system's level, never a level of its own (PB2-18). */
    @Test
    fun storedBrightnessNeverBecomesALevel() {
        val sys = com.tmplayer.player.TouchPrefs.BRIGHTNESS_SYSTEM
        for (stored in listOf(4_294_967_346L, Long.MAX_VALUE, Long.MIN_VALUE, -50L, 1L, 101L, Int.MAX_VALUE.toLong() + 51)) {
            check(com.tmplayer.player.TouchPrefs.sanitiseBrightness(stored) == sys) { "$stored read as a level" }
        }
        for (text in texts) {
            survive("TouchPrefs.sanitiseBrightness", text) {
                val level = com.tmplayer.player.TouchPrefs.sanitiseBrightness(text.toLongOrNull())
                check(level == sys || level in com.tmplayer.player.TouchPrefs.BRIGHTNESS_MIN..100)
            }
        }
        assertNoFailures()
    }

    /** The sign in failure's sentence reads TDLib's and the native loader's error text (StartFailures). */
    @Test
    fun startFailuresNeverThrow() {
        for (text in texts) {
            survive("StartFailures", text) {
                StartFailures.kind(text)
                StartFailures.folder("Can't create directory \"$text\"")
                check(StartFailures.describeRejected(text).isNotBlank())
                check(StartFailures.describe(UnsatisfiedLinkError(text)).isNotBlank())
            }
        }
        assertNoFailures()
    }

    /** The one-time hint registry's stored string (WP-68): a device key, cut short or written by anything. */
    @Test
    fun hintRegistryNeverThrows() {
        for (text in texts) {
            survive("HintRegistry", text) {
                val state = HintRegistry.parse(text, setOf(HintIds.PLAYER))
                check(state.counts.size <= HintRegistry.MAX_IDS)
                check(HintRegistry.parse(HintRegistry.encode(HintRegistry.bump(state, text))).counts.size <= HintRegistry.MAX_IDS)
                HintRegistry.raise(state, text, Int.MAX_VALUE)
            }
        }
        assertNoFailures()
    }

    /** iOS's kept log reads TDLib's own log file back (KeptLog): cut short, or written by anything. */
    @Test
    fun keptLogLinesNeverThrow() {
        val stamp: (Long) -> String = { it.toString() }
        for (text in texts) {
            survive("KeptLogLines.fromTdlib", text) {
                KeptLogLines.fromTdlib(text.lines(), stamp)
                KeptLogLines.fromTdlib(listOf("[ 1][t 1][$text][A.cpp:1] $text", "[ $text][t 1][1.5][A] m", "[ 1][t 1][1.$text][A] m"), stamp)
                KeptLogLines.fromTdlib(listOf("[ 1][t 1][99999999999999999.999999999][A] $text"), stamp)
            }
            survive("KeptLogLines.merge", text) { KeptLogLines.merge(text.lines(), listOf(text), 5) }
        }
        assertNoFailures()
    }

    /** The plays counted for stutter rates (PlayCounts): a private file, cut short or written by anything. */
    @Test
    fun playCountsNeverThrow() {
        val dir = java.nio.file.Files.createTempDirectory("plays-garbage").toFile()
        for (text in texts) {
            survive("PlayCounts", text) {
                val file = dir.resolve("plays.json")
                file.writeText(text)
                val counts = PlayCounts(file) { 3L * 24 * 60 * 60 * 1000 }
                counts.week(text)
                counts.note(PlayCounts.keyOf(text, text, text))
                file.writeText("{\"days\":[{\"day\":3,\"counts\":{\"${text.take(20).replace("\"", "")}\":$text}}]}")
                counts.week("k")
            }
        }
        assertNoFailures()
    }

    /** The stored downloads location and a record's path (HX57): a path or a picked folder's content:// string. */
    @Test
    fun storedLocationNeverThrows() {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3AMovies"
        val extra = listOf(
            tree, "$tree/document/x", "$tree/document/", "content://", "content://com.android.externalstorage.documents/tree/",
            "content://com.android.externalstorage.documents/tree/" + "%".repeat(50_000), "content:", "CONTENT://X", "/".repeat(10_000),
        )
        for (text in texts + extra) {
            survive("StoredLocation.kindOf", text) { StoredLocation.kindOf(text) }
            survive("StoredLocation.isDocument", text) { StoredLocation.isDocument(text) }
            survive("StoredLocation.documentIsIn", text) {
                StoredLocation.documentIsIn(text, text)
                StoredLocation.documentIsIn(tree, text)
                StoredLocation.documentIsIn(text, "$tree/document/x")
            }
        }
        assertNoFailures()
    }

    /** What an ffmpeg the user has may print (C4): its encoder list and its -progress lines. */
    @Test
    fun ffmpegOutputNeverThrows() {
        val extra = listOf(
            "------\n V....D", "------\n VVVVVV " + "x".repeat(10_000), "------\n" + " A....D aac AAC\n".repeat(20_000),
            "out_time_us=-1", "out_time_us=99999999999999999999", "out_time_ms=" + "9".repeat(5_000), "speed=-1x", "speed=NaNx",
            "speed=Infinityx", "progress=", "=", "==", "progress=end\nprogress=end", "frame=\u0000",
        )
        for (text in texts + extra) {
            survive("Ffmpeg.encoders", text) { Ffmpeg.ability(Ffmpeg.encoders(text)) }
            survive("FfmpegProgress.feed", text) {
                val p = FfmpegProgress()
                text.lines().forEach { p.feed(it) }
                p.lastError
            }
        }
        assertNoFailures()
    }

    @Test
    fun dlnaParsersNeverThrow() {
        for (text in texts + dlnaCorpus) {
            survive("Ssdp.parse", text) { Ssdp.parse(text.toByteArray()) }
            survive("Ssdp.udnOf", text) { Ssdp.udnOf(text); Ssdp.maxAge(text) }
            survive("DeviceDescription.parse", text) { DeviceDescription.parse(text, "http://192.168.1.2:80/d.xml") }
            survive("DeviceDescription.parse location", text) { DeviceDescription.parse("<root/>", text) }
            survive("Ssdp.parse NT", text) {
                Ssdp.parse("NOTIFY * HTTP/1.1\r\nNTS: ssdp:alive\r\nNT: $text\r\nUSN: uuid:x::$text\r\nLOCATION: http://192.168.1.2/d.xml\r\nCACHE-CONTROL: $text\r\n\r\n".toByteArray())?.mayBeRenderer
            }
            survive("ManualTvStore.parse", text) { ManualTvStore.parse(text); ManualTvStore.parse(text.lines().joinToString("\n") { "http://192.168.1.2/d.xml\t$it\t$it" }) }
            survive("ManualAddress.parse", text) {
                for (loopback in listOf(false, true)) {
                    (ManualAddress.parse(text, loopback) as? ManualAddress.Parsed.Target)?.let { ManualAddress.candidates(it) }
                }
                ManualAddress.locationOn("192.168.1.2", text)
                ManualAddress.savedTarget(text)
            }
            survive("Soap.parse", text) { Soap.parse(200, text); Soap.parse(500, text); Soap.parse(0, text) }
            survive("Soap.envelope", text) { Soap.parse(200, Soap.envelope("urn:x", "Act", listOf("Arg" to text))) }
            survive("Didl.parse", text) { Didl.parse(text) }
            survive("Didl.fallbacks", text) { Didl.fallbacks(text); Didl.fallbacks(Didl.item(text, text, text, DlnaFeatures())) }
            survive("Xml.decode", text) { com.tmplayer.cast.dlna.Xml.decode(text.toByteArray(), text); com.tmplayer.cast.dlna.Xml.decode(text.toByteArray(Charsets.UTF_16LE), null) }
            survive("Didl.item", text) { Didl.parse(Didl.item(text, text, text, DlnaFeatures(profile = text), subtitleUrl = text, durationMs = text.length.toLong())) }
            // The stored subtitle look with its fine values (WP-29): names, wrong-typed or huge numbers, any text.
            survive("SubtitleStyle.from", text) {
                val n = text.toLongOrNull()
                val style = com.tmplayer.player.SubtitleStyle.from(text, text.isEmpty(), text, text, text, text, text, text.length > 3, n, n, n)
                style.summary()
                style.mpvProperties(text)
                style.stepSize(1)
                style.stepPosition(-1)
                style.fraction
                style.mpvScale
                com.tmplayer.player.SyncDelays.decode(text)
            }
            // One video's own look (WP-29 "This video only"): any text reads as a look or as nothing.
            survive("SubtitleStyle.decode", text) {
                com.tmplayer.player.SubtitleStyle.decode(text)?.summary()
                com.tmplayer.player.SubtitleStyle.decode("size=$text;pp=$text;sp=$text;cc=$text;bold=$text")?.mpvProperties(text)
            }
            survive("SinkCaps.parse", text) { SinkCaps.parse(text) }
            survive("SinkCaps.plan", text) {
                val probe = MediaProbe(Container.Avi, 60_000, VideoTrack(1, Codecs.H264, 1920, 1080), listOf(AudioTrack(2, Codecs.AAC, channels = 2)))
                SinkCaps.parse(text).plan(probe, transcoder = Ffmpeg.ability(setOf("libx264", "aac")))
            }
            survive("UpnpTime.parseMs", text) { UpnpTime.parseMs(text) }
            survive("TransportActions.parse", text) { TransportActions.parse(text) }
            survive("DlnaPlayback.update", text) {
                val p = DlnaPlayback(UpnpTime.parseMs(text) ?: 0)
                p.loaded(0, 0)
                p.update(text, text, UpnpTime.parseMs(text), UpnpTime.parseMs(text), 1_000)
                p.update("PLAYING", text, UpnpTime.parseMs(text), UpnpTime.parseMs(text), 2_000)
                p.seeking(UpnpTime.parseMs(text) ?: Long.MAX_VALUE, 2_500)
                p.update("STOPPED", text, UpnpTime.parseMs(text), null, 3_000)
            }
        }
        for (b in bytes) survive("Ssdp.parse bytes", b.toList()) { Ssdp.parse(b) }
        for (b in bytes) survive("Xml.decode bytes", b.toList()) { com.tmplayer.cast.dlna.Xml.decode(b, "text/xml; charset=nonsense") }
        assertNoFailures()
    }

    @Test
    fun albumPostsNeverThrow() {
        fun video(message: Long, album: Long, name: String, caption: String = "") = MediaItem(
            chatId = 1, messageId = message, fileId = 1, title = name, sizeBytes = 1, durationSec = 1, mimeType = "video/mp4",
            thumbnailFileId = 5, miniThumbnail = null, date = 1, fileName = name, caption = caption, albumId = album,
        )
        val long = "x".repeat(4_096)
        val captions = corpus + texts + long + MediaText.captionOf(long) + listOf("\n".repeat(5_000), "😀".repeat(3_000))
        for (text in captions) {
            // An album of ten photos and no video, photos with no picture size (file id 0), and a huge caption.
            val ten = (1L..10L).map { AlbumPhoto(1, 7, it, if (it % 2 == 0L) 0 else it.toInt(), null, if (it == 1L) text else "") }
            survive("AlbumPosts.fold ten photos no video", text) {
                AlbumPosts.fold(listOf(video(50, 9, "other.mkv")), AlbumPosts.pick(ten))
            }
            survive("AlbumPosts.fold caption", text) {
                val out = AlbumPosts.fold(listOf(video(11, 7, "video.mp4"), video(12, 7, text, text)), AlbumPosts.pick(ten))
                out.forEach { it.searchText; it.parsedName }
            }
            survive("AlbumPosts.pick albumless and negative", text) {
                AlbumPosts.pick(listOf(AlbumPhoto(0, 0, -1, -5, byteArrayOf(), text), AlbumPhoto(1, -3, Long.MAX_VALUE, Int.MIN_VALUE, null, text)))
            }
            survive("HomeCache.decode album keys", text) {
                HomeCache.decode(text)
                HomeCache.itemOf(org.json.JSONObject("{\"c\":1,\"m\":2,\"f\":3,\"aph\":" + (text.hashCode()) + ",\"cov\":\"x\"}"))
            }
        }
        assertNoFailures()
    }

    @Test
    fun formatFactsNeverThrow() {
        for (text in texts + "a".repeat(2_048)) {
            survive("FormatFacts.tags", text) {
                val track = FormatFacts.AudioTrack(mime = text, codecs = text, profile = text, channels = text.length - 3)
                val probe = FormatFacts.Probe(
                    videoMime = text, videoCodecs = text, width = text.length * 977, height = -text.length, frameRate = text.length * 31f,
                    bitDepth = text.length, audio = listOf(track, track), hdr10Plus = text.isNotEmpty(),
                )
                FormatFacts.tags(probe, FormatFacts.resolutionOf(text.length, text.length, text))
                FormatFacts.resolutionOf(0, 0, text)
                FormatFacts.dolbyVisionProfile(text)
                FormatProbes.record(text, probe)
                FormatProbes[text]
            }
        }
        FormatProbes.clear()
        assertNoFailures()
    }

    @Test
    fun formatLineNeverThrows() {
        for (text in texts + "a".repeat(2_048)) {
            survive("FormatLine.line", text) {
                val facts = com.tmplayer.player.FormatLine.Facts(
                    videoMime = text, videoCodecs = text, width = text.length, height = -text.length, frameRate = text.length.toFloat(),
                    audioMime = text, audioCodecs = text, channels = text.length, containerMime = text,
                )
                com.tmplayer.player.FormatLine.copyBlock(com.tmplayer.player.FormatLine.line(facts, listOf(text)), listOf(com.tmplayer.player.FormatLine.Decoders(text, listOf(text))))
            }
            survive("FileDatacenter.of", text) { com.tmplayer.player.FileDatacenter.of(text) }
        }
        assertNoFailures()
    }

    @Test
    fun textParsersNeverThrow() {
        for (text in texts) {
            survive("UpdateFeed.parseFeed", text) { UpdateFeed.parseFeed(text) }
            // The ordered language lists (WP-80): a stored value with garbage entries, absurd length or none at all.
            survive("TrackPreferences.from ordered lists", text) {
                val prefs = com.tmplayer.player.TrackPreferences.from(text, text + ",en,," + text, text)
                prefs.audioLanguages
                prefs.subtitleLanguages
                prefs.audioStored
                prefs.subtitleStored
                com.tmplayer.player.LanguageOrder.parse(text.repeat(40))
                com.tmplayer.player.LanguageOrder.move(com.tmplayer.player.LanguageOrder.parse(text), text, text.length - 3)
                com.tmplayer.player.LanguageOrder.add(emptyList(), text)
            }
            survive("ChatSearch.spellings", text) {
                ChatSearch.spellings(text)
                ChatSearch.matches(text, text)
                ChatSearch.matches("Resident.Evil.2021.mkv", text)
            }
            survive("KnownDevices.parse", text) {
                val list = KnownDevices.parse(text)
                val device = DeviceFacts(hardware = text, model = text, sdk = text.length)
                KnownDevices.find(KnownDevices.effective(list), device, text)
                KnownDevices.plan(com.tmplayer.player.RemedyPlan(), KnownDevices.remediesFor(KnownDevices.effective(list), device, text))
                KnownDevices.startOf(text)
            }
            // The feed's remedy fields, each given text of every shape: bad ones are skipped.
            survive("KnownDevices.parse remedy fields", text) {
                val entry = com.tmplayer.json.JSONObject(mapOf("id" to text, "chipsets" to listOf(text, "abc"), "async" to text, "excludeDecoders" to listOf(text), "startRung" to text, "note" to text))
                KnownDevices.parse("[$entry]")
            }
            survive("AssMargins.bottomOverrides", text) {
                com.tmplayer.player.AssMargins.bottomOverrides(text)
                com.tmplayer.player.AssMargins.bottomOverrides("[Script Info]\nPlayResY: $text\n[V4+ Styles]\nFormat: Name, Alignment, MarginV\nStyle: $text\nStyle: A,2,$text")
            }
            survive("EngineChoice.from", text) { com.tmplayer.player.EngineChoice.from(text) }
            survive("PlayerChoice.parse", text) { com.tmplayer.player.PlayerChoice.parse(text) }
            survive("TunneledMode.from", text) { com.tmplayer.player.TunneledMode.from(text) }
            survive("ExternalExtras.forApp", text) { com.tmplayer.player.ExternalExtras.forApp(text, text, text.length.toLong() - 5, text) }
            survive("EngineHistory.decode", text) { com.tmplayer.player.EngineHistory.decode(text) }
            survive("EngineMark.decode", text) { com.tmplayer.player.EngineMark.decode(text) }
            survive("VlcCrashes.decode", text) { com.tmplayer.player.VlcCrashes.decode(text) }
            survive("UpdateFeed.parseGitHub", text) { UpdateFeed.parseGitHub(text) }
            survive("UpdateFeed.parseGitHubList", text) { UpdateFeed.parseGitHubList(text) }
            survive("UpdateFeed.keyFor", text) { UpdateFeed.keyFor(text) }
            survive("UpdateFeed.compare", text) { UpdateFeed.compare(text, "1.28.1"); UpdateFeed.isNewer("1.28.1", text) }
            survive("UpdateFeed.leadingList", text) { UpdateFeed.leadingList(text); UpdateFeed.sentences(text) }
            survive("ReleaseVersion.parse", text) { ReleaseVersion.parse(text) }
            survive("AutoInstall.Tried.decode", text) { AutoInstall.Tried.decode(text) }
            for ((w, h, iw, ih, d) in listOf(listOf(160, 90, 1600, 900, 600_000L), listOf(0, 0, 0, 0, 0L), listOf(-1, -1, -1, -1, -1L))) {
                survive("StoryboardMaps.parse($w,$h,$iw,$ih,$d)", text) {
                    val map = StoryboardMaps.parse(text, w.toInt(), h.toInt(), iw.toInt(), ih.toInt(), d.toLong())
                    for (at in listOf(-1L, 0L, 1L, 59_999L, 600_000L, Long.MAX_VALUE)) map.tileAt(at)
                }
            }
            survive("Backups.decode", text) { Backups.decode(text) }
            survive("VideoCorrection.decode", text) {
                VideoCorrection.decode(text).encode()
                VideoCorrection.decode("tmdb=film:$text").encode()
                VideoCorrection.decode("wrong\n$text\ntmdb=show:12").pick
                VideoCorrections.idOf(text)
            }
            survive("WebVtt.parseSrt", text) { WebVtt.parseSrt(text) }
            survive("WebVtt.parseAss", text) { WebVtt.parseAss(text) }
            survive("WebVtt.parseVtt", text) { WebVtt.parseVtt(text) }
            survive("KeyValueCodec.decode", text) { KeyValueCodec.decode(text) }
            survive("HomeCache.decode", text) { HomeCache.decode(text) }
            survive("ChatGridDisk.decode", text) { ChatGridDisk.decode(text) }
            survive("ChatSnapshot.decode", text) { ChatSnapshot.decode(text) }
            // HX88: the profile list, whole, as one profile entry, and with a PIN line that is garbage.
            survive("AccountCodec.decode", text) {
                AccountCodec.decode(text)?.let { list -> AccountRules.recovered(list); AccountCodec.encode(list) }
                AccountCodec.decode("{\"v\":1,\"active\":\"main\",\"profiles\":[{\"id\":\"main\",\"folder\":null,\"name\":" + org.json.JSONObject.quote(text) + "},$text]}")
                AccountCodec.decode("{\"v\":1,\"profiles\":[{\"id\":\"p1\",\"folder\":\"p1\",\"pin\":" + org.json.JSONObject.quote(text) + "}]}")
                    ?.profiles?.firstOrNull()?.let { ProfilePin.check(it, "1234", 0L) }
            }
            survive("ResumeSyncDoc.decode", text) { ResumeSyncDoc.decode(text) }
            // HX87: the stored reason a sync channel is not used, and Telegram's words for a refused post.
            survive("SyncChannelBlock.decode", text) {
                for (line in listOf(text, "members:$text", "posting:$text", "$text:5")) SyncChannelBlock.decode(line)?.let { it.encode(); it.words() }
            }
            survive("SyncChannelCheck.refusedPosting", text) { SyncChannelCheck.refusedPosting(text) }
            survive("PlaybackTrace.read", text) {
                for (session in PlaybackTrace.read(text)) StutterVerdict.analyse(session.samples)
                PlaybackTrace.read(text + "\n" + PlaybackTrace.sampleLine(com.tmplayer.player.TraceSample(atMs = 1)))
                PlaybackTraceRing.from(text).sessions()
                // Version 2's lines (facts, hops) with the text in every place a value goes.
                val k = com.tmplayer.json.JSONObject().put("dec.name", text).put("class", text).put("rung", 5)
                val v2 = PlaybackTrace.sessionLine(1) + "\n" +
                    com.tmplayer.json.JSONObject().put("t", "f").put("at", 2).put("k", k) + "\n" +
                    com.tmplayer.json.JSONObject().put("t", "f").put("at", 2).put("k", text) + "\n" +
                    com.tmplayer.json.JSONObject().put("t", "h").put("at", 3).put("ev", text).put("to", text) + "\n" + text
                for (session in PlaybackTrace.read(v2)) com.tmplayer.player.TraceFacts.lines(session.facts, session.hops)
                PlaybackTraceRing.from(v2).sessions().let { PlaybackTrace.encode(it) }
            }
            survive("LogUpload.outcome", text) {
                LogUpload.parseCode(text)
                LogUpload.outcome(201, text, text)
                LogUpload.outcome(429, text, text)
                LogUpload.outcome(-1, text)
            }
            survive("LogBundle.build", text) {
                LogBundle.build(
                    Feedback.Facts(text, Feedback.Platform.Phone, text, text),
                    LogBundle.Parts(crash = listOf(text), sessions = listOf(listOf(text)), appLog = text.lines()),
                    known = listOf(text), maxBytes = 5_000,
                )
            }
            survive("SyncDocuments.decode", text) {
                SyncDocuments.decode(text.encodeToByteArray(), account = 5)
                SyncDocuments.decode("{\"format\":\"tmplayer-sync\",\"version\":1,\"watched\":[$text],\"removed\":[$text]}".encodeToByteArray(), 0)
            }
            survive("ResumeState.decode", text) { ResumeState.decode(text) }
            survive("SyncStatus.decode", text) { SyncStatus.decode(text); SyncStatus.decode("done\u001F$text\u001F$text\u001F$text\u001F$text") }
            survive("ResumeRecord.decode", text) {
                ResumeRecord.decode("1_2", text, 0, 0)
                ResumeRecord.decode(text, text, -1, -1)
            }
            survive("DecodeMemory.decode", text) { DecodeMemory.decode(text) }
            survive("RemedyMemory.decode", text) {
                val memory = RemedyMemory.decode(text, 20_400)
                val key = RemedyClassKey.of(DeviceFacts(hardware = text, sdk = text.length), DecodeJudge.Picture(text), text, text, text)
                memory.planFor(key, text)
                val written = memory.failed(text, com.tmplayer.player.Remedy.Reload, text, text, -1).crashed(key, 20_400)
                RemedyMemory.decode(written.encode(), 20_400).planFor(text, text)
                RemedyMemory.decode("$text=$text|$text|$text|$text|$text|20400|$text~$text~$text", 20_400).encode()
            }
            survive("DownloadRequest.decodeAll", text) { DownloadRequest.decodeAll(text) }
            survive("RecentSearches.decode", text) { RecentSearches.decode(text) }
            survive("AddedChats.hiddenIds", text) {
                val hidden = AddedChats.hiddenIds(text)
                AddedChats.withoutHidden(AddedChoice(AddedChats.modeOf(text)), hidden, AddedChats.decode(text))
            }
            survive("AddedChats.decode", text) {
                AddedChats.decode(text)
                AddedChats.modeOf(text)
                AddedChoice(AddedChats.modeOf(text), AddedChats.decode(text)).ids
            }
            survive("HomeLayout.of", text) {
                HomeLayout.of(null, false, null, true)
                HomeLayout.of(null, null, null, null, text)
            }
            survive("HomeOrder.parse", text) {
                val order = HomeOrder.parse(text)
                HomeOrder.arrange(listOf("continue", "recent", "chat-1"), order)
                HomeOrder.encode(order)
            }
            survive("PieceAlerts.decode", text) { PieceAlerts.decode(text) }
            survive("SupportReminder.decodeRecent", text) { SupportReminder.decodeRecent(text) }
            survive("BookmarkCodec.decode", text) { BookmarkCodec.decode(1, 2, text) }
            survive("QualityHabit.decode", text) { QualityHabit.decode(text) }
            survive("KeepNextShows.decode", text) { check(KeepNextShows.decode(text).size <= KeepNextShows.MAX_SHOWS) }
            survive("LessonSteps.read", text) { LessonSteps.read(text) }
            survive("TimeEntry.parse", text) { TimeEntry.parse(text, 3_600_000, 0); TimeEntry.parse(text, 0, -5) }
            survive("ProxyLinks.parse", text) { ProxyLinks.parse(text) }
            survive("TelegramLinks.parse", text) { TelegramLinks.parse(text) }
            survive("GlobalSearch.parse", text) { GlobalSearch.parse(text) }
            // An old recent search holding an @username is an ordinary query (HX62): read, cleaned and
            // ranked, with no lookup behind it; copies named by garbage still order.
            survive("GlobalSearch username query", text) {
                GlobalSearch.parse("@$text"); GlobalSearch.clean("@$text"); GlobalSearch.searchable("@$text")
            }
            survive("GlobalSearch.copyOrder language", text) {
                fun copy(id: Long, name: String) = MediaItem(
                    chatId = 1, messageId = id, fileId = id.toInt(), title = name, sizeBytes = 1, durationSec = 1, mimeType = "video/mp4",
                    thumbnailFileId = 0, miniThumbnail = null, date = 1, fileName = name,
                )
                val copies = listOf(copy(1, text), copy(2, "$text Hindi"), copy(3, "Tamil $text"))
                GlobalSearch.copyOrder(copies, "hi"); GlobalSearch.copyOrder(copies, text); GlobalSearch.tally(emptyList())
            }
            // HX41: TMDB titles holding garbage, against copies named by it, never throw.
            survive("GlobalSearch.titles", text) {
                fun copy(id: Long, name: String) = MediaItem(
                    chatId = id, messageId = id, fileId = id.toInt(), title = name, sizeBytes = 1, durationSec = 1, mimeType = "video/mp4",
                    thumbnailFileId = 0, miniThumbnail = null, date = 1, fileName = name,
                )
                val query = GlobalSearch.parse("night $text")
                val groups = GlobalSearch.merge(emptyList(), listOf(copy(1, text), copy(2, "$text S01E02"), copy(3, "$text 2019")), query, SearchSort.BestMatch, SearchIdentity.Default)
                val choices = listOf(
                    com.tmplayer.online.PosterChoice(1, false, text, 2019, text, text, text, text),
                    com.tmplayer.online.PosterChoice(2, true, "night $text", null, "", "", null),
                    com.tmplayer.online.PosterChoice(-1, true, text, Int.MIN_VALUE, "", "", null),
                )
                GlobalSearch.titles(choices, groups, query, { text }, { listOf(text) })
                GlobalSearch.titleQuery(query)
            }
            survive("ByteRange.parse", text) { ByteRange.parse(text, 1_000); ByteRange.parse("bytes=$text", 0) }
            survive("PostStart.parse", text) { PostStart.parse(text) }
            survive("Decimal.parse", text) { Decimal.parse(text) }
            survive("MediaName.parse", text) { MediaName.parse(text); MediaName.parse(text, text) }
            survive("MediaName.spellingKey", text) { MediaName.spellingKey(text) }
            survive("SubtitlePicks.load", text) {
                val f = java.io.File.createTempFile("picks", ".json")
                try {
                    f.writeText(text)
                    com.tmplayer.online.SubtitlePicks(f).recall(listOf("a", "msg:1:1"))
                } finally {
                    f.delete()
                }
            }
            survive("QualityRead", text) {
                val item = com.tmplayer.data.MediaItem(
                    chatId = 1, messageId = 1, fileId = 1, title = text, sizeBytes = 1, durationSec = 1, mimeType = text,
                    thumbnailFileId = 0, miniThumbnail = null, date = 1, fileName = text, width = text.length, height = -text.length,
                )
                QualityRead.of(item); QualityRead.named(item); QualityRead.mismatch(item); QualityRead.lowGrade(item)
                QualityRead.rank(text); VideoCopies.heightOf(item); VideoCopies.versionCount(item.copy(versions = listOf(item, item)))
            }
            survive("MediaName.languageHints", text) { MediaName.languageHints(text); com.tmplayer.online.ChatLanguageHints.note(1L, text) }
            survive("MediaName.pack", text) { MediaName.parse(text).let { it.pack; it.packSeason } }
            survive("MetaLinks.read", text) { com.tmplayer.online.MetaLinks.read(text, text, null) }
            survive("MetaQuery.of link", text) {
                com.tmplayer.online.MetaQuery.of(text, "imdb.com/title/tt0111161 $text")
                com.tmplayer.online.MetaQuery.of("x.mkv", "tt$text themoviedb.org/tv/$text")
            }
            survive("ChatSubtitles.tagsOf", text) { com.tmplayer.player.ChatSubtitles.tagsOf(text); com.tmplayer.player.ChatSubtitles.tagsOf("$text.Hindi ($text).srt") }
            survive("TitleShape.of", text) { TitleShape.of(text) }
            survive("TitleReport.parse", text) { TitleReport.parse(text) }
            survive("TitleLedger.decode", text) { TitleLedger.decode(text).let { TitleLedger.decode(it.encode()) } }
            survive("TitleUpload.outcome", text) {
                for (status in listOf(-1, 0, 200, 201, 429, 500)) TitleUpload.outcome(status, text, text)
                LogUpload.titlesUrl(text)
            }
            survive("FeedbackReport.parse", text) { FeedbackReport.parse(text)?.let { FeedbackReport.parse(it.toJson()) } }
            survive("FeedbackDraft.decode", text) { FeedbackDraft.decode(text)?.let { FeedbackDraft.decode(it.encode()) } }
            survive("FeedbackUpload.outcome", text) {
                for (status in listOf(-1, 0, 200, 201, 429, 500)) FeedbackUpload.outcome(status, text, text)
                LogUpload.feedbackUrl(text)
            }
            survive("FeedbackReport.of", text) {
                val facts = Feedback.Facts(text, Feedback.Platform.Other, text, text)
                FeedbackReport.of("0".repeat(32), FeedbackReport.Kind.Problem, text, text, text, facts)?.let { FeedbackReport.parse(it.toJson()) }
                FeedbackReport.problem(text, text)
            }
            survive("TitleReport.of", text) {
                TitleReport.of(text, text, text, includeChat = true)?.let { TitleReport.parse(it.toJson()) }
                TitleReport.scrub(text)
            }
            survive("MediaText.overviewOf", text) {
                MediaText.overviewOf(text, text, text)
                MediaText.overviewOf(text, null, "")
                MediaText.overviewOf("File name: $text\n$text", "$text.mkv", text)
            }
            survive("VideoCopies.keyOfTitle", text) { VideoCopies.keyOfTitle(text); VideoCopies.languagesIn(text) }
            survive("StaleEntries.classify", text) {
                for (code in listOf(Int.MIN_VALUE, -1, 0, 400, 404, 420, 500, Int.MAX_VALUE)) {
                    // Only a plain answer is gone: random text with an ordinary code never is.
                    val lookup = StaleEntries.classify(code, text)
                    check(code == 404 || lookup is MessageLookup.Unreachable || "NOT" in text.uppercase() || "INVALID" in text.uppercase() ||
                        "PRIVATE" in text.uppercase() || "FORBIDDEN" in text.uppercase() || "ACCESS" in text.uppercase() || "BANNED" in text.uppercase())
                }
            }
            survive("SessionCheck.isAuthError", text) {
                for (code in listOf(Int.MIN_VALUE, -1, 0, 400, 420, 500, Int.MAX_VALUE)) {
                    // Random text with an ordinary code never reads as signed out unless it names a refusal.
                    val signedOut = SessionCheck.isAuthError(code, text)
                    check(!signedOut || text.trimStart().startsWith("Unauthorized", ignoreCase = true) || "AUTH_KEY" in text || "SESSION_" in text || "USER_DEACTIVATED" in text)
                }
                SessionCheck.isAuthError(401, text)
            }
            survive("ChatSubtitles.tagsOf and rank", text) {
                ChatSubtitles.tagsOf(text)
                ChatSubtitles.languageCode(text)
                ChatSubtitles.rank(
                    ChatSubtitles.Video(messageId = 1L shl 20, fileName = text, caption = text),
                    listOf(ChatSubtitles.Doc(2L shl 20, 1, text + ".srt", 100), ChatSubtitles.Doc(Long.MAX_VALUE, 2, text, Long.MAX_VALUE), ChatSubtitles.Doc(-5, 3, text, -1)),
                    listOf(text),
                )
            }
            survive("SubMakerApi.parse", text) {
                val base = "https://addon.test/tok"
                SubMakerApi.parseList(text, base)
                SubMakerApi.parseList("{\"subtitles\":[{\"id\":$text,\"lang\":$text,\"label\":$text,\"url\":$text}]}", base)
                SubMakerApi.parseList("{\"subtitles\":[{\"id\":\"a\",\"lang\":\"Make $text\",\"url\":\"$base/translate/x\"}]}", base)
                SubMakerApi.parseManifest(text)
                SubMakerApi.cueCount(text)
                SubMakerApi.isPlaceholder(text)
                SubMakerApi.lastCueEndMs(text)
                SubMakerUrl.normalize(text)
                SubMakerSecret.scrub(text)
            }
            survive("ChatSubtitleText.read (text as a file)", text) {
                runBlocking {
                    for (name in listOf("a.srt", "a.ass", "a.vtt", "a.sub", "a.zip", text)) ChatSubtitleText.read(text.toByteArray(), name, 1, 2, text)
                }
            }
            survive("MsoneApi.parse", text) {
                MsoneApi.parseHealth(text, 0)
                MsoneApi.parseHits(text)
                MsoneApi.parseHits("{\"hits\":[{\"id\":$text,\"title\":$text,\"year\":$text,\"translator\":$text,\"downloadUrl\":$text}]}")
            }
        }
        assertNoFailures()
    }

    @Test
    fun binaryParsersNeverThrow() {
        for (data in bytes) {
            val shown = data.take(24).joinToString(",")
            survive("Mp4Chapters.parseChpl", shown) { Mp4Chapters.parseChpl(data) }
            survive("SyncDocuments.decode bytes", shown) { SyncDocuments.decode(data, account = 5); SyncDocuments.decode(byteArrayOf(0x1f, 0x8b.toByte()) + data, 5) }
            survive("MkvChapters.parseElement", shown) { MkvChapters.parseElement(data) }
            survive("MkvClusters.find", shown) {
                MkvClusters.find(data, data.size, atEnd = false)
                MkvClusters.find(data, data.size, atEnd = true, bytesLeft = data.size / 2L)
                MkvClusters.timecodeScale(data)
            }
            survive("Mp4Probe.parseMoov", shown) { Mp4Probe.parseMoov(data) }
            survive("JpegSize.read", shown) { JpegSize.read(data) }
            survive("SubtitleText.decode", shown) { SubtitleText.decode(data); SubtitleText.decode(data, "ar") }
            survive("ChatSubtitleText.read (bytes as a file)", shown) {
                runBlocking { for (name in listOf("a.srt", "a.ass", "a.sub", "a.zip", "a.vtt")) ChatSubtitleText.read(data, name, 1, 2, "ar") }
            }
            survive("ZipArchive.inspect", shown) {
                runBlocking {
                    val source = object : ZipSource {
                        override val size get() = data.size.toLong()
                        override suspend fun read(offset: Long, length: Int): ByteArray? =
                            if (offset < 0 || offset > data.size) null else data.copyOfRange(offset.toInt(), minOf(data.size.toLong(), offset + length).toInt())
                    }
                    ZipArchive.inspect(source)
                    ZipArchive.inspect(source, diskStarts = listOf(0L, 7L))
                    ZipArchive.looksLikeZip(data)
                }
            }
        }
        assertNoFailures()
    }

    /** A real end record with every count, size and offset field set to nonsense (HX10, ZIP). */
    @Test
    fun zipDirectoriesWithLyingFieldsNeverThrow() {
        val base = java.io.ByteArrayOutputStream().also { bytes ->
            java.util.zip.ZipOutputStream(bytes).use { out ->
                out.putNextEntry(java.util.zip.ZipEntry("a.mkv"))
                out.write(ByteArray(2000))
                out.closeEntry()
            }
        }.toByteArray()
        val end = base.size - 22
        val random = Random(41)
        val values = listOf(0, 1, 0x7F, 0xFF)
        repeat(600) { round ->
            val copy = base.copyOf()
            // One to four bytes of the end record or the central directory record are overwritten.
            repeat(1 + round % 4) {
                val at = if (round % 2 == 0) end + random.nextInt(22) else (copy.size - 22 - 80 + random.nextInt(80)).coerceAtLeast(0)
                copy[at] = values[random.nextInt(values.size)].toByte()
            }
            survive("ZipArchive.inspect (lying fields)", round) {
                runBlocking {
                    ZipArchive.inspect(object : ZipSource {
                        override val size get() = copy.size.toLong()
                        override suspend fun read(offset: Long, length: Int): ByteArray? =
                            if (offset < 0 || offset > copy.size) null else copy.copyOfRange(offset.toInt(), minOf(copy.size.toLong(), offset + length).toInt())
                    })
                }
            }
        }
        assertNoFailures()
    }

    /**
     * The named lists in the sync file, a backup and the store (HX43): wrong types, huge arrays,
     * lines naming a list that is not there or each other's ids, missing ids and a 10 MB name.
     */
    @Test
    fun namedListsNeverThrow() {
        fun sync(lists: String) = "{\"format\":\"tmplayer-sync\",\"version\":2,\"lists\":$lists}"
        fun backup(lists: String) = "{\"format\":\"tmplayer-backup\",\"version\":2,\"lists\":$lists}"
        val line = "{\"list\":\"abcd1234\",\"chat\":-100,\"message\":5,\"title\":\"x\",\"addedAt\":1}"
        val shapes = texts.take(200).flatMap { t ->
            listOf(t, "{\"lists\":$t}", "{\"entries\":$t}", "{\"removed\":$t}", "{\"lists\":[$t],\"entries\":[$t],\"removed\":[$t]}")
        } + listOf(
            "[]", "\"lists\"", "{\"lists\":{},\"entries\":\"x\",\"removed\":7}",
            "{\"lists\":[{\"id\":5,\"name\":[],\"at\":\"now\"},{\"id\":\"ABC\",\"name\":\"x\",\"at\":1},{\"id\":\"\",\"name\":\"x\",\"at\":1},{\"id\":null}]}",
            "{\"lists\":[{\"id\":\"abcd1234\",\"name\":\"" + "\u0000".repeat(500) + "\",\"at\":1}]}",
            "{\"lists\":[{\"id\":\"abcd1234\",\"name\":\"a\",\"at\":1e999}],\"entries\":[{\"list\":\"abcd1234\",\"chat\":1e999,\"message\":-1e999,\"addedAt\":\"x\"}]}",
            // Lines that point at a list that is not there, at one another, or at nothing.
            "{\"lists\":[],\"entries\":[$line,{\"list\":\"nolist000\",\"chat\":1,\"message\":1,\"addedAt\":1},{\"chat\":1,\"message\":1,\"addedAt\":1},{\"list\":\"abcd1234\"}]}",
            "{\"lists\":[{\"id\":\"abcd1234\",\"name\":\"a\",\"at\":1},{\"id\":\"abcd1234\",\"name\":\"b\",\"at\":1}],\"entries\":[$line,$line],\"removed\":[{\"list\":\"abcd1234\",\"at\":1},{\"list\":\"abcd1234\",\"entry\":\"-100_5\",\"at\":1},{\"list\":\"abcd1234\",\"entry\":{},\"at\":null}]}",
            "{\"entries\":[{\"list\":\"abcd1234\",\"chat\":-100,\"message\":5,\"series\":\"" + "s".repeat(100_000) + "\",\"addedAt\":1}]}",
            "{\"entries\":[" + List(30_000) { line }.joinToString(",") + "]}",
            "{\"lists\":[" + List(5_000) { "{\"id\":\"l${it.toString().padStart(6, '0')}\",\"name\":\"n\",\"at\":1}" }.joinToString(",") + "]}",
            "{\"removed\":[" + List(10_000) { "{\"list\":\"abcd1234\",\"entry\":\"-100_$it\",\"at\":1}" }.joinToString(",") + "]}",
        )
        for (shape in shapes) {
            survive("SyncDocuments.decode lists", shape) {
                val read = SyncDocuments.decode(sync(shape).encodeToByteArray(), 0)
                if (read is SyncRead.Ok) {
                    val lists = read.doc.lists
                    assertTrue(lists.lists.size <= NamedListRules.MAX_LISTS * 2 && lists.entries.size <= NamedListRules.MAX_ENTRIES * 2)
                    assertTrue(lists.lists.all { NamedListRules.isId(it.id) && it.name.length <= NamedListRules.NAME_MAX })
                    val merged = SyncDocuments.merge(read.doc, SyncDocument(), 1_800_000_000_000L)
                    assertTrue(merged.lists.lists.size <= NamedListRules.MAX_LISTS && merged.lists.removed.size <= NamedListRules.MAX_REMOVALS)
                    assertTrue(merged.lists.entries.all { e -> merged.lists.lists.any { it.id == e.listId } })
                    SyncDocuments.encode(merged)
                }
            }
            survive("Backups.decode lists", shape) { Backups.decode(backup(shape)) }
        }
        // A name of ten million characters: cut at once, never read whole.
        val huge = "x".repeat(10_000_000)
        survive("NamedListRules.cleanName huge", "10 MB") {
            assertEquals("x".repeat(NamedListRules.NAME_MAX), NamedListRules.cleanName(huge))
        }
        survive("SyncDocuments.decode huge name", "10 MB") {
            SyncDocuments.decode(sync("{\"lists\":[{\"id\":\"abcd1234\",\"name\":\"$huge\",\"at\":1}]}").encodeToByteArray(), 0)
        }
        for (text in texts) {
            survive("NamedListRules.cleanName", text) { NamedListRules.cleanName(text)?.let { assertTrue(it.length <= NamedListRules.NAME_MAX) } }
            survive("NamedListRules.idOfHomeKey", text) { NamedListRules.idOfHomeKey(text); NamedListRules.idOfHomeKey("list-$text") }
            survive("NamedListKeys.read", text) {
                NamedListKeys.read(
                    mapOf(
                        "nm_$text" to text, "nm_abcd1234" to text, "n_abcd1234_$text" to text, "n_$text" to text,
                        "xn_$text" to 5L, "xn_abcd1234" to text, "xn_abcd1234_$text" to -1L, "n_abcd1234_-100_5" to text,
                    ),
                )
            }
        }
        assertNoFailures()
    }

    /**
     * Keep my place on all my devices, version 3: the bookmarks and corrections in the sync file
     * (truncated, wrong types, huge, random) and the raw entries their stores keep their times and
     * removals under. Whatever comes in reads as nothing, or as a bounded list the merge and the
     * writer take without throwing.
     */
    @Test
    fun syncedBookmarksAndCorrectionsNeverThrow() {
        fun sync(fields: String) = "{\"format\":\"tmplayer-sync\",\"version\":3,$fields}"
        val mark = "{\"chat\":-100,\"msg\":5,\"id\":1700000000000,\"pos\":9000,\"note\":\"the twist\",\"at\":1700000000001}"
        val fix = "{\"chat\":-100,\"msg\":5,\"fix\":\"title=Night Train\",\"at\":1700000000001}"
        val shapes = texts.take(200).flatMap { t ->
            listOf("\"bookmarks\":$t", "\"corrections\":$t", "\"bookmarks\":[$t],\"corrections\":[$t]", "\"bookmarks\":[{\"note\":$t,\"chat\":1,\"msg\":1,\"id\":1,\"pos\":1}]")
        } + listOf(
            "\"bookmarks\":{}", "\"bookmarks\":\"x\"", "\"bookmarks\":7,\"corrections\":null",
            "\"bookmarks\":[{\"chat\":\"a\",\"msg\":[],\"id\":null,\"pos\":{},\"note\":5,\"at\":\"now\"}]",
            "\"bookmarks\":[{\"chat\":1e999,\"msg\":-1e999,\"id\":1e999,\"pos\":1e999,\"at\":1e999}]",
            "\"bookmarks\":[{\"chat\":1,\"msg\":1,\"id\":-5,\"pos\":-1,\"note\":\"x\"},{\"chat\":1,\"msg\":1,\"id\":0,\"pos\":0}]",
            "\"bookmarks\":[{\"chat\":1,\"msg\":1,\"id\":5,\"pos\":1,\"note\":\"" + "n".repeat(1_000_000) + "\"}]",
            "\"bookmarks\":[{\"chat\":1,\"msg\":1,\"id\":5,\"pos\":1,\"note\":\"\u001e\u001f\u0000\\n\"}]",
            "\"bookmarks\":[" + List(30_000) { mark }.joinToString(",") + "]",
            "\"corrections\":[{\"chat\":1,\"msg\":1,\"fix\":5,\"at\":1},{\"chat\":1,\"msg\":1,\"at\":1},{\"fix\":\"separate\"}]",
            "\"corrections\":[{\"chat\":1,\"msg\":1,\"fix\":\"title=" + "t".repeat(2_000_000) + "\",\"at\":1}]",
            "\"corrections\":[{\"chat\":1,\"msg\":1,\"fix\":\"tmdb=film:99999999999999999999\\ntmdb=show:-5\\nwrong\\nseparate\",\"at\":-1}]",
            "\"corrections\":[" + List(20_000) { fix }.joinToString(",") + "]",
            "\"removed\":[{\"list\":\"bookmark\",\"key\":\"1_2\",\"at\":1},{\"list\":\"bookmark\",\"key\":\"a_b_c\",\"at\":1},{\"list\":\"correction\",\"key\":5,\"at\":1},{\"list\":\"correction\",\"key\":\"1_2_3\",\"at\":1}]",
            "\"bookmarks\":[$mark],\"corrections\":[$fix],\"removed\":[{\"list\":\"bookmark\",\"key\":\"-100_5_1700000000000\",\"at\":1700000000002}]",
        )
        for (shape in shapes) {
            survive("SyncDocuments.decode bookmarks", shape) {
                val read = SyncDocuments.decode(sync(shape).encodeToByteArray(), 0)
                if (read is SyncRead.Ok) {
                    val doc = read.doc
                    assertTrue(doc.bookmarks.size <= SyncDocuments.MAX_BOOKMARKS * 2 && doc.corrections.size <= SyncDocuments.MAX_CORRECTIONS * 2)
                    assertTrue(doc.bookmarks.all { it.bookmark.note.length <= BookmarkCodec.MAX_NOTE && it.bookmark.id > 0 && it.bookmark.positionMs >= 0 })
                    assertTrue(doc.corrections.all { VideoCorrection.decode(it.text).title?.length ?: 0 <= SyncDocuments.TITLE_MAX })
                    val merged = SyncDocuments.merge(doc, SyncDocument(), 1_800_000_000_000L)
                    assertTrue(merged.bookmarks.size <= SyncDocuments.MAX_BOOKMARKS && merged.corrections.size <= SyncDocuments.MAX_CORRECTIONS)
                    SyncDocuments.apply(SyncDocument(), merged)
                    SyncDocuments.decode(SyncDocuments.encode(merged).bytes, 0)
                }
            }
        }
        for (text in texts) {
            survive("SyncDocuments.bookmarksOf", text) {
                SyncDocuments.bookmarksOf(
                    mapOf(
                        "b_$text" to text, "b_-100_5" to text, "b_-100_5" to "\u001f\u001f", "bt_$text" to text, "bt_-100_5_1" to text,
                        "xb_$text" to 5L, "xb_-100_5_1" to text, "xb_-100_5_1" to -1L, "b_1_1_1" to text, "b_-100_6" to "1\u001f2\u001f$text",
                    ),
                )
            }
            survive("SyncDocuments.correctionsOf", text) {
                SyncDocuments.correctionsOf(
                    mapOf("fix_$text" to text, "fix_-100_5" to text, "fixat_-100_5" to text, "fixat_$text" to 5L, "fix_1_1" to "title=$text\ntmdb=film:$text"),
                )
            }
            survive("SyncDocuments.extraRemovals", text) {
                SyncDocuments.extraRemovals(
                    mapOf("xfix_$text" to 5L, "xfix_-100_5" to text, "xfix_1_2_3" to 5L),
                    mapOf("xb_$text" to 5L, "xb_-100_5_1" to text, "xb_1_2" to 5L),
                )
            }
        }
        assertNoFailures()
    }

    @Test
    fun savedRecordsNeverThrow() {
        val dir = kotlin.io.path.createTempDirectory("garbage").toFile()
        try {
            for ((index, text) in texts.withIndex()) {
                val file = File(dir, "record-$index.json").apply { writeText(text) }
                survive("CrashRecord.read", text) { CrashRecord.read(file) }
            }
            survive("CrashRecord.read missing", "") { CrashRecord.read(File(dir, "missing.json")) }
        } finally {
            dir.deleteRecursively()
        }
        assertNoFailures()
    }

    @Test
    fun jsonModelsNeverThrow() {
        val objects = texts.mapNotNull { runCatching { JSONObject(it) }.getOrNull() } + listOf(
            JSONObject("{\"title\":null,\"year\":\"x\",\"genres\":{},\"cast\":[null,1],\"runtime\":-5}"),
            JSONObject("{\"start\":\"a\",\"end\":-1,\"kind\":[]}"),
            JSONObject("{\"id\":[],\"language\":5,\"files\":\"x\",\"download_count\":\"many\"}"),
        )
        for (json in objects) {
            survive("MetaInfo.fromJson", json) { MetaInfo.fromJson(json) }
            survive("MetaExtras.fromJson", json) { MetaExtras.fromJson(json) }
            survive("SkipSegment.fromJson", json) { SkipSegment.fromJson(json) }
            survive("SubtitleHit.fromJson", json) { SubtitleHit.fromJson(org.json.JSONObject(json.toString())) }
        }
        assertNoFailures()
    }

    /**
     * A trailer's key is TMDB's, AniList's or the cache's, and the trailer page's messages come
     * back through a web view: truncated, wrong typed, huge, random, other scripts' digits and
     * letters. Nothing throws, nothing but an eleven character ASCII id is a key, and nothing but
     * the page's three shapes is a message.
     */
    @Test
    fun trailerKeysAndPageMessagesNeverThrow() {
        val labels = com.tmplayer.online.TrailerPageLabels("Trailer", "Loading")
        val keys = texts + listOf(
            "YE7VzlLtp-", "YE7VzlLtp-4x", "YE7VzlLtp-4".repeat(10_000), "١٢٣٤٥٦٧٨٩٠١", "०१२३४५६७८९०", "ＡＢＣＤＥＦＧＨＩＪＫ",
            "ÀÉÎÕÜàéîõüñ", "YE7VzlLtp\u00004", "YE7Vz\"><x>4", "YE7Vz'+x+'4", "😀😀😀😀😀1", "\uD800\uD800\uD800\uD800\uD800\uD800\uD800\uD800\uD800\uD800\uD800",
            "state:١", "state:１", "error:٢", "error:-", "state:--1", "error:99999999999", "ready".repeat(100),
        )
        for (key in keys) {
            survive("TrailerEmbed", key) {
                val valid = com.tmplayer.online.TrailerEmbed.isValidKey(key)
                if (valid != (key.length == 11 && key.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' })) {
                    error("isValidKey said $valid")
                }
                if ((com.tmplayer.online.TrailerEmbed.embedUrl(key) != null) != valid) error("embedUrl disagreed with isValidKey")
                if ((com.tmplayer.online.TrailerEmbed.pageHtml(key, com.tmplayer.online.TrailerPageLabels(key, key)) != null) != valid) error("pageHtml disagreed")
                com.tmplayer.online.TrailerEmbed.embedUrl("YE7VzlLtp-4", key)
                com.tmplayer.online.TrailerEmbed.pageHtml("YE7VzlLtp-4", labels, key)
                val message = com.tmplayer.online.TrailerEmbed.parseMessage(key)
                com.tmplayer.online.TrailerRules.next(com.tmplayer.online.TrailerState(), message)
                com.tmplayer.online.TrailerRules.route(true, key, true, true)
                MetaExtras.fromJson(JSONObject().put("trailer", key))?.trailer?.let { if (!com.tmplayer.online.TrailerEmbed.isValidKey(it.key)) error("a bad key came back from the cache") }
            }
        }
        assertNoFailures()
    }

    /** Chapter titles are free text, the modes line and the marks file are read from the disk: none may throw. */
    @Test
    fun skipTitlesModesAndMarksNeverThrow() {
        for (text in texts) {
            survive("ChapterSkips.kindOf", text) { ChapterSkips.kindOf(text) }
            survive("SkipModes.decode", text) { SkipModes.decode(text) }
            survive("UserSkipMarks.open", text) {
                UserSkipMarks.open(object : UserSkipMarks.Persistence {
                    override fun read() = text
                    override fun write(text: String) = Unit
                })
                UserSkipMarks.segmentsFor("1:1", "k", 1_400_000)
            }
            survive("Chapters.rows", text) {
                val random = Random(text.hashCode())
                val marks = List(random.nextInt(0, 8)) {
                    com.tmplayer.player.Chapter(random.nextLong(-5_000, 2_000_000), if (it % 2 == 0) text else null)
                }
                com.tmplayer.player.Chapters.rows(marks, random.nextLong(-5_000, 2_000_000))
                // PB-02: marks past the length, and lengths that are unknown, zero or Media3's TIME_UNSET.
                for (length in listOf(random.nextLong(1, 2_000_000), 0L, -1L, Long.MIN_VALUE + 1, 500L)) {
                    val rows = com.tmplayer.player.Chapters.rows(marks, random.nextLong(-5_000, 2_000_000), length)
                    if (length > 0 && rows.any { it.startMs >= length }) throw AssertionError("a row past the end of $length")
                    val inside = com.tmplayer.player.Chapters.inside(marks, length)
                    for (mark in inside) {
                        val to = com.tmplayer.player.Chapters.seekTarget(mark.startMs, length)
                        if (to < 0 || (length > com.tmplayer.player.Chapters.END_GUARD_MS && to >= length)) throw AssertionError("a seek to $to in $length")
                    }
                }
            }
            survive("Chapters.rows, 5,000 marks", text) {
                val random = Random(text.hashCode())
                val marks = List(5_000) { com.tmplayer.player.Chapter(random.nextLong(-5_000, 50_000_000), text) }
                val rows = com.tmplayer.player.Chapters.rows(marks, 0, random.nextLong(0, 50_000_000))
                if (rows.size > com.tmplayer.player.Chapters.MAX_ROWS) throw AssertionError("${rows.size} rows")
                if (rows.any { it.title.length > com.tmplayer.player.Chapters.MAX_TITLE }) throw AssertionError("a title not cut")
            }
            survive("SkipSession.chapters", text) {
                SkipSession().apply {
                    chapters(listOf(com.tmplayer.player.Chapter(Random(text.hashCode()).nextLong(-5, 2_000_000), text)))
                    offer(1_000, 1_400_000, true, 0)
                }
            }
        }
        UserSkipMarks.reset()
        assertNoFailures()
    }

    /** TMDB, TVmaze, OMDb, Wikidata, AniList, MSone's gateway and the skip-times sites answering nonsense. */
    @Test
    fun onlineProvidersNeverThrow() = runBlocking {
        val answers: List<() -> HttpResponse> = corpus.map { body -> { HttpResponse(200, body = body.encodeToByteArray()) } } + listOf(
            { HttpResponse(404, body = "nope".encodeToByteArray()) },
            { HttpResponse(429, headers = mapOf("Retry-After" to "soon"), body = ByteArray(0)) },
            { HttpResponse(429, headers = mapOf("Retry-After" to "-99999999999999"), body = ByteArray(0)) },
            { HttpResponse(500, body = "{}".encodeToByteArray()) },
            { HttpResponse(200, body = byteArrayOf(-1, -2, -3)) },
            { throw IOException("offline") },
        )
        for (answer in answers) {
            val http = HttpTransport { answer() }
            val limiter = RateLimiter(0, { 0L }, {})
            val shown = runCatching { answer().text }.getOrElse { "IOException" }
            val tmdb = TmdbApi(http, limiter, "t")
            survive("TmdbApi", shown) {
                runBlocking {
                    tmdb.searchMovie("k", "q", null, "en")
                    tmdb.searchTv("k", "q", 2020, "en")
                    tmdb.movie("k", "1", "en")
                    tmdb.tv("k", "1", "en")
                    tmdb.localized("k", true, "1", "en", listOf("en", "null"))
                    tmdb.logos("k", false, "1", listOf("en"))
                    tmdb.episode("k", "1", 1, 1, "en")
                    tmdb.season("k", "1", 1, "en")
                    tmdb.extras("k", true, "1", "en", "GB")
                    tmdb.extras("k", false, "1", "en", "GB")
                    // HX41: where to watch, for a film and a show, in a region and in nonsense.
                    tmdb.watchProviders("k", true, "1", "GB")
                    tmdb.watchProviders("k", false, "1", "\u0000??")
                }
            }
            val maze = TvMazeApi(http, limiter, "t")
            survive("TvMazeApi", shown) {
                runBlocking { maze.show("q"); maze.episode("1", 1, 1); maze.episodes("1"); maze.extras("1") }
            }
            survive("OmdbApi", shown) { runBlocking { OmdbApi(http, limiter, "t").scores("k", "tt1") } }
            survive("WikidataApi", shown) { runBlocking { WikidataApi(http, limiter, "t").scores("tt1") } }
            val ani = AniListApi(http, limiter, "t")
            survive("AniListApi", shown) { runBlocking { ani.search("q"); ani.extras("1"); ani.episodes("1") } }
            // T11: the anime episode sources, each asked for its episodes with nonsense coming back.
            survive("TmdbApi anime", shown) {
                runBlocking {
                    tmdb.showGuide("k", "1", "en")
                    tmdb.episodeGroup("k", "g", "en")
                    tmdb.seasons("k", "1", listOf(1, 2, 3), "en")
                }
            }
            // A one-season show's split by its episode group: whatever came back is chosen from and built, never thrown on.
            survive("SeasonSplit", shown) {
                runBlocking {
                    val got = (tmdb.extrasAndGroups("k", false, "1", "en", "GB") as? com.tmplayer.online.MetaReply.Ok)?.value
                    val listed = got?.extras?.seasonList.orEmpty().ifEmpty { listOf(com.tmplayer.online.MetaSeason(1, 37)) }
                    val groups = got?.groups.orEmpty() + listOf(com.tmplayer.online.TmdbEpisodeGroup("g", 6, 37, "Seasons", 2))
                    val episodes = (tmdb.episodeGroup("k", "g", "en") as? com.tmplayer.online.MetaReply.Ok)?.value.orEmpty()
                    com.tmplayer.online.SeasonSplit.choose(listed, groups)
                    val kept = com.tmplayer.online.SeasonSplit.read(com.tmplayer.online.SeasonSplit.store(episodes)).orEmpty()
                    com.tmplayer.online.SeasonSplit.build("g", kept, listed)?.let { split ->
                        split.next(com.tmplayer.online.MetaAiring(1, Int.MAX_VALUE, "x"))
                        split.applyTo(got?.extras ?: com.tmplayer.online.MetaExtras())
                    }
                }
            }
            survive("KitsuApi", shown) { runBlocking { val kitsu = com.tmplayer.online.KitsuApi(http, limiter, "t"); kitsu.idFor("1"); kitsu.episodes("1", 0) } }
            survive("JikanApi", shown) { runBlocking { com.tmplayer.online.JikanApi(http, limiter, "t").episodes("1", 1) } }
            val msone = MsoneApi(http, limiter, "t")
            survive("MsoneApi", shown) {
                runBlocking {
                    msone.health()
                    msone.search(MsoneApi.Query(title = "q", year = 2020, season = 1, episode = 2))
                    msone.search(MsoneApi.Query(imdb = "tt1234567"))
                    msone.search(MsoneApi.Query())
                    msone.fetch(com.tmplayer.online.SubtitleHit(com.tmplayer.online.SubtitleProvider.Msone, "1", "ml", "r"), 1_000)
                }
            }
            val submaker = SubMakerApi(http, limiter, "t")
            survive("SubMakerApi", shown) {
                runBlocking {
                    submaker.list("https://addon.test/tok", "movie", "tt1234567")
                    submaker.list("https://addon.test/tok", "series", "tt1234567:1:2")
                    submaker.list("https://addon.test/tok", "bogus", "x")
                    submaker.check("https://addon.test/tok")
                    submaker.fetch("https://addon.test/tok/translate/x")
                    val translator = SubMakerTranslator(submaker, { }, pollMs = 1, stableMs = 3, capMs = 8)
                    translator.run("https://addon.test/tok/translate/x", 1_000L, kotlinx.coroutines.flow.MutableStateFlow(true), { }, { _, _, _ -> })
                }
            }
            val skips = SkipTimesApi(http, limiter, limiter, "t")
            survive("SkipTimesApi", shown) {
                runBlocking {
                    skips.theIntroDb("1", "tt1", null, 1, 1)
                    skips.theIntroDb(null, null, null, null, null)
                    skips.aniSkip("1", 1, 1_400)
                    skips.introDbApp("tt1", 1, 1)
                }
            }
            // A fake client id, so the asking code runs; the real one is the user's to register.
            survive("AnimeSkipApi", shown) {
                runBlocking { AnimeSkipApi(http, limiter, "t", "fake-client", { 0L }).segments("1", 1, 1_400) }
            }
            survive("IdMapApi", shown) { runBlocking { IdMapApi(http, limiter, "t").forAniList("1") } }
        }
        assertNoFailures()
    }
}
