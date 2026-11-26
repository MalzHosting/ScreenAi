package com.example.floatingai

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class FloatingOverlayService : Service() {

    companion object {
        const val ACTION_START_WITH_PROJECTION = "com.example.floatingai.START_PROJECTION"
        const val ACTION_REVOKE = "com.example.floatingai.REVOKE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA_INTENT = "extra_data_intent"
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var windowManager: WindowManager? = null
    private var panelContainer: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelWebView: WebView? = null
    private var bubbleView: TextView? = null
    private var cropOverlayContainer: FrameLayout? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    @Volatile private var latestBitmap: Bitmap? = null

    private var screenWidth = 1080
    private var screenHeight = 1920
    private var screenDensity = 3
    private var selectedRect = Rect(80, 320, 1000, 1100)

    private var panelWidthPx = 640
    private var panelHeightPx = 520
    private var sizePresetIndex = 0 // 0 = Compact, 1 = Medium, 2 = Large

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_REVOKE -> {
                    revokeAll()
                    return START_NOT_STICKY
                }
                ACTION_START_WITH_PROJECTION -> {
                    startForegroundCompat(includeMediaProjection = true)
                    initScreenMetrics()
                    val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                    @Suppress("DEPRECATION")
                    val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
                    } else {
                        intent.getParcelableExtra(EXTRA_DATA_INTENT)
                    }
                    if (code == Activity.RESULT_OK && data != null) {
                        setupMediaProjectionSafely(code, data)
                    }
                    showPanel()
                }
                else -> {
                    startForegroundCompat(includeMediaProjection = false)
                    initScreenMetrics()
                    showPanel()
                }
            }
        } catch (e: Exception) {
            pushAnswerToPanel("Error start service: ${e.message}")
        }
        return START_STICKY
    }

    private fun initScreenMetrics() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels.coerceAtLeast(480)
        screenHeight = metrics.heightPixels.coerceAtLeast(800)
        screenDensity = metrics.densityDpi.coerceAtLeast(160)

        val prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE)
        val defL = (screenWidth * 0.08f).toInt()
        val defT = (screenHeight * 0.22f).toInt()
        val defR = (screenWidth * 0.92f).toInt()
        val defB = (screenHeight * 0.52f).toInt()

        selectedRect = Rect(
            prefs.getInt("crop_left", defL).coerceIn(0, screenWidth - 120),
            prefs.getInt("crop_top", defT).coerceIn(0, screenHeight - 120),
            prefs.getInt("crop_right", defR).coerceIn(120, screenWidth),
            prefs.getInt("crop_bottom", defB).coerceIn(120, screenHeight)
        )

        applySizePreset(sizePresetIndex, updateWindow = false)
    }

    private fun applySizePreset(index: Int, updateWindow: Boolean = true) {
        sizePresetIndex = ((index % 3) + 3) % 3
        when (sizePresetIndex) {
            0 -> { // Compact (kecil agar soal di layar tetap kelihatan)
                panelWidthPx = (screenWidth * 0.62f).toInt().coerceAtLeast(360)
                panelHeightPx = (screenHeight * 0.27f).toInt().coerceAtLeast(260)
            }
            1 -> { // Medium
                panelWidthPx = (screenWidth * 0.78f).toInt().coerceAtLeast(420)
                panelHeightPx = (screenHeight * 0.37f).toInt().coerceAtLeast(340)
            }
            else -> { // Large
                panelWidthPx = (screenWidth * 0.90f).toInt().coerceAtLeast(460)
                panelHeightPx = (screenHeight * 0.48f).toInt().coerceAtLeast(420)
            }
        }

        if (updateWindow) {
            val p = panelParams
            val v = panelContainer
            if (p != null && v != null) {
                p.width = panelWidthPx
                p.height = panelHeightPx
                try {
                    windowManager?.updateViewLayout(v, p)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun saveSelectedRect(rect: Rect) {
        selectedRect.set(rect)
        getSharedPreferences("ai_prefs", MODE_PRIVATE)
            .edit()
            .putInt("crop_left", rect.left)
            .putInt("crop_top", rect.top)
            .putInt("crop_right", rect.right)
            .putInt("crop_bottom", rect.bottom)
            .apply()
    }

    private fun startForegroundCompat(includeMediaProjection: Boolean) {
        val channelId = "screen_ai_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "ScreenAi Overlay",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notif: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("ScreenAi Aktif")
            .setContentText("Overlay & pembaca soal berjalan")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .build()

        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (includeMediaProjection) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
        } else {
            0
        }
        ServiceCompat.startForeground(this, 101, notif, fgsType)
    }

    private fun setupMediaProjectionSafely(resultCode: Int, data: Intent) {
        cleanupProjectionResources()
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mpm.getMediaProjection(resultCode, data) ?: return
            mediaProjection = mp

            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    cleanupProjectionResources()
                }
            }, mainHandler)

            val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            reader.setOnImageAvailableListener({ ir ->
                val img = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val plane = img.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * screenWidth

                    val rawBmp = Bitmap.createBitmap(
                        screenWidth + rowPadding / pixelStride,
                        screenHeight,
                        Bitmap.Config.ARGB_8888
                    )
                    rawBmp.copyPixelsFromBuffer(buffer)
                    val cleanBmp = Bitmap.createBitmap(rawBmp, 0, 0, screenWidth, screenHeight)
                    if (cleanBmp != rawBmp) rawBmp.recycle()

                    val old = latestBitmap
                    latestBitmap = cleanBmp
                    old?.recycle()
                } catch (_: Exception) {
                } finally {
                    img.close()
                }
            }, mainHandler)

            imageReader = reader
            virtualDisplay = mp.createVirtualDisplay(
                "ScreenAiVD",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                mainHandler
            )
        } catch (e: Exception) {
            pushAnswerToPanel("Gagal setup screen capture: ${e.message}")
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun showPanel() {
        removeBubbleView()
        removeCropOverlay()

        if (panelContainer != null && panelParams != null) {
            panelParams?.width = panelWidthPx
            panelParams?.height = panelHeightPx
            try {
                windowManager?.updateViewLayout(panelContainer, panelParams)
            } catch (_: Exception) {
            }
            panelContainer?.visibility = View.VISIBLE
            return
        }

        val params = WindowManager.LayoutParams(
            panelWidthPx,
            panelHeightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 120
        }
        panelParams = params

        val dragBar = TextView(this).apply {
            text = "  ≡ ScreenAi  •  Geser"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 11f
            setBackgroundColor(Color.parseColor("#FF0F172A"))
            setPadding(12, 12, 8, 12)
        }

        val shrinkBtn = TextView(this).apply {
            text = "−"
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 14f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#FF1E293B"))
            setPadding(22, 8, 22, 8)
            setOnClickListener {
                applySizePreset(sizePresetIndex - 1, updateWindow = true)
            }
        }

        val growBtn = TextView(this).apply {
            text = "+"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 14f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#FF1E293B"))
            setPadding(22, 8, 22, 8)
            setOnClickListener {
                applySizePreset(sizePresetIndex + 1, updateWindow = true)
            }
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#FF0F172A"))
            addView(dragBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(shrinkBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(growBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f

        dragBar.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    try {
                        windowManager?.updateViewLayout(panelContainer, params)
                    } catch (_: Exception) {
                    }
                    true
                }
                else -> false
            }
        }

        val wv = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#F20A101D"))
            settings.javaScriptEnabled = true
            addJavascriptInterface(OverlayBridge(), "OverlayApi")
            loadDataWithBaseURL(null, buildPanelHtml(), "text/html", "UTF-8", null)
        }
        panelWebView = wv

        val resizeHandle = TextView(this).apply {
            text = "⤡ Tarik Ukuran"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 10f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#FF0F172A"))
            setPadding(12, 8, 16, 8)
        }

        var startW = panelWidthPx
        var startH = panelHeightPx
        var resTouchX = 0f
        var resTouchY = 0f

        resizeHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startW = params.width
                    startH = params.height
                    resTouchX = event.rawX
                    resTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val newW = (startW + (event.rawX - resTouchX).toInt())
                        .coerceIn((screenWidth * 0.42f).toInt(), (screenWidth * 0.95f).toInt())
                    val newH = (startH + (event.rawY - resTouchY).toInt())
                        .coerceIn((screenHeight * 0.20f).toInt(), (screenHeight * 0.75f).toInt())
                    panelWidthPx = newW
                    panelHeightPx = newH
                    params.width = newW
                    params.height = newH
                    try {
                        windowManager?.updateViewLayout(panelContainer, params)
                    } catch (_: Exception) {
                    }
                    true
                }
                else -> false
            }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F20A101D"))
            addView(topBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(wv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(resizeHandle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        try {
            windowManager?.addView(container, params)
            panelContainer = container
        } catch (e: Exception) {
            pushAnswerToPanel("Gagal menampilkan overlay: ${e.message}")
        }
    }

    private fun buildPanelHtml(): String {
        return """
            <!DOCTYPE html>
            <html lang="id">
            <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
              * { box-sizing: border-box; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; }
              body {
                margin: 0; padding: 8px; background: transparent; color: #F8FAFC;
                display: flex; flex-direction: column; height: 100vh;
              }
              .grid-btn { display: grid; grid-template-columns: 1fr 1fr; gap: 6px; margin-bottom: 7px; }
              button {
                border: none; border-radius: 9px; padding: 7px 6px;
                font-size: 10.5px; font-weight: 700; cursor: pointer;
              }
              .b-area { background: rgba(56,189,248,0.18); color: #38BDF8; border: 1px solid rgba(56,189,248,0.35); }
              .b-scan { background: linear-gradient(135deg, #0284C7, #38BDF8); color: #04101A; }
              .b-min  { background: rgba(148,163,184,0.16); color: #E2E8F0; border: 1px solid rgba(148,163,184,0.25); }
              .b-rev  { background: rgba(239,68,68,0.2); color: #F87171; border: 1px solid rgba(248,113,113,0.4); }
              .answer-box {
                flex: 1; overflow-y: auto; background: rgba(2, 6, 23, 0.88);
                border: 1px solid rgba(148,163,184,0.16); border-radius: 10px;
                padding: 8px; font-size: 11px; line-height: 1.45; color: #E2E8F0;
                white-space: pre-wrap;
              }
            </style>
            </head>
            <body>
              <div class="grid-btn">
                <button class="b-area" onclick="OverlayApi.toggleArea()">Atur Area</button>
                <button class="b-scan" onclick="OverlayApi.scanNow()">Scan Soal</button>
                <button class="b-min" onclick="OverlayApi.minimizeBubble()">Tutup ke Icon</button>
                <button class="b-rev" onclick="OverlayApi.revokeNow()">Revoke Semua</button>
              </div>
              <div id="out" class="answer-box">Atur area lalu tekan Scan Soal. Gunakan tombol - / + atau Tarik Ukuran untuk mengecilkan panel.</div>
              <script>
                function setAnswer(text) {
                  const box = document.getElementById('out');
                  box.innerText = text;
                  box.scrollTop = 0;
                }
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun pushAnswerToPanel(msg: String) {
        val safe = msg.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "")
        mainHandler.post {
            try {
                panelWebView?.evaluateJavascript("setAnswer('$safe');", null)
            } catch (_: Exception) {
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun minimizeToBubble() {
        removeCropOverlay()
        removePanelView()
        if (bubbleView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 260
        }

        val bubble = TextView(this).apply {
            text = "AI"
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundColor(Color.parseColor("#E60284C7"))
            setPadding(28, 20, 28, 20)
        }

        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) moved = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    try {
                        windowManager?.updateViewLayout(bubble, params)
                    } catch (_: Exception) {
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) showPanel()
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(bubble, params)
            bubbleView = bubble
        } catch (_: Exception) {
        }
    }

    private fun openCropOverlay() {
        if (cropOverlayContainer != null) return
        panelContainer?.visibility = View.GONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        val draftRect = Rect(selectedRect)
        val cropView = CropBoxView(this, draftRect) { updated ->
            draftRect.set(updated)
        }

        val saveBtn = Button(this).apply {
            text = "Simpan Area"
            setBackgroundColor(Color.parseColor("#FF0284C7"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                saveSelectedRect(draftRect)
                removeCropOverlay()
                panelContainer?.visibility = View.VISIBLE
                pushAnswerToPanel("Area tersimpan (${selectedRect.width()}x${selectedRect.height()} px). Tekan Scan Soal.")
            }
        }

        val cancelBtn = Button(this).apply {
            text = "Batal"
            setBackgroundColor(Color.parseColor("#FF1E293B"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                removeCropOverlay()
                panelContainer?.visibility = View.VISIBLE
                pushAnswerToPanel("Pengaturan area dibatalkan.")
            }
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#E6020617"))
            setPadding(24, 24, 24, 40)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(10, 0, 10, 0)
            }
            addView(cancelBtn, lp)
            addView(saveBtn, lp)
        }

        val root = FrameLayout(this).apply {
            addView(cropView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }

        try {
            windowManager?.addView(root, params)
            cropOverlayContainer = root
        } catch (e: Exception) {
            panelContainer?.visibility = View.VISIBLE
            pushAnswerToPanel("Gagal buka area crop: ${e.message}")
        }
    }

    private fun triggerScan() {
        val apiKey = getSharedPreferences("ai_prefs", MODE_PRIVATE)
            .getString("gemini_api_key", "")
            ?.trim()
            .orEmpty()

        if (apiKey.isEmpty()) {
            pushAnswerToPanel("API Key kosong. Buka aplikasi utama dan isi Gemini API Key.")
            return
        }

        removeCropOverlay()
        val prevVis = panelContainer?.visibility ?: View.VISIBLE
        panelContainer?.visibility = View.INVISIBLE
        pushAnswerToPanel("Memindai area layar...")

        mainHandler.postDelayed({
            serviceScope.launch {
                try {
                    val src = latestBitmap
                    val cropped = if (src != null && !src.isRecycled) {
                        val safeL = selectedRect.left.coerceIn(0, src.width - 2)
                        val safeT = selectedRect.top.coerceIn(0, src.height - 2)
                        val safeW = selectedRect.width().coerceAtLeast(32).coerceAtMost(src.width - safeL)
                        val safeH = selectedRect.height().coerceAtLeast(32).coerceAtMost(src.height - safeT)
                        Bitmap.createBitmap(src, safeL, safeT, safeW, safeH)
                    } else null

                    panelContainer?.visibility = prevVis

                    if (cropped == null) {
                        pushAnswerToPanel("Frame layar belum tersedia. Pastikan izin rekam layar aktif dan layar tidak diblokir proteksi aplikasi.")
                        return@launch
                    }

                    val reply = withContext(Dispatchers.IO) {
                        askGeminiWithRetry(apiKey, cropped)
                    }
                    cropped.recycle()
                    pushAnswerToPanel(reply)
                } catch (e: Exception) {
                    panelContainer?.visibility = prevVis
                    pushAnswerToPanel("Gagal memproses soal: ${e.message}")
                }
            }
        }, 250)
    }

    private suspend fun askGeminiWithRetry(apiKey: String, bitmap: Bitmap): String {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 82, baos)
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

        val parts = JSONArray().apply {
            put(JSONObject().apply {
                put("inline_data", JSONObject().apply {
                    put("mime_type", "image/jpeg")
                    put("data", b64)
                })
            })
            put(JSONObject().apply {
                put("text", "Baca teks, rumus, tabel, atau gambar soal pada bagian layar ini. Berikan jawaban akhir yang jelas dan langkah singkat dalam Bahasa Indonesia.")
            })
        }
        val payload = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().put("parts", parts))
            })
        }

        val models = listOf("gemini-3.8-flash", "gemini-3.5-flash", "gemini-2.5-flash", "gemini-2.0-flash")
        var lastError = ""

        for (modelName in models) {
            for (attempt in 1..2) {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
                val req = Request.Builder()
                    .url(url)
                    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                try {
                    httpClient.newCall(req).execute().use { res ->
                        val bodyStr = res.body?.string().orEmpty()
                        if (res.isSuccessful) {
                            val json = JSONObject(bodyStr)
                            val cand = json.optJSONArray("candidates") ?: return "Respons kosong."
                            if (cand.length() == 0) return "Tidak ada jawaban dihasilkan."
                            val resParts = cand.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                                ?: return "Format tidak valid."
                            val sb = StringBuilder()
                            for (i in 0 until resParts.length()) {
                                val t = resParts.getJSONObject(i).optString("text", "")
                                if (t.isNotEmpty()) sb.append(t)
                            }
                            return if (sb.isNotEmpty()) sb.toString().trim() else "Jawaban kosong."
                        } else {
                            val errMsg = try {
                                JSONObject(bodyStr).optJSONObject("error")?.optString("message") ?: bodyStr
                            } catch (_: Exception) {
                                bodyStr
                            }
                            lastError = "[$modelName] HTTP ${res.code}: $errMsg"
                            if (res.code == 503 || res.code == 429) {
                                if (attempt == 1) {
                                    delay(900)
                                    continue
                                }
                                break // pindah ke model berikutnya
                            }
                            if (res.code != 404) {
                                return lastError
                            }
                            break
                        }
                    }
                } catch (e: Exception) {
                    lastError = "[$modelName] ${e.message}"
                    if (attempt == 1) {
                        delay(800)
                    } else {
                        break
                    }
                }
            }
        }
        return lastError.ifEmpty { "Gagal menghubungi Gemini API." }
    }

    private fun revokeAll() {
        removeCropOverlay()
        removePanelView()
        removeBubbleView()
        cleanupProjectionResources()
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
        serviceJob.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun cleanupProjectionResources() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null

        try {
            latestBitmap?.recycle()
        } catch (_: Exception) {
        }
        latestBitmap = null
    }

    private fun removePanelView() {
        try {
            panelWebView?.destroy()
        } catch (_: Exception) {
        }
        panelWebView = null

        panelContainer?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }
        panelContainer = null
    }

    private fun removeBubbleView() {
        bubbleView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }
        bubbleView = null
    }

    private fun removeCropOverlay() {
        cropOverlayContainer?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }
        cropOverlayContainer = null
    }

    override fun onDestroy() {
        removeCropOverlay()
        removePanelView()
        removeBubbleView()
        cleanupProjectionResources()
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        serviceJob.cancel()
        super.onDestroy()
    }

    private inner class OverlayBridge {
        @JavascriptInterface
        fun toggleArea() {
            mainHandler.post { openCropOverlay() }
        }

        @JavascriptInterface
        fun scanNow() {
            mainHandler.post { triggerScan() }
        }

        @JavascriptInterface
        fun minimizeBubble() {
            mainHandler.post { minimizeToBubble() }
        }

        @JavascriptInterface
        fun revokeNow() {
            mainHandler.post { revokeAll() }
        }
    }

    private class CropBoxView(
        context: Context,
        initialRect: Rect,
        private val onRectChanged: (Rect) -> Unit
    ) : View(context) {
        private val boxRect = Rect(initialRect)
        private val borderPaint = Paint().apply {
            color = Color.parseColor("#38BDF8")
            style = Paint.Style.STROKE
            strokeWidth = 6f
            pathEffect = DashPathEffect(floatArrayOf(18f, 10f), 0f)
        }
        private val handlePaint = Paint().apply {
            color = Color.parseColor("#38BDF8")
            style = Paint.Style.FILL
        }
        private val dimPaint = Paint().apply {
            color = Color.parseColor("#66000000")
            style = Paint.Style.FILL
        }
        private enum class Mode { NONE, MOVE, RESIZE }
        private var mode = Mode.NONE
        private var lastX = 0f
        private var lastY = 0f
        private val handleR = 40f

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawRect(0f, 0f, w, boxRect.top.toFloat(), dimPaint)
            canvas.drawRect(0f, boxRect.bottom.toFloat(), w, h, dimPaint)
            canvas.drawRect(0f, boxRect.top.toFloat(), boxRect.left.toFloat(), boxRect.bottom.toFloat(), dimPaint)
            canvas.drawRect(boxRect.right.toFloat(), boxRect.top.toFloat(), w, boxRect.bottom.toFloat(), dimPaint)
            canvas.drawRect(boxRect, borderPaint)
            canvas.drawCircle(boxRect.right.toFloat(), boxRect.bottom.toFloat(), handleR, handlePaint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    val d = kotlin.math.hypot((event.x - boxRect.right).toDouble(), (event.y - boxRect.bottom).toDouble())
                    mode = when {
                        d <= handleR * 2.0 -> Mode.RESIZE
                        boxRect.contains(event.x.toInt(), event.y.toInt()) -> Mode.MOVE
                        else -> Mode.NONE
                    }
                    return mode != Mode.NONE
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.x - lastX).toInt()
                    val dy = (event.y - lastY).toInt()
                    lastX = event.x
                    lastY = event.y
                    when (mode) {
                        Mode.MOVE -> {
                            boxRect.offsetTo(
                                (boxRect.left + dx).coerceIn(0, (width - boxRect.width()).coerceAtLeast(0)),
                                (boxRect.top + dy).coerceIn(0, (height - boxRect.height()).coerceAtLeast(0))
                            )
                        }
                        Mode.RESIZE -> {
                            boxRect.right = (boxRect.right + dx).coerceIn(boxRect.left + 120, width)
                            boxRect.bottom = (boxRect.bottom + dy).coerceIn(boxRect.top + 120, height)
                        }
                        Mode.NONE -> Unit
                    }
                    onRectChanged(boxRect)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mode = Mode.NONE
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
