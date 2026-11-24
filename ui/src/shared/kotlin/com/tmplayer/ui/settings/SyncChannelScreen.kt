package com.tmplayer.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tmplayer.data.ChannelOption
import com.tmplayer.data.ChooseResult
import com.tmplayer.data.DeviceForm
import com.tmplayer.data.ResumeSync
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.SyncChannelBlock
import com.tmplayer.data.SyncChannelChoice
import com.tmplayer.data.SyncDestinations
import com.tmplayer.data.Td
import com.tmplayer.data.TdChannelProbe
import com.tmplayer.i18n.Messages
import com.tmplayer.platform.Background
import com.tmplayer.ui.components.deviceForm
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.onboarding.QuietButton
import com.tmplayer.ui.onboarding.SetupButton
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import com.tmplayer.ui.components.liveMessage
import kotlinx.coroutines.launch

/**
 * What the sync channel page shows (HX87): where the sync file and the backups go now, why a
 * chosen channel is not in use, where an older copy was left, and the pieces of the guide that are
 * open. A plain value, so a render test and the TV focus test can show any state without Telegram.
 */
data class SyncChannelUiState(
    /** The chosen private channel, or null for Saved Messages. */
    val choice: SyncChannelChoice? = null,
    /** Why the chosen channel is not used right now. */
    val block: SyncChannelBlock? = null,
    /** Where an older copy of the file was left after a move; "" for none. */
    val oldCopy: String = "",
    /** "Create it for me" is asking its question in place. */
    val asking: Boolean = false,
    /** Channels the viewer owns (private), once asked for; null before. */
    val options: List<ChannelOption>? = null,
    val loadingOptions: Boolean = false,
    /** A change is under way (creating, checking, moving): no second press. */
    val busy: Boolean = false,
    val note: String? = null,
    val error: String? = null,
)

/** The page's presses, so the content has no Telegram in it. */
class SyncChannelActions(
    val onClose: () -> Unit = {},
    val onAskCreate: () -> Unit = {},
    val onCreate: () -> Unit = {},
    val onCancelCreate: () -> Unit = {},
    val onShowOptions: () -> Unit = {},
    val onPick: (ChannelOption) -> Unit = {},
    val onUseSaved: () -> Unit = {},
    /** Does the last press again, shown beside an error (WP-97); null leaves the button out. */
    val onRetry: (() -> Unit)? = null,
)

/**
 * The Sync channel page: where the sync file and the settings backup are kept. Saved Messages is
 * the default; a private channel the viewer owns, with only them in it, is the other choice. The
 * guide says how to make one (steps on a phone and a desktop, one line on a television, where
 * nothing is typed), and "Create it for me" makes it, after a question in place. The same page on
 * a phone, a television and a desktop, behind the same PIN as Settings.
 */
