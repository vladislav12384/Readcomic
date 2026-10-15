package com.readcomic.shelf

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class PaperSynthTest {
    private val rate = 48000

    private fun play(seconds: Float, setup: (PaperSynth) -> Unit): FloatArray {
        val synth = PaperSynth(rate)
        setup(synth)
        val out = FloatArray((seconds * rate).toInt())
        val block = FloatArray(256)
        var i = 0
        while (i < out.size) {
            synth.render(block, 256)
            block.copyInto(out, i, 0, minOf(256, out.size - i))
            i += 256
        }
        return out
    }

    private fun rms(x: FloatArray) = sqrt(x.sumOf { (it * it).toDouble() } / x.size).toFloat()

    /** Where the energy of [x] sits: the mean frequency, from zero crossings (rough, but enough). */
    private fun brightness(x: FloatArray): Float {
        var crossings = 0
        for (i in 1 until x.size) if ((x[i - 1] < 0f) != (x[i] < 0f)) crossings++
        return crossings / 2f / (x.size / rate.toFloat())
    }

    @Test
    fun silenceWhenNothingHappens() {
        val out = play(0.2f) {}
        assertTrue(out.all { it == 0f })
    }

    @Test
    fun tearingCrackles() {
        val slow = play(0.6f) { it.tear = 0.25f }
        val fast = play(0.6f) { it.tear = 1f }
        assertTrue(slow.all { it.isFinite() && abs(it) <= 1f } && fast.all { it.isFinite() && abs(it) <= 1f })
        assertTrue("louder when tearing faster: ${rms(slow)} < ${rms(fast)}", rms(fast) > rms(slow) * 1.3f)
        assertTrue("audible but not harsh: ${rms(fast)}", rms(fast) in 0.02f..0.35f)
        // Crisp: most of it well above the low rumble.
        assertTrue("bright: ${brightness(fast)}", brightness(fast) > 1500f)
    }

    @Test
    fun cuesSoundAndEnd() {
        for (cue in UnboxCue.entries) {
            val synth = PaperSynth(rate)
            synth.cue(cue, 1f)
            val out = FloatArray(rate)
            var i = 0
            val block = FloatArray(256)
            while (i < out.size) {
                synth.render(block, 256)
                block.copyInto(out, i, 0, minOf(256, out.size - i))
                i += 256
            }
            assertTrue("$cue is heard", out.take(rate / 4).maxOf { abs(it) } > 0.02f)
            assertTrue("$cue stays in range", out.all { it.isFinite() && abs(it) <= 1f })
            // A little later it is over and the audio may rest.
            val more = FloatArray(rate * 2)
            synth.render(more, more.size)
            assertTrue("$cue ends", synth.idle)
            assertFalse(more.takeLast(1000).any { abs(it) > 1e-3f })
        }
    }

    @Test
    fun theGleamRingsHighAndTheBoxThuds() {
        val gleam = play(0.4f) { it.cue(UnboxCue.SHINE, 1f) }
        val thud = play(0.4f) { it.cue(UnboxCue.BOX_LANDS, 1f) }
        assertTrue("gleam ${brightness(gleam)} above thud ${brightness(thud)}", brightness(gleam) > 2000f && brightness(thud) < 800f)
    }
}
