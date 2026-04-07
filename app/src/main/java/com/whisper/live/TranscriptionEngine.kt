package com.whisper.live

/**
 * Common interface for speech-to-text engines.
 *
 * Engines emit incremental words when possible (streaming) and segment boundaries
 * when a phrase is complete.
 */
interface TranscriptionEngine {
    val isReady: Boolean
    val lastResultWasPartial: Boolean

    val newWords: List<String>
    val isSegmentComplete: Boolean
    val shouldStartNewLine: Boolean
    val segmentText: String

    /** Load the model from [modelPath]. */
    fun initialize(modelPath: String): Boolean

    /** Optional language override (engine/model dependent). */
    fun setLanguage(langCode: String)

    /**
     * Feed 16 kHz mono PCM Float32 samples in range [-1, 1].
     * Returns the latest engine text hypothesis.
     */
    fun transcribe(audioData: FloatArray): String

    fun release()
}
