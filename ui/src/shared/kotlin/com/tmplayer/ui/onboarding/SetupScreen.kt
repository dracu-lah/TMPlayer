package com.tmplayer.ui.onboarding

import com.tmplayer.ui.components.pressScale
import com.tmplayer.ui.theme.collectIsFocusVisibleAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.tmplayer.data.AddedChats
import com.tmplayer.data.AddedMode
import com.tmplayer.data.DeviceFit
import com.tmplayer.data.DeviceForm
import com.tmplayer.data.Recommendation
import com.tmplayer.data.SettingsStore
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.ui.components.deviceForm
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.theme.Caution
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import com.tmplayer.ui.theme.focusRing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The setup's preference steps ([Setup]), the same screen on the phone, the television and the
 * desktop, in the first run's shared frame ([StepFrame]). Each step asks one thing with two
 * answers side by side, each with a picture of what it looks like, and opens with the answer that
 * is set now already chosen (on a fresh sign in, the recommended one). Picking an answer only
 * marks it; Next, the step's one primary button, applies what is marked and moves on, so the
 * shortest way through keeps every default. Back, or a swipe back on a phone, goes to the step
 * before; Skip all at the top ends the setup and whatever follows it, changing nothing more.
 *
 * A remote or a keyboard starts each step on Next. Up, or Right on a wide screen, reaches the
 * answers; OK on one marks it and returns to Next. Wide screens (a television, a desktop window,
 * a phone on its side) put the words beside the answers; a phone held upright stacks them.
 */
