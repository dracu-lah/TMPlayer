package com.tmplayer.ui.settings

import com.tmplayer.ui.components.keepTopInSight
import com.tmplayer.ui.components.rememberMoved
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import com.tmplayer.ui.components.liveMessage
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.BuildConfig
import com.tmplayer.data.Updates
import com.tmplayer.i18n.L
import com.tmplayer.ui.about.About
import com.tmplayer.ui.components.AppLogo
import com.tmplayer.ui.components.PhonePad
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import com.tmplayer.ui.theme.focusRing
import com.tmplayer.ui.update.LinkQrDialog
import com.tmplayer.ui.update.openLink
import com.tmplayer.ui.update.readableUrl
import kotlinx.coroutines.launch

/**
 * Settings, then About: the version, the licence and its lack of warranty, and every link the
 * words in [About] list. A phone opens each link in the browser. A TV has none, so each one is a
 * QR code there instead, and the third-party notices are read in the app on both.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val s = LocalStrings.current
    var reading by remember { mutableStateOf(false) }
    if (reading) {
        BackHandler { reading = false }
        NoticesScreen(onBack = { reading = false })
        return
    }

    val context = LocalContext.current
    val touch = isTouch()
    var qr by remember { mutableStateOf<About.Link?>(null) }
    var coffee by remember { mutableStateOf(false) }
    val supporter by remember { com.tmplayer.data.SettingsStore(context).supportCounters }
        .collectAsState(initial = com.tmplayer.data.SupportReminder.Counters())
    val first = remember { FocusRequester() }
    // The rows that open a dialog, which the remote goes back to when it closes (WP-15).
    val coffeeRow = remember { FocusRequester() }
    val linkRows = remember { HashMap<String, FocusRequester>() }
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val open = { link: About.Link -> if (!openLink(context, link.url)) qr = link }

    val aboutState = rememberLazyListState()
    val list: @Composable () -> Unit = {
        LazyColumn(
            state = aboutState,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (touch) {
                        Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = if (touch) PhonePad.Side else Tv.SafeH),
            contentPadding = PaddingValues(
                // A television's list starts under the pinned header, which owns the safe area's top.
                top = 4.dp,
                bottom = if (touch) insets.calculateBottomPadding() + 32.dp else 40.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(if (touch) 0.dp else 10.dp),
        ) {
            item { Masthead() }

            About.groups(Updates.installedVersion, supporter = supporter.supporter).forEachIndexed { index, group ->
                item { SectionTitle(group.title) }
                group.note?.let { note ->
                    item {
                        Column(
                            Modifier.padding(
                                start = if (touch) 16.dp else 0.dp,
                                end = if (touch) 16.dp else 0.dp,
                                bottom = 8.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // TMDB's logo with its notice, smaller than TMPlayer's own mark.
                            if (group.tmdbLogo) com.tmplayer.ui.about.TmdbMark(height = if (touch) 14.dp else 18.dp)
                            Text(
                                note,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Tone.muted,
                            )
                        }
                    }
                }
                // The notices lead the first group: they are read here, not in a browser.
                if (index == 0) {
                    item {
                        ActionRow(
                            title = s.aboutNotices,
                            subtitle = s.aboutNoticesDetail,
                            icon = TmIcons.List,
                            modifier = Modifier.focusRequester(first),
                            onClick = { reading = true },
                        )
                    }
                }
                // Buy Me a Coffee first, then GitHub Sponsors, then the codes and the page that lists
                // every way to help. A phone has BMC's own yellow button; a television has the same
                // row as the others, with the cup on a yellow tile (nothing on a remote grows, WP-03).
                if (group.support) {
                    item {
                        if (touch) {
                            CoffeeButton(
                                onClick = { open(About.Link(s.supportBuyCoffee, "", About.COFFEE)) },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        } else {
                            CoffeeRow(onClick = { coffee = true }, modifier = Modifier.focusRequester(coffeeRow))
                        }
                    }
                    item {
                        ActionRow(
                            title = s.supportSponsorGithub,
                            subtitle = readableUrl(About.SPONSORS),
                            icon = TmIcons.Favorite,
                            onClick = { open(About.Link(s.supportSponsorGithub, "", About.SPONSORS)) },
                        )
                    }
                }
                // No Support QR row here (WP-08): Settings has one Support row, and its dialog shows the codes.
                items(group.links, key = { it.url + it.title }) { link ->
                    ActionRow(
                        title = link.title,
                        subtitle = link.detail,
                        icon = iconFor(group.title),
                        service = link.service,
                        modifier = if (touch) Modifier else Modifier.focusRequester(linkRows.getOrPut(link.url) { FocusRequester() }),
                        onClick = { open(link) },
                    )
                }
            }
        }
    }

    if (touch) {
        // Its name in a fixed header beside Back, as on every other page of Settings.
        val moved = aboutState.rememberMoved()
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    com.tmplayer.ui.components.PageHeader(
                        { M3Text(s.aboutTitle, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                        TmIcons.ArrowBack,
                        s.aboutBackToSettings,
                        onBack,
                        scrolled = moved,
                    )
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) { list() }
            }
        }
    } else {
        // Settings' pinned header (WP-07), outside the list, so a focused row never scrolls under
        // it and the screen reads as part of Settings.
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
            Box(Modifier.padding(start = Tv.SafeH, end = Tv.SafeH, top = Tv.SafeV)) {
                SettingsHeader(title = s.settingsTitle, crumb = s.aboutTitle)
            }
            // The padding is outside the list, so its viewport and clip end at the safe line: the last row
            // is never in the bottom overscan margin (contentPadding would let it draw there).
            Box(Modifier.fillMaxWidth().weight(1f).padding(bottom = Tv.SafeV)) { list() }
        }
        // A frame first: the row is a lazy item, composed only once the list has been measured.
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
    }

    val scope = rememberCoroutineScope()
    val coffeeDone = { coffee = false; if (!touch) scope.launch { coffeeRow.settle() } }
    if (coffee) CoffeeDialog(supporter, asking = false, onLater = coffeeDone, onNever = coffeeDone)

    qr?.let { link ->
        LinkQrDialog(url = link.url, what = link.title.replaceFirstChar { it.lowercase() }.let { s.aboutLinkQrWhat(it) }) {
            qr = null
            if (!touch) scope.launch { linkRows[link.url]?.settle() }
        }
    }
}

/** The logo, the name and version, and the licence with its lack of warranty. */
@Composable
private fun Masthead() {
    val s = LocalStrings.current
    val touch = isTouch()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                start = if (touch) 16.dp else 0.dp,
                end = if (touch) 16.dp else 0.dp,
                top = if (touch) 12.dp else 0.dp,
                bottom = 12.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (touch) 16.dp else 24.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // The mark and the name, then the version under them.
            com.tmplayer.ui.components.BrandLockup(
                size = if (touch) com.tmplayer.ui.components.BrandSize.Medium else com.tmplayer.ui.components.BrandSize.Large,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            // The version is the copy target on a touch screen (WP-104); on a television the About
            // page is read, and the account menu's footer is where OK copies it.
            val info = com.tmplayer.ui.components.rememberVersionInfo()
            val copier = com.tmplayer.ui.components.rememberVersionCopier(info)
            Text(
                if (copier.copied) s.aboutVersionCopied else "${s.settingsVersion} ${com.tmplayer.data.VersionLine.shown(info.version)}",
                style = if (touch) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                color = Tone.text,
                modifier = Modifier
                    .then(
                        if (touch) {
                            Modifier
                                .heightIn(min = 48.dp)
                                .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = s.aboutVersionCopy, onClick = copier::copy)
                                .wrapContentHeight(Alignment.CenterVertically)
                        } else {
                            Modifier
                        },
                    )
                    .liveMessage()
                    .testTag(com.tmplayer.ui.components.VERSION_FOOTER_TAG),
            )
            val state = com.tmplayer.ui.components.rememberVersionState()
            com.tmplayer.ui.components.versionStateText(state)?.let { words ->
                Text(
                    words,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state is com.tmplayer.data.VersionState.Available) Tone.caution else Tone.muted,
                    modifier = Modifier.testTag(com.tmplayer.ui.components.VERSION_NOTE_TAG),
                )
            }
            Text(
                s.aboutBuildLine(BuildConfig.VERSION_CODE.toString(), About.LICENCE),
                style = MaterialTheme.typography.bodyMedium,
                color = Tone.muted,
            )
            Text(
                About.WARRANTY,
                style = MaterialTheme.typography.bodyMedium,
                color = Tone.muted,
                modifier = Modifier.widthIn(max = 720.dp),
            )
        }
    }
}

