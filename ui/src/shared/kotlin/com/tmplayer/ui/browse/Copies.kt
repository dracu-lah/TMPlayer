package com.tmplayer.ui.browse

import com.tmplayer.ui.theme.collectIsFocusVisibleAsState
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.graphicsLayer
import com.tmplayer.ui.components.riseIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.data.CopyHandoff
import com.tmplayer.data.MediaFacts
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaMapper
import com.tmplayer.data.SeriesShelf
import com.tmplayer.data.VideoCopies
import com.tmplayer.data.copyQuality
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import com.tmplayer.ui.components.rememberCover
import com.tmplayer.i18n.Messages
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.focusRing

/**
 * One copy's name on a line of its own: its quality and codec, "1080p  ·  HEVC", or its size
 * where the name and Telegram say nothing about the picture.
 */
fun copyHeading(copy: MediaItem): String {
    val name = copy.fileName.ifBlank { copy.title }
    val facts = MediaFacts.read(name, copy.width, copy.height)
    val quality = VideoCopies.heightOf(copy)?.let { copy.copyQuality() }
    // A video cut into byte pieces says so: "4K  ·  7 parts  ·  HEVC", one choice, never seven.
    val pieces = com.tmplayer.data.SplitPieces.countLabel(copy)
    return listOfNotNull(quality ?: MediaMapper.formatSize(copy.sizeBytes).ifEmpty { null }, pieces, facts.videoCodec, facts.dynamicRange, facts.source)
        .joinToString("  ·  ")
}

/**
 * What the copy's name says it sounds like: its audio languages in the app's language, then the
 * audio format ("Malayalam  ·  DDP 5.1"). Null when the name says neither.
 */
fun copySound(copy: MediaItem, locale: java.util.Locale = java.util.Locale.getDefault()): String? {
    val facts = MediaFacts.read(copy.fileName.ifBlank { copy.title }, copy.width, copy.height)
    val languages = facts.languages.map { code ->
        java.util.Locale.forLanguageTag(code).getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
    }
    return (languages + listOfNotNull(facts.audio)).joinToString("  ·  ").ifEmpty { null }
}

/**
 * What else sets a copy apart, for the line under its [copyHeading]: the size (when the heading
 * did not already give it), the length, when it was posted, the chat where the copies span more
 * than one, whether it is on this device and whether its chat allows saving.
 */
fun copyDetail(
    copy: MediaItem,
    s: Messages,
    chatTitle: String?,
    started: Boolean = false,
    /** The copies listed with it, for [copyFit]. */
    among: List<MediaItem> = emptyList(),
): String = listOfNotNull(
    copyFit(copy, s, among),
    copy.sizeBytes.takeIf { it > 0 && VideoCopies.heightOf(copy) != null }?.let { s.formatter.size(it) },
    copy.durationSec.takeIf { it > 0 }?.let { s.formatter.duration(it.toLong()) },
    copy.date.takeIf { it > 0 }?.let { s.detailCopyPosted(s.formatter.date(it * 1000L)) },
    chatTitle?.takeIf { it.isNotBlank() }?.let { s.detailCopyFrom(it) },
    when (copy.locality) {
        MediaItem.Locality.Downloaded -> s.gridBadgeDownloaded
        MediaItem.Locality.Cached -> s.detailCopyOnDevice
        MediaItem.Locality.Remote -> null
    },
    s.detailCopyStarted.takeIf { started },
    s.detailCopyNoSaving.takeIf { !copy.canBeSaved },
).joinToString("  ·  ")

/**
 * Whether this device plays [copy] smoothly, as far as its name says (HX2,
 * [VideoCopies.playsSmoothlyHere]): "May stutter on this TV" for a copy it can only decode slowly,
 * and "Smooth on this TV" for the others, but only where [among] holds one that may stutter, so a
 * list where every copy plays well says nothing about it. Null where nothing is known.
 */
