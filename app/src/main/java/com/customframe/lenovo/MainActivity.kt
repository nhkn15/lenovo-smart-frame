package com.customframe.lenovo

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Geocoder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.exifinterface.media.ExifInterface
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
import java.util.Date
import java.util.HashMap
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class FramePhoto(
    val file: File,
    val blurFile: File?,
    val dateTaken: String,
    val locationName: String,
    val isPortrait: Boolean
)

data class SlideItem(val primary: FramePhoto, val secondary: FramePhoto? = null)

class Decoded(val photo: FramePhoto, val bg: Bitmap?, val fg: Bitmap?)

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var rootContainer: FrameLayout
    private lateinit var slideContainer: FrameLayout
    private lateinit var statusOverlayText: TextView
    private lateinit var scrim: View
    private lateinit var clockBox: LinearLayout
    private lateinit var timeText: TextView
    private lateinit var dateText: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgExecutor = Executors.newCachedThreadPool()
    private val renderToken = AtomicInteger(0)
    private var webServer: FrameWebServer? = null

    @Volatile private var slides: List<SlideItem> = emptyList()
    @Volatile private var currentIndex = 0
    @Volatile private var isPaused = false
    @Volatile private var activeAlbum = "Default"
    @Volatile private var slideDurationSec = 20
    @Volatile private var pairPortraits = true
    @Volatile private var shuffleSlides = true
    @Volatile private var sleepStartHour = 23
    @Volatile private var wakeStartHour = 7
    @Volatile private var transition = "random"
    @Volatile private var showClock = true
    @Volatile private var showCaption = true
    @Volatile private var syncStatus = "Idle"
    @Volatile private var currentWeatherStr = ""

    private val slideRunnable = object : Runnable {
        override fun run() {
            try {
                if (!isPaused && slides.isNotEmpty() && !isSleepHour()) advanceSlide(1)
            } catch (_: Throwable) {}
            mainHandler.postDelayed(this, slideDurationSec * 1000L)
        }
    }

    private val clockRunnable = object : Runnable {
        override fun run() {
            try { updateClockAndSleepState() } catch (_: Throwable) {}
            mainHandler.postDelayed(this, 10000L)
        }
    }

    private val weatherRunnable = object : Runnable {
        override fun run() {
            bgExecutor.execute { fetchWeather() }
            mainHandler.postDelayed(this, 30 * 60 * 1000L)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun crashRedirect(t: Throwable) {
        try {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            val i = Intent(applicationContext, CrashActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            i.putExtra("trace", sw.toString())
            applicationContext.startActivity(i)
            Thread.sleep(800)
        } catch (_: Throwable) {}
        android.os.Process.killProcess(android.os.Process.myPid())
        System.exit(10)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread.setDefaultUncaughtExceptionHandler { _, t -> crashRedirect(t) }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        prefs = getSharedPreferences("frame_prefs", Context.MODE_PRIVATE)
        activeAlbum = prefs.getString("activeAlbum", "Default") ?: "Default"
        slideDurationSec = prefs.getInt("slideDurationSec", 20)
        pairPortraits = prefs.getBoolean("pairPortraitPhotos", true)
        shuffleSlides = prefs.getBoolean("shuffleSlides", true)
        sleepStartHour = prefs.getInt("sleepStartHour", 23)
        wakeStartHour = prefs.getInt("wakeStartHour", 7)
        transition = prefs.getString("transition", "random") ?: "random"
        showClock = prefs.getBoolean("showClock", true)
        showCaption = prefs.getBoolean("showCaption", true)

        buildNativeUi()
        reloadAlbumPhotos(activeAlbum, pairPortraits)

        bgExecutor.execute {
            try {
                webServer?.stop()
                webServer = FrameWebServer(8080)
                webServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            } catch (e: Throwable) {
                syncStatus = "Web server: ${e.message}"
                updateOnboardingText()
            }
        }

        mainHandler.post(clockRunnable)
        mainHandler.post(weatherRunnable)
        mainHandler.postDelayed(slideRunnable, slideDurationSec * 1000L)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        try { webServer?.stop() } catch (_: Throwable) {}
    }

    // ---------------------------------------------------------------- UI

    private fun buildNativeUi() {
        val mp = FrameLayout.LayoutParams.MATCH_PARENT
        val wc = FrameLayout.LayoutParams.WRAP_CONTENT

        rootContainer = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        slideContainer = FrameLayout(this)
        rootContainer.addView(slideContainer, FrameLayout.LayoutParams(mp, mp))

        scrim = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00000000, 0xB3000000.toInt())
            )
        }
        rootContainer.addView(scrim, FrameLayout.LayoutParams(mp, dp(200), Gravity.BOTTOM))

        clockBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(16), dp(32), dp(24))
        }
        timeText = TextView(this).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 64f)
            setShadowLayer(10f, 0f, 2f, Color.BLACK)
            includeFontPadding = false
        }
        dateText = TextView(this).apply {
            setTextColor(Color.parseColor("#E5E7EB"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setShadowLayer(10f, 0f, 2f, Color.BLACK)
        }
        clockBox.addView(timeText)
        clockBox.addView(dateText)
        rootContainer.addView(clockBox, FrameLayout.LayoutParams(wc, wc, Gravity.BOTTOM or Gravity.START))

        statusOverlayText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        rootContainer.addView(statusOverlayText, FrameLayout.LayoutParams(mp, mp))

        setContentView(rootContainer)
        updateOnboardingText()
    }

    private fun updateOnboardingText() {
        mainHandler.post {
            if (slides.isEmpty()) {
                statusOverlayText.visibility = View.VISIBLE
                statusOverlayText.text = "Lenovo Smart Frame\n\nOpen http://${getLocalIpAddress()}:8080 on your phone or PC to add photos\n\nStatus: $syncStatus"
            } else {
                statusOverlayText.visibility = View.GONE
            }
        }
    }

    private fun renderCurrentSlide() {
        val token = renderToken.incrementAndGet()
        val slide = slides.getOrNull(currentIndex)
        if (slide == null) {
            mainHandler.post { slideContainer.removeAllViews(); updateOnboardingText() }
            return
        }
        bgExecutor.execute {
            try {
                // Decode for the actual display area. Paired portraits each get half the
                // screen width; a single photo gets the full screen dimensions.
                val dm = resources.displayMetrics
                val paired = slide.secondary != null
                val targetW = if (paired) maxOf(1, dm.widthPixels / 2) else dm.widthPixels
                val targetH = dm.heightPixels
                val decoded = listOfNotNull(slide.primary, slide.secondary).map { p ->
                    Decoded(
                        p,
                        decodeSampledBitmap(p.blurFile ?: p.file, 320, 180),
                        decodeSampledBitmap(p.file, targetW, targetH)
                    )
                }
                if (token != renderToken.get()) return@execute
                mainHandler.post { if (token == renderToken.get()) showSlide(decoded) }
            } catch (_: Throwable) {}
        }
    }

    private fun trimTo(n: Int) {
        while (slideContainer.childCount > n) {
            val v = slideContainer.getChildAt(0)
            (v.tag as? List<*>)?.forEach { (it as? ValueAnimator)?.cancel() }
            v.animate().cancel()
            slideContainer.removeViewAt(0)
        }
    }

    private fun showSlide(panes: List<Decoded>) {
        try {
            statusOverlayText.visibility = View.GONE
            trimTo(1)
            val dm = resources.displayMetrics
            val sw = dm.widthPixels
            val sh = dm.heightPixels
            val paired = panes.size == 2
            val mp = LinearLayout.LayoutParams.MATCH_PARENT
            val gapPx = if (paired) dp(14) else 0

            val wrapper = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.BLACK)
            }
            val anims = ArrayList<ValueAnimator>()
            panes.forEachIndexed { i, d ->
                if (i == 1) {
                    wrapper.addView(View(this).apply { setBackgroundColor(Color.BLACK) }, LinearLayout.LayoutParams(gapPx, mp))
                }
                wrapper.addView(
                    createPane(d, if (paired) (sw - gapPx) / 2 else sw, sh, i == 0, anims, paired),
                    LinearLayout.LayoutParams(0, mp, 1f)
                )
            }
            wrapper.tag = anims
            slideContainer.addView(wrapper, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val old = if (slideContainer.childCount > 1) slideContainer.getChildAt(0) else null
            runTransition(wrapper, old, sw)
        } catch (_: Throwable) {}
    }

    private fun runTransition(neu: View, old: View?, sw: Int) {
        if (old == null) {
            neu.alpha = 0f
            neu.animate().alpha(1f).setDuration(800).start()
            return
        }
        val mode = if (transition == "random") listOf("fade", "slide", "zoom").random() else transition
        when (mode) {
            "slide" -> {
                neu.translationX = sw.toFloat()
                neu.animate().translationX(0f).setDuration(900).setInterpolator(DecelerateInterpolator())
                    .withEndAction { trimTo(1) }.start()
                old.animate().translationX(-sw * 0.3f).alpha(0.4f).setDuration(900).start()
            }
            "zoom" -> {
                neu.alpha = 0f
                neu.scaleX = 1.12f
                neu.scaleY = 1.12f
                neu.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(1200)
                    .withEndAction { trimTo(1) }.start()
            }
            else -> {
                neu.alpha = 0f
                neu.animate().alpha(1f).setDuration(900).withEndAction { trimTo(1) }.start()
            }
        }
    }

    private fun createPane(d: Decoded, pw: Int, ph: Int, zoomIn: Boolean, anims: MutableList<ValueAnimator>, paired: Boolean): FrameLayout {
        val mp = FrameLayout.LayoutParams.MATCH_PARENT
        val pane = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            clipChildren = true
        }

        d.bg?.let { b ->
            val iv = ImageView(this).apply {
                setImageBitmap(b)
                scaleType = ImageView.ScaleType.CENTER_CROP
                alpha = 0.55f
            }
            pane.addView(iv, FrameLayout.LayoutParams(mp, mp))
        }

        d.fg?.let { f ->
            // Always fit the complete original photo. The blurred layer fills any unused space.
            val iv = ImageView(this).apply {
                setImageBitmap(f)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            pane.addView(iv, FrameLayout.LayoutParams(mp, mp))

            // Keep the complete photo visible. Avoid Ken Burns scaling here because even
            // a subtle zoom can crop the edges of portraits and panoramic photographs.
        }

        if (showCaption) {
            val loc = d.photo.locationName
            val date = prettyDate(d.photo.dateTaken)
            if (loc.isNotEmpty() || date.isNotEmpty()) {
                val box = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.END
                    setPadding(dp(24), dp(16), dp(24), dp(20))
                }
                if (loc.isNotEmpty()) {
                    box.addView(TextView(this).apply {
                        text = loc
                        gravity = Gravity.END
                        setTextColor(Color.WHITE)
                        setTypeface(null, Typeface.BOLD)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                        setShadowLayer(10f, 0f, 2f, Color.BLACK)
                    })
                }
                if (date.isNotEmpty()) {
                    box.addView(TextView(this).apply {
                        text = date
                        gravity = Gravity.END
                        setTextColor(Color.parseColor("#E5E7EB"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        setShadowLayer(10f, 0f, 2f, Color.BLACK)
                    })
                }
                pane.addView(box, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.END
                ))
            }
        }
        return pane
    }

    private fun prettyDate(s: String): String {
        if (s.length < 10) return ""
        val clean = s.take(10).replace(':', '-')
        return try {
            val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(clean)
            if (d == null) "" else SimpleDateFormat("MMM d, yyyy", Locale.US).format(d)
        } catch (_: Throwable) { "" }
    }

    private fun decodeSampledBitmap(file: File, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return null

            // Use the EXIF orientation to estimate the dimensions after rotation. This
            // avoids undersampling portrait photos that are stored sideways in the file.
            val orientation = try {
                ExifInterface(file.absolutePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } catch (_: Throwable) { ExifInterface.ORIENTATION_NORMAL }
            val swaps = orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
                orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                orientation == ExifInterface.ORIENTATION_TRANSVERSE ||
                orientation == ExifInterface.ORIENTATION_ROTATE_270
            val displayW = if (swaps) options.outHeight else options.outWidth
            val displayH = if (swaps) options.outWidth else options.outHeight

            var sample = 1
            while (displayW / (sample * 2) >= reqWidth &&
                displayH / (sample * 2) >= reqHeight) {
                sample *= 2
            }
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return null

            // BitmapFactory does not apply EXIF orientation. Normalize all eight EXIF
            // orientations before ImageView lays the image out with FIT_CENTER.
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.setRotate(90f)
                    matrix.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.setRotate(-90f)
                    matrix.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            }
            if (orientation == ExifInterface.ORIENTATION_NORMAL ||
                orientation == ExifInterface.ORIENTATION_UNDEFINED) {
                decoded
            } else {
                try {
                    Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                        .also { if (it !== decoded) decoded.recycle() }
                } catch (_: Throwable) {
                    decoded
                }
            }
        } catch (_: Throwable) { null }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT -> { advanceSlide(1); true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { advanceSlide(-1); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { isPaused = !isPaused; true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun advanceSlide(delta: Int) {
        val size = slides.size
        if (size == 0) return
        if (delta > 0 && currentIndex >= size - 1 && shuffleSlides && size > 1) {
            val previous = slides[currentIndex]
            val nextDeck = slides.shuffled().toMutableList()
            if (nextDeck.first() == previous) Collections.swap(nextDeck, 0, 1)
            slides = nextDeck
            currentIndex = 0
        } else {
            currentIndex = ((currentIndex + delta) % size + size) % size
        }
        renderCurrentSlide()
    }

    private fun isSleepHour(): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            sleepStartHour > wakeStartHour -> h >= sleepStartHour || h < wakeStartHour
            sleepStartHour < wakeStartHour -> h in sleepStartHour until wakeStartHour
            else -> false
        }
    }

    private fun updateClockAndSleepState() {
        val now = Date()
        timeText.text = SimpleDateFormat("h:mm", Locale.US).format(now)
        val date = SimpleDateFormat("EEEE, MMMM d", Locale.US).format(now)
        dateText.text = if (currentWeatherStr.isNotEmpty()) "$date  \u2022  $currentWeatherStr" else date

        val sleeping = isSleepHour()
        slideContainer.visibility = if (sleeping) View.INVISIBLE else View.VISIBLE
        clockBox.visibility = if (sleeping || !showClock) View.GONE else View.VISIBLE
        scrim.visibility = if (sleeping || !(showClock || showCaption)) View.GONE else View.VISIBLE
    }

    // ---------------------------------------------------------------- Photos

    private fun getAlbumsRootDir(): File {
        val dir = File(filesDir, "albums")
        if (!dir.exists()) dir.mkdirs()
        val def = File(dir, "Default")
        if (!def.exists()) def.mkdirs()
        return dir
    }

    private fun detectIsPortrait(file: File): Boolean {
        return try {
            val orientation = ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return false
            val swaps = orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
                orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                orientation == ExifInterface.ORIENTATION_TRANSVERSE ||
                orientation == ExifInterface.ORIENTATION_ROTATE_270
            val width = if (swaps) opts.outHeight else opts.outWidth
            val height = if (swaps) opts.outWidth else opts.outHeight
            height > width
        } catch (_: Throwable) { false }
    }

    private fun reloadAlbumPhotos(albumName: String, pairMode: Boolean) {
        bgExecutor.execute {
            try {
                val safeName = albumName.replace("[^a-zA-Z0-9_-]".toRegex(), "_").ifEmpty { "Default" }
                val albumDir = File(getAlbumsRootDir(), safeName)
                if (!albumDir.exists()) albumDir.mkdirs()

                val rawFiles = albumDir.listFiles { f ->
                    f.isFile && f.name.endsWith(".jpg", true) && !f.name.endsWith("_blur.jpg")
                } ?: emptyArray()

                val metaPrefs = getSharedPreferences("photo_meta", Context.MODE_PRIVATE)
                val loaded = rawFiles.map { file ->
                    val blur = File(file.parent, file.nameWithoutExtension + "_blur.jpg")
                    var dateStr = ""
                    var locStr = ""
                    val metaStr = metaPrefs.getString(file.name, null)
                    if (metaStr != null) {
                        try {
                            val obj = JSONObject(metaStr)
                            dateStr = obj.optString("date", "")
                            locStr = obj.optString("loc", "")
                        } catch (_: Throwable) {}
                    }
                    // Always inspect the actual image. Older cached metadata can contain
                    // an incorrect portrait flag from earlier app versions.
                    FramePhoto(file, if (blur.exists()) blur else null, dateStr, locStr, detectIsPortrait(file))
                }.shuffled()

                // Build slides: pair every two confirmed portrait images; keep landscapes full-screen.
                val deck = mutableListOf<SlideItem>()
                val portraitCount = loaded.count { it.isPortrait }
                if (!pairMode) {
                    loaded.forEach { deck.add(SlideItem(it)) }
                } else {
                    val portraits = loaded.filter { it.isPortrait }
                    val landscapes = loaded.filter { !it.isPortrait }
                    var i = 0
                    while (i + 1 < portraits.size) {
                        deck.add(SlideItem(portraits[i], portraits[i + 1]))
                        i += 2
                    }
                    if (i < portraits.size) deck.add(SlideItem(portraits[i]))
                    landscapes.forEach { deck.add(SlideItem(it)) }
                }

                val pairCount = if (pairMode) portraitCount / 2 else 0
                syncStatus = "Loaded ${loaded.size} photos; detected $portraitCount portraits; created $pairCount two-photo slides."
                activeAlbum = safeName
                slides = if (shuffleSlides) deck.shuffled() else deck.toList()
                currentIndex = 0
                renderCurrentSlide()
            } catch (_: Throwable) {}
        }
    }

    // ---------------------------------------------------------------- Google Photos Picker

    private fun httpJsonRequest(urlStr: String, method: String, token: String?, bodyStr: String?): JSONObject {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 15000
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
            if (bodyStr != null) {
                doOutput = true
                outputStream.use { it.write(bodyStr.toByteArray(Charsets.UTF_8)) }
            }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val respText = BufferedReader(InputStreamReader(stream)).use { it.readText() }
        if (code !in 200..299) error("HTTP $code: $respText")
        return JSONObject(if (respText.isBlank()) "{}" else respText)
    }

    private fun startPickerAndPoll(accessToken: String, targetAlbum: String): JSONObject {
        val sessionObj = httpJsonRequest("https://photospicker.googleapis.com/v1/sessions", "POST", accessToken, "{}")
        val sessionId = sessionObj.getString("id")
        val pickerUri = sessionObj.getString("pickerUri")
        bgExecutor.execute { pollPickerSession(accessToken, sessionId, targetAlbum) }
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("pickerUri", pickerUri + "/autoclose")
        }
    }

    private fun pollPickerSession(accessToken: String, sessionId: String, targetAlbum: String) {
        val safeAlbum = targetAlbum.replace("[^a-zA-Z0-9_-]".toRegex(), "_").ifEmpty { "Default" }
        syncStatus = "Waiting for photo selection..."
        updateOnboardingText()

        var ready = false
        for (i in 1..225) {
            try {
                val obj = httpJsonRequest("https://photospicker.googleapis.com/v1/sessions/$sessionId", "GET", accessToken, null)
                if (obj.optBoolean("mediaItemsSet", false)) { ready = true; break }
            } catch (_: Throwable) {}
            try { Thread.sleep(4000) } catch (_: Throwable) {}
        }
        if (!ready) {
            syncStatus = "Selection timed out."
            updateOnboardingText()
            return
        }

        syncStatus = "Fetching selected photos..."
        updateOnboardingText()

        val items = mutableListOf<JSONObject>()
        var pageToken: String? = null
        do {
            try {
                var url = "https://photospicker.googleapis.com/v1/mediaItems?sessionId=$sessionId&pageSize=100"
                if (pageToken != null) url += "&pageToken=$pageToken"
                val obj = httpJsonRequest(url, "GET", accessToken, null)
                val arr = obj.optJSONArray("mediaItems")
                if (arr != null) for (k in 0 until arr.length()) items.add(arr.getJSONObject(k))
                pageToken = if (obj.has("nextPageToken")) obj.getString("nextPageToken") else null
            } catch (_: Throwable) { pageToken = null }
        } while (pageToken != null)

        val albumDir = File(getAlbumsRootDir(), safeAlbum)
        if (!albumDir.exists()) albumDir.mkdirs()
        val metaPrefs = getSharedPreferences("photo_meta", Context.MODE_PRIVATE)

        var added = 0
        var skipped = 0
        var failed = 0
        for ((idx, item) in items.withIndex()) {
            if (item.optString("type", "PHOTO") == "VIDEO") {
                skipped++
                continue
            }
            val id = item.optString("id", "")
            if (id.isBlank()) {
                failed++
                continue
            }
            val targetFile = File(albumDir, "$id.jpg")
            if (targetFile.exists() && targetFile.length() > 0L) {
                skipped++
                continue
            }
            val tempFile = File(albumDir, "$id.jpg.part")
            try {
                syncStatus = "Downloading ${idx + 1}/${items.size} · $added added · $failed failed..."
                updateOnboardingText()
                val createTime = item.optString("createTime", "")
                val baseUrl = item.getJSONObject("mediaFile").getString("baseUrl")
                val conn = (URL("$baseUrl=w1920-h1920").openConnection() as HttpURLConnection).apply {
                    setRequestProperty("Authorization", "Bearer $accessToken")
                    connectTimeout = 20000
                    readTimeout = 45000
                }
                try {
                    if (conn.responseCode !in 200..299) error("Photo download HTTP ${conn.responseCode}")
                    conn.inputStream.use { input -> FileOutputStream(tempFile).use { output -> input.copyTo(output) } }
                    if (tempFile.length() <= 0L) error("Downloaded photo was empty")
                    if (targetFile.exists()) targetFile.delete() // Remove a zero-byte/incomplete file from an earlier attempt.
                    if (!tempFile.renameTo(targetFile)) error("Could not finalize downloaded photo")
                    extractExifAndBlur(targetFile, createTime, metaPrefs)
                    added++
                } finally {
                    conn.disconnect()
                }
            } catch (_: Throwable) {
                failed++
                tempFile.delete()
            }
        }

        try { httpJsonRequest("https://photospicker.googleapis.com/v1/sessions/$sessionId", "DELETE", accessToken, null) } catch (_: Throwable) {}
        prefs.edit().putString("activeAlbum", safeAlbum).apply()
        syncStatus = "Sync complete: $added added, $skipped already present/skipped, $failed failed"
        reloadAlbumPhotos(safeAlbum, pairPortraits)
    }

    private fun extractExifAndBlur(photoFile: File, fallbackDate: String, metaPrefs: SharedPreferences) {
        try {
            val exif = ExifInterface(photoFile.absolutePath)
            val rawDate = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: fallbackDate
            val portrait = detectIsPortrait(photoFile)
            var locStr = ""
            val latLong = FloatArray(2)
            if (exif.getLatLong(latLong)) {
                try {
                    @Suppress("DEPRECATION")
                    val addrs = Geocoder(this, Locale.getDefault()).getFromLocation(latLong[0].toDouble(), latLong[1].toDouble(), 1)
                    if (!addrs.isNullOrEmpty()) {
                        val a = addrs[0]
                        locStr = listOfNotNull(a.locality ?: a.subAdminArea, a.adminArea ?: a.countryName)
                            .filter { it.isNotBlank() }.joinToString(", ")
                    }
                } catch (_: Throwable) {}
            }
            val meta = JSONObject().apply {
                put("date", rawDate.take(10))
                put("loc", locStr)
                put("portrait", portrait)
            }
            metaPrefs.edit().putString(photoFile.name, meta.toString()).apply()

            val small = BitmapFactory.decodeFile(photoFile.absolutePath, BitmapFactory.Options().apply { inSampleSize = 16 })
            if (small != null) {
                val blurFile = File(photoFile.parent, photoFile.nameWithoutExtension + "_blur.jpg")
                FileOutputStream(blurFile).use { out -> small.compress(Bitmap.CompressFormat.JPEG, 70, out) }
            }
        } catch (_: Throwable) {}
    }

    private fun fetchWeather() {
        try {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=32.7157&longitude=-117.1611&current_weather=true&temperature_unit=fahrenheit"
            val obj = httpJsonRequest(url, "GET", null, null)
            val temp = Math.round(obj.getJSONObject("current_weather").getDouble("temperature"))
            currentWeatherStr = "$temp\u00B0F"
        } catch (_: Throwable) {}
    }

    private fun getLocalIpAddress(): String {
        try {
            for (intf in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (addr in Collections.list(intf.inetAddresses)) {
                    if (!addr.isLoopbackAddress && addr.hostAddress?.indexOf(':') == -1) {
                        return addr.hostAddress ?: "0.0.0.0"
                    }
                }
            }
        } catch (_: Throwable) {}
        return "0.0.0.0"
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )
    }

    // ---------------------------------------------------------------- Web server

    inner class FrameWebServer(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            return try {
                when {
                    session.uri == "/" && session.method == Method.GET ->
                        newFixedLengthResponse(Response.Status.OK, MIME_HTML, buildHtml())

                    session.uri == "/api/status" && session.method == Method.GET ->
                        newFixedLengthResponse(Response.Status.OK, "application/json", JSONObject().put("status", syncStatus).toString())

                    session.uri == "/api/upload" && session.method == Method.POST -> {
                        val b = parseBody(session)
                        val album = b.optString("albumName", "Default").replace("[^a-zA-Z0-9_-]".toRegex(), "_").ifEmpty { "Default" }
                        val data = b.getString("imageBase64").substringAfter(",")
                        if (data.isEmpty()) error("empty image")
                        val bytes = Base64.decode(data, Base64.DEFAULT)
                        val albumDir = File(getAlbumsRootDir(), album)
                        if (!albumDir.exists()) albumDir.mkdirs()
                        val file = File(albumDir, "upload_${System.currentTimeMillis()}.jpg")
                        FileOutputStream(file).use { it.write(bytes) }
                        extractExifAndBlur(file, "", getSharedPreferences("photo_meta", Context.MODE_PRIVATE))
                        reloadAlbumPhotos(album, pairPortraits)
                        newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"uploaded"}""")
                    }

                    session.uri == "/api/picker/go" && session.method == Method.GET -> {
                        val tk = session.parms["token"] ?: ""
                        val al = session.parms["album"] ?: "Default"
                        val out = startPickerAndPoll(tk, al)
                        val resp = newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML, "")
                        resp.addHeader("Location", out.getString("pickerUri"))
                        resp
                    }

                    session.uri == "/api/settings" && session.method == Method.POST -> {
                        val b = parseBody(session)
                        val newAlbum = b.optString("activeAlbum", activeAlbum)
                        slideDurationSec = b.optInt("slideDurationSec", 20).coerceAtLeast(5)
                        pairPortraits = b.optBoolean("pairPortraitPhotos", true)
                        shuffleSlides = b.optBoolean("shuffleSlides", true)
                        sleepStartHour = b.optInt("sleepStartHour", 23).coerceIn(0, 23)
                        wakeStartHour = b.optInt("wakeStartHour", 7).coerceIn(0, 23)
                        transition = b.optString("transition", "random")
                        showClock = b.optBoolean("showClock", true)
                        showCaption = b.optBoolean("showCaption", true)
                        val cid = b.optString("oauthClientId", "").trim()

                        val ed = prefs.edit()
                        ed.putString("activeAlbum", newAlbum)
                        ed.putInt("slideDurationSec", slideDurationSec)
                        ed.putBoolean("pairPortraitPhotos", pairPortraits)
                        ed.putBoolean("shuffleSlides", shuffleSlides)
                        ed.putInt("sleepStartHour", sleepStartHour)
                        ed.putInt("wakeStartHour", wakeStartHour)
                        ed.putString("transition", transition)
                        ed.putBoolean("showClock", showClock)
                        ed.putBoolean("showCaption", showCaption)
                        if (cid.isNotEmpty()) ed.putString("oauthClientId", cid)
                        ed.apply()

                        mainHandler.post { updateClockAndSleepState() }
                        reloadAlbumPhotos(newAlbum, pairPortraits)
                        newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"ok"}""")
                    }

                    session.uri == "/api/control" && session.method == Method.POST -> {
                        when (parseBody(session).optString("action")) {
                            "NEXT" -> mainHandler.post { advanceSlide(1) }
                            "PREV" -> mainHandler.post { advanceSlide(-1) }
                            "PAUSE" -> isPaused = !isPaused
                        }
                        newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"ok"}""")
                    }

                    else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
                }
            } catch (e: Throwable) {
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                    JSONObject().put("error", e.message ?: "error").toString())
            }
        }

        private fun parseBody(session: IHTTPSession): JSONObject {
            val map = HashMap<String, String>()
            session.parseBody(map)
            return JSONObject(map["postData"] ?: "{}")
        }

        private fun buildHtml(): String {
            val albums = getAlbumsRootDir().listFiles { f -> f.isDirectory }?.map { it.name }?.sorted() ?: listOf("Default")
            val savedCid = prefs.getString("oauthClientId", "") ?: ""
            val pairChk = if (pairPortraits) "checked" else ""
            val clockChk = if (showClock) "checked" else ""
            val capChk = if (showCaption) "checked" else ""
            val shuffleChk = if (shuffleSlides) "checked" else ""
            fun sel(v: String) = if (transition == v) "selected" else ""
            val opts = albums.joinToString("") { name ->
                val count = File(getAlbumsRootDir(), name).listFiles { f -> f.name.endsWith(".jpg") && !f.name.endsWith("_blur.jpg") }?.size ?: 0
                val s = if (name == activeAlbum) "selected" else ""
                "<option value='$name' $s>$name ($count photos)</option>"
            }

            return """
            <!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Smart Frame</title>
            <style>
                body { font-family: sans-serif; background: #0b0f19; color: #f3f4f6; padding: 18px; max-width: 540px; margin: auto; }
                .card { background: #161c2b; padding: 18px; border-radius: 14px; margin-bottom: 16px; border: 1px solid #263046; }
                h3 { margin-top: 0; font-weight: 600; }
                label { display: block; margin: 10px 0 4px; font-size: 13px; color: #9ca3af; }
                input[type="text"], input[type="number"], input[type="file"], select, button { width: 100%; padding: 11px; border-radius: 8px; border: 1px solid #374357; background: #232c3f; color: #fff; box-sizing: border-box; }
                .chk { display: flex; align-items: center; gap: 10px; margin-top: 10px; background: #232c3f; padding: 12px; border-radius: 8px; color: #f3f4f6; font-size: 14px; }
                .chk input { width: 20px; height: 20px; }
                button { background: #2563eb; border: none; font-weight: bold; cursor: pointer; margin-top: 12px; }
                .row { display: flex; gap: 10px; }
                .ok { color: #34d399; font-size: 13px; }
            </style></head><body>
            <h2>Smart Frame &middot; ${getLocalIpAddress()}</h2>
            <div class="card"><div class="row">
                <button onclick="cmd('PREV')">&#9198; Prev</button>
                <button onclick="cmd('PAUSE')">&#9199; Pause</button>
                <button onclick="cmd('NEXT')">&#9197; Next</button>
            </div></div>
            <div class="card">
                <h3>Upload from this device</h3>
                <label>Album</label><input type="text" id="upAlbum" value="$activeAlbum">
                <label>Photos</label><input type="file" id="files" multiple accept="image/*">
                <button onclick="uploadFiles()">Upload</button>
                <p id="upSt" class="ok"></p>
            </div>
            <div class="card">
                <h3>Google Photos</h3>
                <label>Auth page URL (your GitHub Pages link)</label>
                <input type="text" id="authUrl" placeholder="https://yourname.github.io/yourrepo/">
                <label>Google Web OAuth Client ID</label>
                <input type="text" id="cid" value="$savedCid" placeholder="xxxx.apps.googleusercontent.com">
                <label>Target album</label><input type="text" id="targetAlbum" value="$activeAlbum">
                <button onclick="pick()">+ Pick photos from Google Photos</button>
                <p id="st" class="ok">Status: $syncStatus</p>
            </div>
            <div class="card">
                <h3>Display</h3>
                <label>Active album</label><select id="album">$opts</select>
                <label>Transition</label>
                <select id="tr">
                    <option value="random" ${sel("random")}>Random</option>
                    <option value="fade" ${sel("fade")}>Fade</option>
                    <option value="slide" ${sel("slide")}>Slide</option>
                    <option value="zoom" ${sel("zoom")}>Zoom</option>
                </select>
                <label class="chk"><input type="checkbox" id="pair" $pairChk><span>Show 2 portrait photos side by side</span></label>
                <label class="chk"><input type="checkbox" id="shuffle" $shuffleChk><span>Shuffle slideshow (avoid repeats between cycles)</span></label>
                <label class="chk"><input type="checkbox" id="clk" $clockChk><span>Show clock, date and weather</span></label>
                <label class="chk"><input type="checkbox" id="cap" $capChk><span>Show place and date on photos</span></label>
                <label>Seconds per photo</label><input type="number" id="dur" value="$slideDurationSec">
                <div class="row">
                    <div style="flex:1"><label>Sleep hour (0-23)</label><input type="number" id="sh" value="$sleepStartHour"></div>
                    <div style="flex:1"><label>Wake hour (0-23)</label><input type="number" id="wh" value="$wakeStartHour"></div>
                </div>
                <button onclick="save(true)">Save</button>
            </div>
            <script>
                try { document.getElementById("authUrl").value = localStorage.getItem("authUrl") || ""; } catch (e) {}
                setInterval(function () {
                    fetch('/api/status').then(function (r) { return r.json(); }).then(function (d) {
                        document.getElementById('st').innerText = 'Status: ' + d.status;
                    }).catch(function () {});
                }, 3000);
                async function uploadFiles() {
                    const input = document.getElementById('files');
                    const album = document.getElementById('upAlbum').value || 'Default';
                    const st = document.getElementById('upSt');
                    if (!input.files.length) { alert('Choose at least 1 photo'); return; }
                    for (let i = 0; i < input.files.length; i++) {
                        st.innerText = 'Uploading ' + (i + 1) + ' of ' + input.files.length + '...';
                        const b64 = await new Promise(function (res) {
                            const im = new Image();
                            im.onload = function () {
                                const s = Math.min(1, 1920 / Math.max(im.width, im.height));
                                const c = document.createElement('canvas');
                                c.width = Math.round(im.width * s); c.height = Math.round(im.height * s);
                                c.getContext('2d').drawImage(im, 0, 0, c.width, c.height);
                                res(c.toDataURL('image/jpeg', 0.85));
                            };
                            im.onerror = function () { res(''); };
                            im.src = URL.createObjectURL(input.files[i]);
                        });
                        if (!b64) continue;
                        await fetch('/api/upload', { method: 'POST', body: JSON.stringify({ albumName: album, imageBase64: b64 }) });
                    }
                    st.innerText = 'Upload complete!';
                    setTimeout(function () { location.reload(); }, 1000);
                }
                function pick() {
                    const cid = document.getElementById('cid').value.trim();
                    const authUrl = document.getElementById('authUrl').value.trim();
                    if (!cid || !authUrl) { alert('Enter the Auth page URL and the OAuth Client ID first'); return; }
                    try { localStorage.setItem('authUrl', authUrl); } catch (e) {}
                    save(false).then(function () {
                        const album = encodeURIComponent(document.getElementById('targetAlbum').value || 'Default');
                        location.href = authUrl + '?cid=' + encodeURIComponent(cid) + '&frame=' + location.host + '&album=' + album;
                    });
                }
                async function save(reload) {
                    await fetch('/api/settings', {
                        method: 'POST',
                        body: JSON.stringify({
                            oauthClientId: document.getElementById('cid').value,
                            activeAlbum: document.getElementById('album').value,
                            transition: document.getElementById('tr').value,
                            pairPortraitPhotos: document.getElementById('pair').checked,
                            shuffleSlides: document.getElementById('shuffle').checked,
                            showClock: document.getElementById('clk').checked,
                            showCaption: document.getElementById('cap').checked,
                            slideDurationSec: parseInt(document.getElementById('dur').value),
                            sleepStartHour: parseInt(document.getElementById('sh').value),
                            wakeStartHour: parseInt(document.getElementById('wh').value)
                        })
                    });
                    if (reload) location.reload();
                }
                async function cmd(action) { await fetch('/api/control', { method: 'POST', body: JSON.stringify({ action: action }) }); }
            </script></body></html>
            """.trimIndent()
        }
    }
}
