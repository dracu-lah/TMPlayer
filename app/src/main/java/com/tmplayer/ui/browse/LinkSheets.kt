package com.tmplayer.ui.browse

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.tmplayer.ui.components.loadWhenShown
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.tmplayer.data.BookmarkJump
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.MediaItem
import com.tmplayer.data.PostOpen
import com.tmplayer.i18n.L
import com.tmplayer.ui.components.ChoiceLine
import com.tmplayer.ui.components.ChoiceList
import com.tmplayer.ui.components.ChoiceSheet
import com.tmplayer.ui.components.FocusOnOpen
import com.tmplayer.ui.components.MediaPreview
import com.tmplayer.ui.components.Spinner
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.launch

/**
 * The window's link flow on Android (CP79), on a phone and a television alike: whatever asked
 * for a link ([LinkRequests]: Search, a pinned post, another app's t.me or tg:// link) is opened
 * here. A post plays ([onPlay], from the link's start time) or opens its chat; an invite or a
 * public chat the account is not in is shown, and opened in Telegram itself, never joined here
 * (HX30).
 */
@Composable
internal fun TelegramLinkHost(onPlay: (MediaItem, String) -> Unit, onOpenChat: (ChatSummary) -> Unit) {
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val context = LocalContext.current
    val flow = remember {
        LinkFlow(
            scope = scope,
            onPlay = { play: PostOpen.Play ->
                if (play.startSec > 0) BookmarkJump.request(play.item.chatId, play.item.messageId, play.startSec * 1000L)
                onPlay(play.item, play.chat?.title.orEmpty())
            },
            onOpenChat = onOpenChat,
            say = toast,
            onOpenExternal = { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
        )
    }
    val asked by LinkRequests.asked.collectAsState()
    LaunchedEffect(asked) {
        if (asked == null) return@LaunchedEffect
        LinkRequests.take()?.let(flow::open)
    }
    val plays by LinkRequests.plays.collectAsState()
    LaunchedEffect(plays) {
        if (plays == null) return@LaunchedEffect
        LinkRequests.takePlay()?.let(flow::play)
    }
    val step by flow.step.collectAsState()
    LinkStepSheet(step ?: return, flow)
}

@Composable
private fun LinkStepSheet(step: LinkStep, flow: LinkFlow) {
    val s = LocalStrings.current
    val first = remember { FocusRequester() }
    when (step) {
        is LinkStep.Opening -> ChoiceSheet(title = s.linkOpening, onDismiss = flow::dismiss, onClose = flow::dismiss) {
            Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Spinner(size = 22.dp, strokeWidth = 2.dp)
                Spacer(Modifier.width(14.dp))
                Text(step.link.url.removePrefix("https://"), style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
            }
        }
        is LinkStep.Failed -> ChoiceSheet(title = s.linkOpening, note = step.message, onDismiss = flow::dismiss, onClose = flow::dismiss, closeFocus = first) {
            FocusOnOpen(first)
        }
        is LinkStep.Invite -> {
            val preview = step.preview
            val facts = listOfNotNull(
                (if (preview.isChannel) L.chatsKindChannels else L.chatsKindGroups),
                preview.memberCount.takeIf { it > 0 }?.let { if (preview.isChannel) s.chatsInfoSubscribers(it) else s.chatsInfoMembers(it) },
            ).joinToString("  ·  ")
            NotMemberSheet(
                title = preview.title,
                facts = facts,
                about = preview.description,
                photo = preview.bigPhotoFileId.takeIf { it != 0 } ?: preview.photoFileId,
                miniThumbnail = preview.miniThumbnail,
                note = when {
                    preview.paid -> s.linkInvitePaid
                    preview.createsJoinRequest -> s.linkRequestNote
                    else -> s.linkNotMember
                },
                onOpenTelegram = flow::openInTelegram,
                onLook = null,
                onDismiss = flow::dismiss,
            )
        }
        is LinkStep.Public -> {
            val info = step.info
            val channel = info.summary.kind == com.tmplayer.data.ChatKind.Channel
            val facts = listOfNotNull(
                info.summary.kind.label,
                info.memberCount?.let { if (channel) s.chatsInfoSubscribers(it) else s.chatsInfoMembers(it) },
                info.username?.let { "@$it" },
            ).joinToString("  ·  ")
            NotMemberSheet(
                title = info.summary.title,
                facts = facts,
                about = info.about.orEmpty(),
                photo = info.bigPhotoFileId.takeIf { it != 0 } ?: info.summary.photoFileId,
                miniThumbnail = info.summary.miniThumbnail,
                note = if (info.joinByRequest) s.linkRequestNote else s.linkNotMember,
                onOpenTelegram = flow::openInTelegram,
                onLook = flow::look,
                onDismiss = flow::dismiss,
            )
        }
    }
}

/**
 * A chat the account is not in (CP79, HX30): its picture, name, facts and description, then
 * Open in Telegram. This app never joins it; only Telegram does.
 */
@Composable
private fun NotMemberSheet(
    title: String,
    facts: String,
    about: String,
    photo: Int,
    miniThumbnail: ByteArray?,
    note: String,
    onOpenTelegram: () -> Unit,
    onLook: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    val first = remember { FocusRequester() }
    ChoiceSheet(title = title, note = note, onDismiss = onDismiss, onClose = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            MediaPreview(miniThumbnail = miniThumbnail, thumbnailFileId = photo, fallbackLabel = title, modifier = Modifier.size(64.dp).clip(CircleShape))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (facts.isNotBlank()) Text(facts, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
                if (about.isNotBlank()) Text(about, style = MaterialTheme.typography.bodyMedium, color = Tone.text, maxLines = 6)
            }
        }
        ChoiceList {
            ChoiceLine(
                s.linkOpenInTelegram,
                modifier = Modifier.focusRequester(first),
                detail = s.linkOpenInTelegramDetail,
                icon = TmIcons.OpenExternal,
                onClick = onOpenTelegram,
            )
            if (onLook != null) {
                ChoiceLine(s.linkLookFirst, detail = s.linkLookFirstDetail, icon = TmIcons.ArrowForward, onClick = onLook)
            }
        }
        FocusOnOpen(first)
    }
}

