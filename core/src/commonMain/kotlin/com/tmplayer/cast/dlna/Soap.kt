package com.tmplayer.cast.dlna

import com.tmplayer.platform.Logger
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** What one SOAP call came to. */
sealed interface SoapResult {

    /** The action ran: its out arguments by name. */
    data class Ok(val values: Map<String, String>) : SoapResult {
        operator fun get(name: String): String? = values[name]
    }

    /** The device answered with a UPnP error: [code] is its `errorCode` (701, 714, 716...). */
    data class Fault(val httpStatus: Int, val code: Int?, val description: String?) : SoapResult

    /** No usable answer: refused, timed out, cut short, too big or not XML. */
    data class Failed(val message: String) : SoapResult
}

/**
 * SOAP over HTTP as UPnP uses it: an envelope out, an envelope or a UPnP error back. The
 * envelope builder escapes every value (a title or an address with `&` in it is the bug class
 * that broke `%26` before), and the parser reads only the first element of the body.
 *
 * Renderers differ on errors, and the captured ones show how: Rygel answers a bad InstanceID
 * with HTTP 500 and error 718, gmrender-resurrect answers the same call with HTTP 200 and no
 * error at all. So a 200 is [SoapResult.Ok] and a 500 with a UPnPError is a [SoapResult.Fault],
 * whatever the text of the status line.
 */
object Soap {

    private val NAME = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")

    fun envelope(serviceType: String, action: String, args: List<Pair<String, String>> = emptyList()): String {
        require(NAME.matches(action)) { "bad action name" }
        val body = StringBuilder()
        for ((name, value) in args) {
            require(NAME.matches(name)) { "bad argument name" }
            body.append('<').append(name).append('>').append(Xml.escape(value)).append("</").append(name).append('>')
        }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:$action xmlns:u=\"${Xml.escape(serviceType)}\">$body</u:$action>" +
            "</s:Body></s:Envelope>"
    }

    /** The value of the header SOAPAction, quotes included. */
    fun actionHeader(serviceType: String, action: String): String = "\"$serviceType#$action\""

    fun parse(httpStatus: Int, body: String?): SoapResult {
        val doc = Xml.parse(body, Xml.SOAP_MAX)
        val bodyElement = doc?.elementsNamed("Body")?.firstOrNull()
        val first = bodyElement?.childElements()?.firstOrNull()
        if (first == null) {
            return if (httpStatus in 200..299) SoapResult.Failed("The answer was not a SOAP message")
            else SoapResult.Fault(httpStatus, null, null)
        }
        val name = first.localName ?: first.nodeName.substringAfter(':')
        if (name == "Fault") {
            val error = first.child("detail")?.child("UPnPError")
            val code = error?.childText("errorCode")?.toIntOrNull()
            return SoapResult.Fault(httpStatus, code, error?.childText("errorDescription")?.take(200))
        }
        if (httpStatus !in 200..299) return SoapResult.Fault(httpStatus, null, null)
        val values = LinkedHashMap<String, String>()
        for (e in first.childElements()) {
            if (values.size >= MAX_VALUES) break
            values.putIfAbsent(e.localName ?: e.nodeName.substringAfter(':'), valueOf(e))
        }
        return SoapResult.Ok(values)
    }

    private const val MAX_VALUES = 64

    private fun valueOf(e: Element): String = e.textContent.orEmpty().trim()
}

/** Sends SOAP calls. A seam, so the tests and a future platform can stand in for the network. */
fun interface SoapTransport {
    fun call(service: DlnaService, action: String, args: List<Pair<String, String>>): SoapResult
}

/**
 * The HTTP side of [Soap]: a POST with short timeouts to a control URL, no redirects (a TV that
 * answers a redirect is sending the call somewhere TMPlayer did not choose), and a body cap on
 * what is read back. Never throws: every failure is a [SoapResult.Failed].
 */
class SoapClient(private val timeoutMs: Int = 5_000) : SoapTransport {

    override fun call(service: DlnaService, action: String, args: List<Pair<String, String>>): SoapResult {
        val payload = try {
            Soap.envelope(service.type, action, args).toByteArray(Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            return SoapResult.Failed(e.message ?: "bad call")
        }
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(service.controlUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = false
                useCaches = false
                doOutput = true
                setFixedLengthStreamingMode(payload.size)
                setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                setRequestProperty("SOAPAction", Soap.actionHeader(service.type, action))
                setRequestProperty("User-Agent", "TMPlayer")
                setRequestProperty("Connection", "close")
            }
            connection.outputStream.use { it.write(payload) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { readCapped(it) }
            Soap.parse(status, body)
        } catch (e: TooBig) {
            SoapResult.Failed("The answer was too large")
        } catch (e: IOException) {
            Logger.i(TAG, "$action failed: ${e.message}")
            SoapResult.Failed(e.message ?: "No answer")
        } catch (e: Exception) {
            SoapResult.Failed(e.message ?: "Bad call")
        } finally {
            connection?.disconnect()
        }
    }

    private class TooBig : IOException()

    private fun readCapped(input: java.io.InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > Xml.SOAP_MAX) throw TooBig()
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val TAG = "DlnaSoap"
    }
}
