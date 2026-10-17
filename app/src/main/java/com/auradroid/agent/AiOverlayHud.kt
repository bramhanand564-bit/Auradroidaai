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

    // Live Teach Mode State (WM3-Style Touch & Step Recorder with 1s AI Confirmation)
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
                            val prefs = context.getSharedPreferences(VideoQueueManager.PREFS_NAME, Context.MODE_PRIVATE)
                            val defaultFolder = File(
                                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                                "AutoVideos"
                            ).absolutePath
                            val targetFolder = prefs.getString("folder_path", defaultFolder) ?: defaultFolder
                            val dir = File(targetFolder)
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
                                put("confirm_after_1s", "AI confirms screen updated 1s after $actionType on '$targetText'")
                            }
                            recordedTeachSteps.put(stepObj)
                            VideoQueueManager.appendLog(
                                context,
                                "🎓 Live Teach Step #$stepNum: $actionType -> '$targetText' ($cssX,$cssY)"
                            )
                            updateStatus("Teach #$stepNum", "$actionType: ${targetText.take(16)}", "EXCITED")
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
            updateStatus("🎓 Teach ON", "Tap steps, then tap 💾", "EXCITED")
            VideoQueueManager.appendLog(context, "🎓 Live Teach Recording started for '$currentTeachSkillName'!")
        } else {
            isLiveTeachActive = false
            val blueprintObj = JSONObject().apply {
                put("skill", currentTeachSkillName)
                put("execution_policy", "AI_1S_STEP_CONFIRMED_GUIDE_NEVER_SKIP_BLINDLY")
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
                canvas.drawColor(Color.parseColor("#090D16"))
                wv.draw(canvas)

                val scaled = Bitmap.createScaledBitmap(bmp, 540, 960, true)
                if (scaled != bmp) bmp.recycle()

                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 68, baos)
                scaled.recycle()

                val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                if (cont.isActive) cont.resume(b64)
            } catch (_: Exception) {
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
                    x = dpToPx(10)
                    y = dpToPx(40)
                }

                podRootLayout = buildThreeStagePodUi()
                windowManager.addView(podRootLayout, podLayoutParams)
                isAdded = true
                currentStage = 1
                setSideBarExpanded(AutoTouchService.isJobRunning)
            } catch (_: Exception) {
                isAdded = false
            }
        }
    }

    fun hide() {
        mainHandler.post {
            mainHandler.removeCallbacks(autoCollapseSideBarRunnable)
            try {
                pointerCanvasView?.stopAnimations()
                pointerCanvasView?.let { windowManager.removeView(it) }
            } catch (_: Exception) {}
            try {
                podWebFrame?.removeAllViews()
                podRootLayout?.let { windowManager.removeView(it) }
            } catch (_: Exception) {}
            pointerCanvasView = null
            podRootLayout = null
            isAdded = false
            currentStage = 1
        }
    }

    fun togglePodVisibility(): Boolean {
        isPodVisible = !isPodVisible
        if (isPodVisible) {
            isAdded = false
            show()
        } else {
            hide()
        }
        return isPodVisible
    }

    fun setCursorOverlayVisible(visible: Boolean) {
        mainHandler.post {
            pointerCanvasView?.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

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

        // AquaBubble Liquid Glass Compact Row: Nax Bot (46dp) + Smaller Sleek Mini-Notch
        compactRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#D90F172A"))
                cornerRadius = dpToPx(24).toFloat()
                setStroke(dpToPx(1), Color.parseColor("#5938BDF8"))
            }
            setPadding(dpToPx(3), dpToPx(2), dpToPx(5), dpToPx(2))
        }

        botAvatarView = NaxBotAvatarView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(46), dpToPx(46))
        }

        // Sleek Compact Mini-Notch Container
        sideInfoContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(5), 0, dpToPx(2), 0)
            visibility = View.GONE
        }

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dpToPx(4)
            }
        }

        tvPodTitle = TextView(context).apply {
            text = "Nax Bot"
            setTextColor(Color.parseColor("#F0F9FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            maxWidth = dpToPx(112)
        }

        tvPodSubtitle = TextView(context).apply {
            text = "Ready"
            setTextColor(Color.parseColor("#7DD3FC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.8f)
            maxLines = 1
            maxWidth = dpToPx(112)
        }

        textCol.addView(tvPodTitle)
        textCol.addView(tvPodSubtitle)

        btnQuickMiniBrowser = TextView(context).apply {
            text = "🌐"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0284C7"))
                cornerRadius = dpToPx(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dpToPx(20), dpToPx(20)).apply {
                marginEnd = dpToPx(3)
            }
            setOnClickListener {
                if (currentStage == 1) setPodStage(2) else setPodStage(1)
            }
        }

        btnQuickTeachToggle = TextView(context).apply {
            text = "🎓"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#D97706"))
                cornerRadius = dpToPx(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dpToPx(20), dpToPx(20)).apply {
                marginEnd = dpToPx(3)
            }
            setOnClickListener {
                startOrStopLiveTeach("GOOGLE_FLOW")
            }
        }

        stopBadge = TextView(context).apply {
            text = "■"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E11D48"))
                cornerRadius = dpToPx(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dpToPx(20), dpToPx(20))
            setOnClickListener {
                AutoTouchService.instance?.stopCurrentJob()
                WebPipelineManager.stopCurrentJob()
                updateStatus("Nax Bot", "Stopped", "IDLE")
            }
        }

        sideInfoContainer?.addView(textCol)
        sideInfoContainer?.addView(btnQuickMiniBrowser)
        sideInfoContainer?.addView(btnQuickTeachToggle)
        sideInfoContainer?.addView(stopBadge)

        compactRow?.addView(botAvatarView)
        compactRow?.addView(sideInfoContainer)

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

        expandedContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#131B2E"))
                cornerRadius = dpToPx(16).toFloat()
                setStroke(dpToPx(1), Color.parseColor("#5938BDF8"))
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
            setBackgroundColor(Color.parseColor("#192338"))
        }

        tvExpandedStepInfo = TextView(context).apply {
            text = "Nax Bot • 1s AI Confirmed Studio"
            setTextColor(Color.parseColor("#E0F2FE"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        btnExpandFullToggle = TextView(context).apply {
            text = "⛶ Full"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(dpToPx(8), dpToPx(3), dpToPx(8), dpToPx(3))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0284C7"))
                cornerRadius = dpToPx(8).toFloat()
            }
            setOnClickListener {
                if (currentStage == 2) setPodStage(3) else setPodStage(2)
            }
        }

        val btnHideMini = TextView(context).apply {
            text = "━ Hide"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
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
            setBackgroundColor(Color.parseColor("#090D16"))
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
            tvPodTitle?.text = title.take(20)
            tvPodSubtitle?.text = subtitle.take(24)
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
                mainHandler.postDelayed(autoCollapseSideBarRunnable, 4500L)
            }
        }
    }

    private fun inferEmotionFromStatus(title: String, subtitle: String): String {
        val combined = "$title $subtitle".lowercase()
        return when {
            combined.contains("error") || combined.contains("fail") || combined.contains("blocked") -> "ERROR"
            combined.contains("timeout") || combined.contains("sad") || combined.contains("retry") -> "SAD"
            combined.contains("high") || combined.contains("pro") || combined.contains("sense-4") -> "HIGH_BRAIN"
            combined.contains("verify") || combined.contains("confirm") || combined.contains("stuck") -> "CONFUSED"
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

    /**
     * Required by AutoTouchService to display tap animation and update Nax Bot state
     */
    fun moveBotAndShowTap(x: Float, y: Float, label: String = "") {
        showTapEffect(x, y, label)
    }

    fun showSwipeEffect(startX: Float, startY: Float, endX: Float, endY: Float) {
        mainHandler.post {
            pointerCanvasView?.visibility = View.VISIBLE
            pointerCanvasView?.triggerSwipeAnimation(startX, startY, endX, endY)
        }
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
        strokeWidth = 3.5f
        color = Color.parseColor("#CBD5E1")
    }

    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#0F172A")
    }

    private val bridgeLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.2f
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#0F172A")
    }

    private val browPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }

    private val blushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#88FB7185")
    }

    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
    }

    private val mouthPath = Path()

    private val pulseAnimator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
        duration = 2200L
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            animPhase = it.animatedValue as Float
            val now = System.currentTimeMillis()
            if (now - lastBlinkTime > 3400L) {
                val elapsed = now - lastBlinkTime - 3400L
                blinkFactor = if (elapsed < 160L) {
                    0.12f
                } else {
                    lastBlinkTime = now
                    1f
                }
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
        currentEmotion = emotion.uppercase()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w / 2f
        val cy = h / 2f + sin(animPhase.toDouble()).toFloat() * 1.6f
        val headRx = w * 0.44f
        val headRy = h * 0.40f

        val solidEyeColor = when (currentEmotion) {
            "IDLE" -> Color.parseColor("#0F172A")
            "THINKING" -> Color.parseColor("#2563EB")
            "WORKING" -> Color.parseColor("#10B981")
            "RENDERING", "WAITING" -> Color.parseColor("#F59E0B")
            "HAPPY", "DONE" -> Color.parseColor("#059669")
            "EXCITED" -> Color.parseColor("#06B6D4")
            "CONFUSED", "VERIFYING" -> Color.parseColor("#F97316")
            "HIGH_BRAIN", "PRO" -> Color.parseColor("#9333EA")
            "SEARCHING" -> Color.parseColor("#0284C7")
            "DOWNLOADING" -> Color.parseColor("#14B8A6")
            "UPLOADING" -> Color.parseColor("#22C55E")
            "SURPRISED" -> Color.parseColor("#3B82F6")
            "ERROR", "ANGRY" -> Color.parseColor("#DC2626")
            "SAD", "TIMEOUT" -> Color.parseColor("#1D4ED8")
            "SLEEPY", "COOLDOWN" -> Color.parseColor("#475569")
            else -> Color.parseColor("#0F172A")
        }

        if (currentEmotion != "IDLE") {
            auraPaint.color = solidEyeColor
            auraPaint.alpha = 115
            val auraRect = RectF(cx - headRx - 2f, cy - headRy - 2f, cx + headRx + 2f, cy + headRy + 2f)
            canvas.drawRoundRect(auraRect, headRy, headRy, auraPaint)
        }

        val headRect = RectF(cx - headRx, cy - headRy, cx + headRx, cy + headRy)
        canvas.drawRoundRect(headRect, headRy, headRy, headPaint)
        headRimPaint.color = if (currentEmotion == "IDLE") Color.parseColor("#CBD5E1") else solidEyeColor
        canvas.drawRoundRect(headRect, headRy, headRy, headRimPaint)

        var eyeOffsetX = 0f
        var eyeOffsetY = 0f
        when (currentEmotion) {
            "THINKING" -> {
                eyeOffsetX = sin(animPhase.toDouble()).toFloat() * 2.2f
                eyeOffsetY = -2.2f
            }
            "SEARCHING" -> {
                eyeOffsetX = sin(animPhase * 2.0).toFloat() * 4.0f
            }
            "DOWNLOADING" -> {
                eyeOffsetY = 2.8f
            }
            "UPLOADING" -> {
                eyeOffsetY = -2.8f
            }
        }

        val leftEyeX = cx - w * 0.19f + eyeOffsetX
        val rightEyeX = cx + w * 0.19f + eyeOffsetX
        val eyeY = cy - h * 0.02f + eyeOffsetY
        val baseEyeRadius = w * 0.105f

        eyePaint.color = solidEyeColor
        bridgeLinePaint.color = solidEyeColor
        browPaint.color = solidEyeColor

        canvas.drawLine(
            leftEyeX + baseEyeRadius * 0.6f,
            eyeY,
            rightEyeX - baseEyeRadius * 0.6f,
            eyeY,
            bridgeLinePaint
        )

        when (currentEmotion) {
            "HAPPY", "DONE" -> {
                browPaint.strokeWidth = 5.2f
                val leftArc = RectF(
                    leftEyeX - baseEyeRadius,
                    eyeY - baseEyeRadius,
                    leftEyeX + baseEyeRadius,
                    eyeY + baseEyeRadius
                )
                val rightArc = RectF(
                    rightEyeX - baseEyeRadius,
                    eyeY - baseEyeRadius,
                    rightEyeX + baseEyeRadius,
                    eyeY + baseEyeRadius
                )
                canvas.drawArc(leftArc, 195f, 150f, false, browPaint)
                canvas.drawArc(rightArc, 195f, 150f, false, browPaint)
                canvas.drawCircle(cx - w * 0.25f, cy + h * 0.12f, w * 0.055f, blushPaint)
                canvas.drawCircle(cx + w * 0.25f, cy + h * 0.12f, w * 0.055f, blushPaint)
            }
            "SLEEPY", "COOLDOWN" -> {
                val sleepRectL = RectF(
                    leftEyeX - baseEyeRadius,
                    eyeY - baseEyeRadius * 0.28f,
                    leftEyeX + baseEyeRadius,
                    eyeY + baseEyeRadius * 0.28f
                )
                val sleepRectR = RectF(
                    rightEyeX - baseEyeRadius,
                    eyeY - baseEyeRadius * 0.28f,
                    rightEyeX + baseEyeRadius,
                    eyeY + baseEyeRadius * 0.28f
                )
                canvas.drawRoundRect(sleepRectL, 8f, 8f, eyePaint)
                canvas.drawRoundRect(sleepRectR, 8f, 8f, eyePaint)
            }
            "CONFUSED", "VERIFYING" -> {
                canvas.drawCircle(leftEyeX, eyeY, baseEyeRadius * 1.12f, eyePaint)
                canvas.drawCircle(rightEyeX, eyeY, baseEyeRadius * 0.75f, eyePaint)
                canvas.drawLine(
                    leftEyeX - baseEyeRadius,
                    eyeY - baseEyeRadius * 1.45f,
                    leftEyeX + baseEyeRadius,
                    eyeY - baseEyeRadius * 1.7f,
                    browPaint
                )
            }
            else -> {
                val rScale = when (currentEmotion) {
                    "SURPRISED", "EXCITED" -> 1.18f
                    else -> 1.0f
                }
                val r = baseEyeRadius * rScale
                val ry = (r * blinkFactor).coerceAtLeast(2.2f)
                canvas.drawOval(RectF(leftEyeX - r, eyeY - ry, leftEyeX + r, eyeY + ry), eyePaint)
                canvas.drawOval(RectF(rightEyeX - r, eyeY - ry, rightEyeX + r, eyeY + ry), eyePaint)

                if (currentEmotion == "EXCITED" || currentEmotion == "WORKING") {
                    canvas.drawCircle(cx - w * 0.26f, cy + h * 0.12f, w * 0.055f, blushPaint)
                    canvas.drawCircle(cx + w * 0.26f, cy + h * 0.12f, w * 0.055f, blushPaint)
                }

                if (currentEmotion == "ERROR" || currentEmotion == "ANGRY") {
                    canvas.drawLine(leftEyeX - r, eyeY - r * 1.45f, leftEyeX + r * 0.7f, eyeY - r * 1.05f, browPaint)
                    canvas.drawLine(rightEyeX + r, eyeY - r * 1.45f, rightEyeX - r * 0.7f, eyeY - r * 1.05f, browPaint)
                } else if (currentEmotion == "SAD" || currentEmotion == "TIMEOUT") {
                    canvas.drawLine(leftEyeX - r, eyeY - r * 1.05f, leftEyeX + r * 0.7f, eyeY - r * 1.45f, browPaint)
                    canvas.drawLine(rightEyeX + r, eyeY - r * 1.05f, rightEyeX - r * 0.7f, eyeY - r * 1.45f, browPaint)
                } else if (currentEmotion == "HIGH_BRAIN" || currentEmotion == "PRO") {
                    canvas.drawCircle(cx, cy - headRy * 0.62f, w * 0.045f, eyePaint)
                }
            }
        }

        val mouthY = cy + h * 0.18f
        mouthPath.reset()
        when (currentEmotion) {
            "HAPPY", "DONE", "EXCITED", "WORKING" -> {
                mouthPath.moveTo(cx - w * 0.10f, mouthY)
                mouthPath.quadTo(cx, mouthY + h * 0.09f, cx + w * 0.10f, mouthY)
                canvas.drawPath(mouthPath, browPaint)
            }
            "ERROR", "ANGRY", "SAD", "TIMEOUT" -> {
                mouthPath.moveTo(cx - w * 0.10f, mouthY + h * 0.05f)
                mouthPath.quadTo(cx, mouthY - h * 0.05f, cx + w * 0.10f, mouthY + h * 0.05f)
                canvas.drawPath(mouthPath, browPaint)
            }
            "SURPRISED", "THINKING" -> {
                canvas.drawCircle(cx, mouthY + h * 0.02f, w * 0.042f, eyePaint)
            }
        }
    }
}

