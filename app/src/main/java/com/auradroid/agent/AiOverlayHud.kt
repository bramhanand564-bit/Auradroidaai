package com.auradroid.agent

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.sin

class AiOverlayHud(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var pointerCanvasView: SoftCompanionCursorCanvas? = null
    private var podRootLayout: LinearLayout? = null
    private var podLayoutParams: WindowManager.LayoutParams? = null

    // Stage 1 Views (Compact Draggable Pod)
    private var compactRow: LinearLayout? = null
    private var tvPodTitle: TextView? = null
    private var tvPodSubtitle: TextView? = null
    private var botAvatarView: SoftWhiteCompanionAvatar? = null

    // Stage 2 & Stage 3 Views (Mini Pop-out & Full Screen Live Workspace)
    private var expandedContainer: LinearLayout? = null
    private var tvExpandedStepInfo: TextView? = null
    private var btnExpandFullToggle: TextView? = null
    private var podWebFrame: FrameLayout? = null

    private var sharedWebView: WebView? = null
    private var isAdded = false
    private var isPodVisible = true
    private var currentStage = 1 // 1 = Compact Pod, 2 = Mini Window, 3 = Full Screen

    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreateSharedWebView(): WebView {
        if (sharedWebView == null) {
            val wv = WebView(context.applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.userAgentString = settings.userAgentString.replace("; wv", "")

                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                webViewClient = object : WebViewClient() {}
                webChromeClient = WebChromeClient()

                // Auto-Download Listener for AI Videos & APKs into Download/AutoVideos
                setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                    try {
                        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                        val request = DownloadManager.Request(Uri.parse(url)).apply {
                            setMimeType(mimeType)
                            addRequestHeader("User-Agent", userAgent)
                            CookieManager.getInstance().getCookie(url)?.let {
                                addRequestHeader("Cookie", it)
                            }
                            setTitle(fileName)
                            setDescription("AuraDroid AI Downloading...")
                            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "AutoVideos/$fileName")
                        }
                        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                        dm.enqueue(request)
                        VideoQueueManager.appendLog(context, "⬇️ Background Download Started: $fileName")
                    } catch (e: Exception) {
                        VideoQueueManager.appendLog(context, "⚠️ Download error: ${e.message}")
                    }
                }
                loadUrl("https://www.google.com")
            }
            sharedWebView = wv
        }
        return sharedWebView!!
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
        }
    }

    fun show() {
        mainHandler.post {
            if (!isPodVisible) return@post
            if (isAdded) return@post
            try {
                // 1. Full-screen transparent touch ripple & Soft-White Bot cursor layer
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
                pointerCanvasView = SoftCompanionCursorCanvas(context)
                windowManager.addView(pointerCanvasView, canvasParams)

                // 2. 3-Stage Movable Floating Aura Pod
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
                    x = dpToPx(24)
                    y = dpToPx(42)
                }

                podRootLayout = buildThreeStagePodUi()
                windowManager.addView(podRootLayout, podLayoutParams)
                isAdded = true
                setPodStage(1)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun hide() {
        mainHandler.post {
            if (!isAdded) return@post
            try {
                pointerCanvasView?.stopAnimations()
                pointerCanvasView?.let { windowManager.removeView(it) }
                podWebFrame?.removeAllViews()
                podRootLayout?.let { windowManager.removeView(it) }
            } catch (_: Exception) {
            } finally {
                pointerCanvasView = null
                podRootLayout = null
                isAdded = false
                currentStage = 1
            }
        }
    }

    fun togglePodVisibility(): Boolean {
        isPodVisible = !isPodVisible
        if (isPodVisible) show() else hide()
        return isPodVisible
    }

    fun setCursorOverlayVisible(visible: Boolean) {
        mainHandler.post {
            pointerCanvasView?.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    fun updateStatus(stepBadge: String, detailMessage: String, mood: String = "WORKING") {
        mainHandler.post {
            if (isPodVisible && !isAdded) show()
            tvPodTitle?.text = stepBadge
            tvPodSubtitle?.text = detailMessage
            tvExpandedStepInfo?.text = "$stepBadge • $detailMessage"
            botAvatarView?.setMood(mood)
        }
    }

    fun moveBotAndShowTap(targetX: Float, targetY: Float, actionLabel: String) {
        mainHandler.post {
            if (isPodVisible && !isAdded) show()
            pointerCanvasView?.visibility = View.VISIBLE
            pointerCanvasView?.animateBotTo(targetX, targetY, actionLabel)
        }
    }

    /**
     * Switches smoothly between:
     * Stage 1: Compact Movable Pod
     * Stage 2: Mini Pop-out Window (Live Web/Task Peek)
     * Stage 3: Full Screen Workspace
     */
    private fun setPodStage(stage: Int) {
        mainHandler.post {
            currentStage = stage
            val params = podLayoutParams ?: return@post
            val root = podRootLayout ?: return@post
            val dm = context.resources.displayMetrics

            when (stage) {
                1 -> {
                    // Stage 1: Compact Draggable Pill
                    expandedContainer?.visibility = View.GONE
                    podWebFrame?.removeAllViews()
                    params.width = WindowManager.LayoutParams.WRAP_CONTENT
                    params.height = WindowManager.LayoutParams.WRAP_CONTENT
                    params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                }
                2 -> {
                    // Stage 2: Mini Live Pop-out Window
                    expandedContainer?.visibility = View.VISIBLE
                    btnExpandFullToggle?.text = "⛶ Full Screen"
                    params.width = (dm.widthPixels * 0.86f).toInt()
                    params.height = (dm.heightPixels * 0.44f).toInt()
                    params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    podWebFrame?.let { attachWebViewToContainer(it) }
                }
                3 -> {
                    // Stage 3: Full Screen Interactive Workspace
                    expandedContainer?.visibility = View.VISIBLE
                    btnExpandFullToggle?.text = "↙ Mini Window"
                    params.x = 0
                    params.y = dpToPx(28)
                    params.width = dm.widthPixels
                    params.height = (dm.heightPixels * 0.88f).toInt()
                    // Allow keyboard input when in Full Screen mode
                    params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    podWebFrame?.let { attachWebViewToContainer(it) }
                }
            }

            try {
                windowManager.updateViewLayout(root, params)
            } catch (_: Exception) {}
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildThreeStagePodUi(): LinearLayout {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2181A20"))
                cornerRadius = dpToPx(24).toFloat()
                setStroke(dpToPx(1), Color.parseColor("#334155"))
            }
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
        }

        // --- TOP COMPACT BAR (Always Visible, Draggable, Tap to Expand/Collapse) ---
        compactRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Soft-White Blinking Companion Head
        botAvatarView = SoftWhiteCompanionAvatar(context).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(38), dpToPx(38)).apply {
                marginEnd = dpToPx(10)
            }
        }
        compactRow?.addView(botAvatarView)

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dpToPx(175), LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        tvPodTitle = TextView(context).apply {
            text = "AuraDroid Companion"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }

        tvPodSubtitle = TextView(context).apply {
            text = "Tap to view live workspace"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            maxLines = 1
        }

        textCol.addView(tvPodTitle)
        textCol.addView(tvPodSubtitle)
        compactRow?.addView(textCol)

        val stopBadge = TextView(context).apply {
            text = "■"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dpToPx(10), dpToPx(5), dpToPx(10), dpToPx(5))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EF4444"))
                cornerRadius = dpToPx(14).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dpToPx(6)
            }
            setOnClickListener {
                AutoTouchService.instance?.stopCurrentJob()
            }
        }
        compactRow?.addView(stopBadge)
        root.addView(compactRow)

        // --- EXPANDED STAGE 2 & STAGE 3 CONTAINER (Mini Window & Full Screen) ---
        expandedContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            ).apply {
                topMargin = dpToPx(8)
            }
        }

        // Control Strip inside Expanded Window
        val controlBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(4), 0, dpToPx(8))
        }

        tvExpandedStepInfo = TextView(context).apply {
            text = "Live Background Workspace"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 1
        }
        controlBar.addView(tvExpandedStepInfo)

        btnExpandFullToggle = TextView(context).apply {
            text = "⛶ Full Screen"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(dpToPx(10), dpToPx(5), dpToPx(10), dpToPx(5))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#3B82F6"))
                cornerRadius = dpToPx(12).toFloat()
            }
            setOnClickListener {
                if (currentStage == 2) setPodStage(3) else setPodStage(2)
            }
        }
        controlBar.addView(btnExpandFullToggle)

        val btnMinimizePod = TextView(context).apply {
            text = "━ Hide"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(dpToPx(10), dpToPx(5), dpToPx(10), dpToPx(5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dpToPx(6)
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#262932"))
                cornerRadius = dpToPx(12).toFloat()
            }
            setOnClickListener {
                setPodStage(1)
            }
        }
        controlBar.addView(btnMinimizePod)
        expandedContainer?.addView(controlBar)

        // Live WebView Frame inside Pop-out Window
        podWebFrame = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0D0E11"))
                cornerRadius = dpToPx(14).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        expandedContainer?.addView(podWebFrame)
        root.addView(expandedContainer)

        // Drag anywhere on screen + Single Tap to Toggle Stage 1 <-> Stage 2
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var isDragging = false

        compactRow?.setOnTouchListener { _, event ->
            val params = podLayoutParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isDragging = true
                        if (currentStage != 3) {
                            params.x = (startX + dx).coerceIn(0, 950)
                            params.y = (startY + dy).coerceIn(20, 2100)
                            try {
                                windowManager.updateViewLayout(root, params)
                            } catch (_: Exception) {}
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        if (currentStage == 1) setPodStage(2) else setPodStage(1)
                    }
                    true
                }
                else -> false
            }
        }

        return root
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    /**
     * 100% Original Soft-White Blinking Companion Bot Avatar (Friendly Marshmallow Style)
     */
    private class SoftWhiteCompanionAvatar(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var phase = 0f
        private var mood = "WORKING"

        private val animator = object : Runnable {
            override fun run() {
                phase += 0.16f
                invalidate()
                postDelayed(this, 45)
            }
        }

        init {
            post(animator)
        }

        fun setMood(newMood: String) {
            mood = newMood
            invalidate()
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(animator)
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val bobY = sin(phase * 0.6f) * 1.8f

            // Soft Pill-Shaped White Head
            val headRect = RectF(4f, 6f + bobY, w - 4f, h - 6f + bobY)
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#F8FAFC")
            canvas.drawRoundRect(headRect, h * 0.42f, h * 0.42f, paint)

            // Subtle border
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = when (mood) {
                "DONE" -> Color.parseColor("#10B981")
                "THINKING" -> Color.parseColor("#3B82F6")
                else -> Color.parseColor("#CBD5E1")
            }
            canvas.drawRoundRect(headRect, h * 0.42f, h * 0.42f, paint)

            // Gentle Connector Line Between Eyes
            val eyeY = h / 2f + bobY
            val leftEyeX = w * 0.34f
            val rightEyeX = w * 0.66f

            paint.strokeWidth = 2.2f
            paint.color = Color.parseColor("#CBD5E1")
            canvas.drawLine(leftEyeX, eyeY, rightEyeX, eyeY, paint)

            // Natural Blinking Dark Eyes
            val isBlinking = sin(phase * 0.85f) > 0.93f
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#0F172A")

            if (isBlinking) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3f
                canvas.drawLine(leftEyeX - 4f, eyeY, leftEyeX + 4f, eyeY, paint)
                canvas.drawLine(rightEyeX - 4f, eyeY, rightEyeX + 4f, eyeY, paint)
            } else {
                canvas.drawCircle(leftEyeX, eyeY, 4.3f, paint)
                canvas.drawCircle(rightEyeX, eyeY, 4.3f, paint)
            }
        }
    }

    /**
     * Clean On-Screen Soft-White Bot Cursor + Subtle Touch Ripple (No Neon, No Trailing Lines)
     */
    private class SoftCompanionCursorCanvas(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F8FAFC")
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
        }
        private val screenOffset = IntArray(2)

        private var botX = 540f
        private var botY = 400f
        private var startX = 540f
        private var startY = 400f
        private var targetX = 540f
        private var targetY = 400f
        private var rippleProgress = 0f
        private var blinkPhase = 0f
        private var currentLabel = ""

        private var moveAnimator: ValueAnimator? = null
        private var rippleAnimator: ValueAnimator? = null

        private val ticker = object : Runnable {
            override fun run() {
                blinkPhase += 0.18f
                invalidate()
                postDelayed(this, 45)
            }
        }

        init {
            post(ticker)
        }

        fun animateBotTo(destX: Float, destY: Float, label: String) {
            startX = botX
            startY = botY
            targetX = destX
            targetY = destY
            currentLabel = label

            moveAnimator?.cancel()
            moveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 360
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    val f = anim.animatedValue as Float
                    botX = startX + (targetX - startX) * f
                    botY = startY + (targetY - startY) * f
                    invalidate()
                }
                start()
            }

            rippleAnimator?.cancel()
            rippleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                startDelay = 280
                duration = 600
                addUpdateListener { anim ->
                    rippleProgress = anim.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun stopAnimations() {
            removeCallbacks(ticker)
            moveAnimator?.cancel()
            rippleAnimator?.cancel()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (currentLabel.isEmpty()) return

            getLocationOnScreen(screenOffset)
            val ox = screenOffset[0].toFloat()
            val oy = screenOffset[1].toFloat()

            val drawTargetX = targetX - ox
            val drawTargetY = targetY - oy
            val cx = (botX - ox)
            val cy = (botY - oy) + sin(blinkPhase * 0.7f) * 4f

            // 1. Clean Soft White/Blue Touch Ripple
            if (rippleProgress > 0f && rippleProgress < 1f) {
                val alpha = ((1f - rippleProgress) * 190).toInt().coerceIn(0, 255)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 4f
                paint.color = Color.argb(alpha, 248, 250, 252)
                canvas.drawCircle(drawTargetX, drawTargetY, 16f + rippleProgress * 70f, paint)
            }

            // 2. Soft-White Companion Head Cursor
            val headRect = RectF(cx - 28f, cy - 22f, cx + 28f, cy + 22f)
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#F8FAFC")
            canvas.drawRoundRect(headRect, 22f, 22f, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f
            paint.color = Color.parseColor("#334155")
            canvas.drawRoundRect(headRect, 22f, 22f, paint)

            // Connector Line & Blinking Dark Eyes
            paint.strokeWidth = 2.2f
            paint.color = Color.parseColor("#CBD5E1")
            canvas.drawLine(cx - 10f, cy, cx + 10f, cy, paint)

            val blinking = sin(blinkPhase) > 0.92f
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#0F172A")
            if (blinking) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3f
                canvas.drawLine(cx - 14f, cy, cx - 6f, cy, paint)
                canvas.drawLine(cx + 6f, cy, cx + 14f, cy, paint)
            } else {
                canvas.drawCircle(cx - 10f, cy, 4.2f, paint)
                canvas.drawCircle(cx + 10f, cy, 4.2f, paint)
            }

            // 3. Clean Matte Slate Action Tag
            val textWidth = textPaint.measureText(currentLabel)
            val tagLeft = (cx + 34f).coerceAtMost(width - textWidth - 36f).coerceAtLeast(16f)
            val tagTop = (cy - 20f).coerceAtLeast(60f)
            val tagRect = RectF(tagLeft, tagTop, tagLeft + textWidth + 24f, tagTop + 42f)

            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#E6181A20")
            canvas.drawRoundRect(tagRect, 14f, 14f, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f
            paint.color = Color.parseColor("#475569")
            canvas.drawRoundRect(tagRect, 14f, 14f, paint)

            canvas.drawText(currentLabel, tagLeft + 12f, tagTop + 29f, textPaint)
        }
    }
}
