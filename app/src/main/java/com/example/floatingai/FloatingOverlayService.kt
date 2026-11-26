package com.example.floatingai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

class FloatingOverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var overlayView: LinearLayout? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        showFloatingMenu()
    }

    private fun startForegroundNotification() {
        val channelId = "floating_ai_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Floating AI Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notif: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("FloatingSoalAI Aktif")
            .setContentText("Siap membaca soal di layar")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .build()
        startForeground(1, notif)
    }

    private fun showFloatingMenu() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 200
        }

        val resultView = TextView(this).apply {
            text = "Hasil jawaban muncul di sini"
            setTextColor(Color.WHITE)
            setPadding(16, 12, 16, 12)
        }

        val scanBtn = Button(this).apply {
            text = "Scan Soal"
            setOnClickListener {
                resultView.text = "Menyiapkan capture layar & Gemini API..."
            }
        }

        val closeBtn = Button(this).apply {
            text = "Tutup"
            setOnClickListener {
                stopSelf()
            }
        }

        overlayView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC121212"))
            setPadding(20, 20, 20, 20)
            addView(scanBtn)
            addView(resultView)
            addView(closeBtn)
        }

        windowManager?.addView(overlayView, params)
    }

    override fun onDestroy() {
        overlayView?.let { windowManager?.removeView(it) }
        super.onDestroy()
    }
}
