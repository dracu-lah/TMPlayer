package com.tmplayer.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.data.DeviceForm
import com.tmplayer.data.SettingsStore
import com.tmplayer.i18n.Languages
import com.tmplayer.i18n.Translator
import com.tmplayer.ui.components.AppLogo
import com.tmplayer.ui.components.ChoiceRow
import com.tmplayer.ui.components.TmIcons
import com.tmplayer.ui.components.deviceForm
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.i18n.languageRowDetail
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.LocalDarkTheme
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The tour on a phone and on the desktop, in Material 3. The television draws the same pages, in
 * the same words, with its own remote-driven layout in the app (`OnboardingScreen`).
 *
 * A phone swipes through the pages with the buttons fixed under them, so a Next that slid away
 * with its page is never a Next nobody can press twice. The desktop puts the words beside the
 * picture, as a window has the width for it, and keeps Next under the keyboard's focus.
 */
@Composable
fun OnboardingTour(
    settings: SettingsStore,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    firstRun: Boolean = true,
    tour: TourState = remember(firstRun) { TourState(firstRun) },
) {
    // Back closes the language picker, then walks the pages in reverse, as it does everywhere else
    // in the app. From the first page it leaves a tour Settings asked for; on the first run it is
    // the system's, which closes the app.
    BackHandler(enabled = tour.backEnabled) { if (!tour.back()) onDone() }
    PostersDefaultOn(tour.firstRun)
    if (deviceForm() == DeviceForm.Desktop) {
        DesktopTour(settings, tour, onDone, modifier)
    } else {
        PhoneTour(settings, tour, onDone, modifier)
    }
}

@Composable
private fun PhoneTour(settings: SettingsStore, tour: TourState, onDone: () -> Unit, modifier: Modifier) {
    val s = LocalStrings.current
    val pages = tour.pages
    // `tour.index` stays the one place the page number lives, so Back and Next keep working; the
    // two effects hold the pager and it in step in both directions.
    val pager = rememberPagerState(initialPage = tour.index) { pages.size }
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { tour.index = it } }
    LaunchedEffect(tour.index) { if (pager.currentPage != tour.index) pager.animateScrollToPage(tour.index) }

    BoxWithConstraints(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        // A tablet or a phone on its side gets a reading measure, not a full-width column.
        val measure = if (maxWidth > PHONE_MAX) PHONE_MAX else maxWidth
        Column(
            Modifier
                .width(measure)
                .fillMaxHeight()
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (tour.choosingLanguage) {
                LanguagePicker(settings, tour, desktop = false, rows = Modifier.weight(1f, fill = false))
                Spacer(Modifier.weight(0.001f))
                FilledTonalButton(onClick = { tour.back() }, modifier = Modifier.fillMaxWidth()) { Text(s.onboardingBack) }
                return@Column
            }
            val step = s.onboardingStep(tour.index + 1, pages.size)
            LinearProgressIndicator(
                progress = { (tour.index + 1).toFloat() / pages.size },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = step },
            )
            // The pages take what the buttons leave and scroll inside it, so Next stays on screen
            // however long a page is (a phone on its side, a large font).
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxWidth().weight(1f),
                pageSpacing = 16.dp,
                verticalAlignment = Alignment.Top,
            ) { at ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PageWords(pages[at], DeviceForm.Phone, settings, tour)
                    if (pages[at].illustrated) {
                        // The chat list's shot is a whole phone screen; at full width it would be
                        // taller than the page, so it keeps to a width that leaves the words in view.
                        OnboardingImage(
                            pages[at],
                            Modifier.padding(top = 4.dp).widthIn(max = PHONE_IMAGE_MAX).fillMaxWidth().align(Alignment.CenterHorizontally),
                        )
                    }
                }
            }
            PrimaryButton(tour.isLast, Modifier.fillMaxWidth()) { if (!tour.next()) onDone() }
            if (tour.canSkip) {
                FilledTonalButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(s.onboardingSkip) }
            }
        }
    }
}

