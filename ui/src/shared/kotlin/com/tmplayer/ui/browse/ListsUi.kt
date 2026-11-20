package com.tmplayer.ui.browse

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import com.tmplayer.data.ListAdd
import com.tmplayer.data.ListsStore
import com.tmplayer.data.NamedListRules
import com.tmplayer.data.VisibleList
import com.tmplayer.data.WatchLaterRecord
import com.tmplayer.i18n.Messages
import com.tmplayer.platform.Background
import com.tmplayer.ui.components.TmAlertDialog
import com.tmplayer.ui.components.dpadLeavesFieldOnTv
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// The viewer's named lists (HX43) on every Compose platform: the picker a video's or a show's menu
// opens ("Add to a list"), the name dialog, and the delete confirmation. Each platform hosts them
// once ([ListPickerHost]) and its menus ask through [LocalListPicker].

/** Opens "Add to a list" for a video's or a show's line; null where no host is above (a preview, a test). */
val LocalListPicker = staticCompositionLocalOf<((WatchLaterRecord) -> Unit)?> { null }

/** Opens a list's page by its id; null where the platform has no page above. */
val LocalListOpener = staticCompositionLocalOf<((String) -> Unit)?> { null }

/**
 * Holds the list picker for everything under it: a menu line calls [LocalListPicker] with the line
 * it would add, and the picker opens over whatever is on screen, so it outlives the menu that asked.
 */
@Composable
fun ListPickerHost(
    lists: ListsStore,
    onMessage: (String) -> Unit,
    onOpenList: ((String) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var picking by remember { mutableStateOf<WatchLaterRecord?>(null) }
    val pick = remember { { record: WatchLaterRecord -> picking = record } }
    CompositionLocalProvider(LocalListPicker provides pick, LocalListOpener provides onOpenList) {
        content()
    }
    picking?.let { record -> ListPickerDialog(record, lists, onMessage, onDismiss = { picking = null }) }
}

/**
 * "Add to a list" for [record]: every list, ticked where the line is already in it (picking a
 * ticked one takes it out), then New list, which asks for a name and puts the line in the new
 * list. A list of a locked chat's videos still shows by name; its counts leave them out.
 */
@Composable
fun ListPickerDialog(
    record: WatchLaterRecord,
    lists: ListsStore,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    val all by remember(lists) { lists.visible }.collectAsState(initial = null)
    val say by rememberUpdatedState(onMessage)
    val shown = all ?: return
    val actions = remember(lists, s, record) { ListPickerActions(lists, s, record) { line -> say(line) } }
    ListPickerMenu(
        record = record,
        lists = shown,
        onPick = { list, _ -> actions.toggle(list) },
        onNew = { name -> actions.create(name, shown.size) },
        onDismiss = onDismiss,
    )
}

/**
 * The two writes of "Add to a list" for [record], shared by the card menu's picker and the
 * player's More options page so both say the same lines: [pick] puts the line in a list, or takes
 * it out of one that has it (no confirm: nothing is lost, the line goes back with one more press),
 * [create] makes a list of the name and puts the line in it.
 */
class ListPickerActions(
    private val lists: ListsStore,
    private val s: Messages,
    private val record: WatchLaterRecord,
    private val say: (String) -> Unit,
) {
    /** [inIt]: the line is in [list] now, so this press takes it out. */
    fun pick(list: VisibleList, inIt: Boolean) {
        listWrite(say, s) {
            if (inIt) {
                lists.remove(list.info.id, setOf(record.id))
                s.listsRemoved(record.title, list.info.name)
            } else {
                addedLine(s, record, list.info.name, lists.add(list.info.id, record))
            }
        }
    }

    private val writing = kotlinx.coroutines.sync.Mutex()

    /**
     * A press that decides add or remove from the lists as they are when it runs, not as the screen
     * last drew them: two quick presses before the screen catches up add, then remove, instead of
     * adding twice. Writes of one picker take turns.
     */
    fun toggle(list: VisibleList) {
        listWrite(say, s) {
            writing.withLock {
                val now = lists.now().entries.any { it.listId == list.info.id && it.record.id == record.id }
                if (now) {
                    lists.remove(list.info.id, setOf(record.id))
                    s.listsRemoved(record.title, list.info.name)
                } else {
                    addedLine(s, record, list.info.name, lists.add(list.info.id, record))
                }
            }
        }
    }

    /** [known]: how many lists the picker showed, to tell a full house from an empty name. */
    fun create(name: String, known: Int) {
        listWrite(say, s) {
            val made = lists.create(name)
            when {
                made != null -> addedLine(s, record, made.name, lists.add(made.id, record))
                known >= NamedListRules.MAX_LISTS -> s.listsTooMany(NamedListRules.MAX_LISTS)
                else -> s.listsNameEmpty
            }
        }
    }
}

/**
 * [ListPickerDialog]'s card over [lists] as they are: [onPick] gets the list picked and whether the
 * line was in it (so it comes out), [onNew] the name typed for a new list. Closes itself on either.
 */
@Composable
fun ListPickerMenu(
    record: WatchLaterRecord,
    lists: List<VisibleList>,
    onPick: (VisibleList, Boolean) -> Unit,
    onNew: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    var naming by remember { mutableStateOf(false) }
    if (naming) {
        ListNameDialog(
            title = s.listsNew,
            initial = "",
            confirm = s.listsCreate,
            onSave = { name -> onDismiss(); onNew(name) },
            onDismiss = onDismiss,
        )
        return
    }
    val lines = buildList {
        lists.forEach { list ->
            val inIt = record.id in list.ids
            add(
                MenuLine(list.info.name, if (inIt) TmIcons.CheckCircle else TmIcons.List, if (inIt) s.listsRemove else s.browseVideosCount(list.records.size)) {
                    onDismiss()
                    onPick(list, inIt)
                },
            )
        }
        if (lists.size < NamedListRules.MAX_LISTS) add(MenuLine(s.listsNew, TmIcons.Add, s.listsNameHint) { naming = true })
    }
    MenuCard(
        title = s.listsAddTo,
        subtitle = record.title,
        lines = lines,
        empty = if (lists.isEmpty()) s.listsNone else null,
        onDismiss = onDismiss,
        cancel = s.commonCancel,
    )
}

private fun addedLine(s: Messages, record: WatchLaterRecord, list: String, result: ListAdd): String = when (result) {
    ListAdd.Added -> s.listsAdded(record.title, list)
    ListAdd.Already -> s.listsAlready(record.title, list)
    ListAdd.Full -> s.listsFull(list)
    ListAdd.NoList -> s.listsGone
}

/** Runs a list write off the screen's scope (a menu closes as it is picked) and says how it went. */
internal fun listWrite(say: (String) -> Unit, s: Messages, write: suspend () -> String) {
    // As the profile open when it was asked (HX88): dropped, never written into the next one.
    Background.scope.launch(com.tmplayer.data.AccountOwner(com.tmplayer.data.Accounts.stamp)) {
        val line = try {
            write()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            s.errorsScreenFailedTitle
        }
        // A write with nothing to say (a rename, the Home switch) says nothing.
        if (line.isNotEmpty()) withContext(Dispatchers.Main) { say(line) }
    }
}

/**
 * A list's name, new or renamed: one field, cut to [NamedListRules.NAME_MAX] as it is typed, Save
 * (or the keyboard's Done) only once something is in it. Two lists may share a name.
 */
@Composable
fun ListNameDialog(title: String, initial: String, confirm: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(10) {
            if (runCatching { focus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            delay(50)
        }
    }
    val clean = NamedListRules.cleanName(text)
    val save = { clean?.let(onSave) ?: Unit }
    TmAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 2) },
        text = {
            Column {
                ListNameField(text, onChange = { text = it }, onDone = { save() }, modifier = Modifier.focusRequester(focus))
            }
        },
        confirmButton = { TextButton(onClick = { save() }, enabled = clean != null) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.commonCancel) } },
    )
}

