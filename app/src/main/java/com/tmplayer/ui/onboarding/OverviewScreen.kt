package com.tmplayer.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.LinearProgressIndicator as M3LinearProgressIndicator
import androidx.compose.material3.MaterialTheme as M3MaterialTheme
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.R
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.LocalDarkTheme
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import com.tmplayer.ui.components.Pane
import com.tmplayer.ui.components.TmButton
import com.tmplayer.ui.components.TmSecondaryButton
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.components.paneAction

/**
 * One page: what it shows, and the picture of the app actually showing it.
 *
 * Three pictures, because there are three ways this app looks. [image] is the television, which is
 * dark and has no other mode to be in. [phoneLight] and [phoneDark] are the phone, where a dark
 * screenshot on a light page is somebody else's app.
 */
private data class Page(
    val title: String,
    val body: String,
    val image: Int,
    val phoneLight: Int,
    val phoneDark: Int,
)

/**
 * A short walk through the app, in pictures of the app itself.
 *
 * Every illustration is a real screenshot rather than a drawing, so a beginner is looking for the
 * thing they will actually see. Shown once before signing in, and again whenever Settings asks.
 */
@Composable
fun OverviewScreen(onDone: () -> Unit) {
    var index by remember { mutableIntStateOf(0) }
    val touch = isTouch()
    val PAGES = remember(touch) { pages(touch) }
    val page = PAGES[index]
    val last = index == PAGES.lastIndex
    val next = remember { FocusRequester() }

    // Back walks the pages in reverse, as it does everywhere else in the app, and leaves
    // altogether from the first one rather than trapping anybody here.
    BackHandler { if (index == 0) onDone() else index-- }

    if (touch) {
        // `index` stays the one place the page number lives, so Back and the Next button keep
        // working; the two effects below hold the pager and it in step in both directions.
        val pager = rememberPagerState(initialPage = index) { PAGES.size }
        LaunchedEffect(pager) {
            snapshotFlow { pager.currentPage }.collect { index = it }
        }
        LaunchedEffect(index) {
            if (pager.currentPage != index) pager.animateScrollToPage(index)
        }

        // Stacked rather than side by side: a screenshot beside the words in a portrait column
        // would be a couple of centimetres wide. It goes under the text at full width, and the
        // buttons follow it so the thumb finds them at the bottom of the reading order.
        Pane(spacing = 12.dp) {
            // The bar says "step 2 of 3" without being read, and carries the count in its
            // semantics for the people who are having the screen read to them.
            M3LinearProgressIndicator(
                progress = { (index + 1).toFloat() / PAGES.size },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "Step ${index + 1} of ${PAGES.size}"
                    },
            )

            // Only the words and the picture move under the finger. The buttons below stay put: a
            // Next that slid away with its page would be a Next nobody could press twice in a row.
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxWidth(),
                pageSpacing = 16.dp,
                verticalAlignment = Alignment.Top,
            ) { pageIndex ->
                val shown = PAGES[pageIndex]
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    M3Text(
                        shown.title,
                        style = M3MaterialTheme.typography.headlineSmall,
                        color = Tone.text,
                    )
                    M3Text(
                        shown.body,
                        style = M3MaterialTheme.typography.bodyMedium,
                        color = Tone.muted,
                    )
                    PageImage(shown, Modifier.fillMaxWidth())
                }
            }

            TmButton(
                onClick = { if (last) onDone() else index++ },
                modifier = Modifier.paneAction(),
            ) {
                M3Text(if (last) "Start" else "Next")
                if (!last) {
                    Spacer(Modifier.width(8.dp))
                    M3Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (!last) {
                TmSecondaryButton(onClick = onDone, modifier = Modifier.paneAction()) {
                    M3Text("Skip")
                }
            }
            // Clears the gesture bar's own strip, which a button flush to the bottom of the scroll
            // would otherwise sit under.
            Spacer(Modifier.height(8.dp))
        }
    } else {
        Row(
            Modifier.fillMaxSize().padding(horizontal = Tv.SafeH, vertical = Tv.SafeV),
            horizontalArrangement = Arrangement.spacedBy(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Step ${index + 1} of ${PAGES.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tone.accent,
                )
                Text(
                    page.title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = Tone.text,
                )
                Text(page.body, style = MaterialTheme.typography.bodyLarge, color = Tone.muted)

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TmButton(
                        onClick = { if (last) onDone() else index++ },
                        modifier = Modifier.focusRequester(next),
                    ) {
                        Text(if (last) "Start" else "Next")
                        if (!last) {
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    if (!last) {
                        TmSecondaryButton(onClick = onDone) { Text("Skip") }
                    }
                }
            }

            PageImage(page, Modifier.weight(1.25f).fillMaxWidth())
        }
    }

    // Focus follows the page, so Next stays under the remote through the whole walk. A phone has
    // nothing to move, and a focus ring drawn there would be a mark with no explanation.
    if (!touch) {
        LaunchedEffect(index) { runCatching { next.requestFocus() } }
    }
}

