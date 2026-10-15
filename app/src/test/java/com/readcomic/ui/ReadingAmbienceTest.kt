package com.readcomic.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class ReadingAmbienceTest {
    @Test
    fun eveningsAreFromNineToSeven() {
        val evenings = NightSettings(mode = NightMode.EVENING)
        assertTrue(evenings.activeAt(LocalTime.of(21, 0)))
        assertTrue(evenings.activeAt(LocalTime.of(2, 30)))
        assertTrue(evenings.activeAt(LocalTime.of(6, 59)))
        assertFalse(evenings.activeAt(LocalTime.of(7, 0)))
        assertFalse(evenings.activeAt(LocalTime.of(15, 0)))
        assertTrue(NightSettings(mode = NightMode.ON).activeAt(LocalTime.NOON))
        assertFalse(NightSettings(mode = NightMode.OFF).activeAt(LocalTime.MIDNIGHT))
    }

    @Test
    fun oldBackgroundsBecomeTables() {
        assertEquals(ReadingTable.GRAPHITE, ReadingTable.fromOldBackground(0))
        assertEquals(ReadingTable.BLACK, ReadingTable.fromOldBackground(1))
        assertEquals(ReadingTable.WALNUT, ReadingTable.fromOldBackground(2))
        assertEquals(ReadingTable.LINEN, ReadingTable.fromOldBackground(3))
        // Every surface of the shader is offered once.
        assertEquals((0..7).toSet(), ReadingTable.entries.map { it.kind }.toSet())
    }
}
