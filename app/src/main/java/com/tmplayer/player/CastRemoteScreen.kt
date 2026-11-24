package com.tmplayer.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.media3.common.util.UnstableApi
import com.tmplayer.cast.CastPickerSheet
import com.tmplayer.ui.components.ChoiceLine
import com.tmplayer.ui.components.ChoiceSheet
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.player.CastRemote
import com.tmplayer.ui.player.CastRemoteActions
import java.util.Locale

/** The Cast picker over the player, when the top bar's Cast button opens it. */
@UnstableApi
@Composable
internal fun CastPickerHost(cast: PlayerCast) {
    if (cast.pickerOpen.value) CastPickerSheet(cast.support, onDismiss = { cast.pickerOpen.value = false })
}

/**
 * What the phone shows while the video plays on a TV, in place of the picture: the shared
 * [CastRemote] (compact on a phone on its side, a column upright and on a tablet), driven by
 * [PlayerCast], and the app's picker sheets for the soundtrack, the subtitles and the speed.
 */
@UnstableApi
@Composable
internal fun CastRemoteScreen(cast: PlayerCast) {
    val s = LocalStrings.current
    val steps by cast.episodes.collectAsState()
    val state = cast.screen.value.copy(hasPrevious = steps.previous != null, hasNext = steps.next != null)
    val actions = remember(cast) {
        object : CastRemoteActions {
            override fun back() = cast.back()
            override fun devices() = cast.openPicker()
            override fun togglePlay() = cast.togglePlay()
            override fun seekTo(positionMs: Long) = cast.seekTo(positionMs)
            override fun seekBy(deltaMs: Long) = cast.seekBy(deltaMs)
            override fun previous() {
                cast.episodes.value.previous?.let(cast::playEpisode)
            }

            override fun next() {
                cast.episodes.value.next?.let(cast::playEpisode)
            }

            override fun audio() {
                cast.audioPickerOpen.value = true
            }

            override fun subtitles() {
                cast.subtitlePickerOpen.value = true
            }

            override fun speed() {
                cast.speedPickerOpen.value = true
            }

            override fun setVolume(level: Float) = cast.setVolume(level)
            override fun toggleMute() = cast.toggleMute()
            override fun stop() = cast.stopCasting()
            override fun reconnect() = cast.reconnect()
            override fun playHere() = cast.playHere()
            override fun retry() = cast.retry()
            override fun cancelConvert() = cast.cancelConvert()
        }
    }
    CastRemote(state, actions)

    if (cast.audioPickerOpen.value) {
        ChoiceSheet(title = s.playerAudio, onDismiss = { cast.audioPickerOpen.value = false }, onClose = { cast.audioPickerOpen.value = false }) {
            cast.audioChoices.value.forEach { a -> ChoiceLine(a.label, selected = a.selected) { cast.pickAudio(a.id) } }
        }
    }
    if (cast.subtitlePickerOpen.value) {
        val choices = cast.subtitleChoices.value
        ChoiceSheet(
            title = s.tracksSubtitles,
            onDismiss = { cast.subtitlePickerOpen.value = false },
            onClose = { cast.subtitlePickerOpen.value = false },
            note = cast.subtitlesInsideNote(),
        ) {
            // While casting goes without subtitles the picker is the note alone: no tracks that do nothing.
            if (com.tmplayer.cast.CAST_SUBTITLES) {
                ChoiceLine(s.commonOff, selected = choices.none { it.selected }) { cast.pickSubtitle(null) }
                choices.forEach { t -> ChoiceLine(t.label, selected = t.selected) { cast.pickSubtitle(t.id) } }
            }
        }
    }
    if (cast.speedPickerOpen.value) {
        val now = state.speed ?: 1f
        ChoiceSheet(title = s.playerPlaybackSpeed, onDismiss = { cast.speedPickerOpen.value = false }, onClose = { cast.speedPickerOpen.value = false }) {
            PlayerCast.SPEEDS.forEach { v ->
                val label = (if (v % 1f == 0f) String.format(Locale.ROOT, "%.0f", v) else String.format(Locale.ROOT, "%.2f", v).trimEnd('0')) + "×"
                ChoiceLine(label, selected = v == now) { cast.pickSpeed(v) }
            }
        }
    }
}
