package com.tmplayer.ui.profiles

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.tmplayer.data.FormFactor
import com.tmplayer.data.PickerModel
import com.tmplayer.data.ProfileTile
import com.tmplayer.data.TileStatus
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.lock.WhoIsWatching
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "Who's watching?" drawn on each form factor (HX88): a phone upright and on its side, a tablet and
 * a TV, at a 2.0 font and right to left. Every tile and the Add tile are there, nothing runs off the
 * side of the screen, and in RTL the row starts on the right.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfilePickerRenderTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private val model = PickerModel(
        tiles = listOf(
            ProfileTile("main", "Asha Raman", 1, null, locked = false, status = TileStatus.Ready, active = true),
            ProfileTile("p1", "Benedict with a very long name indeed", 2, null, locked = true, status = TileStatus.Ready, active = false),
            ProfileTile("p2", "", 3, null, locked = false, status = TileStatus.NeedsAttention, active = false),
        ),
        canAdd = false,
        atLimit = true,
    )

    private val withRoom = model.copy(tiles = model.tiles.take(2), canAdd = true, atLimit = false)

    private fun draw(picker: PickerModel, fontScale: Float = 1f, rtl: Boolean = false, tv: Boolean = false) {
        if (tv) FormFactor.override(true)
        rule.setContent {
            val base = LocalDensity.current
            // Inside the theme, which sets the direction from the app's language.
            TmMaterialTheme(dark = true) {
                CompositionLocalProvider(
                    LocalDensity provides Density(base.density, fontScale),
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    WhoIsWatching(model = picker, onOpen = {}, onAdd = {}, onOptions = {})
                }
            }
        }
        rule.waitForIdle()
    }

    /** Every tile is drawn and none is wider than the screen or starts off it. */
    private fun assertAllInside(picker: PickerModel) {
        val root = rule.onRoot().getBoundsInRoot()
        val names = picker.tiles.map { it.name.ifBlank { L.profilesUnnamed(number = it.number) } }
        for (name in names) {
            val box = rule.onNodeWithContentDescription(name, substring = true).getBoundsInRoot()
            assertTrue("$name starts off the screen", box.left >= root.left)
            assertTrue("$name runs off the screen", box.right <= root.right)
        }
        if (picker.canAdd) rule.onNodeWithText(L.profilesAdd).assertExists()
        if (picker.atLimit) rule.onNodeWithText(L.profilesLimit(count = 3)).assertExists()
        rule.onNodeWithText(L.profilesWhoIsWatching).assertExists()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h640dp-port-night-xhdpi")
    fun `phone upright, three profiles at the limit`() {
        draw(model)
        assertAllInside(model)
        rule.onNodeWithText(L.profilesNeedsAttention).assertExists()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h640dp-port-night-xhdpi")
    fun `phone upright at a 2 point 0 font`() {
        draw(withRoom, fontScale = 2f)
        assertAllInside(withRoom)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w640dp-h360dp-land-night-xhdpi")
    fun `phone on its side`() {
        draw(withRoom)
        assertAllInside(withRoom)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w800dp-h1280dp-port-night-xhdpi")
    fun `tablet`() {
        draw(model)
        assertAllInside(model)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `TV at a 2 point 0 font`() {
        draw(withRoom, fontScale = 2f, tv = true)
        assertAllInside(withRoom)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w640dp-h360dp-land-night-xhdpi")
    fun `right to left, the row starts on the right`() {
        draw(withRoom, rtl = true)
        assertAllInside(withRoom)
        val first = rule.onNodeWithContentDescription("Asha", substring = true).getBoundsInRoot()
        val second = rule.onNodeWithContentDescription("Benedict", substring = true).getBoundsInRoot()
        assertTrue("the first profile is on the right in RTL", first.left > second.left)
    }
}
