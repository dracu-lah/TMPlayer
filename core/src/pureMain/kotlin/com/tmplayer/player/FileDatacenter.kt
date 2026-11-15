package com.tmplayer.player

/**
 * The Telegram datacenter a file is kept in, read from its remote id (EG-F61).
 *
 * TDLib's `File` has no datacenter field. Its `remote.id` is the same persistent file id the Bot
 * API hands out, which Telegram's own clients and every library that reads them document as: URL
 * safe base64, then run-length coded zeros (a zero byte is followed by how many zeros it stands
 * for), then little endian fields: the file type (with flags in its high bits) and the datacenter
 * as the second 32 bit number, and a version byte or two at the end.
 *
 * Only the second number is read, and only believed in 1..5 (the datacenters there are; 203 is a
 * CDN's, which a download never starts from): anything else, an id that is empty, cut short,
 * garbage or absurdly long, gives null and the caller shows no line. Never a guess.
 */
object FileDatacenter {

    /** Longest id looked at: real ones are under 200 characters. */
    private const val MAX_ID = 512

    /** The datacenter number in [remoteId], or null when it cannot be read with confidence. */
    fun of(remoteId: String?): Int? {
        val text = remoteId?.trim()?.takeIf { it.length in 8..MAX_ID } ?: return null
        val packed = base64Url(text) ?: return null
        // Only the first eight bytes matter, so the zeros are unpacked just that far.
        val head = ByteArray(8)
        var written = 0
        var i = 0
        while (i < packed.size && written < head.size) {
            val b = packed[i].toInt() and 0xFF
            if (b == 0) {
                val run = (packed.getOrNull(i + 1)?.toInt() ?: return null) and 0xFF
                if (run == 0) return null
                var left = run
                while (left > 0 && written < head.size) {
                    head[written++] = 0
                    left--
                }
                i += 2
            } else {
                head[written++] = b.toByte()
                i++
            }
        }
        if (written < head.size) return null
        val dc = (head[4].toInt() and 0xFF) or
            ((head[5].toInt() and 0xFF) shl 8) or
            ((head[6].toInt() and 0xFF) shl 16) or
            ((head[7].toInt() and 0xFF) shl 24)
        return dc.takeIf { it in 1..5 }
    }

    private fun base64Url(text: String): ByteArray? {
        val out = ArrayList<Byte>(text.length * 3 / 4 + 3)
        var bits = 0
        var count = 0
        for (c in text) {
            val v = when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a' + 26
                in '0'..'9' -> c - '0' + 52
                '-', '+' -> 62
                '_', '/' -> 63
                '=' -> continue
                else -> return null
            }
            bits = (bits shl 6) or v
            count += 6
            if (count >= 8) {
                count -= 8
                out.add(((bits shr count) and 0xFF).toByte())
                bits = bits and ((1 shl count) - 1)
            }
        }
        return out.toByteArray()
    }
}
