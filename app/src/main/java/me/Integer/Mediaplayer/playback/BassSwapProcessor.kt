package me.Integer.Mediaplayer.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real-time 2nd-order Butterworth high-pass filter (< 150 Hz) attached to each DJ deck.
 * During a DJ crossfade, the incoming deck starts with [bassCut] = 1.0 (sub-bass attenuated so
 * kick drums and basslines don't clash) and smoothly swaps bass with the outgoing deck at the
 * midpoint of the transition. When [bassCut] is 0.0, audio passes through untouched.
 */
internal class BassSwapProcessor : BaseAudioProcessor() {

    @Volatile
    var bassCut: Float = 0f

    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0

    // Per-channel biquad delay lines (supports up to 8 channels)
    private val x1 = DoubleArray(MAX_CHANNELS)
    private val x2 = DoubleArray(MAX_CHANNELS)
    private val y1 = DoubleArray(MAX_CHANNELS)
    private val y2 = DoubleArray(MAX_CHANNELS)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT ||
            inputAudioFormat.channelCount !in 1..MAX_CHANNELS
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        computeCoefficients(inputAudioFormat.sampleRate.toDouble())
        return inputAudioFormat
    }

    override fun onFlush() {
        x1.fill(0.0)
        x2.fill(0.0)
        y1.fill(0.0)
        y2.fill(0.0)
    }

    override fun onReset() {
        onFlush()
        bassCut = 0f
    }

    private fun computeCoefficients(sampleRate: Double) {
        val sr = sampleRate.coerceAtLeast(8000.0)
        val w0 = 2.0 * PI * CUTOFF_HZ / sr
        val cosW0 = cos(w0)
        val alpha = sin(w0) / sqrt(2.0)
        val a0 = 1.0 + alpha
        b0 = ((1.0 + cosW0) / 2.0) / a0
        b1 = -(1.0 + cosW0) / a0
        b2 = ((1.0 + cosW0) / 2.0) / a0
        a1 = (-2.0 * cosW0) / a0
        a2 = (1.0 - alpha) / a0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val cut = bassCut.coerceIn(0f, 1f)
        val out = replaceOutputBuffer(remaining)
        if (cut <= 0.005f) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        val channels = inputAudioFormat.channelCount
        val dryMix = 1.0 - cut * MAX_CUT_DEPTH
        val wetMix = cut * MAX_CUT_DEPTH
        while (inputBuffer.remaining() >= 2 * channels) {
            for (ch in 0 until channels) {
                val sample = inputBuffer.short.toDouble()
                val hp = b0 * sample + b1 * x1[ch] + b2 * x2[ch] - a1 * y1[ch] - a2 * y2[ch]
                x2[ch] = x1[ch]
                x1[ch] = sample
                y2[ch] = y1[ch]
                y1[ch] = hp
                val blended = (dryMix * sample + wetMix * hp).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                out.putShort(blended.toShort())
            }
        }
        if (inputBuffer.hasRemaining()) {
            out.put(inputBuffer)
        }
        out.flip()
    }

    private companion object {
        const val CUTOFF_HZ = 145.0
        const val MAX_CUT_DEPTH = 0.88
        const val MAX_CHANNELS = 8
    }
}
