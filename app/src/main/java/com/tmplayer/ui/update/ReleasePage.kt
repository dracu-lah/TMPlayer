package com.tmplayer.ui.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.FormFactor
import com.tmplayer.data.Release
import com.tmplayer.data.UpdateFeed
import com.tmplayer.ui.auth.QrCode
import com.tmplayer.ui.components.PhonePad
import com.tmplayer.ui.components.TmButton
import com.tmplayer.ui.components.ignoreStrayRelease
import com.tmplayer.ui.theme.Caution
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where "the release page" points: the offered release's own page, or the list of releases when
 * nothing is on offer.
 */
internal fun releasePageUrl(release: Release?): String =
    release?.pageUrl?.takeIf { it.isNotBlank() } ?: UpdateFeed.RELEASES_PAGE

/** The address as a person would type it into a phone: no scheme in front. */
internal fun readableUrl(url: String): String = url.removePrefix("https://").removePrefix("http://")

/**
 * Opens the release page, and says whether that worked.
 *
 * A phone hands it to the browser. A television mostly has none, and the ones that do have a
 * browser nobody wants to type into with a remote, so a TV answers false and the caller shows
 * [ReleasePageQrDialog] instead. So does a phone with no browser installed.
 */
internal fun openReleasePage(context: Context, url: String): Boolean {
    if (FormFactor.isTv(context)) return false
    return runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}

/**
 * The release page as a QR code, for a television without a browser: the code, the address in
 * words for anybody who would rather type it, and Close, which holds the remote's focus.
 *
 * The code is the sign in screen's renderer, black on a white plate, for the same reason: it has
 * to read across a room.
 */
@Composable
fun ReleasePageQrDialog(url: String, onClose: () -> Unit) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = url) {
        value = withContext(Dispatchers.Default) { QrCode.render(url, QR_PIXELS) }
    }
    val close = remember { FocusRequester() }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .ignoreStrayRelease()
                .background(Color.Black.copy(alpha = 0.82f)),
            contentAlignment = Alignment.Center,
        ) {
            val panel = min(maxWidth - PhonePad.Side * 2, PANEL_MAX)
            val plate: @Composable () -> Unit = {
                Box(
                    Modifier
                        .size(PLATE)
                        .clip(RoundedCornerShape(Corner.ExtraLarge))
                        .background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    bitmap?.let {
                        Image(
                            bitmap = it,
                            contentDescription = "QR code for the release page",
                            // A scanner needs a quiet margin of the code's own white to find it.
                            modifier = Modifier.fillMaxSize().padding(12.dp),
                        )
                    }
                }
            }
            val words: @Composable ColumnScope.() -> Unit = {
                Text(
                    "Open the release page on your phone",
                    style = MaterialTheme.typography.titleLarge,
                    color = Tone.text,
                )
                Text(
                    "This TV has no browser to open it in. Point your phone's camera at the code, " +
                        "or type the address.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Tone.muted,
                )
                Text(readableUrl(url), style = MaterialTheme.typography.bodyLarge, color = Tone.text)
                Spacer(Modifier.height(6.dp))
                TmButton(onClick = onClose, modifier = Modifier.focusRequester(close)) { Text("Close") }
            }

            val frame = Modifier
                .width(panel)
                .clip(RoundedCornerShape(Corner.ExtraLarge))
                .background(Tone.surface)
                .border(1.dp, Caution.copy(alpha = 0.35f), RoundedCornerShape(Corner.ExtraLarge))
                .padding(28.dp)
            // Side by side on a television, which is wide and short; stacked on a phone held upright.
            if (panel >= SIDE_BY_SIDE) {
                Row(frame, horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    plate()
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = words)
                }
            } else {
                Column(
                    frame,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    plate()
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = words)
                }
            }
        }
    }

    // Close is the only thing on the pane, so the remote starts there.
    LaunchedEffect(Unit) { runCatching { close.requestFocus() } }
}

/** The code's plate: big enough to scan from a sofa, small enough to sit beside its words. */
private val PLATE = 240.dp

/** Pixels the code is drawn at, so it stays crisp on a 4K panel. */
private const val QR_PIXELS = 560

private val PANEL_MAX = 680.dp

/** Narrower than this and the code goes above the words rather than beside them. */
private val SIDE_BY_SIDE = 560.dp
