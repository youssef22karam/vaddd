package com.whisper.live

import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineNeMoCtcModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import com.k2fsa.sherpa.onnx.getModelConfig
import java.io.File

class SherpaOnlineEngine(
    private val modelInfo: ModelDownloader.ModelInfo,
) : TranscriptionEngine {

    companion object {
        private const val TAG = "SherpaOnlineEngine"
        private const val SAMPLE_RATE = 16_000
        private const val MAX_WORDS_PER_LINE = 12
    }

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null

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

    private var lastPartialWords: List<String> = emptyList()
    private var totalWordsInSegment: Int = 0

    override fun initialize(modelPath: String): Boolean {
        release()
        val modelDir = File(modelPath)
        return try {
            if (!buildModelConfig(modelDir)) {
                Log.e(TAG, "Unable to build online model config for ${modelInfo.displayName}")
                return false
            }

            isReady = true
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Sherpa online init failed: ${t.message}", t)
            release()
            false
        }
    }

    override fun setLanguage(langCode: String) = Unit

    override fun transcribe(audioData: FloatArray): String {
        val rec = recognizer ?: return ""
        val st = stream ?: return ""

        newWords = emptyList()
        isSegmentComplete = false
        shouldStartNewLine = false
        segmentText = ""

        return try {
            st.acceptWaveform(audioData, SAMPLE_RATE)
            while (rec.isReady(st)) {
                rec.decode(st)
            }

            val text = rec.getResult(st).text.trim()
            val words = splitWords(text)
            newWords = diffWords(lastPartialWords, words)
            lastPartialWords = words
            totalWordsInSegment = words.size

            if (totalWordsInSegment >= MAX_WORDS_PER_LINE &&
                totalWordsInSegment % MAX_WORDS_PER_LINE < newWords.size
            ) {
                shouldStartNewLine = true
            }

            if (rec.isEndpoint(st)) {
                if (text.isNotBlank()) {
                    isSegmentComplete = true
                    segmentText = text
                    if (newWords.isEmpty()) {
                        newWords = words
                    }
                }
                rec.reset(st)
                lastPartialWords = emptyList()
                totalWordsInSegment = 0
                lastResultWasPartial = false
            } else {
                lastResultWasPartial = text.isNotBlank()
            }

            text
        } catch (t: Throwable) {
            Log.e(TAG, "Sherpa online transcribe error: ${t.message}", t)
            ""
        }
    }

    override fun release() {
        try {
            stream?.release()
        } catch (_: Throwable) {
        }
        try {
            recognizer?.release()
        } catch (_: Throwable) {
        }

        stream = null
        recognizer = null
        isReady = false
        lastPartialWords = emptyList()
        totalWordsInSegment = 0
        newWords = emptyList()
        isSegmentComplete = false
        shouldStartNewLine = false
        segmentText = ""
    }

    private fun buildModelConfig(modelDir: File): Boolean {
        val modelConfig = modelInfo.sherpaPresetType?.let { getModelConfig(it) }
            ?: buildFallbackModelConfig(modelDir)

        val cfg = modelConfig ?: return false

        absolutize(cfg, modelDir)
        cfg.numThreads = SettingsManager.sherpaThreads.coerceAtLeast(1)
        cfg.provider = "cpu"
        cfg.debug = false

        val endpoint = EndpointConfig(
            rule1 = EndpointRule(false, 0.8f, 0.0f),
            rule2 = EndpointRule(true, 0.35f, 0.0f),
            rule3 = EndpointRule(false, 0.0f, 15.0f),
        )

        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80, dither = 0.0f),
            modelConfig = cfg,
            endpointConfig = endpoint,
            enableEndpoint = true,
            decodingMethod = "greedy_search",
            maxActivePaths = 4,
        )

        recognizer = OnlineRecognizer(null, config)
        stream = recognizer?.createStream()
        return stream != null
    }

    private fun buildFallbackModelConfig(modelDir: File): OnlineModelConfig? {
        val tokens = findFile(modelDir, mustContain = listOf("tokens"), extensions = listOf(".txt"))
            ?: return null

        return when {
            modelInfo.modelType.contains("nemo", ignoreCase = true) -> {
                val model = findFile(modelDir, mustContain = listOf("model"), extensions = listOf(".onnx"))
                    ?: findFile(modelDir, mustContain = emptyList(), extensions = listOf(".onnx"))
                    ?: return null
                OnlineModelConfig(
                    neMoCtc = OnlineNeMoCtcModelConfig(model = model.absolutePath),
                    tokens = tokens.absolutePath,
                    modelType = "",
                )
            }

            modelInfo.modelType.contains("ctc", ignoreCase = true) -> {
                val model = findFile(modelDir, mustContain = listOf("model"), extensions = listOf(".onnx"))
                    ?: findFile(modelDir, mustContain = emptyList(), extensions = listOf(".onnx"))
                    ?: return null
                OnlineModelConfig(
                    zipformer2Ctc = OnlineZipformer2CtcModelConfig(model = model.absolutePath),
                    tokens = tokens.absolutePath,
                    modelType = "zipformer2",
                )
            }

            else -> {
                val encoder = findFile(modelDir, mustContain = listOf("encoder"), extensions = listOf(".onnx"))
                    ?: return null
                val decoder = findFile(modelDir, mustContain = listOf("decoder"), extensions = listOf(".onnx"))
                    ?: return null
                val joiner = findFile(modelDir, mustContain = listOf("joiner"), extensions = listOf(".onnx"))
                    ?: return null
                OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = encoder.absolutePath,
                        decoder = decoder.absolutePath,
                        joiner = joiner.absolutePath,
                    ),
                    tokens = tokens.absolutePath,
                    modelType = "zipformer2",
                )
            }
        }
    }

    private fun absolutize(config: OnlineModelConfig, modelDir: File) {
        config.tokens = absolutePath(config.tokens, modelDir)
        config.bpeVocab = absolutePath(config.bpeVocab, modelDir)

        config.transducer.encoder = absolutePath(config.transducer.encoder, modelDir)
        config.transducer.decoder = absolutePath(config.transducer.decoder, modelDir)
        config.transducer.joiner = absolutePath(config.transducer.joiner, modelDir)

        config.paraformer.encoder = absolutePath(config.paraformer.encoder, modelDir)
        config.paraformer.decoder = absolutePath(config.paraformer.decoder, modelDir)

        config.zipformer2Ctc.model = absolutePath(config.zipformer2Ctc.model, modelDir)
        config.neMoCtc.model = absolutePath(config.neMoCtc.model, modelDir)
        config.toneCtc.model = absolutePath(config.toneCtc.model, modelDir)
    }

    private fun absolutePath(path: String, modelDir: File): String {
        if (path.isBlank()) return ""
        val original = path.replace('\\', '/')
        if (File(original).isAbsolute) return original

        val prefix = "${modelDir.name}/"
        val relative = if (original.startsWith(prefix)) original.removePrefix(prefix) else original
        return File(modelDir, relative.replace('/', File.separatorChar)).absolutePath
    }

    private fun splitWords(text: String): List<String> =
        text.split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun diffWords(previous: List<String>, current: List<String>): List<String> {
        var idx = 0
        while (idx < previous.size && idx < current.size && previous[idx] == current[idx]) {
            idx++
        }
        return if (idx < current.size) current.subList(idx, current.size) else emptyList()
    }

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
