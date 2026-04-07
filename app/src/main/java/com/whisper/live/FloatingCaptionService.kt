package com.whisper.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat

/**
 * Foreground service that shows a draggable caption bar on top of all apps.
 * Caption appearance (text size, max lines, height) is read from [SettingsManager].
 * The overlay position is saved to [SettingsManager] when the view is moved.
 */
class FloatingCaptionService : Service() {

    companion object {
        private const val CHANNEL_ID = "caption_overlay"
        private const val NOTIF_ID   = 1001

        /** Live reference so MainActivity can push text without IPC overhead. */
        var instance: FloatingCaptionService? = null
    }

    private var windowManager: WindowManager? = null
    private var overlayView:   View?          = null
    private var captionText:   TextView?      = null
    private var liveBar:       View?          = null
    private lateinit var layoutParams: WindowManager.LayoutParams

    // Drag state
    private var initialX = 0;  private var initialY = 0
    private var touchX   = 0f; private var touchY   = 0f

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForegroundCompat()
        createOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        removeOverlay()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun updateCaption(text: String) {
        captionText?.post {
            captionText?.text = text
            liveBar?.animate()?.alpha(0.2f)?.setDuration(80)
                ?.withEndAction { liveBar?.animate()?.alpha(1f)?.setDuration(120)?.start() }
                ?.start()
        }
    }

    /** Re-apply display settings (called from MainActivity after settings change). */
    fun applyDisplaySettings() {
        val tv = captionText ?: return
        tv.post {
            tv.textSize = SettingsManager.captionTextSize
            tv.maxLines = SettingsManager.captionMaxLines

            val hDp = SettingsManager.captionHeightDp
            val root = overlayView ?: return@post
            val lp = root.layoutParams
            if (lp is WindowManager.LayoutParams) {
                lp.height = if (hDp > 0) dp(hDp) else WindowManager.LayoutParams.WRAP_CONTENT
                try { windowManager?.updateViewLayout(root, lp) } catch (_: Exception) {}
            }
        }
    }

    // ── Overlay creation ──────────────────────────────────────────────────────

    private fun createOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val inflater = LayoutInflater.from(this)
        overlayView  = inflater.inflate(R.layout.overlay_caption, null)
        captionText  = overlayView?.findViewById(R.id.tvCaption)
        liveBar      = overlayView?.findViewById(R.id.liveBar)
        val handle   = overlayView?.findViewById<TextView>(R.id.dragHandle)
        val closeBtn = overlayView?.findViewById<TextView>(R.id.btnClose)

        // Apply saved display settings
        captionText?.textSize = SettingsManager.captionTextSize
        captionText?.maxLines = SettingsManager.captionMaxLines

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val hDp = SettingsManager.captionHeightDp
        val h   = if (hDp > 0) dp(hDp) else WindowManager.LayoutParams.WRAP_CONTENT

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, h, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = SettingsManager.overlayX
            y = SettingsManager.overlayY
        }

        windowManager?.addView(overlayView, layoutParams)

        closeBtn?.setOnClickListener { stopSelf() }

        val dragListener = View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x;  initialY = layoutParams.y
                    touchX   = event.rawX;      touchY   = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initialX + (event.rawX - touchX).toInt()
                    layoutParams.y = initialY - (event.rawY - touchY).toInt()
                    windowManager?.updateViewLayout(overlayView, layoutParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // Save position
                    SettingsManager.saveOverlayPos(
                        applicationContext, layoutParams.x, layoutParams.y)
                    false
                }
                else -> false
            }
        }
        handle?.setOnTouchListener(dragListener)
        liveBar?.setOnTouchListener(dragListener)
    }

    private fun removeOverlay() {
        try {
            if (overlayView != null) {
                windowManager?.removeView(overlayView)
                overlayView = null
            }
        } catch (_: Exception) {}
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false); setSound(null, null) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startForegroundCompat() {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_caption_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openIntent)
            .setOngoing(true).setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else
            startForeground(NOTIF_ID, notification)
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density + 0.5f).toInt()
}
