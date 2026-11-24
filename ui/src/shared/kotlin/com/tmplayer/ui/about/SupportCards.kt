package com.tmplayer.ui.about

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.tmplayer.data.DeviceForm
import com.tmplayer.data.SupportRegion
import com.tmplayer.data.SupportRegion.Method
import com.tmplayer.ui.auth.QrCode
import com.tmplayer.ui.components.deviceForm
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.components.pressScale
import com.tmplayer.ui.components.rememberLessMotion
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.FocusVisibility
import com.tmplayer.ui.theme.LocalFocusStyle
import com.tmplayer.ui.theme.LocalOnFloating
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.collectIsFocusVisibleAsState
import com.tmplayer.ui.theme.floatingSurface
import com.tmplayer.ui.theme.focusFillOver
import com.tmplayer.ui.theme.focusRing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the support window does outside Compose, handed in by each platform: open a page in the
 * browser, copy text, and (Android only) hand the `upi://` link to a UPI app. Each returns whether
 * it worked, so the window can say so and fall back to the code instead of pretending.
 */
class SupportActions(
    val openLink: (String) -> Boolean,
    val copy: (String) -> Boolean,
    /** Null where no UPI app can exist (a television, a computer): the button is not drawn. */
    val openUpiApp: (() -> Boolean)? = null,
    /** Star on GitHub was pressed: the host records it so no star is asked for again. */
    val onStarred: () -> Unit = {},
)

/** Which page of the window is up: the ways to give, or one way's details. */
enum class SupportStep { Ways, Coffee, Upi }

/**
 * One way to give, as a card: its mark, its name and one line of what happens when it is pressed
 * (D1, 2026-11-16). One shared look for the phone, the television and the desktop.
 *
 * - Buy Me a Coffee in its own colours: their yellow, their near-black words and their official
 *   cup ([BmcCupMark]), as their brand kit shows the mark.
 * - UPI as a tinted card with a plain phone icon: NPCI's logo could not be fetched from its brand
 *   centre, so none is drawn (art/brand/LICENSE-NOTE.md).
 *
 * The whole card is the button: a press sinks it, the remote or the keyboard fills it and rings it
 * (the ring is the card's own ink on the yellow), and the pointer lifts it a little. With reduced
 * motion the sink and the lift go; the focus fill stays.
 */
@Composable
fun SupportCard(
    method: Method,
    title: String,
    detail: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leaves: Boolean = false,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val hovered by interactions.collectIsHoveredAsState()
    val calm = rememberLessMotion()
    // A television's remote never grows a card (WP-03): its focus is the fill and the ring. The
    // pointer's hover, and the keyboard's focus on a desktop, still lift it a little.
    val tv = LocalFocusStyle.current.tv
    val lift by animateFloatAsState(
        targetValue = if (!calm && !tv && (hovered || focused)) CARD_LIFT else 1f,
        animationSpec = Motion.standard(Motion.Fast),
        label = "supportCardLift",
    )
    val shape = RoundedCornerShape(Corner.Large)
    val coffee = method == Method.Coffee
    val fill = when {
        focused && !coffee -> Tone.focusFill
        coffee -> BmcBrand.Yellow
        else -> Tone.accent.copy(alpha = 0.14f)
    }
    val ink = when {
        coffee -> BmcBrand.Ink
        focused -> Tone.onFocusFill
        else -> Tone.text
    }
    val soft = ink.copy(alpha = 0.78f)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = CARD_HEIGHT)
            .graphicsLayer { scaleX = lift; scaleY = lift }
            .pressScale(interactions)
            .clip(shape)
            .background(fill)
            .focusRing(focused, shape, color = if (coffee) BmcBrand.Ink else Tone.onFocusFill)
            .hoverable(interactions)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$title. $detail" }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (coffee) {
            BmcCupMark(height = 40.dp)
        } else {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Tone.accent.copy(alpha = if (focused) 0f else 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(TmIcons.Phone, contentDescription = null, tint = if (focused) Tone.onFocusFill else Tone.accent, modifier = Modifier.size(24.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = ink)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = soft)
        }
        if (leaves) Icon(TmIcons.OpenExternal, contentDescription = null, tint = soft, modifier = Modifier.size(20.dp))
    }
}

