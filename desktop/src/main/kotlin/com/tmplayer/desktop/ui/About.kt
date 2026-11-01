package com.tmplayer.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.tmplayer.desktop.os.OpenExternal
import com.tmplayer.i18n.L
import com.tmplayer.ui.about.About
import com.tmplayer.ui.components.SettingsDividerStart
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Tone

/** The pages under Settings, About and help: About TMPlayer, and its third-party notices. */
enum class AboutView { Links, Notices }

@Composable
internal fun AboutView.title(): String {
    val s = LocalStrings.current
    return if (this == AboutView.Notices) s.aboutNoticesTitle else s.settingsAbout
}

@Composable
internal fun AboutView.backLabel(): String {
    val s = LocalStrings.current
    return if (this == AboutView.Notices) s.aboutBackToAbout else s.aboutBackToSettings
}

/**
 * A sub page's name in the two pane layout, with its round Back before it, where every other
 * page has its name alone.
 */
@Composable
internal fun SubPageTitle(title: String, backLabel: String, onBack: () -> Unit) {
    Row(
        Modifier.padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val interactions = remember { MutableInteractionSource() }
        BarTooltip(backLabel, "Esc", below = true) {
            IconButton(
                onClick = onBack,
                interactionSource = interactions,
                modifier = Modifier.keyboardRing(interactions, CircleShape),
            ) { Icon(TmIcons.ArrowBack, contentDescription = backLabel) }
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, color = Tone.text)
    }
}

/**
 * Settings, About and help, then About TMPlayer, on the desktop: the same words and links as the
 * phone and TV (they live in [About]), drawn as Settings draws every page, in grouped rows beside
 * the list. The third-party notices open in the same place rather than in a browser, so they
 * read the same offline.
 */
@Composable
internal fun AboutContent(view: AboutView, version: String, supporter: Boolean, onRead: () -> Unit) {
    when (view) {
        AboutView.Links -> Links(version, supporter, onRead)
        AboutView.Notices -> Notices()
    }
}

/** About TMPlayer on its own with its title, for the render tests that draw it alone. */
@Composable
internal fun AboutPage(version: String, supporter: Boolean = false, onBack: () -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp).widthIn(max = 760.dp)) {
        SubPageTitle(AboutView.Links.title(), AboutView.Links.backLabel(), onBack)
        AboutContent(AboutView.Links, version, supporter, onRead = {})
    }
}

@Composable
private fun Links(version: String, supporter: Boolean, onRead: () -> Unit) {
    val s = LocalStrings.current
    var supporting by remember { mutableStateOf(false) }
    if (supporting) SupportPopup(from = "about", onClose = { supporting = false })

    // The mark and the name, then the version and the licence under them, in line with the rows.
    Column(
        Modifier.padding(start = SETTING_INSET, end = SETTING_INSET, top = 12.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        com.tmplayer.ui.components.BrandLockup(size = com.tmplayer.ui.components.BrandSize.Large)
        Spacer(Modifier.size(4.dp))
        // Clicked seven times, it shows the developer rows in Settings (see DevOptions).
        Text(
            "${s.settingsVersion} $version",
            style = MaterialTheme.typography.titleMedium,
            color = Tone.text,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = DevOptions::tap,
            ),
        )
        Text(About.LICENCE, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
        Text(About.WARRANTY, style = MaterialTheme.typography.bodySmall, color = Tone.muted)
    }

    SettingsColumn(Modifier.padding(vertical = 4.dp), dividerStart = SettingsDividerStart) {
        About.groups(version, supporter = supporter).forEachIndexed { index, group ->
            Group(group.title)
            // TMDB's logo with its notice, smaller than TMPlayer's own mark; the support group's
            // note says it is voluntary. Both sit over the group's card, in line with its words.
            if (group.tmdbLogo || group.note != null) {
                Column(
                    Modifier.padding(start = SETTING_INSET, end = SETTING_INSET, top = 4.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (group.tmdbLogo) com.tmplayer.ui.about.TmdbMark(height = 16.dp)
                    group.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Tone.muted) }
                }
            }
            if (group.support) {
                // Buy Me a Coffee first and in its own yellow, GitHub Sponsors the quieter one beside it.
                Row(
                    Modifier.padding(start = SETTING_INSET, end = SETTING_INSET, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CoffeeButton(onClick = { OpenExternal.browse(About.COFFEE) })
                    OutlinedButton(onClick = { OpenExternal.browse(About.SPONSORS) }) { Text(s.supportSponsorGithub) }
                }
            }
            // The notices lead the first group: they are read here, not in a browser.
            if (index == 0) {
                Action(s.aboutNoticesTitle, s.aboutNoticesDetail, TmIcons.List, onClick = onRead)
            }
            if (group.support) {
                Action(s.aboutShowQr, s.aboutShowQrDetail, TmIcons.Favorite) { supporting = true }
            }
            group.links.forEach { link ->
                Action(
                    link.title,
                    if (link.url == About.PRIVACY) s.settingsPrivacyComputer else link.detail,
                    iconFor(link, group),
                    end = RowEnd.External,
                ) { OpenExternal.browse(link.url) }
            }
        }
    }
}

/** Each link's own picture, so a long page reads at a glance; the group's when there is none. */
private fun iconFor(link: About.Link, group: About.Group): ImageVector = when {
    link.url == About.SOURCE -> TmIcons.FolderOpen
    link.url == About.PRIVACY -> TmIcons.Lock
    link.url == About.LEGAL -> TmIcons.Gavel
    link.url.startsWith("mailto:") -> TmIcons.Email
    link.url.endsWith("/discussions") -> TmIcons.ChatsMany
    link.url.contains("t.me/tmplayerapp") -> TmIcons.Channel
    link.url.contains("t.me/") -> TmIcons.Group
    link.url == com.tmplayer.ui.onboarding.Onboarding.TRANSLATE_URL -> TmIcons.Language
    group.support -> TmIcons.Favorite
    group.tmdbLogo -> TmIcons.Image
    group.title == L.onlineAboutGroup -> TmIcons.Subtitles
    link.title == L.aboutCorrespondingSource -> TmIcons.Archive
    else -> TmIcons.Info
}

@Composable
private fun Notices() {
    val type = MaterialTheme.typography
    SelectionContainer {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = SETTING_INSET, end = SETTING_INSET, top = 8.dp, bottom = 24.dp),
        ) {
            About.noticesBody.forEach { block ->
                when (block) {
                    is About.Block.Heading -> Text(
                        block.text,
                        style = if (block.level <= 2) type.titleMedium else type.titleSmall,
                        color = if (block.level == 1) Tone.text else Tone.accent,
                        modifier = Modifier.padding(top = if (block.level == 1) 0.dp else 12.dp),
                    )
                    is About.Block.Paragraph -> Text(block.text, style = type.bodyMedium, color = Tone.text)
                    is About.Block.Bullet -> Row {
                        Text("•", style = type.bodyMedium, color = Tone.muted)
                        Spacer(Modifier.size(10.dp))
                        Text(block.text, style = type.bodyMedium, color = Tone.text)
                    }
                }
            }
        }
    }
}
