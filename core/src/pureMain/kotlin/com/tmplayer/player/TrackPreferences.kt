package com.tmplayer.player

import com.tmplayer.i18n.PlatformText
import com.tmplayer.platform.textCollator
import com.tmplayer.data.ResumeState
import com.tmplayer.data.TrackChoice
import com.tmplayer.i18n.L
import com.tmplayer.i18n.Languages
import com.tmplayer.i18n.TrackCodes
import com.tmplayer.i18n.Translator

/**
 * When subtitles come on by themselves, for a video nothing has been chosen for yet.
 *
 * [OffUnlessChosen] still lets a forced track through, the few lines a film subtitles on purpose
 * (a sign, a scene in another language): those are part of the film rather than a choice.
 */
enum class SubtitleMode(val key: String, private val labelText: () -> String, private val detailText: () -> String) {
    /**
     * The default, and what the app did before these settings: a track the file marks as its
     * default comes on, which most anime and film releases rely on, and nothing else does.
     */
    AsTheVideoSays("file", { L.settingsSubtitleModeFile }, { L.settingsSubtitleModeFileDetail }),
    OffUnlessChosen("off", { L.settingsSubtitleModeOff }, { L.settingsSubtitleModeOffDetail }),
    Always("always", { L.settingsSubtitleModeAlways }, { L.settingsSubtitleModeAlwaysDetail }),
    ForeignAudio("foreign", { L.settingsSubtitleModeForeign }, { L.settingsSubtitleModeForeignDetail }),
    ;

    val label: String get() = labelText()
    val detail: String get() = detailText()

    companion object {
        fun from(key: String?): SubtitleMode = entries.firstOrNull { it.key == key } ?: AsTheVideoSays
    }
}

/**
 * The app-wide languages from Settings: what a video opens with when neither the file's own saved
 * tracks nor the series it belongs to say otherwise.
 *
 * Each of the two is an ordered list, the first match winning: the file's soundtrack or subtitle in
 * the first language of the list that it has. [audioLanguage] is the head of the audio list and
 * [audioMore] the rest (the same for subtitles), so a list of one is exactly the single choice
 * these settings were before, stored the same way: a code, or codes joined with a comma
 * ([LanguageOrder]). An old stored code therefore is a list of one, and nothing needs migrating.
 *
 * Languages are kept normalised ([TrackLanguages.normalise]) and never twice; an empty list is
 * "Automatic".
 */
data class TrackPreferences(
    val audioLanguage: String? = null,
    val subtitleLanguage: String? = null,
    /** "Off" picked as the subtitle language: never on by themselves, whatever [mode] says. */
    val subtitlesOff: Boolean = false,
    val mode: SubtitleMode = SubtitleMode.AsTheVideoSays,
    /** The audio languages after [audioLanguage], in order. */
    val audioMore: List<String> = emptyList(),
    /** The subtitle languages after [subtitleLanguage], in order. */
    val subtitleMore: List<String> = emptyList(),
) {
    /** The audio languages, in order; empty for Automatic. */
    val audioLanguages: List<String> get() = listOfNotNull(audioLanguage) + audioMore

    /** The subtitle languages, in order; empty for Automatic and for Off. */
    val subtitleLanguages: List<String> get() = listOfNotNull(subtitleLanguage) + subtitleMore

    /**
     * The first subtitle language as the stored key says it: empty for Automatic, [OFF], or a code.
     * What a single language is wanted for (the external player's subtitle file) reads this.
     */
    val subtitleKey: String get() = if (subtitlesOff) OFF else subtitleLanguage.orEmpty()

    /** The stored form of the audio row: the codes joined with a comma, empty for Automatic. */
    val audioStored: String get() = LanguageOrder.store(audioLanguages)

    /** The stored form of the subtitle row: [OFF], the codes joined with a comma, or empty for Automatic. */
    val subtitleStored: String get() = if (subtitlesOff) OFF else LanguageOrder.store(subtitleLanguages)

    /** These preferences with the audio languages set to [languages] (cleaned: normalised, never twice, at most [LanguageOrder.MAX]). */
    fun withAudioLanguages(languages: List<String>): TrackPreferences {
        val list = LanguageOrder.clean(languages)
        return copy(audioLanguage = list.firstOrNull(), audioMore = list.drop(1))
    }

    /** These preferences with the subtitle languages set to [languages]; a list turns Off off. */
    fun withSubtitleLanguages(languages: List<String>): TrackPreferences {
        val list = LanguageOrder.clean(languages)
        return copy(subtitleLanguage = list.firstOrNull(), subtitleMore = list.drop(1), subtitlesOff = false)
    }

    /** What the audio row says is set, on every platform. */
    val audioSummary: String
        get() = audioLanguages.takeIf { it.isNotEmpty() }?.joinToString(", ", transform = TrackLanguages::name) ?: L.settingsAudioLanguageAuto

    /** What the subtitle language row says is set. */
    val subtitleSummary: String
        get() = when {
            subtitlesOff -> L.settingsSubtitleLanguageOff
            subtitleLanguages.isNotEmpty() -> subtitleLanguages.joinToString(", ", transform = TrackLanguages::name)
            else -> L.settingsSubtitleLanguageAuto
        }

    companion object {
        const val OFF = "off"

        /** Reads the three stored values back, anything unreadable (an entry or the whole value) falling back to Automatic. */
        fun from(audio: String?, subtitle: String?, mode: String?): TrackPreferences {
            val audioList = LanguageOrder.parse(audio)
            val subtitleList = if (subtitle == OFF) emptyList() else LanguageOrder.parse(subtitle)
            return TrackPreferences(
                audioLanguage = audioList.firstOrNull(),
                audioMore = audioList.drop(1),
                subtitleLanguage = subtitleList.firstOrNull(),
                subtitleMore = subtitleList.drop(1),
                subtitlesOff = subtitle == OFF,
                mode = SubtitleMode.from(mode),
            )
        }
    }
}

