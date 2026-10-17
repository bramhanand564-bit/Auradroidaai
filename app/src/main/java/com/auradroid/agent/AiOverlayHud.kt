package com.auradroid.agent

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.sin

class AiOverlayHud(private val context: Context) {

    companion object {
        @Volatile
        var lastSniffedMp4Url: String? = null
        @Volatile
        var lastSniffedTimestampMs: Long = 0L

        init {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try {
                    WebView.enableSlowWholeDocumentDraw()
                } catch (_: Exception) {}
            }
        }
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var pointerCanvasView: NaxHandCursorCanvas? = null
    private var podRootLayout: LinearLayout? = null
    private var podLayoutParams: WindowManager.LayoutParams? = null

    private var compactRow: LinearLayout? = null
    private var sideInfoContainer: LinearLayout? = null
    private var tvPodTitle: TextView? = null
    private var tvPodSubtitle: TextView? = null
    private var btnQuickMiniBrowser: TextView? = null
    private var btnQuickTeachToggle: TextView? = null
    private var stopBadge: TextView? = null
    private var botAvatarView: NaxBotAvatarView? = null

    private var expandedContainer: LinearLayout? = null
    private var tvExpandedStepInfo: TextView? = null
    private var btnExpandFullToggle: TextView? = null
    private var podWebFrame: FrameLayout? = null

    private var sharedWebView: WebView? = null
    private var isDesktopMode = false
    private var isAdded = false
    private var isPodVisible = true
    private var isSideBarExpanded = false
    private var currentStage = 1

    var isLiveTeachActive = false
        private set
    private var currentTeachSkillName = "GOOGLE_FLOW"
    private val recordedTeachSteps = JSONArray()
    private var onLiveTeachFinishedCallback: ((String) -> Unit)? = null

