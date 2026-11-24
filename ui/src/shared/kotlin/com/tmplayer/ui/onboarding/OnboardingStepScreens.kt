package com.tmplayer.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tmplayer.data.DeviceForm
import com.tmplayer.ui.about.About
import com.tmplayer.ui.about.QrPlate
import com.tmplayer.ui.components.BrandLayout
import com.tmplayer.ui.components.BrandLockup
import com.tmplayer.ui.components.BrandSize
import com.tmplayer.ui.components.LogoMark
import com.tmplayer.ui.components.RESTING
import com.tmplayer.ui.components.deviceForm
import com.tmplayer.ui.components.rememberLessMotion
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import kotlinx.coroutines.delay

/** The picture of a step: which icon, and the small move it makes once when the step comes up. */
enum class StepArt(val icon: ImageVector) {
    /** Notifications: the bell rings once. */
    Bell(TmIcons.Bell),

    /** Support: the heart beats twice. */
    Heart(TmIcons.Favorite),
}

/** Where a step sits among the optional steps: "Step 1 of 2" is shown only when there is more than one. */
data class StepOf(val index: Int, val total: Int)

/**
 * One of the optional steps after the first run's question (notifications, support), in one frame
 * for a phone, a tablet, a television and a desktop. The mark with the name at the top (its
 * playhead glides in), the step's icon in a round tile that scales in and makes one small move, the
 * heading and a sentence, then [primary] with [skipLabel] under it. Skip is always there, and Back
 * is Skip. [primary] null (a television has no browser to open) leaves Skip as the one button, and
 * it is what the remote lands on; [below] draws under the tile, or under the sentence on a narrow screen (the television's code).
 *
 * Every move is one run when the step appears, never a loop, so nothing runs while it sits. With
 * reduced motion, or the system's animations off ([rememberLessMotion]), all of it is drawn at
 * rest.
 */
@Composable
fun OnboardingStepScreen(
    art: StepArt,
    title: String,
    body: String,
    skipLabel: String,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    primaryLabel: String? = null,
    onPrimary: () -> Unit = {},
    position: StepOf? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val s = LocalStrings.current
    val form = deviceForm()
    BackHandler(onBack = onSkip)

    val skipFocus = remember { FocusRequester() }
    val primaryFocus = remember { FocusRequester() }
    // A remote lands on Skip where the step offers a gift (a television's one button; the phone's
    // first Tab is the primary one), and on the primary button elsewhere.
    val landOnSkip = primaryLabel == null || form == DeviceForm.Tv
    var landed by remember { mutableStateOf(false) }
    if (form != DeviceForm.Phone) {
        LaunchedEffect(title) {
            val target = if (landOnSkip) skipFocus else primaryFocus
            repeat(FOCUS_TRIES) {
                withFrameNanos { }
                if (it > 0) delay(FOCUS_RETRY_MS)
                if (landed) return@LaunchedEffect
                runCatching { target.requestFocus() }
            }
        }
    }

    val outer = when (form) {
        DeviceForm.Tv -> Modifier.padding(horizontal = Tv.SafeH, vertical = Tv.SafeV)
        DeviceForm.Desktop -> Modifier.padding(horizontal = 40.dp, vertical = 28.dp)
        DeviceForm.Phone -> Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)
    }
    val playhead = remember { Animatable(START_HEAD) }
    val less = rememberLessMotion()
    LaunchedEffect(less) {
        if (less) playhead.snapTo(RESTING) else playhead.animateTo(RESTING, tween(HEAD_MS, easing = FastOutSlowInEasing))
    }

    BoxWithConstraints(
        modifier.fillMaxSize().background(Tone.background).then(outer),
        contentAlignment = Alignment.Center,
    ) {
        val wide = maxWidth > maxHeight && maxWidth >= WIDE_FROM
        Column(Modifier.widthIn(max = if (wide) WIDE_MAX else NARROW_MAX).fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandLockup(
                    layout = BrandLayout.Row,
                    size = BrandSize.Small,
                    badge = false,
                    mark = { side -> LogoMark(side, progress = { playhead.value }) },
                )
                Spacer(Modifier.weight(1f))
                if (position != null && position.total > 1) {
                    Text(
                        s.setupStep(position.index, position.total),
                        style = MaterialTheme.typography.labelMedium,
                        color = Tone.muted,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(if (wide) 16.dp else 12.dp))
            val text: @Composable ColumnScope.() -> Unit = {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Tone.text,
                    textAlign = if (wide) TextAlign.Start else TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().headingSemantics(),
                )
                Text(
                    body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Tone.muted,
                    textAlign = if (wide) TextAlign.Start else TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val buttons: @Composable ColumnScope.() -> Unit = {
                if (primaryLabel != null) {
                    SetupButton(
                        primaryLabel,
                        primary = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(primaryFocus)
                            .onFocusChanged { if (it.isFocused && !landOnSkip) landed = true },
                        onClick = onPrimary,
                    )
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        QuietButton(
                            skipLabel,
                            modifier = Modifier.focusRequester(skipFocus).onFocusChanged { if (it.isFocused && landOnSkip) landed = true },
                            onClick = onSkip,
                        )
                    }
                } else {
                    SetupButton(
                        skipLabel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(skipFocus)
                            .onFocusChanged { if (it.isFocused) landed = true },
                        onClick = onSkip,
                    )
                }
            }
            if (wide) {
                Row(
                    Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Where there is a code (the television's), it sits under the tile on this side, so the
                    // words and Skip on the other side fit a 540 dp screen without scrolling.
                    Column(
                        Modifier.weight(1f).fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    ) {
                        StepTile(art, if (below != null) TILE_WITH_CODE else TILE_WIDE)
                        below?.invoke(this)
                    }
                    Column(
                        Modifier.weight(1.2f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        text()
                        Spacer(Modifier.height(6.dp))
                        buttons()
                    }
                }
            } else {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                ) {
                    StepTile(art, TILE_NARROW)
                    Spacer(Modifier.height(4.dp))
                    text()
                    below?.invoke(this)
                }
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { buttons() }
            }
        }
    }
}

/** The step's icon in a round tile: scales in once, then makes its one small move. */
@Composable
private fun StepTile(art: StepArt, side: androidx.compose.ui.unit.Dp) {
    val less = rememberLessMotion()
    val scale = remember { Animatable(if (less) 1f else START_SCALE) }
    val turn = remember { Animatable(0f) }
    val beat = remember { Animatable(1f) }
    LaunchedEffect(art, less) {
        if (less) {
            scale.snapTo(1f)
            turn.snapTo(0f)
            beat.snapTo(1f)
            return@LaunchedEffect
        }
        scale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow))
        delay(MOVE_DELAY_MS)
        when (art) {
            // A short ring: out, back, out a little less, and still.
            StepArt.Bell -> for (angle in RING) turn.animateTo(angle, tween(RING_STEP_MS, easing = FastOutSlowInEasing))
            // Two beats.
            StepArt.Heart -> repeat(2) {
                beat.animateTo(BEAT, tween(BEAT_MS, easing = FastOutSlowInEasing))
                beat.animateTo(1f, tween(BEAT_MS, easing = FastOutSlowInEasing))
            }
        }
    }
    Box(
        Modifier
            .size(side)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                alpha = if (less) 1f else ((scale.value - START_SCALE) / (1f - START_SCALE)).coerceIn(0f, 1f)
            }
            .clip(CircleShape)
            .background(Tone.accent.copy(alpha = TILE_ALPHA))
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            art.icon,
            contentDescription = null,
            tint = Tone.accent,
            modifier = Modifier
                .size(side * 0.5f)
                .graphicsLayer {
                    rotationZ = turn.value
                    scaleX = beat.value
                    scaleY = beat.value
                },
        )
    }
}

