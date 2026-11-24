package com.tmplayer.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.components.GroupPlace
import com.tmplayer.ui.components.groupedCard
import com.tmplayer.ui.components.isTouch

/**
 * Lays the items [build] adds out as grouped cards: every run of rows between two section titles
 * (or notes) becomes one card on a phone, its first and last rows rounded and the ones between
 * divided by an inset line.
 *
 * The pages add their rows with the plain `item { }` they always did; this scope collects them,
 * works out where each one sits in its run, and only then hands them to the list. A section title
 * goes in with [sectionTitle] and a line of small print under a group with [settingsNote], which
 * is how a run is ended.
 *
 * A television keeps one card per row instead: there a row fills with the focus colour, and a
 * block of colour inside a larger card reads less clearly from a sofa than a card of its own.
 */
internal fun LazyListScope.settingsGroups(build: LazyListScope.() -> Unit) {
    val collected = GroupCollector(this).apply(build).entries
    // The heading each row sits under, for the television's breadcrumb (WP-07): null above the first.
    var section: (@Composable () -> String)? = null
    // The first row of every section, for the Page and Channel keys on a television (WP-75): the
    // page's first row, then the first row after each section title.
    val starts = mutableListOf<Int>()
    var opening = true
    collected.forEachIndexed { index, entry ->
        if (entry.breaks) {
            if (entry.section != null) opening = true
        } else if (opening) {
            starts += index
            opening = false
        }
    }
    collected.forEachIndexed { index, entry ->
        if (entry.section != null) section = entry.section
        if (entry.breaks) {
            item(entry.key, entry.contentType, entry.content)
            return@forEachIndexed
        }
        val under = section
        val sectionStart = starts.lastOrNull { it <= index } ?: index
        val nextSection = starts.firstOrNull { it > index } ?: -1
        val previousSection = starts.lastOrNull { it < sectionStart } ?: -1
        val place = GroupPlace(
            first = collected.getOrNull(index - 1)?.breaks ?: true,
            last = collected.getOrNull(index + 1)?.breaks ?: true,
        )
        // A key of its own for a row with none: the pane finds the row again by it (WP-75).
        item(entry.key ?: settingsRowKey(index), entry.contentType) {
            CompositionLocalProvider(LocalGroupPlace provides place) {
                if (isTouch()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = if (place.first) GROUP_GAP else 0.dp, bottom = if (place.last) GROUP_GAP else 0.dp)
                            .groupedCard(place),
                    ) {
                        entry.content(this@item)
                    }
                } else {
                    val crumb = LocalSettingsBreadcrumb.current
                    if (crumb == null) {
                        entry.content(this)
                    } else {
                        // The remote on this row names its section in the pinned header. Written
                        // from the focus callback and read only by the header, so a focus move
                        // composes the header and not the page.
                        val title = under?.invoke()
                        val requester = remember { FocusRequester() }
                        DisposableEffect(crumb, index) {
                            crumb.attach(index, requester)
                            onDispose { crumb.detach(index, requester) }
                        }
                        Box(
                            Modifier.focusRequester(requester).onFocusChanged {
                                if (it.hasFocus) {
                                    crumb.section = title
                                    crumb.rowFocused(index, sectionStart, nextSection, previousSection, first = index == starts.first())
                                }
                            },
                            propagateMinConstraints = true,
                        ) {
                            entry.content(this@item)
                        }
                    }
                }
            }
        }
    }
}

/** A section's heading, which also closes the card above it. */
internal fun LazyListScope.sectionTitle(text: @Composable () -> String) {
    if (this is GroupCollector) {
        sectionEntry(text)
    } else {
        item(contentType = GroupBreak) { SectionTitle(text()) }
    }
}

/** Small print under a group, outside its card. */
internal fun LazyListScope.settingsNote(key: Any? = null, content: @Composable LazyItemScope.() -> Unit) {
    item(key = key, contentType = GroupBreak, content = content)
}

/**
 * Where the row being drawn sits in its group, for a row that wants to know (a range row drawing
 * its own panel). [GroupPlace.Alone] outside [settingsGroups].
 */
internal val LocalGroupPlace = staticCompositionLocalOf { GroupPlace.Alone }

/** The content type that marks an item as outside the cards: a heading or a note. */
private object GroupBreak

/** The room above a group's first row and below its last. */
private val GROUP_GAP = 6.dp

private class Entry(
    val key: Any?,
    val contentType: Any?,
    val content: @Composable LazyItemScope.() -> Unit,
    /** The heading's words when this entry is a section title, else null. */
    val section: (@Composable () -> String)? = null,
) {
    val breaks: Boolean get() = contentType === GroupBreak
}

/**
 * The scope [settingsGroups] hands the pages. Every item is held back until the page is built;
 * anything else a list can be asked for goes straight to the real list, which no settings page
 * does.
 */
private class GroupCollector(target: LazyListScope) : LazyListScope by target {
    val entries = mutableListOf<Entry>()

    /** A section title: a break like a note, which also names the rows under it. */
    fun sectionEntry(text: @Composable () -> String) {
        entries += Entry(null, GroupBreak, { SectionTitle(text()) }, section = text)
    }

    override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
        entries += Entry(key, contentType, content)
    }

    override fun items(
        count: Int,
        key: ((index: Int) -> Any)?,
        contentType: (index: Int) -> Any?,
        itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
    ) {
        repeat(count) { index ->
            entries += Entry(key?.invoke(index), contentType(index), content = { itemContent(index) })
        }
    }
}
