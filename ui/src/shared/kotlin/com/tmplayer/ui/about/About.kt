package com.tmplayer.ui.about

import com.tmplayer.data.SupportReminder
import com.tmplayer.i18n.L
import com.tmplayer.online.OnlineSubtitles
import com.tmplayer.online.OnlineMetadata

/**
 * What the About screen says, the same on phone, TV and desktop: the licence line, every link it
 * offers and the third-party notices. Each app draws it its own way (a TV turns each link into a
 * QR code, since it has no browser), but the words and addresses live only here.
 *
 * GPL-3 section 5(d) asks for the licence and the absence of warranty to be shown in the program's
 * interface, and stores that list the app want a contact address inside it.
 */
object About {

    /** Under the version line. */
    val LICENCE: String get() = L.aboutLicenceLine

    val WARRANTY: String get() = L.aboutWarranty

    const val SOURCE = "https://github.com/dracu-lah/TMPlayer"
    const val NOTICES_ONLINE = "$SOURCE/blob/main/THIRD_PARTY_NOTICES.md"
    const val PRIVACY = "https://tmplayer.org/privacy"
    const val LEGAL = "https://tmplayer.org/legal"
    const val EMAIL = "hello@tmplayer.org"
    const val SPONSORS = "https://github.com/sponsors/dracu-lah"
    const val COFFEE = "https://buymeacoffee.com/nevil.dev"
    const val SITE = "https://tmplayer.org/"
    const val SUPPORT_PAGE = "https://tmplayer.org/support/"
    const val OPENSUBTITLES = "https://www.opensubtitles.com"
    const val SUBDL = "https://subdl.com"
    const val TMDB = "https://www.themoviedb.org"
    const val TVMAZE = "https://www.tvmaze.com"
    const val ANILIST = "https://anilist.co"

    /**
     * The support page, tagged with the place the viewer came from (`card1`, `card2`, `card3`,
     * `settings`, `about`), as Vercel Web Analytics reads it: `utm_source` is the place and
     * `utm_medium` is `app`. A custom `from` parameter would not be recorded, UTM ones are. The
     * entry points are compared on the site, without the app sending anything anywhere.
     */
    fun supportUrl(from: String): String = "$SUPPORT_PAGE?utm_source=$from&utm_medium=app"

    /** The heading, the Settings row and the card's title. */
    val SUPPORT_TITLE: String get() = L.aboutSupportTitle

    /** Under the support heading, wherever the links appear. */
    val SUPPORT_NOTE: String get() = L.aboutSupportNote

    /** A link and the sentence under it. */
    data class Link(val title: String, val detail: String, val url: String)

    /**
     * A heading and the links under it. [note] is a line drawn under the heading, if any, and
     * [tmdbLogo] asks for TMDB's logo beside it ([TmdbMark]), as TMDB's terms require.
     */
    data class Group(
        val title: String,
        val links: List<Link>,
        val note: String? = null,
        val tmdbLogo: Boolean = false,
        /** The support group, which each screen gives its own QR row. */
        val support: Boolean = false,
    )

    /** The one link every screen offers: the support page, which lists both donate links. */
    fun supportLinks(from: String): List<Link> =
        listOf(Link(L.aboutSupportPage, L.aboutSupportPageDetail, supportUrl(from)))

    /** The heading once the viewer has said they support the project, or finished the last ask. */
    val SUPPORT_THANKS_TITLE: String get() = L.aboutSupportThanksTitle
    val SUPPORT_THANKS_NOTE: String get() = L.aboutSupportThanksNote

    /**
     * The support card's words for [rung] (1 to 3): rare, short, voluntary, and honest that nothing
     * is held back. The first rung says how long the viewer has watched. See [SupportReminder] for
     * when it appears, and for [SupportReminder.COPY_VARIANT], which of the two wordings is shown.
     */
    fun reminderTitle(rung: Int, variant: String = SupportReminder.COPY_VARIANT): String = when (rung to variant) {
        1 to "b" -> L.aboutReminder1TitleB
        1 to "a" -> L.aboutReminder1TitleA
        2 to "b" -> L.aboutReminder2TitleB
        2 to "a" -> L.aboutReminder2TitleA
        3 to "b" -> L.aboutReminder3TitleB
        else -> L.aboutReminder3TitleA
    }

    fun reminderText(
        rung: Int,
        counters: SupportReminder.Counters,
        variant: String = SupportReminder.COPY_VARIANT,
    ): String = when (rung to variant) {
        1 to "b" -> L.aboutReminder1TextB(counters.completedWatches.coerceAtLeast(1))
        1 to "a" -> L.aboutReminder1TextA(SupportReminder.hoursWatched(counters))
        2 to "b" -> L.aboutReminder2TextB
        2 to "a" -> L.aboutReminder2TextA
        3 to "b" -> L.aboutReminder3TextB
        else -> L.aboutReminder3TextA
    }

    /**
     * Where the corresponding source for this build is: the release it came from, whose notes link
     * the NextLib and mediamp archives. A development build has no release, so it gets the list.
     */
    fun releaseUrl(version: String): String =
        if (Regex("""\d+\.\d+\.\d+""").matches(version)) "$SOURCE/releases/tag/v$version" else "$SOURCE/releases"

