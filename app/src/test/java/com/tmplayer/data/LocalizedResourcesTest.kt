package com.tmplayer.data

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalizedResourcesTest {
    @Test fun `Spanish resources match English names and format arguments`() {
        val root = sequenceOf(File("src/main/res"), File("app/src/main/res"))
            .first { it.isDirectory }
        val english = read(root.resolve("values/strings.xml"))
        val spanish = read(root.resolve("values-es/strings.xml"))

        assertEquals(english.keys, spanish.keys)
        for (name in english.keys) {
            assertEquals("Format arguments differ for $name", placeholders(english.getValue(name)), placeholders(spanish.getValue(name)))
        }
        assertTrue(spanish.getValue("watched").contains("Visto"))
    }

    private fun read(file: File): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        return buildMap {
            val children = document.documentElement.childNodes
            for (index in 0 until children.length) {
                val node = children.item(index)
                val name = node.attributes?.getNamedItem("name")?.nodeValue ?: continue
                put(name, node.textContent)
            }
        }
    }

    private fun placeholders(value: String): List<String> =
        Regex("%(?:\\d+\\$)?[a-zA-Z]").findAll(value).map { it.value }.toList()
}
