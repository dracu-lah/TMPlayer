package com.tmplayer.ui.browse

import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The phone on its side. See [PromoCoverRender]. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PromoCoverPhoneLandRenderTest {
    private val render = PromoCoverRender()

    @After
    fun tearDown() = render.tearDown()

    @Test
    @Config(sdk = [35], qualifiers = "w891dp-h411dp-land-night-xhdpi")
    fun advertIsNotDrawn() = render.check(tv = false, name = "phone-land")
}
