package com.tmplayer.ui.settings

import android.os.Build
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.data.ChatGate
import com.tmplayer.data.SettingsStore
import com.tmplayer.player.EngineAvailability
import com.tmplayer.player.EngineChoice
import com.tmplayer.player.EngineOptions
import com.tmplayer.player.ExternalPlay
import com.tmplayer.player.InstalledPlayers
import com.tmplayer.player.PlayerChoice
import com.tmplayer.player.TunneledMode
import com.tmplayer.ui.components.ChoiceLine
import com.tmplayer.ui.components.ChoiceList
import com.tmplayer.ui.components.ChoiceSheet
import com.tmplayer.ui.components.FocusOnOpen
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import kotlinx.coroutines.launch

/**
 * Settings, Playback: the default player and the engine rows (PR4, PR5), added to the page as one
 * block so the page itself only says where they go.
 *
 * "Open videos in another app" is not a row of its own: it lives in [DefaultPlayerRow] (one home
 * per job), whose sheet lists TMPlayer, Ask every time and the installed players, and resets to
 * Ask. The player engine offers VLC only where [EngineAvailability] says so, and with the switch
 * beside it is drawn only there ([vlcShown]); the decoder sits between them. Advanced is collapsed ([advancedOpen]) and holds the
 * switches most people never need; each is left out where the device cannot use it, not greyed.
 */
internal fun LazyListScope.playerRows(
    settings: SettingsStore,
    touch: Boolean,
    advancedOpen: Boolean,
    /**
     * Whether the engine rows are drawn: VLC is offered here, or is the stored choice (so the row
     * can say it is unavailable and be put back). Without VLC, Automatic and ExoPlayer are the one
     * engine and there is nothing to switch to, so both rows would do nothing (U20).
     */
    vlcShown: Boolean,
    onToggleAdvanced: () -> Unit,
    /** The decoder, beside the engine it is part of. */
    decoderRow: LazyListScope.() -> Unit,
) {
    item { DefaultPlayerRow(settings) }
    if (vlcShown) item { EngineRow(settings) }
    decoderRow()
    if (vlcShown) {
        item {
            val s = LocalStrings.current
            val scope = rememberCoroutineScope()
            val on by settings.switchEngineOnError.collectAsStateWithLifecycle(initialValue = true)
            ToggleRow(
                title = s.settingsEngineSwitch,
                subtitle = if (on) s.settingsEngineSwitchOn else s.settingsEngineSwitchOff,
                icon = TmIcons.Refresh,
                checked = on,
                onToggle = { scope.launch { settings.setSwitchEngineOnError(!on) } },
            )
        }
    }
    // "Suggest VLC when playback cannot keep up" was here: stored, but read by nothing, so a
    // switch that changed nothing (U20). Its key is still read for old backups.
    item {
        val s = LocalStrings.current
        ActionRow(
            title = s.settingsAdvanced,
            subtitle = s.settingsAdvancedDetail,
            icon = if (advancedOpen) TmIcons.ChevronUp else TmIcons.ChevronDown,
            onClick = onToggleAdvanced,
        )
    }
    if (!advancedOpen) return
    // The television's own: frame-rate matching (Android 12 and later, where the display can be
    // asked) and tunneled playback. Not drawn on a phone, which has neither.
    if (!touch && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        item {
            val s = LocalStrings.current
            val scope = rememberCoroutineScope()
            val on by settings.frameRateMatching.collectAsStateWithLifecycle(initialValue = true)
            ToggleRow(
                title = s.settingsFrameRateMatching,
                subtitle = if (on) s.settingsFrameRateMatchingOn else s.settingsFrameRateMatchingOff,
                icon = TmIcons.Frames,
                checked = on,
                onToggle = { scope.launch { settings.setFrameRateMatching(!on) } },
            )
        }
    }
    if (!touch) {
        item {
            val s = LocalStrings.current
            val scope = rememberCoroutineScope()
            val mode by settings.tunneledMode.collectAsStateWithLifecycle(initialValue = TunneledMode.Auto)
            val modes = TunneledMode.entries
            StepperRow(
                title = s.settingsTunneled,
                subtitle = s.settingsTunneledDetail,
                value = s.tunneledLabel(mode),
                icon = TmIcons.Sync,
                canDecrease = mode != modes.first(),
                canIncrease = mode != modes.last(),
                onStep = { direction ->
                    val next = modes[(modes.indexOf(mode) + direction).coerceIn(0, modes.lastIndex)]
                    scope.launch { settings.setTunneledMode(next) }
                },
                onWrap = { scope.launch { settings.setTunneledMode(modes.first()) } },
            )
        }
        // Audio passthrough (A2): on, Dolby and DTS go out as they are where the output takes
        // them; off, they are decoded here. A phone's speaker and the desktop's mpv only ever get
        // PCM, so the row is the television's. Read as a video opens: the next video follows it.
        item {
            val s = LocalStrings.current
            val scope = rememberCoroutineScope()
            val on by settings.audioPassthrough.collectAsStateWithLifecycle(initialValue = true)
            ToggleRow(
                title = s.settingsAudioPassthrough,
                subtitle = if (on) s.settingsAudioPassthroughOn else s.settingsAudioPassthroughOff,
                icon = TmIcons.Audio,
                checked = on,
                onToggle = { scope.launch { settings.setAudioPassthrough(!on) } },
            )
        }
    }
    // Stored now; the overlay itself comes in a later version, which the row says.
    item {
        val s = LocalStrings.current
        val scope = rememberCoroutineScope()
        val on by settings.playbackStats.collectAsStateWithLifecycle(initialValue = false)
        ToggleRow(
            title = s.settingsPlaybackStats,
            subtitle = if (on) s.settingsPlaybackStatsOn else s.settingsPlaybackStatsOff,
            icon = TmIcons.Info,
            checked = on,
            onToggle = { scope.launch { settings.setPlaybackStats(!on) } },
        )
    }
}

