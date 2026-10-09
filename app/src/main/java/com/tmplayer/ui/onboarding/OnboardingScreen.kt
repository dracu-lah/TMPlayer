package com.tmplayer.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.DeviceForm
import com.tmplayer.data.SettingsStore
import com.tmplayer.ui.components.AppLogo
import com.tmplayer.ui.components.TmButton
import com.tmplayer.ui.components.TmIcons
import com.tmplayer.ui.components.TmSecondaryButton
import com.tmplayer.ui.components.TvChoiceRow
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.i18n.languageRowDetail
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import kotlinx.coroutines.launch

/**
 * The tour, before signing in and whenever Settings asks for it again. A phone gets the shared
 * Material 3 pager ([OnboardingTour], which the desktop uses too); a television gets the same
 * pages, in its own words, laid out for a remote: the words, the choices and the buttons on the
 * left, the promises, the picture or the languages on the right, and Next always under the focus.
 *
 * [firstRun] is the tour before sign in, where the Welcome page cannot be skipped past (see
 * [TourState]).
 */
@Composable
fun OnboardingScreen(firstRun: Boolean, onDone: () -> Unit, start: OnboardingPage = OnboardingPage.Welcome) {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context.applicationContext) }
    val tour = remember(firstRun) { TourState(firstRun, start = Onboarding.pages.indexOf(start)) }
    if (isTouch()) {
        OnboardingTour(settings, onDone, firstRun = firstRun, tour = tour)
    } else {
        TvTour(settings, tour, onDone)
    }
}

@Composable
private fun TvTour(settings: SettingsStore, tour: TourState, onDone: () -> Unit) {
    val s = LocalStrings.current
    val page = tour.page
    val copy = s.onboarding(page, DeviceForm.Tv)
    val next = remember { FocusRequester() }
    val languageRow = remember { FocusRequester() }
    val picked = remember { FocusRequester() }
    // Where focus goes when the picker closes: back on the row that opened it, not on Next.
    var fromPicker by remember { mutableStateOf(false) }
    val saved by settings.language.collectAsState(initial = "")

    BackHandler(enabled = tour.backEnabled) {
        val closing = tour.choosingLanguage
        if (!tour.back()) onDone()
        if (closing) fromPicker = true
    }
    PostersDefaultOn(tour.firstRun)

    Row(
        Modifier.fillMaxSize().padding(horizontal = Tv.SafeH, vertical = Tv.SafeV),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (page == OnboardingPage.Welcome) {
                Image(
                    AppLogo.Mark,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(Corner.Medium)),
                )
            }
            Text(
                s.onboardingStep(tour.index + 1, tour.pages.size),
                style = MaterialTheme.typography.bodyMedium,
                color = Tone.accent,
            )
            Text(copy.title, style = MaterialTheme.typography.headlineMedium, color = Tone.text)
            Text(copy.body, style = MaterialTheme.typography.bodyLarge, color = Tone.muted)
            if (page == OnboardingPage.Welcome) {
                Spacer(Modifier.height(2.dp))
                TvChoiceRow(
                    title = s.onboardingLanguageLabel,
                    trailing = s.languageRowDetail(saved),
                    icon = TmIcons.Language,
                    modifier = Modifier.focusRequester(languageRow),
                ) { tour.choosingLanguage = true }
                PostersSwitch()
            } else {
                for (point in copy.points) TvPoint(point, compact = true)
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TmButton(
                    onClick = { if (!tour.next()) onDone() },
                    modifier = Modifier.focusRequester(next),
                ) {
                    Text(if (tour.isLast) s.onboardingStart else s.onboardingNext)
                    if (!tour.isLast) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                if (tour.canSkip) {
                    TmSecondaryButton(onClick = onDone) { Text(s.onboardingSkip) }
                }
            }
        }

        val side = Modifier.weight(1.25f)
        when {
            tour.choosingLanguage -> Column(side.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.onboardingLanguageTitle, style = MaterialTheme.typography.titleLarge, color = Tone.text)
                TvLanguages(
                    settings,
                    Modifier.weight(1f),
                    focusSelected = picked,
                    current = saved,
                    onPicked = {
                        tour.choosingLanguage = false
                        fromPicker = true
                    },
                )
            }
            page.illustrated -> OnboardingImage(page, side.fillMaxWidth())
            else -> Column(side, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                for (point in copy.points) TvPoint(point, compact = false)
            }
        }
    }

    // Focus follows the page, so Next stays under the remote through the whole walk; the picker
    // takes it on its chosen language, and gives it back to the row that opened it.
    LaunchedEffect(tour.index, tour.choosingLanguage) {
        runCatching {
            when {
                tour.choosingLanguage -> picked.requestFocus()
                fromPicker -> languageRow.requestFocus()
                else -> next.requestFocus()
            }
        }
        fromPicker = false
    }
}

/** A tour point beside its icon; [compact] for the left column, where the words share the height. */
@Composable
private fun TvPoint(point: OnboardingPoint, compact: Boolean) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            point.kind.icon(),
            contentDescription = null,
            tint = Tone.accent,
            modifier = Modifier.padding(top = 2.dp).size(if (compact) 20.dp else 24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                point.title,
                style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                color = Tone.text,
            )
            Text(
                point.body,
                style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                color = Tone.muted,
            )
        }
    }
}

/**
 * The languages as a list the D-pad walks, the chosen one ticked; OK picks one at once and tells
 * [onPicked]. [focusSelected], when given, sits on the chosen row, which the list opens scrolled to.
 */
@Composable
internal fun TvLanguages(
    settings: SettingsStore,
    modifier: Modifier,
    focusSelected: FocusRequester? = null,
    /** The chosen language when the caller already read it, so the first frame knows the row. */
    current: String? = null,
    onPicked: () -> Unit = {},
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val stored by settings.language.collectAsState(initial = "")
    // Without the caller's value the first frame takes "" (System default) for the chosen row, and
    // the focus would land there rather than on the language in use.
    val saved = current ?: stored
    val system = remember { systemLanguageName() }
    val choices = remember { Onboarding.languages() }
    val list = rememberLazyListState(
        initialFirstVisibleItemIndex = choices.indexOfFirst { it.tag.equals(saved, ignoreCase = true) }.coerceAtLeast(0),
    )
    LazyColumn(
        modifier,
        state = list,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(vertical = Tv.FocusClearance),
    ) {
        itemsIndexed(choices, key = { _, it -> it.tag }) { _, choice ->
            val selected = saved.equals(choice.tag, ignoreCase = true)
            TvChoiceRow(
                title = choice.name ?: s.onboardingLanguageSystem,
                trailing = if (choice.name == null) system else null,
                selected = selected,
                modifier = if (selected && focusSelected != null) Modifier.focusRequester(focusSelected) else Modifier,
            ) {
                scope.launch { settings.setLanguage(choice.tag) }
                onPicked()
            }
        }
    }
}