/**
 * The ordered language lists of [TrackPreferences], as one pure set of rules every platform edits
 * them with: add (a language already there is refused), remove, move one place, and the stored form
 * (codes joined with a comma, so a list of one is the single code it always was).
 */
object LanguageOrder {
    /** More than every language the pickers offer, so the cap only ever stops a damaged value. */
    const val MAX = 50

    /** [codes] normalised, with the undetermined and the unreadable skipped, each language once, at most [MAX]. */
    fun clean(codes: List<String?>): List<String> =
        codes.mapNotNull(TrackLanguages::normalise).distinct().take(MAX)

    /** A stored value as a list: entries split on a comma, garbage skipped, a missing or absurdly long value empty. */
    fun parse(stored: String?): List<String> {
        val text = stored?.takeIf { it.length <= MAX * 16 } ?: return emptyList()
        return clean(text.split(','))
    }

    fun store(languages: List<String>): String = languages.joinToString(",")

    /** [language] at the end, or the list as it was when it is already there, unreadable, or the list is full. */
    fun add(list: List<String>, language: String): List<String> {
        val code = TrackLanguages.normalise(language) ?: return list
        return if (code in list || list.size >= MAX) list else list + code
    }

    fun remove(list: List<String>, language: String): List<String> = list.filter { it != language }

    /** [language] moved [by] places (negative is towards the front), held inside the list. */
    fun move(list: List<String>, language: String, by: Int): List<String> {
        val from = list.indexOf(language)
        if (from < 0 || by == 0) return list
        val to = (from + by).coerceIn(0, list.lastIndex)
        if (to == from) return list
        return list.toMutableList().also { it.add(to, it.removeAt(from)) }
    }
}

/** Which of the four places a choice came from, highest first. */
enum class TrackSource { SavedFile, Series, Settings, FileDefaults }

/** What to do about subtitles when a video opens. */
sealed interface SubtitlePlan {
    /** None, though a forced track may still come on when [allowForced]. */
    data class Off(val allowForced: Boolean = false) : SubtitlePlan

    /**
     * On, in [language] where the file has it. [fallBack] takes the file's default or first track
     * when it does not; without it the file's own pick stands.
     */
    data class On(val language: String?, val fallBack: Boolean = false, val more: List<String> = emptyList()) : SubtitlePlan {
        /** The languages to look for, first match winning. */
        val languages: List<String> get() = listOfNotNull(language) + more
    }

    /** On in [language] when the soundtrack is in none of [viewerLanguages], off otherwise. */
    data class IfForeignAudio(val language: String?, val viewerLanguages: Set<String>, val more: List<String> = emptyList()) : SubtitlePlan {
        /** The languages to look for, first match winning. */
        val languages: List<String> get() = listOfNotNull(language) + more
    }

    /** Whatever the file marks as its default. */
    data object FileDefault : SubtitlePlan
}

/** The languages a video opens with, and where each came from. */
data class TrackPlan(
    val audioLanguage: String?,
    val audioSource: TrackSource,
    val subtitles: SubtitlePlan,
    val subtitleSource: TrackSource,
    /** The audio languages after [audioLanguage], in order: only Settings has more than one. */
    val audioMore: List<String> = emptyList(),
) {
    /** The audio languages to look for, first match winning. */
    val audioLanguages: List<String> get() = listOfNotNull(audioLanguage) + audioMore
}

