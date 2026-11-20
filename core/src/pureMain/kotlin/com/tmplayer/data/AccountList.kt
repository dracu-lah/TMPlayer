package com.tmplayer.data

import com.tmplayer.json.JSONArray
import com.tmplayer.json.JSONObject
import kotlin.random.Random

/**
 * One Telegram account on this device (HX88). See docs/agents/multi-account.md.
 *
 * [folder] names the profile's own folders, beside the platform's ([AccountDirs.beside]); null is
 * the platform's folders themselves, which is the login adopted in place when this version first ran.
 */
data class Profile(
    val id: String,
    val folder: String?,
    /** Telegram's user id once the profile has signed in, 0 before. */
    val telegramId: Long = 0L,
    val name: String = "",
    /** The small photo kept for the picker, or null for the initials. */
    val photo: String? = null,
    /** Milliseconds since the epoch. */
    val createdAt: Long = 0L,
    /** Made by Add account and not yet signed in: removed if the app stops before it is. */
    val adding: Boolean = false,
    /** The profile's PIN as [PinHash] stores it (iterations, salt and hash), or null for none. */
    val pin: String? = null,
    val pinFailures: Int = 0,
    /** The wait left after wrong PINs, and the [PinClock] time it was written at. */
    val pinWaitLeftMs: Long = 0L,
    val pinWaitStamp: Long = 0L,
    /** The name the viewer gave the profile on this device (Rename), or null for Telegram's [name]. */
    val label: String? = null,
) {
    val adopted: Boolean get() = folder == null
    val signedIn: Boolean get() = telegramId != 0L

    /** The name the picker shows: the viewer's own, else Telegram's, else "" (the picker numbers it). */
    val shownName: String get() = label?.takeIf { it.isNotBlank() } ?: name
}

/**
 * Every profile, the one open now ([activeId]), and while a switch is under way the one it goes to
 * ([switchingTo]). [activeId] only moves once the last client has closed, so a process killed part
 * way reopens [activeId], the profile that was really open. [trash] is the folders of
 * removed profiles still to be deleted ("" for the platform's own), swept once TDLib's folders
 * are known.
 */
data class AccountList(
    val profiles: List<Profile>,
    val activeId: String,
    val switchingTo: String? = null,
    val trash: List<String> = emptyList(),
) {
    val active: Profile? get() = byId(activeId) ?: profiles.firstOrNull()

    fun byId(id: String): Profile? = profiles.firstOrNull { it.id == id }

    fun replace(profile: Profile): AccountList =
        copy(profiles = profiles.map { if (it.id == profile.id) profile else it })
}

/**
 * One profile's turn: its folder and how many switches came before it (`Accounts.stamp`). What
 * keeps one profile's data is tagged with it and dropped once another profile is open.
 */
data class AccountStamp(val generation: Long, val folder: String?)

/** The rules of the profile list, apart from any disk. */
object AccountRules {
    /** At most this many profiles on one device (user, 2026-11-20). */
    const val MAX = 3

    /** The adopted profile's id. */
    const val ADOPTED_ID = "main"

    private const val FOLDER_LENGTH = 6
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    private val NAME = Regex("[a-z0-9]{1,16}")

    /** The list a device starts with: its one login, on the platform's own folders. */
    fun adopted(now: Long): AccountList =
        AccountList(listOf(Profile(id = ADOPTED_ID, folder = null, createdAt = now)), ADOPTED_ID)

    /** Safe to put in a folder name: never a path, a dot or anything a file system minds. */
    fun validName(name: String): Boolean = NAME.matches(name)

    fun canAdd(list: AccountList): Boolean = list.profiles.size < MAX

    /** The most a profile's own name (Rename) keeps. */
    const val LABEL_MAX = 32

    /** A name the viewer typed, trimmed, without control characters, at most [LABEL_MAX]; null when nothing is left. */
    fun cleanLabel(raw: String): String? =
        raw.filter { !it.isISOControl() }.trim().take(LABEL_MAX).trim().takeIf { it.isNotEmpty() }