@Composable
fun SetupScreen(
    settings: SettingsStore,
    state: SetupState,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    /** What the Device step says about this device, and what it offers to change (CP68). */
    fit: DeviceFit = DeviceFit.Fine,
    recommendations: List<Recommendation> = emptyList(),
    /** How many steps of the first run come before these, and each group's size, for the bar at the top. */
    before: Int = 0,
    groups: List<Int> = listOf(state.steps.size),
    /** Whether something follows the last step, so its button says Next rather than Done. */
    moreAfter: Boolean = false,
    /** Skip all: out of the setup and whatever follows it. */
    onSkipAll: () -> Unit = onDone,
    /** Back from the first step, to what came before it; null leaves as Skip all does. */
    onBackFromFirst: (() -> Unit)? = null,
    /** What the crash report step starts or stops once its answer is written (CP102f). */
    crashReporting: CrashReporting = CrashReporting.Unavailable,
    /**
     * The real "Add chats" picker (HX27/HX28), over the host's own chat list, drawn full screen
     * in place of this one while it is up. Null leaves [SetupStep.Chats] unable to open anything;
     * the host that offers the step is the one that must supply this (see [Setup.stepsFor]'s
     * `chatsAvailable`).
     */
    chatsPicker: (@Composable (onClose: () -> Unit) -> Unit)? = null,
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val online = OnlineMetadata.current
    val lowStorage by settings.lowStorageMode.collectAsState(initial = false)
    val lighter by settings.lighterPictures.collectAsState(initial = false)
    val saveData by settings.saveMobileData.collectAsState(initial = false)
    val crashReports by settings.crashReports.collectAsState(initial = false)
    val addedChats by AddedChats.choice.collectAsState()
    val posters = online?.settings?.collectAsState()?.value?.enabled ?: true
    val values = SetupValues(
        posters = posters,
        lowStorage = lowStorage,
        lighter = lighter,
        saveData = saveData,
        crashReports = crashReports,
        addedChatsPicked = addedChats.mode == AddedMode.Picked,
    )
    val form = deviceForm()
    val remote = form != DeviceForm.Phone

    val step = state.step
    // What is marked on this step: the viewer's pick, or until there is one, what is set now.
    var picked by remember(step) { mutableStateOf<SetupChoice?>(null) }
    val selected = picked ?: values.current(step)
    val primaryFocus = remember { FocusRequester() }
    var focusedOn by remember { mutableStateOf<SetupStep?>(null) }
    // The real picker, over this whole screen rather than inside it: a list of chats has its own
    // chrome (search, Select all, a total at the foot) and drawing both at once would double it.
    var showChatsPicker by remember(step) { mutableStateOf(false) }

    if (step == SetupStep.Chats && showChatsPicker) {
        val picker = chatsPicker
        if (picker == null) {
            // Should not happen (the host only offers the step with a picker to open), but nothing
            // here may be a dead end: fall back to the two-answer card instead of a blank screen.
            showChatsPicker = false
        } else {
            picker {
                // The picker already wrote the answer itself (Done) or changed nothing (Back,
                // discarded): either way it is resolved, so this step is done either way, exactly
                // like skipping it would be.
                showChatsPicker = false
                if (!state.next()) onDone()
            }
            return
        }
    }

    fun next() {
        // The Chats step's real answer takes a screen, not a setting: Next opens it rather than
        // writing anything, whether this is a fresh pick or a returning viewer reviewing theirs.
        if (step == SetupStep.Chats && selected == SetupChoice.ChooseChats) {
            showChatsPicker = true
            return
        }
        val choice = selected
        // Only a change is written: Next on an untouched step leaves every setting as it was, even
        // one changed on its own in Settings since. Mobile data suggests saving although it is off,
        // and the Device and Chats steps' answers are actions, so both compare with what is really set.
        if (choice != values.chosen(step)) {
            scope.launch {
                runCatching {
                    choice.applyTo(
                        settings,
                        posters = online?.let { o -> { on: Boolean -> o.setEnabled(on) } },
                        recommendations = recommendations,
                        crashReports = crashReporting.onChange,
                    )
                }
            }
        }
        if (!state.next()) onDone()
    }

    fun mark(choice: SetupChoice) {
        picked = choice
        // A remote goes back to Next, one press from moving on with what it marked. The Chats
        // step's "Choose chats" instead opens the picker at once, both for a tap and for a remote's
        // OK on the row, so marking it never leaves a dangling answer nothing will ever write.
        if (step == SetupStep.Chats && choice == SetupChoice.ChooseChats) {
            showChatsPicker = true
        } else if (remote) {
            runCatching { primaryFocus.requestFocus() }
        }
    }

    val back: (() -> Unit)? = if (!state.isFirst) ({ state.back() }) else onBackFromFirst
    BackHandler { back?.invoke() ?: onSkipAll() }

    val copy = s.setupCopy(step).let { if (step == SetupStep.Device) it.copy(title = s.fitHeadline(fit)) else it }
    val last = state.isLast && !moreAfter
    StepFrame(
        group = s.setupGroupPreferences,
        position = FlowPosition(before + state.index, groups),
        primary = if (last) s.setupDone else s.setupNext,
        onPrimary = ::next,
        onBack = back,
        onSkipAll = if (last) null else onSkipAll,
        modifier = modifier,
        primaryFocus = primaryFocus,
        onPrimaryFocused = { focusedOn = step },
    ) { wide ->
        if (wide) {
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    StepHeading(copy.title, copy.body)
                    Text(s.setupLater, style = MaterialTheme.typography.bodySmall, color = Tone.muted)
                }
                BoxWithConstraints(Modifier.weight(1.3f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    // Two squares side by side, as large as the width allows and the height leaves
                    // room for, with the answer's name and line under each.
                    val side = min((maxWidth - GAP) / 2 - CARD_PAD * 2, maxHeight - LABELS - CARD_PAD * 2)
                    if (step == SetupStep.Device) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            DeviceChoices(recommendations, selected, ::mark)
                        }
                    } else if (step == SetupStep.CrashReports) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            CrashChoice(selected, ::mark)
                        }
                    } else if (step == SetupStep.Chats) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            ChatsChoice(selected, addedChats.chats.size, ::mark)
                        }
                    } else {
                        Choices(step, selected, side, ::mark)
                    }
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                StepHeading(copy.title, copy.body)
                if (step == SetupStep.Device) {
                    DeviceChoices(recommendations, selected, ::mark)
                } else if (step == SetupStep.CrashReports) {
                    CrashChoice(selected, ::mark)
                } else if (step == SetupStep.Chats) {
                    ChatsChoice(selected, addedChats.chats.size, ::mark)
                } else {
                    Choices(step, selected, side = null, onPick = ::mark)
                }
                Text(s.setupLater, style = MaterialTheme.typography.bodySmall, color = Tone.muted)
            }
        }
    }

    // A remote or a keyboard starts each step on Next, asked for again over a few frames until it
    // has it: a window not yet focused, or a list arriving under the setup, can refuse the first.
    if (remote) {
        LaunchedEffect(step) {
            repeat(FOCUS_TRIES) {
                withFrameNanos { }
                if (it > 0) delay(FOCUS_RETRY_MS)
                if (focusedOn == step) return@LaunchedEffect
                runCatching { primaryFocus.requestFocus() }
            }
        }
    }
}

