package com.whisper.live

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.getOfflineModelConfig
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.sqrt

class SherpaOfflineEngine(
    private val appContext: Context,
    private val modelInfo: ModelDownloader.ModelInfo,
) : TranscriptionEngine {

    companion object {
        private const val TAG = "SherpaOfflineEngine"
        private const val SAMPLE_RATE = 16_000
        private const val MAX_WORDS_PER_LINE = 12

        // Faster segmentation for near-streaming behavior with final-only accuracy.
        private const val MIN_SILENCE_TO_COMMIT_MS = 160f
        private const val MAX_SEGMENT_SEC = 5f
    }

    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null

    private val pendingSegments: ArrayDeque<String> = ArrayDeque()

    override var isReady: Boolean = false
        private set

    override var lastResultWasPartial: Boolean = false
        private set

    override var newWords: List<String> = emptyList()
        private set

    override var isSegmentComplete: Boolean = false
        private set

    override var shouldStartNewLine: Boolean = false
        private set

    override var segmentText: String = ""
        private set

    // Fallback chunk-based VAD when Silero VAD is unavailable.
    private var inSpeech = false
    private var silenceMs = 0f
    private val fallbackBuffer = ArrayList<Float>(SAMPLE_RATE * 6)
    private var totalWordsInSegment = 0

    override fun initialize(modelPath: String): Boolean {
        release()
        val modelDir = File(modelPath)
        return try {
            val modelConfig = buildModelConfig(modelDir) ?: run {
                Log.e(TAG, "Unable to build offline model config for ${modelInfo.displayName}")
                return false
            }

            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80, dither = 0.0f),
                modelConfig = modelConfig,
                decodingMethod = "greedy_search",
                maxActivePaths = 4,
            )

            recognizer = OfflineRecognizer(null, config)
            setupVad()

            isReady = true
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Sherpa offline init failed: ${t.message}", t)
            release()
            false
        }
    }

    override fun setLanguage(langCode: String) = Unit

    override fun transcribe(audioData: FloatArray): String {
        newWords = emptyList()
        isSegmentComplete = false
        shouldStartNewLine = false
        segmentText = ""

        if (!isReady) return ""

        try {
            if (pendingSegments.isNotEmpty()) {
                emitSegment(pendingSegments.removeFirst())
                return segmentText
            }

            val vadInstance = vad
            if (vadInstance != null) {
                // Silero segments speech and emits final chunks quickly.
                vadInstance.acceptWaveform(audioData)
                while (!vadInstance.empty()) {
                    val samples = vadInstance.front().samples
                    vadInstance.pop()
                    decodeSegment(samples)?.let { pendingSegments.addLast(it) }
                }
            } else {
                processFallbackVad(audioData)
            }

            if (pendingSegments.isNotEmpty()) {
                emitSegment(pendingSegments.removeFirst())
                return segmentText
            }

            // No final segment yet, but likely mid-utterance.
            lastResultWasPartial = inSpeech
        } catch (t: Throwable) {
            Log.e(TAG, "Sherpa offline transcribe error: ${t.message}", t)
        }

        return ""
    }

    override fun release() {
        try {
            vad?.release()
        } catch (_: Throwable) {
        }
        try {
            recognizer?.release()
        } catch (_: Throwable) {
        }

        vad = null
        recognizer = null

        pendingSegments.clear()
        fallbackBuffer.clear()
        inSpeech = false
        silenceMs = 0f
        totalWordsInSegment = 0

        isReady = false
        lastResultWasPartial = false
        newWords = emptyList()
        isSegmentComplete = false
        shouldStartNewLine = false
        segmentText = ""
    }

    private fun emitSegment(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) {
            lastResultWasPartial = true
            return
        }

        val words = splitWords(clean)
        segmentText = clean
        newWords = if (words.isNotEmpty()) words else listOf(clean)
        isSegmentComplete = true
        shouldStartNewLine = crossesLineBoundary(newWords.size)
        totalWordsInSegment += newWords.size
        lastResultWasPartial = false

        // Segment committed; reset line-budget tracker.
        totalWordsInSegment = 0
    }

    private fun setupVad() {
        try {
            val vadFile = ensureVadModelFile()
            val config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = vadFile.absolutePath,
                    threshold = 0.40f,
                    minSilenceDuration = 0.16f,
                    minSpeechDuration = 0.08f,
                    windowSize = 512,
                    maxSpeechDuration = MAX_SEGMENT_SEC,
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
                debug = false,
            )
            vad = Vad(null, config)
        } catch (t: Throwable) {
            Log.w(TAG, "Silero VAD unavailable, using fallback VAD: ${t.message}")
            vad = null
        }
    }

    private fun ensureVadModelFile(): File {
        val out = File(appContext.filesDir, "silero_vad.onnx")
        if (out.exists() && out.length() > 0) return out

        appContext.assets.open("silero_vad.onnx").use { input ->
            FileOutputStream(out).use { output ->
                input.copyTo(output)
            }
        }
        return out
    }

    private fun processFallbackVad(audioData: FloatArray) {
        val chunkMs = (audioData.size.toFloat() / SAMPLE_RATE.toFloat()) * 1000f
        val rms = calculateRms(audioData)

        val speechThreshold = max(SettingsManager.silenceThreshold * 1.15f, 0.0035f)
        val silenceThreshold = speechThreshold * 0.78f

        when {
            rms >= speechThreshold -> {
                inSpeech = true
                silenceMs = 0f
                appendToFallbackBuffer(audioData)
            }

            inSpeech -> {
                appendToFallbackBuffer(audioData)
                if (rms < silenceThreshold) {
                    silenceMs += chunkMs
                } else {
                    silenceMs = 0f
                }

                val maxSamples = (MAX_SEGMENT_SEC * SAMPLE_RATE).toInt()
                val readyBySilence = silenceMs >= MIN_SILENCE_TO_COMMIT_MS
                val readyByLength = fallbackBuffer.size >= maxSamples

                if (readyBySilence || readyByLength) {
                    val segment = consumeFallbackBuffer()
                    inSpeech = false
                    silenceMs = 0f
                    decodeSegment(segment)?.let { pendingSegments.addLast(it) }
                }
            }

            else -> {
                lastResultWasPartial = false
            }
        }
    }

    private fun crossesLineBoundary(incomingCount: Int): Boolean {
        val nextTotal = totalWordsInSegment + incomingCount
        return nextTotal >= MAX_WORDS_PER_LINE &&
            (nextTotal % MAX_WORDS_PER_LINE) < incomingCount
    }

    private fun buildModelConfig(modelDir: File): OfflineModelConfig? {
        val config = modelInfo.sherpaPresetType?.let { getOfflineModelConfig(it) }
            ?: buildWhisperSmallConfig(modelDir)
            ?: buildFallbackModelConfig(modelDir)
            ?: return null

        absolutize(config, modelDir)
        config.numThreads = SettingsManager.sherpaThreads.coerceAtLeast(1)
        config.provider = "cpu"
        config.debug = false

        if (modelInfo.modelType.equals("whisper", ignoreCase = true) &&
            config.whisper.language.isBlank()
        ) {
            config.whisper.language = if (modelInfo.langCode == "multi") "" else modelInfo.langCode
            config.whisper.task = "transcribe"
            config.whisper.tailPaddings = 320
        }

        return config
    }

    private fun buildWhisperSmallConfig(modelDir: File): OfflineModelConfig? {
        if (!modelInfo.modelType.equals("whisper", ignoreCase = true)) return null
        if (!modelInfo.dirName.contains("whisper-small", ignoreCase = true)) return null

        val encoder = findFile(modelDir, mustContain = listOf("encoder"), extensions = listOf(".onnx"))
            ?: return null
        val decoder = findFile(modelDir, mustContain = listOf("decoder"), extensions = listOf(".onnx"))
            ?: return null
        val tokens = findFile(modelDir, mustContain = listOf("tokens"), extensions = listOf(".txt"))
            ?: return null

        return OfflineModelConfig(
            whisper = OfflineWhisperModelConfig(
                encoder = encoder.absolutePath,
                decoder = decoder.absolutePath,
                language = "",
                task = "transcribe",
                tailPaddings = 320,
            ),
            tokens = tokens.absolutePath,
            modelType = "whisper",
        )
    }

    private fun buildFallbackModelConfig(modelDir: File): OfflineModelConfig? {
        val tokens = findFile(modelDir, mustContain = listOf("tokens"), extensions = listOf(".txt"))
            ?: return null

        return when {
            modelInfo.modelType.contains("moonshine", ignoreCase = true) -> {
                val pre = findFile(modelDir, mustContain = listOf("preprocess"), extensions = listOf(".onnx"))
                    ?: findFile(modelDir, mustContain = listOf("preprocessor"), extensions = listOf(".onnx"))
                    ?: return null
                val enc = findFile(modelDir, mustContain = listOf("enc"), extensions = listOf(".onnx"))
                    ?: return null
                val uncached = findFile(modelDir, mustContain = listOf("uncached"), extensions = listOf(".onnx"))
                    ?: return null
                val cached = findFile(modelDir, mustContain = listOf("cached"), extensions = listOf(".onnx"))
                    ?: return null
                OfflineModelConfig(
                    moonshine = com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig(
                        preprocessor = pre.absolutePath,
                        encoder = enc.absolutePath,
                        uncachedDecoder = uncached.absolutePath,
                        cachedDecoder = cached.absolutePath,
                    ),
                    tokens = tokens.absolutePath,
                )
            }

            modelInfo.modelType.contains("nemo_transducer", ignoreCase = true) ||
                modelInfo.modelType.contains("transducer", ignoreCase = true) ||
                modelInfo.modelType.contains("zipformer_offline", ignoreCase = true) -> {
                val encoder = findFile(modelDir, mustContain = listOf("encoder"), extensions = listOf(".onnx"))
                    ?: return null
                val decoder = findFile(modelDir, mustContain = listOf("decoder"), extensions = listOf(".onnx"))
                    ?: return null
                val joiner = findFile(modelDir, mustContain = listOf("joiner"), extensions = listOf(".onnx"))
                    ?: return null
                OfflineModelConfig(
                    transducer = com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig(
                        encoder = encoder.absolutePath,
                        decoder = decoder.absolutePath,
                        joiner = joiner.absolutePath,
                    ),
                    tokens = tokens.absolutePath,
                    modelType = "transducer",
                )
            }

            modelInfo.modelType.contains("nemo_ctc", ignoreCase = true) -> {
                val model = findFile(modelDir, mustContain = listOf("model"), extensions = listOf(".onnx"))
                    ?: return null
                OfflineModelConfig(
                    nemo = com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig(
                        model = model.absolutePath,
                    ),
                    tokens = tokens.absolutePath,
                )
            }

            modelInfo.modelType.contains("sense", ignoreCase = true) -> {
                val model = findFile(modelDir, mustContain = listOf("model"), extensions = listOf(".onnx"))
                    ?: return null
                OfflineModelConfig(
                    senseVoice = com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig(
                        model = model.absolutePath,
                    ),
                    tokens = tokens.absolutePath,
                )
            }

            modelInfo.modelType.contains("dolphin", ignoreCase = true) -> {
                val model = findFile(modelDir, mustContain = listOf("model"), extensions = listOf(".onnx"))
                    ?: return null
                OfflineModelConfig(
                    dolphin = com.k2fsa.sherpa.onnx.OfflineDolphinModelConfig(
                        model = model.absolutePath,
                    ),
                    tokens = tokens.absolutePath,
                )
            }

            else -> null
        }
    }

    private fun decodeSegment(samples: FloatArray): String? {
        if (samples.isEmpty()) return null
        val rec = recognizer ?: return null

        val stream = rec.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            rec.decode(stream)
            rec.getResult(stream).text.trim().takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            Log.e(TAG, "Segment decode failed: ${t.message}", t)
            null
        } finally {
            try {
                stream.release()
            } catch (_: Throwable) {
            }
        }
    }

    private fun absolutize(config: OfflineModelConfig, modelDir: File) {
        config.tokens = absolutePath(config.tokens, modelDir)
        config.bpeVocab = absolutePath(config.bpeVocab, modelDir)

        config.transducer.encoder = absolutePath(config.transducer.encoder, modelDir)
        config.transducer.decoder = absolutePath(config.transducer.decoder, modelDir)
        config.transducer.joiner = absolutePath(config.transducer.joiner, modelDir)

        config.paraformer.model = absolutePath(config.paraformer.model, modelDir)

        config.whisper.encoder = absolutePath(config.whisper.encoder, modelDir)
        config.whisper.decoder = absolutePath(config.whisper.decoder, modelDir)

        config.fireRedAsr.encoder = absolutePath(config.fireRedAsr.encoder, modelDir)
        config.fireRedAsr.decoder = absolutePath(config.fireRedAsr.decoder, modelDir)

        config.moonshine.preprocessor = absolutePath(config.moonshine.preprocessor, modelDir)
        config.moonshine.encoder = absolutePath(config.moonshine.encoder, modelDir)
        config.moonshine.uncachedDecoder = absolutePath(config.moonshine.uncachedDecoder, modelDir)
        config.moonshine.cachedDecoder = absolutePath(config.moonshine.cachedDecoder, modelDir)

        config.nemo.model = absolutePath(config.nemo.model, modelDir)
        config.senseVoice.model = absolutePath(config.senseVoice.model, modelDir)
        config.dolphin.model = absolutePath(config.dolphin.model, modelDir)
        config.zipformerCtc.model = absolutePath(config.zipformerCtc.model, modelDir)
        config.wenetCtc.model = absolutePath(config.wenetCtc.model, modelDir)
        config.omnilingual.model = absolutePath(config.omnilingual.model, modelDir)
        config.teleSpeech = absolutePath(config.teleSpeech, modelDir)

        config.canary.encoder = absolutePath(config.canary.encoder, modelDir)
        config.canary.decoder = absolutePath(config.canary.decoder, modelDir)
    }

    private fun absolutePath(path: String, modelDir: File): String {
        if (path.isBlank()) return ""
        val normalized = path.replace('\\', '/')
        if (File(normalized).isAbsolute) return normalized

        val prefix = "${modelDir.name}/"
        val relative = if (normalized.startsWith(prefix)) normalized.removePrefix(prefix) else normalized
        return File(modelDir, relative.replace('/', File.separatorChar)).absolutePath
    }

    private fun appendToFallbackBuffer(samples: FloatArray) {
        fallbackBuffer.ensureCapacity(fallbackBuffer.size + samples.size)
        for (value in samples) fallbackBuffer.add(value)
    }

    private fun consumeFallbackBuffer(): FloatArray {
        val arr = FloatArray(fallbackBuffer.size)
        for (i in fallbackBuffer.indices) arr[i] = fallbackBuffer[i]
        fallbackBuffer.clear()
        return arr
    }

    private fun calculateRms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (v in samples) {
            sum += v * v
        }
        return sqrt((sum / samples.size).toFloat())
    }

    private fun splitWords(text: String): List<String> =
        text.split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun findFile(
        root: File,
        mustContain: List<String>,
        extensions: List<String>,
    ): File? {
        val normalizedContains = mustContain.map { it.lowercase() }
        val normalizedExts = extensions.map { it.lowercase() }

        return root.walkTopDown()
            .filter { it.isFile }
            .filter { file ->
                val name = file.name.lowercase()
                normalizedExts.isEmpty() || normalizedExts.any { ext -> name.endsWith(ext) }
            }
            .firstOrNull { file ->
                val path = file.absolutePath.lowercase().replace('\\', '/')
                normalizedContains.all { path.contains(it) }
            }
    }
}
