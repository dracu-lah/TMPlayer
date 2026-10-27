package com.tmplayer.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.tmplayer.data.SizeFilter
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.components.ChoiceLine
import com.tmplayer.ui.components.ChoiceList
import com.tmplayer.ui.components.ChoiceSheet
import com.tmplayer.ui.components.FocusOnOpen
import com.tmplayer.ui.components.isTouch

/**
 * The chat's "n hidden" button opened: the smallest video shown, picked from a few sizes
 * ([SizeFilter.QUICK_MINIMUMS]) without a trip to Settings, which keeps the slider and the
 * largest size. The remote starts on the one that is on.
 */
@Composable
internal fun SizeQuickSheet(current: Long, max: Long, hidden: Int, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    val choices = remember(current) { SizeFilter.quickMinimums(current) }
    val first = remember { FocusRequester() }
    ChoiceSheet(
        title = s.settingsSmallest,
        note = if (hidden > 0) s.gridSizeQuickNote(hidden) else null,
        onDismiss = onDismiss,
        onClose = onDismiss,
        ignoreRelease = !isTouch(),
    ) {
        ChoiceList {
            choices.forEach { bytes ->
                ChoiceLine(
                    title = SizeFilter.label(bytes),
                    detail = if (bytes == SizeFilter.DEFAULT_MIN) s.gridSizeDefault else null,
                    selected = bytes == current,
                    modifier = if (bytes == current) Modifier.focusRequester(first) else Modifier,
                ) { onPick(SizeFilter.clampMin(bytes, max)) }
            }
        }
        FocusOnOpen(first)
    }
}