/** The name box of [ListNameDialog]; a television's remote leaves it with the arrows. */
@Composable
fun ListNameField(text: String, onChange: (String) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    OutlinedTextField(
        value = text,
        // A paste of a whole page stops at the cap, rather than filling the field.
        onValueChange = { onChange(it.take(NamedListRules.NAME_MAX)) },
        singleLine = true,
        label = { Text(s.listsName) },
        supportingText = { Text(s.listsNameHint, style = MaterialTheme.typography.bodySmall) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier.fillMaxWidth().dpadLeavesFieldOnTv().testTag(LIST_NAME_TAG),
    )
}

/** Asks before a list goes: its name, how many videos it holds, and that the videos stay in their chats. */
@Composable
fun ListDeleteDialog(list: VisibleList, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    TmAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.listsDeleteTitle(list.info.name), maxLines = 2) },
        text = { Text(s.listsDeleteMessage(list.records.size)) },
        confirmButton = { TextButton(onClick = { onDismiss(); onDelete() }) { Text(s.commonDelete) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.commonCancel) } },
    )
}

/**
 * The writes a list's page offers, shared so the phone, the television and the desktop say the
 * same lines: rename, on Home or off it, delete, and Remove from list for a card's lines.
 */
class ListPageActions(private val lists: ListsStore, private val s: Messages, private val say: (String) -> Unit) {
    fun rename(id: String, name: String) = listWrite(say, s) { lists.rename(id, name); "" }
    fun setHome(id: String, on: Boolean) = listWrite(say, s) { lists.setHome(id, on); "" }
    fun delete(list: VisibleList) = listWrite(say, s) { lists.delete(list.info.id); s.listsDeleted(list.info.name) }
    fun remove(list: VisibleList, title: String, ids: Set<String>) =
        listWrite(say, s) { lists.remove(list.info.id, ids); s.listsRemoved(title, list.info.name) }
}

@Composable
fun rememberListPageActions(lists: ListsStore, onMessage: (String) -> Unit): ListPageActions {
    val s = LocalStrings.current
    val say by rememberUpdatedState(onMessage)
    // A write that has nothing to say (a rename, the Home switch) says nothing.
    return remember(lists, s) { ListPageActions(lists, s) { line -> if (line.isNotEmpty()) say(line) } }
}

const val LIST_NAME_TAG = "list-name"
