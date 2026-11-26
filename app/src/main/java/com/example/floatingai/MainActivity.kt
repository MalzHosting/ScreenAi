package com.example.floatingai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val apiInput = EditText(this).apply {
            hint = "Masukkan Gemini API Key"
            val prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE)
            setText(prefs.getString("gemini_api_key", ""))
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
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                    return@setOnClickListener
                }

                startService(Intent(this@MainActivity, FloatingOverlayService::class.java))
                Toast.makeText(this@MainActivity, "Floating overlay aktif", Toast.LENGTH_SHORT).show()
            }
        }

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(apiInput)
            addView(startBtn)
        }
        setContentView(layout)
    }
}
