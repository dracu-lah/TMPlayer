package com.tmplayer.ui.browse

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.R
import com.tmplayer.data.CastMember
import com.tmplayer.data.FilmDetails
import com.tmplayer.data.FilmLookup
import com.tmplayer.data.FilmName
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaMapper
import com.tmplayer.data.Tmdb
import com.tmplayer.ui.components.NetworkImage
import com.tmplayer.ui.components.Poster
import com.tmplayer.ui.components.SkeletonBox
import com.tmplayer.ui.components.rememberShimmer
import com.tmplayer.ui.theme.Accent
import com.tmplayer.ui.theme.Background
import com.tmplayer.ui.theme.SurfaceDark
import com.tmplayer.ui.theme.SurfaceRaised
import com.tmplayer.ui.theme.TextMuted
import com.tmplayer.ui.theme.TextPrimary
import com.tmplayer.ui.theme.Tv
import com.tmplayer.ui.theme.tmButtonColors

/**
 * What a viewer sees between choosing a film and playing it.
 *
 * This screen must never stand between the viewer and the film. TMDB is a nicety and the file
 * plays without it, so Play is present and focused from the first frame, before any lookup has
 * finished, and every failure downgrades the panel to its buttons rather than blocking.
 */
@Composable
fun FilmDetailsPanel(
    item: MediaItem,
    resumeMs: Long,
    onPlay: () -> Unit,
    onPlayFromStart: () -> Unit,
    onWatchTrailer: (String) -> Unit,
    trailersAvailable: Boolean,
    onDismiss: () -> Unit,
    /** The episode that follows this one in the same chat, when one has been loaded. */
    nextEpisode: MediaItem? = null,
    onPlayNext: () -> Unit = {},
) {
    // A Dialog renders in its own window, so this cannot be drawn behind the grid no matter
    // where it is emitted from.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val name = item.fileName.ifBlank { item.title }
        // Bumped by Refresh. An answer is kept for a month, which is right for a film that has
        // been out for years and wrong for one added to TMDB last week, or for a lookup that
        // failed while the TV's connection was down.
        var reloads by remember(item.id) { mutableStateOf(0) }
        val lookup by produceState<FilmLookup>(
            initialValue = FilmLookup.Loading,
            key1 = item.id,
            key2 = reloads,
        ) {
            value = FilmLookup.Loading
            value = Tmdb.lookup(name)
        }

        // Read straight off the file name so the screen knows it is showing an episode before
        // TMDB has said anything, and still knows it if TMDB never answers.
        val parsed = remember(item.id) { FilmName.parse(item.fileName.ifBlank { item.title }) }

        Box(Modifier.fillMaxSize().background(Background)) {
            val details = (lookup as? FilmLookup.Found)?.details

            // The backdrop is scenery, so it sits well under the text rather than competing.
            if (details?.backdropUrl != null) {
                NetworkImage(
                    url = details.backdropUrl,
                    alpha = 0.35f,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(
                            0f to Background,
                            0.55f to Background.copy(alpha = 0.88f),
                            1f to Background.copy(alpha = 0.35f),
                        ),
                    ),
                )
            }

            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = Tv.SafeH, vertical = Tv.SafeV),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        details?.title ?: item.title,
                        style = MaterialTheme.typography.headlineLarge,
                        color = TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    // "S2 E4" from the file name straight away, upgraded to "S2 E4 · Wednesday's
                    // Child" once the episode itself has been looked up.
                    val episodeLabel = details?.episodeLabel ?: when {
                        parsed.season != null && parsed.episode != null ->
                            "S${parsed.season} E${parsed.episode}"
                        parsed.episode != null -> "Episode ${parsed.episode}"
                        parsed.season != null -> "Season ${parsed.season}"
                        else -> null
                    }
                    if (episodeLabel != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            episodeLabel,
                            style = MaterialTheme.typography.titleMedium,
                            color = Accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Spacer(Modifier.height(6.dp))
                    FactsRow(item = item, details = details, state = lookup)
                    Spacer(Modifier.height(14.dp))

                    Actions(
                        resumeMs = resumeMs,
                        trailerKey = details?.trailerKey?.takeIf { trailersAvailable },
                        isEpisode = parsed.isSeries,
                        nextLabel = nextEpisode?.let {
                            val after = FilmName.parse(it.fileName.ifBlank { it.title })
                            if (after.season != null && after.episode != null) {
                                "Next: S${after.season} E${after.episode}"
                            } else {
                                "Next episode"
                            }
                        },
                        onPlay = onPlay,
                        onPlayFromStart = onPlayFromStart,
                        onPlayNext = onPlayNext,
                        onWatchTrailer = onWatchTrailer,
                        onRefresh = {
                            Tmdb.forget(name)
                            reloads++
                        },
                    )

                    Spacer(Modifier.height(16.dp))
                    Synopsis(lookup, isEpisode = parsed.isSeries)

                    if (details != null && details.cast.isNotEmpty()) {
                        Spacer(Modifier.height(18.dp))
                        CastRow(details.cast)
                    }

                    Spacer(Modifier.height(18.dp))
                    SourceName(item)

                    if (lookup is FilmLookup.Found) {
                        Spacer(Modifier.height(18.dp))
                        Attribution()
                    }
                }

                PosterColumn(item = item, details = details, loading = lookup is FilmLookup.Loading)
            }
        }
    }
}