/**
 * Videos listed from somewhere other than the chat's grid (CP81): a chat's round videos or the
 * comments under a post. A press plays one and leaves the list open under the player; a failed or
 * slow page says so with Try again.
 */
@Composable
internal fun MoreVideosSheet(
    title: String,
    note: String?,
    list: PagedVideos,
    empty: String,
    round: Boolean,
    onPlay: (MediaItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    val state by list.state.collectAsState()
    val first = remember { FocusRequester() }
    ChoiceSheet(title = title, note = note, onDismiss = onDismiss, onClose = onDismiss, fullScreen = true) {
        ChoiceList {
            state.items.forEachIndexed { at, item ->
                val detail = listOfNotNull(
                    s.formatter.duration(item.durationSec.toLong()).takeIf { item.durationSec > 0 },
                    s.formatter.size(item.sizeBytes).takeIf { item.sizeBytes > 0 },
                ).joinToString("  ·  ")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MediaPreview(
                        miniThumbnail = item.miniThumbnail,
                        thumbnailFileId = item.thumbnailFileId,
                        fallbackLabel = item.title,
                        // Round videos are drawn as Telegram draws them, in a circle.
                        modifier = Modifier.padding(start = 8.dp).size(48.dp).clip(if (round) CircleShape else MaterialTheme.shapes.small),
                    )
                    ChoiceLine(
                        item.title,
                        modifier = (if (at == 0) Modifier.focusRequester(first) else Modifier).weight(1f),
                        detail = detail.ifBlank { null },
                        icon = TmIcons.Play,
                    ) {
                        // The list stays open under the player, so Back from the video comes
                        // back to the line it was played from; [onPlay] closes it where it should go.
                        onPlay(item)
                    }
                }
            }
            when {
                state.loading -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spinner(size = 18.dp, strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(s.commonLoading, color = Tone.muted)
                }
                state.failure != null && !state.done -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(state.failure.orEmpty(), color = Tone.muted, modifier = Modifier.weight(1f))
                    com.tmplayer.ui.components.TmButton(onClick = list::loadMore) { Text(s.commonTryAgain) }
                }
                state.failure != null -> Text(state.failure.orEmpty(), color = Tone.muted, modifier = Modifier.padding(16.dp))
                !state.done -> ChoiceLine(
                    if (round) s.gridAudioMore else s.detailCommentsMore,
                    icon = TmIcons.Add,
                    // The next page as the foot comes into view, as a grid reads on.
                    modifier = (if (state.items.isEmpty()) Modifier.focusRequester(first) else Modifier)
                        .loadWhenShown(list::loadMore),
                ) { list.loadMore() }
                state.items.isEmpty() -> Text(empty, color = Tone.muted, modifier = Modifier.padding(16.dp))
            }
        }
        FocusOnOpen(first, key = state.items.isNotEmpty() || !state.done)
    }
}

/** The way into a chat's round videos over its videos: how many, and the list (CP81). */
@Composable
internal fun RoundEntry(count: Int, noVideos: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    Column(modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        if (noVideos) {
            Text(s.browseNoPlayableVideos, style = MaterialTheme.typography.titleMedium, color = Tone.text, modifier = Modifier.padding(bottom = 4.dp))
        }
        com.tmplayer.ui.components.TvChoiceRow(title = s.gridRoundTitle, detail = s.gridRoundCount(count), icon = TmIcons.PlayCircle, onClick = onOpen)
    }
}

/**
 * A chat's pinned post at the top of its videos (CP81), when it is a video (played from here) or
 * links to one (opened through the link flow).
 */
@Composable
internal fun PinnedEntry(pinned: com.tmplayer.data.PinnedPost, onPlay: (MediaItem) -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val video = pinned.video
    Column(modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        com.tmplayer.ui.components.TvChoiceRow(
            title = if (video != null) video.title else s.gridPinnedOpen,
            detail = listOfNotNull(s.gridPinnedLabel, if (video == null) s.gridPinnedLinkDetail else pinned.text.takeIf { it != video.title && it.isNotBlank() })
                .joinToString("  ·  "),
            icon = TmIcons.Pin,
            onClick = {
                if (video != null) onPlay(video) else pinned.link?.let(LinkRequests::ask)
            },
        )
    }
}