private fun com.tmplayer.i18n.Strings.tunneledLabel(mode: TunneledMode): String = when (mode) {
    TunneledMode.Auto -> settingsTunneledAuto
    TunneledMode.On -> settingsTunneledOn
    TunneledMode.Off -> settingsTunneledOff
}

/**
 * Default player: what is picked, in words that also say why it may not apply (the kids lock,
 * nothing else installed, an app that is gone). Pressing it opens [DefaultPlayerSheet], where the
 * pick is changed or reset to Ask. The sheet is a window, so the row keeps the remote under it and
 * takes it back when the sheet closes.
 */
@Composable
private fun DefaultPlayerRow(settings: SettingsStore) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val choice by settings.defaultPlayer.collectAsStateWithLifecycle(initialValue = PlayerChoice.TmPlayer)
    val locked = ChatGate.allowed.collectAsStateWithLifecycle().value != null
    var open by rememberSaveable { mutableStateOf(false) }
    // Asked again each time the sheet opens, so an app installed since is there.
    val installed by produceState<List<InstalledPlayers.Found>?>(null, open) { value = InstalledPlayers.find(context) }
    val subtitle = defaultPlayerSummary(s, choice, installed, locked)
    ActionRow(
        title = s.settingsDefaultPlayer,
        subtitle = subtitle,
        icon = TmIcons.OpenExternal,
        onClick = { open = true },
    )
    if (open) {
        DefaultPlayerSheet(
            current = choice,
            apps = installed.orEmpty(),
            onPick = { picked -> scope.launch { settings.setDefaultPlayer(picked) } },
            onClose = { open = false },
        )
    }
}

/** The row's second line. Pure of the screen, so a test can read it. */
internal fun defaultPlayerSummary(
    s: com.tmplayer.i18n.Strings,
    choice: PlayerChoice,
    installed: List<InstalledPlayers.Found>?,
    locked: Boolean,
): String = when {
    locked -> s.settingsDefaultPlayerLocked
    choice is PlayerChoice.App -> {
        val app = installed?.firstOrNull { it.app.packageName == choice.packageName }
        when {
            app != null -> s.settingsDefaultPlayerAppDetail(app = app.app.label)
            // Not listed yet (still asking) reads as the app; listed without it reads as gone.
            installed == null -> s.settingsDefaultPlayerAppDetail(app = ExternalPlay.nameOf(choice.packageName))
            else -> s.settingsDefaultPlayerGone(app = ExternalPlay.nameOf(choice.packageName))
        }
    }
    choice == PlayerChoice.Ask -> s.settingsDefaultPlayerAsk
    installed != null && installed.isEmpty() -> s.settingsDefaultPlayerNone
    else -> s.externalTmplayer
}

/**
 * The Default player's list (PR4): TMPlayer, Ask every time, then every installed player (VLC and
 * MX Player first), the one in force ticked, and Reset to Ask every time at the foot while
 * something else is picked. A line saves and closes. Under a remote the tick's line takes focus on
 * open, and the list scrolls inside the panel; with nothing else installed only TMPlayer and Ask
 * are there, and the note says why.
 */
