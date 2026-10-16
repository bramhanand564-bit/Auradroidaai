package com.auradroid.agent

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.graphics.*
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
import kotlin.math.cos
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

    // Live Teach Mode State (WM3-Style Touch & Step Recorder)
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

                settings.userAgentString =
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, true)

                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun saveBase64Video(base64Data: String, fileName: String) {
                        try {
                            val cleanB64 = base64Data.substringAfter(",")
                            val bytes = Base64.decode(cleanB64, Base64.DEFAULT)
                            val dir = File(
                                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                                "AutoVideos"
                            )
                            if (!dir.exists()) dir.mkdirs()
                            val outFile = File(dir, fileName)
                            FileOutputStream(outFile).use { it.write(bytes) }
                            VideoQueueManager.appendLog(context, "✅ Nax Bot Saved Video: ${outFile.name}")
                        } catch (e: Exception) {
                            VideoQueueManager.appendLog(context, "⚠️ Blob Save Error: ${e.message}")
                        }
                    }

                    @JavascriptInterface
                    fun onLiveTeachEvent(actionType: String, targetText: String, cssX: Int, cssY: Int) {
                        if (!isLiveTeachActive) return
                        try {
                            val stepNum = recordedTeachSteps.length() + 1
                            val stepObj = JSONObject().apply {
                                put("step", stepNum)
                                put("screen_identify", "Live recorded step #$stepNum")
                                put("action", actionType)
                                put("target", targetText.take(80))
                                put("cssX", cssX)
                                put("cssY", cssY)
                                put("verify_next", "Screen updates after $actionType on '$targetText'")
                            }
                            recordedTeachSteps.put(stepObj)
                            VideoQueueManager.appendLog(context, "🎓 Live Teach Step #$stepNum: $actionType -> '$targetText' ($cssX,$cssY)")
                            updateStatus("Teach #$stepNum", "$actionType: ${targetText.take(18)}", "EXCITED")
                        } catch (_: Exception) {}
                    }
                }, "AuraBlobBridge")

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        val lower = reqUrl.lowercase()
                        if ((lower.contains(".mp4") || lower.contains("videoplayback") || lower.contains("generated_video") || lower.contains("googleusercontent.com")) &&
                            !lower.endsWith(".js") && !lower.endsWith(".css") && !lower.endsWith(".png")
                        ) {
                            if (lower.contains(".mp4") || request?.requestHeaders?.get("Accept")?.contains("video") == true) {
                                lastSniffedMp4Url = reqUrl
                                lastSniffedTimestampMs = System.currentTimeMillis()
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        CookieManager.getInstance().flush()
                        url?.let { MainActivity.onBrowserUrlChanged?.invoke(it) }
                        injectLiveTeachHooks(view)
                    }
                }
                webChromeClient = WebChromeClient()

                setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                    enqueueDirectVideoDownload(url, userAgent, contentDisposition, mimeType)
                }

                measure(
                    View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY)
                )
                layout(0, 0, 540, 960)
                loadUrl(VideoQueueManager.getPinnedFlowUrl(context))
            }
            sharedWebView = wv
        }
        return sharedWebView!!
    }

    fun enqueueDirectVideoDownload(
        url: String,
        userAgent: String = "Mozilla/5.0",
        contentDisposition: String = "",
        mimeType: String = "video/mp4"
    ): Boolean {
        return try {
            val wv = getOrCreateSharedWebView()
            if (url.startsWith("blob:")) {
                val js = """
                    (function() {
                        fetch('$url').then(r => r.blob()).then(b => {
                            var reader = new FileReader();
                            reader.onloadend = function() {
                                AuraBlobBridge.saveBase64Video(reader.result, 'flow_video_' + Date.now() + '.mp4');
                            };
                            reader.readAsDataURL(b);
                        });
                    })();
                """.trimIndent()
                mainHandler.post { wv.evaluateJavascript(js, null) }
                return true
            }

            val rawName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val fileName = if (rawName.endsWith(".bin", true) || !rawName.contains(".")) {
                "flow_video_${System.currentTimeMillis()}.mp4"
            } else rawName

            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType.ifBlank { "video/mp4" })
                addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.let {
                    addRequestHeader("Cookie", it)
                }
                setTitle(fileName)
                setDescription("Nax Bot Downloading Video...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "AutoVideos/$fileName")
            }
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            VideoQueueManager.appendLog(context, "⬇️ Nax Bot Downloading MP4: $fileName")
            true
        } catch (e: Exception) {
            VideoQueueManager.appendLog(context, "⚠️ Download error: ${e.message}")
            false
        }
    }

    private fun injectLiveTeachHooks(webView: WebView?) {
        val js = """
            (function() {
                if (window.__naxTeachHooked) return;
                window.__naxTeachHooked = true;
                document.addEventListener('click', function(e) {
                    var el = e.target;
                    var txt = (el.innerText || el.placeholder || el.getAttribute('aria-label') || el.tagName || '').trim().substring(0, 60);
                    if (window.AuraBlobBridge && window.AuraBlobBridge.onLiveTeachEvent) {
                        window.AuraBlobBridge.onLiveTeachEvent('CLICK_EL', txt, Math.round(e.clientX), Math.round(e.clientY));
                    }
                }, true);
            })();
        """.trimIndent()
        webView?.evaluateJavascript(js, null)
    }

    fun startOrStopLiveTeach(
        skillName: String = "GOOGLE_FLOW",
        onFinishedJson: ((String) -> Unit)? = null
    ): Boolean {
        if (!isLiveTeachActive) {
            isLiveTeachActive = true
            currentTeachSkillName = skillName.uppercase().trim().ifEmpty { "GOOGLE_FLOW" }
            while (recordedTeachSteps.length() > 0) recordedTeachSteps.remove(0)
            onLiveTeachFinishedCallback = onFinishedJson
            mainHandler.post {
                btnQuickTeachToggle?.text = "💾"
                setPodStage(2)
                injectLiveTeachHooks(sharedWebView)
            }
            updateStatus("🎓 Teach ON", "Tap steps in browser, then tap 💾", "EXCITED")
            VideoQueueManager.appendLog(context, "🎓 Live Teach Recording started for '$currentTeachSkillName'!")
        } else {
            isLiveTeachActive = false
            val blueprintObj = JSONObject().apply {
                put("skill", currentTeachSkillName)
                put("description", "Live taught workflow by user on Nax Bot")
                put("steps", recordedTeachSteps)
            }
            val jsonStr = blueprintObj.toString(2)
            VideoQueueManager.saveTrainedSkillBlueprint(context, currentTeachSkillName, jsonStr, isVerified = true)
            mainHandler.post {
                btnQuickTeachToggle?.text = "🎓"
                onLiveTeachFinishedCallback?.invoke(jsonStr)
            }
            updateStatus("✅ Skill Saved!", "$currentTeachSkillName (${recordedTeachSteps.length()} steps)", "HAPPY")
            VideoQueueManager.appendLog(context, "💾 Live Teach Blueprint Saved & Verified for '$currentTeachSkillName'!")
        }
        return isLiveTeachActive
    }

    suspend fun captureWebViewScreenshotBase64(): String? = suspendCancellableCoroutine { cont ->
        mainHandler.post {
            try {
                val wv = getOrCreateSharedWebView()
                if (wv.width <= 10 || wv.height <= 10) {
                    wv.measure(
                        View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY)
                    )
                    wv.layout(0, 0, 540, 960)
                }

                val captureW = wv.width.coerceAtLeast(540)
                val captureH = wv.height.coerceAtLeast(960)

                val bmp = Bitmap.createBitmap(captureW, captureH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                canvas.drawColor(Color.parseColor("#121316"))
                wv.draw(canvas)

                val scaled = Bitmap.createScaledBitmap(bmp, 540, 960, true)
                if (scaled != bmp) bmp.recycle()

                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 68, baos)
                scaled.recycle()

                val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                if (cont.isActive) cont.resume(b64)
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    fun toggleDesktopMode(): Boolean {
        isDesktopMode = !isDesktopMode
        val wv = getOrCreateSharedWebView()
        wv.settings.userAgentString = if (isDesktopMode) {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        } else {
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        }
        wv.reload()
        return isDesktopMode
    }

    fun attachWebViewToContainer(targetContainer: FrameLayout) {
        mainHandler.post {
            val wv = getOrCreateSharedWebView()
            (wv.parent as? ViewGroup)?.removeView(wv)
            targetContainer.removeAllViews()
            targetContainer.addView(
                wv,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            wv.requestFocus()
        }
    }

    fun collapseToCompactStage() {
        setPodStage(1)
    }

    /**
     * Opens the live visible Mini Window automatically during the 1st calibration run
     * so screenshots and touch coordinates are 100% accurate before switching to Background mode!
     */
    fun openForFirstRunLiveCalibration() {
        if (currentStage == 1) {
            setPodStage(2)
        }
    }

    fun show() {
        mainHandler.post {
            if (!isPodVisible) return@post
            if (isAdded && podRootLayout?.windowToken != null) return@post

            try { pointerCanvasView?.let { windowManager.removeView(it) } } catch (_: Exception) {}
            try { podRootLayout?.let { windowManager.removeView(it) } } catch (_: Exception) {}

            try {
                val canvasParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                )
                pointerCanvasView = NaxHandCursorCanvas(context).apply { visibility = View.GONE }
                windowManager.addView(pointerCanvasView, canvasParams)

                podLayoutParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = dpToPx(12)
                    y = dpToPx(42)
                }

                podRootLayout = buildThreeStagePodUi()
                windowManager.addView(podRootLayout, podLayoutParams)
                isAdded = true
                currentStage = 1
                setSideBarExpanded(AutoTouchService.isJobRunning)
            } catch (e: Exception) {
                isAdded = false
            }
        }
    }

    fun hide() {
        mainHandler.post {
            mainHandler.removeCallbacks(autoCollapseSideBarRunnable)
            try { pointerCanvasView?.stopAnimations(); pointerCanvasView?.let { windowManager.removeView(it) } } catch (_: Exception) {}
            try { podWebFrame?.removeAllViews(); podRootLayout?.let { windowManager.removeView(it) } } catch (_: Exception) {}
            pointerCanvasView = null
            podRootLayout = null
            isAdded = false
            currentStage = 1
        }
    }

    fun togglePodVisibility(): Boolean {
        isPodVisible = !isPodVisible
        if (isPodVisible) { isAdded = false; show() } else { hide() }
        return isPodVisible
    }

    fun setCursorOverlayVisible(visible: Boolean) {
        mainHandler.post {
            pointerCanvasView?.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    // === PASTE PART B BELOW THIS LINE ===
    private fun setSideBarExpanded(expanded: Boolean) {
        mainHandler.post {
            isSideBarExpanded = expanded
            sideInfoContainer?.visibility = if (expanded) View.VISIBLE else View.GONE
        }
    }

    fun setPodStage(stage: Int) {
        mainHandler.post {
            currentStage = stage.coerceIn(1, 3)
            val lp = podLayoutParams ?: return@post

            when (currentStage) {
                1 -> {
                    // Stage 1: Compact Nax Bot + Sleek Mini-Notch
                    podWebFrame?.removeAllViews()
                    expandedContainer?.visibility = View.GONE
                    lp.width = WindowManager.LayoutParams.WRAP_CONTENT
                    lp.height = WindowManager.LayoutParams.WRAP_CONTENT
                    lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    btnQuickMiniBrowser?.text = "🌐"
                }
                2 -> {
                    // Stage 2: Floating Mini Browser Window (First-Run Live Calibration & Teach Mode)
                    setSideBarExpanded(true)
                    expandedContainer?.visibility = View.VISIBLE
                    lp.width = dpToPx(300)
                    lp.height = dpToPx(470)
                    lp.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    btnExpandFullToggle?.text = "⛶ Full"
                    btnQuickMiniBrowser?.text = "━"
                    podWebFrame?.let { frame ->
                        val wv = getOrCreateSharedWebView()
                        (wv.parent as? ViewGroup)?.removeView(wv)
                        frame.removeAllViews()
                        frame.addView(
                            wv,
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT
                            )
                        )
                        wv.requestFocus()
                    }
                }
                3 -> {
                    // Stage 3: Full Screen Interactive Browser Overlay
                    setSideBarExpanded(true)
                    expandedContainer?.visibility = View.VISIBLE
                    lp.width = WindowManager.LayoutParams.MATCH_PARENT
                    lp.height = WindowManager.LayoutParams.MATCH_PARENT
                    lp.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    btnExpandFullToggle?.text = "🗗 Mini"
                    btnQuickMiniBrowser?.text = "━"
                    podWebFrame?.let { frame ->
                        val wv = getOrCreateSharedWebView()
                        (wv.parent as? ViewGroup)?.removeView(wv)
                        frame.removeAllViews()
                        frame.addView(
                            wv,
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT
                            )
                        )
                        wv.requestFocus()
                    }
                }
            }
            try {
                if (isAdded && podRootLayout != null) {
                    windowManager.updateViewLayout(podRootLayout, lp)
                }
            } catch (_: Exception) {}
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildThreeStagePodUi(): LinearLayout {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Top Compact Row: Nax Bot (Perfect 46dp size) + Sleek Smaller Mini-Notch
        compactRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E614171F"))
                cornerRadius = dpToPx(24).toFloat()
                setStroke(dpToPx(1), Color.parseColor("#334155"))
            }
            setPadding(dpToPx(4), dpToPx(3), dpToPx(6), dpToPx(3))
        }

        // Cute Original Nax Bot Avatar (46dp perfect size)
        botAvatarView = NaxBotAvatarView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(46), dpToPx(46))
        }

        // Sleek Smaller Notch Container (Reduced width & compact padding)
        sideInfoContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(6), 0, dpToPx(2), 0)
            visibility = View.GONE
        }

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dpToPx(5)
            }
        }

        tvPodTitle = TextView(context).apply {
            text = "Nax Bot"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            maxWidth = dpToPx(125)
        }

        tvPodSubtitle = TextView(context).apply {
            text = "Ready"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.2f)
            maxLines = 1
            maxWidth = dpToPx(125)
        }

        textCol.addView(tvPodTitle)
        textCol.addView(tvPodSubtitle)

        // Compact Mini Action Buttons inside the smaller notch
        btnQuickMiniBrowser = TextView(context).apply {
            text = "🌐"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2563EB"))
                cornerRadius = dpToPx(11).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dpToPx(22), dpToPx(22)).apply {
                marginEnd = dpToPx(4)
            }
            setOnClickListener {
                if (currentStage == 1) setPodStage(2) else setPodStage(1)
            }
        }

        btnQuickTeachToggle = TextView(context).apply {
            text = "🎓"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#D97706"))
                cornerRadius = dpToPx(11).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dpToPx(22), dpToPx(22)).apply {
                marginEnd = dpToPx(4)
            }
            setOnClickListener {
                startOrStopLiveTeach("GOOGLE_FLOW")
            }
        }

        stopBadge = TextView(context).apply {
            text = "■"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EF4444"))
                cornerRadius = dpToPx(11).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dpToPx(22), dpToPx(22))
            setOnClickListener {
                AutoTouchService.instance?.stopCurrentJob()
                updateStatus("Nax Bot", "Stopped", "IDLE")
            }
        }

        sideInfoContainer?.addView(textCol)
        sideInfoContainer?.addView(btnQuickMiniBrowser)
        sideInfoContainer?.addView(btnQuickTeachToggle)
        sideInfoContainer?.addView(stopBadge)

        compactRow?.addView(botAvatarView)
        compactRow?.addView(sideInfoContainer)

        // Drag + Tap handler on Nax Bot avatar
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        botAvatarView?.setOnTouchListener { _, event ->
            val lp = podLayoutParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = lp.x
                    initialY = lp.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > dpToPx(6) || abs(dy) > dpToPx(6)) {
                        isDragging = true
                        lp.x = initialX + dx
                        lp.y = initialY + dy
                        try { windowManager.updateViewLayout(podRootLayout, lp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        if (currentStage > 1) {
                            setPodStage(1)
                        } else {
                            setSideBarExpanded(!isSideBarExpanded)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        // Stage 2 / Stage 3 Expanded Browser Container
        expandedContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#14171F"))
                cornerRadius = dpToPx(14).toFloat()
                setStroke(dpToPx(1), Color.parseColor("#334155"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            ).apply {
                topMargin = dpToPx(4)
            }
        }

        val headerBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(8), dpToPx(5), dpToPx(8), dpToPx(5))
            setBackgroundColor(Color.parseColor("#1E222D"))
        }

        tvExpandedStepInfo = TextView(context).apply {
            text = "Nax Bot Live Studio"
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        btnExpandFullToggle = TextView(context).apply {
            text = "⛶ Full"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setPadding(dpToPx(8), dpToPx(3), dpToPx(8), dpToPx(3))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2563EB"))
                cornerRadius = dpToPx(8).toFloat()
            }
            setOnClickListener {
                if (currentStage == 2) setPodStage(3) else setPodStage(2)
            }
        }

        val btnHideMini = TextView(context).apply {
            text = "━ Hide"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setPadding(dpToPx(8), dpToPx(3), dpToPx(8), dpToPx(3))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dpToPx(5)
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#334155"))
                cornerRadius = dpToPx(8).toFloat()
            }
            setOnClickListener { setPodStage(1) }
        }

        headerBar.addView(tvExpandedStepInfo)
        headerBar.addView(btnExpandFullToggle)
        headerBar.addView(btnHideMini)

        podWebFrame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setBackgroundColor(Color.parseColor("#0D0E11"))
        }

        expandedContainer?.addView(headerBar)
        expandedContainer?.addView(podWebFrame)

        root.addView(compactRow)
        root.addView(expandedContainer)
        return root
    }

    fun updateStatus(title: String, subtitle: String, emotion: String = "AUTO") {
        mainHandler.post {
            if (!isAdded) show()
            tvPodTitle?.text = title.take(22)
            tvPodSubtitle?.text = subtitle.take(26)
            tvExpandedStepInfo?.text = "$title • $subtitle"

            val resolvedEmotion = if (emotion == "AUTO") {
                inferEmotionFromStatus(title, subtitle)
            } else {
                emotion.uppercase()
            }
            botAvatarView?.setEmotion(resolvedEmotion)

            setSideBarExpanded(true)
            mainHandler.removeCallbacks(autoCollapseSideBarRunnable)
            if (!AutoTouchService.isJobRunning && !isLiveTeachActive) {
                mainHandler.postDelayed(autoCollapseSideBarRunnable, 5000L)
            }
        }
    }

    private fun inferEmotionFromStatus(title: String, subtitle: String): String {
        val combined = "$title$subtitle".lowercase()
        return when {
            combined.contains("error") || combined.contains("fail") || combined.contains("blocked") -> "ERROR"
            combined.contains("timeout") || combined.contains("sad") || combined.contains("retry") -> "SAD"
            combined.contains("high") || combined.contains("pro") || combined.contains("3.8-pro") -> "HIGH_BRAIN"
            combined.contains("verify") || combined.contains("check") || combined.contains(" stuck") -> "CONFUSED"
            combined.contains("search") || combined.contains("scan") || combined.contains("find") -> "SEARCHING"
            combined.contains("download") || combined.contains("saving") || combined.contains("⬇️") -> "DOWNLOADING"
            combined.contains("upload") || combined.contains("youtube") -> "UPLOADING"
            combined.contains("render") || combined.contains("%") || combined.contains("wait") -> "RENDERING"
            combined.contains("think") || combined.contains("plan") || combined.contains("brain") -> "THINKING"
            combined.contains("teach") || combined.contains("story") || combined.contains("✨") -> "EXCITED"
            combined.contains("done") || combined.contains("complete") || combined.contains("saved") || combined.contains("✅") -> "HAPPY"
            combined.contains("surprise") || combined.contains("new") || combined.contains("alert") -> "SURPRISED"
            combined.contains("sleep") || combined.contains("cooldown") -> "SLEEPY"
            combined.contains("idle") || combined.contains("ready") || combined.contains("stop") -> "IDLE"
            else -> "WORKING"
        }
    }

    fun getWebViewBoundsOnScreen(): Rect? {
        val wv = sharedWebView ?: return null
        if (!wv.isShown) return null
        val loc = IntArray(2)
        wv.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + wv.width, loc[1] + wv.height)
    }

    fun showTapEffect(x: Float, y: Float, label: String = "") {
        mainHandler.post {
            pointerCanvasView?.visibility = View.VISIBLE
            pointerCanvasView?.triggerTapAnimation(x, y, label)
        }
    }

    fun showSwipeEffect(startX: Float, startY: Float, endX: Float, endY: Float) {
        mainHandler.post {
            pointerCanvasView?.visibility = View.VISIBLE
            pointerCanvasView?.triggerSwipeAnimation(startX, startY, endX, endY)
        }
    }

    fun moveBotAndShowTap(x: Float, y: Float, label: String) {
        showTapEffect(x, y, label)
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
}

