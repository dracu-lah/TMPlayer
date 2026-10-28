// A show's Mark as watched (HX20), the web twin of core's ShowMarks: what the episodes list offers
// for the season shown and the whole show, each line naming how many episodes it changes.

export interface MarkEpisode {
    chatId: number
    messageId: number
}

export interface MarkSeason<E extends MarkEpisode> {
    number: number
    episodes: readonly E[]
}

export interface MarkChoice<E extends MarkEpisode> {
    scope: 'season' | 'show'
    watched: boolean
    /** The season's number for a season's line, else null. */
    season: number | null
    episodes: E[]
}

const key = (e: MarkEpisode) => `${e.chatId}:${e.messageId}`

/**
 * The lines for [season] of [seasons], in the order the apps offer them: the season first, then
 * the whole show when it has more than that season; each only when it would change something
 * (watched while some is unwatched, unwatched while some is watched).
 */
export function showChoices<E extends MarkEpisode>(seasons: readonly MarkSeason<E>[], season: MarkSeason<E> | undefined, watched: ReadonlySet<string>): MarkChoice<E>[] {
    const whole = seasons.flatMap((s) => [...s.episodes])
    const isWatched = (e: E) => watched.has(key(e))
    const wholeTodo = whole.filter((e) => !isWatched(e))
    const wholeDone = whole.filter(isWatched)
    const several = !season || seasons.length > 1
    const out: MarkChoice<E>[] = []
    if (season) {
        const todo = season.episodes.filter((e) => !isWatched(e))
        const done = season.episodes.filter(isWatched)
        if (todo.length) out.push({ scope: 'season', watched: true, season: season.number, episodes: todo })
        if (several && wholeTodo.length) out.push({ scope: 'show', watched: true, season: null, episodes: wholeTodo })
        if (done.length) out.push({ scope: 'season', watched: false, season: season.number, episodes: done })
        if (several && wholeDone.length) out.push({ scope: 'show', watched: false, season: null, episodes: wholeDone })
    } else {
        if (wholeTodo.length) out.push({ scope: 'show', watched: true, season: null, episodes: wholeTodo })
        if (wholeDone.length) out.push({ scope: 'show', watched: false, season: null, episodes: wholeDone })
    }
    return out
}
