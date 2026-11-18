package com.tmplayer.player

/**
 * Rotate picture (#34): the video turned on the screen a quarter at a time, clockwise, as it is
 * drawn. Nothing is decoded again and the file is left as it is; the turned picture is fitted
 * inside the screen with its shape kept, never stretched.
 *
 * Not the screen orientation (the phone row's Rotate button, `rotation_*` in the catalog), which
 * turns the whole screen and everything on it: this turns only the picture inside it, which is
 * what a projector on its side or a video filmed the wrong way up needs.
 *
 * Remembered for the video playing (an engine or decoder change keeps it) and back to [NONE]
 * when another video opens: a turn is about one file, not about the screen.
 */
object PictureRotation {
    const val NONE = 0

    /** The four turns, in clockwise degrees, the one that changes nothing first. */
    val CHOICES: List<Int> = listOf(0, 90, 180, 270)

    /**
     * [degrees] as one of [CHOICES]: negative and past a whole turn wrap round, and anything
     * between two quarters goes to the nearer one, so a stray value never reaches a renderer.
     */
    fun normalise(degrees: Int): Int {
        val wrapped = degrees.mod(360)
        return ((wrapped + 45) / 90 * 90).mod(360)
    }

    /** The next quarter turn clockwise, 270 coming back round to 0. */
    fun next(degrees: Int): Int = normalise(normalise(degrees) + 90)

    /** Whether the turned picture's width and height trade places (90 and 270). */
    fun sideways(degrees: Int): Boolean = normalise(degrees) % 180 != 0

    /** The same turn counter-clockwise, which is how Media3's ScaleAndRotateTransformation counts. */
    fun counterClockwise(degrees: Int): Int = (360 - normalise(degrees)).mod(360)

    /** The width over height of a picture of [aspect] once turned; 0 or less (not known yet) stays as it is. */
    fun turnedAspect(aspect: Double, degrees: Int): Double =
        if (aspect > 0.0 && sideways(degrees)) 1.0 / aspect else aspect
}
