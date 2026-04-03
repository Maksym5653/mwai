package com.mwai.overlay

import android.app.*
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Base64
import android.view.*
import android.view.animation.*
import android.widget.*
import androidx.core.app.NotificationCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class OverlayService : Service() {

    companion object {
        var isRunning = false
        const val CHANNEL_ID = "mwai_channel"
        const val GEMINI_MODEL = "gemini-2.0-flash"
        const val GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent"
    }

    private lateinit var windowManager: WindowManager
    private var fabView: View? = null
    private var panelView: View? = null
    private var ghostView: View? = null
    private var fabParams: WindowManager.LayoutParams? = null

    private var isPanelVisible = false
    private var lastTapTime = 0L
    private val DOUBLE_TAP_MS = 400L

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())

    private var screenWidth = 0
    private var screenHeight = 0

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        createNotificationChannel()
        startForeground(1, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }

        val resultCode = intent?.getIntExtra("result_code", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data")
        if (resultCode != -1 && data != null) {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, data)
            setupImageReader()
        }
        showFab()
        return START_NOT_STICKY
    }

    // ─── FAB ──────────────────────────────────────────────────────

    private fun showFab() {
        if (fabView != null) return
        fabView = LayoutInflater.from(this).inflate(R.layout.view_fab, null)

        fabParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24; y = screenHeight / 3
        }

        var initX = 0; var initY = 0
        var touchX = 0f; var touchY = 0f
        var isDragging = false

        fabView!!.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = fabParams!!.x; initY = fabParams!!.y
                    touchX = event.rawX; touchY = event.rawY
                    isDragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(event.rawX - touchX) > 8 || Math.abs(event.rawY - touchY) > 8) {
                        isDragging = true
                        fabParams!!.x = (initX - (event.rawX - touchX)).toInt()
                        fabParams!!.y = (initY + (event.rawY - touchY)).toInt()
                        try { windowManager.updateViewLayout(fabView, fabParams) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < DOUBLE_TAP_MS) {
                            hideFab(showGhost = true)
                        } else {
                            handler.postDelayed({
                                if (System.currentTimeMillis() - lastTapTime >= DOUBLE_TAP_MS) {
                                    if (isPanelVisible) hidePanel() else captureAndAnalyze()
                                }
                            }, DOUBLE_TAP_MS)
                        }
                        lastTapTime = now
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(fabView, fabParams)
        fabView!!.alpha = 0f
        fabView!!.scaleX = 0f; fabView!!.scaleY = 0f
        fabView!!.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(350).setInterpolator(OvershootInterpolator(1.5f)).start()
    }

    private fun hideFab(showGhost: Boolean) {
        fabView?.animate()?.alpha(0f)?.scaleX(0f)?.scaleY(0f)?.setDuration(200)
            ?.withEndAction {
                try { fabView?.let { windowManager.removeView(it) } } catch (_: Exception) {}
                fabView = null
                if (showGhost) showGhostZone()
            }?.start()
    }

    private fun showGhostZone() {
        try { ghostView?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        ghostView = View(this)
        val gp = WindowManager.LayoutParams(
            80, 80,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = fabParams?.x ?: 24; y = fabParams?.y ?: (screenHeight / 3)
        }
        var ghostLastTap = 0L
        ghostView!!.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - ghostLastTap < DOUBLE_TAP_MS) {
                try { ghostView?.let { windowManager.removeView(it) } } catch (_: Exception) {}
                ghostView = null
                showFab()
            }
            ghostLastTap = now
        }
        windowManager.addView(ghostView, gp)
    }

    // ─── SCREEN CAPTURE ───────────────────────────────────────────

    private fun setupImageReader() {
        try { imageReader?.close() } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "MwAI_Cap", screenWidth, screenHeight,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )
    }

    private fun captureScreen(): Bitmap? {
        return try {
            val image = imageReader?.acquireLatestImage() ?: return null
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth
            val bmp = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight, Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(buffer)
            image.close()
            Bitmap.createBitmap(bmp, 0, 0, screenWidth, screenHeight)
        } catch (e: Exception) { null }
    }

    private fun bitmapToBase64(bmp: Bitmap): String {
        val maxW = 1080
        val scaled = if (bmp.width > maxW)
            Bitmap.createScaledBitmap(bmp, maxW, (maxW.toFloat() * bmp.height / bmp.width).toInt(), true)
        else bmp
        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 82, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    // ─── GEMINI CALL ──────────────────────────────────────────────

    private fun captureAndAnalyze() {
        showPanel(loading = true)
        Thread {
            Thread.sleep(300)
            val bmp = captureScreen()
            if (bmp == null) {
                handler.post { updatePanel("❌ Не вдалося зробити скріншот.\nПеревір дозволи MediaProjection.") }
                return@Thread
            }
            callGeminiApi(bitmapToBase64(bmp))
        }.start()
    }

    private fun callGeminiApi(imageBase64: String) {
        val apiKey = getSharedPreferences("mwai", MODE_PRIVATE)
            .getString("api_key", "") ?: ""

        val prompt = """Ти — MW AI, вбудований AI-асистент для Android.
Уважно подивись на цей скріншот екрана і допоможи користувачу:

• Якщо є питання / тест / завдання → дай точні відповіді
• Якщо це гра → підкажи наступний крок або стратегію  
• Якщо є форма / анкета → допоможи заповнити поля
• Якщо це навчальний матеріал → поясни коротко і зрозуміло
• В інших випадках → опиши що бачиш і запропонуй допомогу

Відповідай ТІЛЬКИ українською мовою.
Будь конкретним, структурованим, використовуй емодзі де доречно.
Максимум 300 слів."""

        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("inline_data", JSONObject().apply {
                            put("mime_type", "image/jpeg")
                            put("data", imageBase64)
                        })
                    })
                    put(JSONObject().apply { put("text", prompt) })
                })
            }))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.3)
                put("maxOutputTokens", 1024)
                put("topP", 0.8)
            })
            put("safetySettings", JSONArray().apply {
                listOf("HARM_CATEGORY_HARASSMENT","HARM_CATEGORY_HATE_SPEECH",
                    "HARM_CATEGORY_SEXUALLY_EXPLICIT","HARM_CATEGORY_DANGEROUS_CONTENT").forEach {
                    put(JSONObject().apply {
                        put("category", it); put("threshold", "BLOCK_NONE")
                    })
                }
            })
        }

        val url = "$GEMINI_URL?key=$apiKey"
        val req = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                handler.post { updatePanel("❌ Помилка мережі:\n${e.message}") }
            }
            override fun onResponse(call: Call, response: Response) {
                val rb = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    try {
                        val txt = JSONObject(rb)
                            .getJSONArray("candidates")
                            .getJSONObject(0)
                            .getJSONObject("content")
                            .getJSONArray("parts")
                            .getJSONObject(0)
                            .getString("text")
                        handler.post { updatePanel(txt) }
                    } catch (e: Exception) {
                        handler.post { updatePanel("❌ Помилка парсингу:\n${e.message}\n\n$rb") }
                    }
                } else {
                    val err = try {
                        JSONObject(rb).getJSONObject("error").getString("message")
                    } catch (_: Exception) { "HTTP ${response.code}\n$rb" }
                    handler.post { updatePanel("❌ Gemini помилка:\n$err") }
                }
            }
        })
    }

    // ─── PANEL ────────────────────────────────────────────────────

    private fun showPanel(loading: Boolean) {
        if (panelView != null) { if (loading) updatePanel(null); return }
        panelView = LayoutInflater.from(this).inflate(R.layout.view_panel, null)

        val params = WindowManager.LayoutParams(
            (screenWidth * 0.92).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = 72 }

        val tvContent = panelView!!.findViewById<TextView>(R.id.tv_content)
        val progressBar = panelView!!.findViewById<ProgressBar>(R.id.progress_bar)
        val btnClose = panelView!!.findViewById<ImageButton>(R.id.btn_close)

        if (loading) { progressBar.visibility = View.VISIBLE; tvContent.text = "✨ Аналізую екран..." }

        var closeTap = 0L
        btnClose.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - closeTap < DOUBLE_TAP_MS) { hidePanel(); hideFab(showGhost = true) }
            else hidePanel()
            closeTap = now
        }

        var initY = 0; var tY = 0f
        panelView!!.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { initY = params.y; tY = e.rawY; false }
                MotionEvent.ACTION_MOVE -> {
                    params.y = (initY - (e.rawY - tY)).toInt().coerceAtLeast(0)
                    try { windowManager.updateViewLayout(panelView, params) } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }

        windowManager.addView(panelView, params)
        panelView!!.translationY = 400f; panelView!!.alpha = 0f
        panelView!!.animate().translationY(0f).alpha(1f).setDuration(320)
            .setInterpolator(DecelerateInterpolator(1.5f)).start()
        isPanelVisible = true
    }

    private fun updatePanel(text: String?) {
        if (panelView == null) { showPanel(text == null); return }
        val tv = panelView!!.findViewById<TextView>(R.id.tv_content)
        val pb = panelView!!.findViewById<ProgressBar>(R.id.progress_bar)
        if (text == null) { pb.visibility = View.VISIBLE; tv.text = "✨ Аналізую екран..." }
        else { pb.visibility = View.GONE; tv.text = text }
    }

    private fun hidePanel() {
        panelView?.animate()?.translationY(400f)?.alpha(0f)?.setDuration(260)
            ?.withEndAction {
                try { panelView?.let { windowManager.removeView(it) } } catch (_: Exception) {}
                panelView = null; isPanelVisible = false
            }?.start()
    }

    // ─── NOTIFICATION ─────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "MW AI", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).apply { action = "STOP" },
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MW AI активний 🤖")
            .setContentText("Натисни кнопку MW AI на екрані для аналізу")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_delete, "Зупинити", stop)
            .setOngoing(true).build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        listOf(fabView, panelView, ghostView).forEach {
            try { it?.let { v -> windowManager.removeView(v) } } catch (_: Exception) {}
        }
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?) = null
}
