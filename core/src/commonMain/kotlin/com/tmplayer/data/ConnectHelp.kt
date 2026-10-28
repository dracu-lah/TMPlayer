package com.tmplayer.data

import dev.g000sha256.tdl.dto.ConnectionState
import dev.g000sha256.tdl.dto.ConnectionStateConnecting
import dev.g000sha256.tdl.dto.ConnectionStateConnectingToProxy
import dev.g000sha256.tdl.dto.ConnectionStateReady
import dev.g000sha256.tdl.dto.ConnectionStateUpdating
import dev.g000sha256.tdl.dto.ConnectionStateWaitingForNetwork

/** TDLib's `updateConnectionState`, as the [TelegramLink] it means. */
fun TelegramLink.Companion.of(state: ConnectionState): TelegramLink = when (state) {
    is ConnectionStateWaitingForNetwork -> TelegramLink.WaitingForNetwork
    is ConnectionStateConnectingToProxy -> TelegramLink.ConnectingToProxy
    is ConnectionStateConnecting -> TelegramLink.Connecting
    is ConnectionStateUpdating -> TelegramLink.Updating
    is ConnectionStateReady -> TelegramLink.Ready
    else -> TelegramLink.Connecting
}

/** The kind of network the device is on, which is what TDLib's `setNetworkType` takes. */
enum class NetworkKind { WiFi, Mobile, Roaming, Ethernet, Other }

/**
 * What the platform reports about the network it is on.
 *
 * [known] is false where the platform reports nothing (the desktop), and then nothing here is
 * offered as advice. [validated] is Android's word for "a check page on the internet answered",
 * which a captive portal, and a country or network that blocks Google's check page, never give;
 * that is not the same as being offline, so it only decides [status] between online and unknown.
 * [id] tells one network from the next, so a switch from Wi-Fi to mobile data is noticed even when
 * every other field reads the same.
 */
data class NetworkFacts(
    val known: Boolean = false,
    val hasNetwork: Boolean = true,
    val internet: Boolean = true,
    val validated: Boolean = true,
    val captivePortal: Boolean = false,
    val vpn: Boolean = false,
    val kind: NetworkKind = NetworkKind.Other,
    val id: String = "",
) {
    /**
     * Offline only when there is no network that claims the internet at all. An unvalidated one is
     * [NetworkStatus.Unknown], which is allowed to try: Telegram may well be reachable on it, and
     * when it is not, TDLib's own connection state says so.
     */
    val status: NetworkStatus
        get() = when {
            !known -> NetworkStatus.Unknown
            !hasNetwork || !internet -> NetworkStatus.Offline
            validated && !captivePortal -> NetworkStatus.Online
            else -> NetworkStatus.Unknown
        }

    /** Whether going from [before] to this is a different network, which TDLib must be told about. */
    fun isSwitchFrom(before: NetworkFacts): Boolean =
        hasNetwork && internet && (id != before.id || kind != before.kind || vpn != before.vpn || !before.hasNetwork || !before.internet)
}

/** What the connecting screen says it is doing, one line, kept current. */
enum class ConnectStep {
    Starting,
    WaitingForNetwork,
    ConnectingToProxy,
    Connecting,
    CatchingUp,

    /** Connected, and the sign in or the session is being opened over it. */
    Opening,
}

/** The likeliest reason, once the wait has gone on too long, in the order it is checked. */
enum class ConnectHint {
    /** The device has no network, or TDLib says it is waiting for one. */
    Offline,

    /** A Wi-Fi that wants its own sign in page first. */
    CaptivePortal,

    /** A proxy is switched on and Telegram is still out of reach through it. */
    ProxyNotAnswering,

    /** A VPN is on, and may be the thing in the way. */
    Vpn,

    /**
     * Other sites answer and Telegram does not ([SessionCheck.blocked]): blocked, most likely. The
     * only hint that suggests a proxy.
     */
    MaybeBlocked,