/** One subtitle track as the pick sees it. */
data class SubtitleTrackInfo(val language: String?, val isDefault: Boolean = false, val forced: Boolean = false)

/** The outcome of [TrackRules.pickSubtitle]. */
sealed interface SubtitlePick {
    /** Leave the player's own selection alone. */
    data object Leave : SubtitlePick
    data object None : SubtitlePick
    data class Track(val index: Int) : SubtitlePick
}

/**
 * The order a video's soundtrack and subtitles are decided in, the same on every platform: the
 * file's own saved tracks (a resume puts back exactly what was playing), then what its series was
 * last watched with, then the languages in Settings, then whatever the file marks as default.
 *
 * The soundtrack and the subtitles are decided apart: a series that only ever had its audio
 * remembered still takes its subtitles from Settings.
 */
object TrackRules {
    fun plan(resume: ResumeState?, series: TrackChoice, prefs: TrackPreferences, uiLanguage: String): TrackPlan {
        val (audio, audioSource) = when {
            resume?.audioTrack != null -> TrackLanguages.normalise(resume.audioLanguage) to TrackSource.SavedFile
            series.audioLanguage != null -> TrackLanguages.normalise(series.audioLanguage) to TrackSource.Series
            prefs.audioLanguage != null -> prefs.audioLanguage to TrackSource.Settings
            else -> null to TrackSource.FileDefaults
        }
        val (subtitles, subtitleSource) = when {
            resume?.subtitleTrack != null -> if (resume.subtitlesOff) {
                SubtitlePlan.Off() to TrackSource.SavedFile
            } else {
                SubtitlePlan.On(TrackLanguages.normalise(resume.subtitleLanguage)) to TrackSource.SavedFile
            }
            // "Off" is a real choice for a series (see [TrackChoice]), so a memory with only its
            // audio set still means the viewer watched it without subtitles.
            !series.empty -> if (series.subtitlesOn) {
                SubtitlePlan.On(TrackLanguages.normalise(series.textLanguage)) to TrackSource.Series
            } else {
                SubtitlePlan.Off() to TrackSource.Series
            }
            else -> settingsPlan(prefs, uiLanguage) to TrackSource.Settings
        }
        val audioMore = if (audioSource == TrackSource.Settings) prefs.audioMore else emptyList()
        return TrackPlan(audio, audioSource, subtitles, subtitleSource, audioMore)
    }

    private fun settingsPlan(prefs: TrackPreferences, uiLanguage: String): SubtitlePlan {
        if (prefs.subtitlesOff) return SubtitlePlan.Off()
        val wanted = prefs.subtitleLanguage ?: TrackLanguages.normalise(uiLanguage)
        // The viewer's own languages, in the order they chose them; the app's language when they chose none.
        val more = prefs.subtitleMore
        return when (prefs.mode) {
            SubtitleMode.AsTheVideoSays -> SubtitlePlan.FileDefault
            SubtitleMode.OffUnlessChosen -> SubtitlePlan.Off(allowForced = true)
            SubtitleMode.Always -> SubtitlePlan.On(wanted, fallBack = true, more = more)
            SubtitleMode.ForeignAudio -> SubtitlePlan.IfForeignAudio(
                wanted,
                (prefs.subtitleLanguages + listOfNotNull(TrackLanguages.normalise(uiLanguage))).toSet(),
                more,
            )
        }
    }

    /**
     * Which subtitle track [plan] wants among [tracks], once the player has listed them and
     * [audioLanguage] is what is playing.
     */
    fun pickSubtitle(plan: SubtitlePlan, audioLanguage: String?, tracks: List<SubtitleTrackInfo>): SubtitlePick {
        if (tracks.isEmpty()) return SubtitlePick.Leave
        return when (plan) {
            SubtitlePlan.FileDefault -> SubtitlePick.Leave
            is SubtitlePlan.Off -> if (plan.allowForced) forcedFor(audioLanguage, tracks) else SubtitlePick.None
            is SubtitlePlan.On -> inLanguages(plan.languages, tracks)?.let(SubtitlePick::Track)
                ?: if (plan.fallBack) fallBack(tracks) else SubtitlePick.Leave
            is SubtitlePlan.IfForeignAudio -> {
                val audio = TrackLanguages.normalise(audioLanguage)
                // A soundtrack with no language said is not known to be foreign, so it is left
                // without, as most home videos and lectures are.
                if (audio == null || audio in plan.viewerLanguages) {
                    forcedFor(audioLanguage, tracks)
                } else {
                    inLanguages(plan.languages, tracks)?.let(SubtitlePick::Track) ?: fallBack(tracks)
                }
            }
        }
    }