/** Year, runtime, rating and genres from TMDB; duration and size from the file itself. */
@Composable
private fun FactsRow(item: MediaItem, details: FilmDetails?, state: FilmLookup) {
    val shimmer = rememberShimmer()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (details != null && details.rating > 0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    String.format(java.util.Locale.US, "%.1f", details.rating),
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                )
            }
        }

        // Facts drawn from the file are always true, so they show even while TMDB is still out.
        val fileFacts = listOfNotNull(
            details?.year?.toString(),
            details?.runtimeMinutes?.takeIf { it > 0 }?.let { "${it / 60}h ${it % 60}m" }
                ?: MediaMapper.formatDuration(item.durationSec).ifEmpty { null },
            MediaMapper.formatSize(item.sizeBytes).ifEmpty { null },
            details?.genres?.take(2)?.joinToString(", ")?.ifEmpty { null },
        )

        if (state is FilmLookup.Loading) {
            SkeletonBox(shimmer, Modifier.width(180.dp).height(14.dp))
        }
        if (fileFacts.isNotEmpty()) {
            Text(
                fileFacts.joinToString("  ·  "),
                style = MaterialTheme.typography.bodyLarge,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Play first and focused, always.
 *
 * Resume is a separate button rather than a mode on Play, because a viewer returning to a film
 * they half-watched should not have to guess which one they are about to get.
 */
@Composable
private fun Actions(
    resumeMs: Long,
    trailerKey: String?,
    isEpisode: Boolean,
    nextLabel: String?,
    onPlay: () -> Unit,
    onPlayFromStart: () -> Unit,
    onPlayNext: () -> Unit,
    onWatchTrailer: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    val play = remember { FocusRequester() }
    val hasResume = resumeMs > 0

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = onPlay,
            colors = tmButtonColors(),
            modifier = Modifier.focusRequester(play),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    hasResume -> "Resume from ${com.tmplayer.player.StreamStats.formatClock(resumeMs)}"
                    isEpisode -> "Watch episode"
                    else -> "Watch movie"
                },
            )
        }

        if (hasResume) {
            Button(onClick = onPlayFromStart, colors = tmButtonColors()) {
                Text("Start from the beginning")
            }
        }

        // Somebody working through a series is here to watch the next one, and the alternative
        // is backing out to a grid of near-identical file names and finding it by eye.
        if (nextLabel != null) {
            Button(onClick = onPlayNext, colors = tmButtonColors()) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(nextLabel)
            }
        }

        if (trailerKey != null) {
            Button(onClick = { onWatchTrailer(trailerKey) }, colors = tmButtonColors()) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Watch trailer")
            }
        }

        // Last, because it is the least of what this screen is for: a match that came out wrong,
        // or a lookup that failed while the TV was offline, without backing out and coming in
        // again to no effect.
        Button(onClick = onRefresh, colors = tmButtonColors()) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text("Refresh")
        }
    }

    LaunchedEffect(Unit) { runCatching { play.requestFocus() } }
}

