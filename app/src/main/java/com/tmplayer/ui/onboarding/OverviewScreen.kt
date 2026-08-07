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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.R
import com.tmplayer.ui.theme.Accent
import com.tmplayer.ui.theme.TextMuted
import com.tmplayer.ui.theme.TextPrimary
import com.tmplayer.ui.theme.Tv
import com.tmplayer.ui.theme.tmButtonColors

/** One page: what it shows, and the picture of the app actually showing it. */
private data class Page(val title: String, val body: String, val image: Int)

/**
 * A short walk through the app, in pictures of the app itself.
 *
 * Every illustration is a real screenshot rather than a drawing, so a beginner is looking for the
 * thing they will actually see. They are stored small (960 wide, WebP): a television scales them
 * up to half the screen and nobody is inspecting the text in them.
 *
 * Shown once before signing in, and again whenever Settings asks for it.
 */
@Composable
fun OverviewScreen(onDone: () -> Unit) {
    var index by remember { mutableIntStateOf(0) }
    val page = PAGES[index]
    val last = index == PAGES.lastIndex
    val next = remember { FocusRequester() }

    // Back walks the pages in reverse, which is what it does everywhere else in the app, and
    // leaves altogether from the first one rather than trapping anybody here.
    BackHandler { if (index == 0) onDone() else index-- }

    Row(
        Modifier.fillMaxSize().padding(horizontal = Tv.SafeH, vertical = Tv.SafeV),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Step ${index + 1} of ${PAGES.size}",
                style = MaterialTheme.typography.bodyMedium,
                color = Accent,
            )
            Text(page.title, style = MaterialTheme.typography.headlineLarge, color = TextPrimary)
            Text(page.body, style = MaterialTheme.typography.bodyLarge, color = TextMuted)

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { if (last) onDone() else index++ },
                    colors = tmButtonColors(),
                    modifier = Modifier.focusRequester(next),
                ) {
                    Text(if (last) "Start" else "Next")
                    if (!last) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                if (!last) {
                    Button(onClick = onDone, colors = tmButtonColors()) { Text("Skip") }
                }
            }
        }

        Image(
            painter = painterResource(page.image),
            // The title beside it is the description; a second reading of the same thing is noise.
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .weight(1.25f)
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, TextMuted.copy(alpha = 0.25f), RoundedCornerShape(16.dp)),
        )
    }

    // Focus follows the page, so Next stays under the thumb through the whole walk.
    LaunchedEffect(index) { runCatching { next.requestFocus() } }
}

private val PAGES = listOf(
    Page(
        title = "Sign in with your phone",
        body = "TMPlayer shows a code. Open Telegram on your phone, go to Settings then Devices, " +
            "and point the camera at the TV. Nothing is typed on the remote.",
        // Deliberately blurred: the real code on that screen is a live sign-in token, and a
        // scannable one shipped inside the app would be a working key to somebody's account.
        image = R.drawable.overview_signin,
    ),
    Page(
        title = "Your Telegram chats, on the left",
        body = "The rail sorts them: channels, groups, people, or everything at once. Star the " +
            "chats you watch from and they sit in Favourites, one press away.",
        image = R.drawable.overview_chats,
    ),
    Page(
        title = "Open a chat to see its films",
        body = "TMPlayer lists the videos posted in that chat, newest first, with their size and " +
            "quality. Short clips are filtered out; the size limits are yours to change.",
        image = R.drawable.overview_films,
    ),
    Page(
        title = "Hold OK for more",
        body = "A press plays. Holding OK opens what else can be done: the story, the cast and a " +
            "trailer for a film, or the star and the menu for a chat.",
        image = R.drawable.overview_details,
    ),
    Page(
        title = "It downloads, then plays",
        body = "The film is fetched from Telegram as you watch, and TMPlayer keeps one film on " +
            "this TV at a time. Everything else, including this walkthrough, is in Settings.",
        image = R.drawable.overview_playing,
    ),
)