/**
 * The notifications step. [settingsRoute] is its second form: Android will not show its question (a
 * no was given, or twice before), so the one button opens the system's notification settings.
 */
@Composable
fun NotificationsStepScreen(
    settingsRoute: Boolean,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    position: StepOf? = null,
) {
    val s = LocalStrings.current
    OnboardingStepScreen(
        art = StepArt.Bell,
        title = if (settingsRoute) s.onboardingNotificationsOffTitle else s.onboardingNotificationsTitle,
        body = if (settingsRoute) s.onboardingNotificationsOffBody else s.onboardingNotificationsBody,
        skipLabel = s.setupSkip,
        onSkip = onSkip,
        modifier = modifier,
        primaryLabel = if (settingsRoute) s.onboardingNotificationsSettings else s.onboardingNotificationsAllow,
        onPrimary = if (settingsRoute) onOpenSettings else onAllow,
        position = position,
    )
}

/**
 * The support step. Where a browser can open ([onOpenPage] non null: a phone, a tablet, a desktop)
 * the one button opens the support page; on a television there is none, so the page is shown as a
 * code with its short address and Skip is the one button. Nothing is paid or promised here: the
 * page lists the ways, and the step says plainly that it unlocks nothing.
 */
@Composable
fun SupportStepScreen(
    onOpenPage: (() -> Unit)?,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    position: StepOf? = null,
) {
    val s = LocalStrings.current
    val link = About.supportUrl(SOURCE)
    OnboardingStepScreen(
        art = StepArt.Heart,
        title = s.onboardingSupportTitle,
        body = s.onboardingSupportBody,
        skipLabel = s.setupSkip,
        onSkip = onSkip,
        modifier = modifier,
        primaryLabel = if (onOpenPage != null) s.onboardingSupportOpen else null,
        onPrimary = { onOpenPage?.invoke() },
        position = position,
        below = if (onOpenPage == null) {
            {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    QrPlate(link, s.supportQrFor(shortAddress()), plate = TV_CODE)
                    Text(shortAddress(), style = MaterialTheme.typography.titleMedium, color = Tone.text)
                    Text(s.onboardingSupportScan, style = MaterialTheme.typography.bodyMedium, color = Tone.muted, textAlign = TextAlign.Center)
                }
            }
        } else {
            null
        },
    )
}

/** The page's address as it is said aloud: no scheme, no trailing slash. */
fun shortAddress(): String = About.SUPPORT_PAGE.removePrefix("https://").removeSuffix("/")

/** The place the support step's link is tagged with, as `utm_source`, so the site can tell the entry points apart. */
const val SOURCE = "onboarding"

private fun Modifier.headingSemantics(): Modifier = this.semantics { heading() }

private val TILE_NARROW = 112.dp
private val TILE_WIDE = 168.dp
private val TILE_WITH_CODE = 112.dp
private val TV_CODE = 148.dp
private val WIDE_FROM = 560.dp
private val NARROW_MAX = 600.dp
private val WIDE_MAX = 1000.dp

private const val START_SCALE = 0.6f
private const val START_HEAD = 0.05f
private const val HEAD_MS = 900
private const val MOVE_DELAY_MS = 120L
private const val RING_STEP_MS = 110
private val RING = floatArrayOf(14f, -12f, 8f, -5f, 0f)
private const val BEAT = 1.18f
private const val BEAT_MS = 150
private const val TILE_ALPHA = 0.14f
