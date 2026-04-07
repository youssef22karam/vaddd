package com.whisper.live

import android.content.Context

object SettingsManager {
    private const val P = "stt_offline_prefs"

    const val DEF_GAIN = 1.8f
    const val DEF_CHUNK = 0.2f
    const val DEF_SILENCE_THRESH = 0.008f
    const val DEF_SKIP_SILENCE = true
    const val DEF_TRANSCRIPT_TS = 18f
    const val DEF_CAPTION_TS = 15f
    const val DEF_CAPTION_LINES = 3
    const val DEF_CAPTION_H_DP = 0
    const val DEF_SELECTED_LANGUAGE = "en"
    const val DEF_SHERPA_THREADS = 2
    const val DEF_LAST_MODEL_ID = ""
    const val DEF_OVERLAY_X = 0
    const val DEF_OVERLAY_Y = 120

    var audioGain = DEF_GAIN
    var chunkDuration = DEF_CHUNK
    var silenceThreshold = DEF_SILENCE_THRESH
    var skipSilence = DEF_SKIP_SILENCE
    var transcriptTextSize = DEF_TRANSCRIPT_TS
    var captionTextSize = DEF_CAPTION_TS
    var captionMaxLines = DEF_CAPTION_LINES
    var captionHeightDp = DEF_CAPTION_H_DP
    var selectedLanguage = DEF_SELECTED_LANGUAGE
    var sherpaThreads = DEF_SHERPA_THREADS
    var lastModelId = DEF_LAST_MODEL_ID
    var overlayX = DEF_OVERLAY_X
    var overlayY = DEF_OVERLAY_Y

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
        audioGain = p.getFloat("gain", DEF_GAIN)
        chunkDuration = p.getFloat("chunk", DEF_CHUNK)
        silenceThreshold = p.getFloat("silence_thresh", DEF_SILENCE_THRESH)
        skipSilence = p.getBoolean("skip_silence", DEF_SKIP_SILENCE)
        transcriptTextSize = p.getFloat("ts_size", DEF_TRANSCRIPT_TS)
        captionTextSize = p.getFloat("cap_ts", DEF_CAPTION_TS)
        captionMaxLines = p.getInt("cap_lines", DEF_CAPTION_LINES)
        captionHeightDp = p.getInt("cap_height", DEF_CAPTION_H_DP)
        selectedLanguage = p.getString("selected_language", null)
            ?: p.getString("language", DEF_SELECTED_LANGUAGE)
            ?: DEF_SELECTED_LANGUAGE
        sherpaThreads = p.getInt("sherpa_threads", DEF_SHERPA_THREADS).coerceAtLeast(1)
        lastModelId = p.getString("last_model_id", DEF_LAST_MODEL_ID) ?: DEF_LAST_MODEL_ID
        overlayX = p.getInt("overlay_x", DEF_OVERLAY_X)
        overlayY = p.getInt("overlay_y", DEF_OVERLAY_Y)
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit().run {
            putFloat("gain", audioGain)
            putFloat("chunk", chunkDuration)
            putFloat("silence_thresh", silenceThreshold)
            putBoolean("skip_silence", skipSilence)
            putFloat("ts_size", transcriptTextSize)
            putFloat("cap_ts", captionTextSize)
            putInt("cap_lines", captionMaxLines)
            putInt("cap_height", captionHeightDp)
            putString("selected_language", selectedLanguage)
            putInt("sherpa_threads", sherpaThreads.coerceAtLeast(1))
            putString("last_model_id", lastModelId)
            putInt("overlay_x", overlayX)
            putInt("overlay_y", overlayY)
            apply()
        }
    }

    fun saveOverlayPos(ctx: Context, x: Int, y: Int) {
        overlayX = x
        overlayY = y
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit()
            .putInt("overlay_x", x)
            .putInt("overlay_y", y)
            .apply()
    }

    fun resetToDefaults(ctx: Context) {
        audioGain = DEF_GAIN
        chunkDuration = DEF_CHUNK
        silenceThreshold = DEF_SILENCE_THRESH
        skipSilence = DEF_SKIP_SILENCE
        transcriptTextSize = DEF_TRANSCRIPT_TS
        captionTextSize = DEF_CAPTION_TS
        captionMaxLines = DEF_CAPTION_LINES
        captionHeightDp = DEF_CAPTION_H_DP
        selectedLanguage = DEF_SELECTED_LANGUAGE
        sherpaThreads = DEF_SHERPA_THREADS
        lastModelId = DEF_LAST_MODEL_ID
        save(ctx)
    }
}