@Composable
fun SyncChannelScreen(settings: SettingsStore, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    BackHandler(onBack = onClose)
    val choice by settings.syncChannel.collectAsState(initial = null)
    val block by settings.syncChannelBlock.collectAsState(initial = null)
    val oldCopy by settings.syncOldCopy.collectAsState(initial = "")
    var asking by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf<List<ChannelOption>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    /** One change at a time, run in the app's own scope so leaving the page does not stop it half way. */
    fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        note = null
        error = null
        // As the profile open when it was pressed (HX88): its settings writes are that profile's,
        // and dropped once another is open, never written into the next one.
        Background.scope.launch(com.tmplayer.data.AccountOwner(com.tmplayer.data.Accounts.stamp)) {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = com.tmplayer.data.Failures.humanise(e)
            } finally {
                busy = false
            }
        }
    }

    fun told(result: ChooseResult, fromCreate: Boolean) {
        when (result) {
            is ChooseResult.Picked -> {
                note = when {
                    fromCreate && result.created -> s.syncchannelCreated(result.title)
                    fromCreate -> s.syncchannelReused(result.title)
                    else -> s.syncchannelPicked(result.title)
                }
                options = null
                ResumeSync.syncSoon(settings, afterMs = 0)
            }
            is ChooseResult.Refused -> error = result.block.words()
            is ChooseResult.CreatedUnverified -> note = s.syncchannelCreatedUnverified(result.title)
            is ChooseResult.Failed -> error = result.reason
        }
    }

    val state = SyncChannelUiState(choice, block, oldCopy, asking, options, loading, busy, note, error)
    // The last press that can be asked again, for Try again beside an error (WP-97).
    var again by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun keep(action: () -> Unit): () -> Unit = { again = action; action() }
    val actions = SyncChannelActions(
        onClose = onClose,
        onAskCreate = { asking = true; note = null; error = null },
        onCancelCreate = { asking = false },
        onRetry = { again?.invoke() },
        onCreate = keep {
            asking = false
            work {
                val session = Td.sessionFor(com.tmplayer.data.Accounts.stamp)
                val myId = Td.myId()
                if (session == null || myId == 0L) {
                    error = s.settingsResumeSyncOffline
                } else {
                    told(SyncDestinations.create(settings, session.client, myId, SyncDestinations.CHANNEL_NAME, s.syncchannelChannelDescription), fromCreate = true)
                }
            }
        },
        onShowOptions = keep {
            if (!loading) {
                loading = true
                error = null
                note = null
                Background.scope.launch(com.tmplayer.data.AccountOwner(com.tmplayer.data.Accounts.stamp)) {
                    try {
                        val session = Td.sessionFor(com.tmplayer.data.Accounts.stamp)
                        if (session == null) error = s.settingsResumeSyncOffline else options = SyncDestinations.candidates(session.client)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = com.tmplayer.data.Failures.humanise(e)
                    } finally {
                        loading = false
                    }
                }
            }
        },
        onPick = { option ->
            keep {
                work {
                    val session = Td.sessionFor(com.tmplayer.data.Accounts.stamp)
                    val myId = Td.myId()
                    if (session == null || myId == 0L) {
                        error = s.settingsResumeSyncOffline
                    } else {
                        told(SyncDestinations.choose(settings, TdChannelProbe(session.client), myId, option.chatId), fromCreate = false)
                    }
                }
            }()
        },
        onUseSaved = keep {
            work {
                ResumeSync.changeDestination(settings, null)
                note = s.syncchannelSwitchedSaved
                ResumeSync.syncSoon(settings, afterMs = 0)
            }
        },
    )
    SyncChannelContent(state, actions, modifier)
}

