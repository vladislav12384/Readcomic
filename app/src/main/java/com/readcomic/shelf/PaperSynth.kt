package com.readcomic.shelf

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.tanh

/**
 * The sounds of unpacking, made up as they are played (no recordings): paper tearing is a
 * crackle of tiny fibre snaps over a rustle, denser and louder the faster it tears; tape being
 * cut is a finer, busier crackle with a zip in it; cardboard flaps thump; the gleam of a clean
 * cover rings like a small bell with a "ch" before it.
 *
 * [tear] and [tape] are set from the render thread, [cue] queues one-off sounds; [render] runs
 * on the audio thread. Mono, in -1..1.
 */
class PaperSynth(private val sampleRate: Int, seed: Int = 0x2545F491) {

    /** How loud the paper is being torn, 0..~1.4. */
    @Volatile
    var tear = 0f

    /** How fast the tape is being cut, 0..1. */
    @Volatile
    var tape = 0f

    private val cues = ConcurrentLinkedQueue<Pair<UnboxCue, Float>>()

    fun cue(cue: UnboxCue, strength: Float) {
        cues += cue to strength
    }

    /** Nothing playing and nothing about to: the audio can rest. */
    val idle: Boolean
        get() = cues.isEmpty() && voices.isEmpty() && tearLevel < 1e-4f && tapeLevel < 1e-4f && tear < 0.02f && tape < 0.02f &&
            grains.all { it < 1e-4f }

    private var state = seed

    private fun random(): Float {
        var x = state
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        state = x
        return (x ushr 8) / 16777216f
    }

    private fun white() = random() * 2f - 1f

    // region Filters

    /** A state-variable filter (Chamberlin, trapezoidal form): low, band and high pass at once. */
    private class Svf(sampleRate: Int, frequency: Float, q: Float) {
        private val g = tan(PI * frequency / sampleRate).toFloat()
        private val k = 1f / q
        private val a1 = 1f / (1f + g * (g + k))
        private val a2 = g * a1
        private val a3 = g * a2
        private var ic1 = 0f
        private var ic2 = 0f
        var low = 0f
        var band = 0f
        var high = 0f

        fun process(x: Float) {
            val v3 = x - ic2
            val v1 = a1 * ic1 + a2 * v3
            val v2 = ic2 + a2 * ic1 + a3 * v3
            ic1 = 2f * v1 - ic1
            ic2 = 2f * v2 - ic2
            low = v2
            band = v1
            high = x - k * v1 - v2
        }
    }

    // endregion

    // Paper: fibre snaps (a few overlapping grains) through a bright band, hiss and rustle.
    private val grains = FloatArray(4)
    private val grainDecay = FloatArray(4)
    private var nextGrain = 0
    private val crackleTone = Svf(sampleRate, 3200f, 0.7f)
    private val crackleLow = Svf(sampleRate, 1200f, 0.7f)
    private val hissTone = Svf(sampleRate, 4800f, 0.8f)
    private val rustleTone = Svf(sampleRate, 700f, 0.6f)
    private val rustleFloor = Svf(sampleRate, 140f, 0.7f)
    private val zipTone = Svf(sampleRate, 1150f, 4f)
    private var tearLevel = 0f
    private var tapeLevel = 0f
    private var drift = 0f

    private abstract inner class Voice {
        var t = 0
        val seconds get() = t / sampleRate.toFloat()

        /** The next sample; false once it is over. */
        abstract fun next(out: FloatArray, i: Int): Boolean
    }

    private val voices = ArrayList<Voice>()

