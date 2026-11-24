package com.tmplayer.ui.onboarding

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP-70: a retaken tour or setup picture is never smaller than its spec asks for. Each spec in
 * scripts/annotations/ names its output file, its output width and its crop; the file's own
 * pixel size, read from the WebP header, must be that width and the crop's aspect ratio, so no
 * picture was downscaled or re-cropped by hand (docs/agents/onboarding-pictures.md).
 */
class OnboardingPictureSizeTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "scripts/annotations").isDirectory }

    /** Width and height from a lossy (VP8), lossless (VP8L) or extended (VP8X) WebP header. */
    private fun webpSize(file: File): Pair<Int, Int>? {
        val b = file.readBytes()
        if (b.size < 30 || String(b, 0, 4) != "RIFF" || String(b, 8, 4) != "WEBP") return null
        fun u(i: Int) = b[i].toInt() and 0xFF
        return when (String(b, 12, 4)) {
            "VP8 " -> ((u(26) or (u(27) shl 8)) and 0x3FFF) to ((u(28) or (u(29) shl 8)) and 0x3FFF)
            "VP8L" -> {
                val bits = u(21) or (u(22) shl 8) or (u(23) shl 16) or (u(24) shl 24)
                ((bits and 0x3FFF) + 1) to (((bits shr 14) and 0x3FFF) + 1)
            }
            "VP8X" -> (1 + (u(24) or (u(25) shl 8) or (u(26) shl 16))) to (1 + (u(27) or (u(28) shl 8) or (u(29) shl 16)))
            else -> null
        }
    }

    @Test
    fun `every tour and setup picture has the width and shape its spec asks for`() {
        val specs = File(root, "scripts/annotations").listFiles { f ->
            f.name.startsWith("tour-") || f.name.startsWith("setup-")
        }.orEmpty().sortedBy { it.name }
        assertTrue("no tour or setup specs found", specs.isNotEmpty())
        for (spec in specs) {
            val json = JSONObject(spec.readText())
            val out = File(root, json.getString("output"))
            assertTrue("${spec.name}: ${out.path} is missing", out.isFile)
            val size = webpSize(out)
            assertNotNull("${out.name}: not a WebP", size)
            val (w, h) = size!!
            val crop = json.optJSONArray("crop")
            val cropW = crop?.getInt(2)
            val cropH = crop?.getInt(3)
            val wantW = if (json.has("width")) json.getInt("width") else cropW
            if (wantW != null) assertEquals("${out.name} was downscaled or resized", wantW, w)
            if (cropW != null && cropH != null && wantW != null) {
                val wantH = Math.round(wantW.toDouble() * cropH / cropW).toInt()
                assertTrue("${out.name} is $w x $h, the crop says $wantW x $wantH", kotlin.math.abs(h - wantH) <= 1)
            }
        }
    }
}
