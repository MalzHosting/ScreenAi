package com.example.floatingai

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            try {
                val intent = Intent(this, FloatingOverlayService::class.java).apply {
                    action = FloatingOverlayService.ACTION_START_WITH_PROJECTION
                    putExtra(FloatingOverlayService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(FloatingOverlayService.EXTRA_DATA_INTENT, result.data)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                updateWebStatus("Floating overlay & capture layar aktif.")
            } catch (e: Exception) {
                updateWebStatus("Gagal memulai service: ${e.message}")
            }
        } else {
            updateWebStatus("Izin tangkap layar dibatalkan.")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#070B14"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            addJavascriptInterface(MainBridge(), "ScreenAiBridge")
        }
        setContentView(webView)
        renderSetupHtml()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            val permText = if (Settings.canDrawOverlays(this)) "AKTIF" else "BELUM AKTIF"
            runOnUiThread {
                webView.evaluateJavascript("setOverlayPermStatus('$permText');", null)
            }
        }
    }

    private fun updateWebStatus(msg: String) {
        val safeMsg = msg.replace("'", "\\'").replace("\n", " ")
        runOnUiThread {
            webView.evaluateJavascript("setStatusText('$safeMsg');", null)
        }
    }

    private fun renderSetupHtml() {
        val savedKey = getSharedPreferences("ai_prefs", MODE_PRIVATE)
            .getString("gemini_api_key", "")
            ?.replace("\\", "\\\\")
            ?.replace("'", "\\'")
            .orEmpty()
        val permStatus = if (Settings.canDrawOverlays(this)) "AKTIF" else "BELUM AKTIF"

        val html = """
            <!DOCTYPE html>
            <html lang="id">
            <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
              * { box-sizing: border-box; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; }
              body {
                margin: 0; padding: 24px 18px; min-height: 100vh;
                background: radial-gradient(circle at top right, rgba(56,189,248,0.16), transparent 45%), #070B14;
                color: #F8FAFC;
              }
              .card {
                background: rgba(15, 23, 42, 0.88); border: 1px solid rgba(148, 163, 184, 0.18);
                border-radius: 18px; padding: 20px; backdrop-filter: blur(12px);
                box-shadow: 0 18px 40px rgba(0,0,0,0.45);
              }
              .badge {
                display: inline-block; padding: 4px 10px; border-radius: 999px;
                background: rgba(56,189,248,0.15); color: #38BDF8; font-size: 11px; font-weight: 700;
                letter-spacing: 0.6px; margin-bottom: 10px;
              }
              h1 { margin: 0 0 6px; font-size: 22px; font-weight: 700; }
              p.sub { margin: 0 0 18px; font-size: 13px; color: #94A3B8; line-height: 1.5; }
              label { display: block; font-size: 12px; color: #CBD5E1; margin-bottom: 6px; font-weight: 600; }
              input {
                width: 100%; padding: 13px 14px; border-radius: 12px;
                border: 1px solid rgba(148, 163, 184, 0.25); background: #020617;
                color: #F8FAFC; font-size: 14px; margin-bottom: 14px; outline: none;
              }
              input:focus { border-color: #38BDF8; }
              .row-info {
                display: flex; justify-content: space-between; align-items: center;
                background: rgba(2, 6, 23, 0.7); border: 1px solid rgba(148,163,184,0.12);
                border-radius: 12px; padding: 11px 14px; margin-bottom: 16px; font-size: 12px;
              }
              .pill-ok { color: #4ADE80; font-weight: 700; }
              .pill-no { color: #FBBF24; font-weight: 700; }
              button {
                width: 100%; padding: 13px 16px; border: none; border-radius: 12px;
                font-size: 14px; font-weight: 700; cursor: pointer; margin-bottom: 10px;
              }
              .btn-sec { background: rgba(51, 65, 85, 0.75); color: #F8FAFC; border: 1px solid rgba(148,163,184,0.2); }
              .btn-pri { background: linear-gradient(135deg, #0284C7, #38BDF8); color: #04101A; }
              .btn-red { background: rgba(239, 68, 68, 0.16); color: #F87171; border: 1px solid rgba(248,113,113,0.35); margin-bottom: 0; }
              .status {
                margin-top: 14px; padding: 12px; border-radius: 12px;
                background: rgba(2, 6, 23, 0.85); color: #93C5FD; font-size: 12px; line-height: 1.45;
              }
            </style>
            </head>
            <body>
              <div class="card">
                <div class="badge">SCREENAI OVERLAY v2</div>
                <h1>Floating Soal Assistant</h1>
                <p class="sub">Pilih area teks atau gambar di layar, kirim ke Gemini AI, perkecil jadi ikon mengambang, atau revoke kapan saja.</p>
                <label for="apiKey">Gemini API Key</label>
                <input id="apiKey" type="password" placeholder="AIzaSy..." value="$savedKey" />
                <div class="row-info">
                  <span>Izin Tampil di Atas Aplikasi</span>
                  <span id="permBadge" class="${if (permStatus == "AKTIF") "pill-ok" else "pill-no"}">$permStatus</span>
                </div>
                <button class="btn-sec" onclick="ScreenAiBridge.openOverlayPermission()">1. Buka Izin Overlay</button>
                <button class="btn-pri" onclick="startOverlay()">2. Aktifkan Floating Mod Menu</button>
                <button class="btn-red" onclick="ScreenAiBridge.revokeAll()">Revoke (Hentikan & Hapus Semua)</button>
                <div id="statusBox" class="status">Status: Siap digunakan.</div>
              </div>
              <script>
                function startOverlay() {
                  const key = document.getElementById('apiKey').value.trim();
                  ScreenAiBridge.saveAndLaunch(key);
                }
                function setStatusText(txt) {
                  document.getElementById('statusBox').innerText = 'Status: ' + txt;
                }
                function setOverlayPermStatus(state) {
                  const el = document.getElementById('permBadge');
                  el.innerText = state;
                  el.className = state === 'AKTIF' ? 'pill-ok' : 'pill-no';
                }
              </script>
            </body>
            </html>
        """.trimIndent()

        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    inner class MainBridge {
        @JavascriptInterface
        fun openOverlayPermission() {
            runOnUiThread {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Gagal buka pengaturan: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun saveAndLaunch(apiKey: String) {
            runOnUiThread {
                if (apiKey.isBlank()) {
                    updateWebStatus("Masukkan Gemini API Key terlebih dahulu.")
                    return@runOnUiThread
                }
                getSharedPreferences("ai_prefs", MODE_PRIVATE)
                    .edit()
                    .putString("gemini_api_key", apiKey.trim())
                    .apply()

                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    updateWebStatus("Aktifkan dulu izin Tampil di atas aplikasi lain.")
                    openOverlayPermission()
                    return@runOnUiThread
                }

                try {
                    val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    projectionLauncher.launch(mpm.createScreenCaptureIntent())
                } catch (e: Exception) {
                    updateWebStatus("Gagal memulai screen capture: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun revokeAll() {
            runOnUiThread {
                try {
                    val intent = Intent(this@MainActivity, FloatingOverlayService::class.java).apply {
                        action = FloatingOverlayService.ACTION_REVOKE
                    }
                    startService(intent)
                    updateWebStatus("Semua overlay & sesi di-revoke.")
                } catch (e: Exception) {
                    updateWebStatus("Error revoke: ${e.message}")
                }
            }
        }
    }
}