// Three seconds in all: a television cold start can take that long to give the window its focus.
internal const val FOCUS_TRIES = 40
internal const val FOCUS_RETRY_MS = 75L

/**
 * The Device step's answers: the list of what would change, then Apply and Not now as two rows to
 * mark, like every other step's answers. No pictures: the changes are settings, and their names
 * say what they do.
 */
@Composable
private fun DeviceChoices(
    recommendations: List<Recommendation>,
    selected: SetupChoice,
    onPick: (SetupChoice) -> Unit,
) {
    val s = LocalStrings.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RecommendationList(
            recommendations,
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Corner.Large)).background(Tone.surfaceHigh).padding(16.dp),
        )
        for (choice in SetupStep.Device.choices) {
            val apply = choice == SetupChoice.ApplyRecommended
            OptionRow(
                title = if (apply) s.deviceApply else s.deviceNotNow,
                detail = if (apply) s.setupDeviceApplyDetail else s.setupDeviceNotNowDetail,
                selected = selected == choice,
                recommended = apply,
                modifier = Modifier.arrowsMoveFocus(),
            ) { onPick(choice) }
        }
    }
}

/**
 * The crash report step's one switch ([CrashReportsQuestion]): pressing it marks the other answer,
 * and Next writes what is marked, as on every other step.
 */
@Composable
private fun CrashChoice(selected: SetupChoice, onPick: (SetupChoice) -> Unit) {
    val on = selected == SetupChoice.CrashReportsOn
    CrashReportsQuestion(
        on = on,
        onToggle = { onPick(if (on) SetupChoice.CrashReportsOff else SetupChoice.CrashReportsOn) },
    )
}

/**
 * The Chats step's two rows ([SetupStep.Chats], HX28): Browse everything, the recommended default,
 * and Choose chats, which opens the real "Add chats" picker (HX27) at once rather than waiting for
 * Next, since there is nothing to mark here that a tap on it should not already act on. Once
 * chosen, the count under the row is the same words Settings' own "Choose chats" row uses, so
 * reopening it to change a mind reads as the same feature in both places.
 */
@Composable
private fun ChatsChoice(selected: SetupChoice, chosenCount: Int, onPick: (SetupChoice) -> Unit) {
    val s = LocalStrings.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OptionRow(
            title = s.setupChatsAll,
            detail = s.setupChatsAllDetail,
            selected = selected == SetupChoice.BrowseAllChats,
            recommended = true,
            modifier = Modifier.arrowsMoveFocus(),
        ) { onPick(SetupChoice.BrowseAllChats) }
        OptionRow(
            title = s.setupChatsChoose,
            detail = s.setupChatsChooseDetail,
            selected = selected == SetupChoice.ChooseChats,
            modifier = Modifier.arrowsMoveFocus(),
        ) { onPick(SetupChoice.ChooseChats) }
        if (selected == SetupChoice.ChooseChats) {
            Text(
                s.settingsHomeSourceChosenDetail(count = chosenCount),
                style = MaterialTheme.typography.bodySmall,
                color = Tone.muted,
                // Lines up under the row's own title: its 16 dp padding, then the mark and the
                // gap before the words (OptionRow's own 22 dp mark, 12 dp spacing).
                modifier = Modifier.padding(start = 16.dp + 22.dp + 12.dp, top = 2.dp),
            )
        }
    }
}

