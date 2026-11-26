package com.example.floatingai

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val statusView = TextView(this).apply {
            text = "Status: Menunggu aktivasi"
            setPadding(0, 0, 0, 24)
        }

        val apiInput = EditText(this).apply {
            hint = "Masukkan Gemini API Key"
            val prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE)
            setText(prefs.getString("gemini_api_key", ""))
        }

        val permBtn = Button(this).apply {
            text = "Buka Izin Tampil di Atas Aplikasi Lain"
            setOnClickListener {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }

        val startBtn = Button(this).apply {
            text = "Aktifkan Floating Mod Menu"
            setOnClickListener {
                val key = apiInput.text.toString().trim()
                if (key.isEmpty()) {
                    Toast.makeText(this@MainActivity, "API Key wajib diisi", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                getSharedPreferences("ai_prefs", MODE_PRIVATE)
                    .edit()
                    .putString("gemini_api_key", key)
                    .apply()

                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    statusView.text = "Status: Izin overlay belum aktif. Aktifkan dulu di pengaturan."
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                    return@setOnClickListener
                }

                try {
                    val serviceIntent = Intent(this@MainActivity, FloatingOverlayService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                    statusView.text = "Status: Service overlay dijalankan. Cek layar atas kiri."
                    Toast.makeText(this@MainActivity, "Floating overlay dijalankan", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    statusView.text = "Gagal jalankan overlay: ${e.message}"
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(statusView)
            addView(apiInput)
            addView(permBtn)
            addView(startBtn)
        }
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        val canDraw = Settings.canDrawOverlays(this)
        Toast.makeText(
            this,
            if (canDraw) "Izin overlay: AKTIF" else "Izin overlay: BELUM AKTIF",
            Toast.LENGTH_SHORT
        ).show()
    }
}
