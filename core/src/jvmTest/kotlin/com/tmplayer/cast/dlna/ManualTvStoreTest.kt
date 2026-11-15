package com.tmplayer.cast.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManualTvStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store(name: String = "tvs.txt") = ManualTvStore(folder.root.resolve(name))

    @Test
    fun aTvIsRememberedAcrossRunsAndCanBeRemoved() {
        store().add("http://192.168.1.20:49152/description.xml", "uuid:a", "Living room")
        val again = store()
        assertEquals(listOf("http://192.168.1.20:49152/description.xml"), again.locations())
        assertEquals("192.168.1.20:49152", again.load().single().address)
        assertEquals("Living room", again.load().single().name)
        again.remove("http://192.168.1.20:49152/description.xml")
        assertTrue(store().load().isEmpty())
    }

    @Test
    fun aTvAddedAgainAtANewAddressReplacesItsLine() {
        val s = store()
        s.add("http://192.168.1.20:49152/description.xml", "uuid:a", "TV")
        s.add("http://192.168.1.31:49152/description.xml", "uuid:a", "TV")
        assertEquals(listOf("http://192.168.1.31:49152/description.xml"), s.locations())
        s.add("http://192.168.1.31:49152/description.xml", "uuid:a", "TV")
        assertEquals(1, s.load().size)
    }

    @Test
    fun anAddressThatIsNotAllowedIsNotSaved() {
        val s = store()
        s.add("http://8.8.8.8/description.xml", "uuid:a", "Not a TV")
        s.add("192.168.1.20", "uuid:b", "no path")
        s.add("http://tv.local/d.xml", "uuid:c", "name")
        assertTrue(s.load().isEmpty())
    }

    @Test
    fun aDamagedFileGivesWhatIsGoodAndNeverThrows() {
        val file = folder.root.resolve("tvs.txt")
        file.writeText("garbage\n\nhttp://8.8.8.8/d.xml\tuuid:x\tpublic\nhttp://192.168.1.20/d.xml\tuuid:a\tTV\u0000\u0001 name\nhttp://192.168.1.21/d.xml\n\t\t\n" + "x".repeat(1000))
        val rows = ManualTvStore(file).load()
        assertEquals(listOf("http://192.168.1.20/d.xml", "http://192.168.1.21/d.xml"), rows.map { it.location })
        assertEquals("TV name", rows[0].name)
        assertEquals("", rows[1].name)
    }

    @Test
    fun aHugeFileAndAMissingFileGiveNothing() {
        val big = folder.root.resolve("big.txt")
        big.writeText("http://192.168.1.20/d.xml\tuuid:a\tTV\n".repeat(10_000))
        assertTrue(ManualTvStore(big).load().isEmpty())
        assertTrue(store("none.txt").load().isEmpty())
        assertEquals(ManualTvStore.MAX, ManualTvStore.parse("http://192.168.1.20/d.xml\n".repeat(1) + (1..100).joinToString("\n") { "http://192.168.2.$it/d.xml" }).size)
    }

    @Test
    fun thereAreAtMostThirtyTwo() {
        val s = store()
        for (i in 1..40) s.add("http://192.168.1.$i/d.xml", "uuid:$i", "TV $i")
        val rows = s.load()
        assertEquals(ManualTvStore.MAX, rows.size)
        assertEquals("TV 40", rows.last().name)
    }
}
