package com.tmplayer.online

import com.tmplayer.data.MediaItem
import com.tmplayer.i18n.L

/**
 * Everything the phone and television player know about skipping that is not drawing: where the
 * stretches come from (the file's chapters, what the online lists said, the viewer's marks), the
 * viewer's choice per kind, the three second countdown, and the two taps of "Mark this as the
 * intro". The model is [SkipSession]; this class only binds it to one playing video and to the
 * settings, so the phone, the television and the desktop player each hold one and call it the same way.
 *
 * [say] shows a short line over the picture (the same gesture feedback the chapter steps use).
 */
class SkipController(private val say: (String) -> Unit) {
    val session = SkipSession()
    private val marking = IntroMarking()
    private var itemId = ""
    private var seasonKey: String? = null

    /** Binds to the video that is about to play: its own mark, or the season's, is read at once. */
    fun start(item: MediaItem) {
        itemId = item.id
        seasonKey = UserSkipMarks.seasonKeyOf(item)
        marking.cancel()
        session.marks(UserSkipMarks.segmentsFor(itemId, seasonKey, 0L))
    }

    /** Whether "Mark this as the intro" is offered: an episode of a show, which has a season to share it with. */
    val canMark: Boolean get() = seasonKey != null && itemId.isNotBlank()

    /** Between the two taps: the line then reads "Mark where the intro ends". */
    val introMarking: Boolean get() = marking.started

    val hasOwnMark: Boolean get() = UserSkipMarks.hasOwn(itemId)

    fun online(list: List<SkipSegment>) = session.online(list)

    fun chapters(list: List<com.tmplayer.player.Chapter>) = session.chapters(list)

    /** The merged stretches of a file [durationMs] long, for the next-up card's credits start. */
    fun segments(durationMs: Long): List<SkipSegment> = session.segments(durationMs)

    /**
     * What the pill should show now, or null. The next-up card owns credits that run to the end of
     * an episode that has a successor ([cardCoversTheEnd]), so those are not offered or skipped here.
     */
    fun offer(positionMs: Long, durationMs: Long, playing: Boolean, nowMs: Long, skipped: Set<Long>, cardCoversTheEnd: Boolean): SkipOffer? {
        session.modes = modesInForce()
        val offer = session.offer(positionMs, durationMs, playing, nowMs, skipped) ?: return null
        return offer.takeUnless { cardCoversTheEnd && it.segment.runsToEnd(durationMs) }
    }

    /** Cancel on the countdown. */
    fun cancel(segment: SkipSegment) = session.cancel(segment)

    /** One tap of "Mark this as the intro" at [positionMs]; the second saves it. */
    fun markTap(positionMs: Long, durationMs: Long) {
        when (val step = marking.tap(positionMs)) {
            is MarkStep.Started -> say(L.playerMarkIntroStarted)
            is MarkStep.Restarted -> say(L.playerMarkIntroRestarted)
            MarkStep.TooShort -> say(L.playerMarkIntroTooShort)
            is MarkStep.Done -> {
                if (UserSkipMarks.save(itemId, seasonKey, step.mark)) {
                    session.marks(UserSkipMarks.segmentsFor(itemId, seasonKey, durationMs))
                    say(L.playerMarkIntroSaved)
                }
            }
        }
    }

    /** Removes this video's mark (and the season's guess when it was the same), back to the lists. */
    fun removeMark(durationMs: Long) {
        UserSkipMarks.forget(itemId, seasonKey)
        marking.cancel()
        session.marks(UserSkipMarks.segmentsFor(itemId, seasonKey, durationMs))
        say(L.playerMarkIntroRemoved)
    }

    private fun modesInForce(): SkipModes = OnlineMetadata.current?.settings?.value?.skipModesInForce ?: SkipModes()
}