private fun iconFor(group: String): ImageVector = when (group) {
    L.aboutContact -> TmIcons.Email
    About.SUPPORT_TITLE, About.SUPPORT_THANKS_TITLE -> TmIcons.Favorite
    L.aboutDataAndLaw -> TmIcons.Lock
    else -> TmIcons.Info
}

/**
 * THIRD_PARTY_NOTICES.md, compiled into the app, so it reads the same with no network and on a TV
 * with no browser. On a TV the whole page holds focus and the D-pad scrolls it, a screen at a time
 * less a little, since there is nothing in it to select.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoticesScreen(onBack: () -> Unit) {
    val s = LocalStrings.current
    val touch = isTouch()
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val page = remember { FocusRequester() }
    val insets = WindowInsets.safeDrawing.asPaddingValues()

    val list: @Composable () -> Unit = {
        LazyColumn(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .keepTopInSight(state, enabled = !touch)
                .then(
                    if (touch) {
                        Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    } else {
                        Modifier
                            .focusRequester(page)
                            .onKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                val step = state.layoutInfo.viewportSize.height * 0.8f
                                when (event.key) {
                                    Key.DirectionDown -> scope.launch { state.animateScrollBy(step) }
                                    Key.DirectionUp -> scope.launch { state.animateScrollBy(-step) }
                                    else -> return@onKeyEvent false
                                }
                                true
                            }
                            .focusable(interactionSource = interactions)
                            .focusRing(focused, RoundedCornerShape(Corner.Large), filled = false)
                    },
                )
                .padding(horizontal = if (touch) PhonePad.Side + 16.dp else Tv.SafeH),
            contentPadding = PaddingValues(
                top = if (touch) 8.dp else Tv.SafeV,
                bottom = if (touch) insets.calculateBottomPadding() + 32.dp else 40.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!touch) {
                item {
                    Text(
                        s.aboutNoticesTvHint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tone.muted,
                    )
                }
            }
            items(About.noticesBody) { block -> NoticeBlock(block) }
        }
    }

    if (touch) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { M3Text(s.aboutNotices) },
                    navigationIcon = {
                        com.tmplayer.ui.components.IconTip(s.aboutBackToAbout, side = com.tmplayer.ui.components.TipSide.Below) {
                            IconButton(onClick = onBack) {
                                M3Icon(TmIcons.ArrowBack, contentDescription = s.aboutBackToAbout)
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) { list() }
        }
    } else {
        list()
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { page.requestFocus() }
        }
    }
}

@Composable
private fun NoticeBlock(block: About.Block) {
    val touch = isTouch()
    val type = MaterialTheme.typography
    when (block) {
        is About.Block.Heading -> Text(
            block.text,
            style = when (block.level) {
                1 -> if (touch) type.titleLarge else type.headlineMedium
                2 -> type.titleLarge
                else -> type.titleMedium
            },
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
