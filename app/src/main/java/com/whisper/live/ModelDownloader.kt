package com.whisper.live

import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ModelDownloader {

    enum class EngineType { VOSK, SHERPA_ONLINE, SHERPA_OFFLINE }
    enum class ArchiveType { ZIP, TAR_BZ2 }

    data class LanguageOption(
        val code: String,
        val name: String,
        val flag: String,
    )

    data class ModelInfo(
        val id: String,
        val displayName: String,
        val langCode: String,
        val url: String,
        val dirName: String,
        val sizeMb: Int,
        val engineType: EngineType,
        val modelType: String,
        val archiveType: ArchiveType,
        val sherpaPresetType: Int? = null,
    )

    private const val VOSK_BASE = "https://alphacephei.com/vosk/models"
    private const val SHERPA_RELEASE_BASE =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"

    val languageOptions: List<LanguageOption> = listOf(
        LanguageOption("en", "English", "US"),
        LanguageOption("ar", "Arabic", "SA"),
        LanguageOption("bn", "Bengali", "BD"),
        LanguageOption("de", "German", "DE"),
        LanguageOption("es", "Spanish", "ES"),
        LanguageOption("fr", "French", "FR"),
        LanguageOption("it", "Italian", "IT"),
        LanguageOption("ja", "Japanese", "JP"),
        LanguageOption("ko", "Korean", "KR"),
        LanguageOption("pt", "Portuguese", "PT"),
        LanguageOption("ru", "Russian", "RU"),
        LanguageOption("th", "Thai", "TH"),
        LanguageOption("uk", "Ukrainian", "UA"),
        LanguageOption("vi", "Vietnamese", "VN"),
        LanguageOption("zh", "Chinese", "CN"),
        LanguageOption("multi", "Multilingual", "GL"),
        LanguageOption("all", "All Languages", "ALL"),
    )

    val models: List<ModelInfo> = buildList {
        // Vosk models (ZIP)
        add(vosk("vosk_en_small", "English Small", "en", "vosk-model-small-en-us-0.15", 40))
        add(vosk("vosk_en_large", "English Large", "en", "vosk-model-en-us-0.22", 1800))
        add(vosk("vosk_ar_small", "Arabic Small", "ar", "vosk-model-small-ar-0.22", 35))
        add(vosk("vosk_ar_large", "Arabic Large", "ar", "vosk-model-ar-mgb2-0.4", 323))
        add(vosk("vosk_es_small", "Spanish Small", "es", "vosk-model-small-es-0.42", 39))
        add(vosk("vosk_es_large", "Spanish Large", "es", "vosk-model-es-0.42", 1400))
        add(vosk("vosk_fr_small", "French Small", "fr", "vosk-model-small-fr-0.22", 41))
        add(vosk("vosk_fr_large", "French Large", "fr", "vosk-model-fr-0.22", 1400))
        add(vosk("vosk_de_small", "German Small", "de", "vosk-model-small-de-0.15", 45))
        add(vosk("vosk_de_large", "German Large", "de", "vosk-model-de-0.21", 1400))
        add(vosk("vosk_ru_small", "Russian Small", "ru", "vosk-model-small-ru-0.22", 45))
        add(vosk("vosk_ru_large", "Russian Large", "ru", "vosk-model-ru-0.42", 1800))
        add(vosk("vosk_pt_small", "Portuguese Small", "pt", "vosk-model-small-pt-0.3", 31))
        add(vosk("vosk_pt_large", "Portuguese Large", "pt", "vosk-model-pt-fb-v0.1.1-20220516_2113", 1600))
        add(vosk("vosk_it_small", "Italian Small", "it", "vosk-model-small-it-0.22", 48))
        add(vosk("vosk_it_large", "Italian Large", "it", "vosk-model-it-0.22", 1200))
        add(vosk("vosk_zh_small", "Chinese Small", "zh", "vosk-model-small-cn-0.22", 42))
        add(vosk("vosk_zh_large", "Chinese Large", "zh", "vosk-model-cn-0.22", 1000))
        add(vosk("vosk_vi_small", "Vietnamese Small", "vi", "vosk-model-small-vn-0.4", 32))
        add(vosk("vosk_through_hi", "Hindi Small", "multi", "vosk-model-small-hi-0.22", 42))

        // Sherpa streaming (TAR.BZ2)
        add(sherpaOnline(
            id = "sh_online_en_20m",
            title = "Streaming Zipformer EN 20M (int8)",
            lang = "en",
            dir = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17",
            size = 20,
            presetType = 10,
            type = "transducer",
        ))
        add(sherpaOnline(
            id = "sh_online_en_70m",
            title = "Streaming Zipformer EN 2023-06-26",
            lang = "en",
            dir = "sherpa-onnx-streaming-zipformer-en-2023-06-26",
            size = 70,
            presetType = 6,
            type = "transducer",
        ))
        add(sherpaOnline(
            id = "sh_online_en_kroko",
            title = "Streaming Zipformer EN Kroko",
            lang = "en",
            dir = "sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06",
            size = 70,
            presetType = 21,
            type = "transducer",
        ))
        add(sherpaOnline(
            id = "sh_online_en_nemo",
            title = "NeMo Streaming Fast Conformer EN 80ms",
            lang = "en",
            dir = "sherpa-onnx-nemo-streaming-fast-conformer-ctc-en-80ms",
            size = 140,
            presetType = 11,
            type = "nemo_ctc",
        ))
        add(sherpaOnline(
            id = "sh_online_en_nemotron",
            title = "Nemotron Streaming EN 0.6B (int8)",
            lang = "en",
            dir = "sherpa-onnx-nemotron-speech-streaming-en-0.6b-int8-2026-01-14",
            size = 300,
            presetType = 28,
            type = "transducer",
        ))
        add(sherpaOnline(
            id = "sh_online_zh_en",
            title = "Streaming Zipformer ZH+EN (int8)",
            lang = "zh-en",
            dir = "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20",
            size = 40,
            presetType = 8,
            type = "transducer",
        ))
        add(sherpaOnline(
            id = "sh_online_zh_small_ctc",
            title = "Streaming Zipformer Small CTC ZH (int8)",
            lang = "zh",
            dir = "sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01",
            size = 15,
            presetType = 15,
            type = "ctc",
        ))
        add(sherpaOnline(
            id = "sh_online_zh_ctc",
            title = "Streaming Zipformer CTC ZH (int8)",
            lang = "zh",
            dir = "sherpa-onnx-streaming-zipformer-ctc-zh-int8-2025-06-30",
            size = 70,
            presetType = 17,
            type = "ctc",
        ))
        add(sherpaOnline(
            id = "sh_online_fr",
            title = "Streaming Zipformer FR 2023-04-14",
            lang = "fr",
            dir = "sherpa-onnx-streaming-zipformer-fr-2023-04-14",
            size = 40,
            presetType = 7,
            type = "transducer",
        ))
        add(sherpaOnline(
            id = "sh_online_fr_kroko",
            title = "Streaming Zipformer FR Kroko",
            lang = "fr",
            dir = "sherpa-onnx-streaming-zipformer-fr-kroko-2025-08-06",
            size = 70,
            presetType = 23,
            type = "transducer",
        ))
        add(sherpaOnline(
            id = "sh_online_de_kroko",
            title = "Streaming Zipformer DE Kroko",
            lang = "de",
            dir = "sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06",
            size = 70,
            presetType = 24,
            type = "transducer",
        ))
        add(sherpaOnline(
            id = "sh_online_es_kroko",
            title = "Streaming Zipformer ES Kroko",
            lang = "es",
            dir = "sherpa-onnx-streaming-zipformer-es-kroko-2025-08-06",
            size = 70,
            presetType = 22,
            type = "transducer",
        ))
        add(sherpaOnline(
            id = "sh_online_ko",
            title = "Streaming Zipformer Korean (int8)",
            lang = "ko",
            dir = "sherpa-onnx-streaming-zipformer-korean-2024-06-16",
            size = 20,
            presetType = 14,
            type = "transducer",
        ))
        add(sherpaOnline(
            id = "sh_online_ru",
            title = "Streaming Zipformer Small RU Vosk (int8)",
            lang = "ru",
            dir = "sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16",
            size = 20,
            presetType = 25,
            type = "transducer",
        ))
        add(sherpaOnline(
            id = "sh_online_bn",
            title = "Streaming Zipformer Bengali Vosk",
            lang = "bn",
            dir = "sherpa-onnx-streaming-zipformer-bn-vosk-2026-02-09",
            size = 70,
            presetType = 29,
            type = "transducer",
        ))

        // Sherpa offline (simulated streaming with VAD)
        add(sherpaOffline(
            id = "sh_offline_whisper_tiny_en",
            title = "Whisper tiny.en (int8)",
            lang = "en",
            dir = "sherpa-onnx-whisper-tiny.en",
            size = 40,
            presetType = 2,
            type = "whisper",
        ))
        add(sherpaOffline(
            id = "sh_offline_whisper_base_en",
            title = "Whisper base.en (int8)",
            lang = "en",
            dir = "sherpa-onnx-whisper-base.en",
            size = 75,
            presetType = 3,
            type = "whisper",
        ))
        add(sherpaOffline(
            id = "sh_offline_whisper_small",
            title = "Whisper small (int8, 99 languages)",
            lang = "multi",
            dir = "sherpa-onnx-whisper-small",
            size = 250,
            presetType = null,
            type = "whisper",
        ))
        add(sherpaOffline(
            id = "sh_offline_moon_tiny_en",
            title = "Moonshine tiny EN (int8)",
            lang = "en",
            dir = "sherpa-onnx-moonshine-tiny-en-int8",
            size = 30,
            presetType = 21,
            type = "moonshine",
        ))
        add(sherpaOffline(
            id = "sh_offline_moon_base_en",
            title = "Moonshine base EN (int8)",
            lang = "en",
            dir = "sherpa-onnx-moonshine-base-en-int8",
            size = 60,
            presetType = 22,
            type = "moonshine",
        ))
        add(sherpaOffline(
            id = "sh_offline_nemo_parakeet_v3",
            title = "NeMo Parakeet TDT 0.6B v3 (int8)",
            lang = "en",
            dir = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
            size = 300,
            presetType = 40,
            type = "nemo_transducer",
        ))
        add(sherpaOffline(
            id = "sh_offline_nemo_parakeet_ctc",
            title = "NeMo Parakeet TDT CTC 110M (int8)",
            lang = "en",
            dir = "sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8",
            size = 110,
            presetType = 33,
            type = "nemo_ctc",
        ))
        add(sherpaOffline(
            id = "sh_offline_nemo_citrinet",
            title = "NeMo Citrinet CTC EN (int8)",
            lang = "en",
            dir = "sherpa-onnx-nemo-ctc-en-citrinet-512",
            size = 30,
            presetType = 6,
            type = "nemo_ctc",
        ))
        add(sherpaOffline(
            id = "sh_offline_nemo_fast_10lang",
            title = "NeMo Fast Conformer CTC 10+ lang",
            lang = "multi",
            dir = "sherpa-onnx-nemo-fast-conformer-ctc-be-de-en-es-fr-hr-it-pl-ru-uk-20k",
            size = 120,
            presetType = 7,
            type = "nemo_ctc",
        ))
        add(sherpaOffline(
            id = "sh_offline_sense_voice",
            title = "SenseVoice ZH+EN+JA+KO+YUE (int8)",
            lang = "multi",
            dir = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09",
            size = 200,
            presetType = 41,
            type = "sense_voice",
        ))
        add(sherpaOffline(
            id = "sh_offline_zipformer_thai",
            title = "Zipformer Thai offline",
            lang = "th",
            dir = "sherpa-onnx-zipformer-thai-2024-06-20",
            size = 95,
            presetType = 12,
            type = "zipformer_offline",
        ))
        add(sherpaOffline(
            id = "sh_offline_zipformer_vi",
            title = "Zipformer Vietnamese 30M (int8)",
            lang = "vi",
            dir = "sherpa-onnx-zipformer-vi-30M-int8-2026-02-09",
            size = 55,
            presetType = 49,
            type = "zipformer_offline",
        ))
        add(sherpaOffline(
            id = "sh_offline_nemo_pt_transducer",
            title = "NeMo Portuguese Transducer (int8)",
            lang = "pt",
            dir = "sherpa-onnx-nemo-transducer-stt_pt_fastconformer_hybrid_large_pc-int8",
            size = 180,
            presetType = 35,
            type = "nemo_transducer",
        ))
        add(sherpaOffline(
            id = "sh_offline_nemo_pt_ctc",
            title = "NeMo Portuguese CTC (int8)",
            lang = "pt",
            dir = "sherpa-onnx-nemo-stt_pt_fastconformer_hybrid_large_pc-int8",
            size = 180,
            presetType = 36,
            type = "nemo_ctc",
        ))
        add(sherpaOffline(
            id = "sh_offline_dolphin",
            title = "Dolphin multi-language CTC (int8)",
            lang = "multi",
            dir = "sherpa-onnx-dolphin-base-ctc-multi-lang-int8-2025-04-02",
            size = 220,
            presetType = 25,
            type = "dolphin",
        ))
    }

    fun languageByCode(code: String): LanguageOption =
        languageOptions.firstOrNull { it.code == code } ?: languageOptions.first { it.code == "en" }

    fun modelsForLanguage(langCode: String): List<ModelInfo> {
        val selected = langCode.lowercase(Locale.US)
        if (selected == "all") return models
        return models.filter { languageMatches(it.langCode, selected) }
    }

    fun groupByEngine(models: List<ModelInfo>): Map<EngineType, List<ModelInfo>> =
        models.groupBy { it.engineType }

    private fun languageMatches(modelLang: String, selected: String): Boolean {
        val ml = modelLang.lowercase(Locale.US)
        if (ml == "multi") return true
        if (ml == selected) return true
        if (!ml.contains('-')) return false
        return ml.split('-').any { it == selected }
    }

    fun download(
        model: ModelInfo,
        filesDir: File,
        onProgress: (Int) -> Unit,
        onComplete: (File) -> Unit,
        onError: (String) -> Unit,
    ) {
        val alreadyDownloadedDir = findExistingModelDir(model, filesDir)
        if (alreadyDownloadedDir != null) {
            onComplete(alreadyDownloadedDir)
            return
        }
        val expectedDir = File(filesDir, model.dirName)

        val archiveExt = when (model.archiveType) {
            ArchiveType.ZIP -> ".zip"
            ArchiveType.TAR_BZ2 -> ".tar.bz2"
        }

        val archiveFile = File(filesDir, "${model.dirName}$archiveExt")
        val partialFile = File(filesDir, "${archiveFile.name}.part")

        val existingDirs = filesDir.listFiles()?.filter { it.isDirectory }?.map { it.name }?.toSet() ?: emptySet()

        val client = OkHttpClient.Builder().followRedirects(true).build()
        val resumeFrom = if (partialFile.exists()) partialFile.length() else 0L

        val reqBuilder = Request.Builder().url(model.url)
        if (resumeFrom > 0L) {
            reqBuilder.addHeader("Range", "bytes=$resumeFrom-")
        }
        val request = reqBuilder.build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError(e.message ?: "Network error")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.code != 200 && response.code != 206) {
                    onError("HTTP ${response.code}")
                    return
                }

                val body = response.body ?: run {
                    onError("Empty response")
                    return
                }

                try {
                    // 206 => resume append is active
                    // 200 with Range requested => server ignored Range, restart from zero
                    val append = resumeFrom > 0L && response.code == 206
                    var downloaded = if (append) resumeFrom else 0L
                    if (!append && partialFile.exists()) {
                        partialFile.delete()
                    }

                    val bodyLen = body.contentLength()
                    val totalLen = if (bodyLen > 0L) downloaded + bodyLen else -1L
                    if (totalLen > 0L) {
                        onProgress(((downloaded * 100L) / totalLen).toInt().coerceIn(0, 100))
                    }

                    FileOutputStream(partialFile, append).use { out ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(32 * 1024)
                            var n: Int
                            while (input.read(buf).also { n = it } != -1) {
                                out.write(buf, 0, n)
                                downloaded += n
                                if (totalLen > 0L) {
                                    onProgress(((downloaded * 100L) / totalLen).toInt().coerceIn(0, 100))
                                }
                            }
                        }
                    }

                    if (archiveFile.exists()) archiveFile.delete()
                    if (!partialFile.renameTo(archiveFile)) {
                        throw IOException("Failed to finalize download file")
                    }

                    when (model.archiveType) {
                        ArchiveType.ZIP -> extractZip(archiveFile, filesDir)
                        ArchiveType.TAR_BZ2 -> extractTarBz2(archiveFile, filesDir)
                    }

                    archiveFile.delete()

                    val extracted = resolveExtractedDir(filesDir, model.dirName, existingDirs)
                    if (extracted.isDirectory) {
                        val finalDir = normalizeExtractedModelDir(expectedDir, extracted)
                        onComplete(finalDir)
                    } else {
                        onError("Extraction finished but model folder was not found")
                    }
                } catch (e: Exception) {
                    // Keep *.part so next download attempt can resume.
                    onError(e.message ?: "Download error")
                }
            }
        })
    }

    fun importFromZip(
        inputStream: InputStream,
        filesDir: File,
        onProgress: (Int) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        Thread {
            try {
                val tmp = File(filesDir, "import_tmp_${System.currentTimeMillis()}.zip")
                FileOutputStream(tmp).use { inputStream.copyTo(it) }
                onProgress(30)

                var modelName: String? = null
                ZipInputStream(tmp.inputStream()).use { zis ->
                    val entry = zis.nextEntry
                    if (entry != null) {
                        modelName = entry.name.split('/')[0]
                    }
                }

                if (modelName.isNullOrBlank()) {
                    tmp.delete()
                    onError("Invalid ZIP: could not detect model name")
                    return@Thread
                }

                val destDir = File(filesDir, modelName!!)
                if (destDir.exists()) destDir.deleteRecursively()

                onProgress(55)
                extractZip(tmp, filesDir)
                onProgress(95)
                tmp.delete()

                if (destDir.isDirectory) onComplete(modelName!!)
                else onError("Extraction failed")
            } catch (e: Exception) {
                onError(e.message ?: "Import error")
            }
        }.start()
    }

    fun exportToZip(
        model: ModelInfo,
        filesDir: File,
        outputStream: java.io.OutputStream,
        onComplete: () -> Unit,
        onError: (String) -> Unit,
    ) {
        Thread {
            try {
                val modelDir = getModelPath(model, filesDir)
                if (!modelDir.isDirectory) {
                    onError("Model not found")
                    return@Thread
                }

                ZipOutputStream(outputStream.buffered()).use { zos ->
                    modelDir.walkTopDown().forEach { file ->
                        if (file.isFile) {
                            val rel = file.relativeTo(modelDir).path.replace('\\', '/')
                            val entryName = "${model.dirName}/$rel"
                            zos.putNextEntry(ZipEntry(entryName))
                            file.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }
                onComplete()
            } catch (e: Exception) {
                onError(e.message ?: "Export error")
            }
        }.start()
    }

    fun getModelPath(model: ModelInfo, filesDir: File): File =
        findExistingModelDir(model, filesDir) ?: File(filesDir, model.dirName)

    fun isDownloaded(model: ModelInfo, filesDir: File): Boolean =
        findExistingModelDir(model, filesDir) != null

    fun matchByFolder(folderName: String): ModelInfo? =
        models.firstOrNull { it.dirName == folderName }

    private fun resolveExtractedDir(
        filesDir: File,
        expectedDirName: String,
        existingDirs: Set<String>,
    ): File {
        val expected = File(filesDir, expectedDirName)
        if (expected.isDirectory) return expected

        val newDirs = filesDir.listFiles()
            ?.filter { it.isDirectory && it.name !in existingDirs }
            .orEmpty()

        newDirs.firstOrNull { it.name == expectedDirName }?.let { return it }
        newDirs.firstOrNull { it.name.startsWith(expectedDirName) }?.let { return it }
        if (newDirs.size == 1) return newDirs.first()

        return expected
    }

    private fun findExistingModelDir(model: ModelInfo, filesDir: File): File? {
        val expected = File(filesDir, model.dirName)
        if (expected.isDirectory) return expected

        val dirs = filesDir.listFiles()?.filter { it.isDirectory }.orEmpty()
        dirs.firstOrNull { it.name.equals(model.dirName, ignoreCase = true) }?.let { return it }
        dirs.firstOrNull { it.name.startsWith(model.dirName, ignoreCase = true) }?.let { return it }
        dirs.firstOrNull { model.dirName.startsWith(it.name, ignoreCase = true) }?.let { return it }
        return null
    }

    private fun normalizeExtractedModelDir(expectedDir: File, extractedDir: File): File {
        if (expectedDir.canonicalPath == extractedDir.canonicalPath) {
            return expectedDir
        }

        if (expectedDir.exists()) {
            expectedDir.deleteRecursively()
        }
        expectedDir.parentFile?.mkdirs()

        if (extractedDir.renameTo(expectedDir)) {
            return expectedDir
        }

        // Fallback when rename fails across boundaries: copy then delete source.
        extractedDir.copyRecursively(expectedDir, overwrite = true)
        extractedDir.deleteRecursively()
        return expectedDir
    }

    private fun extractZip(zipFile: File, destParentDir: File) {
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val target = safeTarget(destParentDir, entry.name)
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun extractTarBz2(archiveFile: File, destParentDir: File) {
        archiveFile.inputStream().buffered().use { fis ->
            BZip2CompressorInputStream(fis).use { bzip ->
                TarArchiveInputStream(bzip).use { tar ->
                    var entry: TarArchiveEntry? = tar.nextTarEntry
                    while (entry != null) {
                        val target = safeTarget(destParentDir, entry.name)
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { out -> tar.copyTo(out) }
                        }
                        entry = tar.nextTarEntry
                    }
                }
            }
        }
    }

    private fun safeTarget(destParentDir: File, entryName: String): File {
        val target = File(destParentDir, entryName)
        val destPath = destParentDir.canonicalPath + File.separator
        val targetPath = target.canonicalPath
        if (!targetPath.startsWith(destPath)) {
            throw SecurityException("Blocked archive path traversal: $entryName")
        }
        return target
    }

    private fun vosk(
        id: String,
        title: String,
        lang: String,
        dir: String,
        size: Int,
    ): ModelInfo {
        return ModelInfo(
            id = id,
            displayName = title,
            langCode = lang,
            url = "$VOSK_BASE/$dir.zip",
            dirName = dir,
            sizeMb = size,
            engineType = EngineType.VOSK,
            modelType = "vosk",
            archiveType = ArchiveType.ZIP,
            sherpaPresetType = null,
        )
    }

    private fun sherpaOnline(
        id: String,
        title: String,
        lang: String,
        dir: String,
        size: Int,
        presetType: Int,
        type: String,
    ): ModelInfo {
        return ModelInfo(
            id = id,
            displayName = title,
            langCode = lang,
            url = "$SHERPA_RELEASE_BASE/$dir.tar.bz2",
            dirName = dir,
            sizeMb = size,
            engineType = EngineType.SHERPA_ONLINE,
            modelType = type,
            archiveType = ArchiveType.TAR_BZ2,
            sherpaPresetType = presetType,
        )
    }

    private fun sherpaOffline(
        id: String,
        title: String,
        lang: String,
        dir: String,
        size: Int,
        presetType: Int?,
        type: String,
    ): ModelInfo {
        return ModelInfo(
            id = id,
            displayName = title,
            langCode = lang,
            url = "$SHERPA_RELEASE_BASE/$dir.tar.bz2",
            dirName = dir,
            sizeMb = size,
            engineType = EngineType.SHERPA_OFFLINE,
            modelType = type,
            archiveType = ArchiveType.TAR_BZ2,
            sherpaPresetType = presetType,
        )
    }
}
