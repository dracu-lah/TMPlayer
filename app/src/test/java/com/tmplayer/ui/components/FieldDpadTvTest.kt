package com.tmplayer.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.tmplayer.data.Country
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.auth.COUNTRY_SEARCH_TAG
import com.tmplayer.ui.auth.CountryPicker
import com.tmplayer.ui.browse.LIST_NAME_TAG
import com.tmplayer.ui.browse.ListNameField
import com.tmplayer.ui.connect.ManualProxy
import com.tmplayer.ui.connect.PROXY_FIELD_TAG
import com.tmplayer.ui.connect.PROXY_LINK_TAG
import com.tmplayer.ui.connect.ProxyLinkField
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

/**
 * The remote on a television leaves a one line text field with the arrows (Raju pass 2 left this
 * open for the shared text fields): the proxy link and its by-hand fields, the new list's name and
 * the country picker's search. A one line field keeps the D-pad for its cursor, so without
 * [dpadLeavesFieldOnTv] Up and Down went nowhere once the keyboard was down.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class FieldDpadTvTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() = FormFactor.override(false)

    private fun tv(content: @androidx.compose.runtime.Composable () -> Unit) {
        FormFactor.override(true)
        rule.setContent { TmMaterialTheme(dark = true) { content() } }
        val root = rule.activity.window.decorView.parent
        ReflectionHelpers.callInstanceMethod<Any>(root, "ensureTouchMode", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false))
        ReflectionHelpers.callInstanceMethod<Any>(root, "windowFocusChanged", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
        rule.waitForIdle()
    }

    private fun press(tag: String, key: Key) {
        rule.onNodeWithTag(tag, useUnmergedTree = true).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    @Test
    fun `the proxy link box lets the remote down to the button under it`() {
        tv {
            Column(Modifier.fillMaxSize()) {
                ProxyLinkField(link = "", onChange = {}, error = null, onSubmit = {}, s = LocalStrings.current)
                Text("Add", Modifier.testTag("below").clickable {})
            }
        }
        rule.onNodeWithTag(PROXY_LINK_TAG).requestFocus()
        rule.onNodeWithTag(PROXY_LINK_TAG).assertIsFocused()
        press(PROXY_LINK_TAG, Key.DirectionDown)
        rule.onNodeWithTag("below").assertIsFocused()
        press("below", Key.DirectionUp)
        rule.onNodeWithTag(PROXY_LINK_TAG).assertIsFocused()
    }

    @Test
    fun `the by hand proxy fields hand the remote on, one to the next and back`() {
        tv {
            Column(Modifier.fillMaxSize()) { ManualProxy(busy = false, onAdd = {}, s = LocalStrings.current) }
        }
        val server = PROXY_FIELD_TAG + com.tmplayer.i18n.Translator.messages.proxyServer
        val port = PROXY_FIELD_TAG + com.tmplayer.i18n.Translator.messages.proxyPort
        rule.onNodeWithTag(server).requestFocus()
        rule.onNodeWithTag(server).assertIsFocused()
        press(server, Key.DirectionDown)
        rule.onNodeWithTag(port).assertIsFocused()
        press(port, Key.DirectionUp)
        rule.onNodeWithTag(server).assertIsFocused()
    }

    @Test
    fun `the new list's name box lets the remote down to the next control`() {
        tv {
            Column(Modifier.fillMaxSize()) {
                var name by remember { mutableStateOf("") }
                ListNameField(text = name, onChange = { name = it }, onDone = {})
                Text("Save", Modifier.testTag("below").clickable {})
            }
        }
        rule.onNodeWithTag(LIST_NAME_TAG).requestFocus()
        rule.onNodeWithTag(LIST_NAME_TAG).assertIsFocused()
        press(LIST_NAME_TAG, Key.DirectionDown)
        rule.onNodeWithTag("below").assertIsFocused()
    }

    @Test
    fun `the country search box lets the remote down to the countries and back up`() {
        val countries = listOf(Country("GB", "United Kingdom", listOf("44")), Country("IN", "India", listOf("91")))
        tv { CountryPicker(countries = countries, onPick = {}, onDismiss = {}) }
        rule.onNodeWithTag(COUNTRY_SEARCH_TAG).requestFocus()
        rule.onNodeWithTag(COUNTRY_SEARCH_TAG).assertIsFocused()
        press(COUNTRY_SEARCH_TAG, Key.DirectionDown)
        rule.onNode(isFocused() and hasText("United Kingdom", substring = true)).assertExists()
    }

    @Test
    fun `on a phone the arrows stay with the field's cursor`() {
        FormFactor.override(false)
        rule.setContent {
            TmMaterialTheme(dark = true) {
                Column(Modifier.fillMaxSize()) {
                    var text by remember { mutableStateOf("abc") }
                    ListNameField(text = text, onChange = { text = it }, onDone = {})
                    BasicTextField(value = "", onValueChange = {}, modifier = Modifier.testTag("other"))
                }
            }
        }
        rule.onNodeWithTag(LIST_NAME_TAG).requestFocus()
        rule.waitForIdle()
        // Left is the cursor's on a phone or desktop keyboard: the field keeps the focus.
        press(LIST_NAME_TAG, Key.DirectionLeft)
        rule.onNodeWithTag(LIST_NAME_TAG).assertIsFocused()
    }
}
