package com.tmplayer.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
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
    collected.forEachIndexed { index, entry ->
        if (entry.breaks) {
            item(entry.key, entry.contentType, entry.content)
            return@forEachIndexed
        }
        val place = GroupPlace(
            first = collected.getOrNull(index - 1)?.breaks ?: true,
            last = collected.getOrNull(index + 1)?.breaks ?: true,
        )
        item(entry.key, entry.contentType) {
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
                    entry.content(this)
                }
            }
        }
    }
}

/** A section's heading, which also closes the card above it. */
internal fun LazyListScope.sectionTitle(text: @Composable () -> String) {
    item(contentType = GroupBreak) { SectionTitle(text()) }
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
            entries += Entry(key?.invoke(index), contentType(index)) { itemContent(index) }
        }
    }
}
