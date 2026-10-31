package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.tmplayer.ui.components.loadWhenShown
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.platform.Background
import com.tmplayer.data.BookmarkJump
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.MediaItem
import com.tmplayer.data.PinnedPost
import com.tmplayer.data.PostOpen
import com.tmplayer.i18n.L
import com.tmplayer.ui.browse.LinkFlow
import com.tmplayer.ui.browse.LinkRequests
import com.tmplayer.ui.browse.LinkStep
import com.tmplayer.ui.browse.PagedVideos
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.launch

/**
 * The desktop's link flow (CP79): a link pasted into the search palette (or a pinned post's link)
 * is opened here. A post plays from its start time or opens its chat; an invite or a public chat
 * the account is not in is shown in a card, and opened in Telegram itself, never joined here
 * (HX30). Videos asked for off a chat's grid (comments, round videos) play through here too.
 */
@Composable
fun DesktopLinkHost(state: ShellState) {
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val flow = remember {
        LinkFlow(
            scope = scope,
            onPlay = { play: PostOpen.Play ->
                if (play.startSec > 0) BookmarkJump.request(play.item.chatId, play.item.messageId, play.startSec * 1000L)
                play.chat?.let { state.noteChatTitle(it.id, it.title) }
                state.openPlayer(play.item, startFromBeginning = false)
            },
            onOpenChat = { chat -> state.openChat(chat) },
            say = toast,
            onOpenExternal = com.tmplayer.desktop.os.OpenExternal::browse,
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
    val shown = step ?: return
    DesktopSheet(flow::dismiss) { LinkStepCard(shown, flow) }
}

@Composable
internal fun LinkStepCard(step: LinkStep, flow: LinkFlow) {
    val s = LocalStrings.current
    val joinFocus = remember { FocusRequester() }
    Column(Modifier.fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when (step) {
            is LinkStep.Opening -> {
                Text(s.linkOpening, style = MaterialTheme.typography.headlineSmall)
                CircularProgressIndicator(Modifier.size(28.dp))
                Text(step.link.url.removePrefix("https://"), color = Tone.muted)
                TextButton(onClick = flow::dismiss) { Text(s.commonClose) }
            }
            is LinkStep.Failed -> {
                Text(s.linkOpening, style = MaterialTheme.typography.headlineSmall)
                Text(step.message, color = Tone.text)
                TextButton(onClick = flow::dismiss) { Text(s.commonClose) }
            }
            is LinkStep.Invite -> {
                val p = step.preview
                ChatAvatar(p.miniThumbnail, p.bigPhotoFileId.takeIf { it != 0 } ?: p.photoFileId, p.title, 96.dp)
                Text(p.title, style = MaterialTheme.typography.headlineSmall, color = Tone.text)
                val facts = listOfNotNull(
                    if (p.isChannel) L.chatsKindChannels else L.chatsKindGroups,
                    p.memberCount.takeIf { it > 0 }?.let { if (p.isChannel) s.chatsInfoSubscribers(it) else s.chatsInfoMembers(it) },
                ).joinToString("  ·  ")
                Text(facts, color = Tone.muted)
                if (p.description.isNotBlank()) Text(p.description, color = Tone.text, maxLines = 8, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        p.paid -> s.linkInvitePaid
                        p.createsJoinRequest -> s.linkRequestNote
                        else -> s.linkNotMember
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Tone.muted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OpenInTelegramButton(onOpen = flow::openInTelegram, modifier = Modifier.focusRequester(joinFocus))
                    TextButton(onClick = flow::dismiss) { Text(s.commonClose) }
                }
                LaunchedEffect(Unit) { runCatching { joinFocus.requestFocus() } }
            }
            is LinkStep.Public -> {
                val info = step.info
                val channel = info.summary.kind == ChatKind.Channel
                ChatAvatar(info.summary.miniThumbnail, info.bigPhotoFileId.takeIf { it != 0 } ?: info.summary.photoFileId, info.summary.title, 96.dp)
                Text(info.summary.title, style = MaterialTheme.typography.headlineSmall, color = Tone.text)
                val facts = listOfNotNull(
                    info.summary.kind.label,
                    info.memberCount?.let { if (channel) s.chatsInfoSubscribers(it) else s.chatsInfoMembers(it) },
                    info.username?.let { "@$it" },
                ).joinToString("  ·  ")
                Text(facts, color = Tone.muted)
                info.about?.let { Text(it, color = Tone.text, maxLines = 8, overflow = TextOverflow.Ellipsis) }
                Text(if (info.joinByRequest) s.linkRequestNote else s.linkNotMember, style = MaterialTheme.typography.bodySmall, color = Tone.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OpenInTelegramButton(onOpen = flow::openInTelegram, modifier = Modifier.focusRequester(joinFocus))
                    OutlinedButton(onClick = flow::look) { Text(s.linkLookFirst) }
                    TextButton(onClick = flow::dismiss) { Text(s.commonClose) }
                }
                LaunchedEffect(Unit) { runCatching { joinFocus.requestFocus() } }
            }
        }
    }
}

/** Opens the chat in Telegram itself (HX30): this app never joins it. */
@Composable
internal fun OpenInTelegramButton(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    Button(onClick = onOpen, modifier = modifier.pointerHoverIcon(PointerIcon.Hand)) {
        Icon(TmIcons.OpenExternal, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(s.linkOpenInTelegram)
    }
}

/**
 * Videos listed from somewhere other than the chat's grid (CP81), in a card over the window: a
 * chat's round videos (drawn in circles) or the videos in a post's comments. A click plays one.
 */
@Composable
internal fun MoreVideosDialog(
    title: String,
    note: String?,
    list: PagedVideos,
    empty: String,
    round: Boolean,
    onPlay: (MediaItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    val shown by list.state.collectAsState()
    DesktopSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            note?.let { Text(it, color = Tone.muted) }
            Spacer(Modifier.height8())
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                shown.items.forEach { item ->
                    val detail = listOfNotNull(
                        s.messages.formatter.duration(item.durationSec.toLong()).takeIf { item.durationSec > 0 },
                        s.messages.formatter.size(item.sizeBytes).takeIf { item.sizeBytes > 0 },
                    ).joinToString("  ·  ")
                    Surface(
                        onClick = { onDismiss(); onPlay(item) },
                        shape = MaterialTheme.shapes.small,
                        color = Color.Transparent,
                        modifier = Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            com.tmplayer.ui.components.MediaArt(
                                item.miniThumbnail,
                                item.thumbnailFileId,
                                Modifier.size(48.dp).clip(if (round) CircleShape else MaterialTheme.shapes.small),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (detail.isNotBlank()) Text(detail, color = Tone.muted, style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(TmIcons.Play, contentDescription = null, tint = Tone.muted, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                when {
                    shown.loading -> CircularProgressIndicator(Modifier.padding(16.dp).size(24.dp))
                    shown.failure != null && !shown.done -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(shown.failure.orEmpty(), color = Tone.muted, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = list::loadMore) { Text(s.commonTryAgain) }
                    }
                    shown.failure != null -> Text(shown.failure.orEmpty(), color = Tone.muted, modifier = Modifier.padding(vertical = 8.dp))
                    !shown.done -> TextButton(onClick = list::loadMore, modifier = Modifier.loadWhenShown(list::loadMore)) { Text(if (round) s.gridAudioMore else s.detailCommentsMore) }
                    shown.items.isEmpty() -> Text(empty, color = Tone.muted, modifier = Modifier.padding(vertical = 8.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(s.commonClose) }
            }
        }
    }
}

private fun Modifier.height8(): Modifier = this.then(Modifier.padding(top = 8.dp))

/** The way into a chat's round videos over its posters (CP81). */
@Composable
internal fun RoundEntryRow(count: Int, noVideos: Boolean, onOpen: () -> Unit) {
    val s = LocalStrings.current
    ExtraEntryRow(TmIcons.PlayCircle, s.gridRoundTitle, s.gridRoundCount(count), noVideos, onOpen)
}

/** A chat's pinned post over its posters, when it is or links to a video (CP81). */
@Composable
internal fun PinnedEntryRow(pinned: PinnedPost, noVideos: Boolean, onPlay: (MediaItem) -> Unit) {
    val s = LocalStrings.current
    val video = pinned.video
    ExtraEntryRow(
        TmIcons.Pin,
        video?.title ?: s.gridPinnedOpen,
        listOfNotNull(s.gridPinnedLabel, if (video == null) s.gridPinnedLinkDetail else pinned.text.takeIf { it.isNotBlank() && it != video.title })
            .joinToString("  ·  "),
        noVideos,
    ) { if (video != null) onPlay(video) else pinned.link?.let(LinkRequests::ask) }
}

@Composable
private fun ExtraEntryRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, noVideos: Boolean, onOpen: () -> Unit) {
    val s = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (noVideos) Text(s.browseNoPlayableVideos, style = MaterialTheme.typography.titleMedium)
        Surface(onClick = onOpen, shape = MaterialTheme.shapes.medium, color = Tone.surface, modifier = Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = Tone.accent, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(detail, color = Tone.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(TmIcons.ChevronRight, contentDescription = null, tint = Tone.muted)
            }
        }
    }
}
