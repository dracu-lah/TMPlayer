package com.tmplayer.cast.dlna

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The one way DLNA code reads XML that came off the network: a TV's description, a SOAP answer, a
 * DIDL-Lite string. Nothing a renderer sends is trusted (HX10, HX40):
 *  - A document with a DOCTYPE or an ENTITY is refused before any parser sees it. UPnP has no use
 *    for either, and they are how an XML bomb or an external entity read (XXE) gets in. This runs
 *    first because Android's parser does not honour every secure feature flag.
 *  - The text is capped ([DESCRIPTION_MAX], [SOAP_MAX]) before parsing, so a huge answer costs a
 *    length check, not memory.
 *  - Where the platform's parser knows them, the secure processing and no-external-entity features
 *    are switched on as well; a platform that does not know one is not a failure.
 * A document that does not parse is `null`, never an exception.
 */
internal object Xml {

    /** A device description with its service list: a few KB in practice. */
    const val DESCRIPTION_MAX = 256 * 1024

    /** A SOAP answer; the longest real one is a Sink list of 10 to 30 KB. */
    const val SOAP_MAX = 64 * 1024

    /** A DIDL-Lite string inside an answer or a request. */
    const val DIDL_MAX = 64 * 1024

    private val quiet = object : ErrorHandler {
        override fun warning(e: SAXParseException) = Unit
        override fun error(e: SAXParseException) = Unit
        override fun fatalError(e: SAXParseException) = throw e
    }

    fun parse(text: String?, maxChars: Int): Document? {
        if (text == null || text.length > maxChars || text.isBlank()) return null
        if (text.contains("<!DOCTYPE", ignoreCase = true) || text.contains("<!ENTITY", ignoreCase = true)) return null
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            factory.isXIncludeAware = false
            factory.isExpandEntityReferences = false
            for ((feature, on) in SECURE_FEATURES) {
                try {
                    factory.setFeature(feature, on)
                } catch (_: Exception) {
                    // Not known to this platform's parser: the text checks above still stand.
                }
            }
            val builder = factory.newDocumentBuilder()
            builder.setErrorHandler(quiet)
            builder.parse(InputSource(StringReader(text)))
        } catch (_: Exception) {
            null
        } catch (_: StackOverflowError) {
            null
        }
    }

    private val SECURE_FEATURES = listOf(
        "http://javax.xml.XMLConstants/feature/secure-processing" to true,
        "http://apache.org/xml/features/disallow-doctype-decl" to true,
        "http://xml.org/sax/features/external-general-entities" to false,
        "http://xml.org/sax/features/external-parameter-entities" to false,
        "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
    )

    /** Characters XML 1.0 cannot carry at all; a title with one would make the whole DIDL unreadable. */
    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '&' -> sb.append("&amp;")
                c == '<' -> sb.append("&lt;")
                c == '>' -> sb.append("&gt;")
                c == '"' -> sb.append("&quot;")
                c == '\'' -> sb.append("&apos;")
                c == '\t' || c == '\n' || c == '\r' -> sb.append(c)
                c.code < 0x20 -> Unit
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                    sb.append(c).append(s[i + 1])
                    i++
                }
                Character.isSurrogate(c) -> Unit
                c.code == 0xFFFE || c.code == 0xFFFF -> Unit
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }
}

/** The element children named [name] (any namespace). */
internal fun Element.childElements(name: String? = null): List<Element> {
    val out = ArrayList<Element>()
    var n: Node? = firstChild
    while (n != null) {
        if (n is Element && (name == null || (n.localName ?: n.nodeName.substringAfter(':')) == name)) out += n
        n = n.nextSibling
    }
    return out
}

internal fun Element.child(name: String): Element? = childElements(name).firstOrNull()

/** The trimmed text of the child [name], or null when it is missing or blank. */
internal fun Element.childText(name: String): String? =
    child(name)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

/** Every element named [name] below this document, in document order. */
internal fun Document.elementsNamed(name: String): List<Element> {
    val list = getElementsByTagNameNS("*", name)
    return List(list.length) { list.item(it) as Element }
}