@Composable
internal fun DefaultPlayerSheet(
    current: PlayerChoice,
    apps: List<InstalledPlayers.Found>,
    onPick: (PlayerChoice) -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalStrings.current
    val names = remember(apps) { ExternalPlay.displayNames(apps.map { it.app }) }
    val focus = remember { FocusRequester() }
    fun pick(choice: PlayerChoice) {
        onPick(choice)
        onClose()
    }
    val goneApp = (current as? PlayerChoice.App)?.takeIf { c -> apps.none { it.app.packageName == c.packageName } }
    val note = when {
        goneApp != null -> s.settingsDefaultPlayerGone(app = ExternalPlay.nameOf(goneApp.packageName))
        apps.isEmpty() -> s.settingsDefaultPlayerNone
        else -> s.settingsDefaultPlayerNote
    }
    ChoiceSheet(title = s.settingsDefaultPlayer, note = note, onDismiss = onClose, onClose = onClose) {
        ChoiceList {
            fun marked(selected: Boolean) = if (selected) Modifier.focusRequester(focus) else Modifier
            ChoiceLine(
                title = s.externalTmplayer,
                detail = s.externalTmplayerDetail,
                selected = current == PlayerChoice.TmPlayer,
                modifier = marked(current == PlayerChoice.TmPlayer),
            ) { pick(PlayerChoice.TmPlayer) }
            ChoiceLine(
                title = s.settingsDefaultPlayerAsk,
                detail = s.settingsDefaultPlayerAskDetail,
                selected = current == PlayerChoice.Ask,
                modifier = if (goneApp != null) Modifier.focusRequester(focus) else marked(current == PlayerChoice.Ask),
            ) { pick(PlayerChoice.Ask) }
            apps.forEachIndexed { i, found ->
                val selected = current is PlayerChoice.App && current.packageName == found.app.packageName
                ChoiceLine(title = names[i], selected = selected, modifier = marked(selected)) { pick(found.app.choice) }
            }
            if (current != PlayerChoice.Ask) {
                ChoiceLine(title = s.settingsDefaultPlayerReset, icon = TmIcons.Refresh) { pick(PlayerChoice.Ask) }
            }
        }
        FocusOnOpen(focus)
    }
}

/** Player engine: Automatic, ExoPlayer, and VLC where it is offered ([EngineAvailability]). */
@Composable
private fun EngineRow(settings: SettingsStore) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val stored by settings.engineChoice.collectAsStateWithLifecycle(initialValue = EngineChoice.Automatic)
    var open by rememberSaveable { mutableStateOf(false) }
    val vlc = EngineAvailability.vlcOffered
    ActionRow(
        title = s.settingsEngine,
        subtitle = if (EngineOptions.storedButUnavailable(stored, vlc)) s.settingsEngineVlcUnavailable else s.engineLabel(stored),
        icon = TmIcons.Play,
        onClick = { open = true },
    )
    if (open) {
        val focus = remember { FocusRequester() }
        val offered = EngineOptions.offered(vlc)
        ChoiceSheet(title = s.settingsEngine, onDismiss = { open = false }, onClose = { open = false }) {
            ChoiceList {
                offered.forEach { engine ->
                    val selected = engine == stored
                    ChoiceLine(
                        title = s.engineLabel(engine),
                        detail = s.engineDetail(engine),
                        selected = selected,
                        modifier = if (selected || (engine == EngineChoice.Automatic && stored !in offered)) Modifier.focusRequester(focus) else Modifier,
                    ) {
                        scope.launch { settings.setEngineChoice(engine) }
                        open = false
                    }
                }
            }
            FocusOnOpen(focus)
        }
    }
}

private fun com.tmplayer.i18n.Strings.engineLabel(engine: EngineChoice): String = when (engine) {
    EngineChoice.Automatic -> settingsEngineAutomatic
    EngineChoice.ExoPlayer -> settingsEngineExoplayer
    EngineChoice.Vlc -> settingsEngineVlc
}

private fun com.tmplayer.i18n.Strings.engineDetail(engine: EngineChoice): String = when (engine) {
    EngineChoice.Automatic -> settingsEngineAutomaticDetail
    EngineChoice.ExoPlayer -> settingsEngineExoplayerDetail
    EngineChoice.Vlc -> settingsEngineVlcDetail
}