/**
 * Original Cute White Marshmallow "Nax Bot" Avatar View (Baymax-inspired soft round face)
 * Features 4x large solid color-changing eyes and 15 rich expressive emotions!
 */
class NaxBotAvatarView(context: Context) : View(context) {
    private var currentEmotion = "IDLE"
    private var animPhase = 0f
    private var blinkFactor = 1f
    private var lastBlinkTime = System.currentTimeMillis()

    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#F8FAFC")
    }
    private val headRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.parseColor("#CBD5E1")
    }
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bridgeLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f
        strokeCap = Paint.Cap.ROUND
    }
    private val browPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
    }
    private val blushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#66FB7185")
    }
    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
    }
    private val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.8f
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#334155")
    }
    private val mouthPath = Path()
    private val pulseAnimator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
        duration = 2200L
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { animator ->
            animPhase = animator.animatedValue as Float
            val elapsed = System.currentTimeMillis() - lastBlinkTime
            blinkFactor = if (elapsed in 3400L..3550L) {
                0.12f
            } else {
                if (elapsed > 3550L) lastBlinkTime = System.currentTimeMillis()
                1f
            }
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!pulseAnimator.isStarted) pulseAnimator.start()
    }

    override fun onDetachedFromWindow() {
        pulseAnimator.cancel()
        super.onDetachedFromWindow()
    }

    fun setEmotion(emotion: String) {
        currentEmotion = emotion.trim().uppercase().ifBlank { "IDLE" }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w / 2f
        val cy = h / 2f + sin(animPhase.toDouble()).toFloat() * 1.2f
        val headRx = w * 0.44f
        val headRy = h * 0.40f
        val eyeColor = when (currentEmotion) {
            "THINKING", "SEARCHING" -> Color.parseColor("#2563EB")
            "WORKING", "UPLOADING" -> Color.parseColor("#10B981")
            "RENDERING", "WAITING", "DOWNLOADING" -> Color.parseColor("#F59E0B")
            "HAPPY", "DONE" -> Color.parseColor("#059669")
            "EXCITED" -> Color.parseColor("#06B6D4")
            "CONFUSED", "VERIFYING", "SURPRISED" -> Color.parseColor("#F97316")
            "HIGH_BRAIN", "PRO" -> Color.parseColor("#9333EA")
            "ERROR", "ANGRY" -> Color.parseColor("#DC2626")
            "SAD", "TIMEOUT" -> Color.parseColor("#1D4ED8")
            "SLEEPY", "COOLDOWN" -> Color.parseColor("#475569")
            else -> Color.parseColor("#0F172A")
        }

        if (currentEmotion != "IDLE") {
            auraPaint.color = eyeColor
            auraPaint.alpha = 95
            canvas.drawRoundRect(
                RectF(cx - headRx - 2f, cy - headRy - 2f, cx + headRx + 2f, cy + headRy + 2f),
                headRy, headRy, auraPaint
            )
        }

        val headRect = RectF(cx - headRx, cy - headRy, cx + headRx, cy + headRy)
        canvas.drawRoundRect(headRect, headRy, headRy, headPaint)
        headRimPaint.color = if (currentEmotion == "IDLE") Color.parseColor("#CBD5E1") else eyeColor
        canvas.drawRoundRect(headRect, headRy, headRy, headRimPaint)

        val eyeOffsetX = when (currentEmotion) {
            "SEARCHING" -> sin(animPhase.toDouble() * 2).toFloat() * w * 0.025f
            "CONFUSED" -> w * 0.015f
            else -> 0f
        }
        val eyeOffsetY = when (currentEmotion) {
            "SAD", "TIMEOUT" -> h * 0.035f
            "THINKING" -> -h * 0.025f
            else -> 0f
        }
        val eyeRadius = minOf(w * 0.078f, h * 0.125f)
        val eyeY = cy - h * 0.025f + eyeOffsetY
        eyePaint.color = eyeColor
        val visibleEyeRadiusY = eyeRadius * blinkFactor.coerceIn(0.12f, 1f)
        canvas.drawOval(
            RectF(cx - w * 0.18f - eyeRadius + eyeOffsetX, eyeY - visibleEyeRadiusY,
                cx - w * 0.18f + eyeRadius + eyeOffsetX, eyeY + visibleEyeRadiusY), eyePaint
        )
        canvas.drawOval(
            RectF(cx + w * 0.18f - eyeRadius + eyeOffsetX, eyeY - visibleEyeRadiusY,
                cx + w * 0.18f + eyeRadius + eyeOffsetX, eyeY + visibleEyeRadiusY), eyePaint
        )
        bridgeLinePaint.color = eyeColor
        canvas.drawLine(cx - w * 0.07f, eyeY, cx + w * 0.07f, eyeY, bridgeLinePaint)

        if (currentEmotion in setOf("HAPPY", "DONE", "EXCITED", "SURPRISED")) {
            blushPaint.alpha = 145
            canvas.drawCircle(cx - w * 0.29f, cy + h * 0.14f, minOf(w, h) * 0.055f, blushPaint)
            canvas.drawCircle(cx + w * 0.29f, cy + h * 0.14f, minOf(w, h) * 0.055f, blushPaint)
        }

        browPaint.color = eyeColor
        if (currentEmotion in setOf("ANGRY", "CONFUSED", "ERROR")) {
            canvas.drawLine(cx - w * 0.27f, eyeY - eyeRadius * 1.5f,
                cx - w * 0.10f, eyeY - eyeRadius * 1.9f, browPaint)
            canvas.drawLine(cx + w * 0.10f, eyeY - eyeRadius * 1.9f,
                cx + w * 0.27f, eyeY - eyeRadius * 1.5f, browPaint)
        }

        mouthPath.reset()
        val mouthY = cy + h * 0.20f
        val mouthWidth = w * 0.105f
        when (currentEmotion) {
            "SAD", "TIMEOUT", "ERROR", "ANGRY" -> {
                mouthPath.moveTo(cx - mouthWidth, mouthY + h * 0.035f)
                mouthPath.quadTo(cx, mouthY - h * 0.055f, cx + mouthWidth, mouthY + h * 0.035f)
            }
            "SURPRISED", "CONFUSED" -> {
                canvas.drawCircle(cx, mouthY, minOf(w, h) * 0.045f, mouthPaint)
            }
            "HAPPY", "DONE", "EXCITED" -> {
                mouthPath.moveTo(cx - mouthWidth, mouthY - h * 0.005f)
                mouthPath.quadTo(cx, mouthY + h * 0.085f, cx + mouthWidth, mouthY - h * 0.005f)
            }
            else -> {
                mouthPath.moveTo(cx - mouthWidth * 0.7f, mouthY)
                mouthPath.quadTo(cx, mouthY + h * 0.018f, cx + mouthWidth * 0.7f, mouthY)
            }
        }
        canvas.drawPath(mouthPath, mouthPaint)
    }
}

