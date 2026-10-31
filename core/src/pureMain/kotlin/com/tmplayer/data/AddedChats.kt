package com.tmplayer.data

import com.tmplayer.platform.Background
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A chat the viewer added to TMPlayer, with the name it had then. The name is kept so a chat that
 * has since been left, deleted or banned still has a row to show with a way to remove it, which a
 * bare id could not draw.
 */
data class AddedChat(val id: Long, val title: String)

/**
 * How much of the account TMPlayer draws from (HX27).
 *
 * [Unanswered] is every install that existed before the picker: nobody's library shrinks on an
 * update, so until the viewer answers they see what they see today. [All] is the same view, chosen
 * on purpose ("Keep all my chats"), which is what tells the one-time prompt (HX28) not to ask
 * again. [Picked] honours the stored set, and an empty [Picked] is a real answer: a viewer who
 * added nothing gets an empty Home with a card that opens the picker, not every chat back.
 */
enum class AddedMode { Unanswered, All, Picked }

/** The whole stored answer: the mode and, under [AddedMode.Picked], the chats. */
data class AddedChoice(
    val mode: AddedMode = AddedMode.Unanswered,
    val chats: List<AddedChat> = emptyList(),
) {
    /**
     * The gate's shape: the added ids, or null for "every chat", the same null [KidsLock] uses so
     * one predicate answers both. Null under [AddedMode.Unanswered] and [AddedMode.All].
     */
    val ids: Set<Long>? get() = if (mode == AddedMode.Picked) chats.mapTo(LinkedHashSet()) { it.id } else null

    /** Whether the viewer has answered the picker at all, for the one-time prompt (HX28). */
    val answered: Boolean get() = mode != AddedMode.Unanswered
}

/** Where the added chats are kept: `SettingsStore` in the apps, `SettingsBook` in the browser. */
interface AddedChatsSource {
    val addedChats: Flow<AddedChoice>
}

/**
 * The chats the viewer added (HX27): Chats, Home and its rows, the carousel, Recently added,
 * search, the copies of a video, the TV launcher's rows and the background checks for new videos
 * and episodes all read these alone.
 *
 * What it does NOT reach, on purpose: Continue watching, Watched, Watch later, History and
 * Downloads, which are the viewer's own record and keep a video they started in a chat they later
 * removed, shown without a chat link; and one chat the viewer asked for by name, a t.me link they
 * opened or a notification they tapped. Nothing here touches Telegram: a chat left out is still
 * there, read state and all.
 *
 * Stored as one text setting ([KEY]), a line per chat, with the mode beside it ([MODE_KEY]), so
 * the choice travels in a backup like any other. Process wide, as [HiddenChats] and [ChatGate]
 * are, so every list reads the same answer without a store of its own.
 */
object AddedChats {
    const val KEY = "added_chats"
    const val MODE_KEY = "added_chats_mode"

    private val state = MutableStateFlow(AddedChoice())
    private val idState = MutableStateFlow<Set<Long>?>(null)
    private val ready = MutableStateFlow(false)

    @Volatile
    private var following = false

    /** The stored answer, [AddedMode.Unanswered] until a store is read. */
    val choice: StateFlow<AddedChoice> = state.asStateFlow()

    /** The added ids, or null while every chat is shown. Intersect with the kids lock, never alone. */
    val ids: StateFlow<Set<Long>?> = idState.asStateFlow()

    /** Keeps [choice] in step with [store] for the life of the process. */
    fun follow(store: AddedChatsSource) {
        if (following) return
        following = true
        Background.scope.launch {
            store.addedChats.collect(::set)
        }
    }

    /** Sets the answer directly. For tests, and for a process with no store to follow. */
    fun set(answer: AddedChoice) {
        state.value = answer
        idState.value = answer.ids
        ready.value = true
    }

    /** Waits, briefly, for the stored answer to be read, then gives its ids. Mirrors [ChatGate.awaitReady]. */
    suspend fun awaitReady(): Set<Long>? {
        if (following && !ready.value) withTimeoutOrNull(READY_WAIT_MS) { ready.first { it } }
        return state.value.ids
    }

    /**
     * The one gate every list asks: the chats both the kids lock and the viewer's choice let
     * through, or null when neither narrows anything. A null from either side means "all of them",
     * so the answer is the other one, and two sets meet in the middle.
     */
    fun gate(kids: Set<Long>?, added: Set<Long>?): Set<Long>? = when {
        kids == null -> added
        added == null -> kids
        // The smaller side drives the walk, so 20 added chats are not matched against 3,000 allowed ones.
        kids.size <= added.size -> kids.filterTo(LinkedHashSet()) { it in added }
        else -> added.filterTo(LinkedHashSet()) { it in kids }
    }

    /** Whether [chatId] is one of the added chats ([added] null: every chat is). */
    fun shows(added: Set<Long>?, chatId: Long): Boolean = added == null || chatId in added

    /** The stored text back to its chats, in the order they were added; lines that do not parse are skipped. */
    fun decode(raw: String?): List<AddedChat> =
        raw.orEmpty().lineSequence().mapNotNull { line ->
            val tab = line.indexOf('\t')
            val id = (if (tab < 0) line else line.substring(0, tab)).trim().toLongOrNull() ?: return@mapNotNull null
            AddedChat(id, if (tab < 0) "" else line.substring(tab + 1))
        }.distinctBy { it.id }.toList()

    fun encode(chats: List<AddedChat>): String =
        chats.distinctBy { it.id }.joinToString("\n") { "${it.id}\t${it.title.replace(LINE_BREAKS, " ").trim()}" }

    /** The stored mode, by its text. Anything unknown, and nothing stored, reads as unanswered. */
    fun modeOf(raw: String?): AddedMode = when (raw) {
        MODE_ALL -> AddedMode.All
        MODE_PICKED -> AddedMode.Picked
        else -> AddedMode.Unanswered
    }

    /** The text a mode is stored as, or null for unanswered, which is the key being absent. */
    fun textOf(mode: AddedMode): String? = when (mode) {
        AddedMode.Unanswered -> null
        AddedMode.All -> MODE_ALL
        AddedMode.Picked -> MODE_PICKED
    }

    /** [chats] with [chat] added at the end, or its name brought up to date if it is there already. */
    fun with(chats: List<AddedChat>, chat: AddedChat): List<AddedChat> =
        if (chats.any { it.id == chat.id }) chats.map { if (it.id == chat.id) chat else it } else chats + chat

    /** [chats] without [chatId]. */
    fun without(chats: List<AddedChat>, chatId: Long): List<AddedChat> = chats.filter { it.id != chatId }

    /** [items] from the added chats only, in their order ([added] null: all of them). */
    fun <T> filter(items: List<T>, added: Set<Long>? = ids.value, chatOf: (T) -> Long): List<T> =
        if (added == null) items else items.filter { chatOf(it) in added }

    /** Every chat, chosen on purpose. */
    const val MODE_ALL = "all"

    /** The stored set is the answer, empty or not. */
    const val MODE_PICKED = "picked"

    private const val READY_WAIT_MS = 2_000L
    private val LINE_BREAKS = Regex("[\\t\\r\\n]+")
}
