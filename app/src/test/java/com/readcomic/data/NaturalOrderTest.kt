package com.readcomic.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalOrderTest {
    @Test
    fun sortsNumbersByValue() {
        val names = listOf("page10.jpg", "page2.jpg", "Page1.jpg", "page002b.jpg", "cover.jpg")
        assertEquals(
            listOf("cover.jpg", "Page1.jpg", "page2.jpg", "page002b.jpg", "page10.jpg"),
            names.sortedWith(NaturalOrder),
        )
    }

    @Test
    fun sortsFoldersFirstByName() {
        val names = listOf("Vol 2/01.png", "Vol 10/01.png", "Vol 1/02.png", "Vol 1/10.png")
        assertEquals(
            listOf("Vol 1/02.png", "Vol 1/10.png", "Vol 2/01.png", "Vol 10/01.png"),
            names.sortedWith(NaturalOrder),
        )
    }
}
