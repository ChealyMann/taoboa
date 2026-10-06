package com.example.taobaotranslate

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max

/**
 * Accessibility service that translates Taobao in place:
 *  1. reads Taobao's on-screen text and positions from the accessibility tree
 *     (exact, no OCR),
 *  2. translates it on-device,
 *  3. measures each new text element's colours once from a screenshot,
 *  4. draws the translations over the Chinese, moving them as the page scrolls.
 */
class TranslateAccessibilityService : AccessibilityService() {

    companion object {
        const val TAOBAO_PACKAGE = "com.taobao.taobao"
        const val PREFS = "settings"
        const val PREF_LANG = "target_lang"
        const val ACTION_TURN_OFF = "com.example.taobaotranslate.TURN_OFF"
        private const val ACTION_TOGGLE_PAUSE = "com.example.taobaotranslate.TOGGLE_PAUSE"

        private const val TAG = "TaobaoTranslate"
        private const val CHANNEL_ID = "live_translate"
        private const val NOTIF_ID = 42

        private const val REFRESH_THROTTLE_MS = 200L    // most often we re-read the screen while it changes
        private const val SCREENSHOT_INTERVAL_MS = 350L // Android allows about 3 screenshots a second
        private const val SCROLL_QUIET_MS = 150L        // no colour measuring while the page is moving
        private const val FAST_SCROLL_PX = 150          // per scroll event; faster than this, hide instead of follow
        private const val FAST_SCROLL_HIDE_MS = 250L
        private const val MAX_MEASURE_TRIES = 3
        private const val UNDEFINED_DELTA = -1

        private val CJK = Regex("[\\u3400-\\u4dbf\\u4e00-\\u9fff]")

        @Volatile
        var running = false
    }

    /** One piece of Chinese text on screen. [clip] is the scrolling list it belongs to. */
    private class Item(val text: String, val bounds: Rect, val clip: Rect)

    /** What Taobao is showing. [window] is null when Taobao isn't in front. */
    private class Snapshot(val window: Rect?, val windowId: Int, val items: List<Item>, val occluders: List<Rect>)

    /** A screenshot; [left]/[top] is where its (0, 0) is on screen. */
    private class Capture(val bitmap: Bitmap?, val left: Int, val top: Int, val secure: Boolean)

    private val emptySnapshot = Snapshot(null, -1, emptyList(), emptyList())

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // Reading the tree and measuring colours happen off the main thread, one at a time.
    private val workerExecutor = Executors.newSingleThreadExecutor { Thread(it, "translate-worker").apply { isDaemon = true } }
    private val worker = workerExecutor.asCoroutineDispatcher()

    private var windowManager: WindowManager? = null
    private var overlay: OverlayView? = null
    private var prefs: SharedPreferences? = null
    private var translator: Translator? = null

    private val translations = LruCache<String, String>(1000)
    private val pendingTranslations = HashSet<String>()
    private val styles = LruCache<String, TextStyle>(600)
    private val measureFailures = HashMap<String, Int>()

    private var paused = false

    private var refreshScheduled = false
    private var refreshing = false
    private var dirty = false
    private var lastRefreshAt = 0L
    private var shotInFlight = false
    private var lastShotAt = 0L
    private var scrollCount = 0
    private var lastScrollAt = 0L
    private var fastScrollArea: Rect? = null
    private var fastScrollUntil = 0L

