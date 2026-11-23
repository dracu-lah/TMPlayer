package com.tmplayer.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tmplayer.data.DeviceCountry
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.SupportReminder
import com.tmplayer.ui.about.About
import com.tmplayer.ui.about.BmcBrand
import com.tmplayer.ui.about.BmcCupMark
import com.tmplayer.ui.about.SupportActions
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.update.openLink
import com.tmplayer.ui.update.readableUrl
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
 * A television draws [CoffeeRow] instead: a yellow slab in a list of rows is the one control that
 * has to be told apart from its neighbours by more than a fill, and nothing on a remote may grow.
 */
@Composable
fun CoffeeButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
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

/**
 * Buy Me a Coffee on a television (WP-03, designer #2): the same row as GitHub Sponsors under it,
 * with the cup on a 40 dp yellow tile where the others have their icon chip, the title and the
 * readable address of the page. One list, one focus look (the row's fill and ring), nothing grows.
 * The page itself is shown as a code, as a television has no browser.
 */
@Composable
fun CoffeeRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    ActionRow(
        title = s.supportBuyCoffee,
        subtitle = readableUrl(About.COFFEE),
        icon = TmIcons.Favorite,
        modifier = modifier,
        mark = {
            Box(
                Modifier.size(COFFEE_TILE).clip(RoundedCornerShape(Corner.Medium)).background(BmcBrand.Yellow),
                contentAlignment = Alignment.Center,
            ) { BmcCupMark(height = 24.dp) }
        },
        markSize = COFFEE_TILE,
        onClick = onClick,
    )
}

private val COFFEE_TILE = 40.dp