    /** The track in the first of [languages] that the file has: the viewer's order, first match wins. */
    private fun inLanguages(languages: List<String>, tracks: List<SubtitleTrackInfo>): Int? =
        languages.firstNotNullOfOrNull { inLanguage(it, tracks) }

    private fun inLanguage(language: String?, tracks: List<SubtitleTrackInfo>): Int? {
        language ?: return null
        val matching = tracks.withIndex().filter { TrackLanguages.same(it.value.language, language) }
        // A full track before the forced one in the same language, which carries only the signs.
        return (matching.firstOrNull { !it.value.forced } ?: matching.firstOrNull())?.index
    }

    private fun fallBack(tracks: List<SubtitleTrackInfo>): SubtitlePick {
        val at = tracks.indexOfFirst { it.isDefault && !it.forced }.takeIf { it >= 0 }
            ?: tracks.indexOfFirst { !it.forced }.takeIf { it >= 0 }
            ?: 0
        return SubtitlePick.Track(at)
    }

    private fun forcedFor(audioLanguage: String?, tracks: List<SubtitleTrackInfo>): SubtitlePick {
        val forced = tracks.withIndex().filter { it.value.forced }
        val pick = forced.firstOrNull { TrackLanguages.same(it.value.language, audioLanguage) }
            ?: forced.singleOrNull()?.takeIf { TrackLanguages.normalise(it.value.language) == null }
        return pick?.let { SubtitlePick.Track(it.index) } ?: SubtitlePick.None
    }
}

/**
 * Language codes as tracks carry them, made comparable.
 *
 * Containers write ISO 639-2 (`jpn`, `eng`, `mal`, and the bibliographic `ger` and `fre`), Media3
 * and Android write 639-1 (`ja`), subtitle files and a few muxers write a whole tag (`en-US`) or
 * even the English name. Everything is brought to the two letter code where one exists, the three
 * letter one otherwise, with the region dropped: a viewer who wants English subtitles wants them
 * whether the file says British or American.
 */
object TrackLanguages {
    private val UNDETERMINED = setOf("und", "mis", "mul", "zxx", "qaa", "xx")

    private val englishNames: Map<String, String> by lazy {
        PlatformText.englishLanguageNames()
    }

    /** The comparable code for [code], or null for none or for "undetermined". */
    fun normalise(code: String?): String? {
        val raw = code?.trim()?.replace('_', '-')?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val base = raw.substringBefore('-')
        if (base in UNDETERMINED) return null
        if (base.length > 3) return englishNames[raw] ?: englishNames[base]
        if (base.length < 2 || !base.all { it in 'a'..'z' }) return null
        return TrackCodes.toIso1(base)
    }

    fun same(a: String?, b: String?): Boolean {
        val left = normalise(a) ?: return false
        return left == normalise(b)
    }

    /**
     * Every spelling of [code] a file might use, for mpv's `alang` and `slang`, which compare the
     * strings as written: `de,deu,ger` for German.
     */
    fun spellings(code: String?): List<String> {
        val iso1 = normalise(code) ?: return emptyList()
        val three = if (iso1.length == 2) {
            PlatformText.iso3Of(iso1)?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
        return listOfNotNull(iso1, three, TrackCodes.bibliographicOf(iso1)).distinct()
    }

    /** Languages offered beyond the app's own, for what people watch with subtitles most. */
    private val COMMON = listOf(
        "en", "ja", "ko", "zh", "hi", "ml", "ta", "te", "kn", "mr", "bn", "ur", "es", "pt", "fr",
        "de", "it", "ru", "ar", "tr", "id", "th", "vi", "pl", "uk", "fa", "nl", "sv", "he", "el",
        "ms", "fil",
    )

    /**
     * The languages the Settings rows offer: the app's UI languages and the common ones, each
     * once, in the alphabetical order of their names in the UI language.
     */
    fun choices(): List<String> {
        val codes = (Languages.tags.mapNotNull(::normalise) + COMMON).distinct()
        val locale = Translator.messages.locale
        val collator = textCollator(locale)
        return codes.sortedWith { a, b -> collator.compare(name(a), name(b)) }
    }

    /** [code]'s name in the UI language: "Japanese", "Japonais", "ജാപ്പനീസ്". */
    fun name(code: String): String = Translator.messages.formatter.trackLanguage(code) ?: code
}
