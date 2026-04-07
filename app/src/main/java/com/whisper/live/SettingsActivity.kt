package com.whisper.live

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Settings screen for audio tuning, engine tuning, and display options.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#050505"))
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(40))
        }
        scroll.addView(root)
        setContentView(scroll)

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(16))
        }
        titleRow.addView(textView("<-", 22f, "#888888").apply {
            setPadding(0, 0, dp(16), 0)
            setOnClickListener { finish() }
        })
        titleRow.addView(textView("Settings", 17f, "#FFFFFF", bold = true))
        root.addView(titleRow)
        root.addView(divider())

        root.addView(sectionHeader("ENGINE"))

        val sherpaThreadsLabel = label("Sherpa Threads: ${SettingsManager.sherpaThreads}")
        root.addView(sherpaThreadsLabel)
        root.addView(hint("Number of CPU threads for Sherpa models. Increase for speed, reduce for battery/heat."))
        root.addView(seekBar(1, 8, SettingsManager.sherpaThreads) { value ->
            SettingsManager.sherpaThreads = value
            SettingsManager.save(this)
            sherpaThreadsLabel.text = "Sherpa Threads: $value"
        })

        root.addView(divider())

        root.addView(sectionHeader("AUDIO"))

        val gainLabel = label("Audio Gain: ${fmt(SettingsManager.audioGain)}x")
        root.addView(gainLabel)
        root.addView(hint("Boost microphone volume. Raise if speech is too quiet."))
        root.addView(seekBar(0, 38, gainToProgress(SettingsManager.audioGain)) { value ->
            val gain = 1.0f + value * 0.5f
            SettingsManager.audioGain = gain
            SettingsManager.save(this)
            gainLabel.text = "Audio Gain: ${fmt(gain)}x"
        })

        val chunkValues = floatArrayOf(0.1f, 0.2f, 0.3f, 0.5f, 1.0f, 1.5f, 2.0f, 3.0f)
        val chunkLabel = label("Chunk Duration: ${fmt(SettingsManager.chunkDuration)} s")
        root.addView(chunkLabel)
        root.addView(hint("Audio chunk size sent to the speech engine. Smaller chunks feel faster, larger chunks can be steadier."))
        root.addView(seekBar(0, chunkValues.size - 1, chunkValues.indexOfClosest(SettingsManager.chunkDuration)) { value ->
            val chunk = chunkValues[value]
            SettingsManager.chunkDuration = chunk
            SettingsManager.save(this)
            chunkLabel.text = "Chunk Duration: ${fmt(chunk)} s"
        })

        root.addView(
            switchRow(
                label = "Skip Silent Chunks",
                checked = SettingsManager.skipSilence,
                hint = "Skip audio chunks below threshold. Helps streaming engines; offline VAD may override this.",
            ) { enabled ->
                SettingsManager.skipSilence = enabled
                SettingsManager.save(this)
            }
        )

        val silenceLabel = label("Silence Threshold: ${fmtSil(SettingsManager.silenceThreshold)}")
        root.addView(silenceLabel)
        root.addView(hint("RMS level below this is treated as silence. Lower is more sensitive."))
        root.addView(seekBar(0, 49, silToProgress(SettingsManager.silenceThreshold)) { value ->
            val threshold = 0.001f + value * 0.001f
            SettingsManager.silenceThreshold = threshold
            SettingsManager.save(this)
            silenceLabel.text = "Silence Threshold: ${fmtSil(threshold)}"
        })

        root.addView(divider())

        root.addView(sectionHeader("DISPLAY"))

        val transcriptLabel = label("Transcript Text Size: ${SettingsManager.transcriptTextSize.toInt()} sp")
        root.addView(transcriptLabel)
        root.addView(seekBar(12, 28, SettingsManager.transcriptTextSize.toInt() - 12) { value ->
            val sp = (value + 12).toFloat()
            SettingsManager.transcriptTextSize = sp
            SettingsManager.save(this)
            transcriptLabel.text = "Transcript Text Size: ${sp.toInt()} sp"
        })

        val captionSizeLabel = label("Caption Bar Text: ${SettingsManager.captionTextSize.toInt()} sp")
        root.addView(captionSizeLabel)
        root.addView(seekBar(10, 28, SettingsManager.captionTextSize.toInt() - 10) { value ->
            val sp = (value + 10).toFloat()
            SettingsManager.captionTextSize = sp
            SettingsManager.save(this)
            captionSizeLabel.text = "Caption Bar Text: ${sp.toInt()} sp"
        })

        val captionLinesLabel = label("Caption Max Lines: ${SettingsManager.captionMaxLines}")
        root.addView(captionLinesLabel)
        root.addView(seekBar(1, 6, SettingsManager.captionMaxLines - 1) { value ->
            val lines = value + 1
            SettingsManager.captionMaxLines = lines
            SettingsManager.save(this)
            captionLinesLabel.text = "Caption Max Lines: $lines"
        })

        root.addView(divider())

        val resetBtn = Button(this).apply {
            text = "Reset to Defaults"
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            setTextColor(Color.parseColor("#FF4444"))
            textSize = 14f
            setPadding(0, dp(14), 0, dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
            setOnClickListener {
                AlertDialog.Builder(this@SettingsActivity)
                    .setMessage("Reset all settings to defaults?")
                    .setPositiveButton("Reset") { _, _ ->
                        SettingsManager.resetToDefaults(this@SettingsActivity)
                        recreate()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        root.addView(resetBtn)
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density + 0.5f).toInt()

    private fun textView(text: String, sp: Float, color: String, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = sp
            setTextColor(Color.parseColor(color))
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(14) }
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.parseColor("#666666"))
        setLineSpacing(0f, 1.3f)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(2)
            bottomMargin = dp(4)
        }
    }

    private fun sectionHeader(text: String) = TextView(this).apply {
        this.text = text
        textSize = 11f
        letterSpacing = 0.1f
        setTextColor(Color.parseColor("#00D68F"))
        setTypeface(typeface, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(20)
            bottomMargin = dp(4)
        }
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(Color.parseColor("#1E1E1E"))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1),
        ).apply { topMargin = dp(20) }
    }

    private fun seekBar(min: Int, max: Int, progress: Int, onChange: (Int) -> Unit) =
        SeekBar(this).apply {
            this.max = max - min
            this.progress = (progress - min).coerceAtLeast(0)
            progressDrawable?.setTint(Color.parseColor("#00D68F"))
            thumb?.setTint(Color.parseColor("#00D68F"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(6)
                bottomMargin = dp(4)
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) onChange(value + min)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }

    private fun switchRow(
        label: String,
        checked: Boolean,
        hint: String,
        onChange: (Boolean) -> Unit,
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14) }
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(Color.WHITE)
        })
        textCol.addView(TextView(this).apply {
            text = hint
            textSize = 11f
            setTextColor(Color.parseColor("#666666"))
            setLineSpacing(0f, 1.2f)
        })

        val sw = Switch(this).apply {
            isChecked = checked
            thumbDrawable?.setTint(Color.parseColor("#00D68F"))
            trackDrawable?.setTint(Color.parseColor("#333333"))
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }

        row.addView(textCol)
        row.addView(sw)
        return row
    }

    private fun fmt(value: Float) = "%.1f".format(value)
    private fun fmtSil(value: Float) = "%.3f".format(value)
    private fun gainToProgress(gain: Float) = ((gain - 1.0f) / 0.5f).toInt().coerceIn(0, 38)
    private fun silToProgress(silence: Float) = ((silence - 0.001f) / 0.001f).toInt().coerceIn(0, 49)

    private fun FloatArray.indexOfClosest(target: Float): Int {
        var best = 0
        var bestDist = Float.MAX_VALUE
        forEachIndexed { index, value ->
            val dist = kotlin.math.abs(value - target)
            if (dist < bestDist) {
                bestDist = dist
                best = index
            }
        }
        return best
    }
}
