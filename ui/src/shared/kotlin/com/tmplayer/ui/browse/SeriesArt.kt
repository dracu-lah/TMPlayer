package com.tmplayer.ui.browse

import com.tmplayer.ui.theme.tileFootScrim
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tmplayer.data.Series
import com.tmplayer.data.SeriesProgress
import com.tmplayer.ui.components.CoverArt
import com.tmplayer.ui.components.GeneratedCover
import com.tmplayer.ui.components.MediaArt
import com.tmplayer.ui.components.PosterArt
import com.tmplayer.ui.components.ProgressLine
import com.tmplayer.ui.components.TileBadgeFill
import com.tmplayer.ui.components.TileBadgeHeight
import com.tmplayer.ui.components.TileBadgeInset
import com.tmplayer.ui.components.WatchedBadge
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.online.MetaPicture
import com.tmplayer.ui.online.rememberMeta
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Scrims
import com.tmplayer.ui.theme.Tone

/**
 * A show's picture, as a 2:3 [poster] card in a grid of films and shows, or at a video tile's
 * 16:9 where it sits in a row of videos.
 *
 * The newest episode's thumbnail, or with posters and overviews on, the show's own wide picture
 * (never its poster, which would be a band across its middle in this frame). What says "this
 * opens onto many videos" is the episode count in a pill at the bottom start, over the tile's foot
 * scrim, with the share watched as a progress line under it. A show with every episode watched
 * wears the tick in the top corner, where a video's tile puts its own badge.
 *
 * A show with no thumbnail gets the [GeneratedCover] of its newest episode, without the title
 * (the tile writes it underneath, and the pill takes the corner the title would) and without the
 * episode's number, which would read as the show's.
 *
 * [badge] is the caller's own mark for the top end corner, a "New" for a show with episodes the
 * viewer has not seen yet, say. The art is clipped to [Corner.Medium] here, so a caller lays a
 * focus ring over it with the same shape.
 */
@Composable
fun SeriesArt(
    series: Series,
    progress: SeriesProgress,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    badge: (@Composable () -> Unit)? = null,
    /** A 2:3 poster card rather than a 16:9 tile: see [PosterArt]. */
    poster: Boolean = false,
    /**
     * Episodes posted since the viewer last watched the show (see [SeriesWatch.newEpisodes]): a
     * [NewBadge] in the top end corner, unless the caller's own [badge] has it.
     */
    fresh: Boolean = false,
    /**
     * False inside a frame that already rounds the art's corners (a video tile's): a second clip
     * to the same shape was one more layer per tile for nothing.
     */
    clip: Boolean = true,
) {
    val s = LocalStrings.current
    val cover = series.cover
    val meta = rememberMeta(cover, showOnly = true)
    val generated = remember(cover.chatId, series.title) { CoverArt.of(cover).copy(title = series.title, number = null) }
    Box(modifier.aspectRatio(if (poster) 2f / 3f else 16f / 9f).then(if (clip) Modifier.clip(RoundedCornerShape(Corner.Medium)) else Modifier)) {
        if (poster) {
            // The show's own poster where the providers know it, else the newest episode's frame.
            val nameless = remember(generated) { generated.copy(title = "") }
            PosterArt(cover.miniThumbnail, cover.thumbnailFileId, nameless, meta?.posterUrl, Modifier.fillMaxSize(), compactCover = compact)
        } else {
            MediaArt(cover.miniThumbnail, cover.thumbnailFileId, Modifier.fillMaxSize()) {
                GeneratedCover(generated, Modifier.fillMaxSize(), compact = compact, showTitle = false)
            }
            // The backdrop only: without one, the newest episode's frame stays.
            MetaPicture(meta?.backdropUrl, Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().tileFootScrim())
        val fraction = if (progress.total == 0) 0f else progress.watched.toFloat() / progress.total
        val inset = if (compact) 6.dp else 8.dp
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
            Text(
                s.seriesEpisodesCount(series.episodeCount),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                maxLines = 1,
                modifier = Modifier
                    .padding(start = inset, bottom = if (fraction > 0f) 6.dp else inset)
                    .heightIn(min = EPISODES_PILL)
                    // A rounded fill, not a clip layer per tile; see TileBadge.
                    .background(TileBadgeFill, CircleShape)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
            if (fraction > 0f) ProgressLine(fraction, inset = inset)
        }
        // The show's one score at the bottom end, only when it is already on the device.
        // Not on a small card, where the episodes pill takes the foot.
        if (!compact) com.tmplayer.ui.online.CardScore(
            meta,
            Modifier.align(Alignment.BottomEnd).padding(end = inset, bottom = if (fraction > 0f) 8.dp else inset),
        )
        if (progress.finished) {
            WatchedBadge(Modifier.align(Alignment.TopStart).padding(TileBadgeInset))
        }
        when {
            badge != null -> Box(Modifier.align(Alignment.TopEnd).padding(TileBadgeInset)) { badge() }
            fresh -> NewBadge(compact, Modifier.align(Alignment.TopEnd).padding(TileBadgeInset))
        }
    }
}

/**
 * "New", in the accent, on a tile with an episode posted since the viewer last watched its show:
 * the badge on a show's tile and on each tile of Home's New episodes row. A [TileBadge]'s pill in
 * the accent rather than black, since it is the app telling the viewer something.
 */
@Composable
fun NewBadge(compact: Boolean, modifier: Modifier = Modifier) {
    Text(
        LocalStrings.current.homeNewBadge,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = Tone.onAccent,
        maxLines = 1,
        modifier = modifier
            .heightIn(min = if (compact) TileBadgeHeight - 2.dp else TileBadgeHeight)
            .background(Tone.accent, CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** The episode count's pill: a little taller than a corner badge, as the one fact a show tile adds. */
private val EPISODES_PILL = 22.dp