@Composable
private fun DesktopTour(settings: SettingsStore, tour: TourState, onDone: () -> Unit, modifier: Modifier) {
    val s = LocalStrings.current
    val page = tour.page
    val next = remember { FocusRequester() }
    Box(modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = when {
                // Three columns of languages, so the whole list fits a window without scrolling.
                tour.choosingLanguage -> 920.dp
                page.illustrated -> 1120.dp
                else -> 680.dp
            }).fillMaxWidth().padding(40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (tour.choosingLanguage) {
                LanguagePicker(settings, tour, desktop = true, rows = Modifier.heightIn(max = DESKTOP_LIST_MAX))
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { tour.back() }, modifier = Modifier.focusRequester(next)) { Text(s.onboardingBack) }
                return@Column
            }
            Text(
                s.onboardingStep(tour.index + 1, tour.pages.size),
                style = MaterialTheme.typography.labelLarge,
                color = Tone.accent,
            )
            val buttons: @Composable () -> Unit = {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton(tour.isLast, Modifier.focusRequester(next)) { if (!tour.next()) onDone() }
                    if (!tour.isFirst) TextButton(onClick = { tour.back() }) { Text(s.onboardingBack) }
                    if (tour.canSkip) TextButton(onClick = onDone) { Text(s.onboardingSkip) }
                }
            }
            if (page.illustrated) {
                Row(horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        PageWords(page, DeviceForm.Desktop, settings, tour)
                        Spacer(Modifier.height(8.dp))
                        buttons()
                    }
                    OnboardingImage(page, Modifier.weight(1.2f))
                }
            } else {
                PageWords(page, DeviceForm.Desktop, settings, tour)
                Spacer(Modifier.height(8.dp))
                buttons()
            }
        }
    }
    // Focus follows the page, so Enter walks the whole tour from the keyboard.
    LaunchedEffect(tour.index, tour.choosingLanguage) { runCatching { next.requestFocus() } }
}

/** Next, or Start on the last page, in the filled style the app's primary buttons share. */
@Composable
private fun PrimaryButton(last: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val s = LocalStrings.current
    val scheme = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        modifier = modifier,
        // As the app's TmButton: a dark theme's primary is a pale tint, so the container pair is used.
        colors = if (LocalDarkTheme.current) {
            ButtonDefaults.buttonColors(containerColor = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer)
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        Text(if (last) s.onboardingStart else s.onboardingNext)
        if (!last) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * A page's title, body and points. The Welcome page adds the language row under the body (so a
 * reader in the wrong language finds it before the rest) and the posters switch at the end.
 */
@Composable
private fun PageWords(page: OnboardingPage, form: DeviceForm, settings: SettingsStore, tour: TourState) {
    val s = LocalStrings.current
    val copy = s.onboarding(page, form)
    val desktop = form == DeviceForm.Desktop
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (page == OnboardingPage.Welcome) {
            Image(
                AppLogo.Mark,
                contentDescription = null,
                modifier = Modifier.size(if (desktop) 64.dp else 56.dp).clip(RoundedCornerShape(Corner.Medium)),
            )
        }
        Text(
            copy.title,
            style = if (desktop) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
            color = Tone.text,
        )
        Text(
            copy.body,
            style = if (desktop) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
            color = Tone.muted,
        )
        if (page == OnboardingPage.Welcome) LanguageRow(settings) { tour.choosingLanguage = true }
        for (point in copy.points) PointRow(point, desktop)
        if (page == OnboardingPage.Welcome) PostersSwitch(Modifier.padding(top = 4.dp))
    }
}

/**
 * The Welcome page's compact language row: the language in use, named in itself, and "Change",
 * which opens the full list over the page. The system's language is preselected, so most readers
 * never need it, and it no longer costs everybody a page.
 */
@Composable
private fun LanguageRow(settings: SettingsStore, onChange: () -> Unit) {
    val s = LocalStrings.current
    val saved by settings.language.collectAsState(initial = "")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.Medium))
            .clickable(role = Role.Button, onClick = onChange)
            .padding(start = 0.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TmIcons.Language, contentDescription = null, tint = Tone.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(s.onboardingLanguageLabel, style = MaterialTheme.typography.titleSmall, color = Tone.text)
            Text(
                s.languageRowDetail(saved),
                style = MaterialTheme.typography.bodySmall,
                color = Tone.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onChange) { Text(s.commonChange) }
    }
}

/** The full list of languages, opened from [LanguageRow]; picking one applies it and closes the list. */
@Composable
private fun ColumnScope.LanguagePicker(settings: SettingsStore, tour: TourState, desktop: Boolean, rows: Modifier) {
    val s = LocalStrings.current
    Text(
        s.onboardingLanguageTitle,
        style = if (desktop) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
        color = Tone.text,
    )
    Text(
        s.onboardingLanguageBody,
        style = if (desktop) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
        color = Tone.muted,
    )
    LanguageList(
        settings,
        columns = if (desktop) 3 else 1,
        helpTranslate = false,
        rows = rows,
        onPicked = { tour.choosingLanguage = false },
    )
}

