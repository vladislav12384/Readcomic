package com.readcomic.ui

import com.readcomic.update.AppUpdater
import com.readcomic.update.UpdateOffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateUiTest {
    private val offer = UpdateOffer(39, null, "https://example.org/Readcomic.apk", 10_000_000, null)

    @Test
    fun aCheckTheReaderAskedForShowsOnTheBanner() {
        assertTrue(UpdateUi(state = AppUpdater.State.Checking, checkingByHand = true).shown)
    }

    @Test
    fun aCheckOfTheAppsOwnStaysQuiet() {
        assertFalse(UpdateUi(state = AppUpdater.State.Checking, checkingByHand = false).shown)
        assertFalse(UpdateUi(state = AppUpdater.State.Idle, checkingByHand = true).shown)
    }

    @Test
    fun anUpdateShowsWhoeverLookedForIt() {
        assertTrue(UpdateUi(state = AppUpdater.State.Available(offer)).shown)
        assertTrue(UpdateUi(state = AppUpdater.State.Available(offer), checkingByHand = true).shown)
        assertFalse(UpdateUi(state = AppUpdater.State.Failed(null, "нет связи")).shown)
    }
}