fun copyFit(copy: MediaItem, s: Messages, among: List<MediaItem>): String? {
    val smooth = VideoCopies.playsSmoothlyHere(copy) ?: return null
    if (!smooth) return s.detailCopyMayStutter(VideoCopies.deviceWord)
    return if (among.any { VideoCopies.playsSmoothlyHere(it) == false }) s.detailCopySmooth(VideoCopies.deviceWord) else null
}

/**
 * The chat each copy of [item] is in, by name: [chatTitle] for the
 * page's own chat, the name the page that found the others handed over ([CopyHandoff]) for the rest.
 */
fun copyChat(copy: MediaItem, item: MediaItem, chatTitle: String): String? =
    if (copy.chatId == item.chatId) chatTitle.ifBlank { null } else CopyHandoff.chatTitleOf(copy)

/**
 * A film posted more than once, on its detail page: one quiet line under Play naming the copy that
 * plays ("1080p  ·  1.4 GB  ·  from Film Club") with Change at its end, and under it, once Change
 * is pressed, every copy as a row of its own to choose from.
 *
 * The line is the default because most of the time the copy chosen for the viewer is the right
 * one (one already on the device, the one they started, the quality they usually watch: see
 * [VideoCopies.pick]), and a wall of chips asked a question nobody needed to answer. The rows say
 * what a chip could not: the length, the date, the chat, whether it is here already.
 *
 * Choosing a row hands the copy to [onPick], which remembers it for the video and turns the page to
 * it, so Play, the download line and the facts all speak of that copy.
 */
@Composable
internal fun Versions(
    item: MediaItem,
    onPick: ((MediaItem) -> Unit)?,
    chatTitle: String = "",
    started: (MediaItem) -> Boolean = { false },
) {
    if (item.versions.size <= 1 || onPick == null) return
    // The same file posted twice is one choice, not two identical rows.
    val files = remember(item.versions) { VideoCopies.distinctFiles(item.versions) }
    if (files.size <= 1) return
    val s = LocalStrings.current
    var open by rememberSaveable(item.id) { mutableStateOf(false) }
    val summary = remember(item, chatTitle, s) {
        listOfNotNull(
            copyHeading(item),
            item.sizeBytes.takeIf { it > 0 && VideoCopies.heightOf(item) != null }?.let { s.formatter.size(it) },
            copyChat(item, item, chatTitle)?.let { s.detailCopyFrom(it) },
        ).joinToString("  ·  ")
    }
    // A phone lists the copies in its bottom sheet, over the page, and a television on a page of
    // their own over it; the desktop in a centred card (its own SheetHost).
    val tv = !isTouch()
    val sheet = com.tmplayer.ui.components.LocalSheetHost.current?.takeIf { !tv }
    val list: @Composable (Boolean, androidx.compose.ui.focus.FocusRequester?) -> Unit = { withHeader, chosenFocus ->
        CopyChooser(
            header = withHeader,
            item = item,
            files = files,
            chatTitle = chatTitle,
            started = started,
            chosenFocus = chosenFocus,
            onChoose = { copy ->
                open = false
                if (copy.id != item.id) onPick(copy.asVersion(item))
            },
        )
    }
    Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CopySummary(
            summary = summary,
            count = s.detailVersions(files.size),
            action = if (open && sheet == null && !tv) s.detailCopyDone else s.detailCopyChange,
            onClick = { open = !open },
        )
        if (sheet == null && !tv) {
            AnimatedVisibility(open) { list(true, null) }
        }
    }
    if ((sheet != null || tv) && open) {
        val cover = rememberCover(item)
        val wide = com.tmplayer.ui.online.rememberMeta(item, showOnly = true)?.wideUrl
        val art = rememberArtItem(item)
        val source = remember(item.id, art, cover, wide) {
            com.tmplayer.ui.components.BackdropSource(art.miniThumbnail, art.thumbnailFileId, cover, wide)
        }
        if (tv) {
            TvCopiesPage(
                source = source,
                title = SeriesShelf.tileTitle(item),
                subtitle = copiesSubtitle(item, files, chatTitle, s),
                onClose = { open = false },
                list = { focus -> list(false, focus) },
            )
            return
        }
        sheet!!.Sheet(onDismiss = { open = false }, fullScreen = true) {
            // As tall as the list, so a host that is not the whole screen (the desktop's centred
            // card) is no taller than it needs to be; a full screen sheet fills the rest itself.
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                CopiesHeader(
                    source = source,
                    title = SeriesShelf.tileTitle(item),
                    subtitle = copiesSubtitle(item, files, chatTitle, s),
                    onClose = { open = false },
                )
                Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) { list(false, null) }
            }
        }
    }
}