    private val refreshRunnable = Runnable { refresh() }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PREF_LANG) setUpTranslator()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_TOGGLE_PAUSE -> togglePause()
                ACTION_TURN_OFF -> disableSelf()
            }
        }
    }

    // ---------------------------------------------------------------- startup

    override fun onServiceConnected() {
        running = true
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        prefs = p
        p.registerOnSharedPreferenceChangeListener(prefListener)
        setUpTranslator()

        val wm = getSystemService(WindowManager::class.java)
        windowManager = wm
        val view = OverlayView(this)
        overlay = view
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            // Accessibility overlays always let taps through to the app below.
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
        }
        wm.addView(view, params)

        ContextCompat.registerReceiver(
            this, receiver,
            IntentFilter().apply { addAction(ACTION_TOGGLE_PAUSE); addAction(ACTION_TURN_OFF) },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        showNotification()
        requestRefresh()
    }

    private fun setUpTranslator() {
        val lang = prefs?.getString(PREF_LANG, TranslateLanguage.ENGLISH) ?: TranslateLanguage.ENGLISH
        translator?.close()
        translations.evictAll()
        pendingTranslations.clear()
        val tr = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.CHINESE)
                .setTargetLanguage(lang)
                .build()
        )
        translator = tr
        tr.downloadModelIfNeeded()
            .addOnSuccessListener { requestRefresh() }
            .addOnFailureListener {
                toast("Language pack missing. Open Taobao Live Translate and tap Start while online.")
            }
    }

    // ----------------------------------------------------------------- events

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (paused || overlay == null) return
        val fromTaobao = event.packageName?.toString() == TAOBAO_PACKAGE
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> if (fromTaobao) onScrolled(event) else return
            // App switches, pop-ups, keyboard, notification shade...
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED -> Unit
            else -> if (!fromTaobao) return
        }
        requestRefresh()
    }

    /** Moves the translations with the page right away, before the screen is re-read. */
    private fun onScrolled(event: AccessibilityEvent) {
        scrollCount++
        lastScrollAt = SystemClock.uptimeMillis()
        val ov = overlay ?: return
        val source = event.source ?: return
        val area = Rect()
        source.getBoundsInScreen(area)
        if (area.isEmpty) return

        val dx = event.scrollDeltaX
        val dy = event.scrollDeltaY
        val unknown = dx == UNDEFINED_DELTA && dy == UNDEFINED_DELTA
        if (unknown || abs(dx) > FAST_SCROLL_PX || abs(dy) > FAST_SCROLL_PX) {
            // Too fast to follow neatly: hide until it slows down.
            fastScrollArea = area
            fastScrollUntil = lastScrollAt + FAST_SCROLL_HIDE_MS
            ov.hide(area)
        } else {
            ov.shift(area, -dx.toFloat(), -dy.toFloat())
        }
    }

    override fun onInterrupt() = Unit

    // ---------------------------------------------------------------- refresh

    private fun requestRefresh() {
        if (paused || overlay == null) return
        if (refreshing) {
            dirty = true
            return
        }
        if (refreshScheduled) return
        refreshScheduled = true
        val wait = (lastRefreshAt + REFRESH_THROTTLE_MS - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        handler.postDelayed(refreshRunnable, wait)
    }

    private fun refresh() {
        refreshScheduled = false
        if (paused) return
        refreshing = true
        dirty = false
        lastRefreshAt = SystemClock.uptimeMillis()
        val scrollsAtStart = scrollCount
        scope.launch {
            val snapshot = try {
                withContext(worker) { readScreen() }
            } catch (e: Exception) {
                Log.w(TAG, "Reading the screen failed", e)
                null
            }
            refreshing = false
            if (snapshot != null && !paused) show(snapshot, scrollsAtStart)
            if (dirty) requestRefresh()
        }
    }

    /** Finds Taobao's window and collects its Chinese text. Runs on the worker thread. */
    private fun readScreen(): Snapshot {
        val metrics = resources.displayMetrics
        val screenArea = metrics.widthPixels.toLong() * metrics.heightPixels
        val occluders = ArrayList<Rect>()
        var target: AccessibilityWindowInfo? = null
        var root: AccessibilityNodeInfo? = null
        for (w in windows.sortedByDescending { it.layer }) {
            if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
            val bounds = Rect().also { w.getBoundsInScreen(it) }
            if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                val r = w.root
                if (r?.packageName?.toString() == TAOBAO_PACKAGE) {
                    target = w
                    root = r
                    break
                }
                if (area(bounds) > screenArea / 2) break // another app is in front
            }
            occluders += bounds
        }
        val taobao = target ?: return emptySnapshot
        val taobaoRoot = root ?: return emptySnapshot

        val window = Rect()
        taobao.getBoundsInScreen(window)
        // The notification shade or a large system dialog is covering Taobao.
        if (occluders.any { area(it) > area(window) / 2 }) return emptySnapshot

        val items = ArrayList<Item>()
        collect(taobaoRoot, window, items, 0)
        return Snapshot(window, taobao.id, dedupe(items), occluders)
    }

    private fun collect(node: AccessibilityNodeInfo, clip: Rect, out: MutableList<Item>, depth: Int) {
        if (depth > 80 || !node.isVisibleToUser) return
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.intersect(clip)) return

        val text = when {
            node.isEditable -> null // the search box: leave what the user types alone
            !node.text.isNullOrBlank() -> node.text
            node.childCount == 0 -> node.contentDescription
            else -> null
        }?.toString()?.trim()
        if (text != null && CJK.containsMatchIn(text) && bounds.width() >= 4 && bounds.height() >= 4) {
            out += Item(text, bounds, clip)
        }

        val childClip = if (node.isScrollable) bounds else clip
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, childClip, out, depth + 1)
        }
    }

    /** Drops containers that repeat text their children already show. */
    private fun dedupe(items: List<Item>): List<Item> {
        val unique = items.distinctBy { it.text + "|" + it.bounds.flattenToString() }
        return unique.filter { a ->
            unique.none { b -> b !== a && a.bounds.contains(b.bounds) && a.text.contains(b.text) }
        }
    }

    private fun show(s: Snapshot, scrollsAtStart: Int) {
        val ov = overlay ?: return
        if (s.window == null) {
            ov.setBoxes(emptyList())
            return
        }
        val now = SystemClock.uptimeMillis()
        val hideArea = if (now < fastScrollUntil) fastScrollArea else null

        val boxes = ArrayList<OverlayView.Box>()
        val unmeasured = ArrayList<Item>()
        for (item in s.items) {
            val style = styles.get(styleKey(item))
            if (style === ColorSampler.ON_IMAGE) continue
            val out = translations.get(item.text)
            if (out == null) translate(item.text)
            if (style == null) {
                unmeasured += item
                continue
            }
            if (out == null) continue
            if (hideArea != null && hideArea.contains(item.bounds.centerX(), item.bounds.centerY())) continue
            boxes += boxFor(item, style, out)
        }
        ov.setBoxes(boxes, s.occluders)

        if (hideArea != null) handler.postDelayed({ requestRefresh() }, fastScrollUntil - now)
        if (unmeasured.isNotEmpty()) measure(s, unmeasured, scrollsAtStart)
    }

    private fun boxFor(item: Item, st: TextStyle, text: String): OverlayView.Box {
        val b = item.bounds
        val x = b.left.toFloat()
        val y = b.top.toFloat()
        // Cover the actual ink plus a margin, but never spill outside the element.
        val rect = RectF(
            max(x, x + st.ink.left - OverlayView.PAD_X),
            max(y, y + st.ink.top - OverlayView.PAD_Y),
            minOf(b.right.toFloat(), x + st.ink.right + OverlayView.PAD_X),
            minOf(b.bottom.toFloat(), y + st.ink.bottom + OverlayView.PAD_Y),
        )
        return OverlayView.Box(
            rect, text, st.fg, st.lineHeight, st.bg, st.bgEnd, x + st.bgStartX, x + st.bgEndX,
            maxRight = b.right.toFloat(), maxBottom = b.bottom.toFloat(), clip = item.clip,
        )
    }

    private fun styleKey(item: Item) = "${item.text}|${item.bounds.width()}x${item.bounds.height()}"

    private fun translate(text: String) {
        val tr = translator ?: return
        if (!pendingTranslations.add(text)) return
        tr.translate(text)
            .addOnSuccessListener { out ->
                if (tr === translator) translations.put(text, out)
                requestRefresh()
            }
            .addOnCompleteListener { pendingTranslations.remove(text) }
    }

    // ------------------------------------------------------- colour measuring

    /** Takes one screenshot and measures the colours of text seen for the first time. */
    private fun measure(s: Snapshot, items: List<Item>, scrollsAtStart: Int) {
        if (shotInFlight) return // its completion triggers another refresh
        val now = SystemClock.uptimeMillis()
        val wait = maxOf(lastShotAt + SCREENSHOT_INTERVAL_MS, lastScrollAt + SCROLL_QUIET_MS) - now
        if (wait > 0 || scrollCount != scrollsAtStart) {
            handler.postDelayed({ requestRefresh() }, max(wait, 50L))
            return
        }
        shotInFlight = true
        lastShotAt = now
        scope.launch {
            val cap = capture(s)
            val bmp = cap.bitmap
            if (bmp != null && scrollCount == scrollsAtStart) {
                val measured = withContext(worker) {
                    items.map { item ->
                        val area = Rect(item.bounds).apply { offset(-cap.left, -cap.top) }
                        item to ColorSampler.analyze(bmp, area)
                    }
                }
                for ((item, style) in measured) {
                    val key = styleKey(item)
                    if (style != null) {
                        styles.put(key, style)
                        measureFailures.remove(key)
                    } else {
                        // No text visible yet (still loading?). Give up after a few tries.
                        val tries = (measureFailures[key] ?: 0) + 1
                        if (tries >= MAX_MEASURE_TRIES) styles.put(key, ColorSampler.ON_IMAGE)
                        measureFailures[key] = tries
                    }
                }
                if (measureFailures.size > 500) measureFailures.clear()
            } else if (cap.secure) {
                // Payment and similar screens block screenshots; leave them in Chinese.
                for (item in items) styles.put(styleKey(item), ColorSampler.ON_IMAGE)
            }
            bmp?.recycle()
            shotInFlight = false
            requestRefresh()
        }
    }

    /**
     * Screenshot of Taobao's window (Android 14+, which leaves our overlay out) or
     * of the whole screen (Android 11 to 13).
     */
    private suspend fun capture(s: Snapshot): Capture = suspendCancellableCoroutine { cont ->
        val window = s.window ?: Rect()
        val perWindow = Build.VERSION.SDK_INT >= 34
        val ox = if (perWindow) window.left else 0
        val oy = if (perWindow) window.top else 0
        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val bmp = try {
                    Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.let { hw ->
                        hw.copy(Bitmap.Config.ARGB_8888, false).also { hw.recycle() }
                    }
                } catch (e: Exception) {
                    null
                } finally {
                    buffer.close()
                }
                cont.resume(Capture(bmp, ox, oy, false))
            }

            override fun onFailure(errorCode: Int) {
                cont.resume(Capture(null, 0, 0, errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW))
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) takeScreenshotOfWindow(s.windowId, workerExecutor, callback)
            else takeScreenshot(Display.DEFAULT_DISPLAY, workerExecutor, callback)
        } catch (e: Exception) {
            Log.w(TAG, "Screenshot failed", e)
            cont.resume(Capture(null, 0, 0, false))
        }
    }

    // -------------------------------------------------------- pause / notify

    private fun togglePause() {
        paused = !paused
        if (paused) {
            overlay?.setBoxes(emptyList())
        } else {
            requestRefresh()
        }
        showNotification()
    }

    private fun showNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Live translation", NotificationManager.IMPORTANCE_LOW)
        )
        fun action(label: String, act: String, code: Int, icon: Int): Notification.Action {
            val pi = PendingIntent.getBroadcast(
                this, code, Intent(act).setPackage(packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi).build()
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        nm.notify(
            NOTIF_ID,
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentTitle(if (paused) "Taobao translation paused" else "Translating Taobao")
                .setContentText("Translations appear whenever Taobao is open")
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(
                    if (paused) action("Resume", ACTION_TOGGLE_PAUSE, 1, android.R.drawable.ic_media_play)
                    else action("Pause", ACTION_TOGGLE_PAUSE, 1, android.R.drawable.ic_media_pause)
                )
                .addAction(action("Turn off", ACTION_TURN_OFF, 2, android.R.drawable.ic_menu_close_clear_cancel))
                .build()
        )
    }

    private fun toast(message: String) {
        handler.post { Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show() }
    }

    private fun area(r: Rect): Long = r.width().toLong() * r.height()

    // --------------------------------------------------------------- teardown

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        runCatching { unregisterReceiver(receiver) }
        runCatching { prefs?.unregisterOnSharedPreferenceChangeListener(prefListener) }
        runCatching { overlay?.let { windowManager?.removeView(it) } }
        overlay = null
        runCatching { getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) }
        runCatching { translator?.close() }
        super.onDestroy()
    }
}
