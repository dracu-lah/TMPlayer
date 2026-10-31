package com.tmplayer.ui.components

import com.tmplayer.ui.theme.tileFootScrim
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaName
import com.tmplayer.data.NameColour
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.OnArt
import com.tmplayer.ui.theme.Scrims
import java.util.Locale

/**
 * What a [GeneratedCover] draws for one file, worked out once and remembered: the title to write,
 * the number to set large, which of the [CoverArt.Pairs] to paint, and the chat's initial where
 * the chat is not obvious from the screen.
 */
@Immutable
data class Cover(
    val title: String,
    val number: Int?,
    val pair: Int,
    val chatInitial: String? = null,
)

/**
 * The art for a file that has none: a lecture, a screen recording, a course part posted with no
 * thumbnail. A grey square with one letter on it reads as broken, and a chat of forty of them as a
 * chat that failed to load, so a file with no picture gets a cover that looks intended instead.
 *
 * A diagonal gradient in one of eight dark pairs, chosen by the title alone so a file keeps its
 * colour, every part of one course shares it, and the same film posted in two chats wears the same
 * one; a soft light in the top start corner, so the cover has depth rather than a flat fill; a
 * faint Hugeicons PlayCircle in the middle, so the tile reads as a video waiting for its poster
 * rather than an empty one; the title at the bottom start, white,
 * over [Scrims.tileFoot]; and where the name carries an episode, a part, a lecture or a chapter
 * number, that number set large and faint at the top end, so a course reads as a numbered set.
 *
 * Gradient and text only, with no bitmap, so a grid of them costs no more to draw than the grey
 * squares did. The colours are the same in both themes: the cover is a picture, and pictures are
 * dark.
 *
 * [compact] is for a tile under about 160 dp wide (the phone's grid), where the title takes a
 * smaller style and two lines. [showTitle] off leaves the colour and the number, for a slot
 * whose title is written beside it anyway, or one too small to hold any text.
 */
@Composable
fun GeneratedCover(
    cover: Cover,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    showTitle: Boolean = true,
) {
    val (start, end) = CoverArt.Pairs[cover.pair]
    val inset = if (compact) 8.dp else 12.dp
    Box(
        modifier
            .background(Brush.linearGradient(listOf(start, end)))
            .drawBehind {
                // A light falling from the top start corner, over the gradient and under the words.
                drawRect(
                    Brush.radialGradient(
                        listOf(OnArt.copy(alpha = GLOW_ALPHA), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(size.width * 0.15f, size.height * 0.1f),
                        radius = maxOf(size.width, size.height) * 0.85f,
                    ),
                )
            },
    ) {
        // A faint play mark in the middle, so the cover reads as a video waiting for its poster
        // rather than a blank tile (user, 2026-11-01).
        Icon(
            TmIcons.PlayCircle,
            contentDescription = null,
            tint = OnArt.copy(alpha = PLAY_MARK_ALPHA),
            modifier = Modifier.align(Alignment.Center).size(if (compact) 40.dp else 56.dp),
        )
        cover.number?.let { number ->
            Text(
                number.toString(),
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                color = OnArt.copy(alpha = NUMERAL_ALPHA),
                maxLines = 1,
                modifier = Modifier.align(Alignment.TopEnd).padding(horizontal = inset, vertical = inset / 2),
            )
        }
        cover.chatInitial?.let { initial ->
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(inset)
                    .size(if (compact) 20.dp else 24.dp)
                    .clip(CircleShape)
                    .background(OnArt.copy(alpha = INITIAL_FILL_ALPHA)),
                contentAlignment = Alignment.Center,
            ) {
                Text(initial, style = MaterialTheme.typography.labelSmall, color = OnArt, maxLines = 1)
            }
        }
        if (showTitle && cover.title.isNotBlank()) {
            Box(Modifier.fillMaxSize().tileFootScrim())
            Text(
                cover.title,
                style = (if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium)
                    .copy(fontWeight = FontWeight.Bold),
                color = OnArt,
                maxLines = if (compact) 2 else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(inset),
            )
        }
    }
}

/** [CoverArt.of] for [item], remembered for as long as the item is the same. */
@Composable
fun rememberCover(item: MediaItem, chatTitle: String? = null): Cover =
    remember(item.chatId, item.messageId, item.title, item.caption, chatTitle) { CoverArt.of(item, chatTitle) }

