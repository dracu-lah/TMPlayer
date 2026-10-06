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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.BuildConfig
import com.tmplayer.data.Feedback
import com.tmplayer.data.FormFactor
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.WhatsNew
import com.tmplayer.i18n.LanguageNotice
import com.tmplayer.i18n.Translator
import com.tmplayer.ui.auth.QrCode
import com.tmplayer.ui.components.FloatingWindow
import com.tmplayer.ui.components.PhonePad
import com.tmplayer.ui.components.TmButton
import com.tmplayer.ui.components.TmSecondaryButton
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
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
 * row and closes with Close or Back; a TV walks the list with the D-pad and closes with Back.
 */
@Composable
fun LanguageDialog(settings: SettingsStore, onClose: () -> Unit) {
    val s = LocalStrings.current
    val touch = isTouch()
    val close = remember { FocusRequester() }
    FloatingWindow(onDismiss = onClose) {
        val panel = min(maxWidth - PhonePad.Side * 2, if (touch) 520.dp else 640.dp)
        Column(
            Modifier
                .width(panel)
                .heightIn(max = maxHeight - 48.dp)
                .floatingSurface()
                .padding(if (touch) 20.dp else 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(s.languagePickerTitle, style = MaterialTheme.typography.headlineSmall, color = Tone.text)
            Text(s.languagePickerNote, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
            if (touch) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    LanguageList(settings, columns = 1)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TmSecondaryButton(onClick = onClose) { Label(s.commonClose) }
                }
            } else {
                BackHandler(onBack = onClose)
                TvLanguages(settings, Modifier.weight(1f, fill = false).focusRequester(close))
            }
        }
    }
    if (!touch) LaunchedEffect(Unit) { runCatching { close.requestFocus() } }
}

/**
 * The one-time "Now in Español" card, over the chat list like the support card: the app has
 * followed the device into a language, and this says so in that language, once, with a way out.
 */
@Composable
fun LanguageNoticeCard(language: String, onKeep: () -> Unit, onChange: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val touch = isTouch()
    val keep = remember { FocusRequester() }
    if (!touch) BackHandler(onBack = onKeep)
    Column(
        modifier
            .widthIn(max = if (touch) 520.dp else 560.dp)
            .floatingSurface()
            .padding(if (touch) 16.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = Tone.accent, modifier = Modifier.size(24.dp))
            Text(s.languageNowIn(LanguageNotice.name(language)), style = MaterialTheme.typography.titleMedium, color = Tone.text)
        }
        Text(s.languageNowInBody, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
        CompositionLocalProvider(LocalOnFloating provides true) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TmSecondaryButton(onClick = onChange) { Label(s.commonChange) }
                TmButton(onClick = onKeep, modifier = Modifier.focusRequester(keep)) { Label(s.languageKeep) }
            }
        }
    }
    if (!touch) LaunchedEffect(Unit) { runCatching { keep.requestFocus() } }
}

/**
 * "What's new": the bundled highlights of [WhatsNew.VERSION], on the first run of that version and
 * from Settings. "Full changelog" opens the site's changelog, or its QR code on a TV.
 */
@Composable
fun WhatsNewDialog(onClose: () -> Unit, onChangelog: () -> Unit) {
    val s = LocalStrings.current
    val touch = isTouch()
    val close = remember { FocusRequester() }
    FloatingWindow(onDismiss = onClose) {
        val panel = min(maxWidth - PhonePad.Side * 2, if (touch) 520.dp else 720.dp)
        Column(
            Modifier
                .width(panel)
                .heightIn(max = maxHeight - 48.dp)
                .floatingSurface()
                .verticalScroll(rememberScrollState())
                .padding(if (touch) 20.dp else 28.dp),
            verticalArrangement = Arrangement.spacedBy(if (touch) 12.dp else 14.dp),
        ) {
            Text(s.whatsnewTitle(WhatsNew.VERSION), style = MaterialTheme.typography.headlineSmall, color = Tone.text)
            Text(s.whatsnewSubtitle, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
            for (line in WhatsNew.highlights) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Tone.accent, modifier = Modifier.size(22.dp))
                    Text(line, style = MaterialTheme.typography.bodyLarge, color = Tone.text)
                }
            }
            CompositionLocalProvider(LocalOnFloating provides true) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TmSecondaryButton(onClick = onChangelog) { Label(s.whatsnewFullChangelog) }
                    TmButton(onClick = onClose, modifier = Modifier.focusRequester(close)) { Label(s.commonClose) }
                }
            }
        }
    }
    if (!touch) LaunchedEffect(Unit) { runCatching { close.requestFocus() } }
}

/** What a report from this device says about it. No account, chat or file: see [Feedback]. */
fun feedbackFacts(context: Context): Feedback.Facts {
    val tv = FormFactor.isTv(context)
    val fire = context.packageManager.hasSystemFeature("amazon.hardware.fire_tv")
    val platform = when {
        fire -> Feedback.Platform.FireTv
        tv -> Feedback.Platform.AndroidTv
        else -> Feedback.Platform.Phone
    }
    val model = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.distinct().joinToString(" ")
    return Feedback.Facts(
        version = BuildConfig.VERSION_NAME,
        platform = platform,
        device = "$model, Android ${Build.VERSION.RELEASE}",
        language = Translator.messages.tag,
    )
}

/**
 * "Report a problem". A phone opens the prefilled bug report in the browser, with an email as the
 * way round for somebody without a GitHub account. A TV has no browser, so the report is a QR code
 * for the phone in the viewer's hand, with the address to write to under it.
 */
@Composable
fun FeedbackDialog(onClose: () -> Unit) {
    val s = LocalStrings.current
    val touch = isTouch()
    val context = LocalContext.current
    val facts = remember { feedbackFacts(context) }
    val issue = remember(facts) { Feedback.issueUrl(facts) }
    val close = remember { FocusRequester() }

    FloatingWindow(onDismiss = onClose, ignoreRelease = true) {
        val panel = min(maxWidth - PhonePad.Side * 2, if (touch) 520.dp else 820.dp)
        val frame = Modifier
            .width(panel)
            .floatingSurface()
            .padding(if (touch) 20.dp else 28.dp)
        if (touch) {
            Column(frame, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(s.feedbackTitle, style = MaterialTheme.typography.headlineSmall, color = Tone.text)
                Text(s.feedbackBodyTouch, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
                TmButton(onClick = { if (openLink(context, issue)) onClose() }, modifier = Modifier.fillMaxWidth()) {
                    Label(s.feedbackOpenGithub)
                }
                TmSecondaryButton(
                    onClick = { if (sendMail(context, Feedback.mailto(facts))) onClose() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Label(s.feedbackEmail) }
                Text(s.feedbackEmailDetail(Feedback.EMAIL), style = MaterialTheme.typography.bodySmall, color = Tone.muted)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TmSecondaryButton(onClick = onClose) { Label(s.commonClose) }
                }
            }
        } else {
            Row(frame, horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                QrPlate(issue, s.feedbackQr)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(s.feedbackTitle, style = MaterialTheme.typography.headlineSmall, color = Tone.text)
                    Text(s.feedbackBodyTv, style = MaterialTheme.typography.bodyLarge, color = Tone.muted)
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
