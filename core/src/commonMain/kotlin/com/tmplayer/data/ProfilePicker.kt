package com.tmplayer.data

import kotlinx.coroutines.launch

/**
 * What the "Who's watching?" picker shows (HX88), worked out apart from any screen so the phone,
 * the TV, the desktop and iOS draw the same thing: one tile per profile in the list's order, then
 * Add account while there is room ([AccountRules.MAX]).
 */
data class PickerModel(
    val tiles: List<ProfileTile>,
    /** The Add account tile is shown: fewer than [AccountRules.MAX] profiles. */
    val canAdd: Boolean,
    /** Every place is taken: the line under the row says the limit instead of the Add tile. */
    val atLimit: Boolean,
)

/** One profile's tile. */
data class ProfileTile(
    val id: String,
    /** The name under the avatar: the viewer's own, Telegram's, or "" for one never signed in (the screen numbers it). */
    val name: String,
    /** Its place in the row, from 1, for the name of a profile with none. */
    val number: Int,
    /** The small photo kept for the picker, or null for the initials. */
    val photo: String?,
    /** A PIN guards it: the tile wears the lock badge and opening it asks for the PIN. */
    val locked: Boolean,
    val status: TileStatus,
    /** The profile open now. */
    val active: Boolean,
)

/** What opening a tile does, and what the tile says under its name. */
enum class TileStatus {
    /** Opens the profile. */
    Ready,

    /** Its folders are missing or broken ([ProfileState.Recoverable]): "Needs attention", with Sign in again or Remove. */
    NeedsAttention,

    /** Its login ended (on another device, or expired): opening it shows the sign in. Other profiles are unaffected. */
    SignInAgain,

    /** Made by Add account and not signed in yet: opening it carries on with the sign in. */
    Adding,
}

object ProfilePicker {

    /** The picker shows at launch only with two profiles or more: one never sees it. */
    fun atLaunch(list: AccountList): Boolean = list.profiles.size >= 2

    /**
     * The picker for [list]. [stateOf] tells a broken profile's folders ([AccountSwitcher.stateOf]);
     * [activeNeedsSignIn] is true while the open profile is on a sign in screen, which for a
     * profile that had signed in means its login ended.
     */
    fun model(list: AccountList, stateOf: (Profile) -> ProfileState, activeNeedsSignIn: Boolean): PickerModel {
        val tiles = list.profiles.mapIndexed { index, profile ->
            val active = profile.id == list.activeId
            val state = runCatching { stateOf(profile) }.getOrDefault(ProfileState.Ready)
            val status = when {
                state is ProfileState.Recoverable -> TileStatus.NeedsAttention
                profile.adding || (!profile.signedIn && !profile.adopted) -> TileStatus.Adding
                active && activeNeedsSignIn -> TileStatus.SignInAgain
                else -> TileStatus.Ready
            }
            ProfileTile(
                id = profile.id,
                name = profile.shownName,
                number = index + 1,
                photo = profile.photo,
                locked = profile.pin != null,
                status = status,
                active = active,
            )
        }
        val canAdd = AccountRules.canAdd(list)
        return PickerModel(tiles, canAdd = canAdd, atLimit = !canAdd)
    }

    /**
     * What a press on [tile] asks for: the PIN first when it has one (a broken profile needs none:
     * there is nothing in it to guard, and its way out is Remove or Sign in again).
     */
    fun needsPin(tile: ProfileTile): Boolean = tile.locked && tile.status != TileStatus.NeedsAttention

    /**
     * Whether switching to another profile must ask first: a video playing or a download moving
     * belongs to the profile open now, and the switch stops the one and pauses the other.
     */
    fun asksFirst(playing: Boolean, downloading: Boolean): Boolean = playing || downloading
}

/**
 * What runs before a switch closes the open profile (HX88): each player saves its place and
 * stops, each download runner pauses, while the open profile's stores are still the ones they
 * write to. Registered by the platform's players and runners; [runAll] is called by the picker
 * after the viewer has agreed to stop what plays.
 */
object BeforeSwitch {
    private class Entry(val hook: suspend () -> Unit, val label: () -> String?)

    private val hooks = java.util.concurrent.CopyOnWriteArrayList<Entry>()

    /** [hook] runs before each switch; [label] says what it waits for while it does ("a sync"), null for nothing to say. */
    fun add(hook: suspend () -> Unit, label: () -> String? = { null }) {
        hooks += Entry(hook, label)
    }

    fun remove(hook: suspend () -> Unit): Boolean = hooks.removeAll { it.hook === hook }

    /**
     * Every hook at once, all under one [deadlineMs] (HX88): a switch never waits longer than that
     * for the slowest, and a job that has not stopped by then is abandoned safely, since every
     * write of it carries its owner. [onWaiting] hears what is still being waited for, as it goes.
     * One that fails is logged; the rest still run.
     */
    suspend fun runAll(deadlineMs: Long = DEADLINE_MS, onWaiting: (List<String>) -> Unit = {}) {
        val entries = hooks.toList()
        if (entries.isEmpty()) return
        val left = java.util.concurrent.ConcurrentHashMap<Int, String>()
        entries.forEachIndexed { index, entry -> runCatching { entry.label() }.getOrNull()?.let { left[index] = it } }
        fun say() = runCatching { onWaiting(left.entries.sortedBy { it.key }.map { it.value }) }
        say()
        kotlinx.coroutines.withTimeoutOrNull(deadlineMs) {
            kotlinx.coroutines.coroutineScope {
                entries.forEachIndexed { index, entry ->
                    launch {
                        try {
                            entry.hook()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            com.tmplayer.platform.Logger.w("BeforeSwitch", "a hook before a profile switch failed", e)
                        } finally {
                            left.remove(index)
                            say()
                        }
                    }
                }
            }
        } ?: com.tmplayer.platform.Logger.w("BeforeSwitch", "some work before a profile switch did not stop in time; its writes are dropped")
    }

    /** The longest a switch waits for everything before it, together. */
    const val DEADLINE_MS = 30_000L

    /** How many are registered: players and long jobs. */
    val count: Int get() = hooks.size

    private val players = java.util.concurrent.atomic.AtomicInteger(0)

    /** A player's hook: it also counts as something playing, which a switch asks about first. */
    fun addPlayer(hook: suspend () -> Unit, label: () -> String? = { null }) {
        add(hook, label)
        players.incrementAndGet()
    }

    fun removePlayer(hook: suspend () -> Unit) {
        if (remove(hook)) players.decrementAndGet()
    }

    /** How many players are open now (a long job's hook is not one). */
    val playerCount: Int get() = players.get()
}

/**
 * A switch waiting for the open profile's work to stop, and the viewer's Cancel (HX88): exactly
 * one of them wins. [cancel] true means the switch will not go on (and what it paused may go on
 * again); [commit] true means the switch goes on and a later Cancel does nothing.
 */
class SwitchWait {
    private val phase = java.util.concurrent.atomic.AtomicInteger(IDLE)

    fun begin() = phase.set(WAITING)

    /** The viewer's Cancel: true when it came in time and the switch is called off. */
    fun cancel(): Boolean = phase.compareAndSet(WAITING, CANCELLED)

    /** The wait is over: true when the switch may go on, false when a Cancel came first. */
    fun commit(): Boolean = phase.compareAndSet(WAITING, COMMITTED)

    val waiting: Boolean get() = phase.get() == WAITING

    private companion object {
        const val IDLE = 0
        const val WAITING = 1
        const val CANCELLED = 2
        const val COMMITTED = 3
    }
}
