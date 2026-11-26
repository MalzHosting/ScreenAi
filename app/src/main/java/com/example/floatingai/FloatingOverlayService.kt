package com.example.floatingai

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var windowManager: WindowManager? = null
    private var panelView: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var bubbleView: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var cropOverlayView: CropBoxView? = null
    private var cropParams: WindowManager.LayoutParams? = null

    private var resultView: TextView? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth = 1080
    private var screenHeight = 1920
    private var screenDensity = 3

    private var selectedRect = Rect(80, 320, 1000, 1100)
    private var isSelectingArea = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REVOKE -> {
                revokeAll()
                return START_NOT_STICKY
            }
            ACTION_START_WITH_PROJECTION -> {
                startForegroundCompat()
                initScreenMetrics()
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(EXTRA_DATA_INTENT)
                }
                if (code == Activity.RESULT_OK && data != null) {
                    setupMediaProjection(code, data)
                }
                showPanel()
            }
            else -> {
                startForegroundCompat()
                initScreenMetrics()
                showPanel()
            }
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

        val left = (screenWidth * 0.08f).toInt()
        val right = (screenWidth * 0.92f).toInt()
        val top = (screenHeight * 0.22f).toInt()
        val bottom = (screenHeight * 0.56f).toInt()
        selectedRect = Rect(left, top, right, bottom)
    }

    private fun startForegroundCompat() {
        val channelId = "screen_ai_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "ScreenAi Service",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notif: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("ScreenAi Berjalan")
            .setContentText("Siap scan area soal & gambar ke Gemini AI")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .build()

        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, 101, notif, fgsType)
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        stopProjectionOnly()
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                cleanupProjectionResources()
            }
        }, mainHandler)

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenAiCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            mainHandler
        )
    }

    private fun showPanel() {
        removeBubbleView()
        removeCropOverlay()
        if (panelView != null) {
            panelView?.visibility = View.VISIBLE
            return
        }

        val params = WindowManager.LayoutParams(
            (screenWidth * 0.86f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 140
        }
        panelParams = params

        val header = TextView(this).apply {
            text = "≡ ScreenAi Mod Menu (Geser di sini)"
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(12, 8, 12, 12)
        }

        val areaBtn = Button(this).apply {
            text = "Atur Ukuran / Area Baca"
            setOnClickListener {
                toggleCropBox()
            }
        }

        val scanBtn = Button(this).apply {
            text = "Scan Area & Jawab (Gemini)"
            setOnClickListener {
                runScanAndAskGemini()
            }
        }

        val minBtn = Button(this).apply {
            text = "Tutup ke Icon Mengambang"
            setOnClickListener {
                minimizeToBubble()
            }
        }

        val revokeBtn = Button(this).apply {
            text = "Revoke (Hapus Semua)"
            setTextColor(Color.parseColor("#F87171"))
            setOnClickListener {
                revokeAll()
            }
        }

        val answerText = TextView(this).apply {
            text = "1. Tekan 'Atur Ukuran / Area Baca' untuk memilih bagian teks/gambar soal.\n2. Tekan 'Scan Area & Jawab (Gemini)'."
            setTextColor(Color.WHITE)
            setPadding(12, 12, 12, 12)
        }
        resultView = answerText

        val scrollResult = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (screenHeight * 0.24f).toInt()
            )
            addView(answerText)
        }

        val btnRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(areaBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(scanBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        val btnRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(minBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(revokeBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EB0F172A"))
            setPadding(20, 20, 20, 20)
            addView(header)
            addView(btnRow1)
            addView(btnRow2)
            addView(scrollResult)
        }

        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f

        header.setOnTouchListener { _, event ->
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
                        windowManager?.updateViewLayout(container, params)
                    } catch (_: Exception) {
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(container, params)
            panelView = container
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal membuka panel: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

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
            x = 30
            y = 320
        }
        bubbleParams = params

        val bubble = TextView(this).apply {
            text = "AI"
            setTextColor(Color.WHITE)
            textSize = 16f
            setBackgroundColor(Color.parseColor("#E60284C7"))
            setPadding(34, 26, 34, 26)
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
                    if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) {
                        moved = true
                    }
                    params.x = initialX + dx
                    params.y = initialY + dy
                    try {
                        windowManager?.updateViewLayout(bubble, params)
                    } catch (_: Exception) {
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        showPanel()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(bubble, params)
            bubbleView = bubble
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal membuat bubble: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleCropBox() {
        if (cropOverlayView != null) {
            removeCropOverlay()
            isSelectingArea = false
            resultView?.text = "Area tersimpan: ${selectedRect.width()}x${selectedRect.height()} px. Tekan 'Scan Area & Jawab (Gemini)'."
            return
        }

        isSelectingArea = true
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        cropParams = params

        val cropView = CropBoxView(this, selectedRect) { updatedRect ->
            selectedRect.set(updatedRect)
        }

        try {
            windowManager?.addView(cropView, params)
            cropOverlayView = cropView
            resultView?.text = "Seret kotak atau sudut kanan bawah untuk mengatur ukuran area soal/gambar, lalu tekan 'Atur Ukuran / Area Baca' lagi untuk mengunci."
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal menampilkan kotak area: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runScanAndAskGemini() {
        val apiKey = getSharedPreferences("ai_prefs", MODE_PRIVATE)
            .getString("gemini_api_key", "")
            ?.trim()
            .orEmpty()

        if (apiKey.isEmpty()) {
            resultView?.text = "Error: Gemini API Key kosong. Buka aplikasi utama dan isi API Key."
            return
        }

        if (mediaProjection == null || imageReader == null) {
            resultView?.text = "Error: MediaProjection belum aktif. Buka aplikasi utama dan tekan '2. Aktifkan Floating Mod Menu'."
            return
        }

        removeCropOverlay()
        val prevVisibility = panelView?.visibility ?: View.VISIBLE
        panelView?.visibility = View.INVISIBLE
        resultView?.text = "Sedang mengambil & menganalisis area layar..."

        mainHandler.postDelayed({
            serviceScope.launch {
                try {
                    val croppedBitmap = captureCroppedBitmap(selectedRect)
                    panelView?.visibility = prevVisibility

                    if (croppedBitmap == null) {
                        resultView?.text = "Gagal mengambil gambar layar. Pastikan layar tidak diblokir proteksi aplikasi."
                        return@launch
                    }

                    val answer = withContext(Dispatchers.IO) {
                        queryGeminiWithImage(apiKey, croppedBitmap)
                    }
                    resultView?.text = answer
                } catch (e: Exception) {
                    panelView?.visibility = prevVisibility
                    resultView?.text = "Terjadi error: ${e.message}"
                }
            }
        }, 250)
    }

    private fun captureCroppedBitmap(rect: Rect): Bitmap? {
        val reader = imageReader ?: return null
        val image = reader.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val fullBitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            fullBitmap.copyPixelsFromBuffer(buffer)

            val trimmedFull = Bitmap.createBitmap(fullBitmap, 0, 0, screenWidth, screenHeight)
            if (trimmedFull != fullBitmap) fullBitmap.recycle()

            val safeLeft = rect.left.coerceIn(0, screenWidth - 2)
            val safeTop = rect.top.coerceIn(0, screenHeight - 2)
            val safeWidth = rect.width().coerceAtLeast(32).coerceAtMost(screenWidth - safeLeft)
            val safeHeight = rect.height().coerceAtLeast(32).coerceAtMost(screenHeight - safeTop)

            val cropped = Bitmap.createBitmap(trimmedFull, safeLeft, safeTop, safeWidth, safeHeight)
            if (cropped != trimmedFull) trimmedFull.recycle()
            cropped
        } finally {
            image.close()
        }
    }

    private fun queryGeminiWithImage(apiKey: String, bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        val base64Image = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)

        val partImage = JSONObject().apply {
            put("inline_data", JSONObject().apply {
                put("mime_type", "image/jpeg")
                put("data", base64Image)
            })
        }
        val partText = JSONObject().apply {
            put(
                "text",
                "Baca semua teks, rumus, tabel, atau gambar soal pada bagian layar ini. Berikan jawaban yang jelas dan akurat dalam Bahasa Indonesia, disertai langkah singkat bila perlu."
            )
        }
        val payload = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(partImage)
                        put(partText)
                    })
                })
            })
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(url).post(body).build()

        httpClient.newCall(request).execute().use { resp ->
            val rawBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                return "HTTP ${resp.code}: $rawBody"
            }
            val root = JSONObject(rawBody)
            val candidates = root.optJSONArray("candidates") ?: return "Tidak ada kandidat jawaban."
            if (candidates.length() == 0) return "Respons kosong dari Gemini."
            val parts = candidates.getJSONObject(0)
                .optJSONObject("content")
                ?.optJSONArray("parts") ?: return "Format jawaban tidak ditemukan."
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val txt = parts.getJSONObject(i).optString("text", "")
                if (txt.isNotEmpty()) {
                    sb.append(txt)
                }
            }
            return if (sb.isNotEmpty()) sb.toString().trim() else "Jawaban kosong."
        }
    }

    private fun revokeAll() {
        removeCropOverlay()
        removePanelView()
        removeBubbleView()
        stopProjectionOnly()
        serviceJob.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun stopProjectionOnly() {
        cleanupProjectionResources()
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
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
    }

    private fun removePanelView() {
        panelView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }
        panelView = null
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
        cropOverlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }
        cropOverlayView = null
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

        private enum class DragMode { NONE, MOVE, RESIZE_BR }
        private var dragMode = DragMode.NONE
        private var lastX = 0f
        private var lastY = 0f
        private val handleRadius = 36f

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()

            canvas.drawRect(0f, 0f, w, boxRect.top.toFloat(), dimPaint)
            canvas.drawRect(0f, boxRect.bottom.toFloat(), w, h, dimPaint)
            canvas.drawRect(0f, boxRect.top.toFloat(), boxRect.left.toFloat(), boxRect.bottom.toFloat(), dimPaint)
            canvas.drawRect(boxRect.right.toFloat(), boxRect.top.toFloat(), w, boxRect.bottom.toFloat(), dimPaint)

            canvas.drawRect(boxRect, borderPaint)
            canvas.drawCircle(boxRect.right.toFloat(), boxRect.bottom.toFloat(), handleRadius, handlePaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    val distBr = kotlin.math.hypot(
                        (event.x - boxRect.right).toDouble(),
                        (event.y - boxRect.bottom).toDouble()
                    )
                    dragMode = when {
                        distBr <= handleRadius * 1.8 -> DragMode.RESIZE_BR
                        boxRect.contains(event.x.toInt(), event.y.toInt()) -> DragMode.MOVE
                        else -> DragMode.NONE
                    }
                    return dragMode != DragMode.NONE
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.x - lastX).toInt()
                    val dy = (event.y - lastY).toInt()
                    lastX = event.x
                    lastY = event.y

                    when (dragMode) {
                        DragMode.MOVE -> {
                            val newLeft = (boxRect.left + dx).coerceIn(0, width - boxRect.width())
                            val newTop = (boxRect.top + dy).coerceIn(0, height - boxRect.height())
                            boxRect.offsetTo(newLeft, newTop)
                        }
                        DragMode.RESIZE_BR -> {
                            val newRight = (boxRect.right + dx).coerceIn(boxRect.left + 120, width)
                            val newBottom = (boxRect.bottom + dy).coerceIn(boxRect.top + 120, height)
                            boxRect.right = newRight
                            boxRect.bottom = newBottom
                        }
                        DragMode.NONE -> Unit
                    }
                    onRectChanged(boxRect)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragMode = DragMode.NONE
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
