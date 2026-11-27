// What the player keeps per video or for every video: the speed per series and chat, the volume
// boost, night mode and Even out volume.
import { resolveSpeed, sanitiseBoost, speedTarget, speedToKeep, type SpeedScopes } from '../player/extras'
import { read, remove, write } from './kv'

// Speed per series, chat and topic (SettingsStore.openingSpeed and rememberSpeed): a speed belongs to
// what the video is part of. Read and written only as numbers; anything else stored is as if none was.
const speedKey = (scope: string) => `speed:${scope}`

/** The speed a video opens at: the one kept for its series, else for its chat, else 1x. */
export async function openingSpeed(scopes: SpeedScopes): Promise<number> {
    const [series, chat] = await Promise.all([scopes.series ? read<unknown>(speedKey(scopes.series)) : undefined, scopes.chat ? read<unknown>(speedKey(scopes.chat)) : undefined])
    return resolveSpeed(series, chat)
}

/** Remembers a speed the viewer picked for the narrowest place the video belongs to; 1x forgets it there and in the chat under it. */
export function rememberSpeed(scopes: SpeedScopes, value: number) {
    const keep = speedToKeep(value)
    if (keep === null) {
        for (const scope of [scopes.series, scopes.chat]) if (scope) remove(speedKey(scope), true)
        return
    }
    const target = speedTarget(scopes)
    if (target) write(speedKey(target), keep)
}

/** Volume boost, kept for every video until it is turned off (the apps' volume_boost); 1 is off. */
const BOOST_KEY = 'player:boost'
export async function readBoost(): Promise<number> {
    return sanitiseBoost(await read<unknown>(BOOST_KEY))
}
export function writeBoost(level: number) {
    if (level > 1) write(BOOST_KEY, level)
    else remove(BOOST_KEY, true)
}

/** Night mode and Even out volume on the web, kept for every video until turned off (the apps' night_mode and even_volume). Off unless asked: routing every video through Web Audio is a cost a viewer chooses. */
const NIGHT_KEY = 'player:night'
const EVEN_KEY = 'player:even'
export async function readNight(): Promise<boolean> {
    return (await read<unknown>(NIGHT_KEY)) === true
}
export function writeNight(on: boolean) {
    if (on) write(NIGHT_KEY, true)
    else remove(NIGHT_KEY, true)
}
export async function readEven(): Promise<boolean> {
    return (await read<unknown>(EVEN_KEY)) === true
}
export function writeEven(on: boolean) {
    if (on) write(EVEN_KEY, true)
    else remove(EVEN_KEY, true)
}
