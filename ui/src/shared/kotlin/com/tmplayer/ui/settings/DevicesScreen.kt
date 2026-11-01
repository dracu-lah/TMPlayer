package com.tmplayer.ui.settings

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.data.AccountSession
import com.tmplayer.data.AccountSessions
import com.tmplayer.data.DeviceForm
import com.tmplayer.i18n.Messages
import com.tmplayer.ui.components.deviceForm
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.onboarding.QuietButton
import com.tmplayer.ui.onboarding.SetupButton
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import kotlinx.coroutines.launch

/**
 * The Devices page (CP83): every device signed in to the Telegram account, this one first and
 * marked, each with its app, its place and when it was last active; End session under each other
 * device and End all other sessions at the foot, each asked once more in place before anything
 * happens (the question stands where the button was, its safe answer focused, so a remote never
 * ends a session by one press too many). Reached from Settings, so it is behind the app lock and
 * the kids lock's PIN wherever Settings is. The same page on a phone, a television and a desktop.
 */
@Composable
fun DevicesScreen(onClose: () -> Unit, modifier: Modifier = Modifier, preview: List<AccountSession>? = null) {
    val s = LocalStrings.current
    val form = deviceForm()
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onClose)

    var sessions by remember { mutableStateOf(preview) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    // The session whose End is being asked about: its id, or ALL for every other one.
    var asking by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }

    suspend fun reload() {
        val loaded = AccountSessions.load()
        loaded.onSuccess { sessions = it; error = null }
        loaded.onFailure { if (sessions == null) error = it.message.orEmpty() }
    }

    fun end(target: Long, name: String) {
        if (busy) return
        busy = true
        scope.launch {
            val failure = if (target == ALL) AccountSessions.endAllOthers() else AccountSessions.end(target)
            note = failure ?: if (target == ALL) s.sessionsEndAllDone else s.sessionsEndDone(name)
            asking = null
            reload()
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        if (preview == null) reload()
    }
    LaunchedEffect(sessions != null || error != null) {
        if (form == DeviceForm.Tv) runCatching { firstFocus.requestFocus() }
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
            QuietButton(s.commonBack, icon = true, modifier = if (sessions == null) Modifier.focusRequester(firstFocus) else Modifier, onClick = onClose)
            Text(s.sessionsTitle, style = MaterialTheme.typography.headlineMedium, color = Tone.text, modifier = Modifier.semantics { heading() })
            Text(s.sessionsBlurb, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
            note?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = Tone.accent) }

            val list = sessions
            when {
                list == null && error != null -> {
                    Text(s.sessionsFailed(error.orEmpty()), style = MaterialTheme.typography.bodyMedium, color = Tone.danger)
                    SetupButton(s.commonTryAgain, primary = true, onClick = { error = null; scope.launch { reload() } })
                }
                list == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Tone.accent)
                    Text(s.sessionsLoading, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
                }
                else -> {
                    val now = System.currentTimeMillis() / 1000
                    val mine = list.filter { it.current }
                    val others = list.filterNot { it.current }
                    mine.forEach { SessionCard(it, s, now) }
                    Spacer(Modifier.height(4.dp))
                    // End all other sessions sits beside the heading, not after the list, so it
                    // stays in view without scrolling past every device (CP83 follow-up: the
                    // bottom button was out of sight once a few devices were signed in).
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(s.sessionsOthers, style = MaterialTheme.typography.titleMedium, color = Tone.text, modifier = Modifier.semantics { heading() })
                        if (others.size > 1 && asking != ALL) {
                            SetupButton(s.sessionsEndAll, onClick = { asking = ALL; note = null })
                        }
                    }
                    if (asking == ALL) {
                        Confirm(
                            title = s.sessionsEndAllTitle,
                            body = s.sessionsEndAllConfirm,
                            action = if (busy) s.sessionsEnding else s.sessionsEndAll,
                            onYes = { end(ALL, "") },
                            onNo = { asking = null },
                        )
                    }
                    if (others.isEmpty()) Text(s.sessionsNoneOther, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
                    others.forEachIndexed { index, session ->
                        val name = AccountSessions.title(session, s)
                        SessionCard(session, s, now) {
                            if (asking == session.id) {
                                Confirm(
                                    title = s.sessionsEndTitle,
                                    body = s.sessionsEndConfirm(name),
                                    action = if (busy) s.sessionsEnding else s.sessionsEnd,
                                    onYes = { end(session.id, name) },
                                    onNo = { asking = null },
                                )
                            } else {
                                QuietButton(
                                    s.sessionsEnd,
                                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                                    onClick = { asking = session.id; note = null },
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Stands for "every other session" in the page's question. Telegram's session ids are never 0. */
private const val ALL = 0L

/** One device: its icon, its name (and "This device"), what app, where and when, and [actions]. */
@Composable
private fun SessionCard(session: AccountSession, s: Messages, nowSec: Long, actions: (@Composable () -> Unit)? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (session.current) Tone.accent.copy(alpha = 0.10f) else Tone.surfaceHigh)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(if (session.current) Tone.accent else Tone.background),
                contentAlignment = Alignment.Center,
            ) {
                Icon(iconOf(session.kind), contentDescription = null, tint = if (session.current) Tone.onAccent else Tone.text, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    AccountSessions.title(session, s),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Tone.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AccountSessions.appLine(session).takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Tone.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (session.current) Text(s.sessionsThisDevice, style = MaterialTheme.typography.labelMedium, color = Tone.accent)
        }
        Text(AccountSessions.placeLine(session, s, nowSec), style = MaterialTheme.typography.bodySmall, color = Tone.muted)
        val extra = listOfNotNull(
            session.ip.takeIf { it.isNotBlank() }?.let { s.sessionsAddress(it) },
            session.loginDate.takeIf { it > 0 }?.let { s.sessionsSignedIn(s.formatter.date(it * 1000L)) },
        ).joinToString("  ·  ")
        if (extra.isNotEmpty()) Text(extra, style = MaterialTheme.typography.labelSmall, color = Tone.muted.copy(alpha = 0.85f))
        if (session.unconfirmed) Text(s.sessionsUnconfirmed, style = MaterialTheme.typography.labelMedium, color = Tone.danger)
        if (session.passwordPending) Text(s.sessionsPasswordPending, style = MaterialTheme.typography.labelMedium, color = Tone.danger)
        if (actions != null) Box(Modifier.padding(top = 2.dp)) { actions() }
    }
}

/**
 * The question in place of the button that asked it: what will happen, then the action and
 * Cancel, Cancel focused so a stray press of OK on a remote changes nothing.
 */
@Composable
internal fun Confirm(title: String, body: String, action: String, onYes: () -> Unit, onNo: () -> Unit) {
    val s = LocalStrings.current
    val cancel = remember { FocusRequester() }
    AnimatedVisibility(visible = true) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Tone.background)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Tone.text)
            Text(body, style = MaterialTheme.typography.bodySmall, color = Tone.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SetupButton(action, primary = true, onClick = onYes)
                SetupButton(s.commonCancel, modifier = Modifier.focusRequester(cancel), onClick = onNo)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { cancel.requestFocus() } }
}

private fun iconOf(kind: AccountSession.Kind): ImageVector = when (kind) {
    AccountSession.Kind.Phone, AccountSession.Kind.Tablet -> TmIcons.Phone
    AccountSession.Kind.Computer -> TmIcons.WindowMaximize
    AccountSession.Kind.Browser -> TmIcons.Language
    AccountSession.Kind.Console -> TmIcons.Tv
    AccountSession.Kind.Unknown -> TmIcons.Account
}

/**
 * Whether the Devices page is up: one switch for the window, drawn by one [DevicesScreenHost]
 * over Settings, as the proxy screen is.
 */
object DevicesScreenState {
    var open by mutableStateOf(false)
}

/** [DevicesScreen] over everything while [DevicesScreenState.open]; put it last in the window's `Box`. */
@Composable
fun DevicesScreenHost(modifier: Modifier = Modifier) {
    if (DevicesScreenState.open) DevicesScreen(onClose = { DevicesScreenState.open = false }, modifier = modifier)
}
