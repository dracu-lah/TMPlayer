package com.tmplayer.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.BuildConfig
import com.tmplayer.data.DeviceQuirks
import com.tmplayer.data.Feedback
import com.tmplayer.data.FeedbackReport
import com.tmplayer.data.FeedbackReports
import com.tmplayer.data.FeedbackSender
import com.tmplayer.data.FormFactor
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.ReleaseHighlights
import com.tmplayer.data.WhatsNew
import com.tmplayer.i18n.LanguageNotice
import com.tmplayer.i18n.Translator
import com.tmplayer.ui.auth.QrCode
import com.tmplayer.ui.components.ChoiceList
import com.tmplayer.ui.components.FeedbackActions
import com.tmplayer.ui.components.FeedbackBody
import com.tmplayer.ui.components.FeedbackHost
import com.tmplayer.ui.components.feedbackHeading
import com.tmplayer.ui.components.ChoiceSheet
import com.tmplayer.ui.components.FloatingWindow
import com.tmplayer.ui.components.PhonePad
import com.tmplayer.ui.components.TmButton
import com.tmplayer.ui.components.TmSecondaryButton
import com.tmplayer.ui.components.deviceForm
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.components.tvHoldsFocus
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.onboarding.LanguageList
import com.tmplayer.ui.onboarding.TvLanguages
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.LocalOnFloating
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.floatingSurface
import com.tmplayer.ui.update.openLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings, then Language: every language named in itself, "System default" first. The app
 * changes the moment a row is picked, under the open picker, with no restart. A phone ticks a radio
 * row; a TV walks the list with the D-pad. Both close with Close at the bottom end, as every
 * picker and Material dialog does (Down from the last row on a TV), or with Back.
 */
