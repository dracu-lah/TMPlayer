package com.tmplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The TV's home rows and Play next entries of a PIN locked profile (HX88): taken down on the first
 * value already (a cold start with the profile locked), and again each time it is hidden.
 */
class OutsideSurfacesTest {

    @Test
    fun `a cold start with a locked profile clears the launcher, and every lock after`() = runBlocking {
        val hidden = MutableStateFlow(true)
        val cleared = AtomicInteger(0)
        val job = OutsideSurfaces.follow(kotlinx.coroutines.CoroutineScope(Dispatchers.Default), hidden) { cleared.incrementAndGet() }
        withTimeout(5_000) { while (cleared.get() < 1) yield() }
        hidden.value = false
        // Seen as open before the next lock, as a real unlock in between would be.
        kotlinx.coroutines.delay(300)
        hidden.value = true
        withTimeout(5_000) { while (cleared.get() < 2) yield() }
        job.cancel()
        assertEquals(2, cleared.get())
    }

    @Test
    fun `an open profile leaves the launcher alone`() = runBlocking {
        val cleared = AtomicInteger(0)
        val job = OutsideSurfaces.follow(kotlinx.coroutines.CoroutineScope(Dispatchers.Default), MutableStateFlow(false)) { cleared.incrementAndGet() }
        kotlinx.coroutines.delay(200)
        job.cancel()
        assertEquals(0, cleared.get())
    }
}
