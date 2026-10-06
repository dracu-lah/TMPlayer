package com.tmplayer.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.relocation.bringIntoView as bringNodeIntoView

/**
 * Whatever inside this block asks to be brought into view (a button taking focus, mostly) is shown
 * together with everything above it in the block, from the block's top edge down.
 *
 * Meant for the first screen of a scrolling page: a title page's picture, title and buttons. Coming
 * back up to the buttons from below the fold, the scrolling parent would otherwise stop as soon as
 * the button itself showed (or, on a television, at its pivot a third of the way down), leaving the
 * title and the picture cut off at the top. With this the one request the focus makes is already
 * for the whole top of the block, so the parent scrolls back to it in a single movement: nothing
 * races the focus's own scroll, so nothing jitters.
 *
 * Only a request changes anything, and touch scrolling makes none, so a finger stays free to scroll
 * anywhere on a phone.
 */
fun Modifier.revealFromTop(): Modifier = this then RevealFromTopElement

private data object RevealFromTopElement : ModifierNodeElement<RevealFromTopNode>() {
    override fun create(): RevealFromTopNode = RevealFromTopNode()
    override fun update(node: RevealFromTopNode) = Unit
}

private class RevealFromTopNode : Modifier.Node(), BringIntoViewModifierNode {
    override suspend fun bringIntoView(childCoordinates: LayoutCoordinates, boundsProvider: () -> Rect?) {
        // Passed on to the scrolling parent as a rectangle in this block's own coordinates: from
        // the block's top to the foot of what asked, as wide as the block.
        bringNodeIntoView {
            if (!isAttached || !childCoordinates.isAttached) return@bringNodeIntoView null
            val own = requireLayoutCoordinates()
            val child = boundsProvider() ?: return@bringNodeIntoView null
            val foot = own.localPositionOf(childCoordinates, Offset.Zero).y + child.bottom
            Rect(0f, 0f, own.size.width.toFloat(), foot.coerceAtLeast(0f))
        }
    }
}
