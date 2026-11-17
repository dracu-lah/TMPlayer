package androidx.media3.session

// Media3's notification buttons on iOS, by name: the player's notification is iOS's Now Playing,
// whose skip and episode controls are the system's (layer 4).

/** A button of the media notification, Media3's. */
class CommandButton private constructor(
    val icon: Int,
    val playerCommand: Int,
    val displayName: CharSequence,
    val slots: List<Int>,
    val isEnabled: Boolean,
) {
    class Builder(private val icon: Int) {
        private var playerCommand = -1
        private var displayName: CharSequence = ""
        private var slots: List<Int> = listOf(SLOT_OVERFLOW)
        private var enabled = true

        constructor() : this(ICON_UNDEFINED)

        fun setPlayerCommand(playerCommand: Int): Builder = apply { this.playerCommand = playerCommand }

        fun setDisplayName(displayName: CharSequence): Builder = apply { this.displayName = displayName }

        fun setSlots(vararg slots: Int): Builder = apply { this.slots = slots.toList() }

        fun setEnabled(enabled: Boolean): Builder = apply { this.enabled = enabled }

        fun setIconResId(@Suppress("UNUSED_PARAMETER") iconResId: Int): Builder = this

        fun build(): CommandButton = CommandButton(icon, playerCommand, displayName, slots, enabled)
    }

    companion object {
        const val SLOT_CENTRAL = 1
        const val SLOT_BACK = 2
        const val SLOT_FORWARD = 3
        const val SLOT_BACK_SECONDARY = 4
        const val SLOT_FORWARD_SECONDARY = 5
        const val SLOT_OVERFLOW = 6

        const val ICON_UNDEFINED = 0
        const val ICON_PLAY = 1
        const val ICON_PAUSE = 2
        const val ICON_STOP = 3
        const val ICON_NEXT = 4
        const val ICON_PREVIOUS = 5
        const val ICON_SKIP_FORWARD = 6
        const val ICON_SKIP_FORWARD_5 = 7
        const val ICON_SKIP_FORWARD_10 = 8
        const val ICON_SKIP_FORWARD_15 = 9
        const val ICON_SKIP_FORWARD_30 = 10
        const val ICON_SKIP_BACK = 11
        const val ICON_SKIP_BACK_5 = 12
        const val ICON_SKIP_BACK_10 = 13
        const val ICON_SKIP_BACK_15 = 14
        const val ICON_SKIP_BACK_30 = 15
    }
}
