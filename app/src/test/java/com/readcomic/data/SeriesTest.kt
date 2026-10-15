package com.readcomic.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesTest {
    private fun check(title: String, name: String, volume: Float?, number: Float?) {
        val info = Series.of(title)
        assertEquals("series of «$title»", name, info.name)
        assertEquals("volume of «$title»", volume, info.volume)
        assertEquals("number of «$title»", number, info.number)
    }

    @Test
    fun usualNames() {
        check("Batman #12", "Batman", null, 12f)
        check("Saga 001 (2012) (Digital) (Zone-Empire)", "Saga", null, 1f)
        check("One Piece Vol. 3 Ch. 25", "One Piece", 3f, 25f)
        check("Akira_v02", "Akira", 2f, null)
        check("Хроники Амбера — Том 2", "Хроники Амбера", 2f, null)
        check("Город теней - т. 3", "Город теней", 3f, null)
        check("Ведьмак. Выпуск 4", "Ведьмак", null, 4f)
        check("Watchmen 01 of 12", "Watchmen", null, 1f)
        check("100 Bullets 015", "100 Bullets", null, 15f)
        check("Spider-Man 2099 #5", "Spider-Man 2099", null, 5f)
        check("Batman 12.5", "Batman", null, 12.5f)
        check("Берсерк, глава 120", "Берсерк", null, 120f)
    }

    @Test
    fun namesWithoutANumberStayWhole() {
        check("Spider-Man 2099", "Spider-Man 2099", null, null)
        check("Демо: как пользоваться", "Демо: как пользоваться", null, null)
        check("Maus", "Maus", null, null)
        check("1602", "1602", null, null)
    }

    @Test
    fun placeOnTheLabel() {
        assertEquals("№ 12", Series.of("Batman #012").place)
        assertEquals("Том 2", Series.of("Akira v02").place)
        assertEquals("Том 3 · № 25", Series.of("One Piece Vol. 3 Ch. 25").place)
        assertNull(Series.of("Maus").place)
    }

    @Test
    fun keyIgnoresCaseAndPunctuation() {
        assertEquals(Series.keyOf("Spider-Man"), Series.keyOf("spider man"))
        assertEquals(Series.keyOf("Ёжик"), Series.keyOf("ежик"))
    }
}
