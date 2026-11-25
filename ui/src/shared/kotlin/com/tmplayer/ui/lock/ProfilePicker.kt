package com.tmplayer.ui.lock

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.data.AccountSwitcher
import com.tmplayer.data.DeviceForm
import com.tmplayer.data.NameInitial
import com.tmplayer.data.PickerModel
import com.tmplayer.data.PinAttempts
import com.tmplayer.data.PinCheck
import com.tmplayer.data.PinClock
import com.tmplayer.data.ProfilePin
import com.tmplayer.data.ProfileState
import com.tmplayer.data.ProfileTile
import com.tmplayer.data.Thumbnails
import com.tmplayer.data.TileStatus
import com.tmplayer.i18n.L
import com.tmplayer.ui.components.DrawnLetter
import com.tmplayer.ui.components.NameArt
import com.tmplayer.ui.components.NameArtTile
import com.tmplayer.ui.components.deviceForm
import com.tmplayer.ui.components.liveMessage
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.collectIsFocusVisibleAsState
import com.tmplayer.ui.theme.focusFillOver
import com.tmplayer.ui.theme.focusRing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Who's watching?" (HX88): the profiles of this device as a row of round pictures with their
 * names, then Add account while there is room, on every device the same (phone, tablet, TV,
 * desktop, iOS). The platform's shell decides when it shows (at launch with two profiles or more,
 * and from the profile menu) and what each press does; this draws the row and hears the presses.
 *
 * Touch: a tap opens, a long press opens the options. Remote: the first tile has focus, Left and
 * Right move along the row, OK opens, a long OK (or the Menu key) opens the options, Down goes to
 * Add account and on to Manage profiles. In Manage profiles every press opens the options.
 * [focusOn] puts the focus back on a tile once a dialog over the picker closes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WhoIsWatching(
    model: PickerModel,
    onOpen: (ProfileTile) -> Unit,
    onAdd: () -> Unit,
    onOptions: (ProfileTile) -> Unit,
    modifier: Modifier = Modifier,
    /** A line under the row while a switch runs ("Opening Asha", or the slow close's line), or null. */
    status: String? = null,
    /** While the switch waits for the open profile's work to stop: Cancel stays on that profile. */
    onCancelStatus: (() -> Unit)? = null,
    /** The tile to give the focus to, asked again each time [focusAsk] changes. */
    focusOn: String? = null,
    focusAsk: Int = 0,
    /** Manage profiles: every press opens the options. Held by the caller, so a panel over the row keeps it. */
    manage: Boolean = false,
    onManageChange: (Boolean) -> Unit = {},
) {
    val s = LocalStrings.current
    val form = deviceForm()
    val tv = form == DeviceForm.Tv
    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requester(id: String) = requesters.getOrPut(id) { FocusRequester() }
    val addFocus = remember { FocusRequester() }
    val manageFocus = remember { FocusRequester() }
    val busy = status != null

    // A remote needs something under it from the first frame: the first tile, or the one a closed
    // dialog came from. A touch screen draws no ring, so nothing is focused there.
    // Again once the row takes presses (busy over): a tile cannot hold focus while it is disabled.
    LaunchedEffect(focusOn, focusAsk, model.tiles.size, busy) {
        if (!tv && focusOn == null) return@LaunchedEffect
        if (busy) return@LaunchedEffect
        withFrameNanos { }
        val target = focusOn?.takeIf { id -> model.tiles.any { it.id == id } } ?: model.tiles.firstOrNull()?.id
        runCatching { if (target != null) requester(target).requestFocus() else addFocus.requestFocus() }
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Tone.background),
        contentAlignment = Alignment.Center,
    ) {
        // One size for every tile, from the room there is: four in a row on a TV and a tablet,
        // two to a row on a narrow phone, never so small a name cannot fit.
        val count = model.tiles.size + if (model.canAdd) 1 else 0
        val gap = if (tv) 32.dp else 20.dp
        val widest = if (tv) 132.dp else if (form == DeviceForm.Desktop) 120.dp else 104.dp
        val fit = ((maxWidth - 48.dp - gap * (count - 1).coerceAtLeast(0)) / count.coerceAtLeast(1))
        val avatar = fit.coerceIn(72.dp, widest)
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = if (tv) 48.dp else 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (manage) s.profilesManage else s.profilesWhoIsWatching,
                style = if (tv) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineMedium,
                color = Tone.text,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            if (manage) {
                Text(
                    s.profilesManageHint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tone.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(if (tv) 40.dp else 28.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                for (tile in model.tiles) {
                    ProfileTileView(
                        tile = tile,
                        size = avatar,
                        manage = manage,
                        enabled = !busy,
                        modifier = Modifier
                            .focusRequester(requester(tile.id))
                            .focusProperties { down = if (model.canAdd) addFocus else manageFocus },
                        onOpen = { if (manage) onOptions(tile) else onOpen(tile) },
                        onOptions = { onOptions(tile) },
                    )
                }
                if (model.canAdd) {
                    AddTile(
                        size = avatar,
                        enabled = !busy,
                        modifier = Modifier
                            .focusRequester(addFocus)
                            .focusProperties { down = manageFocus },
                        onClick = onAdd,
                    )
                }
            }
            if (model.atLimit) {
                Text(
                    s.profilesLimit(count = com.tmplayer.data.AccountRules.MAX),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tone.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 24.dp).widthIn(max = 520.dp),
                )
            }
            Box(Modifier.heightIn(min = 56.dp).padding(top = 20.dp), contentAlignment = Alignment.Center) {
                if (status != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // Only a picture of the wait: the words are what is spoken, and a change of
                        // them ("Opening Asha", then "Finishing first: ...") is heard as it comes.
                        CircularProgressIndicator(Modifier.size(24.dp).clearAndSetSemantics { }, color = Tone.accent, strokeWidth = 2.dp)
                        Text(
                            status,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Tone.muted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp).widthIn(max = 520.dp).liveMessage(),
                        )
                        if (onCancelStatus != null) {
                            // The remote is put on it: the tiles wait, Cancel is the one thing to press.
                            val cancelFocus = remember { FocusRequester() }
                            LaunchedEffect(Unit) {
                                withFrameNanos { }
                                if (tv) runCatching { cancelFocus.requestFocus() }
                            }
                            ProfileButton(label = s.commonCancel, onClick = onCancelStatus, modifier = Modifier.padding(top = 12.dp).focusRequester(cancelFocus))
                        }
                    }
                }
            }
            ProfileButton(
                label = if (manage) s.commonDone else s.profilesManage,
                icon = if (manage) TmIcons.Check else TmIcons.Edit,
                modifier = Modifier.padding(top = 8.dp).focusRequester(manageFocus),
                onClick = { onManageChange(!manage) },
            )
        }
    }
}