    /** A decaying sine. */
    private inner class Tone(val frequency: Float, val amplitude: Float, val decay: Float, val delay: Float = 0f, val glide: Float = 0f) : Voice() {
        private var phase = 0f
        override fun next(out: FloatArray, i: Int): Boolean {
            t++
            val s = seconds - delay
            if (s < 0f) return true
            val f = frequency * (1f + glide * exp(-s / 0.03f))
            phase += (2f * PI.toFloat() * f / sampleRate)
            if (phase > 2f * PI.toFloat()) phase -= 2f * PI.toFloat()
            val attack = (s / 0.0015f).coerceAtMost(1f)
            val env = amplitude * attack * exp(-s / decay)
            out[i] += sin(phase) * env
            // Done once some 55 dB down.
            return env > amplitude * 0.0018f || s < decay
        }
    }

    /** Filtered noise with a quick attack and a decay; [sweep] moves its band from [frequency] by that factor. */
    private inner class Noise(
        val frequency: Float,
        val q: Float,
        val amplitude: Float,
        val attack: Float,
        val decay: Float,
        val kind: Int,
        val delay: Float = 0f,
        val hold: Float = 0f,
    ) : Voice() {
        private val filter = Svf(sampleRate, frequency, q)
        override fun next(out: FloatArray, i: Int): Boolean {
            t++
            val s = seconds - delay
            if (s < 0f) return true
            filter.process(white())
            val shape = when (kind) {
                0 -> filter.low
                1 -> filter.band
                else -> filter.high
            }
            val env = amplitude * (s / attack).coerceAtMost(1f) * if (s < attack + hold) 1f else exp(-(s - attack - hold) / decay)
            out[i] += shape * env
            return s < attack + hold + decay * 7f
        }
    }

    /** A burst of crackle, [rate] snaps a second for [length] seconds. */
    private inner class Crackle(val rate: Float, val amplitude: Float, val length: Float, val delay: Float = 0f) : Voice() {
        override fun next(out: FloatArray, i: Int): Boolean {
            t++
            val s = seconds - delay
            if (s < 0f) return true
            if (s < length) {
                val fade = 1f - s / length
                if (random() < rate * fade / sampleRate) snap(amplitude * fade * (0.3f + 0.7f * random()))
            }
            return s < length
        }
    }

    private fun snap(amplitude: Float) {
        val k = nextGrain
        nextGrain = (nextGrain + 1) % grains.size
        grains[k] += amplitude
        // Each snap rings for a fraction of a millisecond, some longer than others.
        val tau = (0.00015f + 0.0009f * random() * random()) * sampleRate
        grainDecay[k] = exp(-1f / tau)
    }

    private fun start(cue: UnboxCue, strength: Float) {
        val s = strength.coerceIn(0f, 1.5f)
        when (cue) {
            UnboxCue.POKE -> {
                snap(0.55f * s)
                voices += Tone(170f, 0.12f * s, 0.016f)
                voices += Noise(900f, 0.8f, 0.25f * s, 0.001f, 0.012f, 1)
            }
            UnboxCue.TEAR_TICK -> snap(0.25f * s)
            UnboxCue.TAPE_DONE -> {
                voices += Crackle(5200f, 0.42f * s, 0.16f)
                voices += Noise(1600f, 1.2f, 0.12f * s, 0.004f, 0.05f, 1)
            }
            UnboxCue.FLAP -> {
                voices += Noise(480f, 0.7f, 0.55f * s, 0.003f, 0.06f, 0)
                voices += Tone(105f, 0.22f * s, 0.05f)
                voices += Crackle(900f, 0.12f * s, 0.05f)
            }
            UnboxCue.BOX_LANDS -> {
                voices += Tone(72f, 0.5f * s, 0.11f, glide = 0.25f)
                voices += Noise(320f, 0.7f, 0.5f * s, 0.002f, 0.08f, 0)
                // Comics shifting inside.
                voices += Crackle(700f, 0.18f * s, 0.12f, delay = 0.02f)
                voices += Noise(260f, 0.8f, 0.2f * s, 0.002f, 0.06f, 0, delay = 0.13f)
            }
            UnboxCue.BOX_SHAKES -> {
                voices += Noise(380f, 0.7f, 0.3f * s, 0.002f, 0.05f, 0)
                voices += Crackle(800f, 0.15f * s, 0.1f)
            }
            UnboxCue.RELEASE -> {
                // The rest of the paper slipping off: a soft swoosh and a few snaps.
                voices += Noise(1800f, 0.9f, 0.16f * s, 0.12f, 0.16f, 1)
                voices += Noise(600f, 0.7f, 0.14f * s, 0.08f, 0.14f, 0)
                voices += Crackle(600f, 0.2f * s, 0.35f)
            }
            UnboxCue.SHINE -> {
                // "Ch" and then a small bright bell.
                voices += Noise(6500f, 0.7f, 0.22f * s, 0.001f, 0.018f, 2)
                voices += Tone(2637f, 0.15f * s, 0.38f, delay = 0.012f, glide = 0.02f)
                voices += Tone(3951f, 0.08f * s, 0.22f, delay = 0.012f)
                voices += Tone(5274f, 0.045f * s, 0.14f, delay = 0.012f)
                voices += Tone(3520f, 0.09f * s, 0.3f, delay = 0.085f)
                voices += Tone(7040f, 0.025f * s, 0.12f, delay = 0.085f)
            }
        }
    }

