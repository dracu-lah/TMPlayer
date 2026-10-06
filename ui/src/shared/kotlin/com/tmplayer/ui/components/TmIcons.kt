package com.tmplayer.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The glyphs Material's core icon set does not carry.
 *
 * Declared as vectors rather than pulled from `material-icons-extended`, which would add several
 * thousand unused icons to an app that needs exactly these.
 *
 * Every path is filled black, which is what Material fills its own icons with. `Icon` paints a
 * vector through a tint filter, so the colour asked for at the call site is the one that lands and
 * this fill never shows, except under an `Icon` given `Color.Unspecified`, which skips the filter.
 * Black is the fill that fails visibly there rather than invisibly on a light theme.
 */
object TmIcons {

    /** A minus sign, for the stepper buttons. */
    val Remove: ImageVector by lazy { icon("Remove", "M19,13H5V11H19V13Z") }

    /** A clock face with an arrow running back round it: the recent searches. */
    val History: ImageVector by lazy {
        icon(
            "History",
            "M13,3A9,9 0 0,0 4,12H1L4.89,15.89L4.96,16.03L9,12H6A7,7 0 0,1 13,5A7,7 0 0,1 20,12" +
                "A7,7 0 0,1 13,19C11.07,19 9.32,18.21 8.06,16.94L6.64,18.36C8.27,20 10.5,21 13,21" +
                "A9,9 0 0,0 22,12A9,9 0 0,0 13,3M12,8V13L16.28,15.54L17,14.33L13.5,12.25V8H12Z",
        )
    }

    /** Wi-Fi with a slash, for the passive offline status chip. */
    val WifiOff: ImageVector by lazy {
        icon("WifiOff") {
            path(fill = SolidColor(Color.Black)) {
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

    /** Wi-Fi, for the setting that says downloads may only happen over it. */
    val Wifi: ImageVector by lazy {
        icon("Wifi") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 21f)
                lineTo(15.6f, 16.5f)
                curveTo(14.6f, 15.75f, 13.35f, 15.3f, 12f, 15.3f)
                curveTo(10.65f, 15.3f, 9.4f, 15.75f, 8.4f, 16.5f)
                close()
                moveTo(12f, 12.3f)
                curveTo(14.05f, 12.3f, 15.93f, 12.99f, 17.42f, 14.15f)
                lineTo(19.3f, 11.8f)
                curveTo(17.29f, 10.19f, 14.76f, 9.3f, 12f, 9.3f)
                curveTo(9.24f, 9.3f, 6.71f, 10.19f, 4.7f, 11.8f)
                lineTo(6.58f, 14.15f)
                curveTo(8.07f, 12.99f, 9.95f, 12.3f, 12f, 12.3f)
                close()
                moveTo(12f, 6.3f)
                curveTo(15.46f, 6.3f, 18.63f, 7.5f, 21.13f, 9.5f)
                lineTo(23f, 7.16f)
                curveTo(19.99f, 4.75f, 16.17f, 3.3f, 12f, 3.3f)
                curveTo(7.83f, 3.3f, 4.01f, 4.75f, 1f, 7.16f)
                lineTo(2.87f, 9.5f)
                curveTo(5.37f, 7.5f, 8.54f, 6.3f, 12f, 6.3f)
                close()
            }
        }
    }