    /**
     * Every link About offers, in order. The third-party notices are not among them: those are read
     * inside the app, so each screen puts that row first under "Licence" itself. With
     * [SupportReminder.enabled] off (the future Play build), the support group is left out. The
     * online extras group is the credit OpenSubtitles' terms ask for, shown in every build that
     * carries the feature ([OnlineSubtitles.available]). The
     * posters and details group is the attribution TMDB, TVmaze and AniList ask for, in every build
     * that has the feature ([OnlineMetadata.current]), whether or not the viewer turned it on.
     */
    fun groups(
        version: String,
        support: Boolean = SupportReminder.enabled,
        online: Boolean = OnlineSubtitles.available,
        metadata: Boolean = OnlineMetadata.current != null,
        supporter: Boolean = false,
    ): List<Group> = listOfNotNull(
        Group(
            L.aboutLicence,
            listOf(
                Link(L.aboutSourceCode, L.aboutSourceCodeDetail, SOURCE),
                Link(
                    L.aboutCorrespondingSource,
                    L.aboutCorrespondingSourceDetail,
                    releaseUrl(version),
                ),
            ),
        ),
        Group(
            L.aboutDataAndLaw,
            listOf(
                Link(L.aboutPrivacy, L.aboutPrivacyDetail, PRIVACY),
                Link(L.aboutLawfulUse, L.aboutLawfulUseDetail, LEGAL),
            ),
        ),
        Group(
            L.aboutContact,
            listOf(
                Link(L.aboutEmail, EMAIL, "mailto:$EMAIL"),
                Link(L.aboutTelegramChannel, L.aboutTelegramChannelDetail, "https://t.me/tmplayerapp"),
                Link(L.aboutTelegramGroup, L.aboutTelegramGroupDetail, "https://t.me/tmplayer_chat"),
                Link("GitHub Discussions", L.aboutDiscussionsDetail, "$SOURCE/discussions"),
                // Moved here (and to Settings' language picker) from the tour's first page.
                Link(L.onboardingHelpTranslate, L.onboardingHelpTranslateBody, com.tmplayer.ui.onboarding.Onboarding.TRANSLATE_URL),
            ),
        ),
        if (online) {
            Group(
                L.onlineAboutGroup,
                listOf(
                    Link("OpenSubtitles.com", L.onlineAboutDetail, OPENSUBTITLES),
                    Link("SubDL", L.onlineAboutSubdlDetail, SUBDL),
                ),
            )
        } else {
            null
        },
        if (metadata) {
            Group(
                L.metadataAboutGroup,
                listOf(
                    Link("TMDB", L.metadataAboutTmdbDetail, TMDB),
                    Link("TVmaze", L.metadataAboutTvmazeDetail, TVMAZE),
                    Link("AniList", L.metadataAboutAnilistDetail, ANILIST),
                ),
                note = L.metadataAboutNotice,
                tmdbLogo = true,
            )
        } else {
            null
        },
        if (support) {
            Group(
                if (supporter) SUPPORT_THANKS_TITLE else SUPPORT_TITLE,
                supportLinks("about"),
                note = if (supporter) SUPPORT_THANKS_NOTE else SUPPORT_NOTE,
                support = true,
            )
        } else {
            null
        },
    )

    /** One piece of the notices: a heading, a paragraph or a bullet point. */
    sealed interface Block {
        val text: String

        data class Heading(val level: Int, override val text: String) : Block
        data class Paragraph(override val text: String) : Block
        data class Bullet(override val text: String) : Block
    }

    /** THIRD_PARTY_NOTICES.md as this build carries it, for reading offline. */
    val notices: List<Block> by lazy { readable(NoticesSource.TEXT) }

    /** The notices under a screen whose own title already says "Third-party notices". */
    val noticesBody: List<Block> by lazy { notices.filterNot { it is Block.Heading && it.level == 1 } }

    /**
     * Markdown turned into blocks a screen can draw without a Markdown renderer: wrapped lines
     * joined, links reduced to their words, angle brackets and code ticks dropped, and each table
     * row spelled out as one bullet ("mpv (libmpv). Windows x64: 0.41.0. Linux x64: ...").
     */
    fun readable(markdown: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val pending = StringBuilder()
        var bullet = false
        var header: List<String>? = null

        fun flush() {
            if (pending.isNotEmpty()) {
                val text = inline(pending.toString())
                blocks += if (bullet) Block.Bullet(text) else Block.Paragraph(text)
            }
            pending.clear()
            bullet = false
        }

        for (raw in markdown.lines()) {
            val line = raw.trimEnd()
            when {
                line.isBlank() -> {
                    flush()
                    header = null
                }
                line.startsWith("#") -> {
                    flush()
                    val level = line.takeWhile { it == '#' }.length
                    blocks += Block.Heading(level, inline(line.drop(level).trim()))
                }
                line.startsWith("|") -> {
                    flush()
                    val cells = line.trim('|').split('|').map { inline(it.trim()) }
                    when {
                        header == null -> header = cells
                        cells.all { cell -> cell.all { it == '-' || it == ':' } } -> Unit
                        else -> {
                            val names = header.orEmpty()
                            val rest = cells.drop(1).mapIndexed { i, cell ->
                                val name = names.getOrNull(i + 1)
                                if (name.isNullOrBlank()) cell else "$name: $cell"
                            }
                            blocks += Block.Bullet((listOf(cells.first()) + rest).joinToString(". "))
                        }
                    }
                }
                line.startsWith("- ") -> {
                    flush()
                    bullet = true
                    pending.append(line.removePrefix("- "))
                }
                else -> {
                    if (pending.isNotEmpty()) pending.append(' ')
                    pending.append(line.trim())
                }
            }
        }
        flush()
        return blocks
    }

    private val LINK = Regex("""\[([^\]]+)]\([^)]+\)""")
    private val ANGLE = Regex("""<((?:https?://|mailto:)[^>]+)>""")

    private fun inline(text: String): String = text
        .replace(LINK, "$1")
        .replace(ANGLE, "$1")
        .replace("`", "")
        .replace("**", "")
}
