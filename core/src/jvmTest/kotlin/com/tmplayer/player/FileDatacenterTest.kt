package com.tmplayer.player

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** EG-F61: the datacenter in a file's remote id, or nothing. */
class FileDatacenterTest {

    @Test
    fun `a document id of datacenter 2`() {
        // The start of a real shape: type 5 with the file reference flag, then the datacenter as the second number.
        assertEquals(2, FileDatacenter.of("BQACAgIAAxkBAAI"))
        assertEquals(2, FileDatacenter.of("BQACAgIAAxkBAAIBY2Zz9k9X6fQ0lG0u2oDiK_Yhz3QqAAIqCQACzZ5BS1Zk1QABSdZCTgQ"))
    }

    @Test
    fun `every datacenter from 1 to 5, and no other`() {
        fun id(dc: Int): String {
            // type 5 with the reference flag, the datacenter, more bytes, and the zeros packed as the coding does.
            val raw = byteArrayOf(5, 0, 0, 2, dc.toByte(), 0, 0, 0, 25, 1, 2, 3, 4, 5, 6, 7, 8, 9, 22, 4)
            val packed = ArrayList<Byte>()
            var i = 0
            while (i < raw.size) {
                if (raw[i] == 0.toByte()) {
                    var run = 0
                    while (i < raw.size && raw[i] == 0.toByte() && run < 255) {
                        run++
                        i++
                    }
                    packed += 0
                    packed += run.toByte()
                } else {
                    packed += raw[i]
                    i++
                }
            }
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            val sb = StringBuilder()
            var bits = 0
            var count = 0
            for (b in packed) {
                bits = (bits shl 8) or (b.toInt() and 0xFF)
                count += 8
                while (count >= 6) {
                    count -= 6
                    sb.append(alphabet[(bits shr count) and 63])
                }
                bits = bits and ((1 shl count) - 1)
            }
            if (count > 0) sb.append(alphabet[(bits shl (6 - count)) and 63])
            return sb.toString()
        }
        for (dc in 1..5) assertEquals(dc, FileDatacenter.of(id(dc)), "dc $dc")
        for (dc in listOf(0, 6, 7, 203, 255)) assertNull(FileDatacenter.of(id(dc)), "dc $dc")
    }

    @Test
    fun `garbage gives nothing`() {
        assertNull(FileDatacenter.of(null))
        assertNull(FileDatacenter.of(""))
        assertNull(FileDatacenter.of("BQAC"))
        assertNull(FileDatacenter.of("!!!!!!!!!!!!"))
        assertNull(FileDatacenter.of("A".repeat(2_048)))
        assertNull(FileDatacenter.of("AAAAAAAAAAAAAAAA"))
        val random = Random(61)
        repeat(500) {
            val text = String(CharArray(random.nextInt(0, 120)) { "ABCxyz019-_=+/ !".random(random) })
            FileDatacenter.of(text)
        }
    }
}
