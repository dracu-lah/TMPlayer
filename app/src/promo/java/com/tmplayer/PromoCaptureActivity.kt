package com.tmplayer

import android.os.Bundle
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tmplayer.data.Account
import com.tmplayer.data.CardLayout
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.MediaItem
import com.tmplayer.ui.browse.BrowseData
import com.tmplayer.ui.browse.BrowseScreen
import com.tmplayer.ui.browse.BrowseTab
import com.tmplayer.ui.browse.Header
import com.tmplayer.ui.browse.MediaCard
import com.tmplayer.ui.components.UiState
import com.tmplayer.ui.settings.SettingsScreen
import com.tmplayer.ui.theme.Background
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tv

/**
 * A promo-build-only fixture used to capture honest UI without exposing a real Telegram account.
 * Start it with `--es screen chats`, `media`, or `settings`.
 */
class PromoCaptureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = intent.getStringExtra("screen") ?: "chats"
        setContent {
            TMPlayerTheme {
                Box(Modifier.fillMaxSize().background(Background)) {
                    when (screen) {
                        "media" -> PromoMediaScreen()
                        "settings" -> SettingsScreen(chats = promoChats(), onLoggedOut = {})
                        else -> PromoChatsScreen()
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
private fun PromoChatsScreen() {
    val chats = promoChats()
    val account = Account("Demo account", "local_demo", null, 0)
    BrowseScreen(
        state = UiState.Content(BrowseData(chats, account)),
        favorites = setOf(102, 104),
        continueWatching = emptyList(),
        onRetry = {},
        onRefresh = {},
        onOpenChat = {},
        onResumeMedia = {},
        onOpenSettings = {},
        onToggleFavorite = {},
        picked = BrowseTab.Recent,
        onPickTab = {},
        layout = CardLayout.List,
    )
}

@Composable
private fun PromoMediaScreen() {
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
