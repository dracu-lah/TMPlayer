package com.tmplayer.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.DeviceCountry
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.SupportReminder
import com.tmplayer.ui.about.About
import com.tmplayer.ui.about.BmcBrand
import com.tmplayer.ui.about.BmcCupMark
import com.tmplayer.ui.about.SupportActions
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.update.openLink
import kotlinx.coroutines.launch

/**
 * What the shared support window does on Android: the browser (a television has none, so it
 * answers false and the window shows a code), the clipboard, the `upi://` link for a UPI app
 * (false when no app answers, which the window then says), and the star being remembered.
 */
@Composable
private fun rememberSupportActions(): SupportActions {
    val context = LocalContext.current
    return remember(context) {
        SupportActions(
            openLink = { url -> openLink(context, url) },
            copy = { text ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                // A refused or missing clipboard leaves the ID on screen to copy by hand.
                runCatching { clipboard?.setPrimaryClip(ClipData.newPlainText(text, text)) != null }.getOrDefault(false)
            },
            // A television has no UPI app, and nothing here ever opens a payment on its own: this
            // runs only when "Open in a UPI app" is pressed.
            openUpiApp = if (com.tmplayer.data.FormFactor.isTv(context)) {
                null
            } else {
                {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(About.UPI_LINK)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.isSuccess
                }
            },
            onStarred = {
                // A star given is never asked for again (CP76).
                val store = SettingsStore(context.applicationContext)
                com.tmplayer.App.backgroundScope.launch { runCatching { store.markSupportStarred() } }
            },
        )
    }
}

/**
 * "Support TMPlayer", opened from Settings and About: the shared window with Buy Me a Coffee and
 * UPI as cards, the region's way first (D1). Nothing opens until a card or button is pressed.
 */
@Composable
fun SupportDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val order = remember(context) { DeviceCountry.supportOrder(context) }
    com.tmplayer.ui.about.SupportDialog(
        order = order,
        title = About.SUPPORT_TITLE,
        text = About.SUPPORT_NOTE,
        actions = rememberSupportActions(),
        onClose = onClose,
    )
}

/**
 * The gentle ask, at a good moment and at most every two weeks (see [SupportReminder]), or the same
 * window opened from About. "Maybe later" is [onLater], "Don't show again" is [onNever]; Back is
 * "Maybe later". Opened from About ([asking] false) it is the plain window with Close.
 */
@Composable
fun CoffeeDialog(
    counters: SupportReminder.Counters,
    asking: Boolean,
    onLater: () -> Unit,
    onNever: () -> Unit,
) {
    val context = LocalContext.current
    val order = remember(context) { DeviceCountry.supportOrder(context) }
    com.tmplayer.ui.about.SupportDialog(
        order = order,
        title = if (asking) About.reminderTitle(1) else About.SUPPORT_TITLE,
        text = if (asking) About.reminderText(1, counters) else About.SUPPORT_NOTE,
        actions = rememberSupportActions(),
        onClose = onLater,
        onNever = if (asking) onNever else null,
    )
}

/**
 * Buy Me a Coffee's own button: their yellow, their near-black words and their cup, as their
 * official buttons are drawn ([BmcBrand]). For a phone, where it opens the page in the browser.
 */
@Composable
fun CoffeeButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    if (!isTouch()) {
        // The remote's version, for About, where it opens the code: the same yellow at rest and
        // under the focus, which TV Material marks by growing the button and ringing it in ink.
        androidx.tv.material3.Button(
            onClick = onClick,
            modifier = modifier.heightIn(min = 56.dp),
            shape = androidx.tv.material3.ButtonDefaults.shape(RoundedCornerShape(Corner.Medium)),
            colors = androidx.tv.material3.ButtonDefaults.colors(
                containerColor = BmcBrand.Yellow,
                contentColor = BmcBrand.Ink,
                focusedContainerColor = BmcBrand.Yellow,
                focusedContentColor = BmcBrand.Ink,
                pressedContainerColor = BmcBrand.Yellow,
                pressedContentColor = BmcBrand.Ink,
            ),
            border = androidx.tv.material3.ButtonDefaults.border(
                focusedBorder = androidx.tv.material3.Border(
                    androidx.compose.foundation.BorderStroke(3.dp, BmcBrand.Ink),
                    shape = RoundedCornerShape(Corner.Medium),
                ),
            ),
        ) {
            BmcCupMark(height = 24.dp)
            Spacer(Modifier.width(12.dp))
            Text(s.supportBuyCoffee, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        return
    }
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 56.dp),
        shape = RoundedCornerShape(Corner.Medium),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = BmcBrand.Yellow,
            contentColor = BmcBrand.Ink,
        ),
    ) {
        BmcCupMark(height = 24.dp)
        Spacer(Modifier.width(12.dp))
        androidx.compose.material3.Text(
            s.supportBuyCoffee,
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
