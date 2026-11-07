package com.tmplayer.online

import com.tmplayer.data.EpisodeSteps
import com.tmplayer.data.EpisodeTag
import com.tmplayer.data.MediaItem

/**
 * The player's episode labels with the providers' episode names in them, where online lookups
 * are on and the provider knows the episode. Nothing changes while they are off: the names the
 * uploader wrote stay, and a label without one stays a bare code.
 */
object EpisodeNames {

    /**
     * [steps] with each episode's name from [online], the playing one ([playing]) asked first. A
     * lookup that fails or finds nothing leaves that label as it was.
     */
    suspend fun named(steps: EpisodeSteps, playing: MediaItem?, online: OnlineMetadata?): EpisodeSteps {
        if (online == null || !online.settings.value.enabled) return steps
        return steps.copy(
            current = steps.current?.let { named(it, playing, online) },
            previousTag = steps.previousTag?.let { named(it, steps.previous, online) },
            nextTag = steps.nextTag?.let { named(it, steps.next, online) },
        )
    }

    /** [tag] with the provider's name for the episode, else as it was. */
    suspend fun named(tag: EpisodeTag, item: MediaItem?, online: OnlineMetadata): EpisodeTag {
        val name = runCatching { (online.lookup(queryFor(tag, item)) as? MetaResult.Found)?.info?.episode }
            .getOrNull()
            ?.takeIf { it.season == tag.season && it.number == tag.episode }
            ?.name
            ?.takeIf { it.isNotBlank() }
            ?: return tag
        return tag.copy(name = name)
    }

    /**
     * Episode [number] of [season] in the provider's list for that season ([listed], as
     * [OnlineMetadata.season] answers it): its still and overview for the episode rows. Null when
     * the provider does not list it.
     */
    fun listed(season: Int, number: Int, listed: List<MetaEpisode>?): MetaEpisode? =
        listed?.firstOrNull { it.season == season && it.number == number }

    /**
     * The name a series list gives episode [number] of [season], from the provider's list for that
     * season ([listed], as [OnlineMetadata.season] answers it), or null when the provider has none
     * worth showing: then the row keeps its own "Episode 5". A placeholder the provider writes for
     * an episode nobody has named yet ("Episode 5", "Épisode 5", "الحلقة 5") is not a name.
     */
    fun listName(season: Int, number: Int, listed: List<MetaEpisode>?): String? =
        listed
            ?.firstOrNull { it.season == season && it.number == number }
            ?.name
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !placeholder(it, number) }

    /**
     * Whether [name] only numbers the episode: [number] and at most one short word round it,
     * in any script. "Chapter 3" and "3" are; "The 4th Floor" and "Pilot" are not.
     */
    fun placeholder(name: String, number: Int): Boolean {
        val digits = DIGITS.findAll(name).map { it.value.toIntOrNull() }.toList()
        if (digits.isEmpty()) return false
        if (digits.any { it != number }) return false
        val words = name.replace(DIGITS, " ").split(SEPARATORS).filter { it.isNotBlank() }
        // One word, or a few single characters as Chinese writes it: "第 5 集".
        return (words.size <= 1 || words.all { it.length == 1 }) && words.sumOf { it.length } <= PLACEHOLDER_WORD
    }

    private val DIGITS = Regex("""\p{Nd}+""")
    private val SEPARATORS = Regex("""[\s\p{P}\p{S}]+""")

    /** The longest word a placeholder carries: "エピソード", "Episódio", "Episodio". */
    private const val PLACEHOLDER_WORD = 10

    /**
     * The question for one episode: the one the detail panel asks for the same file where that
     * names the same episode, so the answer is shared, else one built from the series view's own
     * numbering, which covers a bare "E5" the file name alone does not give away.
     */
    private fun queryFor(tag: EpisodeTag, item: MediaItem?): MetaQuery =
        item?.let { com.tmplayer.data.VideoCorrections.queryOf(it) }
            ?.takeIf { it.wantsEpisode && (it.season ?: 1) == tag.season && it.episode == tag.episode }
            ?: MetaQuery(title = tag.show, season = tag.season, episode = tag.episode)
}
