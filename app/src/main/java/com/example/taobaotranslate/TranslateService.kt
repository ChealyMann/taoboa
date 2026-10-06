package com.example.taobaotranslate

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min

/**
 * Foreground service that:
 *  1. captures the screen (MediaProjection),
 *  2. finds Chinese text with on-device ML Kit OCR,
 *  3. translates it on-device,
 *  4. draws the translation over the original text in a touch-transparent overlay.
 *
 * It only re-runs OCR when the screen content actually changes (scroll, new page).
 */
class TranslateService : Service() {

    companion object {
        const val ACTION_START = "com.example.taobaotranslate.START"
        const val ACTION_STOP = "com.example.taobaotranslate.STOP"
        const val ACTION_TOGGLE_PAUSE = "com.example.taobaotranslate.TOGGLE_PAUSE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_LANG = "lang"

        private const val TAG = "TaobaoTranslate"
        private const val CHANNEL_ID = "live_translate"
        private const val NOTIF_ID = 42

        private const val MAX_CAPTURE_WIDTH = 1080
        private const val POLL_MS = 600L          // how often we look for screen changes
        private const val CLEAN_FRAME_MS = 220L   // wait for the compositor after hiding our overlay
        private const val SETTLE_MS = 350L        // wait for our overlay to appear before re-baselining

        private const val SIG_W = 48              // change-detection fingerprint size
        private const val SIG_H = 96

        private const val FLAT_RING_FRACTION = 0.7f // share of pixels around a block that must match its background

        private val CJK = Regex("[\\u3400-\\u4dbf\\u4e00-\\u9fff]")

        @Volatile
        var running = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var windowManager: WindowManager? = null
    private var overlay: OverlayView? = null
    private var translator: Translator? = null
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val cache = LruCache<String, String>(500)

    private var captureScale = 1f
    @Volatile private var paused = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // User ended the capture from the system UI.
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_TOGGLE_PAUSE -> togglePause()
            ACTION_START -> if (!running) start(intent) else stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- startup

    private fun start(intent: Intent) {
        createChannel()
        // Android 14+: must be a foreground service of type mediaProjection BEFORE
        // we obtain the MediaProjection.
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }
        val targetLang = intent.getStringExtra(EXTRA_LANG) ?: TranslateLanguage.ENGLISH

        if (data == null || resultCode != Activity.RESULT_OK) {
            fail("Screen capture permission was not granted.")
            return
        }

        try {
            running = true

            translator = Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.CHINESE)
                    .setTargetLanguage(targetLang)
                    .build()
            )

            val wm = getSystemService(WindowManager::class.java)
            windowManager = wm
            val bounds = wm.maximumWindowMetrics.bounds
            val screenW = bounds.width()
            val screenH = bounds.height()
            captureScale = min(1f, MAX_CAPTURE_WIDTH.toFloat() / screenW)
            val capW = (screenW * captureScale).toInt()
            val capH = (screenH * captureScale).toInt()

            val mpm = getSystemService(MediaProjectionManager::class.java)
            val mp = mpm.getMediaProjection(resultCode, data)
            projection = mp
            // Android 14+: the callback must be registered before creating the display.
            mp.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

            val reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
            imageReader = reader
            virtualDisplay = mp.createVirtualDisplay(
                "taobao-translate",
                capW, capH, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null
            )

            val view = OverlayView(this)
            overlay = view
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            wm.addView(view, params)

