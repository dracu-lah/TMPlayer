package com.tmplayer.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import com.tmplayer.ui.theme.Floating
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.floatingBorder

/**
 * The phone's bottom sheet, with the floating hairline and the drag handle drawn inside it.
 *
 * [ModalBottomSheet] applies its `modifier` before the step that slides the sheet up into place,
 * so a border handed to it outlines where the sheet would sit unmoved: a box from the top of the
 * screen, with a line straight across the sheet. Drawn on the content instead, the hairline moves
 * with the sheet. The content takes the whole surface (no window insets of the sheet's own) and
 * pads itself for the navigation bar, so the edge runs down to the bottom of the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = Floating.SheetShape,
        containerColor = FloatingTone.sheet,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .border(floatingBorder(), Floating.SheetShape)
                .windowInsetsPadding(BottomSheetDefaults.windowInsets),
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { BottomSheetDefaults.DragHandle() }
            content()
        }
    }
}

/**
 * Keeps a list's scrolling inside the list. Without it, a drag that carries on past the top of the
 * list pulls the whole sheet down, and on release the sheet springs back up past where it rests and
 * settles: the sheet jumps every time the list reaches its top. The sheet still moves by its handle
 * and by anything above the list.
 */
fun Modifier.keepScrollInSheet(): Modifier = nestedScroll(KeepScrollInSheet)

private object KeepScrollInSheet : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = available

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}
