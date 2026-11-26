package com.example.floatingai

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var apiInput: EditText

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(this, FloatingOverlayService::class.java).apply {
                action = FloatingOverlayService.ACTION_START_WITH_PROJECTION
                putExtra(FloatingOverlayService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(FloatingOverlayService.EXTRA_DATA_INTENT, result.data)
            }
            startOverlayServiceCompat(intent)
            statusView.text = "Status: Overlay & izin tangkap layar aktif."
        } else {
            statusView.text = "Status: Izin tangkap layar (MediaProjection) ditolak."
            Toast.makeText(this, "Izin tangkap layar diperlukan untuk scan soal", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statusView = TextView(this).apply {
            text = "Status: Siap dikonfigurasi"
            setPadding(0, 0, 0, 24)
        }

        apiInput = EditText(this).apply {
            hint = "Masukkan Gemini API Key"
            val prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE)
            setText(prefs.getString("gemini_api_key", ""))
        }

        val permBtn = Button(this).apply {
            text = "1. Izinkan Tampil di Atas Aplikasi Lain"
            setOnClickListener {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        val startBtn = Button(this).apply {
            text = "2. Aktifkan Floating Mod Menu"
            setOnClickListener {
                val key = apiInput.text.toString().trim()
                if (key.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Gemini API Key wajib diisi", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                getSharedPreferences("ai_prefs", MODE_PRIVATE)
                    .edit()
                    .putString("gemini_api_key", key)
                    .apply()

                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    statusView.text = "Status: Aktifkan dulu izin Tampil di atas aplikasi lain."
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                    return@setOnClickListener
                }

                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionLauncher.launch(mpm.createScreenCaptureIntent())
            }
        }

        val revokeBtn = Button(this).apply {
            text = "Revoke (Hentikan & Hapus Semua Overlay)"
            setOnClickListener {
                val intent = Intent(this@MainActivity, FloatingOverlayService::class.java).apply {
                    action = FloatingOverlayService.ACTION_REVOKE
                }
                startService(intent)
                statusView.text = "Status: Semua overlay & sesi di-revoke."
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(statusView)
            addView(apiInput)
            addView(permBtn)
            addView(startBtn)
            addView(revokeBtn)
        }
        setContentView(layout)
    }

    private fun startOverlayServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
