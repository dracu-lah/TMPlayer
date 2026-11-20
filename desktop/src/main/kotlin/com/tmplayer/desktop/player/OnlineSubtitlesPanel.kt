package com.tmplayer.desktop.player

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tmplayer.desktop.os.OpenExternal
import com.tmplayer.online.DownloadResult
import com.tmplayer.online.OnlineSubtitles
import com.tmplayer.online.OnlineWords
import com.tmplayer.online.SearchResult
import com.tmplayer.online.SubtitleHit
import com.tmplayer.online.SubtitleNotice
import com.tmplayer.online.SubtitleProvider
import com.tmplayer.online.SubtitleTarget
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import kotlinx.coroutines.launch
import java.io.File

/**
 * The subtitle menu's "Search online" on the desktop: a panel over the picture, beside where the
 * details panel sits, listing what OpenSubtitles (and SubDL, with the viewer's key) has for the
 * video. A click downloads one and hands it to mpv, where the delay and the look apply as they do
 * to any other track. What blocks a download is the first line, and the rows it blocks are dimmed.
 *
 * With [msone] it runs MSone's search instead: MSone's state at the gateway (not set up, paused
 * or stopped at its request) is the first line, with the gateway's own words, Check again and
 * [onPickFile] under it, and each hit credits MSone and its translator with links to their pages.
 */
