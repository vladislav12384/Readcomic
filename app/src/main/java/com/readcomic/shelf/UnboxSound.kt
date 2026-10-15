package com.readcomic.shelf

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log

/**
 * Plays [PaperSynth] through an [AudioTrack] on its own thread while there is something to
 * hear, and lets the audio rest otherwise. Safe to call from any thread.
 */
class UnboxSound {
    private val synth = PaperSynth(SAMPLE_RATE)
    private val lock = Object()
    private var thread: Thread? = null

    @Volatile
    private var running = false

    /** Off: nothing is played (the vibrations stay). */
    @Volatile
    var enabled = true

    fun cue(cue: UnboxCue, strength: Float) {
        if (!enabled) return
        synth.cue(cue, strength)
        wake()
    }

    /** How loud the tearing and the cutting are now (set every frame while the box is up). */
    fun loudness(tear: Float, tape: Float) {
        val t = if (enabled) tear else 0f
        val c = if (enabled) tape else 0f
        if (synth.tear == t && synth.tape == c) return
        synth.tear = t
        synth.tape = c
        if (t > 0.02f || c > 0.02f) wake()
    }

    private fun wake() {
        synchronized(lock) {
            if (thread == null) {
                running = true
                thread = Thread(::loop, "unbox-sound").also { it.start() }
            }
            lock.notifyAll()
        }
    }

    /** Stops the sound for good (the view is gone). */
    fun release() {
        synchronized(lock) {
            running = false
            lock.notifyAll()
        }
        thread?.join(300)
        thread = null
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val track = try {
            val minimum = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minimum, BLOCK * 4 * 4))
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "No audio for unpacking", e)
            synchronized(lock) { thread = null }
            return
        }
        val buffer = FloatArray(BLOCK)
        var quiet = 0
        try {
            track.play()
            while (running) {
                if (synth.idle) {
                    quiet++
                    // A moment of silence written out, then the track rests until woken.
                    if (quiet * BLOCK > SAMPLE_RATE / 2) {
                        track.pause()
                        track.flush()
                        synchronized(lock) {
                            while (running && synth.idle && synth.tear < 0.02f && synth.tape < 0.02f) lock.wait(2000)
                        }
                        if (!running) break
                        quiet = 0
                        track.play()
                    }
                } else {
                    quiet = 0
                }
                synth.render(buffer, BLOCK)
                track.write(buffer, 0, BLOCK, AudioTrack.WRITE_BLOCKING)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unpacking sound stopped", e)
        } finally {
            runCatching { track.stop() }
            track.release()
            synchronized(lock) { if (thread == Thread.currentThread()) thread = null }
        }
    }

    private companion object {
        const val TAG = "UnboxSound"
        const val SAMPLE_RATE = 48000
        const val BLOCK = 256
    }
}
