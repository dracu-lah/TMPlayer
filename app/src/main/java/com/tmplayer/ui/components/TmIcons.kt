package com.tmplayer.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The glyphs Material's core icon set does not carry.
 *
 * Declared as vectors rather than pulled from `material-icons-extended`, which would add several
 * thousand unused icons to an app that needs exactly these.
 */
object TmIcons {

    /** Wi-Fi with a slash, for the passive offline status chip. */
    val WifiOff: ImageVector by lazy {
        icon("WifiOff") {
            path(fill = SolidColor(Color.White)) {
                moveTo(2.27f, 3.55f)
                lineTo(20.45f, 21.73f)
                lineTo(21.73f, 20.45f)
                lineTo(18.32f, 17.04f)
                curveTo(19.06f, 16.63f, 19.85f, 16.29f, 20.68f, 16.04f)
                lineTo(18.62f, 13.98f)
                curveTo(17.82f, 14.28f, 17.05f, 14.66f, 16.34f, 15.11f)
                lineTo(14.85f, 13.62f)
                curveTo(16.24f, 12.72f, 17.85f, 12.08f, 19.58f, 11.79f)
                lineTo(17.42f, 9.63f)
                curveTo(15.76f, 10.03f, 14.2f, 10.69f, 12.81f, 11.57f)
                lineTo(11.26f, 10.02f)
                curveTo(13.1f, 8.79f, 15.23f, 7.94f, 17.52f, 7.58f)
                lineTo(15.37f, 5.43f)
                curveTo(13.12f, 5.92f, 11.01f, 6.81f, 9.16f, 8.03f)
                lineTo(3.55f, 2.27f)
                close()
                moveTo(1.42f, 9.45f)
                lineTo(3.58f, 11.61f)
                curveTo(4.39f, 11.2f, 5.24f, 10.84f, 6.13f, 10.57f)
                lineTo(4.02f, 8.46f)
                curveTo(3.12f, 8.74f, 2.25f, 9.07f, 1.42f, 9.45f)
                close()
                moveTo(5.32f, 13.96f)
                lineTo(7.43f, 16.07f)
                curveTo(8.08f, 15.72f, 8.77f, 15.44f, 9.49f, 15.23f)
                lineTo(7.35f, 13.09f)
                curveTo(6.65f, 13.34f, 5.97f, 13.63f, 5.32f, 13.96f)
                close()
                moveTo(9.17f, 18.02f)
                lineTo(12f, 20.85f)
                lineTo(13.48f, 19.37f)
                lineTo(10.73f, 16.62f)
                close()
            }
        }
    }

    /** A clock, for "Recent". */
    val Clock: ImageVector by lazy {
        icon("Clock") {
            path(fill = SolidColor(Color.White)) {
                moveTo(12f, 2f)
                curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
                curveTo(2f, 17.52f, 6.48f, 22f, 12f, 22f)
                curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
                curveTo(22f, 6.48f, 17.52f, 2f, 12f, 2f)
                close()
                moveTo(12f, 20f)
                curveTo(7.59f, 20f, 4f, 16.41f, 4f, 12f)
                curveTo(4f, 7.59f, 7.59f, 4f, 12f, 4f)
                curveTo(16.41f, 4f, 20f, 7.59f, 20f, 12f)
                curveTo(20f, 16.41f, 16.41f, 20f, 12f, 20f)
                close()
            }
            // Hour hand up, minute hand right, meeting at the centre.
            path(fill = SolidColor(Color.White)) {
                moveTo(12.5f, 6f)
                horizontalLineTo(11f)
                verticalLineTo(13f)
                horizontalLineTo(16.75f)
                verticalLineTo(11.5f)
                horizontalLineTo(12.5f)
                close()
            }
        }
    }

    /**
     * An unfilled star, for a favourite that is not set. Tint alone could not carry it: focus
     * repaints the icon white.
     */
    val StarOutline: ImageVector by lazy {
        icon("StarOutline") {
            path(fill = SolidColor(Color.White)) {
                moveTo(12f, 8.89f)
                lineTo(13.03f, 12.11f)
                lineTo(13.26f, 12.83f)
                horizontalLineTo(16.47f)
                lineTo(13.86f, 14.73f)
                lineTo(13.25f, 15.17f)
                lineTo(14.28f, 18.39f)
                lineTo(12f, 16.4f)
                lineTo(9.72f, 18.39f)
                lineTo(10.75f, 15.17f)
                lineTo(10.14f, 14.73f)
                lineTo(7.53f, 12.83f)
                horizontalLineTo(10.74f)
                lineTo(10.97f, 12.11f)
                close()
                moveTo(12f, 2f)
                lineTo(9.19f, 8.63f)
                lineTo(2f, 9.24f)
                lineTo(7.45f, 13.97f)
                lineTo(5.82f, 21f)
                lineTo(12f, 17.27f)
                lineTo(18.18f, 21f)
                lineTo(16.54f, 13.97f)
                lineTo(22f, 9.24f)
                lineTo(14.81f, 8.62f)
                close()
            }
        }
    }

    /** An empty circle: the unselected half of a radio pair. */
    val CircleOutline: ImageVector by lazy {
        icon("CircleOutline") {
            path(fill = SolidColor(Color.White)) {
                moveTo(12f, 2f)
                curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
                curveTo(2f, 17.52f, 6.48f, 22f, 12f, 22f)
                curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
                curveTo(22f, 6.48f, 17.52f, 2f, 12f, 2f)
                close()
                moveTo(12f, 20f)
                curveTo(7.59f, 20f, 4f, 16.41f, 4f, 12f)
                curveTo(4f, 7.59f, 7.59f, 4f, 12f, 4f)
                curveTo(16.41f, 4f, 20f, 7.59f, 20f, 12f)
                curveTo(20f, 16.41f, 16.41f, 20f, 12f, 20f)
                close()
            }
        }
    }

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

    /** Two people: a group rather than a single contact. */
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

    /**
     * Four panes, for the grid arrangement.
     *
     * The partner of Material's own `List`, which is what the other half of that toggle uses.
     */
    val Grid: ImageVector by lazy {
        icon("Grid") {
            path(fill = SolidColor(Color.White)) {
                moveTo(3f, 3f)
                horizontalLineTo(11f)
                verticalLineTo(11f)
                horizontalLineTo(3f)
                close()
            }
            path(fill = SolidColor(Color.White)) {
                moveTo(13f, 3f)
                horizontalLineTo(21f)
                verticalLineTo(11f)
                horizontalLineTo(13f)
                close()
            }
            path(fill = SolidColor(Color.White)) {
                moveTo(3f, 13f)
                horizontalLineTo(11f)
                verticalLineTo(21f)
                horizontalLineTo(3f)
                close()
            }
            path(fill = SolidColor(Color.White)) {
                moveTo(13f, 13f)
                horizontalLineTo(21f)
                verticalLineTo(21f)
                horizontalLineTo(13f)
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
