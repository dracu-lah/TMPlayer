package com.tmplayer.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryTrimTest {

    private val levels = listOf(
        MemoryTrim.RUNNING_MODERATE, MemoryTrim.RUNNING_LOW, MemoryTrim.RUNNING_CRITICAL, MemoryTrim.UI_HIDDEN,
        MemoryTrim.BACKGROUND, MemoryTrim.MODERATE, MemoryTrim.COMPLETE,
    )

    @Test
    fun aNormalDeviceTrimsAsBefore() {
        for (level in levels) {
            val plan = MemoryTrim.of(level, lowEnd = false)
            val all = level == MemoryTrim.RUNNING_CRITICAL || level >= MemoryTrim.MODERATE
            assertEquals(if (all) MemoryTrim.Pictures.All else MemoryTrim.Pictures.Half, plan.pictures, "level $level")
            assertFalse(plan.dropIdleQueries, "level $level")
        }
    }

    @Test
    fun aLowEndDeviceDropsThePicturesAndIdleAnswersFromRunningLowUp() {
        assertEquals(MemoryTrim.Plan(MemoryTrim.Pictures.Half, false), MemoryTrim.of(MemoryTrim.RUNNING_MODERATE, lowEnd = true))
        for (level in levels.filter { it >= MemoryTrim.RUNNING_LOW }) {
            assertEquals(MemoryTrim.Plan(MemoryTrim.Pictures.All, true), MemoryTrim.of(level, lowEnd = true), "level $level")
        }
        // A level Android may add later, or garbage, never throws and errs towards giving back.
        assertEquals(MemoryTrim.Pictures.All, MemoryTrim.of(Int.MAX_VALUE, lowEnd = false).pictures)
        assertEquals(MemoryTrim.Pictures.Half, MemoryTrim.of(-1, lowEnd = true).pictures)
    }

    @Test
    fun idleAnswersGoAndTheOnesOnScreenStay() {
        var now = 1_000_000L
        val cache = QueryCache(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), clock = { now })
        val old = QueryKey.ChatMedia(1)
        val shown = QueryKey.ChatMedia(2)
        cache.put(old, "old")
        now += 5 * 60_000L
        cache.put(shown, "shown")
        now += 10_000L
        assertEquals(1, cache.dropIdle(MemoryTrim.IDLE_QUERY_MS))
        assertNull(cache.data<String>(old))
        assertEquals("shown", cache.data<String>(shown))
        assertEquals(0, cache.dropIdle(MemoryTrim.IDLE_QUERY_MS), "nothing more to drop")
        assertTrue(cache.size == 1)
    }
}