    /** A folder name no profile and no folder still in the trash has. */
    fun newFolder(list: AccountList, random: Random = Random.Default): String {
        val taken = list.profiles.mapNotNull { it.folder }.toSet() + list.trash + list.profiles.map { it.id }
        while (true) {
            val name = "p" + (1..FOLDER_LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
            if (name !in taken) return name
        }
    }

    /**
     * The open profile's share of the one cache [ceiling] (HX88): what the sleeping profiles'
     * caches ([sleeping] bytes) leave, and never under a quarter of it, so three profiles never
     * triple the cache and the open one can still play.
     */
    fun sharedCeiling(ceiling: Long, sleeping: Long): Long =
        (ceiling - sleeping.coerceAtLeast(0L)).coerceAtLeast(ceiling / 4)

    /** Another profile already signed in as [telegramId], or null. */
    fun sameTelegram(list: AccountList, telegramId: Long, exceptId: String): Profile? =
        if (telegramId == 0L) null else list.profiles.firstOrNull { it.id != exceptId && it.telegramId == telegramId }

    /** [profile] taken off the list, its folder put in the trash; the active one moves to [nextId]. */
    fun removed(list: AccountList, profile: Profile, nextId: String = list.activeId): AccountList {
        val left = list.profiles.filter { it.id != profile.id }
        val next = nextId.takeIf { id -> left.any { it.id == id } } ?: left.firstOrNull()?.id ?: list.activeId
        return list.copy(
            profiles = left,
            activeId = next,
            trash = (list.trash + (profile.folder ?: "")).distinct(),
        )
    }

    /**
     * What a start finds after a process that stopped part way: a switch under way reopens the
     * profile that was open ([AccountList.activeId] had not moved yet), and a profile still being added goes with its folder unless it is the only
     * one. Unchanged when there is nothing to do.
     */
    fun recovered(list: AccountList): AccountList {
        var out = list
        if (out.switchingTo != null) out = out.copy(switchingTo = null)
        for (half in out.profiles.filter { it.adding }) {
            if (out.profiles.size <= 1) break
            out = removed(out, half, out.activeId.takeIf { it != half.id } ?: out.profiles.first { it.id != half.id }.id)
        }
        if (out.byId(out.activeId) == null) out.profiles.firstOrNull()?.let { out = out.copy(activeId = it.id) }
        return out
    }
}

/**
 * The profile list as a small JSON file. The read takes what it can: an entry that does not parse,
 * an id or folder that is not a plain name, a second entry with the same id or folder, anything past
 * [AccountRules.MAX], all skipped; null when nothing usable is left, and the caller starts again from
 * [AccountRules.adopted].
 */
object AccountCodec {
    private const val VERSION = 1
    private const val NAME_MAX = 128
    private const val PATH_MAX = 1024
    private const val PIN_MAX = 256

    fun encode(list: AccountList): String = JSONObject()
        .put("v", VERSION)
        .put("active", list.activeId)
        .put("switchingTo", list.switchingTo ?: JSONObject.NULL)
        .put("trash", JSONArray(list.trash))
        .put("profiles", JSONArray(list.profiles.map { p ->
            JSONObject()
                .put("id", p.id)
                .put("folder", p.folder ?: JSONObject.NULL)
                .put("telegramId", p.telegramId)
                .put("name", p.name)
                .put("photo", p.photo ?: JSONObject.NULL)
                .put("createdAt", p.createdAt)
                .put("adding", p.adding)
                .put("pin", p.pin ?: JSONObject.NULL)
                .put("pinFailures", p.pinFailures)
                .put("pinWaitLeftMs", p.pinWaitLeftMs)
                .put("pinWaitStamp", p.pinWaitStamp)
                .put("label", p.label ?: JSONObject.NULL)
        }))
        .toString()

    fun decode(text: String): AccountList? {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (json.optInt("v", -1) != VERSION) return null
        val entries = json.optJSONArray("profiles") ?: return null
        val profiles = ArrayList<Profile>()
        for (i in 0 until entries.length()) {
            if (profiles.size >= AccountRules.MAX) break
            val p = runCatching { entries.optJSONObject(i) }.getOrNull() ?: continue
            val profile = runCatching { profileOf(p) }.getOrNull() ?: continue
            if (profiles.any { it.id == profile.id || (profile.folder != null && it.folder == profile.folder) }) continue
            if (profile.folder == null && profiles.any { it.folder == null }) continue
            profiles += profile
        }
        if (profiles.isEmpty()) return null
        val active = text(json, "active")?.takeIf { id -> profiles.any { it.id == id } } ?: profiles.first().id
        val to = text(json, "switchingTo")?.takeIf { id -> profiles.any { it.id == id } }
        val trashed = json.optJSONArray("trash")
        val trash = (0 until (trashed?.length() ?: 0)).mapNotNull { i ->
            (runCatching { trashed?.opt(i) }.getOrNull() as? String)?.takeIf { it.isEmpty() || AccountRules.validName(it) }
        }.filter { name -> profiles.none { (it.folder ?: "") == name } }.distinct().take(AccountRules.MAX * 4)
        return AccountList(profiles, active, to, trash)
    }