/** One profile: its picture, the lock badge, its name and what is wrong with it, if anything. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProfileTileView(
    tile: ProfileTile,
    size: Dp,
    manage: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onOpen: () -> Unit,
    onOptions: () -> Unit,
) {
    val s = LocalStrings.current
    val name = ProfileWords.name(tile)
    val line = ProfileWords.status(tile)
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val description = listOfNotNull(name, line, if (tile.locked) s.profilesLocked else null, if (tile.active) s.profilesCurrent else null)
        .joinToString(", ")
    Column(
        modifier
            .width((size + 16.dp) * nameRoom())
            .longOk(enabled, onClick = onOpen, onLong = onOptions)
            .combinedClickable(
                interactionSource = interactions,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onLongClickLabel = s.profilesOptions(name = name),
                onLongClick = onOptions,
                onClick = onOpen,
            )
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(size)) {
            ProfileAvatar(
                name = name,
                photo = tile.photo,
                size = size,
                modifier = Modifier
                    .focusRing(focused, CircleShape, filled = false)
                    .then(if (manage) Modifier.border(2.dp, Tone.muted, CircleShape) else Modifier),
            )
            if (tile.locked || manage) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(size / 3.4f)
                        .background(Tone.surface, CircleShape)
                        .border(1.dp, Tone.muted, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (manage) TmIcons.Edit else TmIcons.Lock,
                        contentDescription = null,
                        tint = Tone.text,
                        modifier = Modifier.size(size / 6.5f),
                    )
                }
            }
        }
        Text(
            name,
            style = MaterialTheme.typography.titleMedium,
            color = if (focused) Tone.text else Tone.text.copy(alpha = 0.86f),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp),
        )
        if (line != null) {
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = if (tile.status == TileStatus.Adding) Tone.muted else Tone.caution,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * How much wider than its picture a tile is, from the font: 1 up to 1.3 times, then as much as the
 * font grew. A name is 16 sp, so past 1.3 times the fixed 88 to 120 dp broke "Benedict" into
 * "Benedi" and "ct" and cut "Add account" (phone audit, 2026-11-25); the row then wraps to fewer tiles.
 */
