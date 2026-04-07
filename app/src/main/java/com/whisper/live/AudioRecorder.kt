package com.whisper.live

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Records microphone audio in configurable-length chunks at 16 kHz mono,
 * converting Short PCM → Float32 and delivering to [onAudioChunk].
 *
 * Key improvements over v1:
 *  • Configurable chunk size (from SettingsManager.chunkDuration)
 *  • Configurable gain multiplier — boosts quiet mic inputs so Vosk
 *    can recognise speech that would otherwise appear as near-silence.
 *  • VAD silence gate — chunks whose RMS energy is below the threshold are
 *    skipped rather than fed to the (slow) inference engine.
 *  • Audio is clipped to ±1.0 after gain to prevent distortion.
 */
class AudioRecorder(
    private val includeSilenceChunks: Boolean = false,
    private val onAudioChunk: suspend (FloatArray) -> Unit,
) {
    companion object {
        private const val TAG         = "AudioRecorder"
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL     = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING    = AudioFormat.ENCODING_PCM_16BIT
        private const val MIN_OFFLINE_CHUNK_SEC = 0.12f
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true

        scope.launch {
            // Read fresh settings each time recording starts
            val configuredChunkSec = SettingsManager.chunkDuration.coerceAtLeast(0.08f)
            val chunkSec = if (includeSilenceChunks) {
                configuredChunkSec.coerceAtMost(MIN_OFFLINE_CHUNK_SEC)
            } else {
                configuredChunkSec
            }
            val configuredGain = SettingsManager.audioGain
            val gain = if (includeSilenceChunks) configuredGain.coerceAtMost(2.0f) else configuredGain
            val skipSil     = SettingsManager.skipSilence
            val silThresh   = SettingsManager.silenceThreshold
            val shouldSkipSilence = skipSil && !includeSilenceChunks

            val minBuf       = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            val chunkSamples = (SAMPLE_RATE * chunkSec).toInt()
            val bufferBytes  = maxOf(minBuf, chunkSamples * 2)

            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, CHANNEL, ENCODING, bufferBytes
            )

            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                return@launch
            }

            rec.startRecording()
            Log.i(
                TAG,
                "Recording @ ${SAMPLE_RATE} Hz, chunk=${chunkSec}s (configured=${configuredChunkSec}s), " +
                    "gain=${gain}x, skipSilence=$shouldSkipSilence (thresh=$silThresh)",
            )

            val shortBuf = ShortArray(chunkSamples)

            while (running) {
                var totalRead = 0
                while (totalRead < chunkSamples && running) {
                    val n = rec.read(shortBuf, totalRead, chunkSamples - totalRead)
                    if (n <= 0) break
                    totalRead += n
                }
                if (totalRead == 0) continue

                // Convert Int16 → Float32
                val raw = FloatArray(totalRead) { i -> shortBuf[i] / 32_768f }

                // ── Voice Activity Detection ──────────────────────────────────
                // Compute RMS energy; skip chunk if below silence threshold.
                val rms = sqrt(raw.fold(0.0) { acc, v -> acc + v * v }.toFloat() / totalRead)
                if (shouldSkipSilence && rms < silThresh) {
                    Log.v(TAG, "Skipping silent chunk (RMS=${"%.5f".format(rms)})")
                    continue
                }

                // ── Gain + clip ───────────────────────────────────────────────
                val boosted = applyGainWithSoftLimit(raw, gain)

                Log.d(TAG, "Chunk RMS=${"%.4f".format(rms)} → sending ${totalRead} samples")
                onAudioChunk(boosted)
            }

            rec.stop()
            rec.release()
            Log.i(TAG, "Recording stopped")
        }
    }

    fun stop() {
        running = false
        scope.cancel()
    }

    private fun applyGainWithSoftLimit(raw: FloatArray, gain: Float): FloatArray {
        if (gain == 1.0f) return raw

        val boosted = FloatArray(raw.size)
        var peak = 0f
        for (i in raw.indices) {
            val v = raw[i] * gain
            boosted[i] = v
            val a = abs(v)
            if (a > peak) peak = a
        }

        val scale = if (peak > 0.98f) 0.98f / peak else 1f
        if (scale == 1f) return boosted

        for (i in boosted.indices) {
            boosted[i] *= scale
        }
        return boosted
    }
}
