package com.tmplayer.i18n

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Android's per-app language list offers exactly the languages the app ships. */
class LocalesConfigTest {

    @Test
    fun `locales_config lists every shipped language in order`() {
        val xml = File("../app/src/main/res/xml/locales_config.xml").readText()
        val listed = Regex("""<locale android:name="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals(Languages.tags, listed)
    }
}