@Composable
private fun nameRoom(): Float = LocalDensity.current.fontScale.let { if (it > 1.3f) it else 1f }

/** The Add account tile, last in the row, a plus in a ring where a picture would be. */
@Composable
private fun AddTile(size: Dp, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val s = LocalStrings.current
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    Column(
        modifier
            .width((size + 16.dp) * nameRoom())
            .longOk(enabled, onClick = onClick, onLong = onClick)
            .combinedClickable(
                interactionSource = interactions,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(size)
                .background(if (focused) Tone.focusFill else Tone.surface, CircleShape)
                .border(2.dp, Tone.muted, CircleShape)
                .focusRing(focused, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(TmIcons.Add, contentDescription = null, tint = if (focused) Tone.onFocusFill else Tone.text, modifier = Modifier.size(size / 2.6f))
        }
        Text(
            s.profilesAdd,
            style = MaterialTheme.typography.titleMedium,
            color = Tone.text,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/**
 * A profile's round picture: its small photo when one is kept, decoded at the size drawn into the
 * thumbnails' shared memory budget (a 1 GB TV never holds a full photo per tile), else its
 * initial on its name's colour ([NameArtTile]).
 */
@Composable
fun ProfileAvatar(
    name: String,
    photo: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    /** The letter's size when it should not scale with [size] (the TV bar's 40 dp picture, 22 sp); unspecified scales it. */
    letterSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
) {
    val px = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(1)
    val picture by produceState<ImageBitmap?>(null, photo, px) {
        if (photo == null) return@produceState
        value = runCatching {
            val stamp = withContext(Dispatchers.IO) { File(photo).takeIf { it.isFile }?.lastModified() } ?: return@runCatching null
            Thumbnails.online("profile:$photo:$stamp", px) { File(photo) }
        }.getOrNull()
    }
    Box(modifier.size(size).clip(CircleShape), contentAlignment = Alignment.Center) {
        val shown = picture
        if (shown != null) {
            Image(shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            NameArtTile(name) {
                DrawnLetter(
                    NameInitial.of(name).orEmpty(),
                    MaterialTheme.typography.headlineMedium.copy(
                        color = NameArt.onLetter,
                        fontSize = if (letterSize != androidx.compose.ui.unit.TextUnit.Unspecified) {
                            letterSize
                        } else {
                            MaterialTheme.typography.headlineMedium.fontSize * (size.value / 96f)
                        },
                    ),
                )
            }
        }
    }
}

/**
 * OK and Enter on a remote or a keyboard: a press opens, held long enough for the key to repeat
 * it opens the options instead ([onLong]), and so does the remote's Menu key. Touch goes through
 * the clickable after it, which also hears TalkBack's actions.
 */
@Composable
private fun Modifier.longOk(enabled: Boolean, onClick: () -> Unit, onLong: () -> Unit): Modifier {
    val hold = remember { KeyHold() }
    return onPreviewKeyEvent { event ->
        val ok = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
        when {
            event.key == Key.Menu -> {
                if (event.type == KeyEventType.KeyUp && enabled) onLong()
                true
            }
            !ok -> false
            event.type == KeyEventType.KeyDown -> {
                if (!hold.down) {
                    hold.down = true
                    hold.fired = false
                } else if (!hold.fired && enabled) {
                    // The key repeats only while it is held: that is the long press.
                    hold.fired = true
                    onLong()
                }
                true
            }
            event.type == KeyEventType.KeyUp -> {
                val wasDown = hold.down
                hold.down = false
                if (wasDown && !hold.fired && enabled) onClick()
                hold.fired = false
                wasDown
            }
            else -> false
        }
    }
}

/** Whether OK is held, and whether its long press already went, across the recompositions focus brings. */
private class KeyHold {
    var down = false
    var fired = false
}

/** A pill button that every device can reach: a remote's focus fills it, a finger taps it. */
@Composable
fun ProfileButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    primary: Boolean = false,
    danger: Boolean = false,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val shape = RoundedCornerShape(Corner.Large)
    // A primary button rests in the accent, which is also the television's focus fill: it keeps the
    // fill and shows focus with the ring instead (focusFillOver), so focused never equals unfocused.
    val rest = if (primary) Tone.accent else Tone.surface
    val fill = if (focused) focusFillOver(rest) else rest
    val ink = when {
        focused -> Tone.onFocusFill
        primary -> Tone.onAccent
        danger -> Tone.danger
        else -> Tone.text
    }
    androidx.compose.foundation.layout.Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(fill, shape)
            .focusRing(focused, shape, rest = rest)
            .combinedClickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = ink, textAlign = TextAlign.Center)
    }
}

/** One choice in a [ProfilePanel]. */
class PanelAction(val label: String, val onClick: () -> Unit, val danger: Boolean = false, val primary: Boolean = false)

/**
 * A panel over the picker or the shell, the same on every device: a title, a few lines, and its
 * choices one under the other, the first focused for a remote. Back closes it ([onDismiss]).
 * Drawn in the window rather than as a dialog of its own, so a remote's focus never leaves it and
 * returns to the tile that opened it when it closes.
 */
@Composable
fun ProfilePanel(
    title: String,
    actions: List<PanelAction>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    body: String? = null,
    header: (@Composable () -> Unit)? = null,
    extra: (@Composable () -> Unit)? = null,
) {
    BackHandler(onBack = onDismiss)
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Box(
        modifier
            .fillMaxSize()
            .background(Tone.background.copy(alpha = 0.94f))
            // A tap outside closes it. Not a clickable: that would be a focus stop for the remote.
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(24.dp)
                .widthIn(max = 460.dp)
                .clip(RoundedCornerShape(Corner.Large))
                .background(Tone.surface)
                // A remote stays in the panel: nothing behind it (the row, the shell) can take focus.
                .focusProperties { onExit = { cancelFocusChange() } }
                // A panel with nothing to press (a wait) holds the remote itself.
                .then(if (actions.isEmpty()) Modifier.focusRequester(first).focusable() else Modifier)
                .focusGroup()
                // Taps inside stay inside.
                .pointerInput(Unit) { detectTapGestures { } }
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            header?.invoke()
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = Tone.text,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            if (body != null) {
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tone.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            extra?.invoke()
            Spacer(Modifier.height(20.dp))
            actions.forEachIndexed { index, action ->
                ProfileButton(
                    label = action.label,
                    onClick = action.onClick,
                    primary = action.primary,
                    danger = action.danger,
                    modifier = Modifier
                        .padding(top = if (index == 0) 0.dp else 10.dp)
                        .fillMaxWidth()
                        .then(if (index == 0) Modifier.focusRequester(first) else Modifier),
                )
            }
        }
    }
}

/** A profile's options: rename, its PIN, sign out, or for a broken one Sign in again and Remove. */
@Composable
fun ProfileOptions(
    tile: ProfileTile,
    onRename: () -> Unit,
    onSetPin: () -> Unit,
    onRemovePin: () -> Unit,
    onSignOut: () -> Unit,
    onSignInAgain: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    val name = ProfileWords.name(tile)
    val actions = buildList {
        if (tile.status == TileStatus.NeedsAttention) {
            add(PanelAction(s.profilesSignInAgain, onSignInAgain, primary = true))
            add(PanelAction(s.profilesRemove, onRemove, danger = true))
        } else {
            add(PanelAction(s.profilesRename, onRename))
            add(PanelAction(if (tile.locked) s.profilesChangePin else s.profilesSetPin, onSetPin))
            if (tile.locked) add(PanelAction(s.profilesRemovePin, onRemovePin))
            add(PanelAction(s.profilesSignOut, onSignOut, danger = true))
        }
        add(PanelAction(s.commonCancel, onDismiss))
    }
    ProfilePanel(
        title = name,
        // What a PIN changes beyond the picker: a cast from this profile names only the app (HX88).
        body = listOfNotNull(ProfileWords.status(tile), if (tile.status != TileStatus.NeedsAttention) L.profilesPinCastNote else null)
            .joinToString("\n\n")
            .ifEmpty { null },
        actions = actions,
        onDismiss = onDismiss,
        header = { ProfileAvatar(name, tile.photo, 72.dp, Modifier.padding(bottom = 12.dp)) },
    )
}

/** Rename: one field, Save and Cancel. Empty goes back to the Telegram name. */
@Composable
fun RenameProfile(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    var text by rememberSaveable { mutableStateOf(current) }
    ProfilePanel(
        title = s.profilesRenameTitle,
        body = s.profilesRenameHint,
        actions = listOf(
            PanelAction(s.profilesSave, { onSave(text) }, primary = true),
            PanelAction(s.commonCancel, onDismiss),
        ),
        onDismiss = onDismiss,
        extra = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(com.tmplayer.data.AccountRules.LABEL_MAX) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave(text) }),
                modifier = Modifier.padding(top = 16.dp).fillMaxWidth(),
            )
        },
    )
}

