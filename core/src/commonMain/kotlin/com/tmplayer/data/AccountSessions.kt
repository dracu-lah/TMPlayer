package com.tmplayer.data

import com.tmplayer.i18n.L
import com.tmplayer.i18n.Messages
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.SessionDeviceType
import dev.g000sha256.tdl.dto.SessionDeviceTypeAndroid
import dev.g000sha256.tdl.dto.SessionDeviceTypeApple
import dev.g000sha256.tdl.dto.SessionDeviceTypeBrave
import dev.g000sha256.tdl.dto.SessionDeviceTypeChrome
import dev.g000sha256.tdl.dto.SessionDeviceTypeEdge
import dev.g000sha256.tdl.dto.SessionDeviceTypeFirefox
import dev.g000sha256.tdl.dto.SessionDeviceTypeIpad
import dev.g000sha256.tdl.dto.SessionDeviceTypeIphone
import dev.g000sha256.tdl.dto.SessionDeviceTypeLinux
import dev.g000sha256.tdl.dto.SessionDeviceTypeMac
import dev.g000sha256.tdl.dto.SessionDeviceTypeOpera
import dev.g000sha256.tdl.dto.SessionDeviceTypeSafari
import dev.g000sha256.tdl.dto.SessionDeviceTypeUbuntu
import dev.g000sha256.tdl.dto.SessionDeviceTypeVivaldi
import dev.g000sha256.tdl.dto.SessionDeviceTypeWindows
import dev.g000sha256.tdl.dto.SessionDeviceTypeXbox

/**
 * One device signed in to the account (CP83, `getActiveSessions`), as the Devices page lists it.
 * Dates are Unix seconds, as Telegram gives them.
 */
@androidx.compose.runtime.Immutable
data class AccountSession(
    val id: Long,
    val current: Boolean,
    val kind: Kind,
    val device: String,
    val platform: String,
    val systemVersion: String,
    val appName: String,
    val appVersion: String,
    val official: Boolean,
    val loginDate: Int,
    val lastActiveDate: Int,
    val ip: String,
    val location: String,
    /** Signed in with the code but still short of the two-step password. */
    val passwordPending: Boolean = false,
    /** Telegram has not yet asked the account whether this new sign in was theirs. */
    val unconfirmed: Boolean = false,
) {
    /** What the device is, for its icon. */
    enum class Kind { Phone, Tablet, Computer, Browser, Console, Unknown }
}

/**
 * The Devices page's list and its two actions: end one other session, end every other session.
 * Both are confirmed on the page before they get here; ending this device's own is not offered
 * (that is Sign out).
 */
object AccountSessions {

    fun kindOf(type: SessionDeviceType?): AccountSession.Kind = when (type) {
        is SessionDeviceTypeAndroid, is SessionDeviceTypeIphone -> AccountSession.Kind.Phone
        is SessionDeviceTypeIpad -> AccountSession.Kind.Tablet
        is SessionDeviceTypeWindows, is SessionDeviceTypeMac, is SessionDeviceTypeLinux,
        is SessionDeviceTypeUbuntu, is SessionDeviceTypeApple -> AccountSession.Kind.Computer
        is SessionDeviceTypeChrome, is SessionDeviceTypeFirefox, is SessionDeviceTypeSafari,
        is SessionDeviceTypeEdge, is SessionDeviceTypeOpera, is SessionDeviceTypeBrave,
        is SessionDeviceTypeVivaldi -> AccountSession.Kind.Browser
        is SessionDeviceTypeXbox -> AccountSession.Kind.Console
        else -> AccountSession.Kind.Unknown
    }

