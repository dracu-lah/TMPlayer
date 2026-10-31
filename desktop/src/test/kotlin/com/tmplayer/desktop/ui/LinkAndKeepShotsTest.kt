package com.tmplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.tmplayer.data.ChatInfo
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.InvitePreview
import com.tmplayer.data.MediaItem
import com.tmplayer.data.PinnedPost
import com.tmplayer.data.TgLink
import com.tmplayer.ui.browse.LinkFlow
import com.tmplayer.ui.browse.LinkStep
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The pieces CP79, CP81 and CP82 add to the desktop, drawn on its own renderer in both themes: an
 * invite's card, a public channel's card, a refusal, the pinned post and round video entries over
 * a chat's posters, and a line of the forward picker. Neither card ever joins the chat (HX30):
 * both offer to open it in Telegram instead. With TMPLAYER_SHOTS=<dir> the frames are written
 * there as `link-*.png`.
 */
class LinkAndKeepShotsTest {

    private val flow = LinkFlow(CoroutineScope(Dispatchers.Unconfined), onPlay = {}, onOpenChat = {}, say = {})

    private val channel = ChatSummary(id = 1, title = "Nature Clips", miniThumbnail = null, photoFileId = 0, kind = ChatKind.Channel)

    private val invite = InvitePreview(
        link = TgLink.Invite("AbCdEf123456"),
        title = "Weekend Course",
        isChannel = false,
        photoFileId = 0,
        bigPhotoFileId = 0,
        miniThumbnail = null,
        memberCount = 312,
        description = "Recorded lessons, one a week, posted on Saturdays.",
        createsJoinRequest = false,
        paid = false,
        chatId = 0,
        alreadyMember = false,
    )

    private val video = MediaItem(
        chatId = 1, messageId = 9, fileId = 3, title = "Welcome, start here", sizeBytes = 48_000_000, durationSec = 240,
        mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 0,
    )

    @Test
    fun linkCardsAndEntriesDraw() {
        val shots: List<Pair<String, @Composable () -> Unit>> = listOf(
            "invite" to { LinkStepCard(LinkStep.Invite(invite), flow) },
            "invite-request" to { LinkStepCard(LinkStep.Invite(invite.copy(createsJoinRequest = true)), flow) },
            "public" to {
                LinkStepCard(
                    LinkStep.Public(ChatInfo(channel, 0, "nature_clips", "Short videos from walks and trips.", 12_400, canJoin = true)),
                    flow,
                )
            },
            "refused" to { LinkStepCard(LinkStep.Failed("This post is in a private chat you have not joined. Ask for its invite link."), flow) },
            "entries" to {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    PinnedEntryRow(PinnedPost(9, video, null, "Welcome, start here"), noVideos = false) {}
                    PinnedEntryRow(PinnedPost(10, null, TgLink.Post("nature_clips", null, 4), "Start here"), noVideos = false) {}
                    AudioEntryRow(14, noVideos = false) {}
                    RoundEntryRow(6, noVideos = false) {}
                }
            },
            "round-only" to { Column(Modifier.padding(24.dp)) { RoundEntryRow(3, noVideos = true) {} } },
        )
        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            for ((name, shot) in shots) {
                ImageComposeScene(640, 760, Density(1.5f)) {
                    TmMaterialTheme(dark = dark) {
                        Surface(Modifier.fillMaxSize().background(Tone.background), color = Tone.background) { shot() }
                    }
                }.use { scene ->
                    scene.render(0)
                    save("link-$name-$theme.png", scene.render(2_000_000_000).png())
                }
            }
        }
    }

    private fun org.jetbrains.skia.Image.png(): ByteArray = encodeToData(EncodedImageFormat.PNG)!!.bytes

    private fun save(name: String, png: ByteArray) {
        assertTrue(png.size > 500)
        val out = System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        File(out).apply { mkdirs() }.resolve(name).writeBytes(png)
    }
}
