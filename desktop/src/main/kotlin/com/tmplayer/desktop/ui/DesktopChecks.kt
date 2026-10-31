package com.tmplayer.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.tmplayer.data.ChatGate
import com.tmplayer.data.EpisodeAlerts
import com.tmplayer.data.MediaItem
import com.tmplayer.data.NewVideoAlerts
import com.tmplayer.data.Notice
import com.tmplayer.data.PieceAlerts
import com.tmplayer.data.StorageAlerts
import com.tmplayer.data.SupportReminder
import com.tmplayer.data.Td
import com.tmplayer.data.UpdateAlerts
import com.tmplayer.data.Updates
import com.tmplayer.desktop.DesktopPaths
import com.tmplayer.desktop.os.DesktopNotices
import com.tmplayer.desktop.os.DesktopTransferNotifier
import com.tmplayer.desktop.os.NoticeButton
import com.tmplayer.desktop.os.OpenExternal
import com.tmplayer.i18n.L
import com.tmplayer.platform.Background
import com.tmplayer.platform.Logger
import com.tmplayer.ui.about.About
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the desktop checks while it runs, minimised too, once signed in, every [EVERY_MS], each
 * behind its own switch ([Notice], CP76): new videos in the starred chats ([NewVideoAlerts]), new
 * episodes of shows under way ([EpisodeAlerts]), the missing pieces of cut videos ([PieceAlerts]),
 * low storage ([StorageAlerts]), a new version found by the update check ([UpdateAlerts]) and the
 * support ask ([SupportReminder.nudgeDue]), a star first and money after. All go out as system
 * notifications ([DesktopTransferNotifier.notices]); a press on one, where the system passes
 * presses back, raises the window and opens what it is about. Nothing is posted while the window
 * has the focus: the lists and the update popup already say it.
 */
@Composable
fun DesktopChecks(state: ShellState) {
    LaunchedEffect(state) {
        delay(FIRST_AFTER_MS)
        while (true) {
            runCatching { checkOnce(state) }.onFailure { Logger.w(TAG, "check failed: ${it.message}") }
            delay(EVERY_MS)
        }
    }
}

private suspend fun checkOnce(state: ShellState) = withContext(Dispatchers.IO) {
    val settings = state.settings
    val notices = DesktopTransferNotifier.notices
    val focused = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow != null

    val newVideos = settings.noticeOnNow(Notice.NewVideos)
    val episodes = settings.noticeOnNow(Notice.NewEpisodes)
    val pieces = settings.noticeOnNow(Notice.MissingPieces)
    if (newVideos || episodes || pieces) {
        val session = withTimeoutOrNull(CONNECT_WAIT_MS) { Td.awaitConnectedSession() }
        if (session != null) {
            // The kids lock and the chats the viewer added (HX27): no alert comes from a chat
            // they never added.
            val allowed = ChatGate.awaitBrowsing()
            if (newVideos) {
                val alert = NewVideoAlerts.check(session.client, settings, allowed)
                if (alert != null && !focused) {
                    notices?.notice(alert.title, alert.body) { openChat(state, alert.chatId) }
                }
            }
            if (episodes) {
                val alert = EpisodeAlerts.check(session.client, settings, state.watched, allowed)
                if (alert != null && !focused && notices != null) {
                    val play = alert.play
                    val buttons = if (play != null) listOf(NoticeButton(L.commonPlay) { playFromNotice(state, play, alert.chatTitle) }) else emptyList()
                    notices.notice(alert.title, alert.body, { openChat(state, alert.chatId) }, buttons)
                }
            }
            if (pieces) {
                val alerts = PieceAlerts.check(session.client, settings, allowed, System.currentTimeMillis())
                if (!focused && notices != null) {
                    for (alert in alerts) {
                        val play = alert.play
                        val buttons = if (play != null) listOf(NoticeButton(L.commonPlay) { playFromNotice(state, play, alert.chatTitle) }) else emptyList()
                        notices.notice(alert.title, alert.body, { openChat(state, play?.chatId) }, buttons)
                    }
                }
            }
        }
    }

    if (notices != null && !focused) {
        StorageAlerts.check(settings, DesktopPaths.disk(), System.currentTimeMillis())?.let { (title, body) ->
            notices.notice(title, body, { openFreeUp(state) }, listOf(NoticeButton(L.downloadsFreeUpSpace) { openFreeUp(state) }))
        }
    }

    // The update check itself runs on its own schedule (UpdateScheduler); this only says what it
    // found, once per version, to a window that is not in front.
    if (notices != null && settings.noticeOnNow(Notice.UpdateReady)) {
        val release = UpdateAlerts.due(
            Updates.state.value,
            alertedFor = settings.updateAlertedNow(),
            skipped = settings.updatePrefs.skippedVersion(),
            switchOn = true,
            inFront = focused,
        )
        if (release != null) {
            settings.setUpdateAlerted(release.version)
            val (title, body) = UpdateAlerts.words(release)
            notices.notice(title, body) { DesktopTransferNotifier.raise() }
        }
    }

    if (!focused && notices != null) {
        SupportReminder.claimNudgeRung(settings, System.currentTimeMillis())?.let { rung -> askForSupport(state, notices, rung) }
    }
}

/** The support ask: a star on its own first, then a coffee; the body opens the window. */
private suspend fun askForSupport(state: ShellState, notices: DesktopNotices, rung: SupportReminder.NudgeRung) {
    val counters = state.settings.supportCountersNow()
    val star = rung == SupportReminder.NudgeRung.Star
    val buttons = buildList {
        if (!star) add(NoticeButton(L.supportBuyCoffee) { OpenExternal.browse(About.COFFEE) })
        if (SupportReminder.offersStar(counters, rung)) {
            add(
                NoticeButton(L.supportStarGithub) {
                    OpenExternal.browse(About.SOURCE)
                    Background.scope.launch { runCatching { state.settings.markSupportStarred() } }
                },
            )
        }
        // Not now: the notice goes, and that is all.
        add(NoticeButton(L.notifyActionNotNow) {})
    }
    notices.notice(
        if (star) L.notifyStarTitle else L.notifySponsorTitle,
        if (star) L.notifyStarBody else L.notifySponsorBody,
        {
            DesktopTransferNotifier.raise()
            SupportReminder.requestOpen()
        },
        buttons,
    )
}

private fun openChat(state: ShellState, chatId: Long?) {
    DesktopTransferNotifier.raise()
    java.awt.EventQueue.invokeLater {
        val chat = chatId?.let(state::chatOf)
        state.go(Destination.Chats)
        if (chat != null) state.openChat(chat)
    }
}

private fun playFromNotice(state: ShellState, item: MediaItem, chatTitle: String) {
    DesktopTransferNotifier.raise()
    java.awt.EventQueue.invokeLater {
        if (chatTitle.isNotBlank()) state.noteChatTitle(item.chatId, chatTitle)
        state.openPlayer(item, startFromBeginning = false)
    }
}

private fun openFreeUp(state: ShellState) {
    DesktopTransferNotifier.raise()
    java.awt.EventQueue.invokeLater {
        state.go(Destination.Downloads)
        state.downloadsOpenFreeUp = true
    }
}

private const val TAG = "DesktopChecks"

/** About every 45 minutes, between the half hour and the hour the phone's three are stretched to. */
private const val EVERY_MS = 45L * 60 * 1000

/** Well after the start, which has the chat list to load first. */
private const val FIRST_AFTER_MS = 3L * 60 * 1000

private const val CONNECT_WAIT_MS = 60_000L
