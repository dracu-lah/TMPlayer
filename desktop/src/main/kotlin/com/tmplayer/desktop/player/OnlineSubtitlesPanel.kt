package com.tmplayer.desktop.player

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
import com.tmplayer.online.DownloadResult
import com.tmplayer.online.OnlineSubtitles
import com.tmplayer.online.OnlineWords
import com.tmplayer.online.SearchResult
import com.tmplayer.online.SubtitleHit
import com.tmplayer.online.SubtitleNotice
import com.tmplayer.online.SubtitleTarget
import com.tmplayer.ui.i18n.LocalStrings
import kotlinx.coroutines.launch
import java.io.File

/**
 * The subtitle menu's "Search online" on the desktop: a panel over the picture, beside where the
 * details panel sits, listing what OpenSubtitles (and SubDL, with the viewer's key) has for the
 * video. A click downloads one and hands it to mpv, where the delay and the look apply as they do
 * to any other track. What blocks a download is the first line, and the rows it blocks are dimmed.
 */
@Composable
internal fun BoxScope.OnlineSubtitlesPanel(
    target: suspend () -> SubtitleTarget?,
    onLoaded: (File, String) -> Unit,
    onClose: () -> Unit,
    /** For the render test: a result to show without searching. */
    preset: SearchResult? = null,
) {
    val s = LocalStrings.current
    val online = OnlineSubtitles.current ?: return
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf(preset) }
    var notice by remember { mutableStateOf<SubtitleNotice?>(null) }
    var wanted by remember { mutableStateOf<SubtitleTarget?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<SubtitleHit?>(null) }

    LaunchedEffect(attempt) {
        if (preset != null && attempt == 0) return@LaunchedEffect
        result = null
        notice = null
        val t = target()
        wanted = t
        result = if (t == null) SearchResult(emptyList()) else online.search(t)
    }

    Surface(
        Modifier.align(Alignment.TopEnd).padding(top = 72.dp, end = 16.dp).width(460.dp).heightIn(max = 560.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xE61C1C1E),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.onlineResultsTitle, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                OverlayButton(PlayerIcons.Close, s.commonClose, onClose, size = 32)
            }
            Spacer(Modifier.height(8.dp))
            val shown = result
            if (shown == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(s.onlineSearching, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                }
                return@Column
            }
            val reason = notice ?: shown.notice
            if (reason != null) {
                Text(
                    OnlineWords.notice(reason),
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0x33FFFFFF)).padding(10.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
            if (shown.hits.isEmpty() && reason == null) {
                Text(s.onlineNoResults, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
            }
            if (reason == SubtitleNotice.Offline || reason == SubtitleNotice.Busy) {
                TextButton(onClick = { attempt++ }) { Text(s.commonTryAgain) }
            }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                shown.hits.forEach { hit ->
                    val enabled = online.blockedFor(hit) == null && busy == null
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = enabled) {
                                busy = hit
                                scope.launch {
                                    when (val done = online.download(hit, wanted)) {
                                        is DownloadResult.Done -> {
                                            onLoaded(done.file, done.label)
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
                            hit.release.ifBlank { hit.fileName },
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
                    }
                }
            }
        }
    }
}