@Composable
private fun PointRow(point: OnboardingPoint, desktop: Boolean) {
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Top) {
        Icon(point.kind.icon(), contentDescription = null, tint = Tone.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                point.title,
                style = if (desktop) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                color = Tone.text,
            )
            Text(
                point.body,
                style = if (desktop) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                color = Tone.muted,
            )
        }
    }
}

/** The icon beside a tour point, the same on every device. */
fun OnboardingPoint.Kind.icon(): ImageVector = when (this) {
    OnboardingPoint.Kind.Unofficial -> Icons.Filled.Info
    OnboardingPoint.Kind.NoServer -> Icons.Filled.Lock
    OnboardingPoint.Kind.NoData -> Icons.Filled.CheckCircle
    OnboardingPoint.Kind.FirstVideo -> Icons.AutoMirrored.Filled.Send
    OnboardingPoint.Kind.Folders -> TmIcons.Folder
    OnboardingPoint.Kind.SignIn -> Icons.Filled.Phone
}

/**
 * The system's language as it names itself, for the "System default" row: the device's languages
 * the app resolves against, never the language picked in the app. The process locale is only the
 * fallback before the app has resolved one, because Android rewrites it to the per-app language.
 */
fun systemLanguageName(): String? {
    val tag = Translator.systemDefault(fallback = listOf(Locale.getDefault().toLanguageTag()))
    return if (tag == Languages.PSEUDO) Onboarding.PSEUDO_NAME else Languages.find(tag)?.name
}

/**
 * Every language, each named in itself, with "System default" first and picked until somebody
 * picks another. The choice is the Settings one, so the app switches the moment a row is chosen,
 * and [onPicked] hears of it. The tour's picker and the Settings picker on a phone and a computer
 * both draw this. [helpTranslate] adds the "Help translate" link under the list (Settings has it;
 * the tour leaves it to Settings and About).
 *
 * With [rows] (a height limit or a weight) the rows scroll inside that, with a hairline over and
 * under them while there is more to see, so a page with buttons under the list keeps them in sight.
 * Without it the rows take their full height, for a picker that already scrolls them in its panel.
 */
@Composable
fun LanguageList(
    settings: SettingsStore,
    columns: Int,
    helpTranslate: Boolean = true,
    rows: Modifier? = null,
    onPicked: () -> Unit = {},
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val saved by settings.language.collectAsState(initial = "")
    val system = remember { systemLanguageName() }
    val choices = remember { Onboarding.languages() }
    val scroll = rememberScrollState()
    val more = rows != null && scroll.maxValue > 0
    if (more) HorizontalDivider(color = Tone.outline)
    // Edge to edge, as a Material list's rows are: each is already 56 dp of its own.
    Column(if (rows != null) rows.verticalScroll(scroll) else Modifier) {
        for (row in choices.chunked(columns)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (choice in row) {
                    ChoiceRow(
                        title = choice.name ?: s.onboardingLanguageSystem,
                        detail = if (choice.name == null) system else null,
                        selected = saved.equals(choice.tag, ignoreCase = true),
                        modifier = Modifier.weight(1f),
                    ) {
                        scope.launch { settings.setLanguage(choice.tag) }
                        onPicked()
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    if (more) HorizontalDivider(color = Tone.outline)
    if (helpTranslate) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        TextButton(onClick = { runCatching { uri.openUri(Onboarding.TRANSLATE_URL) } }) { Text(s.onboardingHelpTranslate) }
        Text(s.onboardingHelpTranslateBody, style = MaterialTheme.typography.bodySmall, color = Tone.muted)
    }
}

/** The rounded, outlined frame every tour screenshot sits in. */
@Composable
internal fun Modifier.onboardingFrame(): Modifier =
    clip(RoundedCornerShape(Corner.Large)).border(1.dp, Tone.outline, RoundedCornerShape(Corner.Large))

private val PHONE_MAX = 600.dp

/** The chat list's shot on a phone page: wide enough to read, narrow enough to keep the words in view. */
private val PHONE_IMAGE_MAX = 240.dp

/** Six rows of three (the first one taller, for the system language under it): the whole list today. */
private val DESKTOP_LIST_MAX = 360.dp