/** The synopsis, or an honest sentence about why there isn't one. */
@Composable
private fun Synopsis(lookup: FilmLookup, isEpisode: Boolean) {
    val shimmer = rememberShimmer()
    val noun = if (isEpisode) "episode" else "film"

    when (lookup) {
        is FilmLookup.Loading -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SkeletonBox(shimmer, Modifier.fillMaxWidth().height(13.dp))
            SkeletonBox(shimmer, Modifier.fillMaxWidth().height(13.dp))
            SkeletonBox(shimmer, Modifier.fillMaxWidth(0.6f).height(13.dp))
        }

        is FilmLookup.Found -> Text(
            lookup.details.overview.ifBlank { "No synopsis has been written for this $noun yet." },
            style = MaterialTheme.typography.bodyLarge,
            color = TextMuted,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
        )

        // All three remaining cases say the same thing to the viewer: the film is fine, the
        // extras are not here. Only the reason differs, and only the reason is worth varying.
        is FilmLookup.NotFound -> Notice("We couldn't find this $noun in the movie database. You can still play it.")
        is FilmLookup.Failed -> Notice("${lookup.message} You can still play the $noun.")
        is FilmLookup.Disabled -> Notice("Details aren't available in this version. You can still play the $noun.")
    }
}

/**
 * The name Telegram gave the file, verbatim.
 *
 * The heading above is whatever the database matched, which is usually right and occasionally
 * is not. This is the only place the viewer can check what they are actually about to play, and
 * it fills the gap at the foot of the panel rather than leaving it empty.
 */
@Composable
private fun SourceName(item: MediaItem) {
    Column {
        Text("File from Telegram", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text(
            item.fileName.ifBlank { item.title },
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        // Facts about this copy rather than about the film: how sharp it is and when it was
        // posted. Neither is anywhere else on the screen, and both decide whether this is the
        // copy worth watching.
        val provenance = listOfNotNull(
            MediaMapper.qualityTags(item.fileName).firstOrNull(),
            MediaMapper.formatPostedDate(item.date).ifEmpty { null }?.let { "posted $it" },
        )
        if (provenance.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                provenance.joinToString("  ·  "),
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Notice(message: String) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceDark)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Filled.Info,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(20.dp),
        )
        Text(message, style = MaterialTheme.typography.bodyLarge, color = TextMuted)
    }
}

@Composable
private fun CastRow(cast: List<CastMember>) {
    Column {
        Text("Cast", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(cast, key = { it.name }) { member ->
                Column(
                    Modifier.width(84.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    NetworkImage(
                        url = Tmdb.imageUrl(member.profilePath, Tmdb.PROFILE_SIZE),
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(SurfaceRaised),
                        placeholder = {
                            // A silhouette rather than initials: at 64dp a letter reads as a
                            // label, and this row is scanned for faces.
                            Icon(
                                Icons.Filled.Person,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(34.dp),
                            )
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        member.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (member.character.isNotBlank()) {
                        Text(
                            member.character,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** The poster, falling back to the thumbnail Telegram already gave us. */
@Composable
private fun PosterColumn(item: MediaItem, details: FilmDetails?, loading: Boolean) {
    val shimmer = rememberShimmer()

    Box(
        Modifier
            .width(POSTER_WIDTH)
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceDark),
    ) {
        when {
            details?.posterUrl != null -> NetworkImage(
                url = details.posterUrl,
                modifier = Modifier.fillMaxSize(),
                placeholder = { SkeletonBox(shimmer, Modifier.fillMaxSize()) },
            )

            loading -> SkeletonBox(shimmer, Modifier.fillMaxSize())

            // No TMDB poster: Telegram's own thumbnail is a real frame from the film, which is
            // a better stand-in than a blank rectangle.
            else -> Poster(
                miniThumbnail = item.miniThumbnail,
                thumbnailFileId = item.thumbnailFileId,
                fallbackLabel = item.title,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Required by the TMDB API terms of use, which ask for the logo and this exact disclaimer.
 *
 * Shown only when TMDB data is actually on screen: crediting a source that supplied nothing
 * would be both untrue and confusing.
 */
@Composable
private fun Attribution() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.tmdb_logo),
            contentDescription = "The Movie Database",
            modifier = Modifier.height(14.dp),
        )
        Text(
            "This product uses the TMDB API but is not endorsed or certified by TMDB.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
        )
    }
}

private val POSTER_WIDTH = 168.dp
