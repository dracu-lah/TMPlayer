package com.tmplayer.ui.browse

import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The television. See [PromoCoverRender]. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PromoCoverTvRenderTest {
    private val render = PromoCoverRender()

    @After
    fun tearDown() = render.tearDown()

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-mdpi")
    fun advertIsNotDrawn() = render.check(tv = true, name = "tv")
}
