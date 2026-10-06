package com.tmplayer.online

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Properties

/**
 * The viewer's OpenSubtitles account and the online subtitle switches, as this device knows them.
 *
 * [token] is the session OpenSubtitles hands out at sign in; the password is never kept. The
 * quota figures are the last ones OpenSubtitles reported, which is what "N of M downloads left
 * today" reads. [rejectedKey] is a fingerprint of an app key OpenSubtitles refused, so the feature
 * stays off for that key and comes back by itself when an update brings another.
 */
data class OnlineAccount(
    val username: String = "",
    val token: String = "",
    val host: String = OpenSubtitlesApi.DEFAULT_HOST,
    val allowed: Int = 0,
    val remaining: Int = -1,
    val resetAt: Long = 0,
    /** Signed in once, then OpenSubtitles stopped taking the token. */
    val expired: Boolean = false,
    val includeMachine: Boolean = false,
    val subdlKey: String = "",
    val subdlRefused: Boolean = false,
    val rejectedKey: String = "",
    val rejectedAt: Long = 0,
) {
    val signedIn: Boolean get() = token.isNotBlank()

    /** No downloads left until [resetAt], as far as the last answer said. */
    fun quotaUsed(now: Long): Boolean = signedIn && remaining == 0 && (resetAt == 0L || now < resetAt)
}

/** [OnlineAccount] kept in a small properties file in the app's private storage. */
class OnlineSubtitlesStore(private val file: File) {

    private val state = MutableStateFlow(load())

    val account: StateFlow<OnlineAccount> = state.asStateFlow()

    val now: OnlineAccount get() = state.value

    @Synchronized
    fun update(change: (OnlineAccount) -> OnlineAccount) {
        val next = change(state.value)
        if (next == state.value) return
        save(next)
        state.value = next
    }

    private fun load(): OnlineAccount {
        val p = Properties()
        runCatching { if (file.isFile) file.inputStream().use { p.load(it) } }
        fun s(name: String) = p.getProperty(name).orEmpty()
        fun i(name: String, default: Int) = p.getProperty(name)?.toIntOrNull() ?: default
        fun l(name: String) = p.getProperty(name)?.toLongOrNull() ?: 0L
        return OnlineAccount(
            username = s("username"),
            token = s("token"),
            host = s("host").ifBlank { OpenSubtitlesApi.DEFAULT_HOST },
            allowed = i("allowed", 0),
            remaining = i("remaining", -1),
            resetAt = l("reset_at"),
            expired = s("expired") == "true",
            includeMachine = s("include_machine") == "true",
            subdlKey = s("subdl_key"),
            subdlRefused = s("subdl_refused") == "true",
            rejectedKey = s("rejected_key"),
            rejectedAt = l("rejected_at"),
        )
    }

    private fun save(a: OnlineAccount) {
        val p = Properties()
        p["username"] = a.username
        p["token"] = a.token
        p["host"] = a.host
        p["allowed"] = a.allowed.toString()
        p["remaining"] = a.remaining.toString()
        p["reset_at"] = a.resetAt.toString()
        p["expired"] = a.expired.toString()
        p["include_machine"] = a.includeMachine.toString()
        p["subdl_key"] = a.subdlKey
        p["subdl_refused"] = a.subdlRefused.toString()
        p["rejected_key"] = a.rejectedKey
        p["rejected_at"] = a.rejectedAt.toString()
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.outputStream().use { p.store(it, null) }
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }
}