/**
 * The screenshot for a page, in the shape and the appearance of the device reading it.
 *
 * A television gets the landscape shot of a television. A phone gets the top of a phone screen,
 * light or dark to match what the reader is looking at, which is why the box is taller there.
 */
@Composable
private fun PageImage(page: Page, modifier: Modifier) {
    val touch = isTouch()
    val image = when {
        !touch -> page.image
        LocalDarkTheme.current -> page.phoneDark
        else -> page.phoneLight
    }
    Image(
        painter = painterResource(image),
        // The title beside it is the description; a second reading of the same thing is noise.
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .aspectRatio(if (touch) 108f / 140f else 16f / 9f)
            .clip(RoundedCornerShape(Corner.Large))
            .border(1.dp, Tone.outline, RoundedCornerShape(Corner.Large)),
    )
}

/**
 * The walkthrough, in the words of whichever device is reading it.
 *
 * A phone has no remote to point at a TV and no rail, only a drawer, so on touch the words describe
 * the app rather than the picture.
 */
private fun pages(touch: Boolean) = listOf(
    Page(
        title = if (touch) "Sign in with your number" else "Sign in with your phone",
        body = if (touch) {
            "Type the number your Telegram account is registered to and TMPlayer sends you a " +
                "code. If your account lives on another phone, scan a QR code instead."
        } else {
            "TMPlayer shows a code. Open Telegram on your phone, go to Settings then Devices, " +
                "and point the camera at the TV. Nothing is typed on the remote."
        },
        // Deliberately blurred: the real code on that screen is a live sign-in token, and a
        // scannable one shipped inside the app would be a working key to somebody's account.
        image = R.drawable.overview_signin,
        phoneLight = R.drawable.overview_signin_touch,
        phoneDark = R.drawable.overview_signin_touch_dark,
    ),
    Page(
        title = if (touch) "Your Telegram chats" else "Your Telegram chats, on the left",
        body = if (touch) {
            "The menu sorts them: channels, groups, people, or everything at once. Star the " +
                "chats you watch from and they sit in Favourites, one tap away."
        } else {
            "The rail sorts them: channels, groups, people, or everything at once. Star the " +
                "chats you watch from and they sit in Favourites, one press away."
        },
        image = R.drawable.overview_chats,
        phoneLight = R.drawable.overview_chats_touch,
        phoneDark = R.drawable.overview_chats_touch_dark,
    ),
    Page(
        title = "Open a chat to see its videos",
        body = "TMPlayer lists the videos posted in that chat, newest first, with their size and " +
            "quality. Short clips are filtered out; the size limits are yours to change.",
        image = R.drawable.overview_media,
        phoneLight = R.drawable.overview_media_touch,
        phoneDark = R.drawable.overview_media_touch_dark,
    ),
)
