package com.tmplayer.player

import android.content.Context
import android.content.SharedPreferences

/**
 * OFF since 2026-11-19 (user: icons only): nothing in the app calls [takeSession] and the player
 * passes no `showLabels`, so these words never show. The machinery stays for a later, opt-in use.
 *
 * The words under the player's six icons (Subtitles, Sound, Speed, Fit, Rotate, Lock), for the first
 * [SESSIONS] times the player is opened and then icons only, remembered (R5, Raju step 6.2). Someone
 * who has never used a video app cannot tell a speed dial from a lock; someone who has used the
 * player three times knows, and the row goes back to its quiet look.
 *
 * A "session" is one opening of the player screen. The count is kept in a private file, capped at
 * [SESSIONS] so it never grows, and a file that cannot be read or written means no labels rather
 * than an error: the icons still carry their names for TalkBack and for a long press.
 */
object ControlLabels {

    /** How many openings of the player carry the words. */
    const val SESSIONS = 3

    /** The words are not scaled past this: at the largest system font a word under each of six icons would be wider than the row. */
    const val MAX_FONT_SCALE = 1.3f

    /** The words' size in sp at font scale 1.0. */
    const val TEXT_SP = 11f

    private const val FILE = "player_control_labels"
    private const val KEY = "sessions"

    /** Whether a player opening that is number [seen] + 1 shows the words. */
    fun shows(seen: Int): Boolean = seen < SESSIONS

    /** The count after one more opening, never above [SESSIONS]. */
    fun after(seen: Int): Int = (seen + 1).coerceAtMost(SESSIONS)

    /** The words' size in sp on a television, where nothing is under 14 (WP-04). */
    const val TV_TEXT_SP = 14f

    /** The words' size in pixels: [TEXT_SP] ([TV_TEXT_SP] on a television) at the system font scale, held at [MAX_FONT_SCALE]. */
    fun textPx(density: Float, fontScale: Float, tv: Boolean = false): Float =
        (if (tv) TV_TEXT_SP else TEXT_SP) * density * fontScale.coerceIn(1f, MAX_FONT_SCALE)

    /**
     * Counts this opening of the player and says whether it shows the words. Call once per player
     * screen, not per video: an episode that follows another in the same screen is the same session.
     */
    fun takeSession(context: Context): Boolean = takeSession(
        runCatching { context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE) }.getOrNull(),
    )

    internal fun takeSession(prefs: SharedPreferences?): Boolean {
        prefs ?: return false
        return runCatching {
            val seen = prefs.getInt(KEY, 0).coerceIn(0, SESSIONS)
            if (seen < SESSIONS) prefs.edit().putInt(KEY, after(seen)).apply()
            shows(seen)
        }.getOrDefault(false)
    }
}