    /** Fills [out] with the next [frames] samples. */
    fun render(out: FloatArray, frames: Int = out.size) {
        while (true) {
            val (cue, strength) = cues.poll() ?: break
            start(cue, strength)
        }
        val attack = 1f - exp(-1f / (0.006f * sampleRate))
        val release = 1f - exp(-1f / (0.05f * sampleRate))
        val tearTarget = tear.coerceIn(0f, 1.4f).let { if (it < 0.02f) 0f else it }
        val tapeTarget = tape.coerceIn(0f, 1.2f).let { if (it < 0.02f) 0f else it }
        for (i in 0 until frames) {
            tearLevel += (tearTarget - tearLevel) * if (tearTarget > tearLevel) attack else release
            tapeLevel += (tapeTarget - tapeLevel) * if (tapeTarget > tapeLevel) attack else release
            drift += (random() - 0.5f) * 0.002f
            drift *= 0.9995f

            // Fibres snapping: more of them the faster the tear runs.
            val rate = (if (tearLevel > 1e-3f) 140f + 2600f * tearLevel else 0f) + (if (tapeLevel > 1e-3f) 1400f + 5200f * tapeLevel else 0f)
            if (rate > 0f && random() < rate / sampleRate) {
                val loud = max(tearLevel, tapeLevel * 0.8f)
                val r = random()
                snap(loud.coerceAtMost(1f) * (0.12f + 0.55f * r * r))
            }
            var grain = 0f
            for (k in grains.indices) {
                grain += grains[k]
                grains[k] *= grainDecay[k]
            }
            val noise = white()
            crackleTone.process(grain * noise)
            crackleLow.process(grain * noise)
            hissTone.process(white())
            rustleTone.process(white())
            rustleFloor.process(rustleTone.low)
            zipTone.process(white())
            var sample = crackleTone.band * 1.6f + crackleLow.high * 0.35f
            sample += hissTone.band * 0.05f * tearLevel
            sample += rustleFloor.high * 0.14f * tearLevel * (0.7f + drift * 6f).coerceIn(0.3f, 1.2f)
            sample += zipTone.band * 0.22f * tapeLevel
            out[i] = sample
        }
        val iterator = voices.iterator()
        val extra = FloatArray(frames)
        while (iterator.hasNext()) {
            val voice = iterator.next()
            var alive = true
            for (i in 0 until frames) if (!voice.next(extra, i)) {
                alive = false
            }
            if (!alive) iterator.remove()
        }
        for (i in 0 until frames) out[i] = tanh((out[i] + extra[i]) * MASTER) * 0.9f
    }

    private companion object {
        const val MASTER = 0.9f
    }
}
