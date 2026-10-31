package com.tmplayer.ui.browse

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tmplayer.data.ChatInfo
import com.tmplayer.data.ChatKind
import com.tmplayer.i18n.L
import com.tmplayer.ui.components.ChoiceSheet
import com.tmplayer.ui.components.MediaPreview
import com.tmplayer.ui.components.PhoneSheet
import com.tmplayer.ui.components.TmSecondaryButton
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.components.showMessage
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Tone

/**
 * A chat's info: its large picture, its name, what kind of chat it is and how many members or
 * subscribers it has, the public @username, and a person's bio or a group's or channel's
 * description. Opened from the chat's name at the top of its videos (user, 2026-10-18).
 *
 * A phone gets a bottom sheet, half open and pulled up for a long description; the username and
 * the description can be copied, and a public chat has Share and Copy link for its t.me address.
 * A television gets the same in a floating panel, without the copying a remote cannot use.
 * [title], [photoFileId] and [miniThumbnail] draw the top straight away while the rest is asked for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatInfoSheet(
    chatId: Long,
    title: String,
    photoFileId: Int,
    miniThumbnail: ByteArray?,
    onDismiss: () -> Unit,
) {
    val load = rememberChatInfo(chatId)
    val info = load.info
    if (isTouch()) {
        PhoneSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 20.dp)
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ChatInfoBody(info, title, photoFileId, miniThumbnail, touch = true)
                JoinChatButton(info)
                ChatInfoFailed(load)
            }
        }
    } else {
        ChoiceSheet(title = info?.summary?.title ?: title, onDismiss = onDismiss, onClose = onDismiss) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ChatInfoBody(info, title, photoFileId, miniThumbnail, touch = false)
                JoinChatButton(info)
                ChatInfoFailed(load)
            }
        }
    }
}

/**
 * Open in Telegram, for a public group or channel seen through its @username and not yet joined
 * (CP79). This app never joins it (HX30): Telegram does, once the viewer presses this.
 */
@Composable
private fun JoinChatButton(info: ChatInfo?) {
    if (info == null || !info.canJoin) return
    val s = LocalStrings.current
    val context = LocalContext.current
    val link = info.link ?: return
    com.tmplayer.ui.components.TmButton(
        onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        },
    ) {
        Icon(TmIcons.OpenExternal, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(s.linkOpenInTelegram)
    }
}

/** The rest could not be loaded: said, with Try again, so a failure does not pass for a wait. */
@Composable
private fun ChatInfoFailed(load: ChatInfoLoad) {
    if (!load.failed) return
    val s = LocalStrings.current
    Text(s.chatsInfoFailed, style = MaterialTheme.typography.bodyMedium, color = Tone.muted, textAlign = TextAlign.Center)
    com.tmplayer.ui.components.TmButton(onClick = load.retry) { Text(s.commonTryAgain) }
}

@Composable
private fun ChatInfoBody(info: ChatInfo?, title: String, photoFileId: Int, miniThumbnail: ByteArray?, touch: Boolean) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val name = info?.summary?.title ?: title
    MediaPreview(
        miniThumbnail = miniThumbnail,
        // The large picture once it is known; the small one the list already has until then.
        thumbnailFileId = info?.bigPhotoFileId?.takeIf { it != 0 } ?: photoFileId,
        fallbackLabel = name,
        modifier = Modifier.size(if (touch) 96.dp else 120.dp).clip(CircleShape),
    )
    if (touch) {
        Text(name, style = MaterialTheme.typography.headlineSmall, color = Tone.text, textAlign = TextAlign.Center)
    }
    info?.let { facts(it) }?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = Tone.muted, textAlign = if (touch) TextAlign.Center else TextAlign.Start)
    }
    info?.username?.let { username ->
        Row(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .then(if (touch) Modifier.clickable(onClickLabel = s.chatsInfoCopyUsername) { copy(context, "@$username", L.chatsInfoUsernameCopied) } else Modifier)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("@$username", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
    info?.about?.let { about ->
        // Selectable, so a link or a line of it can be copied out.
        SelectionContainer {
            Text(about, style = MaterialTheme.typography.bodyLarge, color = Tone.text, textAlign = if (touch) TextAlign.Center else TextAlign.Start)
        }
    }
    val link = info?.link
    if (link != null) {
        if (touch) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TmSecondaryButton(onClick = { share(context, link) }) {
                    Icon(TmIcons.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(s.commonShare)
                }
                TmSecondaryButton(onClick = { copy(context, link, L.commonLinkCopied) }) { Text(s.commonCopyLink) }
            }
        } else {
            Text(link.removePrefix("https://"), style = MaterialTheme.typography.bodyLarge, color = Tone.muted)
        }
    }
}

/** "Channel · 12,400 subscribers", "Group · 31 members", or the kind alone. */
private fun facts(info: ChatInfo): String? {
    val kind = info.summary.kind
    val count = info.memberCount?.let { if (kind == ChatKind.Channel) L.chatsInfoSubscribers(it) else L.chatsInfoMembers(it) }
    return listOfNotNull(kind.label.takeIf { kind != ChatKind.Saved }, count).joinToString("  ·  ").ifBlank { null }
}

private fun copy(context: Context, text: String, said: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    runCatching { clipboard?.setPrimaryClip(ClipData.newPlainText("Telegram", text)) }
    showMessage(context, said)
}

private fun share(context: Context, link: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
    runCatching { context.startActivity(Intent.createChooser(send, L.commonShare).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { showMessage(context, L.gridNoAppOpens) }
}
