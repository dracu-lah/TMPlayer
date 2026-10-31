package com.tmplayer.ui.browse

import com.tmplayer.data.ChatInfo
import com.tmplayer.data.ChatRepository
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.Failures
import com.tmplayer.data.InvitePreview
import com.tmplayer.data.KidsLock
import com.tmplayer.data.ChatGate
import com.tmplayer.data.LinkRules
import com.tmplayer.data.PostOpen
import com.tmplayer.data.Td
import com.tmplayer.data.TelegramLinkActions
import com.tmplayer.data.TgLink
import com.tmplayer.i18n.L
import com.tmplayer.ui.components.SlowAnswer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A Telegram link somebody asked to open (CP79), from wherever it came: pasted into Search, a
 * pinned post's link, or another app handing TMPlayer a t.me or tg:// address. The window's
 * [LinkFlow] picks it up and takes it from there, so every way in ends the same way.
 */
object LinkRequests {
    private val _asked = MutableStateFlow<TgLink?>(null)
    val asked: StateFlow<TgLink?> = _asked.asStateFlow()

    fun ask(link: TgLink) {
        _asked.value = link
    }

    /** Takes the request, once: the flow that acts on it clears it. */
    fun take(): TgLink? = _asked.value.also { _asked.value = null }

    /** A video found off the chat's grid (a comment, a round video) to play through the window's own path. */
    data class PlayAsk(val item: com.tmplayer.data.MediaItem, val chatTitle: String)

    private val _plays = MutableStateFlow<PlayAsk?>(null)
    val plays: StateFlow<PlayAsk?> = _plays.asStateFlow()

    /** Asks the window to play [item]; a blank [chatTitle] is looked up from its chat. */
    fun play(item: com.tmplayer.data.MediaItem, chatTitle: String = "") {
        _plays.value = PlayAsk(item, chatTitle)
    }

    fun takePlay(): PlayAsk? = _plays.value.also { _plays.value = null }
}

/** Where opening a link has got to, for the platform's sheet to draw. */
sealed interface LinkStep {
    /** Telegram is being asked about [link]. */
    data class Opening(val link: TgLink) : LinkStep

    /** An invite's chat, not already joined (HX30): opened in Telegram, never joined in-app. */
    data class Invite(val preview: InvitePreview) : LinkStep

    /** A public group or channel the account is not in (HX30): opened in Telegram, not joined here. */
    data class Public(val info: ChatInfo) : LinkStep

    /** It could not be opened, for [message]. */
    data class Failed(val message: String) : LinkStep
}

/**
 * The link flow itself: resolves a post and plays it or opens its chat, or previews an invite or
 * a public chat the account is not in. Neither is ever joined here (HX30): the sheet offers to
 * open the chat in Telegram instead, through [onOpenExternal]. [onPlay] and [onOpenChat] are the
 * platform's own (the player, the chat's grid); [say] its toast.
 */
class LinkFlow(
    private val scope: CoroutineScope,
    private val onPlay: (PostOpen.Play) -> Unit,
    private val onOpenChat: (ChatSummary) -> Unit,
    private val say: (String) -> Unit,
    private val onOpenExternal: (String) -> Unit = {},
) {
    private val _step = MutableStateFlow<LinkStep?>(null)
    val step: StateFlow<LinkStep?> = _step.asStateFlow()
    private var job: Job? = null

    fun open(link: TgLink) {
        job?.cancel()
        _step.value = LinkStep.Opening(link)
        job = scope.launch {
            try {
                val answer = withTimeoutOrNull(TIMEOUT_MS) {
                    val session = Td.awaitConnectedSessionOrNull() ?: return@withTimeoutOrNull LinkStep.Failed(Failures.OFFLINE)
                    resolve(TelegramLinkActions(session.client), session.client, link)
                } ?: LinkStep.Failed(SlowAnswer.MESSAGE)
                _step.value = answer
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _step.value = LinkStep.Failed(Failures.humanise(e))
            }
        }
    }

    /** The answer for [link]: a step to show, or null once it has been acted on. */
    private suspend fun resolve(actions: TelegramLinkActions, td: dev.g000sha256.tdl.TdlClient, link: TgLink): LinkStep? = when (link) {
        is TgLink.Post -> when (val open = actions.openPost(link)) {
            is PostOpen.Play -> {
                onPlay(open)
                null
            }
            is PostOpen.OpenChat -> {
                onOpenChat(open.chat)
                open.note?.let(say)
                null
            }
            is PostOpen.Refused -> LinkStep.Failed(open.reason)
        }
        is TgLink.Invite -> actions.previewInvite(link).fold(
            onSuccess = { preview ->
                val chat = if (preview.alreadyMember) ChatRepository(td).summary(preview.chatId) else null
                if (chat != null && KidsLock.allows(ChatGate.awaitReady(), chat.id)) {
                    onOpenChat(chat)
                    say(L.linkAlreadyMember)
                    null
                } else {
                    LinkStep.Invite(preview)
                }
            },
            onFailure = { LinkStep.Failed(it.message ?: Failures.DEFAULT) },
        )
        is TgLink.Public -> {
            val repository = ChatRepository(td)
            val chat = repository.findByUsername(link.username)
            val info = chat?.let { repository.chatInfo(it.id) }
            when {
                chat == null -> LinkStep.Failed(L.linkNobody(link.username))
                info == null || !info.canJoin -> {
                    onOpenChat(chat)
                    null
                }
                else -> LinkStep.Public(info)
            }
        }
    }

    /** Opens the chat on show in Telegram itself (HX30): this app never joins it. */
    fun openInTelegram() {
        val shown = _step.value
        val url = when (shown) {
            is LinkStep.Invite -> shown.preview.link.url
            is LinkStep.Public -> shown.info.link ?: return
            else -> return
        }
        _step.value = null
        onOpenExternal(url)
    }

    /** A public chat on show, opened to look at without joining. */
    fun look() {
        val public = _step.value as? LinkStep.Public ?: return
        _step.value = null
        onOpenChat(public.info.summary)
    }

    /** Plays a video asked for through [LinkRequests.play], naming its chat when the asker could not. */
    fun play(ask: LinkRequests.PlayAsk) {
        scope.launch {
            if (!KidsLock.allows(ChatGate.awaitReady(), ask.item.chatId)) {
                say(L.linkKidsLock)
                return@launch
            }
            val chat = if (ask.chatTitle.isNotBlank()) null else {
                Td.awaitConnectedSessionOrNull(QUICK_MS)?.let { runCatching { ChatRepository(it.client).summary(ask.item.chatId) }.getOrNull() }
            }
            onPlay(PostOpen.Play(ask.item, chat ?: ask.chatTitle.takeIf { it.isNotBlank() }?.let { titled(ask.item.chatId, it) }, 0))
        }
    }

    /** A stand-in for the chat, enough to name it. */
    private fun titled(chatId: Long, title: String) =
        ChatSummary(id = chatId, title = title, miniThumbnail = null, photoFileId = 0, kind = com.tmplayer.data.ChatKind.Channel)

    fun dismiss() {
        job?.cancel()
        _step.value = null
    }

    private companion object {
        const val TIMEOUT_MS = 25_000L
        const val QUICK_MS = 3_000L
    }
}

/** Whether a chat not already joined may be previewed at all: never under the kids lock. */
suspend fun mayJoinChats(): Boolean = LinkRules.mayJoin(ChatGate.awaitReady())
