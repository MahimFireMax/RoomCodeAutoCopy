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
import android.view.WindowManager
import android.widget.Toast
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
    @Volatile private var lastCopied = ""
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

        display = projection?.createVirtualDisplay(
            "RoomCodeAutoCopy", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, handler
        )
    }

    private fun processImage(image: Image, screenW: Int, screenH: Int) {
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
                    if (stopped) return@addOnSuccessListener
                    val text = result.text.uppercase(Locale.ROOT)
                    // Conservative defaults: numeric code 6–12 digits, or alphanumeric 6–16
                    // characters containing at least one letter and one digit.
                    val tokens = Regex("""(?<![A-Z0-9])[A-Z0-9]{6,16}(?![A-Z0-9])""")
                        .findAll(text).map { it.value }.distinct().toList()
                    val candidate = tokens.firstOrNull { token ->
                        val numericOnly = token.all { it.isDigit() } && token.length in 6..12
                        val mixed = token.any { it.isDigit() } && token.any { it.isLetter() }
                        numericOnly || mixed
                    }
                    val now = System.currentTimeMillis()
                    if (candidate != null) {
                        lastSeenAt = now
                        if (candidate != lastCopied) {
                            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Room Code", candidate))
                            lastCopied = candidate
                            Toast.makeText(applicationContext, "Copied!", Toast.LENGTH_SHORT).show()
                        }
                    } else if (now - lastSeenAt > 2500L) {
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