    fun of(session: dev.g000sha256.tdl.dto.Session): AccountSession = AccountSession(
        id = session.id,
        current = session.isCurrent,
        kind = if (looksLikeComputer(session)) AccountSession.Kind.Computer else kindOf(session.deviceType),
        device = session.deviceModel.orEmpty().trim(),
        platform = session.platform.orEmpty().trim(),
        systemVersion = session.systemVersion.orEmpty().trim(),
        appName = session.applicationName.orEmpty().trim(),
        appVersion = session.applicationVersion.orEmpty().trim(),
        official = session.isOfficialApplication,
        loginDate = session.logInDate,
        lastActiveDate = session.lastActiveDate,
        ip = session.ipAddress.orEmpty().trim(),
        location = session.location.orEmpty().trim(),
        passwordPending = session.isPasswordPending,
        unconfirmed = session.isUnconfirmed,
    )

    /**
     * TMPlayer's desktop app signs in with "Desktop" and its system (Linux, Windows, macOS), which
     * Telegram can type as a phone: such a session is a computer.
     */
    private fun looksLikeComputer(session: dev.g000sha256.tdl.dto.Session): Boolean {
        val text = listOf(session.deviceModel, session.platform, session.systemVersion).joinToString(" ").lowercase()
        return "desktop" in text || "windows" in text || "macos" in text || "mac os" in text
    }

    /** This device first, then the others by when they were last used, newest first. */
    fun arrange(sessions: List<AccountSession>): List<AccountSession> =
        sessions.sortedWith(compareByDescending<AccountSession> { it.current }.thenByDescending { it.lastActiveDate }.thenBy { it.id })

    /** "Pixel 8" or, where the model is not given, the platform; never empty. */
    fun title(session: AccountSession, s: Messages): String =
        session.device.ifBlank { session.platform }.ifBlank { s.sessionsUnknownDevice }

    /** "TMPlayer 1.27.0  ·  Android 15": the app, then the system, each where it is known. */
    fun appLine(session: AccountSession): String = listOf(
        listOf(session.appName, session.appVersion).filter { it.isNotBlank() }.joinToString(" "),
        listOf(session.platform, session.systemVersion).filter { it.isNotBlank() }.joinToString(" ")
            .takeIf { it != session.device },
    ).filter { !it.isNullOrBlank() }.joinToString("  ·  ")

    /**
     * "Berlin, Germany  ·  Active 2 hours ago", or "Online now" for this device. The address is
     * left out of the line: the place says the same to a person, and the page shows it on its own.
     */
    fun placeLine(session: AccountSession, s: Messages, nowSec: Long): String = listOfNotNull(
        session.location.ifBlank { null },
        if (session.current) s.sessionsThisDeviceOnline else lastActive(session.lastActiveDate, s, nowSec),
    ).joinToString("  ·  ")

    /** "Active just now", "Active 5 minutes ago", "Active 3 days ago", or the date past a month. */
    fun lastActive(dateSec: Int, s: Messages, nowSec: Long): String {
        if (dateSec <= 0) return s.sessionsActiveUnknown
        val ago = (nowSec - dateSec).coerceAtLeast(0)
        return when {
            ago < 120 -> s.sessionsActiveNow
            ago < 3600 -> s.sessionsActiveMinutes((ago / 60).toInt())
            ago < 86_400 -> s.sessionsActiveHours((ago / 3600).toInt())
            ago < 30 * 86_400 -> s.sessionsActiveDays((ago / 86_400).toInt())
            else -> s.sessionsActiveOn(s.formatter.date(dateSec * 1000L))
        }
    }

    /**
     * The authorized client, waited for exactly as before (HX88 keeps the wait: one profile sees
     * no change), handed out only when it is [owner]'s and no switch is under way.
     */
    private suspend fun ownSession(owner: AccountStamp): TdlClient? {
        val session = runCatching { Td.awaitAuthorizedSession() }.getOrNull() ?: return null
        return session.client.takeIf { session.owner == owner && OwnedSessions.mayAct(owner) }
    }

