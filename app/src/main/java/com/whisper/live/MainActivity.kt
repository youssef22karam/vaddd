package com.whisper.live

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.whisper.live.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var micEngine: TranscriptionEngine = VoskEngine()
    private var phoneEngine: TranscriptionEngine = VoskEngine()
    private var audioRecorder: AudioRecorder? = null
    private var currentModel: ModelDownloader.ModelInfo? = null

    private var isMicRecording = false
    private var isPhoneCapturing = false
    private var overlayActive = false

    private var activeLineView: TextView? = null
    private val activeLineWords = mutableListOf<String>()

    companion object {
        private const val REQUEST_AUDIO = 100
        private const val REQUEST_OVERLAY = 101
        private const val REQUEST_PROJECTION = 102
        private const val REQUEST_IMPORT = 103
        private const val REQUEST_EXPORT = 104

        private const val ACTIVE_COLOR = "#00D68F"
        private const val SETTLED_COLOR = "#CCCCCC"
        private const val DIM_COLOR = "#555555"
        private const val PHONE_COLOR = "#4B96FF"

        private var pendingExportModel: ModelDownloader.ModelInfo? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsManager.load(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUi()
        updateLanguageBadge()
        checkModelOnDisk()
    }

    override fun onResume() {
        super.onResume()
        SettingsManager.load(this)
        FloatingCaptionService.instance?.applyDisplaySettings()
        updateLanguageBadge()
        updateStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMicRecording()
        stopPhoneCapture()
        releaseEngines()
        VoskEngine.releaseSharedModel()
        stopOverlayService()
    }

    private fun setupUi() {
        binding.btnLanguage.setOnClickListener { showLanguagePicker() }
        binding.btnModel.setOnClickListener { showModelPicker() }
        binding.btnModel.setOnLongClickListener {
            val model = currentModel
            if (model != null && ModelDownloader.isDownloaded(model, filesDir)) {
                showDownloadedModelMenu(model)
            } else {
                Toast.makeText(this, "No downloaded model selected yet.", Toast.LENGTH_SHORT).show()
            }
            true
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnRecord.isEnabled = false
        binding.btnRecord.setOnClickListener {
            if (isMicRecording) stopMicRecording() else requestMicPermission()
        }

        binding.btnPhoneAudio.isEnabled = false
        binding.btnPhoneAudio.setOnClickListener {
            if (isPhoneCapturing) stopPhoneCapture() else requestPhoneCapture()
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            binding.btnPhoneAudio.isEnabled = false
            binding.btnPhoneAudio.alpha = 0.3f
        }

        binding.btnCopy.setOnClickListener { copyAllText() }
        binding.btnOverlay.setOnClickListener { toggleOverlay() }

        binding.phrasesContainer.setOnLongClickListener {
            AlertDialog.Builder(this)
                .setMessage("Clear all transcription text?")
                .setPositiveButton("Clear") { _, _ -> clearPhrases() }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
    }

    private fun updateLanguageBadge() {
        val lang = ModelDownloader.languageByCode(SettingsManager.selectedLanguage)
        binding.btnLanguage.text =
            if (lang.code == "all") "ALL" else "${lang.flag} ${lang.code.uppercase()}"
    }

    private fun showLanguagePicker() {
        val options = ModelDownloader.languageOptions.filter { it.code != "all" }
        val labels = options.map { "${it.flag} ${it.name}" }.toTypedArray()
        val currentIndex = options.indexOfFirst { it.code == SettingsManager.selectedLanguage }

        AlertDialog.Builder(this)
            .setTitle("Filter models by language")
            .setSingleChoiceItems(labels, currentIndex) { dialog, index ->
                SettingsManager.selectedLanguage = options[index].code
                SettingsManager.save(this)
                updateLanguageBadge()
                dialog.dismiss()
            }
            .setNeutralButton("Show All") { _, _ ->
                SettingsManager.selectedLanguage = "all"
                SettingsManager.save(this)
                updateLanguageBadge()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkModelOnDisk() {
        val preferred = ModelDownloader.models.firstOrNull {
            it.id == SettingsManager.lastModelId && ModelDownloader.isDownloaded(it, filesDir)
        }

        val found = preferred ?: ModelDownloader.models.firstOrNull { model ->
            ModelDownloader.isDownloaded(model, filesDir)
        }

        if (found != null) {
            setStatus("Initializing model...", "#888888")
            loadModel(found)
        } else {
            setStatus("Tap Model to download a language model", "#383838")
        }
    }

    private data class PickerEntry(
        val label: String,
        val model: ModelDownloader.ModelInfo? = null,
        val isHeader: Boolean = false,
        val isImport: Boolean = false,
    )

    private fun showModelPicker() {
        val filtered = ModelDownloader.modelsForLanguage(SettingsManager.selectedLanguage)
        val grouped = ModelDownloader.groupByEngine(filtered)

        val entries = mutableListOf<PickerEntry>()
        entries += PickerEntry(label = "Import model from ZIP...", isImport = true)

        fun addGroup(title: String, type: ModelDownloader.EngineType) {
            val models = grouped[type].orEmpty().sortedBy { it.displayName }
            if (models.isEmpty()) return

            entries += PickerEntry(label = "----- $title -----", isHeader = true)
            models.forEach { model ->
                val downloaded = ModelDownloader.isDownloaded(model, filesDir)
                val tag = when (model.engineType) {
                    ModelDownloader.EngineType.VOSK -> "[Vosk]"
                    ModelDownloader.EngineType.SHERPA_ONLINE -> "[Streaming]"
                    ModelDownloader.EngineType.SHERPA_OFFLINE -> "[Offline]"
                }
                val suffix = if (downloaded) "  [OK]" else ""
                entries += PickerEntry(
                    label = "$tag ${model.displayName} (${model.sizeMb} MB)$suffix",
                    model = model,
                )
            }
        }

        addGroup("Vosk Models", ModelDownloader.EngineType.VOSK)
        addGroup("Sherpa Streaming", ModelDownloader.EngineType.SHERPA_ONLINE)
        addGroup("Sherpa Offline (VAD)", ModelDownloader.EngineType.SHERPA_OFFLINE)

        AlertDialog.Builder(this)
            .setTitle("Language Models")
            .setItems(entries.map { it.label }.toTypedArray()) { _, index ->
                val entry = entries[index]
                when {
                    entry.isHeader -> Unit
                    entry.isImport -> launchImport()
                    entry.model != null -> {
                        val model = entry.model
                        if (ModelDownloader.isDownloaded(model, filesDir)) {
                            showDownloadedModelMenu(model)
                        } else {
                            downloadAndLoad(model)
                        }
                    }
                }
            }
            .show()
    }

    private fun showDownloadedModelMenu(model: ModelDownloader.ModelInfo) {
        val options = arrayOf("Load this model", "Export as ZIP", "Delete")

        AlertDialog.Builder(this)
            .setTitle(model.displayName)
            .setItems(options) { _, index ->
                when {
                    index == 0 -> {
                        setStatus("Loading model...", "#888888")
                        loadModel(model)
                    }

                    index == 1 -> {
                        launchExport(model)
                    }

                    else -> deleteModel(model)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun downloadAndLoad(model: ModelDownloader.ModelInfo) {
        binding.btnModel.isEnabled = false
        binding.progressContainer.visibility = View.VISIBLE
        binding.progressBar.progress = 0
        setStatus("Downloading ${model.displayName}...", "#4B96FF")

        ModelDownloader.download(
            model = model,
            filesDir = filesDir,
            onProgress = { pct ->
                runOnUiThread {
                    binding.progressBar.progress = pct
                    binding.tvProgress.text = "$pct%"
                }
            },
            onComplete = {
                runOnUiThread {
                    binding.progressContainer.visibility = View.GONE
                    binding.btnModel.isEnabled = true
                    setStatus("Loading model...", "#888888")
                    loadModel(model)
                }
            },
            onError = { err ->
                runOnUiThread {
                    binding.progressContainer.visibility = View.GONE
                    binding.btnModel.isEnabled = true
                    setStatus("Download failed: $err", "#FF4444")
                }
            },
        )
    }

    private fun loadModel(model: ModelDownloader.ModelInfo) {
        stopMicRecording()
        stopPhoneCapture()

        val modelDir = ModelDownloader.getModelPath(model, filesDir)
        if (!modelDir.isDirectory) {
            enableButtons(false, false)
            setStatus("Model files not found. Download or import it again.", "#FF4444")
            Toast.makeText(this, "Model folder is missing for ${model.displayName}", Toast.LENGTH_LONG).show()
            return
        }

        currentModel = model
        SettingsManager.lastModelId = model.id
        SettingsManager.save(this)

        lifecycleScope.launch {
            val modelPath = modelDir.absolutePath
            releaseEngines()

            val pairOk = withContext(Dispatchers.IO) {
                if (model.engineType == ModelDownloader.EngineType.VOSK) {
                    if (!VoskEngine.loadSharedModel(modelPath)) return@withContext Pair(false, false)
                    val mic = VoskEngine()
                    val phone = VoskEngine()
                    val micOk = mic.initializeFromShared()
                    val phoneOk = phone.initializeFromShared()
                    micEngine = mic
                    phoneEngine = phone
                    Pair(micOk, phoneOk)
                } else {
                    VoskEngine.releaseSharedModel()
                    val mic = createEngine(model)
                    val phone = createEngine(model)
                    val micOk = mic.initialize(modelPath)
                    val phoneOk = phone.initialize(modelPath)
                    micEngine = mic
                    phoneEngine = phone
                    Pair(micOk, phoneOk)
                }
            }

            val (micOk, phoneOk) = pairOk
            if (micOk) {
                setStatus("Ready [${engineTag(model.engineType)} ${model.langCode.uppercase()}]", "#00D68F")
                enableButtons(micOk, phoneOk)
            } else {
                setStatus("Failed to load model", "#FF4444")
                enableButtons(false, false)
            }
        }
    }

    private fun createEngine(model: ModelDownloader.ModelInfo): TranscriptionEngine {
        return when (model.engineType) {
            ModelDownloader.EngineType.VOSK -> VoskEngine()
            ModelDownloader.EngineType.SHERPA_ONLINE -> SherpaOnlineEngine(model)
            ModelDownloader.EngineType.SHERPA_OFFLINE -> SherpaOfflineEngine(applicationContext, model)
        }
    }

    private fun releaseEngines() {
        try {
            micEngine.release()
        } catch (_: Exception) {
        }
        try {
            phoneEngine.release()
        } catch (_: Exception) {
        }
    }

    private fun deleteModel(model: ModelDownloader.ModelInfo) {
        AlertDialog.Builder(this)
            .setMessage("Delete ${model.displayName}? You can download it again later.")
            .setPositiveButton("Delete") { _, _ ->
                ModelDownloader.getModelPath(model, filesDir).deleteRecursively()
                Toast.makeText(this, "Model deleted", Toast.LENGTH_SHORT).show()

                if (currentModel?.id == model.id) {
                    currentModel = null
                    SettingsManager.lastModelId = ""
                    SettingsManager.save(this)
                    enableButtons(false, false)
                    setStatus("Model deleted - tap Model to download", "#383838")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun enableButtons(micOk: Boolean, phoneOk: Boolean) {
        binding.btnRecord.isEnabled = micOk
        binding.btnRecord.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor("#222222"))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            binding.btnPhoneAudio.isEnabled = phoneOk
            binding.btnPhoneAudio.backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.parseColor("#222222"))
        }
    }

    private fun launchImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
        }
        startActivityForResult(intent, REQUEST_IMPORT)
    }

    private fun launchExport(model: ModelDownloader.ModelInfo) {
        pendingExportModel = model
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, "${model.dirName}.zip")
        }
        startActivityForResult(intent, REQUEST_EXPORT)
    }

    private fun doImport(uri: Uri) {
        binding.progressContainer.visibility = View.VISIBLE
        binding.progressBar.progress = 0
        setStatus("Importing model...", "#4B96FF")

        val stream = contentResolver.openInputStream(uri) ?: run {
            setStatus("Cannot read file", "#FF4444")
            return
        }

        ModelDownloader.importFromZip(
            inputStream = stream,
            filesDir = filesDir,
            onProgress = { pct ->
                runOnUiThread {
                    binding.progressBar.progress = pct
                    binding.tvProgress.text = "$pct%"
                }
            },
            onComplete = { folderName ->
                runOnUiThread {
                    binding.progressContainer.visibility = View.GONE
                    val matched = ModelDownloader.matchByFolder(folderName)
                    if (matched != null) {
                        Toast.makeText(this, "Imported: ${matched.displayName}", Toast.LENGTH_LONG).show()
                        setStatus("Loading model...", "#888888")
                        loadModel(matched)
                    } else {
                        Toast.makeText(this, "Imported folder: $folderName", Toast.LENGTH_LONG).show()
                        setStatus("Import complete. Tap Model to load.", "#00D68F")
                    }
                }
            },
            onError = { err ->
                runOnUiThread {
                    binding.progressContainer.visibility = View.GONE
                    setStatus("Import failed: $err", "#FF4444")
                }
            },
        )
    }

    private fun doExport(uri: Uri, model: ModelDownloader.ModelInfo) {
        setStatus("Exporting ${model.displayName}...", "#4B96FF")
        val stream = contentResolver.openOutputStream(uri) ?: run {
            setStatus("Cannot write file", "#FF4444")
            return
        }

        ModelDownloader.exportToZip(
            model = model,
            filesDir = filesDir,
            outputStream = stream,
            onComplete = {
                runOnUiThread {
                    updateStatus()
                    Toast.makeText(this, "Export complete", Toast.LENGTH_SHORT).show()
                }
            },
            onError = { err ->
                runOnUiThread {
                    setStatus("Export failed: $err", "#FF4444")
                }
            },
        )
    }

    private fun requestMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startMicRecording()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_AUDIO,
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            startMicRecording()
        }
    }

    private fun startMicRecording() {
        if (isMicRecording) return
        if (currentModel == null) {
            Toast.makeText(this, "Please load a model first.", Toast.LENGTH_SHORT).show()
            return
        }

        isMicRecording = true
        if (!isPhoneCapturing) {
            clearPhrases()
            binding.tvHint.visibility = View.GONE
        }

        binding.btnRecord.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor("#FF4444"))

        updateStatus()
        startPulse(binding.pulseRing)

        val includeSilence = currentModel?.engineType == ModelDownloader.EngineType.SHERPA_OFFLINE

        audioRecorder = AudioRecorder(includeSilenceChunks = includeSilence) { pcmData ->
            withContext(Dispatchers.IO) {
                micEngine.transcribe(pcmData)
            }

            val words = micEngine.newWords
            val segDone = micEngine.isSegmentComplete
            val newLine = micEngine.shouldStartNewLine
            val finalText = if (segDone) micEngine.segmentText else null

            if (words.isNotEmpty() || segDone) {
                withContext(Dispatchers.Main) {
                    handleNewWords(words, segDone, newLine, "mic", finalText)
                }
            }
        }

        audioRecorder?.start()
    }

    private fun stopMicRecording() {
        if (!isMicRecording) return
        isMicRecording = false
        audioRecorder?.stop()
        audioRecorder = null

        binding.btnRecord.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor("#222222"))

        stopPulse(binding.pulseRing)
        updateStatus()
    }

    private fun requestPhoneCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "Phone audio requires Android 10+", Toast.LENGTH_SHORT).show()
            return
        }
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_PROJECTION)
    }

    private fun startPhoneCapture(resultCode: Int, data: Intent) {
        if (isPhoneCapturing) return
        if (currentModel == null) {
            Toast.makeText(this, "Please load a model first.", Toast.LENGTH_SHORT).show()
            return
        }

        isPhoneCapturing = true
        if (!isMicRecording) {
            clearPhrases()
            binding.tvHint.visibility = View.GONE
        }

        binding.btnPhoneAudio.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor(PHONE_COLOR))

        updateStatus()
        startPulse(binding.pulseRingPhone)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AudioCaptureService.forceIncludeSilenceChunks =
                currentModel?.engineType == ModelDownloader.EngineType.SHERPA_OFFLINE

            AudioCaptureService.onAudioChunk = { pcmData ->
                withContext(Dispatchers.IO) {
                    phoneEngine.transcribe(pcmData)
                }

                val words = phoneEngine.newWords
                val segDone = phoneEngine.isSegmentComplete
                val newLine = phoneEngine.shouldStartNewLine
                val finalText = if (segDone) phoneEngine.segmentText else null

                if (words.isNotEmpty() || segDone) {
                    withContext(Dispatchers.Main) {
                        handleNewWords(words, segDone, newLine, "phone", finalText)
                    }
                }
            }

            startForegroundService(AudioCaptureService.createIntent(this, resultCode, data))
        }
    }

    private fun stopPhoneCapture() {
        if (!isPhoneCapturing) return
        isPhoneCapturing = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AudioCaptureService.onAudioChunk = null
            AudioCaptureService.forceIncludeSilenceChunks = false
            stopService(Intent(this, AudioCaptureService::class.java))
        }

        binding.btnPhoneAudio.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor("#222222"))

        stopPulse(binding.pulseRingPhone)
        updateStatus()
    }

    private fun handleNewWords(
        words: List<String>,
        segmentComplete: Boolean,
        startNewLine: Boolean,
        source: String,
        finalText: String?,
    ) {
        if (startNewLine && activeLineView != null) {
            finalizeActiveLine()
        }

        val effectiveWords = if (words.isNotEmpty()) {
            words
        } else if (segmentComplete && !finalText.isNullOrBlank() && activeLineWords.isEmpty()) {
            finalText.split(Regex("\\s+")).filter { it.isNotBlank() }
        } else {
            emptyList()
        }

        for (word in effectiveWords) {
            appendWordToActiveLine(word, source)
        }

        if (segmentComplete) {
            finalizeActiveLine()
        }

        if (overlayActive && activeLineWords.isNotEmpty()) {
            FloatingCaptionService.instance?.updateCaption(activeLineWords.joinToString(" "))
        }

        scrollToBottom()
    }

    private fun appendWordToActiveLine(word: String, source: String) {
        if (activeLineView == null) {
            dimAllLines()
            activeLineView = createLineView()
            binding.phrasesContainer.addView(activeLineView)
            binding.tvHint.visibility = View.GONE
        }

        activeLineWords.add(word)
        val color = if (source == "phone") PHONE_COLOR else ACTIVE_COLOR
        val fullText = activeLineWords.joinToString(" ")
        val ssb = SpannableStringBuilder(fullText)
        ssb.setSpan(
            ForegroundColorSpan(Color.parseColor(color)),
            0,
            fullText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        activeLineView?.text = ssb
    }

    private fun finalizeActiveLine() {
        val tv = activeLineView ?: return
        val text = activeLineWords.joinToString(" ")
        if (text.isBlank()) {
            activeLineView = null
            activeLineWords.clear()
            return
        }

        tv.text = text
        tv.setTextColor(Color.parseColor(SETTLED_COLOR))
        tv.alpha = 1f

        if (overlayActive) {
            FloatingCaptionService.instance?.updateCaption(text)
        }

        activeLineView = null
        activeLineWords.clear()
    }

    private fun createLineView(): TextView {
        return TextView(this).apply {
            textSize = SettingsManager.transcriptTextSize
            setTextColor(Color.parseColor(ACTIVE_COLOR))
            alpha = 1f
            setPadding(0, 0, 0, 6)
            setLineSpacing(0f, 1.4f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    private fun dimAllLines() {
        val container = binding.phrasesContainer
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child is TextView && child.id != binding.tvHint.id && child != activeLineView) {
                child.setTextColor(Color.parseColor(DIM_COLOR))
                child.animate().alpha(0.5f).setDuration(200).start()
            }
        }
    }

    private fun scrollToBottom() {
        binding.scrollView.post {
            binding.scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun clearPhrases() {
        val container = binding.phrasesContainer
        val toRemove = mutableListOf<View>()
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child is TextView && child.id != binding.tvHint.id) {
                toRemove.add(child)
            }
        }
        toRemove.forEach { container.removeView(it) }

        activeLineView = null
        activeLineWords.clear()
        binding.tvHint.visibility = View.VISIBLE
        binding.tvHint.alpha = 1f
    }

    private fun copyAllText() {
        val allText = StringBuilder()
        for (i in 0 until binding.phrasesContainer.childCount) {
            val child = binding.phrasesContainer.getChildAt(i)
            if (child is TextView && child.id != binding.tvHint.id) {
                val text = child.text.toString().trim()
                if (text.isNotBlank()) {
                    if (allText.isNotEmpty()) allText.append("\n")
                    allText.append(text)
                }
            }
        }

        if (allText.isNotBlank()) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("transcription", allText.toString()))
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Nothing to copy", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleOverlay() {
        if (!overlayActive) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                AlertDialog.Builder(this)
                    .setTitle("Live Captions Permission")
                    .setMessage(
                        "To show captions over other apps, grant the Display over other apps permission."
                    )
                    .setPositiveButton("Open Settings") { _, _ ->
                        startActivityForResult(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName"),
                            ),
                            REQUEST_OVERLAY,
                        )
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                return
            }
            startOverlayService()
        } else {
            stopOverlayService()
        }
    }

    private fun startOverlayService() {
        val intent = Intent(this, FloatingCaptionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        overlayActive = true
        binding.btnOverlay.setTextColor(Color.parseColor("#00D68F"))
    }

    private fun stopOverlayService() {
        stopService(Intent(this, FloatingCaptionService::class.java))
        overlayActive = false
        binding.btnOverlay.setTextColor(Color.parseColor("#888888"))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_OVERLAY -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                    startOverlayService()
                }
            }

            REQUEST_PROJECTION -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    startPhoneCapture(resultCode, data)
                } else {
                    Toast.makeText(this, "Phone audio permission denied", Toast.LENGTH_SHORT).show()
                }
            }

            REQUEST_IMPORT -> {
                if (resultCode == Activity.RESULT_OK) {
                    val uri = data?.data ?: return
                    doImport(uri)
                }
            }

            REQUEST_EXPORT -> {
                if (resultCode == Activity.RESULT_OK) {
                    val uri = data?.data ?: return
                    val model = pendingExportModel ?: return
                    pendingExportModel = null
                    doExport(uri, model)
                }
            }
        }
    }

    private fun updateStatus() {
        val parts = mutableListOf<String>()
        if (isMicRecording) parts.add("Mic")
        if (isPhoneCapturing) parts.add("Phone")

        if (parts.isEmpty()) {
            val model = currentModel
            if (model == null) {
                setStatus("Ready", "#00D68F")
                return
            }
            setStatus(
                "Ready [${engineTag(model.engineType)} ${model.langCode.uppercase()}]",
                "#00D68F",
            )
        } else {
            setStatus("Listening: ${parts.joinToString(" + ")}", "#00D68F")
        }
    }

    private fun engineTag(type: ModelDownloader.EngineType): String {
        return when (type) {
            ModelDownloader.EngineType.VOSK -> "Vosk"
            ModelDownloader.EngineType.SHERPA_ONLINE -> "Streaming"
            ModelDownloader.EngineType.SHERPA_OFFLINE -> "Offline"
        }
    }

    private fun setStatus(msg: String, dotColor: String = "#383838") {
        binding.tvStatus.text = msg
        (binding.statusDot.background as? GradientDrawable)?.setColor(Color.parseColor(dotColor))
    }

    private val pulseAnimators = mutableMapOf<View, AnimatorSet>()

    private fun startPulse(ring: View) {
        val sx = ObjectAnimator.ofFloat(ring, "scaleX", 1f, 1.6f).apply {
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.RESTART
        }
        val sy = ObjectAnimator.ofFloat(ring, "scaleY", 1f, 1.6f).apply {
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.RESTART
        }
        val alpha = ObjectAnimator.ofFloat(ring, "alpha", 0.7f, 0f).apply {
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.RESTART
        }

        pulseAnimators[ring] = AnimatorSet().apply {
            playTogether(sx, sy, alpha)
            duration = 900
            start()
        }
        ring.visibility = View.VISIBLE
    }

    private fun stopPulse(ring: View) {
        pulseAnimators.remove(ring)?.cancel()
        ring.visibility = View.GONE
        ring.alpha = 0f
        ring.scaleX = 1f
        ring.scaleY = 1f
    }
}