/** A profile's own PIN, checked through the switcher, five wrong and a 30 second wait ([ProfilePin]). */
class ProfilePinChecker(private val switcher: AccountSwitcher, private val id: String) : PinChecker {
    override suspend fun waitUntil(): Long {
        val profile = switcher.profiles.value.byId(id) ?: return 0L
        val now = PinClock.now()
        val left = ProfilePin.waitLeft(profile, now)
        return if (left > 0) now + left else 0L
    }

    override suspend fun check(pin: String): PinCheck = switcher.checkPin(id, pin)

    override fun freeLeft(attempts: PinAttempts): Int = ProfilePin.triesLeft(attempts.failures)

    override val forgot: String get() = L.profilesPinForgot
}

/** The picker's words, worked out once for every platform. */
object ProfileWords {
    /** The tile's name, or "Account 2" for one never signed in. */
    fun name(tile: ProfileTile): String = tile.name.ifBlank { L.profilesUnnamed(number = tile.number) }

    /** The line under the name, or null for a profile that just opens. */
    fun status(tile: ProfileTile): String? = when (tile.status) {
        TileStatus.Ready -> null
        TileStatus.NeedsAttention -> L.profilesNeedsAttention
        TileStatus.SignInAgain -> L.profilesSignInAgain
        TileStatus.Adding -> L.profilesAdding
    }

    fun reason(reason: ProfileState.Reason): String = when (reason) {
        ProfileState.Reason.FolderMissing -> L.profilesReasonMissing
        ProfileState.Reason.NotAFolder -> L.profilesReasonNotFolder
        ProfileState.Reason.ReadOnly -> L.profilesReasonReadOnly
    }
}