    /**
     * Telegram is not answering and nothing says why: no sign of a block, so no proxy is
     * suggested, only that the app keeps trying (HX23). A wait that merely went on is not a block.
     */
    NotAnswering,
}

/**
 * The connecting screen's reasoning, kept apart from it so it can be tested: what to say while it
 * waits, whether it is waiting at all, and once it has waited too long, what is most likely wrong.
 */
object ConnectAdvice {

    fun step(link: TelegramLink): ConnectStep = when (link) {
        TelegramLink.Starting -> ConnectStep.Starting
        TelegramLink.WaitingForNetwork -> ConnectStep.WaitingForNetwork
        TelegramLink.ConnectingToProxy -> ConnectStep.ConnectingToProxy
        TelegramLink.Connecting -> ConnectStep.Connecting
        TelegramLink.Updating -> ConnectStep.CatchingUp
        TelegramLink.Ready -> ConnectStep.Opening
    }

    /**
     * Whether the sign in is held up on Telegram: still on Connecting, or on one of its screens with
     * no line to Telegram, where whatever is typed next has nowhere to go. A signed in session and a
     * failure are never waiting: the one has its own offline notice, the other its own screen.
     */
    fun waiting(auth: AuthState, link: TelegramLink): Boolean = when (auth) {
        AuthState.Connecting -> true
        AuthState.Ready, is AuthState.Failed -> false
        else -> !link.isUp
    }

    /**
     * What the platform reports, in the words the help shows under the hint: the kind of network,
     * then whatever about it is worth checking. Empty where the platform reports nothing.
     */
    fun networkNotes(network: NetworkFacts): List<NetworkNote> {
        if (!network.known) return emptyList()
        if (!network.hasNetwork || !network.internet) return listOf(NetworkNote.None)
        val notes = mutableListOf(
            when (network.kind) {
                NetworkKind.WiFi -> NetworkNote.WiFi
                NetworkKind.Mobile -> NetworkNote.Mobile
                NetworkKind.Roaming -> NetworkNote.Roaming
                NetworkKind.Ethernet, NetworkKind.Other -> NetworkNote.Other
            },
        )
        if (network.vpn) notes += NetworkNote.Vpn
        if (network.captivePortal) {
            notes += NetworkNote.Portal
        } else if (!network.validated) {
            notes += NetworkNote.Unchecked
        }
        return notes
    }

    /**
     * The likeliest reason, checked in order. A block is said only with [evidence] of one
     * ([SessionCheck.blocked]); before the line is checked, or when Telegram's data centres answer,
     * the hint says Telegram is not answering and suggests nothing.
     */
    fun hint(
        link: TelegramLink,
        network: NetworkFacts,
        proxyOn: Boolean,
        evidence: LineEvidence = LineEvidence(),
    ): ConnectHint = when {
        link == TelegramLink.WaitingForNetwork -> ConnectHint.Offline
        network.known && network.status == NetworkStatus.Offline -> ConnectHint.Offline
        network.known && network.captivePortal -> ConnectHint.CaptivePortal
        proxyOn || link == TelegramLink.ConnectingToProxy -> ConnectHint.ProxyNotAnswering
        network.known && network.vpn -> ConnectHint.Vpn
        SessionCheck.blocked(network.status == NetworkStatus.Online, evidence) -> ConnectHint.MaybeBlocked
        else -> ConnectHint.NotAnswering
    }

    /**
     * Whether the proxy button says "Use a proxy" (a suggestion) rather than the neutral "Proxy
     * settings": only for a block with evidence behind it, and never once a proxy is on.
     */
    fun suggestsProxy(hint: ConnectHint, proxyOn: Boolean): Boolean = !proxyOn && hint == ConnectHint.MaybeBlocked
}

/** One thing the platform says about its network, for the help's "This device reports" line. */
enum class NetworkNote { None, WiFi, Mobile, Roaming, Other, Vpn, Portal, Unchecked }
