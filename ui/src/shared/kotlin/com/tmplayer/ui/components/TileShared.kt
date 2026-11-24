package com.tmplayer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.tmplayer.data.HintIds
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.VideoCorrection
import com.tmplayer.data.VideoCorrections
import com.tmplayer.i18n.Messages
import com.tmplayer.i18n.Translator
import com.tmplayer.online.MetaSettings
import com.tmplayer.online.OnlineMetadata

/**
 * The app wide states every video tile reads (the viewer's corrections, the lookup settings, the
 * language), collected once for a screen of tiles rather than once per tile. A TV row of twenty
 * tiles otherwise held some eighty collectors, each started as the tile was composed, and one
 * correction recomposed every one of them.
 *
 * Handed down as [State]s rather than values, so the screen that provides them is not recomposed
 * when one changes: only the tiles that read the changed one are.
 */
@Stable
class TileShared internal constructor(
    val corrections: State<Map<String, VideoCorrection>>,
    /** The lookups these settings belong to; a tile under another instance collects its own. */
    val online: OnlineMetadata?,
    val settings: State<MetaSettings>?,
    val language: State<Messages>,
    /** The download queue, which every video tile's corner badge reads. */
    val downloads: State<Map<Int, OfflineDownloads.Progress>>,
)

/** The screen's [TileShared], or null where no screen provides one: each reader collects its own. */
val LocalTileShared = staticCompositionLocalOf<TileShared?> { null }

/** The states for [LocalTileShared], collected here once. */
@Composable
fun rememberTileShared(): TileShared {
    val corrections = VideoCorrections.state.collectAsState()
    val online = OnlineMetadata.current
    val settings = online?.settings?.collectAsState()
    val language = rememberLanguage()
    val downloads = OfflineDownloads.active.collectAsState()
    return remember(corrections, online, settings, language, downloads) { TileShared(corrections, online, settings, language, downloads) }
}

/**
 * [id]'s correction alone: a tile reading it recomposes when its own correction changes, not when
 * any other video's does.
 */
@Composable
fun rememberCorrection(id: String): VideoCorrection? {
    val all = rememberCorrections()
    val one by remember(id, all) { derivedStateOf { all.value[id] } }
    return one
}

/** Every correction, as a [State] to read where it is needed: the screen's, else collected here. */
@Composable
fun rememberCorrections(): State<Map<String, VideoCorrection>> =
    LocalTileShared.current?.corrections ?: VideoCorrections.state.collectAsState()

/** The lookup settings of [online], the screen's when it has them. */
@Composable
fun rememberMetaSettings(online: OnlineMetadata): State<MetaSettings> {
    val shared = LocalTileShared.current
    return if (shared?.settings != null && shared.online === online) shared.settings else online.settings.collectAsState()
}

/**
 * The language in use, the screen's when it has one. [Translator.active] hands out a new flow on
 * every read, so it is taken once here: collected from a fresh one on each recomposition, its
 * collector started over every time.
 */
@Composable
fun rememberLanguage(): State<Messages> {
    LocalTileShared.current?.let { return it.language }
    val flow = remember { Translator.active }
    return flow.collectAsState()
}

/**
 * The download queue, by file id, the screen's when it has one: a TV row of tiles otherwise
 * started a lifecycle-aware collector per tile as each one was composed.
 */
@Composable
fun rememberDownloadQueue(): State<Map<Int, OfflineDownloads.Progress>> =
    LocalTileShared.current?.downloads ?: OfflineDownloads.active.collectAsState()

/**
 * What a video tile or History row needs to tell a remote about its options (WP-14): the words, and
 * the settings the one-time hint registry counts in. Provided by the screens of tiles that open an
 * options menu on a long OK or Menu; null where a screen provides none, and then no tile hints.
 */
@Stable
class TileOptionsHint(val settings: SettingsStore, val label: String)

val LocalTileOptionsHint = staticCompositionLocalOf<TileOptionsHint?> { null }

/** How many focuses show the tile hint before it stops for good. */
const val TILE_OPTIONS_HINT_SHOWS = 5

/**
 * The tile's second line on a television: "Hold OK or press Menu for options", through the hint
 * registry (WP-68), for the first [TILE_OPTIONS_HINT_SHOWS] focuses and at most one hint per screen
 * visit. Place it inside the box of the tile's art. It composes nothing while the tile is not
 * focused (the read of [focused] is here, so only this scope redraws as the remote moves).
 */
@Composable
fun TileOptionsTip(focused: State<Boolean>) {
    val hint = LocalTileOptionsHint.current ?: return
    if (!focused.value) return
    OneTimeHint(
        settings = hint.settings,
        id = HintIds.TILE_OPTIONS,
        label = hint.label,
        maxShows = TILE_OPTIONS_HINT_SHOWS,
    )
}