/** The other way, when it is not the one this region uses: one quiet row that opens its card. */
@Composable
private fun SupportQuietRow(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    SupportButton(label, onClick, modifier = modifier.fillMaxWidth(), icon = TmIcons.Phone, quiet = true)
}

/**
 * A button of the support window. [primary] is the accent fill, [quiet] has no fill at rest; the
 * default is the step above the dialog. Under the remote or the keyboard it takes the focus fill
 * and ring every control takes; a press sinks it. 48 dp tall on a phone or a computer, 56 on a
 * television.
 */
@Composable
private fun SupportButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    quiet: Boolean = false,
    icon: ImageVector? = null,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val shape = RoundedCornerShape(Corner.Medium)
    val rest = when {
        primary -> Tone.accent
        quiet -> Color.Transparent
        else -> FloatingTone.control
    }
    // The primary one rests in the accent: it keeps it and shows focus with the ring (focusFillOver).
    val fill = if (focused) focusFillOver(rest) else rest
    val ink = when {
        focused -> Tone.onFocusFill
        primary -> Tone.onAccent
        quiet -> Tone.muted
        else -> Tone.text
    }
    Row(
        modifier
            .heightIn(min = if (isTouch()) 48.dp else 56.dp)
            .pressScale(interactions)
            .clip(shape)
            .background(fill)
            .focusRing(focused, shape, rest = rest)
            .hoverable(interactions)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = ink, textAlign = TextAlign.Center)
    }
}

/**
 * The support window, the one every way in opens (Settings, About, the reminder), the same on a
 * phone, a television and a computer (D1).
 *
 * The ways to give are two cards in the order [order] says (UPI first in India, Buy Me a Coffee
 * first elsewhere, the other as a quiet row there), then Sponsor on GitHub and Star on GitHub.
 * Pressing a card opens that way's page: Buy Me a Coffee opens in the browser, and where there is
 * none (a television, a phone with no browser) it shows its code and address; UPI shows the code,
 * the UPI ID and Copy, and on a phone "Open in a UPI app". Nothing opens or is paid until a press.
 *
 * With [onNever] set it is the gentle ask: "Maybe later" ([onClose]) and "Don't show again".
 * Back and Esc go one level up (a page back to the ways, the ways closed); a tap outside closes.
 * On a television and a computer the first control takes the remote, and the control that opened
 * a page gets it back when the page closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportDialog(
    order: SupportRegion.Order,
    title: String,
    text: String,
    actions: SupportActions,
    onClose: () -> Unit,
    onNever: (() -> Unit)? = null,
    /** The browser build's "I already support": null where there is no such way out. */
    onSupporter: (() -> Unit)? = null,
    start: SupportStep = SupportStep.Ways,
) {
    val touch = isTouch()
    var step by rememberSaveable { mutableStateOf(start) }
    var upiFailed by rememberSaveable { mutableStateOf(false) }
    var copied by rememberSaveable { mutableStateOf(false) }
    val coffeeFocus = remember { FocusRequester() }
    val upiFocus = remember { FocusRequester() }
    val pageFocus = remember { FocusRequester() }
    // The card that opened a page gets the remote back when the page closes (rule 13).
    var cameFrom by remember { mutableStateOf(order.first) }
    // A phone is touched, so nothing there takes focus; a television and a computer start on a control.
    val takesFocus = deviceForm() != DeviceForm.Phone

    val back = { if (step == SupportStep.Ways) onClose() else step = SupportStep.Ways }

    fun openCoffee() {
        // A browser answers on a phone and a computer. Where it does not, or on a television, the
        // card shows the code to scan instead.
        if (touch && actions.openLink(About.COFFEE)) return
        step = SupportStep.Coffee
    }

    BasicAlertDialog(
        onDismissRequest = back,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        CompositionLocalProvider(LocalOnFloating provides true) {
            Box(Modifier.padding(horizontal = 16.dp).widthIn(max = if (touch) 440.dp else 560.dp)) {
                AnimatedContent(
                    targetState = step,
                    transitionSpec = { fadeIn(Motion.standard(Motion.Fast)) togetherWith fadeOut(Motion.standard(Motion.Fast)) },
                    label = "supportStep",
                ) { shown ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .floatingSurface()
                            .verticalScroll(rememberScrollState())
                            .padding(if (touch) 20.dp else 28.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        when (shown) {
                            SupportStep.Ways -> WaysPage(
                                order = order,
                                title = title,
                                text = text,
                                touch = touch,
                                actions = actions,
                                coffeeFocus = coffeeFocus,
                                upiFocus = upiFocus,
                                onCoffee = {
                                    cameFrom = Method.Coffee
                                    openCoffee()
                                },
                                onUpi = {
                                    cameFrom = Method.Upi
                                    upiFailed = false
                                    copied = false
                                    step = SupportStep.Upi
                                },
                                onClose = onClose,
                                onNever = onNever,
                                onSupporter = onSupporter,
                            )
                            SupportStep.Coffee -> CoffeePage(touch = touch, actions = actions, pageFocus = pageFocus, onBack = back)
                            SupportStep.Upi -> UpiPage(
                                touch = touch,
                                actions = actions,
                                failed = upiFailed,
                                copied = copied,
                                pageFocus = pageFocus,
                                onOpenApp = { upiFailed = !(actions.openUpiApp?.invoke() ?: false) },
                                onCopy = { copied = actions.copy(About.UPI_ID) },
                                onBack = back,
                            )
                        }
                    }
                }
            }
        }
    }

    if (takesFocus) {
        // A frame first: the page is composed once the cross-fade has made it, and a request
        // before that has nothing to land on.
        LaunchedEffect(step) {
            withFrameNanos { }
            withFrameNanos { }
            runCatching {
                when {
                    step != SupportStep.Ways -> pageFocus.requestFocus()
                    cameFrom == Method.Upi -> upiFocus.requestFocus()
                    else -> coffeeFocus.requestFocus()
                }
            }
        }
    }
}

