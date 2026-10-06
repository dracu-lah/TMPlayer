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
     * The question for one episode: the one the detail panel asks for the same file where that
     * names the same episode, so the answer is shared, else one built from the series view's own
     * numbering, which covers a bare "E5" the file name alone does not give away.
     */
    private fun queryFor(tag: EpisodeTag, item: MediaItem?): MetaQuery =
        item?.let { MetaQuery.of(it.fileName.ifBlank { it.title }, it.caption) }
            ?.takeIf { it.wantsEpisode && (it.season ?: 1) == tag.season && it.episode == tag.episode }
            ?: MetaQuery(title = tag.show, season = tag.season, episode = tag.episode)
}
