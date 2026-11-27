package com.wboard.ime.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardHistoryStoreTest {

    @Test
    fun recordsClipsInMruOrderAndSkipsSensitive() {
        val store = ClipboardHistoryStore(maxItems = 3)
        assertTrue(store.recordClip("halo dunia", isSensitive = false))
        assertFalse(store.recordClip("super-secret-password", isSensitive = true))
        assertTrue(store.recordClip("kedua", isSensitive = false))
        assertTrue(store.recordClip("ketiga", isSensitive = false))
        assertTrue(store.recordClip("keempat", isSensitive = false))

        assertEquals(listOf("keempat", "ketiga", "kedua"), store.items())

        // Re-copying moves item to top without duplicating
        assertTrue(store.recordClip("kedua", isSensitive = false))
        assertEquals(listOf("kedua", "keempat", "ketiga"), store.items())
    }

    @Test
    fun clearRemovesAllClips() {
        val store = ClipboardHistoryStore()
        store.recordClip("satu")
        store.clear()
        assertTrue(store.items().isEmpty())
    }
}