/** How a [Cover] is chosen: the pure half of [GeneratedCover], kept apart so it can be tested. */
object CoverArt {

    /**
     * Eight hand-tuned dark pairs, the lighter stop first: indigo, teal, plum, rust, forest, sea
     * blue, violet and olive. Every stop keeps white text at 6.9:1 or better, so the title is
     * readable on the cover with no scrim at all, and the scrim only adds to it.
     *
     * Kept in core as [NameColour.Pairs], since a chat with no photo wears the same eight
     * ([NameArt]) and the web app has to draw them too.
     */
    val Pairs: List<Pair<Color, Color>> = NameColour.Pairs.map { (light, deep) -> Color(light) to Color(deep) }

    /** The cover for [item], with [chatTitle]'s initial where the screen does not say the chat. */
    fun of(item: MediaItem, chatTitle: String? = null): Cover =
        of(item.chatId, item.nameForParsing, item.caption, chatTitle, fallbackTitle = item.title)

    /**
     * The cover for the file called [name] in chat [chatId]. The title written on it is the name as
     * [MediaName] reads it ("Harbour Notes" from "Harbour.Notes.S01E03.1080p.mkv"), or
     * [fallbackTitle] when that comes out empty.
     */
    fun of(
        chatId: Long,
        name: String,
        caption: String? = null,
        chatTitle: String? = null,
        fallbackTitle: String = name,
    ): Cover {
        val parsed = MediaName.parse(name, caption)
        val title = parsed.title.ifBlank { fallbackTitle }
        return Cover(
            title = title,
            number = parsed.episode ?: numeral(name) ?: caption?.let(::numeral),
            pair = pairIndex(title),
            chatInitial = chatTitle?.let(::initial),
        )
    }

    /**
     * Which of the [Pairs] a title wears. A hash of its words, case and spacing aside, so it is the
     * same on every launch, every device and in every chat, and every episode of a show (whose
     * parsed titles match) shares it. The hash is spread before it is folded, since titles that
     * differ by one letter at the end would otherwise sit in neighbouring pairs.
     */
    fun pairIndex(title: String): Int {
        var h = title.trim().lowercase(Locale.ROOT).replace(SPACES, " ").hashCode()
        h = h xor (h ushr 16)
        h *= 0x45d9f3b
        h = h xor (h ushr 16)
        return Math.floorMod(h, Pairs.size)
    }

    private val SPACES = Regex("""\s+""")

    /**
     * The number a course or a set of parts puts in its names: "Lecture 07" gives 7, "Part 4" 4,
     * "Chapter 12" 12. Episodes ("S01E03") are [MediaName]'s and are asked for there first; this is
     * for the words it does not read. Null when the name carries no such number.
     */
    fun numeral(name: String): Int? =
        NUMBERED.find(name.replace(SEPARATORS, " "))?.groupValues?.get(1)?.toIntOrNull()

    private fun initial(chatTitle: String): String? {
        val text = chatTitle.trim().takeIf { it.isNotEmpty() } ?: return null
        val first = text.codePointAt(0)
        return String(Character.toChars(first)).uppercase()
    }

    private val SEPARATORS = Regex("""[._]+""")

    /**
     * A counting word, then the number. Not inside another word ("Watch 3" is no chapter), and
     * not more than three digits, so a year or a resolution is not mistaken for a part.
     */
    private val NUMBERED = Regex(
        """(?<!\p{L})(?:lecture|lect|lec|part|pt|chapter|chap|ch|lesson|class|module|session|unit|day|week|episode|ep)""" +
            """\s*[#:-]?\s*(\d{1,3})(?!\d)""",
        RegexOption.IGNORE_CASE,
    )
}

/** How faint the big number is: a mark on the colour, never competing with the title. */
private const val NUMERAL_ALPHA = 0.22f

/** The light in the top start corner: enough to give the colour depth, never a bright spot. */
private const val GLOW_ALPHA = 0.10f

/** The centre play mark: a hint the tile is a video, not a competitor to the title or number. */
private const val PLAY_MARK_ALPHA = 0.20f

/** The chat's initial sits on a disc of white at this alpha. */
private const val INITIAL_FILL_ALPHA = 0.18f
