package com.tmplayer.data

/**
 * The search box at the top of Settings on the phone and the desktop (U20): every word typed must
 * be found in a row's title, the lines under it or its page's name, and the rows whose title
 * answers best come first. A television has no box but a Find a setting button and panel (WP-09,
 * [findOnTv]): the walk to a row still takes up to a dozen presses.
 *
 * Pure, so the rules are table tested: the caller hands in the rows its device shows and [text],
 * which turns a catalog key into the words in the viewer's language.
 */
object SettingsSearch {

    /** The box shows once a device has more rows than this; fewer read faster as a list. */
    const val BOX_FROM_ROWS = 25

    /** Whether [form] gets the box with [rows] rows to look through. */
    fun offered(form: DeviceForm, rows: Int): Boolean = form != DeviceForm.Tv && rows > BOX_FROM_ROWS

    /**
     * Whether a television has "Find a setting" (WP-09): always, with any rows to look through. It is
     * a button in the header and a panel, never the phone's box over a list, and the same [match]
     * rules apply to the rows [form] shows (a touch-only row is never one of them).
     */
    fun findOnTv(rows: Int): Boolean = rows > 0

    /** The rows people come to Settings for, shown for an empty query on a television. */
    val COMMON_ROWS = listOf("language", "subtitle_style", "decoder", "sign_out", "back_up", "interface_size")

    /** [COMMON_ROWS] among [rows], in that order, as hits; the rows this device does not have are left out. */
    fun suggestions(rows: List<SettingsRow>, form: DeviceForm, text: (String) -> String): List<Hit> =
        COMMON_ROWS.mapNotNull { id -> rows.firstOrNull { it.id == id } }.map { row ->
            Hit(row, read(text, row.titleOn(form)), "", read(text, row.group.title))
        }

    /** Results past this many are not worth reading; a longer query narrows them. */
    const val MAX_HITS = 30

    /** One row found: its words as drawn, and its page's name to say where it lives. */
    data class Hit(val row: SettingsRow, val title: String, val detail: String, val page: String)

    /**
     * The rows of [rows] that match [query], best first; nothing for a blank query. [text] reads a
     * catalog key in the current language; [form] picks the desktop's wording where it has its own.
     */
    fun match(query: String, rows: List<SettingsRow>, form: DeviceForm, text: (String) -> String): List<Hit> {
        val words = words(query)
        if (words.isEmpty()) return emptyList()
        val phrase = words.joinToString(" ")
        return rows.asSequence()
            .mapIndexedNotNull { index, row ->
                val title = read(text, row.titleOn(form))
                val detail = row.details.asSequence().map { read(text, it) }.firstOrNull { it.isNotBlank() }.orEmpty()
                val page = read(text, row.group.title)
                val titleWords = words(title)
                val also = words(row.keywords?.let { read(text, it) }.orEmpty())
                val rest = words(row.details.joinToString(" ") { read(text, it) } + " " + page + " " + row.drawnAs.joinToString(" ") { read(text, it) })
                if (!words.all { word -> titleWords.any { it.startsWith(word) } || also.any { it.startsWith(word) } || rest.any { it.startsWith(word) } }) {
                    return@mapIndexedNotNull null
                }
                val titleText = titleWords.joinToString(" ")
                val rank = when {
                    titleText.startsWith(phrase) -> 0
                    words.all { word -> titleWords.any { it.startsWith(word) } } -> 1
                    titleText.contains(phrase) -> 2
                    // The words a person types for the row beat a line that happens to hold one.
                    words.all { word -> titleWords.any { it.startsWith(word) } || also.any { it.startsWith(word) } } -> 3
                    else -> 4
                }
                Triple(rank, index, Hit(row, title, detail, page))
            }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .map { it.third }
            .distinctBy { it.row.id }
            .take(MAX_HITS)
            .toList()
    }

    /** A message as words: placeholders an ICU message was read without are dropped. */
    private fun read(text: (String) -> String, key: String): String =
        runCatching { text(key) }.getOrDefault("").replace(PLACEHOLDER, " ").trim()

    /** Lower case words, split at anything that is not a letter or a digit, accents folded. */
    internal fun words(text: String): List<String> = buildList {
        val word = StringBuilder()
        for (ch in text) {
            val folded = fold(ch.lowercaseChar())
            if (folded.isLetterOrDigit()) {
                word.append(folded)
            } else if (ch == '\'' || ch == '’') {
                // "wallpaper's" is one word, as somebody types it.
                continue
            } else if (word.isNotEmpty()) {
                add(word.toString())
                word.clear()
            }
        }
        if (word.isNotEmpty()) add(word.toString())
    }

    private val PLACEHOLDER = Regex("""\{[^}]*\}""")

    /** The Latin accents a viewer leaves off when typing; other scripts are matched as they are. */
    private fun fold(ch: Char): Char = when (ch) {
        in "àáâãäåā" -> 'a'
        in "çćč" -> 'c'
        in "èéêëēę" -> 'e'
        in "ìíîïī" -> 'i'
        in "ñń" -> 'n'
        in "òóôõöøō" -> 'o'
        in "ùúûüū" -> 'u'
        in "ýÿ" -> 'y'
        in "śšș" -> 's'
        in "źżž" -> 'z'
        in "ł" -> 'l'
        in "ğ" -> 'g'
        in "ı" -> 'i'
        else -> ch
    }
}

