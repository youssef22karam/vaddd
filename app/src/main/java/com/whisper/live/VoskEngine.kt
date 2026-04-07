package com.whisper.live

import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Vosk-based transcription engine — real-time streaming, word-by-word.
 *
 * Mirrors the logic from Radio vosk.py:
 *   • Tracks words already displayed ([lastPartialWords])
 *   • Returns only NEW words via [newWords]
 *   • On natural pauses (final results), commits the segment.
 *   • Starts a new visual line after [MAX_WORDS_PER_LINE] words
 *     WITHOUT resetting the recognizer (prevents word loss).
 *
 * Model sharing: call [initializeWithModel] to reuse a Model object
 * across two engines (mic + phone audio) to save memory.
 */
class VoskEngine : TranscriptionEngine {

    companion object {
        private const val TAG                 = "VoskEngine"
        private const val SAMPLE_HZ           = 16_000f
        const val MAX_WORDS_PER_LINE          = 12

        // ── Shared model (loaded once, used by both mic and phone engines) ───
        private var sharedModel: Model?  = null
        private var sharedModelPath: String? = null

        @Synchronized
        fun loadSharedModel(path: String): Boolean {
            if (sharedModelPath == path && sharedModel != null) return true
            return try {
                sharedModel?.close()
                sharedModel = Model(path)
                sharedModelPath = path
                Log.i(TAG, "Shared model loaded from $path")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Model load failed: ${e.message}")
                false
            }
        }

        fun releaseSharedModel() {
            sharedModel?.close()
            sharedModel = null
            sharedModelPath = null
        }
    }

    private var recognizer: Recognizer? = null

    override var isReady: Boolean = false
        private set

    override var lastResultWasPartial: Boolean = false
        private set

    // ── Word-by-word tracking (mirrors Python's last_partial_words) ──────────
    private var lastPartialWords: List<String> = emptyList()
    private var totalWordsInSegment: Int = 0

    /** New words recognized since the last [transcribe] call. */
    override var newWords: List<String> = emptyList()
        private set

    /** True when a segment boundary was hit (natural pause or line-break). */
    override var isSegmentComplete: Boolean = false
        private set

    /** True when we should start a new visual line (word count threshold). */
    override var shouldStartNewLine: Boolean = false
        private set

    /** The full segment text at completion. */
    override var segmentText: String = ""
        private set

    // ── TranscriptionEngine ───────────────────────────────────────────────────

    override fun initialize(modelPath: String): Boolean {
        return try {
            loadSharedModel(modelPath)
            createRecognizer()
            isReady = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Vosk init failed: ${e.message}")
            false
        }
    }

    /** Initialize using the already-loaded shared model (no path needed). */
    fun initializeFromShared(): Boolean {
        if (sharedModel == null) return false
        return try {
            createRecognizer()
            isReady = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Vosk init from shared failed: ${e.message}")
            false
        }
    }

    override fun setLanguage(langCode: String) = Unit

    /**
     * Feed [audioData] (Float32 PCM, 16 kHz) to the Vosk recognizer.
     *
     * After each call, read:
     *   - [newWords]           → words to append to current line
     *   - [isSegmentComplete]  → true = finalize current line (natural pause)
     *   - [shouldStartNewLine] → true = start new visual line (word count)
     *   - [segmentText]        → full text for the completed segment
     */
    override fun transcribe(audioData: FloatArray): String {
        val rec = recognizer ?: return ""

        // Reset per-call outputs
        newWords = emptyList()
        isSegmentComplete = false
        shouldStartNewLine = false
        segmentText = ""

        // Vosk needs Int16 PCM
        val shorts = ShortArray(audioData.size) { i ->
            (audioData[i] * 32_767f).toInt().coerceIn(-32_767, 32_767).toShort()
        }

        return try {
            val isFinal = rec.acceptWaveForm(shorts, shorts.size)

            if (isFinal) {
                // ── FINAL RESULT (Vosk detected a pause) ─────────────────────
                lastResultWasPartial = false
                val json = rec.result
                val text = parseText(json, true)

                if (text.isNotBlank()) {
                    val currentWords = text.split(" ").filter { it.isNotBlank() }
                    // Emit any remaining new words not yet shown
                    if (currentWords.size > lastPartialWords.size) {
                        newWords = currentWords.subList(lastPartialWords.size, currentWords.size)
                    }
                    isSegmentComplete = true
                    segmentText = text
                }

                // Reset tracking for next segment
                lastPartialWords = emptyList()
                totalWordsInSegment = 0
                text

            } else {
                // ── PARTIAL RESULT (still speaking) ──────────────────────────
                lastResultWasPartial = true
                val json = rec.partialResult
                val text = parseText(json, false)

                if (text.isNotBlank()) {
                    val currentWords = text.split(" ").filter { it.isNotBlank() }

                    // Find NEW words only (mirroring Python's logic)
                    if (currentWords.size > lastPartialWords.size) {
                        newWords = currentWords.subList(lastPartialWords.size, currentWords.size)
                        lastPartialWords = currentWords
                        totalWordsInSegment = currentWords.size

                        // Signal new visual line every N words (NO recognizer reset)
                        if (totalWordsInSegment >= MAX_WORDS_PER_LINE &&
                            totalWordsInSegment % MAX_WORDS_PER_LINE < newWords.size) {
                            shouldStartNewLine = true
                        }
                    }
                }

                text
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vosk transcribe error: ${e.message}")
            lastResultWasPartial = false
            newWords = emptyList()
            ""
        }
    }

    override fun release() {
        try { recognizer?.close() } catch (_: Exception) {}
        recognizer = null
        isReady = false
        lastPartialWords = emptyList()
        totalWordsInSegment = 0
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun createRecognizer() {
        val m = sharedModel ?: throw IllegalStateException("No shared model loaded")
        recognizer = Recognizer(m, SAMPLE_HZ).apply {
            setWords(true)
            setPartialWords(true)
        }
    }

    private fun parseText(json: String, isFinal: Boolean): String {
        if (json.isBlank()) return ""
        return try {
            val obj = JSONObject(json)
            obj.optString(if (isFinal) "text" else "partial", "").trim()
        } catch (_: Exception) { "" }
    }
}
