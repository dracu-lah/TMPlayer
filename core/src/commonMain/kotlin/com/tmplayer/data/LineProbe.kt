package com.tmplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Looks at the line when Telegram has not answered for a while (HX23): whether another site
 * answers, and whether any of Telegram's data centres takes a plain connection. A handful of
 * sockets once per wait, each given up after [TIMEOUT_MS], and nothing is sent over them.
 *
 * Before this the help said Telegram "may be blocked" after any long wait; on the Mi TV it said so
 * for a session Telegram had ended, on a network that reached Telegram fine (2026-10-28).
 */
object LineProbe {

    /** Telegram's data centres, by address so no lookup stands in the way (core.telegram.org/api/datacenter). */
    private val DATA_CENTRES = listOf(
        "149.154.175.53",
        "149.154.167.51",
        "149.154.175.100",
        "149.154.167.91",
        "91.108.56.130",
    )

    /** Another site: the app's own, which it already asks for updates, and GitHub's API. */
    private val OTHER_SITES = listOf("tmplayer.org", "api.github.com")

    private const val PORT = 443
    private const val TIMEOUT_MS = 4_000

    /** What answered, with [LineEvidence.freshClientTried] left for the caller. */
    suspend fun run(): LineEvidence = withContext(Dispatchers.IO) {
        coroutineScope {
            val telegram = async { anyAnswers(DATA_CENTRES) }
            val internet = async { anyAnswers(OTHER_SITES) }
            LineEvidence(internet = internet.await(), telegram = telegram.await())
        }
    }

    private suspend fun anyAnswers(hosts: List<String>): Boolean = coroutineScope {
        hosts.map { host -> async { answers(host) } }.awaitAll().any { it }
    }

    private fun answers(host: String): Boolean = runCatching {
        Socket().use { socket -> socket.connect(InetSocketAddress(host, PORT), TIMEOUT_MS) }
        true
    }.getOrDefault(false)
}