/**
 * Visual Hand Pointer & Ripple Overlay Canvas for Nax Bot Touch Verification
 */
class NaxHandCursorCanvas(context: Context) : View(context) {

    private var tapX = -1f
    private var tapY = -1f
    private var tapLabel = ""
    private var rippleProgress = 0f

    private var swipeX1 = -1f
    private var swipeY1 = -1f
    private var swipeX2 = -1f
    private var swipeY2 = -1f
    private var isSwiping = false

    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.parseColor("#38BDF8")
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#2DD4BF")
    }

    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#D90F172A")
    }

    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        typeface = Typeface.DEFAULT_BOLD
    }

    private var currentAnimator: ValueAnimator? = null

    private fun screenToCanvas(x: Float, y: Float): Pair<Float, Float> {
        // Accessibility node bounds are screen coordinates; Canvas draws in view-local coordinates.
        val location = IntArray(2)
        getLocationOnScreen(location)
        return Pair(x - location[0], y - location[1])
    }

    fun triggerTapAnimation(x: Float, y: Float, label: String) {
        val local = screenToCanvas(x, y)
        tapX = local.first
        tapY = local.second
        tapLabel = label
        isSwiping = false
        startEffectAnimator()
    }

    fun triggerSwipeAnimation(x1: Float, y1: Float, x2: Float, y2: Float) {
        val start = screenToCanvas(x1, y1)
        val end = screenToCanvas(x2, y2)
        swipeX1 = start.first
        swipeY1 = start.second
        swipeX2 = end.first
        swipeY2 = end.second
        isSwiping = true
        startEffectAnimator()
    }

    private fun startEffectAnimator() {
        currentAnimator?.cancel()
        currentAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 650L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                rippleProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stopAnimations() {
        currentAnimator?.cancel()
        tapX = -1f
        tapY = -1f
        isSwiping = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rippleProgress <= 0f || rippleProgress >= 1f) return

        val alpha = ((1f - rippleProgress) * 255).toInt().coerceIn(0, 255)
        ripplePaint.alpha = alpha
        dotPaint.alpha = alpha

        if (isSwiping) {
            val curX = swipeX1 + (swipeX2 - swipeX1) * rippleProgress
            val curY = swipeY1 + (swipeY2 - swipeY1) * rippleProgress
            canvas.drawLine(swipeX1, swipeY1, curX, curY, ripplePaint)
            canvas.drawCircle(curX, curY, 18f, dotPaint)
        } else if (tapX >= 0f && tapY >= 0f) {
            val radius = 16f + rippleProgress * 64f
            canvas.drawCircle(tapX, tapY, radius, ripplePaint)
            canvas.drawCircle(tapX, tapY, 14f, dotPaint)

            // Restore the visible animated hand: the index fingertip stays anchored on
            // the exact tap target while the palm gently presses down and releases.
            val handShiftY = if (rippleProgress < 0.45f) rippleProgress * 10f else (1f - rippleProgress) * 8f
            val hx = tapX + 18f
            val hy = tapY + 14f + handShiftY
            val handFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
            val handOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#0F172A")
                style = Paint.Style.STROKE
                strokeWidth = 4f
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }
            val hand = Path().apply {
                moveTo(hx - 13f, hy + 35f)
                lineTo(hx - 20f, hy + 18f)
                quadTo(hx - 23f, hy + 9f, hx - 15f, hy + 7f)
                quadTo(hx - 9f, hy + 6f, hx - 5f, hy + 16f)
                lineTo(hx - 5f, hy - 18f)
                quadTo(hx - 5f, hy - 28f, hx + 2f, hy - 28f)
                quadTo(hx + 9f, hy - 28f, hx + 9f, hy - 18f)
                lineTo(hx + 10f, hy + 3f)
                lineTo(hx + 17f, hy - 2f)
                quadTo(hx + 25f, hy - 6f, hx + 28f, hy + 2f)
                lineTo(hx + 32f, hy + 7f)
                quadTo(hx + 41f, hy + 4f, hx + 44f, hy + 14f)
                lineTo(hx + 40f, hy + 34f)
                quadTo(hx + 37f, hy + 42f, hx + 27f, hy + 42f)
                lineTo(hx - 4f, hy + 42f)
                close()
            }
            canvas.drawPath(hand, handFill)
            canvas.drawPath(hand, handOutline)

            if (tapLabel.isNotBlank()) {
                val tw = labelTextPaint.measureText(tapLabel)
                val rect = RectF(tapX - tw / 2f - 14f, tapY - 72f, tapX + tw / 2f + 14f, tapY - 30f)
                canvas.drawRoundRect(rect, 12f, 12f, labelBgPaint)
                canvas.drawText(tapLabel, tapX - tw / 2f, tapY - 42f, labelTextPaint)
            }
        }
    }
}