    private fun profileOf(p: JSONObject): Profile? {
        val id = text(p, "id")?.takeIf(AccountRules::validName) ?: return null
        val folder = text(p, "folder")
        if (folder != null && !AccountRules.validName(folder)) return null
        return Profile(
            id = id,
            folder = folder,
            telegramId = p.optLong("telegramId", 0L).takeIf { it > 0L } ?: 0L,
            name = text(p, "name").orEmpty().take(NAME_MAX),
            photo = text(p, "photo")?.takeIf { it.isNotBlank() && it.length <= PATH_MAX },
            createdAt = p.optLong("createdAt", 0L).coerceAtLeast(0L),
            adding = p.optBoolean("adding", false),
            pin = text(p, "pin")?.takeIf { it.isNotBlank() && it.length <= PIN_MAX },
            pinFailures = p.optInt("pinFailures", 0).coerceIn(0, 10_000),
            pinWaitLeftMs = p.optLong("pinWaitLeftMs", 0L).coerceIn(0L, ProfilePin.WAIT_MS),
            pinWaitStamp = p.optLong("pinWaitStamp", 0L),
            label = text(p, "label")?.let(AccountRules::cleanLabel),
        )
    }

    /** A string field, or null when it is missing, null or another type. */
    private fun text(json: JSONObject, key: String): String? = json.opt(key) as? String
}

/**
 * A profile's own PIN (optional, 4 digits, off by default): five wrong in a row and the pad waits
 * 30 seconds, then five more. Forgetting it means signing that profile out.
 */
object ProfilePin {
    const val TRIES = 5
    const val WAIT_MS = 30_000L

    /** [profile] with [pin] set, or cleared for null. False when [pin] is not 4 digits. */
    fun set(profile: Profile, pin: String?): Profile? {
        if (pin == null) return profile.copy(pin = null, pinFailures = 0, pinWaitLeftMs = 0L, pinWaitStamp = 0L)
        if (!PinHash.isValid(pin)) return null
        return profile.copy(pin = PinHash.hash(pin), pinFailures = 0, pinWaitLeftMs = 0L, pinWaitStamp = 0L)
    }

    /** The wait left at [now] ([PinClock] time) from wrong PINs before, 0 when a PIN may be tried. */
    fun waitLeft(profile: Profile, now: Long): Long {
        if (profile.pin == null) return 0L
        val served = if (now >= profile.pinWaitStamp) now - profile.pinWaitStamp else 0L
        return (profile.pinWaitLeftMs.coerceIn(0L, WAIT_MS) - served).coerceAtLeast(0L)
    }

    /** How many wrong PINs are left before the next wait, after [failures] in a row. */
    fun triesLeft(failures: Int): Int = TRIES - (failures.coerceAtLeast(0) % TRIES)

    /** What the pad hears back for [pin] at [now] ([PinClock] time), and the profile to store. */
    fun check(profile: Profile, pin: String, now: Long): Pair<Profile, PinCheck> {
        val stored = profile.pin ?: return profile to PinCheck.NoPin
        val served = if (now >= profile.pinWaitStamp) now - profile.pinWaitStamp else 0L
        val left = (profile.pinWaitLeftMs.coerceIn(0L, WAIT_MS) - served).coerceAtLeast(0L)
        // Stamped again with what is left, as PinAttempts.restored does: after a reboot the clock
        // starts below the old stamp, and the wait then runs out from now instead of lasting until
        // the uptime passes the stamp again. It is never longer than WAIT_MS either way.
        if (left > 0) return profile.copy(pinWaitLeftMs = left, pinWaitStamp = now) to PinCheck.Waiting(left)
        if (PinHash.verify(pin, stored)) {
            return profile.copy(pinFailures = 0, pinWaitLeftMs = 0L, pinWaitStamp = 0L) to PinCheck.Right
        }
        val count = profile.pinFailures + 1
        val wait = if (count % TRIES == 0) WAIT_MS else 0L
        val next = profile.copy(pinFailures = count, pinWaitLeftMs = wait, pinWaitStamp = now)
        return next to PinCheck.Wrong(PinAttempts(count, if (wait > 0) now + wait else 0L))
    }
}
