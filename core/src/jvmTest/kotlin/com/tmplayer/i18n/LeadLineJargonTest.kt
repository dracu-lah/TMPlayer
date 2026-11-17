package com.tmplayer.i18n

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The words a first time user reads first when Telegram cannot be reached or a sign in fails
 * (Raju groups E and F, FQ1, FQ2, FQ7) carry no engine names. Detail one press away (More help, the
 * proxy screen, the logs) may say "proxy"; the lead lines may not.
 */
class LeadLineJargonTest {

    private val english: Map<String, String> =
        Translator.parse(File("src/commonMain/resources/i18n/en.json").readText())

    /** The lead lines: what the banner, the connecting card, the stall sheet and the sign in errors say first. */
    private val leadKeys = listOf(
        "connect.banner",
        "connect.slow_plain",
        "connect.hint_offline",
        "connect.hint_portal",
        "connect.hint_blocked_plain",
        "connect.hint_proxy_plain",
        "connect.hint_vpn",
        "connect.hint_not_answering",
        "connect.retrying",
        "connect.more_help",
        "connect.less_help",
        "errors.phone_check",
        "errors.code_check",
        "errors.premium_qr",
        "errors.start_failed",
        "errors.start_rejected",
        "errors.start_blocked",
        "errors.start_folder",
        "errors.start_folder_unnamed",
        "errors.start_system_too_old",
        "errors.no_credentials",
        "login.telegram_password_title",
        "player.stalled_title",
        "player.stalled_slow",
        "player.stalled_no_line_plain",
        "player.stalled_mid_title",
        "player.stalled_mid_slow",
    )

    private val jargon = Regex("tdlib|proxy|proxies|mtproto|codec|cache|api[ _]?(id|hash|credentials)|local\\.properties|TG_API", RegexOption.IGNORE_CASE)

    @Test
    fun `every lead line exists`() {
        for (key in leadKeys) assertTrue("missing $key", key in english)
    }

    @Test
    fun `no lead line carries an engine name`() {
        for (key in leadKeys) {
            val text = english.getValue(key)
            assertFalse("$key says \"${jargon.find(text)?.value}\": $text", jargon.containsMatchIn(text))
        }
    }

    @Test
    fun `the two lines Raju hit first read plainly`() {
        // Wrong code tells what to do next; the phone number error never needs the words "country code".
        assertTrue(english.getValue("errors.code_check").contains("latest message", ignoreCase = true))
        assertFalse(english.getValue("errors.phone_check").contains("country code", ignoreCase = true))
        // The sign in's QR sentence names the button as it is called.
        assertTrue(english.getValue("errors.premium_qr").contains(english.getValue("login.use_qr")))
    }
}
