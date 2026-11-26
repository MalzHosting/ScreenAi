package com.example.floatingai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

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
                "ScreenAi Overlay Service",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notif: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("ScreenAi Aktif")
            .setContentText("Floating mod menu berjalan di layar")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .build()

        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, 1, notif, fgsType)
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
            x = 50
            y = 260
        }

        val headerView = TextView(this).apply {
            text = "≡ ScreenAi Menu"
            setTextColor(Color.CYAN)
            setPadding(16, 10, 16, 10)
        }

        val resultView = TextView(this).apply {
            text = "Overlay siap. Tekan Scan Soal."
            setTextColor(Color.WHITE)
            setPadding(16, 10, 16, 10)
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

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E610141D"))
            setPadding(24, 24, 24, 24)
            addView(headerView)
            addView(scanBtn)
            addView(resultView)
            addView(closeBtn)
        }

        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f

        headerView.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - touchX).toInt()
                        params.y = initialY + (event.rawY - touchY).toInt()
                        try {
                            windowManager?.updateViewLayout(container, params)
                        } catch (_: Exception) {
                        }
                        return true
                    }
                }
                return false
            }
        })

        try {
            windowManager?.addView(container, params)
            overlayView = container
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal tampilkan overlay: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    override fun onDestroy() {
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }
        overlayView = null
        super.onDestroy()
    }
}
