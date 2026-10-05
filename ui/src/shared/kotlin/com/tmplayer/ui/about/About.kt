package com.tmplayer.ui.about

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
    const val LICENCE = "Free software under the GNU GPL, version 3 or later"

    const val WARRANTY = "TMPlayer comes with no warranty, to the extent the law allows. You may " +
        "share and change it under the terms of the GPL, which the source code carries in full."

    const val SOURCE = "https://github.com/dracu-lah/TMPlayer"
    const val NOTICES_ONLINE = "$SOURCE/blob/main/THIRD_PARTY_NOTICES.md"
    const val PRIVACY = "https://tmplayer.org/privacy"
    const val LEGAL = "https://tmplayer.org/legal"
    const val EMAIL = "hello@tmplayer.org"

    /** A link and the sentence under it. */
    data class Link(val title: String, val detail: String, val url: String)

    /** A heading and the links under it. [note] is a line drawn under the heading, if any. */
    data class Group(val title: String, val links: List<Link>, val note: String? = null)

    /**
     * Where the corresponding source for this build is: the release it came from, whose notes link
     * the NextLib and mediamp archives. A development build has no release, so it gets the list.
     */
    fun releaseUrl(version: String): String =
        if (Regex("""\d+\.\d+\.\d+""").matches(version)) "$SOURCE/releases/tag/v$version" else "$SOURCE/releases"

    /**
     * Every link About offers, in order. The third-party notices are not among them: those are read
     * inside the app, so each screen puts that row first under "Licence" itself.
     */
    fun groups(version: String): List<Group> = listOf(
        Group(
            "Licence",
            listOf(
                Link("Source code", "Everything TMPlayer is built from, on GitHub", SOURCE),
                Link(
                    "Corresponding source",
                    "The NextLib and mediamp source archives, linked from this version's release notes",
                    releaseUrl(version),
                ),
            ),
        ),
        Group(
            "Your data and the law",
            listOf(
                Link("Privacy", "What stays on this device and which services TMPlayer contacts", PRIVACY),
                Link("Lawful use", "Use TMPlayer only with media you may access", LEGAL),
            ),
        ),
        Group(
            "Contact",
            listOf(
                Link("Email", EMAIL, "mailto:$EMAIL"),
                Link("Telegram channel", "News and every release, at t.me/tmplayerapp", "https://t.me/tmplayerapp"),
                Link("Telegram group", "Help and chat, at t.me/tmplayer_chat", "https://t.me/tmplayer_chat"),
                Link("GitHub Discussions", "Questions, ideas and bug reports", "$SOURCE/discussions"),
            ),
        ),
        Group(
            "Support TMPlayer",
            listOf(
                Link("GitHub Sponsors", "A monthly or one-off sponsorship", "https://github.com/sponsors/dracu-lah"),
                Link("Buy Me a Coffee", "A one-off coffee", "https://buymeacoffee.com/nevil.dev"),
            ),
            note = "Voluntary. It unlocks nothing: every feature is free for everyone.",
        ),
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
