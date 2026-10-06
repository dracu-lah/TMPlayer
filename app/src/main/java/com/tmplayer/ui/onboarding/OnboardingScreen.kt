package com.tmplayer.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.DeviceForm
import com.tmplayer.data.SettingsStore
import com.tmplayer.ui.components.TmButton
import com.tmplayer.ui.components.TmSecondaryButton
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import com.tmplayer.ui.theme.focusRing
import kotlinx.coroutines.launch

/**
 * The tour, before signing in and whenever Settings asks for it again. A phone gets the shared
 * Material 3 pager ([OnboardingTour], which the desktop uses too); a television gets the same
 * pages, in its own words, laid out for a remote: the words and the buttons on the left, the
 * picture, the languages or the promises on the right, and Next always under the focus.
 *
 * [firstRun] is the tour before sign in, where the About page cannot be skipped past (see
 * [TourState]).
 */
@Composable
fun OnboardingScreen(firstRun: Boolean, onDone: () -> Unit, start: OnboardingPage = OnboardingPage.Language) {
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

    BackHandler(enabled = !tour.isFirst || tour.canLeave) { if (!tour.back()) onDone() }

    Row(
        Modifier.fillMaxSize().padding(horizontal = Tv.SafeH, vertical = Tv.SafeV),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                s.onboardingStep(tour.index + 1, tour.pages.size),
                style = MaterialTheme.typography.bodyMedium,
                color = Tone.accent,
            )
            Text(copy.title, style = MaterialTheme.typography.headlineLarge, color = Tone.text)
            Text(copy.body, style = MaterialTheme.typography.bodyLarge, color = Tone.muted)
            if (page == OnboardingPage.Language) {
                Text(s.onboardingHelpTranslate, style = MaterialTheme.typography.titleSmall, color = Tone.text)
                Text(s.onboardingHelpTranslateBody, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
            }

            Spacer(Modifier.height(12.dp))
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
            page.illustrated -> OnboardingImage(page, side.fillMaxWidth())
            page == OnboardingPage.Language -> TvLanguages(settings, side.fillMaxHeight())
            else -> Column(side, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                for (point in copy.points) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            point.kind.icon(),
                            contentDescription = null,
                            tint = Tone.accent,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(point.title, style = MaterialTheme.typography.titleMedium, color = Tone.text)
                            Text(point.body, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
                        }
                    }
                }
            }
        }
    }

    // Focus follows the page, so Next stays under the remote through the whole walk.
    LaunchedEffect(tour.index) { runCatching { next.requestFocus() } }
}

/** The languages as a list the D-pad walks, the chosen one ticked; OK picks one at once. */
@Composable
internal fun TvLanguages(settings: SettingsStore, modifier: Modifier) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val saved by settings.language.collectAsState(initial = "")
    val system = remember { systemLanguageName() }
    LazyColumn(
        modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = Tv.FocusClearance),
    ) {
        items(Onboarding.languages(), key = { it.tag }) { choice ->
            val interactions = remember { MutableInteractionSource() }
            val focused by interactions.collectIsFocusedAsState()
            val shape = RoundedCornerShape(Corner.Medium)
            val title = choice.name ?: s.onboardingLanguageSystem
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(shape)
                    .background(if (focused) Tone.focusFill else Tone.surface)
                    .focusRing(focused, shape)
                    .clickable(
                        interactionSource = interactions,
                        indication = null,
                        role = Role.RadioButton,
                    ) { scope.launch { settings.setLanguage(choice.tag) } }
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val ink = if (focused) Tone.onFocusFill else Tone.text
                if (saved.equals(choice.tag, ignoreCase = true)) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = if (focused) ink else Tone.accent, modifier = Modifier.size(22.dp))
                } else {
                    Spacer(Modifier.size(22.dp))
                }
                Spacer(Modifier.width(16.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, color = ink, modifier = Modifier.weight(1f))
                if (choice.name == null && system != null) {
                    Text(system, style = MaterialTheme.typography.bodyMedium, color = if (focused) ink else Tone.muted)
                }
            }
        }
    }
}
