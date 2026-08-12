package com.tmplayer

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.runBlocking
import com.tmplayer.data.Account
import com.tmplayer.data.AuthState
import com.tmplayer.ui.auth.LoginScreen
import com.tmplayer.data.CardLayout
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.ThemeChoice
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.FormFactor
import com.tmplayer.data.MediaItem
import com.tmplayer.ui.browse.BrowseData
import com.tmplayer.ui.browse.BrowseScreen
import com.tmplayer.ui.browse.BrowseTab
import com.tmplayer.ui.browse.Header
import com.tmplayer.ui.browse.MediaCard
import com.tmplayer.ui.browse.TouchMediaScaffold
import com.tmplayer.ui.components.UiState
import com.tmplayer.ui.onboarding.OverviewScreen
import com.tmplayer.ui.settings.SettingsScreen
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.LocalDarkTheme
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tv

/**
 * A promo-build-only fixture used to capture honest UI without exposing a real Telegram account.
 *
 * Start it with `--es screen chats`, `media` or `settings`. Add `--ez tv true` to capture the
 * television layout on a phone panel resized to 1920x1080, which is how the TV shots on the site
 * are taken now that the stick is not the only device this app has to look right on.
 *
 * Every picture in here is a demo drawable shipped with this build. Nothing on screen belongs to
 * anybody, which is the point: the shots on the README and the site can be published as they are.
 */
class PromoCaptureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val tv = intent.getBooleanExtra("tv", false)
        // Before setContent, because the whole tree branches on it during the first composition.
        FormFactor.override(tv)
        requestedOrientation = if (tv) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        super.onCreate(savedInstanceState)
        if (tv) {
            // A television has no status bar and no gesture pill, and a shot of the TV layout with
            // a phone's clock and battery in the corner would be a picture of something that does
            // not exist.
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).hide(
                WindowInsetsCompat.Type.systemBars(),
            )
        }
        // `--es theme light|dark` pins the appearance, so the site can carry a light picture for
        // its light theme and a dark one for its dark theme rather than showing a black phone on
        // a white page. Written to this build's own preferences, which are its own: the promo
        // application id is separate, so nothing here can touch a real install's setting.
        intent.getStringExtra("theme")?.let { wanted ->
            val choice = when (wanted.lowercase()) {
                "light" -> ThemeChoice.Light
                "dark" -> ThemeChoice.Dark
                else -> ThemeChoice.System
            }
            runBlocking { SettingsStore(applicationContext).setThemeChoice(choice) }
        }

        val start = intent.getStringExtra("screen") ?: "chats"
        if (start == "signin") {
            // The number field takes focus the moment it appears, which is right in the app and
            // wrong in a screenshot: half the picture would be somebody's keyboard, and the
            // autofill strip above it offers the number of whoever is holding the phone.
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
        setContent {
            // The fixture navigates, so one recording can walk from the chat list into a chat and
            // back out the way a viewer would, rather than being three unrelated clips cut together.
            var screen by remember { mutableStateOf(start) }
            TMPlayerTheme {
                // The real app does this from MainActivity, which the fixture does not run
                // through. Without it a light shot carries a white clock on a white status bar,
                // which is a picture of a bug the app does not have.
                val dark = LocalDarkTheme.current
                LaunchedEffect(dark) {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                // The theme's own background, not the television's literal. Painting the dark
                // constant here put a black page behind a light-mode screen, so every light shot
                // taken from this fixture was of something the app never draws.
                Box(Modifier.fillMaxSize().background(Tone.background)) {
                    when (screen) {
                        "media" -> if (tv) {
                            TvMediaScreen()
                        } else {
                            PhoneMediaScreen(onBack = { screen = "chats" })
                        }
                        // The number pane, not the QR one. A shipped picture of a real QR is a
                        // working key to an account, which is why the old shot had to be blurred;
                        // an empty number field says the same thing and hides nothing.
                        "signin" -> LoginScreen(
                            state = AuthState.Phone(),
                            onSubmitPassword = {},
                            submitError = null,
                        )
                        "overview" -> OverviewScreen(onDone = { screen = "chats" })
                        "settings" -> SettingsScreen(
                            chats = promoChats(),
                            onLoggedOut = {},
                            onBack = { screen = "chats" },
                        )
                        else -> PromoChatsScreen(
                            onOpenChat = { screen = "media" },
                            onOpenSettings = { screen = "settings" },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun imageBytes(@DrawableRes drawable: Int): ByteArray {
    val resources = LocalContext.current.resources
    return remember(drawable) { resources.openRawResource(drawable).use { it.readBytes() } }
}

@Composable
private fun promoChats(): List<ChatSummary> = listOf(
    ChatSummary(101, "Weekend Clips", imageBytes(R.drawable.demo_coast), 0, ChatKind.Group),
    ChatSummary(102, "Home Projects", imageBytes(R.drawable.demo_workshop), 0, ChatKind.Channel),
    ChatSummary(103, "Recipe Notes", imageBytes(R.drawable.demo_kitchen), 0, ChatKind.Group),
    ChatSummary(104, "Travel Diary", imageBytes(R.drawable.demo_forest), 0, ChatKind.Channel),
    ChatSummary(105, "Design Study", imageBytes(R.drawable.demo_tutorial), 0, ChatKind.Direct),
    ChatSummary(106, "Family Archive", imageBytes(R.drawable.demo_birthday), 0, ChatKind.Group),
)

@Composable
private fun PromoChatsScreen(onOpenChat: () -> Unit = {}, onOpenSettings: () -> Unit = {}) {
    val chats = promoChats()
    val account = Account("Demo", "demo", null, 0)
    BrowseScreen(
        state = UiState.Content(BrowseData(chats, account)),
        favorites = setOf(102, 104),
        continueWatching = emptyList(),
        onRetry = {},
        onRefresh = {},
        onOpenChat = { onOpenChat() },
        onResumeMedia = {},
        onOpenSettings = onOpenSettings,
        onToggleFavorite = {},
        picked = BrowseTab.Recent,
        onPickTab = {},
        layout = CardLayout.List,
    )
}

/**
 * The phone's chat listing: an app bar, then Telegram's dense captionless grid.
 *
 * This is the real scaffold and the real card, not a drawing of them, so a shot taken here cannot
 * quietly disagree with what the app does.
 */
@Composable
private fun PhoneMediaScreen(onBack: () -> Unit = {}) {
    val media = promoMedia()
    TouchMediaScaffold(
        chatTitle = "Weekend Clips",
        chatPhotoFileId = 0,
        chatMiniThumbnail = imageBytes(R.drawable.demo_coast),
        isFavorite = true,
        query = "",
        onQuery = {},
        onSubmit = {},
        onBack = onBack,
        onToggleFavorite = {},
        layout = CardLayout.Grid,
        onToggleLayout = {},
        onRefresh = {},
    ) {
        LazyVerticalGrid(
            // Three, which is what the real grid computes for a 393dp phone from its minimum
            // tile width. The fixture has to draw what the app draws or the screenshots are of a
            // layout nobody has.
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(start = DENSE_GAP, end = DENSE_GAP),
            horizontalArrangement = Arrangement.spacedBy(DENSE_GAP),
            verticalArrangement = Arrangement.spacedBy(DENSE_GAP),
        ) {
            // Three times through the set, so the grid runs past the bottom of the panel the way a
            // real chat's does. A shot that ends in empty background reads as an empty chat, and
            // twice was enough only while a tile was a bare picture: the caption under each one
            // costs two lines and a meta row, which is a whole row of tiles fewer per screen.
            val tiles = (0 until 3).flatMap { pass ->
                media.map { it.copy(messageId = it.messageId + pass * media.size) }
            }
            items(tiles, key = { it.messageId }) { item ->
                MediaCard(item = item, watched = null, onClick = {}, onFocused = {}, dense = true)
            }
        }
    }
}

@Composable
private fun TvMediaScreen() {
    val media = promoMedia()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxSize()) {
        Header(
            chatTitle = "Weekend Clips",
            chatPhotoFileId = 0,
            chatMiniThumbnail = imageBytes(R.drawable.demo_coast),
            isFavorite = true,
            query = "",
            onQuery = {},
            onSubmit = {},
            onToggleFavorite = {},
            layout = CardLayout.Grid,
            // Screenshots are taken on a TV, so the heading starts inside the overscan.
            edge = Tv.SafeH,
            onToggleLayout = {},
            onRefresh = {},
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            contentPadding = PaddingValues(
                start = Tv.SafeH,
                end = Tv.SafeH,
                bottom = Tv.SafeV + 16.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(media, key = { it.id }) { item ->
                MediaCard(
                    item = item,
                    watched = null,
                    onClick = {},
                    onFocused = {},
                    modifier = if (item === media.first()) {
                        Modifier.focusRequester(first)
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}

@Composable
private fun promoMedia(): List<MediaItem> {
    @Composable
    fun item(
        id: Long,
        title: String,
        drawable: Int,
        sizeMb: Long,
        duration: Int,
        fileName: String,
    ) = MediaItem(
        chatId = 101,
        messageId = id,
        fileId = 0,
        title = title,
        sizeBytes = sizeMb * 1024 * 1024,
        durationSec = duration,
        mimeType = "video/mp4",
        thumbnailFileId = 0,
        miniThumbnail = imageBytes(drawable),
        date = 0,
        fileName = fileName,
    )
    return listOf(
        item(1, "Coast walk, day 2", R.drawable.demo_coast, 428, 1_482, "coast-walk-day-2-1080p.mp4"),
        item(2, "Chickpea salad recipe", R.drawable.demo_kitchen, 186, 724, "chickpea-salad-1080p.mp4"),
        item(3, "Build a small shelf, part 1", R.drawable.demo_workshop, 612, 2_115, "small-shelf-part-1-1080p.mkv"),
        item(4, "Birthday highlights", R.drawable.demo_birthday, 344, 1_104, "birthday-highlights-1080p.mp4"),
        item(5, "Shape basics tutorial", R.drawable.demo_tutorial, 238, 968, "shape-basics-1080p.webm"),
        item(6, "Forest trail morning", R.drawable.demo_forest, 391, 1_376, "forest-trail-1080p.mp4"),
    )
}

/** Telegram's grid gap, matched to [com.tmplayer.ui.browse] so the shot is the real spacing. */
private val DENSE_GAP = 2.dp