@Composable
private fun WaysPage(
    order: SupportRegion.Order,
    title: String,
    text: String,
    touch: Boolean,
    actions: SupportActions,
    coffeeFocus: FocusRequester,
    upiFocus: FocusRequester,
    onCoffee: () -> Unit,
    onUpi: () -> Unit,
    onClose: () -> Unit,
    onNever: (() -> Unit)?,
    onSupporter: (() -> Unit)?,
) {
    val s = LocalStrings.current
    Box(Modifier.size(48.dp).clip(CircleShape).background(Tone.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Icon(TmIcons.Favorite, contentDescription = null, tint = Tone.accent, modifier = Modifier.size(26.dp))
    }
    Text(title, style = MaterialTheme.typography.headlineSmall, color = Tone.text, textAlign = TextAlign.Center)
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Tone.muted, textAlign = TextAlign.Center)

    @Composable
    fun card(method: Method) {
        val modifier = Modifier.focusRequester(if (method == Method.Coffee) coffeeFocus else upiFocus)
        when (method) {
            Method.Coffee -> SupportCard(
                method = Method.Coffee,
                title = s.supportBuyCoffee,
                detail = if (touch) s.supportCoffeeDetail else s.supportCoffeeDetailTv,
                onClick = onCoffee,
                modifier = modifier,
                leaves = touch,
            )
            Method.Upi -> SupportCard(
                method = Method.Upi,
                title = s.supportPayUpi,
                detail = s.supportUpiDetail,
                onClick = onUpi,
                modifier = modifier,
            )
        }
    }

    card(order.first)
    if (order.secondIsQuiet) {
        // Outside India UPI is the quieter row, but it is always there, one press from its card.
        SupportQuietRow(s.supportOtherUpi, onUpi, Modifier.focusRequester(upiFocus))
    } else {
        card(order.second)
    }

    // The two free or quieter ways: a sponsorship, and a star, which costs nothing and is worth
    // asking for as much as a coffee (user, 2026-10-18).
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SupportButton(s.supportSponsorGithub, { actions.openLink(About.SPONSORS) }, Modifier.weight(1f), icon = TmIcons.Favorite)
        SupportButton(
            s.supportStarGithub,
            {
                actions.openLink(About.SOURCE)
                actions.onStarred()
            },
            Modifier.weight(1f),
            icon = TmIcons.StarOutline,
        )
    }

    if (onNever != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SupportButton(s.supportDontShow, onNever, Modifier.weight(1f), quiet = true)
            SupportButton(s.supportMaybeLater, onClose, Modifier.weight(1f))
        }
    } else {
        SupportButton(s.commonClose, onClose, Modifier.fillMaxWidth())
    }
    if (onSupporter != null) SupportButton(s.webCoffeeAlready, onSupporter, Modifier.fillMaxWidth(), quiet = true)
}