/**
 * Every place outside Settings that sends the viewer to a row or a page of it (U20): a card's
 * button, Setup choices' "Also in", and the lines of help text that name where a setting is
 * ("Change it any time in Settings, Playback"). [from] is the catalog key or the code that links,
 * [group] and [row] where it must land; SettingsMapTest holds every one to a row that exists on
 * each of its [forms], so moving a row without moving its links fails there.
 */
enum class SettingsLink(val from: String, val group: SettingsGroup, val row: String?, val forms: Set<DeviceForm>) {
    /** Home's "every row is off" card, Open settings (HomeOffCard). */
    HomeOff("home.open_settings", SettingsGroup.Library, "home_carousel", setOf(DeviceForm.Phone, DeviceForm.Tv, DeviceForm.Desktop)),

    /** Setup choices, Posters: Also in Library. */
    SetupPosters("setup.choices_body", SettingsGroup.Library, "posters", setOf(DeviceForm.Phone, DeviceForm.Tv, DeviceForm.Desktop)),

    /** Setup choices, Storage: Also in Downloads and storage. */
    SetupStorage("setup.choices_body", SettingsGroup.Storage, "low_storage", setOf(DeviceForm.Phone, DeviceForm.Tv, DeviceForm.Desktop)),

    /** Setup choices, Performance: Also in Appearance (Lighter pictures). */
    SetupPerformance("setup.choices_body", SettingsGroup.Appearance, "lighter_pictures", setOf(DeviceForm.Phone, DeviceForm.Tv)),

    /** Setup choices, Mobile data: Also in Downloads and storage (a phone's row). */
    SetupMobileData("setup.choices_body", SettingsGroup.Storage, "mobile_data", setOf(DeviceForm.Phone)),

    /** The chooser's "Change it any time in Settings, Playback". */
    ChooserNote("external.chooser_note", SettingsGroup.Playback, "default_player", setOf(DeviceForm.Phone, DeviceForm.Tv)),

    /** "VLC plays every video on this device now. Settings, Playback changes it". */
    AlwaysVlc("player.engine_always_vlc_done", SettingsGroup.Playback, "player_engine", setOf(DeviceForm.Phone, DeviceForm.Tv)),

    /** The online subtitle notices: "Sign in under Settings, Online subtitles". */
    OnlineSignIn("online.notice_sign_in", SettingsGroup.Subtitles, "online_account", setOf(DeviceForm.Phone, DeviceForm.Tv, DeviceForm.Desktop)),

    /** "Check it under Settings, Online subtitles" for a SubDL key refused. */
    SubdlRefused("online.notice_subdl_refused", SettingsGroup.Subtitles, "subdl_key", setOf(DeviceForm.Phone, DeviceForm.Tv, DeviceForm.Desktop)),

    /** "Add your SubMaker address under Settings, Online subtitles" when none is set. */
    SubMakerOff("submaker.off", SettingsGroup.Subtitles, "submaker_base", setOf(DeviceForm.Phone, DeviceForm.Tv, DeviceForm.Desktop)),

    /** "SubMaker didn't accept your address. Check it under Settings, Online subtitles". */
    SubMakerRejected("submaker.rejected", SettingsGroup.Subtitles, "submaker_base", setOf(DeviceForm.Phone, DeviceForm.Tv, DeviceForm.Desktop)),

    /** The kids lock's empty Chats: "Choose them in Settings, App lock". */
    KidsEmpty("lock.kids_empty", SettingsGroup.Privacy, "allowed_chats", setOf(DeviceForm.Phone, DeviceForm.Tv, DeviceForm.Desktop)),

    /** "Posters are off. Turn them back on in Settings, Posters and details." */
    PostersOff("grid.meta_notice_turned_off", SettingsGroup.Library, "posters", setOf(DeviceForm.Phone, DeviceForm.Tv, DeviceForm.Desktop)),

    /** Not enough room to play: "choose another storage location in Settings". */
    NoSpace("player.no_space", SettingsGroup.Storage, "download_location", setOf(DeviceForm.Phone, DeviceForm.Tv, DeviceForm.Desktop)),

    /** "Wi-Fi only is on ... turn Wi-Fi only off in Settings" (a phone's row). */
    WifiOnly("player.mobile_data_never", SettingsGroup.Storage, "mobile_data", setOf(DeviceForm.Phone)),

    /** "Developer options are on. Save name check is in Settings, under Save logs." */
    DeveloperOn("logs.developer_on", SettingsGroup.Troubleshooting, "name_report", setOf(DeviceForm.Phone, DeviceForm.Tv, DeviceForm.Desktop)),

    /** The status corner's sync hints (unreadable file, newer file, other account): Open settings, Account (WP-100). */
    SyncHint("settings.resume_sync_unreadable", SettingsGroup.Accounts, "resume_sync", setOf(DeviceForm.Phone, DeviceForm.Tv, DeviceForm.Desktop)),

    /** The account menu's compact version row opens Settings, About (the build and the update source). */
    AccountVersion("settings.about", SettingsGroup.About, "about", setOf(DeviceForm.Phone, DeviceForm.Tv, DeviceForm.Desktop)),
    ;

    /** Where Setup choices' "Also in" goes is in the ui module (SetupStep lives there). */
    companion object
}