    /** The account's sessions, or the reason they could not be read. */
    suspend fun load(): Result<List<AccountSession>> {
        val td = runCatching { Td.awaitAuthorizedSession().client }.getOrElse { return Result.failure(it) }
        return when (val result = td.getActiveSessions()) {
            // TMPlayer's own sessions only (user, 2026-10-19): the page is about where this app is
            // signed in, not every Telegram app on the account. Told apart by the API id the app
            // signs in with; this device is always listed.
            is TdlResult.Success -> Result.success(arrange(result.result.sessions.orEmpty().filter(::ours).map(::of)))
            is TdlResult.Failure -> Result.failure(IllegalStateException(Failures.humanise(result.message)))
        }
    }

    /**
     * The devices an "End all" question names: the first few by name, then how many more, so the
     * viewer reads what is about to be signed out before agreeing.
     */
    fun devicesLine(sessions: List<AccountSession>, s: Messages, shown: Int = 3): String {
        val names = sessions.map { title(it, s) }
        val head = names.take(shown).joinToString(", ")
        return if (names.size > shown) head + ", " + s.sessionsEndAllMore((names.size - shown).toString()) else head
    }

    /** Whether ending [id] would sign this very device out, which this page never does. */
    internal fun endsThisDevice(sessions: Array<out dev.g000sha256.tdl.dto.Session>, id: Long): Boolean =
        sessions.any { it.id == id && it.isCurrent }

    /**
     * Signs [id] out. Null on success, else what went wrong. This device's own session is refused
     * here as well as left out of the page (Sign out in Settings is what ends it), and so is any
     * case where the list cannot be read to tell.
     */
    suspend fun end(id: Long): String? {
        // The open profile's own client (HX88): never the next one's if a switch lands meanwhile.
        // Waited for as before (no timeout of its own), then checked for its profile.
        val owner = Accounts.stamp
        val td = ownSession(owner) ?: return L.errorsNotConnected
        val listed = when (val read = td.getActiveSessions()) {
            is TdlResult.Success -> read.result.sessions.orEmpty()
            is TdlResult.Failure -> return Failures.humanise(read.message)
        }
        if (endsThisDevice(listed, id)) return L.sessionsEndThisDevice
        if (!OwnedSessions.mayAct(owner)) return L.errorsNotConnected
        val result = td.terminateSession(id)
        return (result as? TdlResult.Failure)?.let { Failures.humanise(it.message) }
    }

    /**
     * Signs TMPlayer out on every other device, one session at a time: Telegram's own "all other
     * sessions" would sign out every Telegram app on the account too. Null on success, else what
     * went wrong first.
     */
    suspend fun endAllOthers(): String? {
        val owner = Accounts.stamp
        val td = ownSession(owner) ?: return L.errorsNotConnected
        val sessions = when (val result = td.getActiveSessions()) {
            is TdlResult.Success -> result.result.sessions.orEmpty()
            is TdlResult.Failure -> return Failures.humanise(result.message)
        }
        for (session in sessions.filter { !it.isCurrent && ours(it) }) {
            if (!OwnedSessions.mayAct(owner)) return L.errorsNotConnected
            val result = td.terminateSession(session.id)
            if (result is TdlResult.Failure) return Failures.humanise(result.message)
        }
        return null
    }

    /** Whether [session] is TMPlayer's: this device, or one signed in with this app's API id. */
    private fun ours(session: dev.g000sha256.tdl.dto.Session): Boolean {
        if (session.isCurrent) return true
        val apiId = Td.apiId
        return if (apiId != 0) session.apiId == apiId else session.applicationName.orEmpty().contains("TMPlayer", ignoreCase = true)
    }
}

/**
 * The note for a download slowed by Telegram itself (CP83): Telegram sends
 * `updateSpeedLimitNotification` when it caps the speed of an account without Premium, and the
 * player's slow line says so for a while after, as information. Never an upsell, and never for a
 * Premium account, whose downloads Telegram does not cap.
 */
object SpeedLimit {

    /** How long one notification is taken to still hold. */
    const val HOLDS_MS = 5 * 60 * 1000L

    fun limited(premium: Boolean, limitedAtMs: Long, nowMs: Long): Boolean =
        !premium && limitedAtMs > 0 && nowMs - limitedAtMs in 0..HOLDS_MS
}
