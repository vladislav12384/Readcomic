package com.readcomic.ui

import com.readcomic.data.ComicEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionOrderTest {
    private fun comic(title: String, series: String? = null, added: Long = 0L, read: Long = 0L, writer: String? = null) =
        ComicEntry(id = title, uri = title, title = title, pageCount = 20, addedAt = added, lastReadAt = read, series = series, writer = writer)

    @Test
    fun alphabeticalWithSeriesKeptTogetherInOrder() {
        val comics = listOf(
            comic("Watchmen"),
            comic("Saga 10", series = "Saga"),
            comic("akira"),
            comic("Saga 2", series = "Saga"),
            comic("Saga 1", series = "Saga"),
            comic("Batman #12"),
            comic("Ёлка"),
            comic("Ежевика"),
        )
        val placed = CollectionOrder.arrange(comics, ShelfOrder.TITLE)
        assertEquals(
            listOf("akira", "Batman #12", "Saga 1", "Saga 2", "Saga 10", "Watchmen", "Ежевика", "Ёлка"),
            placed.map { it.entry.title },
        )
        // Only comics put into a series stand under its sign, with their number on the label.
        assertEquals(listOf(null, null, "Saga", "Saga", "Saga", null, null, null), placed.map { it.series })
        assertEquals("№ 10", placed[4].label)
        assertEquals("Batman #12", placed[1].label)
    }

    @Test
    fun recentOrdersDoNotGroup() {
        val comics = listOf(
            comic("Saga 1", series = "Saga", added = 1, read = 5),
            comic("Maus", added = 3, read = 9),
            comic("Saga 2", series = "Saga", added = 2),
        )
        assertEquals(listOf("Maus", "Saga 2", "Saga 1"), CollectionOrder.arrange(comics, ShelfOrder.ADDED).map { it.entry.title })
        assertEquals(listOf("Maus", "Saga 1", "Saga 2"), CollectionOrder.arrange(comics, ShelfOrder.READ).map { it.entry.title })
        assertTrue(CollectionOrder.arrange(comics, ShelfOrder.ADDED).all { it.series == null })
    }

    @Test
    fun searchMatchesEveryWordAnywhere() {
        val saga = comic("Saga 12", series = "Saga", writer = "Brian K. Vaughan")
        assertTrue(CollectionOrder.matches(saga, "saga"))
        assertTrue(CollectionOrder.matches(saga, "vaughan 12"))
        assertTrue(CollectionOrder.matches(comic("Ёжик в тумане"), "ежик"))
        assertFalse(CollectionOrder.matches(saga, "batman"))
        assertTrue(CollectionOrder.matches(saga, "  "))
    }
}