private class NaxHandCursorCanvas(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F8FAFC"); textSize = 27f; typeface = Typeface.DEFAULT_BOLD }
    private val screenOffset = IntArray(2)

    private var handX = 540f; private var handY = 400f; private var startX = 540f; private var startY = 400f
    private var targetX = 540f; private var targetY = 400f; private var rippleProgress = 0f; private var pressScale = 1f
    private var phase = 0f; private var currentLabel = ""; private var handMood = "WORKING"

    private var moveAnimator: ValueAnimator? = null
    private var rippleAnimator: ValueAnimator? = null

    private val ticker = object : Runnable {
        override fun run() { phase += 0.2f; invalidate(); postDelayed(this, 40) }
    }

    init { post(ticker) }

    fun setHandMood(mood: String) { handMood = mood.uppercase(); invalidate() }

    fun animateHandToTap(destX: Float, destY: Float, label: String) {
        startX = handX; startY = handY; targetX = destX; targetY = destY; currentLabel = label
        moveAnimator?.cancel()
        moveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 340; interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                handX = startX + (targetX - startX) * f; handY = startY + (targetY - startY) * f
                invalidate()
            }
            start()
        }
        rippleAnimator?.cancel()
        rippleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = 260; duration = 620
            addUpdateListener { anim ->
                val p = anim.animatedValue as Float; rippleProgress = p
                pressScale = if (p < 0.35f) 1f - (p / 0.35f) * 0.16f else 0.84f + ((p - 0.35f) / 0.65f) * 0.16f
                invalidate()
            }
            start()
        }
    }

    fun triggerTapAnimation(destX: Float, destY: Float, label: String) {
        animateHandToTap(destX, destY, label)
    }

    fun triggerSwipeAnimation(startX: Float, startY: Float, endX: Float, endY: Float) {
        handX = startX
        handY = startY
        animateHandToTap(endX, endY, "Swipe")
    }

    fun stopAnimations() { removeCallbacks(ticker); moveAnimator?.cancel(); rippleAnimator?.cancel() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (currentLabel.isEmpty()) return
        getLocationOnScreen(screenOffset)
        val ox = screenOffset[0].toFloat(); val oy = screenOffset[1].toFloat()
        val tipX = handX - ox; val tipY = (handY - oy) + sin(phase * 0.8f) * 2.5f

        val accentColor = when (handMood) { "ERROR", "SAD" -> Color.parseColor("#EF4444"); "DONE", "HAPPY" -> Color.parseColor("#10B981"); else -> Color.parseColor("#00E5FF") }

        if (rippleProgress > 0f && rippleProgress < 1f) {
            val alpha = ((1f - rippleProgress) * 210).toInt().coerceIn(0, 255)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 4.5f; paint.color = Color.argb(alpha, 0, 229, 255)
            canvas.drawCircle(targetX - ox, targetY - oy, 12f + rippleProgress * 62f, paint)
        }

        canvas.save(); canvas.translate(tipX, tipY); canvas.scale(pressScale, pressScale)
        paint.style = Paint.Style.FILL; paint.color = Color.argb(55, 0, 0, 0)
        canvas.drawRoundRect(RectF(-22f, 20f, 34f, 74f), 26f, 26f, paint)

        val handFillColor = Color.parseColor("#F8FAFC"); val handStrokeColor = Color.parseColor("#334155")
        val thumbRect = RectF(-34f, 28f, -6f, 52f)
        paint.style = Paint.Style.FILL; paint.color = handFillColor
        canvas.rotate(-22f, -20f, 40f); canvas.drawRoundRect(thumbRect, 14f, 14f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.6f; paint.color = handStrokeColor
        canvas.drawRoundRect(thumbRect, 14f, 14f, paint); canvas.rotate(22f, -20f, 40f)

        val midFingerRect = RectF(6f, 10f, 22f, 38f); val ringFingerRect = RectF(18f, 16f, 33f, 42f)
        paint.style = Paint.Style.FILL; paint.color = handFillColor
        canvas.drawRoundRect(ringFingerRect, 10f, 10f, paint); paint.style = Paint.Style.STROKE; paint.color = handStrokeColor; canvas.drawRoundRect(ringFingerRect, 10f, 10f, paint)
        paint.style = Paint.Style.FILL; paint.color = handFillColor
        canvas.drawRoundRect(midFingerRect, 10f, 10f, paint); paint.style = Paint.Style.STROKE; paint.color = handStrokeColor; canvas.drawRoundRect(midFingerRect, 10f, 10f, paint)

        val indexFingerRect = RectF(-9f, -2f, 10f, 36f)
        paint.style = Paint.Style.FILL; paint.color = handFillColor
        canvas.drawRoundRect(indexFingerRect, 11f, 11f, paint); paint.style = Paint.Style.STROKE; paint.color = handStrokeColor; canvas.drawRoundRect(indexFingerRect, 11f, 11f, paint)

        val palmRect = RectF(-20f, 22f, 32f, 70f)
        paint.style = Paint.Style.FILL; paint.color = handFillColor; canvas.drawRoundRect(palmRect, 24f, 24f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.6f; paint.color = handStrokeColor; canvas.drawRoundRect(palmRect, 24f, 24f, paint)

        val cuffRect = RectF(-12f, 64f, 24f, 75f)
        paint.style = Paint.Style.FILL; paint.color = Color.parseColor("#E2E8F0"); canvas.drawRoundRect(cuffRect, 8f, 8f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; paint.color = handStrokeColor; canvas.drawRoundRect(cuffRect, 8f, 8f, paint)

        paint.style = Paint.Style.FILL; paint.color = accentColor
        canvas.drawCircle(6f, 46f, 4.5f, paint); canvas.drawCircle(0f, 0f, 4f, paint)
        canvas.restore()

        val textWidth = textPaint.measureText(currentLabel)
        val tagLeft = (tipX + 42f).coerceAtMost(width - textWidth - 34f).coerceAtLeast(16f)
        val tagTop = (tipY + 18f).coerceAtMost(height - 70f).coerceAtLeast(50f)
        val tagRect = RectF(tagLeft, tagTop, tagLeft + textWidth + 24f, tagTop + 40f)

        paint.style = Paint.Style.FILL; paint.color = Color.parseColor("#E6181A20"); canvas.drawRoundRect(tagRect, 14f, 14f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.8f; paint.color = accentColor; canvas.drawRoundRect(tagRect, 14f, 14f, paint)
        canvas.drawText(currentLabel, tagLeft + 12f, tagTop + 28f, textPaint)
    }
}