            loopJob = scope.launch { runLoop() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start", e)
            fail("Could not start: ${e.message}")
        }
    }

    // ------------------------------------------------------------- main loop

    private suspend fun runLoop() {
        val tr = translator ?: return
        try {
            tr.downloadModelIfNeeded().await()
        } catch (e: Exception) {
            fail("Language pack missing. Open the app and tap Start once while online.")
            return
        }

        var lastSig: IntArray? = null

        while (currentCoroutineContext().isActive) {
            delay(POLL_MS)
            if (paused) {
                lastSig = null
                continue
            }

            // ImageReader only delivers a frame when the screen changed, so a null
            // here means "nothing moved".
            val first = grabFrame() ?: continue
            val firstSig = signature(first)
            first.recycle()
            if (!differs(lastSig, firstSig)) continue

            // Something changed (scroll, new page...). Hide our own overlay so OCR sees
            // the original Chinese instead of our translations.
            val ov = overlay ?: return
            withContext(Dispatchers.Main) { ov.visibility = View.INVISIBLE }
            delay(CLEAN_FRAME_MS)
            val clean = grabFrame()
            if (clean == null) {
                withContext(Dispatchers.Main) { ov.visibility = View.VISIBLE }
                lastSig = firstSig
                continue
            }
            val cleanSig = signature(clean)

            val boxes = try {
                translateFrame(clean, tr)
            } catch (e: Exception) {
                Log.w(TAG, "OCR/translate failed", e)
                emptyList()
            }
            clean.recycle()

            withContext(Dispatchers.Main) {
                ov.setBoxes(boxes)
                ov.visibility = View.VISIBLE
            }

            // Re-baseline against what the screen looks like WITH our overlay on it.
            delay(SETTLE_MS)
            val settled = grabFrame()
            lastSig = if (settled != null) signature(settled).also { settled.recycle() } else cleanSig
        }
    }

    private suspend fun translateFrame(bmp: Bitmap, tr: Translator): List<OverlayView.Box> = coroutineScope {
        val result = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
        val inv = 1f / captureScale

        result.textBlocks
            .filter { CJK.containsMatchIn(it.text) && it.boundingBox != null }
            .map { block ->
                async {
                    // Chinese has no spaces between words, so join wrapped lines directly.
                    val src = block.lines.joinToString("") { it.text }.trim()
                    if (src.isEmpty()) return@async null
                    val r = block.boundingBox!!
                    val (bg, fg) = sampleColors(bmp, r) ?: return@async null
                    val out = cache.get(src) ?: try {
                        tr.translate(src).await().also { cache.put(src, it) }
                    } catch (e: Exception) {
                        return@async null
                    }
                    val rect = RectF(r.left * inv - 4f, r.top * inv - 2f, r.right * inv + 4f, r.bottom * inv + 2f)
                    val lineHeights = block.lines.mapNotNull { it.boundingBox?.height() }.sorted()
                    val lineHeight = (lineHeights.getOrNull(lineHeights.size / 2) ?: r.height()) * inv
                    OverlayView.Box(rect, out, bg, fg, lineHeight)
                }
            }
            .awaitAll()
            .filterNotNull()
    }

    // ----------------------------------------------------------- image helpers

    private fun grabFrame(): Bitmap? {
        val image = imageReader?.acquireLatestImage() ?: return null
        image.use { img ->
            val plane = img.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * img.width
            val full = Bitmap.createBitmap(
                img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888
            )
            full.copyPixelsFromBuffer(plane.buffer)
            if (rowPadding == 0) return full
            val cropped = Bitmap.createBitmap(full, 0, 0, img.width, img.height)
            full.recycle()
            return cropped
        }
    }

    /** Tiny grayscale fingerprint of a frame, used to detect screen changes cheaply. */
    private fun signature(b: Bitmap): IntArray {
        val small = Bitmap.createScaledBitmap(b, SIG_W, SIG_H, true)
        val px = IntArray(SIG_W * SIG_H)
        small.getPixels(px, 0, SIG_W, 0, 0, SIG_W, SIG_H)
        if (small !== b) small.recycle()
        return IntArray(px.size) { i ->
            val c = px[i]
            (Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10
        }
    }

    private fun differs(a: IntArray?, b: IntArray): Boolean {
        if (a == null) return true
        var changed = 0
        for (i in a.indices) if (abs(a[i] - b[i]) > 24) changed++
        return changed > a.size * 0.02
    }

    /**
     * Returns (background, text) colours for a text block, so the overlay blends
     * into the page: red prices stay red, grey captions stay grey. Returns null
     * when the block sits on an image rather than a flat background.
     */
    private fun sampleColors(bmp: Bitmap, r: Rect): Pair<Int, Int>? {
        val left = r.left.coerceIn(0, bmp.width - 1)
        val top = r.top.coerceIn(0, bmp.height - 1)
        val right = r.right.coerceIn(left + 1, bmp.width)
        val bottom = r.bottom.coerceIn(top + 1, bmp.height)

        // Background: most common colour on a ring just outside the block.
        val outL = (left - 3).coerceAtLeast(0)
        val outT = (top - 3).coerceAtLeast(0)
        val outR = (right + 2).coerceAtMost(bmp.width - 1)
        val outB = (bottom + 2).coerceAtMost(bmp.height - 1)
        val ring = IntArray(2 * ((outR - outL) / 4 + 1) + 2 * ((outB - outT) / 4 + 1))
        var n = 0
        for (x in outL..outR step 4) { ring[n++] = bmp.getPixel(x, outT); ring[n++] = bmp.getPixel(x, outB) }
        for (y in outT..outB step 4) { ring[n++] = bmp.getPixel(outL, y); ring[n++] = bmp.getPixel(outR, y) }
        val bg = dominant(ring, n) ?: Color.WHITE

        // Text printed on a photo or banner has a busy surround. Leave it alone:
        // a solid box over a picture looks worse than the untranslated slogan.
        var flat = 0
        for (i in 0 until n) if (colorDistance(ring[i], bg) <= 60) flat++
        if (flat < n * FLAT_RING_FRACTION) return null

        // Text: most common colour inside the block that clearly differs from the background.
        val w = right - left
        val h = bottom - top
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, left, top, w, h)
        var inkCount = 0
        for (c in px) if (colorDistance(c, bg) > 120) px[inkCount++] = c
        val ink = dominant(px, inkCount)
        val fg = if (ink != null && abs(luma(ink) - luma(bg)) >= 100) ink
        else if (luma(bg) > 140) Color.BLACK else Color.WHITE
        return bg to fg
    }

    /** Most common colour among the first [n] entries (bucketed, then averaged). */
    private fun dominant(colors: IntArray, n: Int): Int? {
        if (n == 0) return null
        val counts = IntArray(4096)
        val rs = IntArray(4096)
        val gs = IntArray(4096)
        val bs = IntArray(4096)
        for (i in 0 until n) {
            val c = colors[i]
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            val k = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
            counts[k]++; rs[k] += r; gs[k] += g; bs[k] += b
        }
        var best = 0
        for (k in counts.indices) if (counts[k] > counts[best]) best = k
        val c = counts[best]
        return Color.rgb(rs[best] / c, gs[best] / c, bs[best] / c)
    }

    private fun colorDistance(a: Int, b: Int): Int =
        abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))

    private fun luma(c: Int): Int = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000

    // -------------------------------------------------------- pause / notify

    private fun togglePause() {
        paused = !paused
        if (paused) overlay?.setBoxes(emptyList())
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Live translation", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(): Notification {
        fun action(label: String, act: String, code: Int, icon: Int): Notification.Action {
            val pi = PendingIntent.getService(
                this, code,
                Intent(this, TranslateService::class.java).setAction(act),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi).build()
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Live translation " + if (paused) "paused" else "on")
            .setContentText("Translating Chinese text on screen")
            .setOngoing(true)
            .addAction(
                if (paused) action("Resume", ACTION_TOGGLE_PAUSE, 1, android.R.drawable.ic_media_play)
                else action("Pause", ACTION_TOGGLE_PAUSE, 1, android.R.drawable.ic_media_pause)
            )
            .addAction(action("Stop", ACTION_STOP, 2, android.R.drawable.ic_menu_close_clear_cancel))
            .build()
    }

    private fun fail(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
        stopSelf()
    }

    // --------------------------------------------------------------- teardown

    override fun onDestroy() {
        running = false
        scope.cancel()
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching {
            projection?.unregisterCallback(projectionCallback)
            projection?.stop()
        }
        runCatching { overlay?.let { windowManager?.removeView(it) } }
        runCatching { recognizer.close() }
        runCatching { translator?.close() }
        super.onDestroy()
    }
}
