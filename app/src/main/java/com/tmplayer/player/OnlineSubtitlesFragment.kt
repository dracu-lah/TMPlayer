package com.tmplayer.player

import android.os.Bundle
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import androidx.lifecycle.lifecycleScope
import com.tmplayer.i18n.L
import com.tmplayer.online.DownloadResult
import com.tmplayer.online.OnlineSubtitles
import com.tmplayer.online.OnlineWords
import com.tmplayer.online.SearchResult
import com.tmplayer.online.SubtitleHit
import com.tmplayer.online.SubtitleNotice
import com.tmplayer.online.SubtitleTarget
import kotlinx.coroutines.launch

/**
 * The subtitle picker's "Search online": what OpenSubtitles (and SubDL, with the viewer's key)
 * has for the video playing now, in the same guided list as the picker, so a remote and a thumb
 * both work it. Choosing one downloads it and loads it over the video, where the per-file delay
 * and the subtitle look apply to it like any other track.
 *
 * Whatever stands in the way (not signed in, no downloads left today, the service unavailable)
 * is the first line, in words, and the results it blocks are greyed out under it; results already
 * on this device stay available whatever the account says.
 */
class OnlineSubtitlesFragment : GuidedStepSupportFragment() {

    private val host: TrackPickerHost? get() = activity as? TrackPickerHost
    private var target: SubtitleTarget? = null
    private var hits: List<SubtitleHit> = emptyList()
    private var busy = false

    override fun onProvideTheme(): Int = androidx.leanback.R.style.Theme_Leanback_GuidedStep

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance =
        GuidanceStylist.Guidance(L.onlineResultsTitle, L.onlineSearchOnlineDetail, L.tracksSubtitles, null)

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += line(ID_STATUS, L.onlineSearching, enabled = false)
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        search()
    }

    private fun search() {
        val online = OnlineSubtitles.current ?: return
        setActions(listOf(line(ID_STATUS, L.onlineSearching, enabled = false)))
        viewLifecycleOwner.lifecycleScope.launch {
            val wanted = host?.onlineTarget()
            target = wanted
            if (wanted == null) {
                show(SearchResult(emptyList()))
                return@launch
            }
            show(online.search(wanted))
        }
    }

    private fun show(result: SearchResult, override: SubtitleNotice? = null) {
        val online = OnlineSubtitles.current ?: return
        hits = result.hits
        val notice = override ?: result.notice
        val list = mutableListOf<GuidedAction>()
        // The reason first, and focusable, so a remote lands on the sentence that explains the
        // greyed lines under it rather than skipping past it.
        if (notice != null) list += line(ID_NOTICE, OnlineWords.notice(notice), enabled = true)
        if (hits.isEmpty()) {
            if (notice == null) list += line(ID_NOTICE, L.onlineNoResults, enabled = true)
        } else {
            hits.forEachIndexed { index, hit ->
                list += GuidedAction.Builder(requireContext())
                    .id(index.toLong())
                    .title(hit.release.ifBlank { hit.fileName })
                    .description(OnlineWords.hitDetail(hit))
                    .multilineDescription(true)
                    .enabled(online.blockedFor(hit) == null)
                    .build()
            }
        }
        if (notice == SubtitleNotice.Offline || notice == SubtitleNotice.Busy) {
            list += line(ID_RETRY, L.commonTryAgain, enabled = true)
        }
        setActions(list)
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_RETRY -> search()
            ID_NOTICE, ID_STATUS -> Unit
            else -> hits.getOrNull(action.id.toInt())?.let(::download)
        }
    }

    private fun download(hit: SubtitleHit) {
        val online = OnlineSubtitles.current ?: return
        if (busy) return
        busy = true
        val index = hits.indexOf(hit)
        actions.getOrNull(findActionPositionById(index.toLong()))?.let {
            it.description = L.onlineDownloading
            notifyActionChanged(findActionPositionById(index.toLong()))
        }
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = online.download(hit, target)) {
                is DownloadResult.Done -> {
                    host?.attachOnlineSubtitle(result.file, result.label)
                    finishGuidedStepSupportFragments()
                }
                is DownloadResult.Failed -> show(SearchResult(hits), override = result.notice)
            }
            busy = false
        }
    }

    private fun line(id: Long, text: String, enabled: Boolean) = GuidedAction.Builder(requireContext())
        .id(id)
        .title(text)
        .multilineDescription(true)
        .enabled(enabled)
        .build()

    private companion object {
        const val ID_STATUS = 2_000L
        const val ID_NOTICE = 2_001L
        const val ID_RETRY = 2_002L
    }
}