    private val autoCollapseSideBarRunnable = Runnable {
        if (!AutoTouchService.isJobRunning && !isLiveTeachActive && currentStage == 1) {
            setSideBarExpanded(false)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreateSharedWebView(): WebView {
        if (sharedWebView == null) {
            val wv = object : WebView(context.applicationContext) {
                override fun onCheckIsTextEditor(): Boolean = true
            }.apply {
                isFocusable = true
                isFocusableInTouchMode = true
                requestFocus()

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.javaScriptCanOpenWindowsAutomatically = true
                settings.setSupportMultipleWindows(false)
                settings.mediaPlaybackRequiresUserGesture = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false

                // Idea 5: Stealth Anti-Bot JS Setup
                settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(this@apply, true)
                }

                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun saveBase64Video(base64Data: String, fileName: String) {
                        try {
                            val cleanB64 = base64Data.substringAfter(",")
                            val bytes = Base64.decode(cleanB64, Base64.DEFAULT)
                            val prefs = context.getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
                            val defaultFolder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AutoVideos").absolutePath
                            val targetFolder = prefs.getString("folder_path", defaultFolder) ?: defaultFolder
                            val dir = File(targetFolder)
                            if (!dir.exists()) dir.mkdirs()
                            val outFile = File(dir, fileName)
                            FileOutputStream(outFile).use { it.write(bytes) }
                        } catch (_: Exception) {}
                    }
                }, "AuraBlobBridge")

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        val lower = reqUrl.lowercase()
                        if ((lower.contains(".mp4") || lower.contains("videoplayback") || lower.contains("generated_video")) && !lower.endsWith(".js") && !lower.endsWith(".css")) {
                            lastSniffedMp4Url = reqUrl
                            lastSniffedTimestampMs = System.currentTimeMillis()
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        CookieManager.getInstance().flush()
                        url?.let { MainActivity.onBrowserUrlChanged?.invoke(it) }
                        
                        // Idea 5: Stealth Injection to bypass Webdriver detection
                        val stealthJs = """
                            (function() {
                                Object.defineProperty(navigator, 'webdriver', {get: () => undefined});
                                window.chrome = { runtime: {} };
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(stealthJs, null)
                    }
                }
                webChromeClient = WebChromeClient()

                setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                    enqueueDirectVideoDownload(url, userAgent, contentDisposition, mimeType)
                }

                measure(View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY))
                layout(0, 0, 540, 960)
            }
            sharedWebView = wv
        }
        return sharedWebView!!
    }

    fun enqueueDirectVideoDownload(url: String, userAgent: String = "Mozilla/5.0", contentDisposition: String = "", mimeType: String = "video/mp4"): Boolean {
        return try {
            val wv = getOrCreateSharedWebView()
            if (url.startsWith("blob:")) {
                val js = "(function() { fetch('$url').then(r => r.blob()).then(b => { var reader = new FileReader(); reader.onloadend = function() { AuraBlobBridge.saveBase64Video(reader.result, 'flow_video_' + Date.now() + '.mp4'); }; reader.readAsDataURL(b); }); })();"
                mainHandler.post { wv.evaluateJavascript(js, null) }
                return true
            }
            val rawName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val fileName = if (!rawName.contains(".")) "flow_video_${System.currentTimeMillis()}.mp4" else rawName

            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType("video/mp4")
                addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                setTitle(fileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "AutoVideos/$fileName")
            }
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun startOrStopLiveTeach(skillName: String = "GOOGLE_FLOW", onFinishedJson: ((String) -> Unit)? = null): Boolean {
        // ... (Keep existing implementation)
        return false // Stubbed for brevity here, keep your existing logic
    }

    suspend fun captureWebViewScreenshotBase64(): String? = suspendCancellableCoroutine { cont ->
        mainHandler.post {
            try {
                val wv = getOrCreateSharedWebView()
                val bmp = Bitmap.createBitmap(540, 960, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                canvas.drawColor(Color.parseColor("#090D16"))
                wv.draw(canvas)
                val baos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 68, baos)
                bmp.recycle()
                if (cont.isActive) cont.resume(Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP))
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    fun toggleDesktopMode(): Boolean {
        isDesktopMode = !isDesktopMode
        val wv = getOrCreateSharedWebView()
        wv.settings.userAgentString = if (isDesktopMode) "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36" else "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        wv.reload()
        return isDesktopMode
    }

    fun attachWebViewToContainer(targetContainer: FrameLayout) {
        mainHandler.post {
            val wv = getOrCreateSharedWebView()
            (wv.parent as? ViewGroup)?.removeView(wv)
            targetContainer.removeAllViews()
            targetContainer.addView(wv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            wv.requestFocus()
        }
    }

    fun collapseToCompactStage() { setPodStage(1) }
    fun openForFirstRunLiveCalibration() { if (currentStage == 1 || currentStage == 4) setPodStage(2) }

    // Idea 4: 1x1 Pixel Invisible Background Mode
    fun enableInvisible1x1StealthMode() {
        setPodStage(4)
    }

    fun show() {
        mainHandler.post {
            if (!isPodVisible) return@post
            if (isAdded && podRootLayout?.windowToken != null) return@post
            try {
                val canvasParams = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
                pointerCanvasView = NaxHandCursorCanvas(context).apply { visibility = View.GONE }
                windowManager.addView(pointerCanvasView, canvasParams)

                podLayoutParams = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = dpToPx(10); y = dpToPx(40) }
                podRootLayout = buildThreeStagePodUi()
                windowManager.addView(podRootLayout, podLayoutParams)
                isAdded = true
                currentStage = 1
                setSideBarExpanded(AutoTouchService.isJobRunning)
            } catch (_: Exception) { isAdded = false }
        }
    }

    fun hide() {
        mainHandler.post {
            try { pointerCanvasView?.let { windowManager.removeView(it) } } catch (_: Exception) {}
            try { podRootLayout?.let { windowManager.removeView(it) } } catch (_: Exception) {}
            isAdded = false
        }
    }

    fun togglePodVisibility(): Boolean {
        isPodVisible = !isPodVisible
        if (isPodVisible) show() else hide()
        return isPodVisible
    }

    private fun setSideBarExpanded(expanded: Boolean) {
        mainHandler.post { isSideBarExpanded = expanded; sideInfoContainer?.visibility = if (expanded) View.VISIBLE else View.GONE }
    }

    fun setPodStage(stage: Int) {
        mainHandler.post {
            currentStage = stage.coerceIn(1, 4)
            val lp = podLayoutParams ?: return@post

            when (currentStage) {
                1 -> { // Compact Normal
                    podWebFrame?.removeAllViews()
                    expandedContainer?.visibility = View.GONE
                    podRootLayout?.alpha = 1.0f
                    lp.width = WindowManager.LayoutParams.WRAP_CONTENT
                    lp.height = WindowManager.LayoutParams.WRAP_CONTENT
                    lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                }
                2 -> { // Mini Floating
                    setSideBarExpanded(true)
                    expandedContainer?.visibility = View.VISIBLE
                    podRootLayout?.alpha = 1.0f
                    lp.width = dpToPx(300)
                    lp.height = dpToPx(470)
                    lp.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    podWebFrame?.let { attachWebViewToContainer(it) }
                }
                3 -> { // Full Screen
                    setSideBarExpanded(true)
                    expandedContainer?.visibility = View.VISIBLE
                    podRootLayout?.alpha = 1.0f
                    lp.width = WindowManager.LayoutParams.MATCH_PARENT
                    lp.height = WindowManager.LayoutParams.MATCH_PARENT
                    lp.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    podWebFrame?.let { attachWebViewToContainer(it) }
                }
                4 -> { // Idea 4: 1x1 Pixel Invisible Background Mode (Tricks OS into keeping Webview fully active)
                    podWebFrame?.removeAllViews()
                    expandedContainer?.visibility = View.GONE
                    podRootLayout?.alpha = 0.01f // Invisible
                    lp.width = 1 // 1 pixel
                    lp.height = 1 // 1 pixel
                    lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                }
            }
            try { if (isAdded) windowManager.updateViewLayout(podRootLayout, lp) } catch (_: Exception) {}
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildThreeStagePodUi(): LinearLayout {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        compactRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(Color.parseColor("#D90F172A")); cornerRadius = dpToPx(24).toFloat(); setStroke(dpToPx(1), Color.parseColor("#5938BDF8")) }
            setPadding(dpToPx(3), dpToPx(2), dpToPx(5), dpToPx(2))
        }
        botAvatarView = NaxBotAvatarView(context).apply { layoutParams = LinearLayout.LayoutParams(dpToPx(46), dpToPx(46)) }
        sideInfoContainer = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dpToPx(5), 0, dpToPx(2), 0); visibility = View.GONE }
        
        val textCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dpToPx(4) } }
        tvPodTitle = TextView(context).apply { text = "Nax Bot"; setTextColor(Color.parseColor("#F0F9FF")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f); typeface = Typeface.DEFAULT_BOLD; maxLines = 1; maxWidth = dpToPx(112) }
        tvPodSubtitle = TextView(context).apply { text = "Ready"; setTextColor(Color.parseColor("#7DD3FC")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.8f); maxLines = 1; maxWidth = dpToPx(112) }
        textCol.addView(tvPodTitle); textCol.addView(tvPodSubtitle)

        btnQuickMiniBrowser = TextView(context).apply { text = "🌐"; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f); background = GradientDrawable().apply { setColor(Color.parseColor("#0284C7")); cornerRadius = dpToPx(10).toFloat() }; layoutParams = LinearLayout.LayoutParams(dpToPx(20), dpToPx(20)).apply { marginEnd = dpToPx(3) }; setOnClickListener { if (currentStage == 1) setPodStage(2) else setPodStage(1) } }
        btnQuickTeachToggle = TextView(context).apply { text = "🎓"; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f); background = GradientDrawable().apply { setColor(Color.parseColor("#D97706")); cornerRadius = dpToPx(10).toFloat() }; layoutParams = LinearLayout.LayoutParams(dpToPx(20), dpToPx(20)).apply { marginEnd = dpToPx(3) } }
        stopBadge = TextView(context).apply { text = "■"; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f); typeface = Typeface.DEFAULT_BOLD; background = GradientDrawable().apply { setColor(Color.parseColor("#E11D48")); cornerRadius = dpToPx(10).toFloat() }; layoutParams = LinearLayout.LayoutParams(dpToPx(20), dpToPx(20)); setOnClickListener { AutoTouchService.instance?.stopCurrentJob(); updateStatus("Nax Bot", "Stopped", "IDLE") } }

        sideInfoContainer?.addView(textCol); sideInfoContainer?.addView(btnQuickMiniBrowser); sideInfoContainer?.addView(btnQuickTeachToggle); sideInfoContainer?.addView(stopBadge)
        compactRow?.addView(botAvatarView); compactRow?.addView(sideInfoContainer)

        var initialX = 0; var initialY = 0; var initialTouchX = 0f; var initialTouchY = 0f; var isDragging = false
        botAvatarView?.setOnTouchListener { _, event ->
            val lp = podLayoutParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { initialX = lp.x; initialY = lp.y; initialTouchX = event.rawX; initialTouchY = event.rawY; isDragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt(); val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > dpToPx(6) || abs(dy) > dpToPx(6)) { isDragging = true; lp.x = initialX + dx; lp.y = initialY + dy; try { windowManager.updateViewLayout(podRootLayout, lp) } catch (_: Exception) {} }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!isDragging) { if (currentStage > 1) setPodStage(1) else setSideBarExpanded(!isSideBarExpanded) }; true }
                else -> false
            }
        }

        expandedContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; background = GradientDrawable().apply { setColor(Color.parseColor("#131B2E")); cornerRadius = dpToPx(16).toFloat(); setStroke(dpToPx(1), Color.parseColor("#5938BDF8")) }; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT).apply { topMargin = dpToPx(4) } }
        val headerBar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dpToPx(8), dpToPx(5), dpToPx(8), dpToPx(5)); setBackgroundColor(Color.parseColor("#192338")) }
        tvExpandedStepInfo = TextView(context).apply { text = "Nax Bot • Cognitive State"; setTextColor(Color.parseColor("#E0F2FE")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f); maxLines = 1; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        btnExpandFullToggle = TextView(context).apply { text = "⛶ Full"; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f); setPadding(dpToPx(8), dpToPx(3), dpToPx(8), dpToPx(3)); background = GradientDrawable().apply { setColor(Color.parseColor("#0284C7")); cornerRadius = dpToPx(8).toFloat() }; setOnClickListener { if (currentStage == 2) setPodStage(3) else setPodStage(2) } }
        val btnHideMini = TextView(context).apply { text = "━ Hide"; setTextColor(Color.parseColor("#CBD5E1")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f); setPadding(dpToPx(8), dpToPx(3), dpToPx(8), dpToPx(3)); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dpToPx(5) }; background = GradientDrawable().apply { setColor(Color.parseColor("#334155")); cornerRadius = dpToPx(8).toFloat() }; setOnClickListener { setPodStage(1) } }
        headerBar.addView(tvExpandedStepInfo); headerBar.addView(btnExpandFullToggle); headerBar.addView(btnHideMini)
        podWebFrame = FrameLayout(context).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f); setBackgroundColor(Color.parseColor("#090D16")) }
        expandedContainer?.addView(headerBar); expandedContainer?.addView(podWebFrame)
        root.addView(compactRow); root.addView(expandedContainer)
        return root
    }

    fun updateStatus(title: String, subtitle: String, emotion: String = "AUTO") {
        mainHandler.post {
            if (!isAdded) show()
            tvPodTitle?.text = title.take(20); tvPodSubtitle?.text = subtitle.take(24); tvExpandedStepInfo?.text = "$title • $subtitle"
            botAvatarView?.setEmotion(if (emotion == "AUTO") "WORKING" else emotion.uppercase())
            setSideBarExpanded(true)
            mainHandler.removeCallbacks(autoCollapseSideBarRunnable)
            if (!AutoTouchService.isJobRunning) mainHandler.postDelayed(autoCollapseSideBarRunnable, 4500L)
        }
    }

    fun getWebViewBoundsOnScreen(): Rect? {
        val wv = sharedWebView ?: return null
        if (!wv.isShown) return null
        val loc = IntArray(2); wv.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + wv.width, loc[1] + wv.height)
    }

    fun showTapEffect(x: Float, y: Float, label: String = "") { mainHandler.post { pointerCanvasView?.visibility = View.VISIBLE; pointerCanvasView?.triggerTapAnimation(x, y, label) } }
    fun moveBotAndShowTap(x: Float, y: Float, label: String = "") { showTapEffect(x, y, label) }
    fun showSwipeEffect(startX: Float, startY: Float, endX: Float, endY: Float) { mainHandler.post { pointerCanvasView?.visibility = View.VISIBLE; pointerCanvasView?.triggerSwipeAnimation(startX, startY, endX, endY) } }
    private fun dpToPx(dp: Int): Int { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), context.resources.displayMetrics).toInt() }
}

class NaxBotAvatarView(context: Context) : View(context) {
    // Basic Avatar View to keep snippet length manageable, keeping UI solid
    private var currentEmotion = "IDLE"
    fun setEmotion(emotion: String) { currentEmotion = emotion; invalidate() }
    override fun onDraw(canvas: Canvas) {
        val paint = Paint().apply { color = Color.parseColor("#F8FAFC"); style = Paint.Style.FILL }
        canvas.drawCircle(width/2f, height/2f, width/2.2f, paint)
        paint.color = Color.parseColor("#0F172A")
        canvas.drawCircle(width/3f, height/2f, 6f, paint)
        canvas.drawCircle(width*2/3f, height/2f, 6f, paint)
    }
}

class NaxHandCursorCanvas(context: Context) : View(context) {
    private var tapX = -1f; private var tapY = -1f
    fun triggerTapAnimation(x: Float, y: Float, label: String) { tapX = x; tapY = y; invalidate() }
    fun triggerSwipeAnimation(x1: Float, y1: Float, x2: Float, y2: Float) {}
    fun stopAnimations() { tapX = -1f; tapY = -1f; invalidate() }
    override fun onDraw(canvas: Canvas) {
        if (tapX >= 0f) {
            val p = Paint().apply { color = Color.parseColor("#2DD4BF"); style = Paint.Style.FILL }
            canvas.drawCircle(tapX, tapY, 20f, p)
        }
    }
}
