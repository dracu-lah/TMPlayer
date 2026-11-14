package com.tmplayer.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.tmplayer.data.DeviceForm
import com.tmplayer.ui.about.About
import com.tmplayer.ui.auth.QrCode
import com.tmplayer.ui.components.deviceForm
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The crash report question (CP102f): one switch, "Send crash reports", with the line saying what
 * a report holds under it, and the way to the privacy page. Off until somebody turns it on, and
 * nothing beside it says which answer is better: it is consent, not a preference to nudge.
 *
 * Drawn on its own so the setup's step today and CP100's Preferences screen later show the same
 * row. [on] is what is marked now; [onToggle] flips it, and the caller decides when it is written.
 *
 * A phone or a desktop opens the privacy page in the browser. A television mostly has none, so
 * there, or where opening fails, the page's address comes up as a QR code to read with a phone.
 */
@Composable
fun CrashReportsQuestion(
    on: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val uri = LocalUriHandler.current
    val tv = deviceForm() == DeviceForm.Tv
    var qr by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OptionRow(
            title = s.settingsCrashReports,
            // A computer's report names the operating system, not a device (CP102g).
            detail = if (deviceForm() == DeviceForm.Desktop) s.setupCrashHoldsDesktop else s.setupCrashHolds,
            selected = on,
            role = Role.Switch,
            modifier = Modifier.arrowsMoveFocus(),
            onClick = onToggle,
        )
        SetupButton(
            label = s.setupCrashPrivacy,
            modifier = Modifier.arrowsMoveFocus(),
        ) {
            qr = when {
                qr -> false
                tv -> true
                else -> runCatching { uri.openUri(About.PRIVACY) }.isFailure
            }
        }
        if (qr) PrivacyQr()
    }
}

/** The privacy page's address as a QR code on a white plate, with the address in words beside it. */
@Composable
internal fun PrivacyQr() {
    val s = LocalStrings.current
    val bitmap: ImageBitmap? by produceState<ImageBitmap?>(null) {
        value = withContext(Dispatchers.Default) { QrCode.render(About.PRIVACY, QR_PIXELS) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(QR_SIZE).clip(RoundedCornerShape(Corner.Medium)).background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let {
                Image(
                    it,
                    contentDescription = s.updateQrFor(what = s.settingsQrPrivacyPage),
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                s.updateOpenOnPhone(what = s.settingsQrPrivacyPage),
                style = MaterialTheme.typography.bodyMedium,
                color = Tone.text,
            )
            // The address in words, for anybody who would rather type it. Without the slash at the
            // end, which a right-to-left line would otherwise move to the front.
            Text(
                About.PRIVACY.removePrefix("https://").removeSuffix("/"),
                style = MaterialTheme.typography.bodySmall,
                color = Tone.muted,
            )
        }
    }
}

private const val QR_PIXELS = 360
private val QR_SIZE = 120.dp
