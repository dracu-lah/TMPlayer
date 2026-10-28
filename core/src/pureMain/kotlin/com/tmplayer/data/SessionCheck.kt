package com.tmplayer.data

/**
 * What the app has found out about a line to Telegram that will not come up (HX23), so the help it
 * shows rests on something it saw rather than on how long it has waited.
 *
 * [internet] is whether another site answered (Android's own check page, or the app's own site);
 * [telegram] whether any of Telegram's data centres took a plain connection. Null where not yet
 * checked. [freshClientTried] once a new client was started for this wait and it still has no line.
 */
data class LineEvidence(
    val internet: Boolean? = null,
    val telegram: Boolean? = null,
    val freshClientTried: Boolean = false,
)

/** What a signed in session with no line is given next, after the session and the line are checked. */
enum class LineAction {
    /** Leave TDLib to it: offline, through a proxy, Telegram itself out of reach, or tried already. */
    Wait,

    /**
     * Telegram's data centres answer and this session still has no line: what a session Telegram
     * ended looks like to a client that has not noticed (HX23). A new client over the same
     * database finds out at once, as a force stop did on the TV.
     */
    FreshClient,
}

/**
 * The decisions behind a session that stopped answering (HX23): whether Telegram ended it, whether
 * a new client is worth starting, and whether there is any sign that Telegram is blocked. Kept
 * free of TDLib so each one is a plain test.
 */
object SessionCheck {

    /**
     * Whether a refused request says the session is no longer signed in: TDLib's own
     * "Unauthorized" (its answer to every request once it is logging out), or Telegram's words for
     * a key or a session it no longer knows. A two-step password asked for during sign in is a 401
     * too, and is not one of them.
     */
    fun isAuthError(code: Int, message: String?): Boolean {
        val raw = message.orEmpty()
        if (raw.contains("SESSION_PASSWORD_NEEDED")) return false
        if (code == 401) return true
        return raw.startsWith("Unauthorized", ignoreCase = true) || // i18n-ok: TDLib text, matched
            AUTH_ERRORS.any { raw.contains(it) }
    }

    /**
     * Whether TDLib moving to [state] means the session ended without the viewer asking: a logout
     * nobody here started, whether Telegram ended it while the app ran or before it opened.
     */
    fun endedByTelegram(state: TdAuthorization, logOutAsked: Boolean): Boolean =
        !logOutAsked && state == TdAuthorization.LoggingOut

    /**
     * Whether a sign in screen comes after an account this device had signed in: the marker is set
     * on every sign in and wiped by Sign out with the rest of the preferences, so it outlives only
     * a session that ended elsewhere (Telegram's Devices list, or Telegram itself).
     */
    fun endedElsewhere(signInStep: Boolean, wasSignedIn: Boolean): Boolean = signInStep && wasSignedIn

    /**
     * What a signed in session that has had no line for a while gets once the line is checked.
     * Only on view and with no proxy: a proxy's own data centre connection says nothing about the
     * session, and a client is replaced at most once per wait.
     */
    fun afterProbe(
        signedIn: Boolean,
        foreground: Boolean,
        proxyOn: Boolean,
        evidence: LineEvidence,
    ): LineAction = when {
        !signedIn || !foreground || proxyOn -> LineAction.Wait
        evidence.freshClientTried -> LineAction.Wait
        evidence.telegram == true -> LineAction.FreshClient
        else -> LineAction.Wait
    }

    /**
     * Whether there is a sign that Telegram is blocked here, which is the only time a proxy is
     * suggested: other sites answer and no data centre takes a connection, or they do and a new
     * client still got no line through them (a network that filters Telegram's traffic).
     */
    fun blocked(networkOnline: Boolean, evidence: LineEvidence): Boolean {
        val internet = networkOnline || evidence.internet == true
        if (!internet) return false
        return evidence.telegram == false || (evidence.telegram == true && evidence.freshClientTried)
    }

    private val AUTH_ERRORS = listOf(
        "AUTH_KEY_UNREGISTERED",
        "AUTH_KEY_INVALID",
        "SESSION_REVOKED",
        "SESSION_EXPIRED",
        "USER_DEACTIVATED",
    )
}
