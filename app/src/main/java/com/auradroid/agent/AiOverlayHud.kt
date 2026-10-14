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
import kotlin.math.cos
import kotlin.math.sin

class AiOverlayHud(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var pointerCanvasView: CuteBaymaxHandCursorCanvas? = null
    private var podRootLayout: LinearLayout? = null
    private var podLayoutParams: WindowManager.LayoutParams? = null

    private var compactRow: LinearLayout? = null
    private var tvPodTitle: TextView? = null
    private var tvPodSubtitle: TextView? = null
    private var botAvatarView: ExpressiveCompanionAvatar? = null

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
                pointerCanvasView = CuteBaymaxHandCursorCanvas(context)
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
                    x = dpToPx(20)
                    y = dpToPx(38)
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
            pointerCanvasView?.setHandMood(mood)
        }
    }

    fun moveBotAndShowTap(targetX: Float, targetY: Float, actionLabel: String) {
        mainHandler.post {
            if (isPodVisible && !isAdded) show()
            pointerCanvasView?.visibility = View.VISIBLE
            pointerCanvasView?.animateHandToTap(targetX, targetY, actionLabel)
        }
    }

    private fun setPodStage(stage: Int) {
        mainHandler.post {
            currentStage = stage
            val params = podLayoutParams ?: return@post
            val root = podRootLayout ?: return@post
            val dm = context.resources.displayMetrics

            when (stage) {
                1 -> {
                    expandedContainer?.visibility = View.GONE
                    podWebFrame?.removeAllViews()
                    params.width = WindowManager.LayoutParams.WRAP_CONTENT
                    params.height = WindowManager.LayoutParams.WRAP_CONTENT
                    params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                }
                2 -> {
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
                    expandedContainer?.visibility = View.VISIBLE
                    btnExpandFullToggle?.text = "↙ Mini Window"
                    params.x = 0
                    params.y = dpToPx(28)
                    params.width = dm.widthPixels
                    params.height = (dm.heightPixels * 0.88f).toInt()
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
                cornerRadius = dpToPx(26).toFloat()
                setStroke(dpToPx(1), Color.parseColor("#334155"))
            }
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
        }

        compactRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        botAvatarView = ExpressiveCompanionAvatar(context).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(44), dpToPx(40)).apply {
                marginEnd = dpToPx(10)
            }
        }
        compactRow?.addView(botAvatarView)

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dpToPx(175), LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        tvPodTitle = TextView(context).apply {
            text = "Aura Companion"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }

        tvPodSubtitle = TextView(context).apply {
            text = "Tap to peek live view"
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
     * Expressive Soft-White Companion Face:
     * - Dynamic Eye Colors (Blue = Thinking, Green = Working/Success, Red = Error/Sad, Amber = Waiting, Dark = Ready)
     * - Dynamic Emoji Mouths (Smile, Big Happy Laugh, Thinking 'o', Sad Frown + Tear)
     * - Micro-animations (Scanning eyes while thinking, blinking, floating sparkles, rosy cheeks)
     */
    private class ExpressiveCompanionAvatar(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var phase = 0f
        private var mood = "WORKING" // WORKING, THINKING, DONE, ERROR, SAD, WAITING

        private val animator = object : Runnable {
            override fun run() {
                phase += 0.18f
                invalidate()
                postDelayed(this, 40)
            }
        }

        init {
            post(animator)
        }

        fun setMood(newMood: String) {
            mood = newMood.uppercase()
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
            val bobY = sin(phase * 0.7f) * 2.0f

            // 1. Soft Marshmallow White Head
            val headRect = RectF(3f, 4f + bobY, w - 3f, h - 4f + bobY)
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#F8FAFC")
            canvas.drawRoundRect(headRect, h * 0.46f, h * 0.46f, paint)

            // Mood-reactive Soft Head Border
            val accentColor = when (mood) {
                "ERROR", "SAD", "FAILED" -> Color.parseColor("#EF4444") // Red
                "THINKING", "PROCESSING" -> Color.parseColor("#2563EB") // Electric Blue
                "DONE", "HAPPY", "SUCCESS" -> Color.parseColor("#10B981") // Emerald Green
                "WAITING" -> Color.parseColor("#F59E0B") // Warm Amber
                else -> Color.parseColor("#059669") // Teal/Green Working
            }

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.4f
            paint.color = accentColor
            canvas.drawRoundRect(headRect, h * 0.46f, h * 0.46f, paint)

            // Eye Positions (Slightly shifts left-right when THINKING!)
            val lookOffsetX = if (mood == "THINKING") sin(phase * 1.6f) * 3.2f else 0f
            val lookOffsetY = if (mood == "THINKING") cos(phase * 1.2f) * 1.5f else 0f

            val eyeY = h * 0.43f + bobY + lookOffsetY
            val leftEyeX = w * 0.32f + lookOffsetX
            val rightEyeX = w * 0.68f + lookOffsetX

            // 2. Cute Rosy Cheeks (Brighter when Happy/Working)
            if (mood != "ERROR" && mood != "SAD") {
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(85, 244, 114, 182)
                canvas.drawOval(RectF(w * 0.14f, eyeY + 3.5f, w * 0.28f, eyeY + 9f), paint)
                canvas.drawOval(RectF(w * 0.72f, eyeY + 3.5f, w * 0.86f, eyeY + 9f), paint)
            }

            // 3. Subtle Baymax-style Bridge Line (Faint so the smile stands out clearly)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.6f
            paint.color = Color.parseColor("#E2E8F0")
            canvas.drawLine(leftEyeX, eyeY, rightEyeX, eyeY, paint)

            // 4. Dynamic Color-Changing Eyes
            val isBlinking = sin(phase * 0.85f) > 0.93f
            val eyeColor = when (mood) {
                "ERROR", "SAD", "FAILED" -> Color.parseColor("#DC2626") // Red Eyes on Error!
                "THINKING", "PROCESSING" -> Color.parseColor("#2563EB") // Blue Eyes while Thinking!
                "DONE", "HAPPY", "SUCCESS" -> Color.parseColor("#059669") // Green Happy Eyes!
                "WAITING" -> Color.parseColor("#D97706") // Amber Eyes!
                else -> Color.parseColor("#0F172A") // Deep Charcoal/Teal Eyes
            }

            paint.color = eyeColor

            if (mood == "DONE" || mood == "HAPPY") {
                // Happy Joyful Curved Arc Eyes (^  ^)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3.2f
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawArc(RectF(leftEyeX - 5f, eyeY - 4f, leftEyeX + 5f, eyeY + 4f), 190f, 160f, false, paint)
                canvas.drawArc(RectF(rightEyeX - 5f, eyeY - 4f, rightEyeX + 5f, eyeY + 4f), 190f, 160f, false, paint)
            } else if (isBlinking) {
                // Smooth Blink Line
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3f
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(leftEyeX - 4.5f, eyeY, leftEyeX + 4.5f, eyeY, paint)
                canvas.drawLine(rightEyeX - 4.5f, eyeY, rightEyeX + 4.5f, eyeY, paint)
            } else {
                // Round Expressive Eyes
                paint.style = Paint.Style.FILL
                val eyeRadius = if (mood == "THINKING") 4.8f + sin(phase * 2f) * 0.6f else 4.8f
                canvas.drawCircle(leftEyeX, eyeY, eyeRadius, paint)
                canvas.drawCircle(rightEyeX, eyeY, eyeRadius, paint)

                // Cute White Sparkle Catchlights inside Eyes
                paint.color = Color.WHITE
                canvas.drawCircle(leftEyeX - 1.5f, eyeY - 1.5f, 1.6f, paint)
                canvas.drawCircle(rightEyeX - 1.5f, eyeY - 1.5f, 1.6f, paint)
                canvas.drawCircle(leftEyeX + 1.5f, eyeY + 1.5f, 0.8f, paint)
                canvas.drawCircle(rightEyeX + 1.5f, eyeY + 1.5f, 0.8f, paint)

                // Sad / Worried Eyebrows when Error/Sad
                if (mood == "ERROR" || mood == "SAD") {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 2.4f
                    paint.color = Color.parseColor("#DC2626")
                    canvas.drawLine(leftEyeX - 5f, eyeY - 6f, leftEyeX + 4f, eyeY - 8.5f, paint)
                    canvas.drawLine(rightEyeX - 4f, eyeY - 8.5f, rightEyeX + 5f, eyeY - 6f, paint)
                }
            }

            // 5. Expressive Emoji Mouth / Smile Below the Eyes!
            val mouthCenterX = w * 0.50f + lookOffsetX * 0.5f
            val mouthTopY = h * 0.62f + bobY
            paint.color = eyeColor
            paint.strokeCap = Paint.Cap.ROUND

            when (mood) {
                "ERROR", "SAD", "FAILED" -> {
                    // Sad Frown Mouth (︵)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 2.8f
                    val frownRect = RectF(mouthCenterX - 6.5f, mouthTopY, mouthCenterX + 6.5f, mouthTopY + 8f)
                    canvas.drawArc(frownRect, 200f, 140f, false, paint)

                    // Cute Animated Blue Tear Drop
                    val tearY = eyeY + 5f + (phase % 3f) * 2.2f
                    paint.style = Paint.Style.FILL
                    paint.color = Color.parseColor("#38BDF8")
                    canvas.drawCircle(rightEyeX + 5.5f, tearY, 2.2f, paint)
                }
                "THINKING", "PROCESSING" -> {
                    // Cute Thinking "o" Mouth that pulses slightly
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 2.5f
                    val r = 3.2f + sin(phase * 2.2f) * 0.8f
                    canvas.drawCircle(mouthCenterX, mouthTopY + 3f, r, paint)

                    // 3 Floating Thinking Dots at top-right of head
                    paint.style = Paint.Style.FILL
                    paint.color = Color.parseColor("#3B82F6")
                    val activeDot = ((phase * 1.5f).toInt()) % 3
                    for (i in 0..2) {
                        val dotR = if (i == activeDot) 2.4f else 1.4f
                        canvas.drawCircle(w * 0.76f + i * 4.2f, 7f + bobY - i * 1.5f, dotR, paint)
                    }
                }
                "DONE", "HAPPY", "SUCCESS" -> {
                    // Big Cheerful Open D-Smile (▽)
                    paint.style = Paint.Style.FILL
                    val laughRect = RectF(mouthCenterX - 7.5f, mouthTopY - 2f, mouthCenterX + 7.5f, mouthTopY + 7.5f)
                    canvas.drawArc(laughRect, 0f, 180f, true, paint)
                }
                else -> {
                    // Cute Friendly Happy Smile Arc (◡)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 2.8f
                    val smileRect = RectF(mouthCenterX - 6.5f, mouthTopY - 2.5f, mouthCenterX + 6.5f, mouthTopY + 5.5f)
                    canvas.drawArc(smileRect, 15f, 150f, false, paint)
                }
            }
        }
    }

    /**
     * Super Cute Plump Soft-White Baymax Hand Cursor (`CuteBaymaxHandCursorCanvas`)
     * Moves smoothly to the target coordinate and gently presses ("boops") the button with its chubby index finger!
     */
    private class CuteBaymaxHandCursorCanvas(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F8FAFC")
            textSize = 27f
            typeface = Typeface.DEFAULT_BOLD
        }
        private val screenOffset = IntArray(2)

        private var handX = 540f
        private var handY = 400f
        private var startX = 540f
        private var startY = 400f
        private var targetX = 540f
        private var targetY = 400f
        private var rippleProgress = 0f
        private var pressScale = 1f
        private var phase = 0f
        private var currentLabel = ""
        private var handMood = "WORKING"

        private var moveAnimator: ValueAnimator? = null
        private var rippleAnimator: ValueAnimator? = null

        private val ticker = object : Runnable {
            override fun run() {
                phase += 0.2f
                invalidate()
                postDelayed(this, 40)
            }
        }

        init {
            post(ticker)
        }

        fun setHandMood(mood: String) {
            handMood = mood.uppercase()
            invalidate()
        }

        fun animateHandToTap(destX: Float, destY: Float, label: String) {
            startX = handX
            startY = handY
            targetX = destX
            targetY = destY
            currentLabel = label

            moveAnimator?.cancel()
            moveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 340
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    val f = anim.animatedValue as Float
                    handX = startX + (targetX - startX) * f
                    handY = startY + (targetY - startY) * f
                    invalidate()
                }
                start()
            }

            rippleAnimator?.cancel()
            rippleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                startDelay = 260
                duration = 620
                addUpdateListener { anim ->
                    val p = anim.animatedValue as Float
                    rippleProgress = p
                    // Cute "Boop" press-down effect when finger touches the target
                    pressScale = if (p < 0.35f) {
                        1f - (p / 0.35f) * 0.16f
                    } else {
                        0.84f + ((p - 0.35f) / 0.65f) * 0.16f
                    }
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

            // Exact fingertip target coordinates on canvas
            val tipX = handX - ox
            val tipY = (handY - oy) + sin(phase * 0.8f) * 2.5f

            val accentColor = when (handMood) {
                "ERROR", "SAD" -> Color.parseColor("#EF4444")
                "DONE", "HAPPY" -> Color.parseColor("#10B981")
                else -> Color.parseColor("#3B82F6")
            }

            // 1. Cute Touch Ripple & Sparkle Dots at Fingertip
            if (rippleProgress > 0f && rippleProgress < 1f) {
                val alpha = ((1f - rippleProgress) * 210).toInt().coerceIn(0, 255)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 4.5f
                paint.color = Color.argb(alpha, 59, 130, 246)
                canvas.drawCircle(targetX - ox, targetY - oy, 12f + rippleProgress * 62f, paint)

                // 4 Tiny Cute Sparkle Dots popping around the tap point
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(alpha, 248, 250, 252)
                val sparkDist = 18f + rippleProgress * 45f
                for (angleDeg in listOf(45.0, 135.0, 225.0, 315.0)) {
                    val rad = Math.toRadians(angleDeg)
                    val sx = (targetX - ox) + (cos(rad) * sparkDist).toFloat()
                    val sy = (targetY - oy) + (sin(rad) * sparkDist).toFloat()
                    canvas.drawCircle(sx, sy, 4f * (1f - rippleProgress), paint)
                }
            }

            // 2. Draw Super Cute Plump Soft-White Baymax Hand!
            // The index fingertip is anchored at (tipX, tipY) so you see the exact point being touched!
            canvas.save()
            canvas.translate(tipX, tipY)
            canvas.scale(pressScale, pressScale)

            // Soft Drop Shadow under Hand
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(55, 0, 0, 0)
            canvas.drawRoundRect(RectF(-22f, 20f, 34f, 74f), 26f, 26f, paint)

            val handFillColor = Color.parseColor("#F8FAFC")
            val handStrokeColor = Color.parseColor("#334155")

            // A) Cute Chubby Thumb (sticking out to the left)
            val thumbRect = RectF(-34f, 28f, -6f, 52f)
            paint.style = Paint.Style.FILL
            paint.color = handFillColor
            canvas.rotate(-22f, -20f, 40f)
            canvas.drawRoundRect(thumbRect, 14f, 14f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.6f
            paint.color = handStrokeColor
            canvas.drawRoundRect(thumbRect, 14f, 14f, paint)
            canvas.rotate(22f, -20f, 40f)

            // B) Cute Chubby Middle & Ring Finger Bumps (to the right of index finger)
            val midFingerRect = RectF(6f, 10f, 22f, 38f)
            val ringFingerRect = RectF(18f, 16f, 33f, 42f)

            paint.style = Paint.Style.FILL
            paint.color = handFillColor
            canvas.drawRoundRect(ringFingerRect, 10f, 10f, paint)
            paint.style = Paint.Style.STROKE
            paint.color = handStrokeColor
            canvas.drawRoundRect(ringFingerRect, 10f, 10f, paint)

            paint.style = Paint.Style.FILL
            paint.color = handFillColor
            canvas.drawRoundRect(midFingerRect, 10f, 10f, paint)
            paint.style = Paint.Style.STROKE
            paint.color = handStrokeColor
            canvas.drawRoundRect(midFingerRect, 10f, 10f, paint)

            // C) Plump Pointing Index Finger (Tip right at 0,0!)
            val indexFingerRect = RectF(-9f, -2f, 10f, 36f)
            paint.style = Paint.Style.FILL
            paint.color = handFillColor
            canvas.drawRoundRect(indexFingerRect, 11f, 11f, paint)
            paint.style = Paint.Style.STROKE
            paint.color = handStrokeColor
            canvas.drawRoundRect(indexFingerRect, 11f, 11f, paint)

            // D) Marshmallow-Puffy Rounded Palm (overlapping finger bases smoothly)
            val palmRect = RectF(-20f, 22f, 32f, 70f)
            paint.style = Paint.Style.FILL
            paint.color = handFillColor
            canvas.drawRoundRect(palmRect, 24f, 24f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.6f
            paint.color = handStrokeColor
            canvas.drawRoundRect(palmRect, 24f, 24f, paint)

            // E) Cute Baymax Wrist Cuff & Tiny Heart/Port Badge on back of hand
            val cuffRect = RectF(-12f, 64f, 24f, 75f)
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#E2E8F0")
            canvas.drawRoundRect(cuffRect, 8f, 8f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = handStrokeColor
            canvas.drawRoundRect(cuffRect, 8f, 8f, paint)

            // Tiny glowing status dot on the back of the cute hand
            paint.style = Paint.Style.FILL
            paint.color = accentColor
            canvas.drawCircle(6f, 46f, 4.5f, paint)

            // Exact Fingertip Touch Indicator Dot at (0, 0)
            paint.color = accentColor
            canvas.drawCircle(0f, 0f, 4f, paint)

            canvas.restore()

            // 3. Sleek Action Label Tag next to the Cute Hand
            val textWidth = textPaint.measureText(currentLabel)
            val tagLeft = (tipX + 42f).coerceAtMost(width - textWidth - 34f).coerceAtLeast(16f)
            val tagTop = (tipY + 18f).coerceAtMost(height - 70f).coerceAtLeast(50f)
            val tagRect = RectF(tagLeft, tagTop, tagLeft + textWidth + 24f, tagTop + 40f)

            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#E6181A20")
            canvas.drawRoundRect(tagRect, 14f, 14f, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.8f
            paint.color = accentColor
            canvas.drawRoundRect(tagRect, 14f, 14f, paint)

            canvas.drawText(currentLabel, tagLeft + 12f, tagTop + 28f, textPaint)
        }
    }
}
