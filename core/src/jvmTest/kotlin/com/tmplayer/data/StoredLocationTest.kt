package com.tmplayer.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the stored downloads location and a record's path mean (HX57): a path, or a picked folder. */
class StoredLocationTest {

    private val tree = "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FTMPlayer"
    private val document = "$tree/document/primary%3ADownload%2FTMPlayer%2FVideo.mkv"

    @Test
    fun `blank is internal, a path is a volume, a local tree is a tree`() {
        assertEquals(StoredLocation.Kind.Internal, StoredLocation.kindOf(null))
        assertEquals(StoredLocation.Kind.Internal, StoredLocation.kindOf(""))
        assertEquals(StoredLocation.Kind.Internal, StoredLocation.kindOf("  "))
        assertEquals(StoredLocation.Kind.Volume, StoredLocation.kindOf("/storage/1A2B-3C4D/Android/data/app/files"))
        assertEquals(StoredLocation.Kind.Tree, StoredLocation.kindOf(tree))
    }

    @Test
    fun `a cloud folder or a bare prefix is never used`() {
        assertEquals(StoredLocation.Kind.Unusable, StoredLocation.kindOf("content://com.google.android.apps.docs.storage/tree/abc"))
        assertEquals(StoredLocation.Kind.Unusable, StoredLocation.kindOf("content://"))
        assertEquals(StoredLocation.Kind.Unusable, StoredLocation.kindOf("content://com.android.externalstorage.documents/tree/"))
    }

    @Test
    fun `a record's path is a document only with the content prefix`() {
        assertTrue(StoredLocation.isDocument(document))
        assertFalse(StoredLocation.isDocument("/data/user/0/app/files/downloads/a.mkv"))
        assertFalse(StoredLocation.isDocument(null))
    }

    @Test
    fun `a document is in its own folder and in no other`() {
        assertTrue(StoredLocation.documentIsIn(tree, document))
        assertFalse(StoredLocation.documentIsIn("$tree-other", document))
        assertFalse(StoredLocation.documentIsIn(tree, "/data/a.mkv"))
        assertFalse(StoredLocation.documentIsIn(tree, "$tree/document/"))
        assertFalse(StoredLocation.documentIsIn("/a/path", document))
    }
}
