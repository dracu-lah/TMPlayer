package com.tmplayer.desktop.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The player's glyphs, drawn from Material icon paths (Apache 2.0), because the desktop build
 * carries only the core icon set and the extended one is several megabytes for a dozen shapes.
 */
object PlayerIcons {
    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(addPathNodes(path), fill = SolidColor(Color.White))
            .build()

    val Play = icon("Play", "M8 5v14l11-7z")
    val Pause = icon("Pause", "M6 19h4V5H6v14zm8-14v14h4V5h-4z")
    val Replay = icon(
        "Replay",
        "M12 5V1L7 6l5 5V7c3.31 0 6 2.69 6 6s-2.69 6-6 6-6-2.69-6-6H4c0 4.42 3.58 8 8 8s8-3.58 8-8-3.58-8-8-8z",
    )
    val SkipNext = icon("SkipNext", "M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z")
    val SkipPrevious = icon("SkipPrevious", "M6 6h2v12H6zm3.5 6l8.5 6V6z")
    val VolumeUp = icon(
        "VolumeUp",
        "M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02z" +
            "M14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z",
    )
    val VolumeDown = icon(
        "VolumeDown",
        "M18.5 12c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM5 9v6h4l5 5V4L9 9H5z",
    )
    val VolumeOff = icon(
        "VolumeOff",
        "M16.5 12c0-1.77-1.02-3.29-2.5-4.03v2.21l2.45 2.45c.03-.2.05-.41.05-.63zm2.5 0c0 .94-.2 1.82-.54 2.64" +
            "l1.51 1.51C20.63 14.91 21 13.5 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71z" +
            "M4.27 3L3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06c1.38-.31 2.63-.95 " +
            "3.69-1.81L19.73 21 21 19.73l-9-9L4.27 3zM12 4L9.91 6.09 12 8.18V4z",
    )
    val Fullscreen = icon(
        "Fullscreen",
        "M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z",
    )
    val FullscreenExit = icon(
        "FullscreenExit",
        "M5 16h3v3h2v-5H5v2zm3-8H5v2h5V5H8v3zm6 11h2v-3h3v-2h-5v5zm2-11V5h-2v5h5V8h-3z",
    )
    val Subtitles = icon(
        "Subtitles",
        "M20 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zM4 12h4v2H4v-2zm10 6H4v-2h10v2z" +
            "m6 0h-4v-2h4v2zm0-4H10v-2h10v2z",
    )
    val Audio = icon(
        "Audio",
        "M12 3v9.28c-.47-.17-.97-.28-1.5-.28C8.01 12 6 14.01 6 16.5S8.01 21 10.5 21c2.31 0 4.2-1.75 4.45-4H15V6h4V3h-7z",
    )
    val PictureInPicture = icon(
        "PictureInPicture",
        "M19 7h-8v6h8V7zm2-4H3c-1.1 0-2 .9-2 2v14c0 1.1.9 1.98 2 1.98h18c1.1 0 2-.88 2-1.98V5c0-1.1-.9-2-2-2z" +
            "m0 16.01H3V4.98h18v14.03z",
    )
    val AspectRatio = icon(
        "AspectRatio",
        "M19 12h-2v3h-3v2h5v-5zM7 9h3V7H5v5h2V9zm14-6H3c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h18c1.1 0 2-.9 2-2V5" +
            "c0-1.1-.9-2-2-2zm0 16.01H3V4.99h18v14.02z",
    )
    val ArrowBack = icon("ArrowBack", "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z")
    val MoreVert = icon(
        "MoreVert",
        "M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z" +
            "m0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z",
    )
    val Close = icon(
        "Close",
        "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z",
    )
    val Check = icon("Check", "M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z")
}