    /** A clock, for "Recent". */
    val Clock: ImageVector by lazy {
        icon("Clock") {
            path(fill = SolidColor(Color.Black)) {
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
            path(fill = SolidColor(Color.Black)) {
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
     * An unfilled star, for a favourite that is not set. The hole in the middle is cut into the
     * path rather than left to a tint, because focus repaints the whole glyph one colour.
     */
    val StarOutline: ImageVector by lazy {
        icon("StarOutline") {
            path(fill = SolidColor(Color.Black)) {
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
            path(fill = SolidColor(Color.Black)) {
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
            path(fill = SolidColor(Color.Black)) {
                moveTo(3f, 10f)
                verticalLineTo(14f)
                horizontalLineTo(7f)
                lineTo(12f, 19f)
                verticalLineTo(5f)
                lineTo(7f, 10f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(16.5f, 12f)
                curveTo(16.5f, 10.23f, 15.48f, 8.71f, 14f, 7.97f)
                verticalLineTo(16.02f)
                curveTo(15.48f, 15.29f, 16.5f, 13.77f, 16.5f, 12f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
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
            path(fill = SolidColor(Color.Black)) {
                moveTo(16f, 11f)
                curveTo(17.66f, 11f, 19f, 9.66f, 19f, 8f)
                curveTo(19f, 6.34f, 17.66f, 5f, 16f, 5f)
                curveTo(14.34f, 5f, 13f, 6.34f, 13f, 8f)
                curveTo(13f, 9.66f, 14.34f, 11f, 16f, 11f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(8f, 11f)
                curveTo(9.66f, 11f, 11f, 9.66f, 11f, 8f)
                curveTo(11f, 6.34f, 9.66f, 5f, 8f, 5f)
                curveTo(6.34f, 5f, 5f, 6.34f, 5f, 8f)
                curveTo(5f, 9.66f, 6.34f, 11f, 8f, 11f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(8f, 13f)
                curveTo(5.67f, 13f, 1f, 14.17f, 1f, 16.5f)
                verticalLineTo(19f)
                horizontalLineTo(15f)
                verticalLineTo(16.5f)
                curveTo(15f, 14.17f, 10.33f, 13f, 8f, 13f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
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
            path(fill = SolidColor(Color.Black)) {
                moveTo(3f, 3f)
                horizontalLineTo(11f)
                verticalLineTo(11f)
                horizontalLineTo(3f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(13f, 3f)
                horizontalLineTo(21f)
                verticalLineTo(11f)
                horizontalLineTo(13f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(3f, 13f)
                horizontalLineTo(11f)
                verticalLineTo(21f)
                horizontalLineTo(3f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(13f, 13f)
                horizontalLineTo(21f)
                verticalLineTo(21f)
                horizontalLineTo(13f)
                close()
            }
        }
    }

    /** An arrow into a tray: what is held on this device, for the Downloads destination. */
    val Download: ImageVector by lazy {
        icon("Download") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(11f, 3f)
                horizontalLineTo(13f)
                verticalLineTo(11f)
                horizontalLineTo(17f)
                lineTo(12f, 16f)
                lineTo(7f, 11f)
                horizontalLineTo(11f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(5f, 18f)
                horizontalLineTo(19f)
                verticalLineTo(20f)
                horizontalLineTo(5f)
                close()
            }
        }
    }

    /** Microphone, for voice search. */
    val Mic: ImageVector by lazy {
        icon("Mic") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 14f)
                curveTo(13.66f, 14f, 15f, 12.66f, 15f, 11f)
                verticalLineTo(5f)
                curveTo(15f, 3.34f, 13.66f, 2f, 12f, 2f)
                curveTo(10.34f, 2f, 9f, 3.34f, 9f, 5f)
                verticalLineTo(11f)
                curveTo(9f, 12.66f, 10.34f, 14f, 12f, 14f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
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

    /** A tabbed folder, for each of the viewer's own Telegram chat folders. */
    val Folder: ImageVector by lazy {
        icon("Folder") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(10f, 4f)
                horizontalLineTo(4f)
                curveTo(2.9f, 4f, 2f, 4.9f, 2f, 6f)
                verticalLineTo(18f)
                curveTo(2f, 19.1f, 2.9f, 20f, 4f, 20f)
                horizontalLineTo(20f)
                curveTo(21.1f, 20f, 22f, 19.1f, 22f, 18f)
                verticalLineTo(8f)
                curveTo(22f, 6.9f, 21.1f, 6f, 20f, 6f)
                horizontalLineTo(12f)
                lineTo(10f, 4f)
                close()
            }
        }
    }

    /**
     * A lidded box, which is the picture every mail and chat app uses for an archive.
     *
     * The body is hollow by even-odd fill: `Icon` paints the whole vector through one tint, so a
     * knockout in a second colour would come out the same colour and leave a solid slab.
     */
    val Archive: ImageVector by lazy {
        icon("Archive") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(3f, 4f)
                horizontalLineTo(21f)
                verticalLineTo(8f)
                horizontalLineTo(3f)
                close()
            }
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                moveTo(4.5f, 9.5f)
                horizontalLineTo(19.5f)
                verticalLineTo(20.5f)
                horizontalLineTo(4.5f)
                close()
                moveTo(6.5f, 11.5f)
                horizontalLineTo(17.5f)
                verticalLineTo(18.5f)
                horizontalLineTo(6.5f)
                close()
            }
            // The slot in the front, which is what stops the box reading as an empty rectangle.
            path(fill = SolidColor(Color.Black)) {
                moveTo(9f, 13f)
                horizontalLineTo(15f)
                verticalLineTo(15f)
                horizontalLineTo(9f)
                close()
            }
        }
    }

    /** A ribbon bookmark, for Saved Messages. */
    val Bookmark: ImageVector by lazy {
        icon("Bookmark") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(17f, 3f)
                horizontalLineTo(7f)
                curveTo(5.9f, 3f, 5f, 3.9f, 5f, 5f)
                verticalLineTo(21f)
                lineTo(12f, 18f)
                lineTo(19f, 21f)
                verticalLineTo(5f)
                curveTo(19f, 3.9f, 18.1f, 3f, 17f, 3f)
                close()
            }
        }
    }

    /**
     * A plain filled disc.
     *
     * It is the unread dot Telegram puts on a chat, drawn at icon size for the tab that collects
     * them. Nothing more elaborate survives being 24 dp across on a television seen from a sofa.
     */
    val Dot: ImageVector by lazy {
        icon("Dot") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 4f)
                curveTo(7.58f, 4f, 4f, 7.58f, 4f, 12f)
                curveTo(4f, 16.42f, 7.58f, 20f, 12f, 20f)
                curveTo(16.42f, 20f, 20f, 16.42f, 20f, 12f)
                curveTo(20f, 7.58f, 16.42f, 4f, 12f, 4f)
                close()
            }
        }
    }

    /** The two bars, for holding a download where it is. */
    val Pause: ImageVector by lazy {
        icon("Pause") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(6f, 5f)
                horizontalLineTo(10f)
                verticalLineTo(19f)
                horizontalLineTo(6f)
                close()
                moveTo(14f, 5f)
                horizontalLineTo(18f)
                verticalLineTo(19f)
                horizontalLineTo(14f)
                close()
            }
        }
    }

    /**
     * The pushpin Telegram marks a pinned chat with. A shape people read at a glance, in the
     * space a word would spend a third of a narrow line on.
     */
    val Pin: ImageVector by lazy {
        icon(
            "Pin",
            "M16,9V4l1,0c0.55,0 1,-0.45 1,-1v0c0,-0.55 -0.45,-1 -1,-1H7C6.45,2 6,2.45 6,3v0" +
                "c0,0.55 0.45,1 1,1l1,0v5c0,1.66 -1.34,3 -3,3v0c0,0.55 0.45,1 1,1h4.97l0.01,7" +
                "l1,1l1,-1l-0.01,-7H19c0.55,0 1,-0.45 1,-1v0C17.34,12 16,10.66 16,9z",
        )
    }

    /**
     * Three nodes joined by two lines: the share glyph Android has used since Holo.
     *
     * Material's core `Share` is the other one, the arrow leaving a box, which is the iPhone's.
     * On Android the three dots are what people press.
     */
    val Share: ImageVector by lazy {
        icon(
            "Share",
            "M18,16.08c-0.76,0 -1.44,0.3 -1.96,0.77L8.91,12.7C8.96,12.47 9,12.24 9,12" +
                "s-0.04,-0.47 -0.09,-0.7l7.05,-4.11C17.5,7.69 18.21,8 19,8c1.66,0 3,-1.34 3,-3" +
                "s-1.34,-3 -3,-3s-3,1.34 -3,3c0,0.24 0.04,0.47 0.09,0.7L9.04,9.81C8.5,9.31 7.79,9 " +
                "7,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3c0.79,0 1.5,-0.31 2.04,-0.81l7.12,4.16" +
                "c-0.05,0.21 -0.08,0.43 -0.08,0.65c0,1.61 1.31,2.92 2.92,2.92s2.92,-1.31 2.92,-2.92" +
                "S19.61,16.08 18,16.08z",
        )
    }

    /** The plain bell, for giving a silenced chat its voice back. */
    val Bell: ImageVector by lazy {
        icon(
            "Bell",
            "M12,22c1.1,0 2,-0.9 2,-2h-4C10,21.1 10.89,22 12,22zM18,16v-5c0,-3.07 -1.64,-5.64 " +
                "-4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5S10.5,3.17 10.5,4v0.68C7.63,5.36 6,7.92 " +
                "6,11v5l-2,2v1h16v-1L18,16z",
        )
    }

    /** The struck-through bell, for a chat whose notifications the viewer has turned off. */
    val BellOff: ImageVector by lazy {
        icon(
            "BellOff",
            "M20,18.69L7.84,6.14 5.27,3.49 4,4.76l2.8,2.8v0.01c-0.52,0.99 -0.8,2.16 -0.8,3.42" +
                "v5l-2,2v1h13.73l2,2L21,19.72l-1,-1.03zM12,22c1.11,0 2,-0.89 2,-2h-4" +
                "c0,1.11 0.89,2 2,2zM18,14.68V11c0,-3.08 -2.13,-5.64 -5,-6.32V4" +
                "c0,-0.83 -0.67,-1.5 -1.5,-1.5S10,3.17 10,4v0.68c-0.15,0.03 -0.29,0.08 -0.43,0.12" +
                " -0.17,0.05 -0.34,0.11 -0.51,0.18h-0.01c-0.1,0.04 -0.2,0.09 -0.3,0.13" +
                " -0.01,0 -0.01,0 -0.02,0.01 -0.23,0.11 -0.44,0.24 -0.65,0.37l0,0L18,14.68z",
        )
    }

    /** A globe: the language setting. */
    val Language: ImageVector by lazy {
        icon(
            "Language",
            "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2z" +
                "M18.92,8h-2.95c-0.32,-1.25 -0.78,-2.45 -1.38,-3.56 1.84,0.63 3.37,1.91 4.33,3.56z" +
                "M12,4.04c0.83,1.2 1.48,2.53 1.91,3.96h-3.82c0.43,-1.43 1.08,-2.76 1.91,-3.96z" +
                "M4.26,14C4.1,13.36 4,12.69 4,12s0.1,-1.36 0.26,-2h3.38c-0.08,0.66 -0.14,1.32 -0.14,2" +
                " 0,0.68 0.06,1.34 0.14,2L4.26,14z" +
                "M5.08,16h2.95c0.32,1.25 0.78,2.45 1.38,3.56 -1.84,-0.63 -3.37,-1.9 -4.33,-3.56z" +
                "M8.03,8L5.08,8c0.96,-1.66 2.49,-2.93 4.33,-3.56C8.81,5.55 8.35,6.75 8.03,8z" +
                "M12,19.96c-0.83,-1.2 -1.48,-2.53 -1.91,-3.96h3.82c-0.43,1.43 -1.08,2.76 -1.91,3.96z" +
                "M14.34,14L9.66,14c-0.09,-0.66 -0.16,-1.32 -0.16,-2 0,-0.68 0.07,-1.35 0.16,-2h4.68" +
                "c0.09,0.65 0.16,1.32 0.16,2 0,0.68 -0.07,1.34 -0.16,2z" +
                "M14.59,19.56c0.6,-1.11 1.06,-2.31 1.38,-3.56h2.95c-0.96,1.65 -2.49,2.93 -4.33,3.56z" +
                "M16.36,14c0.08,-0.66 0.14,-1.32 0.14,-2 0,-0.68 -0.06,-1.34 -0.14,-2h3.38" +
                "c0.16,0.64 0.26,1.31 0.26,2s-0.1,1.36 -0.26,2h-3.38z",
        )
    }

    /** A beetle: "Report a problem". */
    val Bug: ImageVector by lazy {
        icon(
            "Bug",
            "M20,8h-2.81c-0.45,-0.78 -1.07,-1.45 -1.82,-1.96L17,4.41 15.59,3l-2.17,2.17" +
                "C12.96,5.06 12.49,5 12,5c-0.49,0 -0.96,0.06 -1.41,0.17L8.41,3 7,4.41l1.62,1.63" +
                "C7.88,6.55 7.26,7.22 6.81,8L4,8v2h2.09c-0.05,0.33 -0.09,0.66 -0.09,1v1L4,12v2h2v1" +
                "c0,0.34 0.04,0.67 0.09,1L4,16v2h2.81c1.04,1.79 2.97,3 5.19,3s4.15,-1.21 5.19,-3L20,18" +
                "v-2h-2.09c0.05,-0.33 0.09,-0.66 0.09,-1v-1h2v-2h-2v-1c0,-0.34 -0.04,-0.67 -0.09,-1L20,10L20,8z" +
                "M14,16h-4v-2h4v2zM14,12h-4v-2h4v2z",
        )
    }

    /** A painter's palette: the wallpaper colours switch. */
    val Palette: ImageVector by lazy {
        icon(
            "Palette",
            "M12,3c-4.97,0 -9,4.03 -9,9s4.03,9 9,9c0.83,0 1.5,-0.67 1.5,-1.5 0,-0.39 -0.15,-0.74" +
                " -0.39,-1.01 -0.23,-0.26 -0.38,-0.61 -0.38,-0.99 0,-0.83 0.67,-1.5 1.5,-1.5H16" +
                "c2.76,0 5,-2.24 5,-5 0,-4.42 -4.03,-8 -9,-8z" +
                "M6.5,12c-0.83,0 -1.5,-0.67 -1.5,-1.5S5.67,9 6.5,9 8,9.67 8,10.5 7.33,12 6.5,12z" +
                "M9.5,8C8.67,8 8,7.33 8,6.5S8.67,5 9.5,5s1.5,0.67 1.5,1.5S10.33,8 9.5,8z" +
                "M14.5,8c-0.83,0 -1.5,-0.67 -1.5,-1.5S13.67,5 14.5,5s1.5,0.67 1.5,1.5S15.33,8 14.5,8z" +
                "M17.5,12c-0.83,0 -1.5,-0.67 -1.5,-1.5S16.67,9 17.5,9s1.5,0.67 1.5,1.5 -0.67,1.5 -1.5,1.5z",
        )
    }

    /**
     * A crescent moon: the theme setting. Not the half-shaded sun, which the phone player already
     * uses for its brightness swipe a few rows further down the same screen.
     */
    val Theme: ImageVector by lazy {
        icon(
            "Theme",
            "M12,3c-4.97,0 -9,4.03 -9,9s4.03,9 9,9 9,-4.03 9,-9c0,-0.46 -0.04,-0.92 -0.1,-1.36" +
                " -0.98,1.37 -2.58,2.26 -4.4,2.26 -2.98,0 -5.4,-2.42 -5.4,-5.4 0,-1.81 0.89,-3.42" +
                " 2.26,-4.4C12.92,3.04 12.46,3 12,3z",
        )
    }

    /** A framed picture: thumbnails, posters, anything that is an image rather than a video. */
    val Image: ImageVector by lazy {
        icon(
            "Image",
            "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14" +
                "c1.1,0 2,-0.9 2,-2zM8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z",
        )
    }

    /**
     * A stack of pictures: the frames shown over the scrub bar. Apart from [Image], which is the
     * posters row, so two settings about different pictures do not wear the same glyph.
     */
    val Frames: ImageVector by lazy {
        icon(
            "Frames",
            "M22,16V4c0,-1.1 -0.9,-2 -2,-2H8c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h12" +
                "c1.1,0 2,-0.9 2,-2zM11,12l2.03,2.71L16,11l4,5H8l3,-4z" +
                "M2,6v14c0,1.1 0.9,2 2,2h14v-2H4V6H2z",
        )
    }

    /** A judge's gavel: the lawful use page. */
    val Gavel: ImageVector by lazy {
        icon(
            "Gavel",
            "M1,21h12v2H1v-2zM5.24,8.07l2.83,-2.83 14.14,14.14 -2.83,2.83L5.24,8.07z" +
                "M12.32,1l5.66,5.66 -2.83,2.83 -5.66,-5.66L12.32,1z" +
                "M3.83,9.48l5.66,5.66 -2.83,2.83L1,12.31l2.83,-2.83z",
        )
    }

    /** An eye struck through: the controls going out of sight. */
    val VisibilityOff: ImageVector by lazy {
        icon(
            "VisibilityOff",
            "M12,7c2.76,0 5,2.24 5,5 0,0.65 -0.13,1.26 -0.36,1.83l2.92,2.92c1.51,-1.26 2.7,-2.89" +
                " 3.43,-4.75 -1.73,-4.39 -6,-7.5 -11,-7.5 -1.4,0 -2.74,0.25 -3.98,0.7l2.16,2.16" +
                "C10.74,7.13 11.35,7 12,7z" +
                "M2,4.27l2.28,2.28 0.46,0.46C3.08,8.3 1.78,10.02 1,12c1.73,4.39 6,7.5 11,7.5" +
                " 1.55,0 3.03,-0.3 4.38,-0.84l0.42,0.42L19.73,22 21,20.73 3.27,3 2,4.27z" +
                "M7.53,9.8l1.55,1.55c-0.05,0.21 -0.08,0.43 -0.08,0.65 0,1.66 1.34,3 3,3" +
                " 0.22,0 0.44,-0.03 0.65,-0.08l1.55,1.55c-0.67,0.33 -1.41,0.53 -2.2,0.53" +
                " -2.76,0 -5,-2.24 -5,-5 0,-0.79 0.2,-1.53 0.53,-2.2z" +
                "M11.84,9.02l3.15,3.15 0.02,-0.16c0,-1.66 -1.34,-3 -3,-3l-0.17,0.01z",
        )
    }

    /** A phone between shake marks: the haptics switch. */
    val Vibration: ImageVector by lazy {
        icon(
            "Vibration",
            "M0,15h2V9H0v6zM3,17h2V7H3v10zM22,9v6h2V9h-2zM19,17h2V7h-2v10z" +
                "M16.5,3h-9C6.67,3 6,3.67 6,4.5v15c0,0.83 0.67,1.5 1.5,1.5h9c0.83,0 1.5,-0.67 1.5,-1.5" +
                "v-15c0,-0.83 -0.67,-1.5 -1.5,-1.5zM16,19H8V5h8v14z",
        )
    }

    /** A question mark in a circle: the walkthrough, "how does this work". */
    val Help: ImageVector by lazy {
        icon(
            "Help",
            "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2z" +
                "M13,19h-2v-2h2v2z" +
                "M15.07,11.25l-0.9,0.92C13.45,12.9 13,13.5 13,15h-2v-0.5c0,-1.1 0.45,-2.1 1.17,-2.83" +
                "l1.24,-1.26c0.37,-0.36 0.59,-0.86 0.59,-1.41 0,-1.1 -0.9,-2 -2,-2s-2,0.9 -2,2H8" +
                "c0,-2.21 1.79,-4 4,-4s4,1.79 4,4c0,0.88 -0.36,1.68 -0.93,2.25z",
        )
    }

    /**
     * Two ticked lines: picking several at once. Kept apart from the single tick that marks one
     * video watched, which sits a row above it in the same menu.
     */
    val Checklist: ImageVector by lazy {
        icon(
            "Checklist",
            "M22,7h-9v2h9V7zM22,15h-9v2h9v-2z" +
                "M5.54,11L2,7.46l1.41,-1.41l2.12,2.12l4.24,-4.24l1.41,1.41L5.54,11z" +
                "M5.54,19L2,15.46l1.41,-1.41l2.12,2.12l4.24,-4.24l1.41,1.41L5.54,19z",
        )
    }

    /**
     * A remote control under signal arcs: the remote's keys. Material's "settings remote", so it
     * reads apart from the info mark on the playback details line beside it.
     */
    val Remote: ImageVector by lazy {
        icon(
            "Remote",
            "M15,9H9c-0.55,0 -1,0.45 -1,1v12c0,0.55 0.45,1 1,1h6c0.55,0 1,-0.45 1,-1V10" +
                "c0,-0.55 -0.45,-1 -1,-1zM12,15c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2z" +
                "M7.05,6.05l1.41,1.41C9.37,6.56 10.62,6 12,6s2.63,0.56 3.54,1.46l1.41,-1.41" +
                "C15.68,4.78 13.93,4 12,4s-3.68,0.78 -4.95,2.05z" +
                "M12,0C8.96,0 6.21,1.23 4.22,3.22l1.41,1.41C7.26,3.01 9.51,2 12,2s4.74,1.01 6.36,2.64" +
                "l1.41,-1.41C17.79,1.23 15.04,0 12,0z",
        )
    }

    /**
     * Episodes: three frames stacked one behind another, a season's worth. SVG Repo's "episodes"
     * (343094), drawn in lines on its own 21 unit grid, as the source has it.
     */
    val Episodes: ImageVector by lazy {
        ImageVector.Builder(
            name = "Episodes",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 21f,
            viewportHeight = 21f,
        ).group(translationX = 1f, translationY = 4f) {
            listOf(
                "m13.5 12.5v-6c0-1.1045695-.8954305-2-2-2h-9c-1.1045695 0-2 .8954305-2 2v6c0 1.1045695.8954305 2 2 2h9c1.1045695 0 2-.8954305 2-2z",
                "m15.5 12.5v-6.99481259c0-1.65685425-1.3431458-3-3-3-.0017276 0-.0034553 0-.0051829 0l-8.9948171.01554432",
                "m17.5 10.5v-5.99308345c0-2.209139-1.790861-4-4-4-.0023035 0-.004607 0-.0069106 0l-7.9930894.01381519",
            ).forEach { data ->
                addPath(
                    pathData = PathParser().parsePathString(data).toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.25f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
    }

    /**
     * The same builder again, given SVG path data rather than the drawing DSL.
     *
     * For glyphs whose forty curves would be forty chances to mistype a `curveTo` call.
     * [PathParser] is the same parser an XML vector drawable goes through, so what is drawn is what
     * the icon set drew.
     */
    private fun icon(name: String, pathData: String): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = PathParser().parsePathString(pathData).toNodes(),
        fill = SolidColor(Color.Black),
    ).build()

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
