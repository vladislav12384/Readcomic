package com.readcomic.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicEntryTest {
    private fun comic(current: Int, last: Int = current, read: Long = 1L) =
        ComicEntry(id = "c", uri = "c", title = "c", pageCount = 20, currentPage = current, lastVisiblePage = last, lastReadAt = read)

    @Test
    fun readingGoesOnWhereItStopped() {
        assertEquals(0, comic(0, read = 0L).resumePage)
        assertEquals(7, comic(7).resumePage)
        // The spread before the end: its right-hand page is not the last one yet.
        assertEquals(17, comic(17, last = 18).resumePage)
    }

    @Test
    fun aComicReadToTheEndStartsAgain() {
        // On the last page alone, or on the last spread.
        val single = comic(19)
        assertTrue(single.isFinished)
        assertEquals(0, single.resumePage)
        val spread = comic(18, last = 19)
        assertTrue(spread.isFinished)
        assertEquals(0, spread.resumePage)
    }

    @Test
    fun aComicNeverOpenedIsNotReadToTheEnd() {
        val single = ComicEntry(id = "c", uri = "c", title = "c", pageCount = 1)
        assertFalse(single.isFinished)
        assertEquals(0, single.resumePage)
    }
}
