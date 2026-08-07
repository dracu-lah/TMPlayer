package com.tmplayer.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The three glyphs Material's core icon set does not carry.
 *
 * Declared as vectors rather than pulled from `material-icons-extended`, which would add several
 * thousand unused icons to an app that needs exactly these.
 */
object TmIcons {

    /** Broadcast channel. */
    val Channel: ImageVector by lazy {
        icon("Channel") {
            // Megaphone body and handle.
            path(fill = SolidColor(Color.White)) {
                moveTo(3f, 10f)
                verticalLineTo(14f)
                horizontalLineTo(7f)
                lineTo(12f, 19f)
                verticalLineTo(5f)
                lineTo(7f, 10f)
                close()
            }
            path(fill = SolidColor(Color.White)) {
                moveTo(16.5f, 12f)
                curveTo(16.5f, 10.23f, 15.48f, 8.71f, 14f, 7.97f)
                verticalLineTo(16.02f)
                curveTo(15.48f, 15.29f, 16.5f, 13.77f, 16.5f, 12f)
                close()
            }
            path(fill = SolidColor(Color.White)) {
                moveTo(14f, 3.23f)
                verticalLineTo(5.29f)
                curveTo(16.89f, 6.15f, 19f, 8.83f, 19f, 12f)
                curveTo(19f, 15.17f, 16.89f, 17.85f, 14f, 18.71f)
                verticalLineTo(20.77f)
                curveTo(18.01f, 19.86f, 21f, 16.28f, 21f, 12f)
                curveTo(21f, 7.72f, 18.01f, 4.14f, 14f, 3.23f)
                close()
            }
        }
    }

    /** Two people — a group rather than a single contact. */
    val Group: ImageVector by lazy {
        icon("Group") {
            path(fill = SolidColor(Color.White)) {
                moveTo(16f, 11f)
                curveTo(17.66f, 11f, 19f, 9.66f, 19f, 8f)
                curveTo(19f, 6.34f, 17.66f, 5f, 16f, 5f)
                curveTo(14.34f, 5f, 13f, 6.34f, 13f, 8f)
                curveTo(13f, 9.66f, 14.34f, 11f, 16f, 11f)
                close()
            }
            path(fill = SolidColor(Color.White)) {
                moveTo(8f, 11f)
                curveTo(9.66f, 11f, 11f, 9.66f, 11f, 8f)
                curveTo(11f, 6.34f, 9.66f, 5f, 8f, 5f)
                curveTo(6.34f, 5f, 5f, 6.34f, 5f, 8f)
                curveTo(5f, 9.66f, 6.34f, 11f, 8f, 11f)
                close()
            }
            path(fill = SolidColor(Color.White)) {
                moveTo(8f, 13f)
                curveTo(5.67f, 13f, 1f, 14.17f, 1f, 16.5f)
                verticalLineTo(19f)
                horizontalLineTo(15f)
                verticalLineTo(16.5f)
                curveTo(15f, 14.17f, 10.33f, 13f, 8f, 13f)
                close()
            }
            path(fill = SolidColor(Color.White)) {
                moveTo(16f, 13f)
                curveTo(15.71f, 13f, 15.38f, 13.02f, 15.03f, 13.05f)
                curveTo(16.19f, 13.89f, 17f, 15.02f, 17f, 16.5f)
                verticalLineTo(19f)
                horizontalLineTo(23f)
                verticalLineTo(16.5f)
                curveTo(23f, 14.17f, 18.33f, 13f, 16f, 13f)
                close()
            }
        }
    }

    /** Microphone, for voice search. */
    val Mic: ImageVector by lazy {
        icon("Mic") {
            path(fill = SolidColor(Color.White)) {
                moveTo(12f, 14f)
                curveTo(13.66f, 14f, 15f, 12.66f, 15f, 11f)
                verticalLineTo(5f)
                curveTo(15f, 3.34f, 13.66f, 2f, 12f, 2f)
                curveTo(10.34f, 2f, 9f, 3.34f, 9f, 5f)
                verticalLineTo(11f)
                curveTo(9f, 12.66f, 10.34f, 14f, 12f, 14f)
                close()
            }
            path(fill = SolidColor(Color.White)) {
                moveTo(17f, 11f)
                curveTo(17f, 13.76f, 14.76f, 16f, 12f, 16f)
                curveTo(9.24f, 16f, 7f, 13.76f, 7f, 11f)
                horizontalLineTo(5f)
                curveTo(5f, 14.53f, 7.61f, 17.43f, 11f, 17.92f)
                verticalLineTo(21f)
                horizontalLineTo(13f)
                verticalLineTo(17.92f)
                curveTo(16.39f, 17.43f, 19f, 14.53f, 19f, 11f)
                horizontalLineTo(17f)
                close()
            }
        }
    }

    private fun icon(
        name: String,
        content: ImageVector.Builder.() -> ImageVector.Builder,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).content().build()
}