/** The page itself; no Telegram, no settings. */
@Composable
fun SyncChannelContent(state: SyncChannelUiState, actions: SyncChannelActions, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val form = deviceForm()
    val tv = form == DeviceForm.Tv
    val createFocus = remember { FocusRequester() }
    val firstOption = remember { FocusRequester() }

    // A remote lands on the one button that does the job; a question closed puts it back there.
    LaunchedEffect(state.asking) {
        if (tv && !state.asking) runCatching { createFocus.requestFocus() }
    }
    LaunchedEffect(state.options?.firstOrNull()?.chatId) {
        if (tv && state.options?.isNotEmpty() == true) runCatching { firstOption.requestFocus() }
    }

    val outer = when (form) {
        DeviceForm.Tv -> Modifier.padding(horizontal = Tv.SafeH, vertical = Tv.SafeV)
        DeviceForm.Desktop -> Modifier.padding(horizontal = 40.dp, vertical = 28.dp)
        DeviceForm.Phone -> Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)
    }
    Box(modifier.fillMaxSize().background(Tone.background).then(outer), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 680.dp).fillMaxWidth().verticalScroll(rememberScrollState()).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            QuietButton(s.commonBack, icon = true, onClick = actions.onClose)
            Text(s.syncchannelTitle, style = MaterialTheme.typography.headlineMedium, color = Tone.text, modifier = Modifier.semantics { heading() })
            Text(s.syncchannelIntro, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
            StatusCard(state, s)

            // How to set it up: three steps to read on a phone or a desktop, one line on a television.
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Tone.surfaceHigh).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(s.syncchannelGuideTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Tone.text, modifier = Modifier.semantics { heading() })
                if (tv) {
                    Text(s.syncchannelGuideTv, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
                } else {
                    Step(1, s.syncchannelGuideStep1)
                    Step(2, s.syncchannelGuideStep2)
                    Step(3, s.syncchannelGuideStep3)
                }
                Text(s.syncchannelPrivacy, style = MaterialTheme.typography.bodySmall, color = Tone.muted)
            }

            if (state.asking) {
                Confirm(
                    title = s.syncchannelCreateConfirmTitle,
                    body = s.syncchannelCreateConfirmBody,
                    action = s.syncchannelCreateConfirmAction,
                    onYes = actions.onCreate,
                    onNo = actions.onCancelCreate,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SetupButton(
                        if (state.busy) s.syncchannelCreating else s.syncchannelButtonCreate,
                        primary = true,
                        enabled = !state.busy,
                        modifier = Modifier.focusRequester(createFocus),
                        onClick = actions.onAskCreate,
                    )
                    if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Tone.accent)
                }
                SetupButton(s.syncchannelButtonChoose, enabled = !state.busy && !state.loadingOptions, onClick = actions.onShowOptions)
                if (state.choice != null) {
                    QuietButton(s.syncchannelButtonUseSaved, onClick = actions.onUseSaved)
                }
            }

            if (state.loadingOptions) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Tone.accent)
                    Text(s.syncchannelChooseLoading, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
                }
            }
            state.options?.let { options ->
                Text(s.syncchannelChooseTitle, style = MaterialTheme.typography.titleMedium, color = Tone.text, modifier = Modifier.semantics { heading() })
                if (options.isEmpty()) {
                    Text(s.syncchannelChooseNone, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
                }
                options.forEachIndexed { index, option ->
                    SetupButton(
                        option.title.ifBlank { s.syncchannelThisChannel },
                        enabled = !state.busy,
                        modifier = if (index == 0) Modifier.focusRequester(firstOption) else Modifier,
                        onClick = { actions.onPick(option) },
                    )
                }
            }
            // Spoken when they appear (WP-97), and an error offers the last press again.
            state.note?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = Tone.accent, modifier = Modifier.liveMessage()) }
            state.error?.let { failure ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(failure, style = MaterialTheme.typography.labelLarge, color = Tone.danger, modifier = Modifier.weight(1f, fill = false).liveMessage())
                    actions.onRetry?.let { retry -> if (!state.busy) QuietButton(s.commonTryAgain, onClick = retry) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Where the file is now, why a chosen channel is not in use, and where an older copy was left. */
@Composable
private fun StatusCard(state: SyncChannelUiState, s: Messages) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Tone.accent.copy(alpha = 0.10f)).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val inUse = state.choice != null && state.block == null
        Text(
            if (inUse) s.syncchannelUsingChannel(state.choice?.title?.ifBlank { s.syncchannelThisChannel } ?: "") else s.syncchannelUsingSaved,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = Tone.text,
        )
        state.block?.let { Text(it.words(), style = MaterialTheme.typography.bodyMedium, color = Tone.danger) }
        if (state.oldCopy.isNotBlank()) Text(s.syncchannelOldCopy(state.oldCopy), style = MaterialTheme.typography.bodySmall, color = Tone.muted)
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("$number.", style = MaterialTheme.typography.bodyMedium, color = Tone.accent, fontWeight = FontWeight.SemiBold)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Tone.muted, modifier = Modifier.weight(1f))
    }
}

/** The line a row in Settings shows for where the file is kept. */
fun syncChannelRowLine(s: Messages, choice: SyncChannelChoice?, block: SyncChannelBlock?): String = when {
    choice == null -> s.syncchannelSavedMessages
    block != null -> s.syncchannelRowBlocked
    else -> s.syncchannelRowChannel(choice.title.ifBlank { s.syncchannelThisChannel })
}

/**
 * Whether the sync channel page is up: one switch for the window, drawn by one
 * [SyncChannelScreenHost] over Settings, as the Devices page is.
 */
object SyncChannelScreenState {
    var open by mutableStateOf(false)
}

/** [SyncChannelScreen] over everything while [SyncChannelScreenState.open]. */
@Composable
fun SyncChannelScreenHost(settings: SettingsStore, modifier: Modifier = Modifier) {
    if (SyncChannelScreenState.open) SyncChannelScreen(settings, onClose = { SyncChannelScreenState.open = false }, modifier = modifier)
}
