package com.tmplayer.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.theme.Tv

/**
 * True when this device is driven by a finger rather than by a remote.
 *
 * Read it for layout rather than for behaviour. What a control does when it is operated belongs
 * inside that control, so that the two halves of the app cannot drift apart; what a pane looks
 * like is a decision each pane has to make for itself.
 */
@Composable
@ReadOnlyComposable
fun isTouch(): Boolean = !FormFactor.isTv(LocalContext.current)

/**
 * The one column of content that a sign-in or onboarding pane is.
 *
 * These panes ask one thing at a time and were written for a television: a fixed 640dp column
 * centred in the screen, inset by the overscan margin, which on a phone held upright is most of
 * the width given over to nothing and a column that cannot use the space it does have. The two
 * shapes are different enough that spelling them out at every call site is how they stop matching
 * each other, so they are spelled out once here.
 *
 * On a phone the column fills the width up to a comfortable reading measure, starts at the top
 * rather than the middle so that the heading does not move when a keyboard appears, and keeps
 * clear of the status bar, the gesture bar and the notch. On a television it is the centred column
 * it always was.
 *
 * Scrolls in both cases. A code arriving on a small screen in landscape leaves very little height,
 * and a pane that cannot scroll simply loses its button off the bottom.
 */
@Composable
fun Pane(
    modifier: Modifier = Modifier,
    /** Vertical gap between the pane's children, since every caller wants an even rhythm. */
    spacing: Dp = 14.dp,
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    content: @Composable ColumnScope.() -> Unit,
) {
    val touch = isTouch()

    if (!touch) {
        Box(
            modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(vertical = Tv.SafeV),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.width(TV_COLUMN),
                horizontalAlignment = horizontalAlignment,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(spacing),
            ) {
                content()
            }
        }
        return
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            // safeDrawing rather than systemBars: it is the one that also clears a notch and the
            // gesture handle, and this pane is the first screen the app ever shows. It already
            // carries the keyboard inset, so an imePadding() on top of it lifted every sign-in
            // pane a whole keyboard height too high the moment the keyboard appeared.
            .safeDrawingPadding(),
    ) {
        // Wide enough to be a tablet or a phone on its side, where a full-width column would run
        // to a line length nobody reads comfortably.
        val measure = if (maxWidth > PHONE_MAX) PHONE_MAX else maxWidth

        Column(
            Modifier
                .width(measure)
                .align(Alignment.TopCenter)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PhonePad.Side, vertical = PhonePad.Top),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(spacing),
        ) {
            content()
        }
    }
}

/**
 * A pane's primary control, laid out the way its device expects.
 *
 * A phone puts one full-width button under the content and reaches it with a thumb. A television
 * puts its buttons in a row and reaches them with a D-pad, where full width would mean a focus
 * ring stretched across the screen.
 */
@Composable
fun Modifier.paneAction(): Modifier = if (isTouch()) fillMaxWidth() else this

/**
 * What a phone pane leaves around its content.
 *
 * 16 dp a side is the Material margin and what every list on the device already sits at, the
 * browse grid included. This was 20, so a screen that used it stood four dp further in than the
 * screen the viewer had just come from, which is the sort of difference nobody names and everybody
 * sees when the two are one gesture apart.
 */
object PhonePad {
    val Side = 16.dp
    val Top = 16.dp
}

private val TV_COLUMN = 640.dp

/** Past this the column stops growing, so a line of text stays a readable length. */
private val PHONE_MAX = 560.dp