/** Buy Me a Coffee where nothing opens it: the code on their yellow and the address under it. */
@Composable
private fun CoffeePage(touch: Boolean, actions: SupportActions, pageFocus: FocusRequester, onBack: () -> Unit) {
    val s = LocalStrings.current
    Text(s.supportBuyCoffee, style = MaterialTheme.typography.headlineSmall, color = Tone.text, textAlign = TextAlign.Center)
    Text(s.supportCoffeeScan, style = MaterialTheme.typography.bodyMedium, color = Tone.muted, textAlign = TextAlign.Center)
    Column(
        Modifier.clip(RoundedCornerShape(Corner.Large)).background(BmcBrand.Yellow).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BmcCupMark(height = 22.dp)
            Text("Buy Me a Coffee", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = BmcBrand.Ink) // i18n-ok: brand name
        }
        QrPlate(About.COFFEE, s.supportScanCoffee, plate = if (touch) 200.dp else 230.dp)
        Text(About.COFFEE.removePrefix("https://"), style = MaterialTheme.typography.bodyMedium, color = BmcBrand.Ink)
    }
    // A phone whose browser would not open the page still has the button here to try again.
    if (touch) SupportButton(s.commonOpen, { actions.openLink(About.COFFEE) }, Modifier.fillMaxWidth(), primary = true)
    SupportButton(s.commonBack, onBack, Modifier.fillMaxWidth().focusRequester(pageFocus), icon = TmIcons.ArrowBack)
}

/** UPI: the code for another phone, the ID to copy or type, and on an Android phone the app. */
@Composable
private fun UpiPage(
    touch: Boolean,
    actions: SupportActions,
    failed: Boolean,
    copied: Boolean,
    pageFocus: FocusRequester,
    onOpenApp: () -> Unit,
    onCopy: () -> Unit,
    onBack: () -> Unit,
) {
    val s = LocalStrings.current
    val app = touch && actions.openUpiApp != null
    Text(s.supportPayUpi, style = MaterialTheme.typography.headlineSmall, color = Tone.text, textAlign = TextAlign.Center)
    Text(
        if (failed) s.supportUpiNoApp else s.supportUpiPageNote,
        style = MaterialTheme.typography.bodyMedium,
        color = if (failed) Tone.text else Tone.muted,
        textAlign = TextAlign.Center,
    )
    QrPlate(About.UPI_LINK, s.supportScanUpi, plate = if (touch) 200.dp else 230.dp)
    Text(s.supportScanUpi, style = MaterialTheme.typography.bodyMedium, color = Tone.muted, textAlign = TextAlign.Center)
    Text("${s.supportUpiId}: ${About.UPI_ID}", style = MaterialTheme.typography.bodyLarge, color = Tone.text, textAlign = TextAlign.Center)
    if (app) SupportButton(s.supportOpenUpiApp, onOpenApp, Modifier.fillMaxWidth().focusRequester(pageFocus), primary = true)
    SupportButton(
        if (copied) s.supportUpiCopied else s.supportUpiCopy,
        onCopy,
        Modifier.fillMaxWidth().then(if (app) Modifier else Modifier.focusRequester(pageFocus)),
        icon = TmIcons.Copy,
    )
    SupportButton(s.commonBack, onBack, Modifier.fillMaxWidth(), icon = TmIcons.ArrowBack)
}

/** A code on its white plate with the quiet margin a scanner needs, at the size a camera across a room reads. */
@Composable
internal fun QrPlate(link: String, description: String, plate: Dp) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = link) {
        value = withContext(Dispatchers.Default) { QrCode.render(link, QR_PIXELS) }
    }
    Box(
        Modifier.size(plate).clip(RoundedCornerShape(Corner.Medium)).background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(it, contentDescription = description, modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(10.dp))
        }
    }
}

private val CARD_HEIGHT = 72.dp
private const val CARD_LIFT = 1.02f
private const val QR_PIXELS = 520