/**
 * A television's copies list: a page of its own over the details, the phone sheet's picture and
 * title at its top, the copies under it, the remote landing on the one that plays. Back closes it,
 * as the round Close at the top end does; a television has no sheet to drag away.
 */
@Composable
private fun TvCopiesPage(
    source: com.tmplayer.ui.components.BackdropSource,
    title: String,
    subtitle: String,
    onClose: () -> Unit,
    list: @Composable (androidx.compose.ui.focus.FocusRequester) -> Unit,
) {
    val chosen = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // The picture and the title stay put and the copies scroll under them, so the remote
        // walking down a long list never loses what the list is of.
        Column(Modifier.fillMaxSize().background(com.tmplayer.ui.theme.FloatingTone.sheet)) {
            CopiesHeader(source = source, title = title, subtitle = subtitle, onClose = onClose, tv = true)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = com.tmplayer.ui.theme.Tv.SafeH)
                    .padding(bottom = com.tmplayer.ui.theme.Tv.SafeV),
            ) { Column(Modifier.widthIn(max = 760.dp)) { list(chosen) } }
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        // The dialog's window takes a frame or two to be laid out and handed the focus.
        repeat(5) {
            kotlinx.coroutines.delay(60)
            if (runCatching { chosen.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
}

/**
 * The top of the full screen copies sheet: the video's picture (the provider's backdrop, else the
 * Telegram thumbnail, else its generated cover) fading into the sheet, the title and the count on
 * its foot, and a round close at the top end, the sheet having no handle to drag.
 */
@Composable
private fun CopiesHeader(
    source: com.tmplayer.ui.components.BackdropSource,
    title: String,
    subtitle: String,
    onClose: () -> Unit,
    /** A television's: shorter, its words and Close inside the overscan, Close a focusable disc. */
    tv: Boolean = false,
) {
    val sheetColour = com.tmplayer.ui.theme.FloatingTone.sheet
    Box(Modifier.fillMaxWidth().height(if (tv) COPIES_HEADER_TV else COPIES_HEADER).clipToBounds()) {
        com.tmplayer.ui.components.ArtBackdrop(source, Modifier.matchParentSize())
        Box(
            Modifier
                .matchParentSize()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to sheetColour.copy(alpha = 0.15f),
                        0.55f to sheetColour.copy(alpha = 0.55f),
                        1f to sheetColour,
                    ),
                ),
        )
        if (tv) {
            PageDiscButton(
                TmIcons.Close,
                LocalStrings.current.commonClose,
                onClose,
                Modifier.align(Alignment.TopEnd).padding(horizontal = com.tmplayer.ui.theme.Tv.SafeH, vertical = com.tmplayer.ui.theme.Tv.SafeV),
            )
        } else {
            com.tmplayer.ui.components.FloatingRoundButton(
                TmIcons.Close,
                LocalStrings.current.commonClose,
                onClose,
                Modifier.align(Alignment.TopEnd).padding(12.dp),
            )
        }
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = if (tv) com.tmplayer.ui.theme.Tv.SafeH else 20.dp, vertical = 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = Tone.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Tone.muted, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** How tall the copies sheet's picture is. */
private val COPIES_HEADER = 220.dp

/** A television's: a third of its 540dp height, so the first copies show under it. */
private val COPIES_HEADER_TV = 190.dp

/** "7 versions  ·  Film Club", or "7 versions  ·  in 3 chats". */
private fun copiesSubtitle(item: MediaItem, files: List<Pair<MediaItem, List<MediaItem>>>, chatTitle: String, s: Messages): String {
    val chats = files.mapNotNull { (lead, _) -> copyChat(lead, item, chatTitle) }.distinct()
    return listOfNotNull(
        s.detailVersions(files.size),
        if (chats.size > 1) s.detailCopyInChats(chats.size) else chats.firstOrNull(),
    ).joinToString("  ·  ")
}

/**
 * Every copy of [item] to choose from, each thing said once: the title and "7 versions" and the
 * chat over the list (or "in 3 chats"), then the copies grouped under their quality, best first,
 * "1080p", "720p", then the ones whose name states none. A row is only what sets it apart: its size,
 * then what it is (source, codec, languages, audio), its length, its date where the dates differ,
 * and its chat where they span several. No box round a row; the one that plays is tinted. The
 * file's whole name stays on the page's About.
 */
@Composable
private fun CopyChooser(
    /** False under [CopiesHeader], which already says the title and the count. */
    header: Boolean,
    item: MediaItem,
    files: List<Pair<MediaItem, List<MediaItem>>>,
    chatTitle: String,
    started: (MediaItem) -> Boolean,
    /** Put on the copy that plays, for a remote to land on when the list opens. */
    chosenFocus: androidx.compose.ui.focus.FocusRequester? = null,
    onChoose: (MediaItem) -> Unit,
) {
    val s = LocalStrings.current
    val rows = remember(files, item.id) {
        files.map { (lead, same) -> (same.firstOrNull { it.id == item.id } ?: lead) to same }
            .sortedWith(
                compareByDescending<Pair<MediaItem, List<MediaItem>>> { VideoCopies.heightOf(it.first) ?: 0 }
                    .thenByDescending { it.first.sizeBytes },
            )
    }
    val datesDiffer = remember(rows) { rows.map { it.first.date / 86_400 }.distinct().size > 1 }
    val chatOf: (MediaItem) -> String? = { copyChat(it, item, chatTitle) }
    val chats = remember(rows) { rows.mapNotNull { chatOf(it.first) }.distinct() }
    // Quality first, as the group's one heading; the copies whose name states none come last.
    val groups = remember(rows) {
        rows.groupBy { (copy, _) -> VideoCopies.heightOf(copy)?.let { copy.copyQuality() } }.entries
            .sortedBy { it.key == null }
    }
    // A choice made by a finger is shown before the list goes: the tint and the mark move to the
    // copy picked, and the sheet closes a moment later, so the hand sees what it chose.
    val touch = isTouch()
    val still = com.tmplayer.ui.components.rememberLessMotion()
    val tick = com.tmplayer.ui.components.rememberStepTick()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var picked by remember(item.id) { mutableStateOf<String?>(null) }
    val choose: (MediaItem) -> Unit = { copy ->
        if (picked == null) {
            if (!touch || still) {
                onChoose(copy)
            } else {
                picked = copy.id
                tick()
                scope.launch {
                    kotlinx.coroutines.delay(CHOICE_SHOWN_MS)
                    onChoose(copy)
                }
            }
        }
    }
    var row = 0
    Column {
        if (header) Text(
            SeriesShelf.tileTitle(item),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = Tone.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (header) Text(
            copiesSubtitle(item, files, chatTitle, s),
            style = MaterialTheme.typography.bodyMedium,
            color = Tone.muted,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        groups.forEach { (quality, group) ->
            Text(
                quality ?: s.detailCopyOther,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = Tone.accent,
                modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 4.dp),
            )
            group.forEach { (copy, same) ->
                val facts = MediaFacts.of(copy)
                CopyOption(
                    copy = copy,
                    title = copy.sizeBytes.takeIf { it > 0 }?.let { com.tmplayer.data.SplitPieces.sizeFact(copy, s.formatter.size(it)) } ?: copyHeading(copy),
                    what = listOfNotNull(
                        facts.source,
                        facts.videoCodec,
                        facts.dynamicRange,
                        copySound(copy),
                    ).joinToString("  ·  "),
                    facts = listOfNotNull(
                        copyFit(copy, s, rows.map { it.first }),
                        copy.durationSec.takeIf { it > 0 }?.let { s.formatter.duration(it.toLong()) },
                        copy.date.takeIf { datesDiffer && it > 0 }?.let { s.detailCopyPosted(s.formatter.date(it * 1000L)) },
                        chatOf(copy)?.takeIf { chats.size > 1 }?.let { s.detailCopyFrom(it) },
                        when (copy.locality) {
                            MediaItem.Locality.Downloaded -> s.gridBadgeDownloaded
                            MediaItem.Locality.Cached -> s.detailCopyOnDevice
                            MediaItem.Locality.Remote -> null
                        },
                        s.detailCopyStarted.takeIf { same.any(started) },
                        postedAgain(same, s),
                    ).joinToString("  ·  "),
                    chosen = picked?.let { id -> same.any { it.id == id } } ?: same.any { it.id == item.id },
                    onClick = { choose(copy) },
                    // The rows arrive one after another, top first, as the sheet comes up.
                    modifier = Modifier.riseIn(row++, enabled = touch),
                    focusRequester = chosenFocus?.takeIf { same.any { it.id == item.id } },
                )
            }
        }
    }
}

/** One copy in [CopyChooser]: a radio, its size as the title, and what it is under that. */
@Composable
private fun CopyOption(
    copy: MediaItem,
    title: String,
    what: String,
    facts: String,
    chosen: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
) {
    val s = LocalStrings.current
    val tv = !isTouch()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val shape = RoundedCornerShape(Corner.Medium)
    // The chosen tint fades from the old copy to the new one rather than switching rows.
    val tint by androidx.compose.animation.animateColorAsState(
        if (chosen) Tone.accent.copy(alpha = 0.12f) else Color.Transparent,
        com.tmplayer.ui.theme.Motion.standard(com.tmplayer.ui.theme.Motion.Fast),
        label = "copyTint",
    )
    Row(
        modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) Tone.focusFill else tint)
            .focusRing(focused, shape)
            .semantics {
                selected = chosen
                contentDescription = s.seriesCopy(copyHeading(copy))
            }
            .clickable(interactionSource = interactions, indication = if (tv) null else androidx.compose.foundation.LocalIndication.current, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val ink = if (focused) Tone.onFocusFill else Tone.text
        RadioMark(chosen, focused)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = ink)
            if (what.isNotEmpty()) Text(what, style = MaterialTheme.typography.bodySmall, color = if (focused) ink else Tone.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (facts.isNotEmpty()) Text(facts, style = MaterialTheme.typography.bodySmall, color = if (focused) ink.copy(alpha = 0.8f) else Tone.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (!copy.canBeSaved) Icon(TmIcons.Lock, contentDescription = null, tint = if (focused) ink else Tone.muted, modifier = Modifier.size(18.dp))
    }
}

/** The round mark of a choice: a ring, filled at its centre on the chosen one. */
@Composable
private fun RadioMark(chosen: Boolean, focused: Boolean, modifier: Modifier = Modifier) {
    val ink = if (focused) Tone.onFocusFill else Tone.text
    Box(
        modifier
            .size(20.dp)
            .clip(CircleShape)
            .border(2.dp, if (chosen) (if (focused) ink else Tone.accent) else ink.copy(alpha = 0.5f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        // The dot grows into the ring chosen and shrinks out of the one left, so the choice is
        // seen to move; with less motion asked for it only appears.
        val still = com.tmplayer.ui.components.rememberLessMotion()
        val dot by androidx.compose.animation.core.animateFloatAsState(
            if (chosen) 1f else 0f,
            if (still) androidx.compose.animation.core.snap() else if (chosen) com.tmplayer.ui.components.Micro.release() else com.tmplayer.ui.theme.Motion.standard(com.tmplayer.ui.theme.Motion.Fast),
            label = "radioDot",
        )
        if (dot > 0f) {
            Box(
                Modifier
                    .size(10.dp)
                    .graphicsLayer {
                        scaleX = dot
                        scaleY = dot
                    }
                    .clip(CircleShape)
                    .background(if (focused) ink else Tone.accent),
            )
        }
    }
}

/** How long a copy picked by a finger shows as chosen before the sheet closes. */
private const val CHOICE_SHOWN_MS = 220L

/**
 * For a file posted more than once: "in 2 chats" where the posts are in different chats, else
 * "posted twice"; null for a file posted once.
 */
fun postedAgain(same: List<MediaItem>, s: Messages): String? {
    if (same.size <= 1) return null
    val chats = same.map { it.chatId }.distinct().size
    return if (chats > 1) s.detailCopyInChats(chats) else s.detailCopyPostedTimes(same.size)
}

/** "1080p  ·  1.4 GB  ·  from Film Club" and the count of copies, with Change at the end: one target. */
@Composable
private fun CopySummary(summary: String, count: String, action: String, onClick: () -> Unit) {
    val tv = !isTouch()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val shape = RoundedCornerShape(Corner.Medium)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            // Only the outline: a filled block here read as one more card on a page already made
            // of them. On a television the focus fill takes the outline's place.
            .background(if (focused) Tone.focusFill else Color.Transparent)
            .then(if (focused) Modifier else Modifier.border(1.dp, Tone.outline, shape))
            .focusRing(focused, shape)
            .clickable(interactionSource = interactions, indication = if (tv) null else androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = if (tv) 12.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val ink = if (focused) Tone.onFocusFill else Tone.text
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(summary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(count, style = MaterialTheme.typography.labelMedium, color = if (focused) ink.copy(alpha = 0.8f) else Tone.muted, maxLines = 1)
        }
        Text(action, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = if (focused) ink else Tone.accent, maxLines = 1)
    }
}

/**
 * One copy in the list: a round mark (filled on the copy that plays), its quality in full, and
 * what else sets it apart under that ([copyDetail]). The whole row is the target.
 */
@Composable
internal fun CopyRow(
    copy: MediaItem,
    detail: String,
    chosen: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** The languages and audio format the name states ([copySound]); null for none. */
    sound: String? = null,
    /** The file's own name, last and quiet, for whoever wants to read exactly what it is. */
    fileName: String? = null,
) {
    val s = LocalStrings.current
    val tv = !isTouch()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val shape = RoundedCornerShape(Corner.Medium)
    val heading = remember(copy) { copyHeading(copy) }
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                when {
                    focused -> Tone.focusFill
                    chosen -> Tone.accent.copy(alpha = 0.12f)
                    else -> Color.Transparent
                },
            )
            .focusRing(focused, shape)
            .border(1.dp, if (chosen && !focused) Tone.accent.copy(alpha = 0.5f) else if (focused) Color.Transparent else Tone.outline, shape)
            .semantics {
                selected = chosen
                contentDescription = s.seriesCopy(heading)
            }
            .clickable(interactionSource = interactions, indication = if (tv) null else androidx.compose.foundation.LocalIndication.current, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = if (tv) 12.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val ink = if (focused) Tone.onFocusFill else Tone.text
        RadioMark(chosen, focused)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(heading, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sound != null) {
                Text(sound, style = MaterialTheme.typography.labelMedium, color = if (focused) ink else Tone.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (detail.isNotEmpty()) {
                Text(detail, style = MaterialTheme.typography.labelMedium, color = if (focused) ink.copy(alpha = 0.8f) else Tone.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (fileName != null) {
                Text(fileName, style = MaterialTheme.typography.labelSmall, color = if (focused) ink.copy(alpha = 0.7f) else Tone.muted.copy(alpha = 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!copy.canBeSaved) {
            Icon(TmIcons.Lock, contentDescription = null, tint = if (focused) ink else Tone.muted, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Telegram's own lighter copies of a big video (CP80), on its detail page: one quiet line under
 * the copies naming the one that plays ("As uploaded  ·  1080p  ·  2 GB", or "Telegram's 720p")
 * with Change at its end, and under it, once pressed, the upload and each of Telegram's copies to
 * choose from. The choice goes to the player through [com.tmplayer.data.QualityWish]; the page
 * itself, its download line and its facts keep speaking of the upload, which is what a download
 * keeps.
 */
@Composable
fun TelegramQualityLine(item: MediaItem) {
    val qualities = item.qualities
    if (qualities.isEmpty() || item.isJoined) return
    val s = LocalStrings.current
    var open by rememberSaveable(item.id) { mutableStateOf(false) }
    // 0 is the upload; null is no choice made, which also plays the upload unless the player
    // finds the line too slow or Save mobile data on.
    var chosen by remember(item.id) { mutableStateOf(com.tmplayer.data.QualityWish.peek(item.chatId, item.messageId)) }
    val uploadName = listOfNotNull(
        s.detailUploadQuality,
        VideoCopies.heightOf(item)?.let { item.copyQuality() },
        item.sizeBytes.takeIf { it > 0 }?.let { s.formatter.size(it) },
    ).joinToString("  ·  ")
    val playing = qualities.firstOrNull { it.height == chosen }
    val summary = playing?.let { q ->
        listOfNotNull(
            s.detailTelegramQuality(com.tmplayer.data.TelegramQualities.label(q.height)),
            q.sizeBytes.takeIf { it > 0 }?.let { s.formatter.size(it) },
        ).joinToString("  ·  ")
    } ?: uploadName
    val count = s.detailTelegramQualities + ": " + qualities.joinToString(", ") { com.tmplayer.data.TelegramQualities.label(it.height) }
    val pick: (Int) -> Unit = { height ->
        chosen = height
        com.tmplayer.data.QualityWish.request(item.chatId, item.messageId, height)
        open = false
    }
    Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CopySummary(
            summary = summary,
            count = count,
            action = if (open) s.detailCopyDone else s.detailCopyChange,
            onClick = { open = !open },
        )
        AnimatedVisibility(open) {
            Column {
                var row = 0
                val touch = isTouch()
                CopyOption(
                    copy = item,
                    title = s.detailUploadQuality,
                    what = listOfNotNull(VideoCopies.heightOf(item)?.let { item.copyQuality() }, MediaFacts.of(item).videoCodec).joinToString("  ·  "),
                    facts = item.sizeBytes.takeIf { it > 0 }?.let { s.formatter.size(it) }.orEmpty(),
                    chosen = playing == null,
                    onClick = { pick(0) },
                    modifier = Modifier.riseIn(row++, enabled = touch),
                )
                qualities.forEach { q ->
                    CopyOption(
                        copy = item,
                        title = s.detailTelegramQuality(com.tmplayer.data.TelegramQualities.label(q.height)),
                        what = s.detailTelegramQualityDetail,
                        facts = listOfNotNull(
                            q.sizeBytes.takeIf { it > 0 }?.let { s.formatter.size(it) },
                            q.codec.uppercase(java.util.Locale.ROOT).replace("H265", "HEVC").replace("H264", "H.264").ifBlank { null },
                        ).joinToString("  ·  "),
                        chosen = playing == q,
                        onClick = { pick(q.height) },
                        modifier = Modifier.riseIn(row++, enabled = touch),
                    )
                }
            }
        }
    }
}