@Composable
fun LanguageDialog(settings: SettingsStore, onClose: () -> Unit) {
    val touch = isTouch()
    val s = LocalStrings.current
    val close = remember { FocusRequester() }
    ChoiceSheet(title = s.languagePickerTitle, note = s.languagePickerNote, onDismiss = onClose, onClose = onClose) {
        if (touch) {
            ChoiceList { LanguageList(settings, columns = 1) }
        } else {
            BackHandler(onBack = onClose)
            TvLanguages(settings, Modifier.weight(1f, fill = false), focusSelected = close)
        }
    }
    // The chosen row is a lazy item: composed a frame after the list is measured, so asked a few times.
    if (!touch) LaunchedEffect(Unit) {
        repeat(5) {
            androidx.compose.runtime.withFrameNanos { }
            if (runCatching { close.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
}

/**
 * The one-time "Now in Español" card, over the chat list like the support card: the app has
 * followed the device into a language, and this says so in that language, once, with a way out.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LanguageNoticeCard(language: String, onKeep: () -> Unit, onChange: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val touch = isTouch()
    val keep = remember { FocusRequester() }
    if (!touch) BackHandler(onBack = onKeep)
    Column(
        modifier
            // On a TV it takes the remote on Keep, keeps it until answered (Back keeps), and gives
            // it back where it was (HX22).
            .tvHoldsFocus(keep)
            .widthIn(max = if (touch) 520.dp else 560.dp)
            .floatingSurface()
            .padding(if (touch) 16.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(TmIcons.Info, contentDescription = null, tint = Tone.accent, modifier = Modifier.size(24.dp))
            Text(s.languageNowIn(LanguageNotice.name(language)), style = MaterialTheme.typography.titleMedium, color = Tone.text)
        }
        Text(s.languageNowInBody, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
        CompositionLocalProvider(LocalOnFloating provides true) {
            // Wraps whole buttons at a large font or in a long translation.
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TmSecondaryButton(onClick = onChange) { Label(s.commonChange) }
                TmButton(onClick = onKeep, modifier = Modifier.focusRequester(keep)) { Label(s.languageKeep) }
            }
        }
    }
}

/**
 * The page and, under it, the Tips card ([com.tmplayer.ui.onboarding.TipsCard]) in a row of its own
 * (D-1): the page shrinks by the card's height rather than the card covering its bottom edge.
 * [offer] is whether the card is due and allowed; [hintUp] is true while the television's browse hint is on screen or still to come, and
 * holds the card back so the two never show together. The card appears the moment the hint is gone.
 */
@Composable
fun TourOfferFrame(
    offer: Boolean,
    hintUp: Boolean,
    card: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        if (offer && !hintUp) {
            val tv = !isTouch()
            card(
                Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(horizontal = if (tv) 48.dp else 16.dp, vertical = if (tv) 16.dp else 8.dp)
                    .align(Alignment.CenterHorizontally)
                    .widthIn(max = if (tv) 1100.dp else 520.dp)
                    .fillMaxWidth(),
            )
        }
    }
}

/**
 * "What's new": the bundled highlights of [WhatsNew.VERSION], on the first run of that version and
 * from Settings, grouped under headings and limited to the lines for this device's form (a TV is
 * told about its remote, a phone about touch). "Full changelog" opens the site's changelog, or its
 * QR code on a TV. It is a window of its own: Back closes it, and the shell draws it only once the
 * app lock is open and no video is playing, so it never holds either.
 */
@Composable
fun WhatsNewDialog(onClose: () -> Unit, onChangelog: () -> Unit) {
    val touch = isTouch()
    val form = deviceForm()
    val sections = remember(form) { WhatsNew.sections(form) }
    FloatingWindow(onDismiss = onClose) {
        val panel = min(maxWidth - PhonePad.Side * 2, if (touch) 520.dp else 720.dp)
        WhatsNewPanel(
            sections = sections,
            touch = touch,
            onClose = onClose,
            onChangelog = onChangelog,
            modifier = Modifier.width(panel).heightIn(max = maxHeight - 48.dp),
        )
    }
}

/**
 * The sheet's content: a title, the sections and the two buttons. On a TV Close holds the remote
 * from the start (OK dismisses, Left reaches "Full changelog"); on touch nothing takes focus.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WhatsNewPanel(
    sections: List<ReleaseHighlights.Section>,
    touch: Boolean,
    onClose: () -> Unit,
    onChangelog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val close = remember { FocusRequester() }
    Column(
        modifier
            .floatingSurface()
            .verticalScroll(rememberScrollState())
            .padding(if (touch) 20.dp else 28.dp),
        verticalArrangement = Arrangement.spacedBy(if (touch) 12.dp else 14.dp),
    ) {
        Text(
            s.whatsnewTitle(WhatsNew.VERSION),
            style = MaterialTheme.typography.headlineSmall,
            color = Tone.text,
            modifier = Modifier.semantics { heading() },
        )
        Text(s.whatsnewSubtitle, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
        for (section in sections) {
            Text(
                section.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Tone.text,
                modifier = Modifier.padding(top = 4.dp).semantics { heading() },
            )
            for (line in section.lines) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Icon(TmIcons.CheckCircle, contentDescription = null, tint = Tone.accent, modifier = Modifier.size(22.dp))
                    Text(line, style = MaterialTheme.typography.bodyLarge, color = Tone.text)
                }
            }
        }
        CompositionLocalProvider(LocalOnFloating provides true) {
            // Wraps rather than squeezes: "Vollständiges Änderungsprotokoll" left "Schließen"
            // a column of single letters on a phone.
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TmSecondaryButton(onClick = onChangelog) { Label(s.whatsnewFullChangelog) }
                TmButton(onClick = onClose, modifier = Modifier.focusRequester(close)) { Label(s.commonClose) }
            }
        }
    }
    if (!touch) LaunchedEffect(Unit) { runCatching { close.requestFocus() } }
}

/** What a report from this device says about it. No account, chat or file: see [Feedback]. */
fun feedbackFacts(context: Context): Feedback.Facts {
    val tv = FormFactor.isTv(context)
    val fire = DeviceQuirks.isFireTv(context)
    val platform = when {
        fire -> Feedback.Platform.FireTv
        tv -> Feedback.Platform.AndroidTv
        else -> Feedback.Platform.Phone
    }
    val model = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.distinct().joinToString(" ")
    return Feedback.Facts(
        version = BuildConfig.VERSION_NAME,
        platform = platform,
        // The layout's reason goes along (HX78): a generic box shown the wrong one says why.
        device = "$model, Android ${Build.VERSION.RELEASE}, ${runCatching { FormFactor.describe(context) }.getOrDefault("form unknown")}",
        language = Translator.messages.tag,
    )
}

/**
 * "Report a problem" and "Request a feature". With the build's inbox (HX64) the report is written
 * in the app, shown word for word and sent ([FeedbackHost]): a phone in the shared dialog, with
 * GitHub and e-mail kept as the ways round; a TV in [FeedbackTvPanel], beside the QR code for the
 * phone in the viewer's hand, since typing with a remote is slow.
 *
 * Without an inbox (forks, CI): a phone opens the prefilled GitHub page in the browser, with an
 * email as the way round for somebody without a GitHub account; a TV has no browser, so the page
 * is a QR code for the phone, with the address to write to under it.
 */
@Composable
fun FeedbackDialog(onClose: () -> Unit, kind: FeedbackReport.Kind = FeedbackReport.Kind.Problem) {
    val s = LocalStrings.current
    val touch = isTouch()
    val context = LocalContext.current
    val facts = remember { feedbackFacts(context) }
    val feature = kind == FeedbackReport.Kind.Feature
    val issue = remember(facts, feature) { if (feature) Feedback.featureUrl(facts) else Feedback.issueUrl(facts) }
    val close = remember { FocusRequester() }

    if (FeedbackReports.available) {
        FeedbackHost(
            kind = kind,
            facts = facts,
            onDone = onClose,
            onGithub = { openLink(context, issue) },
            onEmail = { sendMail(context, Feedback.mailto(facts, feature)) },
            tvPanel = { state, logs, actions -> FeedbackTvPanel(state, logs, actions, issue, if (feature) s.feedbackFeatureQr else s.feedbackQr) },
        )
        return
    }

    FloatingWindow(onDismiss = onClose, ignoreRelease = true) {
        val panel = min(maxWidth - PhonePad.Side * 2, if (touch) 520.dp else 820.dp)
        val frame = Modifier
            .width(panel)
            .floatingSurface()
            .padding(if (touch) 20.dp else 28.dp)
        val title = if (feature) s.feedbackFeatureTitle else s.feedbackTitle
        if (touch) {
            Column(frame, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = Tone.text)
                Text(if (feature) s.feedbackFeatureBodyTouch else s.feedbackBodyTouch, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
                TmButton(onClick = { if (openLink(context, issue)) onClose() }, modifier = Modifier.fillMaxWidth()) {
                    Label(s.feedbackOpenGithub)
                }
                TmSecondaryButton(
                    onClick = { if (sendMail(context, Feedback.mailto(facts, feature))) onClose() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Label(s.feedbackEmail) }
                Text(s.feedbackEmailDetail(Feedback.EMAIL), style = MaterialTheme.typography.bodySmall, color = Tone.muted)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TmSecondaryButton(onClick = onClose) { Label(s.commonClose) }
                }
            }
        } else {
            Row(frame, horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                QrPlate(issue, if (feature) s.feedbackFeatureQr else s.feedbackQr)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, color = Tone.text)
                    Text(if (feature) s.feedbackFeatureBodyTv else s.feedbackBodyTv, style = MaterialTheme.typography.bodyLarge, color = Tone.muted)
                    Text(
                        s.feedbackEmailDetailTv(Feedback.EMAIL, facts.version),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tone.muted,
                    )
                    Spacer(Modifier.size(4.dp))
                    TmButton(onClick = onClose, modifier = Modifier.focusRequester(close)) { Label(s.commonClose) }
                }
            }
        }
    }
    if (!touch) LaunchedEffect(Unit) { runCatching { close.requestFocus() } }
}

/**
 * The feedback window under a remote (HX64): the same steps and words as the phone's dialog
 * ([FeedbackBody]) in the television's own panel, with the phone route beside the form: typing
 * with a remote is slow, so the QR code of the GitHub page ([qrUrl]) is there too. Back closes and
 * keeps what is typed.
 */
@Composable
fun FeedbackTvPanel(state: FeedbackSender.State, logsAvailable: Boolean, actions: FeedbackActions, qrUrl: String, qrDescription: String) {
    FloatingWindow(onDismiss = actions.onClose, ignoreRelease = true) {
        val panel = min(maxWidth - PhonePad.Side * 2, 1000.dp)
        FeedbackTvContent(state, logsAvailable, actions, Modifier.width(panel).heightIn(max = maxHeight - 48.dp)) {
            if (state is FeedbackSender.State.Editing) QrPlate(qrUrl, qrDescription)
        }
    }
}

/** The panel's contents, apart from its window so a test can drive them by remote. [side] is drawn beside the steps (the QR code). */
@Composable
internal fun FeedbackTvContent(
    state: FeedbackSender.State,
    logsAvailable: Boolean,
    actions: FeedbackActions,
    modifier: Modifier = Modifier,
    side: @Composable () -> Unit = {},
) {
    val s = LocalStrings.current
    val safe = remember { FocusRequester() }
    Row(modifier.floatingSurface().padding(28.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(feedbackHeading(s, state), style = MaterialTheme.typography.headlineSmall, color = Tone.text)
            FeedbackBody(state, logsAvailable, actions)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                when (state) {
                    is FeedbackSender.State.Editing -> {
                        TmSecondaryButton(onClick = actions.onClose) { Text(s.commonClose) }
                        TmButton(onClick = actions.onNext) { Text(s.feedbackNext) }
                    }
                    is FeedbackSender.State.PreparingLogs ->
                        TmSecondaryButton(onClick = actions.onBack, modifier = Modifier.focusRequester(safe)) { Text(s.commonBack) }
                    is FeedbackSender.State.Review -> {
                        TmSecondaryButton(onClick = actions.onBack, modifier = Modifier.focusRequester(safe)) { Text(s.commonBack) }
                        TmButton(onClick = actions.onSend) { Text(s.feedbackSend) }
                    }
                    is FeedbackSender.State.Sending ->
                        TmSecondaryButton(onClick = actions.onClose, modifier = Modifier.focusRequester(safe)) { Text(s.commonCancel) }
                    is FeedbackSender.State.Queued -> {
                        TmSecondaryButton(onClick = actions.onClose, modifier = Modifier.focusRequester(safe)) { Text(s.commonClose) }
                        TmSecondaryButton(onClick = actions.onBack) { Text(s.feedbackEdit) }
                        TmButton(onClick = actions.onSend) { Text(s.commonTryAgain) }
                    }
                    is FeedbackSender.State.Refused -> {
                        TmSecondaryButton(onClick = actions.onClose, modifier = Modifier.focusRequester(safe)) { Text(s.commonClose) }
                        TmButton(onClick = actions.onBack) { Text(s.feedbackEdit) }
                    }
                    is FeedbackSender.State.Sent -> TmButton(onClick = actions.onClose, modifier = Modifier.focusRequester(safe)) { Text(s.commonClose) }
                    FeedbackSender.State.Idle, FeedbackSender.State.Opening -> Unit
                }
            }
        }
        if (state is FeedbackSender.State.Editing) {
            Column(Modifier.width(PLATE), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                side()
                Text(s.feedbackTvPhoneHint, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
            }
        }
    }
    // The press that changes nothing is focused at each step after the form (where the message
    // field takes the remote): a stray OK from the press that got here never sends.
    LaunchedEffect(state::class) { if (state !is FeedbackSender.State.Editing) runCatching { safe.requestFocus() } }
}

/** Opens a mailto link in the mail app, false when there is none. */
private fun sendMail(context: Context, mailto: String): Boolean = runCatching {
    context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse(mailto)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}.isSuccess

/** A QR code on a white plate, big enough to scan from a sofa. */
@Composable
private fun QrPlate(url: String, description: String) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = url) {
        value = withContext(Dispatchers.Default) { QrCode.render(url, QR_PIXELS) }
    }
    Box(
        Modifier.size(PLATE).clip(RoundedCornerShape(Corner.ExtraLarge)).background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { Image(it, contentDescription = description, modifier = Modifier.fillMaxSize().padding(12.dp)) }
    }
}

/** A button's words, in the library the button is drawn with (see Support.kt). */
@Composable
private fun Label(text: String) {
    if (isTouch()) androidx.compose.material3.Text(text) else Text(text)
}

private val PLATE = 260.dp
private const val QR_PIXELS = 600
