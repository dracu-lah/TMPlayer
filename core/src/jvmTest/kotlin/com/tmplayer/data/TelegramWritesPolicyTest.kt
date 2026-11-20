package com.tmplayer.data

import dev.g000sha256.tdl.TdlClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Telegram writes policy (user, 2026-11-20): TMPlayer changes Telegram data only through the
 * short list below, and a new write call fails the build until somebody lists it here on purpose.
 *
 * How it works: the method names come from the real [TdlClient] by reflection, so nothing has to
 * be kept in step by hand. Every method that is not a read (`get`, `search`, `check`, ...) and not
 * known to touch only this device or the sign in (see [LOCAL_OR_SIGN_IN]) counts as a write. The
 * sources are scanned for calls to those names, and each file that makes one must be in [ALLOWED]
 * with exactly those methods. `viewMessages` is held to a stricter rule: it may only be used for
 * sponsored message signals (Telegram's terms), never to mark a chat read.
 *
 * Allowed: the sync file in Saved Messages (ResumeSync), the settings backup file
 * (SavedMessagesBackup), the one private channel "Create it for me" makes (SyncDestinations, HX87),
 * sponsored message view, click and report signals, Active sessions
 * (AccountSessions, always behind a confirm), open and close of the chat on screen, and sign out.
 */
class TelegramWritesPolicyTest {

    // Gradle runs the tests from the module directory.
    private val root = File("..")

    /** Scanned like this: the Kotlin and Swift sources that ship, never tests or the fake account. */
    private val roots = listOf("core/src", "app/src/main", "desktop/src/main", "ios", "ui/src")

    private val skipped = listOf("/build/", "/jvmTest/", "/commonTest/", "/jsTest/", "/test/", "/androidTest/", "/synthetic/", "/node_modules/")

    private fun sources(): List<File> = roots.flatMap { dir ->
        File(root, dir).walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "swift") }
            .filter { file -> skipped.none { file.invariantSeparatorsPath.contains(it) } }
            .toList()
    }

    /** The file's code without comments, so a KDoc that names a call is not a call. */
    private fun code(file: File): String = file.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
        .lines().joinToString("\n") { it.replace(Regex("(^|\\s)//.*$"), "") }

    private val tdlMethods: Set<String> = TdlClient::class.java.declaredMethods.filter { !it.isSynthetic && '$' !in it.name }.map { it.name }.toSet()

    private fun isRead(name: String) =
        READ_PREFIXES.any { name.startsWith(it) && name.getOrNull(it.length)?.isUpperCase() == true }

    /** Every TDLib method that may change Telegram data (or is not known to be harmless). */
    private val writes: Set<String> =
        tdlMethods.filter { !isRead(it) && it !in LOCAL_OR_SIGN_IN && it[0].isLowerCase() }.toSet()

    /** File path (from the repo root) to the write methods it calls, as `x.method(` or `::method`. */
    private fun calls(): Map<String, Set<String>> {
        val call = Regex("(\\w+)?\\s*\\??(?:\\.\\s*(\\w+)\\s*\\(|::\\s*(\\w+))")
        val found = sortedMapOf<String, Set<String>>()
        for (file in sources()) {
            val names = call.findAll(code(file)).mapNotNull { m ->
                val receiver = m.groupValues[1]
                val name = m.groupValues[2].ifEmpty { m.groupValues[3] }
                // `Td.logOut()` is the wrapper whose body is checked in Td.kt; `state.closeChat()` is the desktop's own screen state.
                if (name in writes && receiver !in WRAPPER_RECEIVERS) name else null
            }.toSet()
            if (names.isNotEmpty()) found[file.relativeTo(root).invariantSeparatorsPath] = names
        }
        return found
    }

    @Test
    fun `the scan sees the real client`() {
        // If reflection ever came back empty, every other check here would pass for nothing.
        for (name in listOf(
            "sendMessage", "editMessageText", "editMessageMedia", "deleteMessages", "pinChatMessage", "unpinChatMessage",
            "viewMessages", "openChat", "closeChat", "clickChatSponsoredMessage", "reportChatSponsoredMessage",
            "terminateSession", "logOut", "deleteAccount", "joinChat", "leaveChat", "forwardMessages", "sendChatAction",
            "addChatToList",
        )) {
            assertTrue("$name is not a write in the scan", name in writes)
        }
        assertTrue("getChat is a read", "getChat" !in writes)
        assertTrue("sources are found", sources().size > 500)
    }

    @Test
    fun `only the listed files call Telegram write methods`() {
        val unexpected = mutableListOf<String>()
        for ((path, names) in calls()) {
            val ok = ALLOWED.entries.firstOrNull { path.endsWith(it.key) }?.value.orEmpty()
            val extra = names - ok
            if (extra.isNotEmpty()) unexpected += "$path calls ${extra.sorted()}"
        }
        assertTrue(
            "New Telegram write calls. TMPlayer must not change Telegram data beyond the HX89 list; " +
                "if this one is meant, add it to ALLOWED in TelegramWritesPolicyTest and say why:\n" +
                unexpected.joinToString("\n"),
            unexpected.isEmpty(),
        )
    }

    @Test
    fun `the allow list has no entry that is no longer used`() {
        val found = calls()
        val stale = mutableListOf<String>()
        for ((suffix, names) in ALLOWED) {
            val used = found.entries.filter { it.key.endsWith(suffix) }.flatMap { it.value }.toSet()
            val unused = names - used
            if (unused.isNotEmpty()) stale += "$suffix no longer calls ${unused.sorted()}"
        }
        assertTrue("Remove from ALLOWED:\n" + stale.joinToString("\n"), stale.isEmpty())
    }

    @Test
    fun `viewMessages is only used for sponsored message signals`() {
        val offenders = sources()
            .filter { Regex("\\bviewMessages\\b").containsMatchIn(code(it)) }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filterNot { it.endsWith("/SponsoredMessageRepository.kt") }
        assertEquals(
            "viewMessages marks messages read; only the sponsored message view signal may use it",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `no chat is ever marked read`() {
        val readers = setOf(
            "viewMessages", "readAllChatMentions", "readAllChatReactions", "readAllMessageThreadMentions",
            "readAllMessageThreadReactions", "toggleChatIsMarkedAsUnread",
        )
        val offenders = calls().filter { (path, names) ->
            names.any { it in readers } && !path.endsWith("/SponsoredMessageRepository.kt")
        }
        assertEquals(emptyMap<String, Set<String>>(), offenders)
    }

    @Test
    fun `the sync channel is made as a channel, never a group, and only once per press`() {
        val file = sources().first { it.invariantSeparatorsPath.endsWith("data/SyncDestinations.kt") }
        val calls = Regex("createNewSupergroupChat\\(([^)]*)\\)").findAll(code(file)).map { it.groupValues[1].replace(" ", "") }.toList()
        // title, isForum = false, isChannel = true: a group would let others in by its link.
        assertEquals(listOf("name,false,true,description,null,0,false"), calls)
    }

    private companion object {
        /** TDLib methods that only read. */
        val READ_PREFIXES = listOf("get", "search", "check", "test", "can", "is", "parse", "load")

        /** Calls on these receivers are wrappers or screen state, not the TDLib client. */
        val WRAPPER_RECEIVERS = setOf("Td", "state")

        /**
         * Methods that do not change Telegram data: files and downloads on this device, TDLib's
         * own settings and proxies, and the steps of signing in (Telegram needs them to let anybody in).
         */
        val LOCAL_OR_SIGN_IN = setOf(
            "close", "destroy", "downloadFile", "cancelDownloadFile", "deleteFile", "readFilePart", "optimizeStorage",
            "addProxy", "enableProxy", "disableProxy", "removeProxy", "pingProxy",
            "setOption", "setTdlibParameters", "setLogStream", "setLogVerbosityLevel", "setNetworkType",
            "addFileToDownloads", "removeFileFromDownloads", "removeAllFilesFromDownloads", "toggleDownloadIsPaused",
            "setAuthenticationPhoneNumber", "setAuthenticationEmailAddress", "resendAuthenticationCode",
            "requestQrCodeAuthentication", "requestAuthenticationPasswordRecovery", "recoverAuthenticationPassword",
            // The viewer's own Saved Messages chat object; nothing is written by opening it.
            "createPrivateChat",
        )

        /**
         * The Telegram writes TMPlayer is allowed (path suffix to methods). Anything else fails
         * the scan above.
         */
        val ALLOWED: Map<String, Set<String>> = mapOf(
            // The sync file in Saved Messages, opt in, only its own file messages.
            "data/ResumeSync.kt" to setOf("sendMessage", "editMessageMedia", "editMessageText", "deleteMessages", "pinChatMessage"),
            // The settings backup file, on the viewer's press.
            "data/SavedMessagesBackup.kt" to setOf("sendMessage"),
            // HX87, on purpose: "Create it for me" makes one private channel (is_channel, never a group)
            // with only the viewer in it, on the viewer's press and after a confirm, for the sync file
            // and backups. Nothing else is written there until its member count is exactly 1.
            "data/SyncDestinations.kt" to setOf("createNewSupergroupChat"),
            // Sponsored message signals Telegram's terms ask for.
            "data/SponsoredMessageRepository.kt" to setOf("viewMessages", "clickChatSponsoredMessage", "reportChatSponsoredMessage"),
            // Active sessions, always behind a confirm that names the device.
            "data/AccountSessions.kt" to setOf("terminateSession"),
            // Telling TDLib which chat is on screen (no read marks), and sign out.
            // resetAuthenticationEmailAddress is the sign in step for a lost login email (Telegram's own flow).
            "data/Td.kt" to setOf("openChat", "closeChat", "logOut", "resetAuthenticationEmailAddress"),
            // Not Telegram: D-Bus messages to the desktop's notification and media player services.
            "desktop/os/LinuxNotifications.kt" to setOf("sendMessage"),
            "desktop/os/Mpris.kt" to setOf("sendMessage"),
        )
    }
}
