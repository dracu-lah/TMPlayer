package com.tmplayer.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.collectIsFocusVisibleAsState
import com.tmplayer.ui.theme.focusRing

/** What a [PageHeader] is made of. */
object PageHeaderDefaults {
    /** The header's least height below the status bar: a 48 dp target with 4 dp over and under. */
    val Height = 56.dp

    /** The Back button's touch target. */
    val Target = 48.dp
    val Icon = 24.dp
}

/** The tags tests find a [PageHeader]'s parts by. */
const val PAGE_HEADER_TAG = "page-header"
const val PAGE_HEADER_BACK_TAG = "page-header-back"
const val PAGE_HEADER_TITLE_TAG = "page-header-title"
const val PAGE_HEADER_HAIRLINE_TAG = "page-header-hairline"

/**
 * The fixed top bar of a phone or tablet page that is not a tab (Settings and its pages, About,
 * Downloads): the Back arrow at the start in a full 48 dp target, the page's name beside it, and a
 * solid ground, so the list under it is never drawn beneath a floating control. It stands in a
 * Scaffold's `topBar`, clear of the status bar and a cutout, so the list is laid out below it. A
 * hairline shows along its foot once the list has moved ([scrolled]), and not at the top, where
 * there is nothing under it to divide from.
 *
 * Back (or Close, while a selection is on) is the first stop for a keyboard or a screen reader,
 * and does what the hardware key does: the page hands the same action to both.
 */
@Composable
fun PageHeader(
    title: @Composable () -> Unit,
    backIcon: ImageVector,
    backLabel: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    scrolled: Boolean = false,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    Box(
        modifier
            .fillMaxWidth()
            .background(Tone.background)
            .testTag(PAGE_HEADER_TAG),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .heightIn(min = PageHeaderDefaults.Height)
                .padding(start = 4.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTip(backLabel, side = TipSide.Below) {
                Box(
                    Modifier
                        .size(PageHeaderDefaults.Target)
                        .pressScale(interactions)
                        .clip(CircleShape)
                        .focusRing(focused, CircleShape)
                        .clickable(interactionSource = interactions, indication = ripple(), role = Role.Button, onClick = onBack)
                        .semantics { contentDescription = backLabel }
                        .testTag(PAGE_HEADER_BACK_TAG),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(backIcon, contentDescription = null, tint = Tone.text, modifier = Modifier.size(PageHeaderDefaults.Icon))
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .padding(start = 8.dp)
                    .semantics { heading() }
                    .testTag(PAGE_HEADER_TITLE_TAG),
                contentAlignment = Alignment.CenterStart,
            ) {
                ProvideTextStyle(MaterialTheme.typography.titleLarge.copy(color = Tone.text)) { title() }
            }
        }
        AnimatedVisibility(
            visible = scrolled,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            HorizontalDivider(color = Tone.outline, modifier = Modifier.testTag(PAGE_HEADER_HAIRLINE_TAG))
        }
    }
}

/** True once the list has moved off its first row: the cue for [PageHeader]'s hairline. */
@Composable
fun LazyListState.rememberMoved(): Boolean {
    val state = this
    val moved by remember(state) {
        derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 0 }
    }
    return moved
}
