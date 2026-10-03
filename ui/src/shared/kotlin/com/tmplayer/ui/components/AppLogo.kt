package com.tmplayer.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The player mark as a vector, in its own colours: the same five paths as Android's
 * `res/drawable/ic_logo.xml`, for the places that have no Android resources (the desktop's window
 * icon and its sign in pane). Drawn with `Image`, never `Icon`, which would tint it flat.
 */
object AppLogo {

    val Mark: ImageVector by lazy {
        ImageVector.Builder(
            name = "AppLogo",
            defaultWidth = 192.dp,
            defaultHeight = 192.dp,
            viewportWidth = 1024f,
            viewportHeight = 1024f,
        ).apply {
            layer(0xFF1E1E28, TILE)
            layer(0xFF0E0E12, SCREEN)
            layer(0xFF3A3A46, TRACK)
            layer(0xFF2AABEE, PROGRESS)
            layer(0xFFEDEDF2, PLAYHEAD_AND_TRIANGLE)
        }.build()
    }

    private fun ImageVector.Builder.layer(argb: Long, data: String) {
        addPath(PathParser().parsePathString(data).toNodes(), fill = SolidColor(Color(argb)))
    }

    private const val TILE =
        "M960 1002.666667H64a42.666667 42.666667 0 0 1-42.666667-42.666667V64a42.666667 42.666667 0 0 1 " +
            "42.666667-42.666667h896a42.666667 42.666667 0 0 1 42.666667 42.666667v896a42.666667 " +
            "42.666667 0 0 1-42.666667 42.666667z"
    private const val SCREEN = "M64 64h896v682.666667H64z"
    private const val TRACK =
        "M896 896H597.333333a21.333333 21.333333 0 1 1 0-42.666667h298.666667a21.333333 21.333333 0 1 1 " +
            "0 42.666667z"
    private const val PROGRESS =
        "M661.333333 896H128a21.333333 21.333333 0 1 1 0-42.666667h533.333333a21.333333 21.333333 0 1 1 " +
            "0 42.666667z"
    private const val PLAYHEAD_AND_TRIANGLE =
        "M640 960c-47.04 0-85.333333-38.293333-85.333333-85.333333s38.293333-85.333333 85.333333-85.333334 " +
            "85.333333 38.293333 85.333333 85.333334-38.293333 85.333333-85.333333 85.333333zM426.666667 " +
            "554.666667a21.269333 21.269333 0 0 1-21.333334-21.333334V277.333333a21.333333 21.333333 0 0 1 " +
            "33.173334-17.749333l192 128a21.333333 21.333333 0 0 1 0 35.498667l-192 128A21.333333 21.333333 " +
            "0 0 1 426.666667 554.666667z"
}