@Composable
internal fun BoxScope.OnlineSubtitlesPanel(
    target: suspend () -> SubtitleTarget?,
    onLoaded: (File, String) -> Unit,
    onClose: () -> Unit,
    /** The hit that was downloaded and loaded, for the player to remember per video (HX25). */
    onPicked: (com.tmplayer.online.SubtitleHit, File) -> Unit = { _, _ -> },
    onPickFile: () -> Unit = {},
    msone: Boolean = false,
    /** Search the viewer's own SubMaker: ready subtitles, and "Make <Language>" entries that run as [onTranslate] jobs. */
    submaker: Boolean = false,
    /** Starts the translation of a "Make <Language>" hit in the background; false when one runs for it already. */
    onTranslate: (SubtitleHit, SubtitleTarget) -> Boolean = { _, _ -> false },
    /** For the render test: a result to show without searching. */
    preset: SearchResult? = null,
) {
    val s = LocalStrings.current
    val online = OnlineSubtitles.current ?: return
    val jobs by online.translations.states.collectAsState()
    // The language whose first translation is waiting for the viewer's yes (it spends their own AI quota).
    var asking by remember { mutableStateOf<SubtitleHit?>(null) }
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf(preset) }
    var notice by remember { mutableStateOf<SubtitleNotice?>(null) }
    var wanted by remember { mutableStateOf<SubtitleTarget?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<SubtitleHit?>(null) }
    // Check again asks the gateway even inside the six hours between checks.
    var force by remember { mutableStateOf(false) }

    LaunchedEffect(attempt) {
        if (preset != null && attempt == 0) return@LaunchedEffect
        result = null
        notice = null
        val t = target()
        wanted = t
        if (msone && force) online.msoneStatus(force = true)
        force = false
        result = when {
            t == null -> SearchResult(emptyList())
            msone -> online.searchMsone(t)
            submaker -> online.searchSubMaker(t)
            else -> online.search(t)
        }
    }

    Surface(
        Modifier.align(Alignment.TopEnd).padding(top = 72.dp, end = 16.dp).width(460.dp).heightIn(max = 560.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xE61C1C1E),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Whose list this is: the service's logo, drawn only, so the title beside it is read once.
                com.tmplayer.ui.components.ServiceBadge(
                    if (msone) com.tmplayer.ui.components.Service.MSone
                    else if (submaker) com.tmplayer.ui.components.Service.SubMaker
                    else com.tmplayer.ui.components.Service.OpenSubtitles,
                    modifier = Modifier.padding(end = 10.dp),
                    size = com.tmplayer.ui.components.ServiceBadgeSize.Line,
                )
                Text(if (msone) s.msoneTitle else if (submaker) s.submakerTitle else s.onlineResultsTitle, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                OverlayButton(TmIcons.Close, s.commonClose, onClose, size = 32)
            }
            Spacer(Modifier.height(8.dp))
            val shown = result
            if (shown == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(if (msone) s.msoneSearching else if (submaker) s.submakerSearching else s.onlineSearching, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                }
                // A search that runs long says so, with Try again, rather than spinning in silence.
                var slow by remember(attempt) { mutableStateOf(false) }
                LaunchedEffect(attempt) {
                    kotlinx.coroutines.delay(com.tmplayer.ui.components.SlowAnswer.AFTER_MS)
                    slow = true
                }
                if (slow) {
                    Spacer(Modifier.height(8.dp))
                    Text(com.tmplayer.ui.components.SlowAnswer.MESSAGE, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                    TextButton(onClick = { attempt++ }) { Text(s.commonTryAgain) }
                }
                return@Column
            }
            val reason = notice ?: shown.notice
            if (reason != null) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0x33FFFFFF)).padding(10.dp)) {
                    Text(OnlineWords.notice(reason), color = Color.White, fontSize = 13.sp)
                    // The gateway's own words, as it gave them (MSone's, quoted by the operator).
                    OnlineWords.message(reason)?.let {
                        Text(it, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            if (shown.hits.isEmpty() && reason == null) {
                Text(if (msone) s.msoneNoResults else s.onlineNoResults, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
            }
            Row {
                if (reason is SubtitleNotice.MsoneOff) {
                    TextButton(onClick = { force = true; attempt++ }) { Text(s.msoneCheckAgain) }
                } else if (reason == SubtitleNotice.Offline || reason == SubtitleNotice.Busy || reason == SubtitleNotice.MsoneBusy || reason == SubtitleNotice.SubMakerBusy) {
                    TextButton(onClick = { attempt++ }) { Text(s.commonTryAgain) }
                }
                // MSone that cannot search, or found nothing: a subtitle file of the viewer's own.
                if (msone && shown.hits.isEmpty()) TextButton(onClick = onPickFile) { Text(s.msonePickFile) }
            }
            fun begin(hit: SubtitleHit) {
                val t = wanted ?: return
                online.translations.clear(online.translationKey(t.videoKey, hit))
                onTranslate(hit, t)
            }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                asking?.let { question ->
                    // The first translation into a language says it spends the viewer's own AI quota.
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0x33FFFFFF)).padding(10.dp)) {
                        Text(s.submakerAskTitle(question.translateTo), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(s.submakerAskMessage, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                        Row {
                            TextButton(onClick = {
                                online.markSubMakerAsked(question.translateTo)
                                asking = null
                                begin(question)
                            }) { Text(s.submakerAskConfirm) }
                            TextButton(onClick = { asking = null }) { Text(s.commonCancel) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                shown.hits.forEach { hit ->
                    val jobKey = wanted?.let { online.translationKey(it.videoKey, hit) }
                    val job = if (hit.translateTo.isNotEmpty() && jobKey != null) jobs[jobKey] else null
                    if (job != null) {
                        // The progress line, with the control that ends a running job or tries a stopped one again.
                        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                            Text(OnlineWords.translation(hit.translateTo, job), color = Color.White, fontSize = 14.sp, maxLines = 2)
                            when {
                                job.active -> TextButton(onClick = { online.translations.cancel(jobKey!!) }) { Text(s.submakerCancel) }
                                job is com.tmplayer.online.TranslationState.Failed -> TextButton(onClick = { begin(hit) }) { Text(s.commonTryAgain) }
                                else -> TextButton(onClick = { online.translations.clear(jobKey!!) }) { Text(s.commonOk) }
                            }
                        }
                        return@forEach
                    }
                    val enabled = online.blockedFor(hit) == null && busy == null
                    val hitInteractions = remember { MutableInteractionSource() }
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .overlayFocusRing(hitInteractions, RoundedCornerShape(8.dp))
                            .clickable(interactionSource = hitInteractions, indication = LocalIndication.current, enabled = enabled, role = Role.Button) {
                                if (hit.translateTo.isNotEmpty()) {
                                    if (online.submakerAsked(hit.translateTo)) begin(hit) else asking = hit
                                    return@clickable
                                }
                                busy = hit
                                scope.launch {
                                    when (val done = online.download(hit, wanted)) {
                                        is DownloadResult.Done -> {
                                            onLoaded(done.file, done.label)
                                            onPicked(hit, done.file)
                                            onClose()
                                        }
                                        is DownloadResult.Failed -> notice = done.notice
                                    }
                                    busy = null
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        val alpha = if (online.blockedFor(hit) == null) 1f else 0.4f
                        Text(
                            OnlineWords.hitTitle(hit),
                            color = Color.White.copy(alpha = alpha),
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (busy == hit) s.onlineDownloading else OnlineWords.hitDetail(hit),
                            color = Color.White.copy(alpha = 0.6f * alpha),
                            fontSize = 12.sp,
                            maxLines = 2,
                        )
                        if (hit.provider == SubtitleProvider.Msone && (hit.pageUrl.isNotEmpty() || hit.translatorUrl.isNotEmpty())) {
                            Row {
                                if (hit.pageUrl.isNotEmpty()) {
                                    TextButton(onClick = { OpenExternal.browse(hit.pageUrl) }) { Text(s.msoneReleasePage, fontSize = 12.sp) }
                                }
                                if (hit.translatorUrl.isNotEmpty()) {
                                    TextButton(onClick = { OpenExternal.browse(hit.translatorUrl) }) { Text(s.msoneTranslator(hit.translator), fontSize = 12.sp) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