/**
 * One answer as a row to mark: a round check, its name, the line under it, and "Recommended"
 * where it is. The Device step's answers and the appearance step's wallpaper switch.
 */
@Composable
internal fun OptionRow(
    title: String,
    detail: String?,
    selected: Boolean,
    modifier: Modifier = Modifier,
    recommended: Boolean = false,
    role: Role = Role.RadioButton,
    onClick: () -> Unit,
) {
    val s = LocalStrings.current
    val interactions = remember { MutableInteractionSource() }
    val shown by interactions.collectIsFocusVisibleAsState()
    val shape = RoundedCornerShape(Corner.Large)
    val touch = deviceForm() == DeviceForm.Phone
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (shown) Tone.focusFill else Tone.surfaceHigh)
            .then(if (selected) Modifier.border(2.dp, Tone.accent, shape) else Modifier)
            .focusRing(shown, shape)
            .semantics { this.selected = selected }
            .clickable(
                interactionSource = interactions,
                indication = if (touch) LocalIndication.current else null,
                role = role,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Mark(selected, if (shown) Tone.onFocusFill else Tone.accent)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (shown) Tone.onFocusFill else Tone.text)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (shown) Tone.onFocusFill.copy(alpha = 0.85f) else Tone.muted,
                )
            }
            if (recommended) {
                Text(
                    s.setupRecommended,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (shown) Tone.onFocusFill else Tone.accent,
                )
            }
        }
    }
}

/** A round mark: a filled check when chosen, an empty ring when not. */
@Composable
internal fun Mark(selected: Boolean, color: Color, size: Dp = 22.dp) {
    if (selected) {
        Icon(TmIcons.CheckCircle, contentDescription = null, tint = color, modifier = Modifier.size(size))
    } else {
        Box(Modifier.size(size).padding(2.dp).border(2.dp, Tone.outline, CircleShape))
    }
}

/** The two answers side by side. [side] is the picture's edge; null lets each take half the width. */
@Composable
private fun Choices(
    step: SetupStep,
    selected: SetupChoice,
    side: Dp?,
    onPick: (SetupChoice) -> Unit,
) {
    // As tall as the taller card, so a longer line under one answer does not leave the other short.
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(GAP), verticalAlignment = Alignment.Top) {
        for (choice in step.choices) {
            ChoiceCard(
                choice = choice,
                selected = selected == choice,
                side = side,
                modifier = (if (side == null) Modifier.weight(1f) else Modifier).arrowsMoveFocus(),
                onPick = { onPick(choice) },
            )
        }
    }
}

