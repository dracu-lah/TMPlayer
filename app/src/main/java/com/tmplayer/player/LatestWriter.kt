package com.tmplayer.player

import com.tmplayer.data.RemedyMemory
import com.tmplayer.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Writes whole snapshots one at a time through one coroutine on [scope], so two saves made in
 * quick succession can never land out of order (a launch per save could). Snapshots offered while
 * a write runs are folded into the newest, which is the only one worth writing. A failed write is
 * dropped; the next snapshot carries everything anyway.
 */
class LatestWriter<T>(scope: CoroutineScope, private val write: suspend (T) -> Unit) {

    private val pending = Channel<T>(Channel.CONFLATED)

    init {
        scope.launch {
            for (value in pending) runCatching { write(value) }
        }
    }

    fun offer(value: T) {
        pending.trySend(value)
    }
}

/** The remedy ladder's memory, written through one [LatestWriter] for the whole app (RL6). */
object RemedyMemoryWrites {
    private val writer by lazy {
        LatestWriter<Pair<SettingsStore, RemedyMemory>>(com.tmplayer.App.backgroundScope) { (store, memory) -> store.setRemedyMemory(memory) }
    }

    fun save(store: SettingsStore, memory: RemedyMemory) = writer.offer(store to memory)
}
