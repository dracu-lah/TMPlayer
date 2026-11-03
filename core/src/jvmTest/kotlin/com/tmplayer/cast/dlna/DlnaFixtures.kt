package com.tmplayer.cast.dlna

/** The captured answers under `src/jvmTest/resources/dlna/` (see the README there). */
object DlnaFixtures {
    val renderers = listOf("gmrender", "rygel")

    fun text(path: String): String =
        (DlnaFixtures::class.java.getResource("/dlna/$path") ?: error("No fixture $path")).readText(Charsets.UTF_8)

    /** A saved HTTP answer: its status code and its body (the part after the first blank line). */
    fun http(path: String): Pair<Int, String> {
        val raw = text(path).replace("\r\n", "\n")
        val status = raw.lineSequence().first().split(' ').getOrNull(1)?.toIntOrNull() ?: 0
        return status to raw.substringAfter("\n\n", "")
    }

    fun soap(renderer: String, name: String): SoapResult {
        val (status, body) = http("$renderer/soap/$name.response.http")
        return Soap.parse(status, body)
    }

    /** The datagrams of `ssdp-replies.txt` or `ssdp-notify.txt`. */
    fun datagrams(renderer: String, file: String): List<ByteArray> =
        text("$renderer/$file").split("\n----\n").filter { it.isNotBlank() }.map { it.toByteArray() }

    fun location(renderer: String): String =
        datagrams(renderer, "ssdp-replies.txt").mapNotNull { Ssdp.parse(it)?.location }.first()

    fun description(renderer: String): DeviceDescription? =
        DeviceDescription.parse(http("$renderer/description.http").second, location(renderer))
}