@Composable
private fun ChoiceCard(choice: SetupChoice, selected: Boolean, side: Dp?, modifier: Modifier, onPick: () -> Unit) {
    val s = LocalStrings.current
    val copy = s.choiceCopy(choice)
    val interactions = remember { MutableInteractionSource() }
    val shown by interactions.collectIsFocusVisibleAsState()
    val shape = RoundedCornerShape(Corner.Large)
    val touch = deviceForm() == DeviceForm.Phone
    Column(
        modifier
            .then(if (side != null) Modifier.width(side + CARD_PAD * 2) else Modifier)
            .fillMaxHeight()
            .clip(shape)
            .background(if (shown) Tone.focusFill else Tone.surfaceHigh)
            .then(if (selected) Modifier.border(2.dp, Tone.accent, shape) else Modifier)
            .focusRing(shown, shape)
            .semantics { this.selected = selected }
            .clickable(
                interactionSource = interactions,
                indication = if (touch) LocalIndication.current else null,
                role = Role.RadioButton,
                onClick = onPick,
            )
            .padding(CARD_PAD),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .then(if (side != null) Modifier.size(side) else Modifier.fillMaxWidth().aspectRatio(1f))
                .clip(RoundedCornerShape(Corner.Medium))
                .background(Tone.background),
        ) {
            ChoicePicture(choice, Modifier.fillMaxSize())
            if (selected) {
                Icon(
                    TmIcons.CheckCircle,
                    contentDescription = null,
                    tint = Tone.accent,
                    // On the picture's right whatever the reading direction: the shots are of the
                    // app as it is, and on their left sits what the answer is about.
                    modifier = Modifier
                        .align(if (choice.screenshot) AbsoluteAlignment.TopRight else Alignment.TopEnd)
                        .padding(6.dp)
                        .size(26.dp)
                        .background(Tone.background, CircleShape),
                )
            }
        }
        val onFill = if (shown) Tone.onFocusFill else Tone.text
        Text(copy.title, style = MaterialTheme.typography.titleSmall, color = onFill, maxLines = 2)
        Text(
            copy.detail,
            style = MaterialTheme.typography.bodySmall,
            color = if (shown) Tone.onFocusFill.copy(alpha = 0.85f) else Tone.muted,
            maxLines = 4,
        )
        // Under the words rather than on the picture, which it would hide part of. The row is as
        // tall as the taller card, so the two still line up.
        if (choice.recommended) {
            Text(
                s.setupRecommended,
                style = MaterialTheme.typography.labelMedium,
                color = if (shown) Tone.onFocusFill else Tone.accent,
            )
        }
    }
}

/**
 * An answer's picture, filling [modifier]'s square: the marked shot, or the drawing for the storage
 * and mobile data steps. The setup's cards and Settings' Setup choices show the same one.
 */
@Composable
internal fun ChoicePicture(choice: SetupChoice, modifier: Modifier) {
    when {
        choice.screenshot -> SetupShot(choice, modifier)
        choice.step == SetupStep.MobileData -> MobileDataArt(choice, modifier)
        else -> StorageArt(choice, modifier)
    }
}

/**
 * A round button that fills under the focus like the cards do. [primary] is the accent-filled one:
 * the first run's Next and Done ([StepFrame]), and the low-end device card's Apply.
 */
