package com.whisper.live

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Foreground service that captures **internal/system audio**
 * (what's playing on the phone — music, videos, calls, etc.)
 * using Android's AudioPlaybackCapture API (Android 10+ / API 29+).
 *
 * Flow:
 * 1. MainActivity requests MediaProjection permission (system dialog)
 * 2. On approval, starts this service with the projection result
 * 3. This service captures audio and feeds it to [onAudioChunk]
 *
 * Call [AudioCaptureService.onAudioChunk] to set the audio callback
 * before starting the service.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class AudioCaptureService : Service() {

    companion object {
        private const val TAG          = "AudioCaptureService"
        private const val CHANNEL_ID   = "audio_capture_channel"
        private const val NOTIF_ID     = 2002
        private const val SAMPLE_RATE  = 16_000
        private const val MIN_OFFLINE_CHUNK_SEC = 0.12f
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA        = "data"

        @Volatile
        var instance: AudioCaptureService? = null
            private set

        /** Callback set by MainActivity to receive audio chunks. */
        var onAudioChunk: (suspend (FloatArray) -> Unit)? = null
        var forceIncludeSilenceChunks: Boolean = false

        fun createIntent(ctx: Context, resultCode: Int, data: Intent): Intent {
            return Intent(ctx, AudioCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var recording = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }

        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.e(TAG, "Invalid projection result")
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIF_ID, buildNotification())

        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        if (mediaProjection == null) {
            Log.e(TAG, "Failed to get MediaProjection")
            stopSelf()
            return START_NOT_STICKY
        }

        startCapture()
        return START_NOT_STICKY
    }

    private fun startCapture() {
        val projection = mediaProjection ?: return

        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val configuredChunkSec = SettingsManager.chunkDuration.coerceAtLeast(0.08f)
            val chunkSec = if (forceIncludeSilenceChunks) {
                configuredChunkSec.coerceAtMost(MIN_OFFLINE_CHUNK_SEC)
            } else {
                configuredChunkSec
            }
            val chunkSamples = (SAMPLE_RATE * chunkSec).toInt()
            val minBuf       = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferBytes = maxOf(minBuf, chunkSamples * 2)

            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferBytes)
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize for playback capture")
                stopSelf()
                return
            }

            recording = true
            audioRecord?.startRecording()
            Log.i(
                TAG,
                "Internal audio capture started @ $SAMPLE_RATE Hz, " +
                    "chunk=${chunkSec}s (configured=${configuredChunkSec}s)",
            )

            scope.launch {
                val configuredGain = SettingsManager.audioGain
                val gain = if (forceIncludeSilenceChunks) configuredGain.coerceAtMost(2.0f) else configuredGain
                val skipSil   = SettingsManager.skipSilence
                val silThresh = SettingsManager.silenceThreshold
                val shouldSkipSilence = skipSil && !forceIncludeSilenceChunks
                val shortBuf  = ShortArray(chunkSamples)

                while (recording) {
                    var totalRead = 0
                    while (totalRead < chunkSamples && recording) {
                        val n = audioRecord?.read(shortBuf, totalRead, chunkSamples - totalRead) ?: 0
                        if (n <= 0) break
                        totalRead += n
                    }
                    if (totalRead == 0) continue

                    // Convert Int16 → Float32
                    val raw = FloatArray(totalRead) { i -> shortBuf[i] / 32_768f }

                    // VAD — skip silence
                    val rms = sqrt(raw.fold(0.0) { acc, v -> acc + v * v }.toFloat() / totalRead)
                    if (shouldSkipSilence && rms < silThresh) continue

                    // Gain + clip
                    val boosted = applyGainWithSoftLimit(raw, gain)

                    onAudioChunk?.invoke(boosted)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio capture error: ${e.message}")
            stopSelf()
        }
    }

    fun stopCapture() {
        recording = false
        scope.cancel()
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        audioRecord = null
        mediaProjection = null
    }

    override fun onDestroy() {
        stopCapture()
        instance = null
        super.onDestroy()
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Internal Audio Capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Recording phone audio for transcription" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("STT Offline")
            .setContentText("Capturing phone audio for transcription…")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
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
