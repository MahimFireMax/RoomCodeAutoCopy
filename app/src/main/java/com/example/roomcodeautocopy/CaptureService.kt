package com.example.roomcodeautocopy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.widget.FrameLayout
import android.graphics.drawable.GradientDrawable
import android.graphics.RectF
import android.graphics.Paint
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class CaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "projection_data"
        private const val CHANNEL = "room_code_capture"
        private const val NOTIFICATION_ID = 41
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val processing = AtomicBoolean(false)
    @Volatile private var paused = false
    private var floatingPanel: View? = null
    private var panelManager: WindowManager? = null
    @Volatile private var selectingRegion = false
    @Volatile private var lastCopied = ""
    @Volatile private var latestResult = ""
    @Volatile private var copyMode = "auto"
    @Volatile private var lastSeenAt = 0L
    @Volatile private var stopped = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopped = true
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        if (projection == null && intent != null) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            @Suppress("DEPRECATION")
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                intent.getParcelableExtra(EXTRA_DATA)
            }
            if (data == null || resultCode == 0) {
                stopSelf()
                return START_NOT_STICKY
            }
            try {
                val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projection = mgr.getMediaProjection(resultCode, data)
                projection?.registerCallback(projectionCallback, Handler(mainLooper))
                startCapture()
            } catch (_: SecurityException) {
                stopSelf()
                return START_NOT_STICKY
            } catch (_: Exception) {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        thread = HandlerThread("room-code-ocr").also { it.start() }
        handler = Handler(thread!!.looper)
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader!!.setOnImageAvailableListener({ source ->
            val image = try { source.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
            if (!processing.compareAndSet(false, true)) {
                image.close()
                return@setOnImageAvailableListener
            }
            try {
                processImage(image, width, height)
            } catch (_: Exception) {
                processing.set(false)
                try { image.close() } catch (_: Exception) {}
            }
        }, handler)

        showFloatingPanel()

        display = projection?.createVirtualDisplay(
            "RoomCodeAutoCopy", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, handler
        )
    }

    private fun processImage(image: Image, screenW: Int, screenH: Int) {
        if (stopped || paused || selectingRegion) {
            image.close()
            processing.set(false)
            return
        }
        var padded: Bitmap? = null
        var cropped: Bitmap? = null
        try {
            val prefs = getSharedPreferences("region", Context.MODE_PRIVATE)
            val xPct = prefs.getInt("x", 10)
            val yPct = prefs.getInt("y", 10)
            val wPct = prefs.getInt("w", 80)
            val hPct = prefs.getInt("h", 80)
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenW
            padded = Bitmap.createBitmap(
                screenW + rowPadding / pixelStride, screenH, Bitmap.Config.ARGB_8888
            )
            padded.copyPixelsFromBuffer(plane.buffer)
            val left = (screenW * xPct / 100).coerceIn(0, screenW - 1)
            val top = (screenH * yPct / 100).coerceIn(0, screenH - 1)
            val cropW = (screenW * wPct / 100).coerceAtMost(screenW - left).coerceAtLeast(1)
            val cropH = (screenH * hPct / 100).coerceAtMost(screenH - top).coerceAtLeast(1)
            cropped = Bitmap.createBitmap(padded, left, top, cropW, cropH)
        } finally {
            image.close()
            padded?.recycle()
        }

        val bitmap = cropped ?: run {
            processing.set(false)
            return
        }
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    if (stopped || paused) return@addOnSuccessListener

                    val rawText = result.text.trim()
                    val text = rawText.uppercase(Locale.ROOT)
                    val prefs = getSharedPreferences("region", Context.MODE_PRIVATE)
                    val mode = prefs.getString("mode", "mixed") ?: "mixed"

                    val tokens = Regex("""(?<![A-Z0-9])[A-Z0-9]{1,64}(?![A-Z0-9])""")
                        .findAll(text)
                        .map { it.value }
                        .distinct()
                        .toList()

                    val candidate = when (mode) {
                        "numbers" -> tokens.firstOrNull { token ->
                            token.all { it.isDigit() }
                        }
                        "letters" -> tokens.firstOrNull { token ->
                            token.all { it.isLetter() }
                        }
                        "mixed" -> tokens.firstOrNull { token ->
                            token.any { it.isLetter() } && token.any { it.isDigit() }
                        }
                        "text" -> rawText.takeIf { it.isNotBlank() }
                        else -> tokens.firstOrNull { token ->
                            token.any { it.isLetter() } && token.any { it.isDigit() }
                        }
                    }

                    val now = System.currentTimeMillis()
                    if (candidate != null) {
                        lastSeenAt = now
                        latestResult = candidate
                        val currentCopyMode = prefs.getString("copy_mode", "auto") ?: "auto"
                        copyMode = currentCopyMode
                        if (currentCopyMode == "auto" && candidate != lastCopied) {
                            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Screen Text", candidate))
                            lastCopied = candidate
                            Toast.makeText(applicationContext, "Copied!", Toast.LENGTH_SHORT).show()
                        }
                    } else if (now - lastSeenAt > 2500L) {
                        latestResult = ""
                        lastCopied = ""
                    }
                }
                .addOnFailureListener {
                    // A transient OCR failure should not crash or stop the capture loop.
                }
                .addOnCompleteListener {
                    if (!bitmap.isRecycled) bitmap.recycle()
                    processing.set(false)
                }
        } catch (_: Exception) {
            if (!bitmap.isRecycled) bitmap.recycle()
            processing.set(false)
        }
    }



    private var regionOverlay: View? = null

    private fun closeRegionSelector() {
        selectingRegion = false
        try {
            regionOverlay?.let { panelManager?.removeView(it) }
        } catch (_: Exception) {}
        regionOverlay = null
    }

    private fun showRegionSelector() {
        if (regionOverlay != null || Build.VERSION.SDK_INT < 23) return
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow overlay permission first", Toast.LENGTH_LONG).show()
            return
        }

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        panelManager = wm
        selectingRegion = true

        var startX = 0f
        var startY = 0f
        var endX = 0f
        var endY = 0f
        var hasSelection = false

        val drawingView = object : View(this) {
            private val dimPaint = Paint().apply {
                color = Color.argb(175, 0, 0, 0)
                style = Paint.Style.FILL
            }
            private val clearPaint = Paint().apply {
                color = Color.TRANSPARENT
                style = Paint.Style.FILL
                xfermode = android.graphics.PorterDuffXfermode(
                    android.graphics.PorterDuff.Mode.CLEAR
                )
            }
            private val borderPaint = Paint().apply {
                color = Color.GREEN
                style = Paint.Style.STROKE
                strokeWidth = 4f
                isAntiAlias = true
            }
            private val hintPaint = Paint().apply {
                color = Color.WHITE
                textSize = 42f
                isAntiAlias = true
            }

            init {
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                canvas.drawColor(Color.TRANSPARENT)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
                if (hasSelection) {
                    val rect = RectF(
                        minOf(startX, endX), minOf(startY, endY),
                        maxOf(startX, endX), maxOf(startY, endY)
                    )
                    canvas.drawRect(rect, clearPaint)
                    canvas.drawRect(rect, borderPaint)
                } else {
                    canvas.drawText("Drag to select an area", 30f, 90f, hintPaint)
                }
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.x.coerceIn(0f, width.toFloat())
                        startY = event.y.coerceIn(0f, height.toFloat())
                        endX = startX
                        endY = startY
                        hasSelection = true
                        invalidate()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        endX = event.x.coerceIn(0f, width.toFloat())
                        endY = event.y.coerceIn(0f, height.toFloat())
                        invalidate()
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        endX = event.x.coerceIn(0f, width.toFloat())
                        endY = event.y.coerceIn(0f, height.toFloat())
                        invalidate()
                        return true
                    }
                }
                return true
            }

            fun saveSelection(): Boolean {
                val left = minOf(startX, endX)
                val top = minOf(startY, endY)
                val right = maxOf(startX, endX)
                val bottom = maxOf(startY, endY)
                if (!hasSelection || width <= 0 || height <= 0 ||
                    right - left < 20f || bottom - top < 20f
                ) return false

                val x = (left * 100f / width).toInt().coerceIn(0, 99)
                val y = (top * 100f / height).toInt().coerceIn(0, 99)
                val w = ( (right - left) * 100f / width).toInt().coerceIn(1, 100 - x)
                val h = ( (bottom - top) * 100f / height).toInt().coerceIn(1, 100 - y)

                getSharedPreferences("region", Context.MODE_PRIVATE).edit()
                    .putInt("x", x).putInt("y", y)
                    .putInt("w", w).putInt("h", h).apply()
                return true
            }
        }

        val root = FrameLayout(this)
        root.addView(
            drawingView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(12, 8, 12, 8)
            setBackgroundColor(Color.argb(235, 25, 35, 55))
        }

        val cancel = Button(this).apply {
            text = "Cancel"
            isAllCaps = false
            setOnClickListener { closeRegionSelector() }
        }
        val confirm = Button(this).apply {
            text = "Confirm"
            isAllCaps = false
            setOnClickListener {
                if (drawingView.saveSelection()) {
                    closeRegionSelector()
                    Toast.makeText(this@CaptureService, "Region saved", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@CaptureService, "Drag a larger rectangle first", Toast.LENGTH_SHORT).show()
                }
            }
        }
        actions.addView(cancel)
        actions.addView(confirm)

        val actionParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        )
        root.addView(actions, actionParams)

        @Suppress("DEPRECATION")
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        try {
            wm.addView(root, params)
            regionOverlay = root
        } catch (e: Exception) {
            selectingRegion = false
            regionOverlay = null
            Toast.makeText(this, "Could not open region selector", Toast.LENGTH_LONG).show()
        }
    }

    private fun showFloatingPanel() {
        if (floatingPanel != null || Build.VERSION.SDK_INT < 23) return
        if (!android.provider.Settings.canDrawOverlays(this)) return

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        panelManager = wm

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 10, 10, 10)
            setBackgroundColor(Color.TRANSPARENT)
        }

        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 12, 14, 12)
            setBackgroundColor(Color.argb(245, 18, 28, 48))
            visibility = View.GONE
        }

        val bubble = TextView(this).apply {
            text = "SC"
            textSize = 21f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(25, 110, 220))
                setStroke(2, Color.WHITE)
            }
            contentDescription = "Screen Copier controls"
            val size = (58 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.END
                bottomMargin = 8
            }
        }
        panel.addView(bubble)

        val statusLabel = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(8, 8, 8, 8)
        }
        menu.addView(statusLabel)

        fun updateStatus() {
            statusLabel.text = if (paused) {
                "Scanning Paused"
            } else {
                "Scanning Active"
            }
        }

        fun menuButton(label: String, action: () -> Unit): Button {
            val button = Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 12f
                setOnClickListener { action() }
            }
            menu.addView(button)
            return button
        }

        val pauseButton = menuButton("", {})
        fun updatePauseButton() {
            pauseButton.text = if (paused) {
                "Resume Scanning"
            } else {
                "Pause Scanning"
            }
            updateStatus()
        }
        pauseButton.setOnClickListener {
            paused = !paused
            updatePauseButton()
            Toast.makeText(
                this,
                if (paused) "Scanning paused" else "Scanning resumed",
                Toast.LENGTH_SHORT
            ).show()
        }
        updatePauseButton()

        val prefs = getSharedPreferences("region", Context.MODE_PRIVATE)
        val modes = listOf("numbers", "letters", "mixed", "text")
        fun modeLabel(mode: String): String = when (mode) {
            "numbers" -> "Numbers"
            "letters" -> "Letters"
            "text" -> "Full Text"
            else -> "Mixed Code"
        }

        val modeButton = menuButton("", {})
        fun updateModeButton() {
            val current = prefs.getString("mode", "mixed") ?: "mixed"
            modeButton.text = "Mode: ${modeLabel(current)}"
        }
        modeButton.setOnClickListener {
            val current = prefs.getString("mode", "mixed") ?: "mixed"
            val next = (modes.indexOf(current).coerceAtLeast(0) + 1) % modes.size
            prefs.edit().putString("mode", modes[next]).apply()
            updateModeButton()
            Toast.makeText(this, "Mode: ${modeLabel(modes[next])}", Toast.LENGTH_SHORT).show()
        }
        updateModeButton()

        val copyModeButton = menuButton("", {})
        fun updateCopyModeButton() {
            val copyMode = prefs.getString("copy_mode", "auto") ?: "auto"
            copyModeButton.text = if (copyMode == "auto") {
                "Copy Mode: Auto Copy"
            } else {
                "Copy Mode: Manual Copy"
            }
        }
        copyModeButton.setOnClickListener {
            val current = prefs.getString("copy_mode", "auto") ?: "auto"
            val next = if (current == "auto") "manual" else "auto"
            prefs.edit().putString("copy_mode", next).apply()
            copyMode = next
            updateCopyModeButton()
            Toast.makeText(this, "Copy mode: ${if (next == "auto") "Auto Copy" else "Manual Copy"}", Toast.LENGTH_SHORT).show()
        }
        updateCopyModeButton()

        menuButton("Copy Result") {
            val result = latestResult
            if (result.isBlank()) {
                Toast.makeText(this, "No text detected yet", Toast.LENGTH_SHORT).show()
            } else {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Screen Text", result))
                lastCopied = result
                Toast.makeText(this, "Result copied", Toast.LENGTH_SHORT).show()
            }
        }

        menuButton("Region") {
            menu.visibility = View.GONE
            showRegionSelector()
        }

        menuButton("Hide") {
            menu.visibility = View.GONE
        }

        panel.addView(menu)

        bubble.setOnClickListener {
            menu.visibility = if (menu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        @Suppress("DEPRECATION")
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 160
        }

        bubble.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var moved = false

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - touchX
                        val dy = event.rawY - touchY
                        if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                        if (moved) {
                            params.x = startX + dx.toInt()
                            params.y = startY + dy.toInt()
                            try { wm.updateViewLayout(panel, params) } catch (_: Exception) {}
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) view.performClick()
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> return true
                }
                return false
            }
        })

        try {
            wm.addView(panel, params)
            floatingPanel = panel
        } catch (_: Exception) {
            floatingPanel = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL, "Room Code screen scanning", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return builder.setContentTitle("Room Code Auto Copy")
            .setContentText("Screen scanning is active. Tap Stop in the app to end.")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopped = true
        try {
            floatingPanel?.let { panelManager?.removeView(it) }
        } catch (_: Exception) {}
        floatingPanel = null
        panelManager = null
        try { display?.release() } catch (_: Exception) {}
        try { reader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { thread?.quitSafely() } catch (_: Exception) {}
        try { recognizer.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}