@Composable
internal fun SetupButton(
    label: String,
    icon: Boolean = false,
    primary: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused = interactions.collectIsFocusVisibleAsState().value
    val touch = deviceForm() == DeviceForm.Phone
    Row(
        modifier
            // Sinks a little under a finger or a click, and comes back up on release.
            .pressScale(interactions, com.tmplayer.ui.components.Micro.PillPressScale)
            .clip(CircleShape)
            .background(
                when {
                    focused -> Tone.focusFill
                    primary -> Tone.accent
                    else -> Tone.surfaceHigh
                },
            )
            .focusRing(focused, CircleShape)
            // The accent fill and the focus fill are near one colour in the dark theme, so a
            // focused primary button also gets an outline in the text colour.
            .then(if (primary && focused) Modifier.border(2.dp, Tone.text, CircleShape) else Modifier)
            .clickable(
                interactionSource = interactions,
                indication = if (touch) LocalIndication.current else null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = when {
            focused -> Tone.onFocusFill
            primary -> Tone.onAccent
            else -> Tone.text
        }
        if (icon) {
            Icon(TmIcons.ArrowBack, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/**
 * The storage step's picture, drawn rather than shot, since a frame of the player says nothing
 * about what is kept. Over a video's length (the bar), the part kept on the device is filled: up to
 * where it is playing and a little ahead, or with Low storage mode only a short stretch around
 * the playhead. Under it, how full the device gets. Follows the reading direction.
 */
@Composable
private fun StorageArt(choice: SetupChoice, modifier: Modifier) {
    val low = choice == SetupChoice.LowStorage
    val accent = Tone.accent
    val track = Tone.outline
    val ground = Tone.background
    val text = Tone.muted
    Column(
        modifier.padding(14.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(TmIcons.Play, contentDescription = null, tint = text, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Canvas(Modifier.weight(1f).height(26.dp).mirrored()) {
                val h = size.height * 0.42f
                val y = (size.height - h) / 2
                val r = CornerRadius(h / 2, h / 2)
                drawRoundRect(track, Offset(0f, y), Size(size.width, h), r)
                val from = if (low) 0.34f else 0f
                drawRoundRect(accent, Offset(size.width * from, y), Size(size.width * (0.62f - from), h), r)
                val head = Offset(size.width * 0.48f, size.height / 2)
                drawCircle(ground, radius = size.height * 0.42f, center = head)
                drawCircle(accent, radius = size.height * 0.30f, center = head)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(TmIcons.Download, contentDescription = null, tint = text, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Canvas(Modifier.weight(1f).height(40.dp).mirrored()) {
                val r = CornerRadius(size.height * 0.22f, size.height * 0.22f)
                drawRoundRect(track, Offset.Zero, size, r)
                val inset = size.height * 0.14f
                val inner = Size(size.width - inset * 2, size.height - inset * 2)
                drawRoundRect(ground, Offset(inset, inset), inner, r)
                val used = if (low) 0.18f else 0.78f
                drawRoundRect(
                    if (low) accent else Caution,
                    Offset(inset, inset),
                    Size(inner.width * used, inner.height),
                    r,
                )
            }
        }
    }
}

/**
 * The mobile data step's picture, drawn like the storage step's. Over a video's length, the part
 * fetched while it plays on mobile data: a short stretch ahead of the playhead when saving data,
 * or everything to the end without limits. Downloads are not in it: neither answer holds them back
 * (that is Wi-Fi only, in Settings), so a second bar would draw a difference there is not.
 */
@Composable
private fun MobileDataArt(choice: SetupChoice, modifier: Modifier) {
    val save = choice == SetupChoice.SaveData
    val accent = Tone.accent
    val track = Tone.outline
    val ground = Tone.background
    val text = Tone.muted
    Column(
        modifier.padding(14.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(TmIcons.Play, contentDescription = null, tint = text, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Canvas(Modifier.weight(1f).height(26.dp).mirrored()) {
                val h = size.height * 0.42f
                val y = (size.height - h) / 2
                val r = CornerRadius(h / 2, h / 2)
                drawRoundRect(track, Offset(0f, y), Size(size.width, h), r)
                val to = if (save) 0.58f else 1f
                drawRoundRect(if (save) accent else Caution, Offset(0f, y), Size(size.width * to, h), r)
                val head = Offset(size.width * 0.40f, size.height / 2)
                drawCircle(ground, radius = size.height * 0.42f, center = head)
                drawCircle(accent, radius = size.height * 0.30f, center = head)
            }
        }
    }
}

/**
 * The arrow keys move between the answers and down to the buttons. A remote's D-pad does this on
 * its own; a desktop window's arrow keys do not, and Tab alone is a poor way across two cards.
 */
@Composable
internal fun Modifier.arrowsMoveFocus(): Modifier {
    val focusManager = LocalFocusManager.current
    return onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val direction = when (event.key) {
            Key.DirectionLeft -> FocusDirection.Left
            Key.DirectionRight -> FocusDirection.Right
            Key.DirectionUp -> FocusDirection.Up
            Key.DirectionDown -> FocusDirection.Down
            else -> return@onPreviewKeyEvent false
        }
        focusManager.moveFocus(direction)
        true
    }
}

/** Flips a drawing for a right-to-left language, where the bar fills from the right. */
@Composable
private fun Modifier.mirrored(): Modifier =
    if (LocalLayoutDirection.current == LayoutDirection.Rtl) graphicsLayer(scaleX = -1f) else this

private val GAP = 12.dp
private val CARD_PAD = 8.dp

/** The room under a picture for the answer's name, its line and "Recommended". */
private val LABELS = 110.dp
