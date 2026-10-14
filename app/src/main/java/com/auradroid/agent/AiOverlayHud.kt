package com.auradroid.agent

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.sin

class AiOverlayHud(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var pointerCanvasView: CuteBotCanvasView? = null
    private var miniTabLayout: LinearLayout? = null
    private var tvStepTitle: TextView? = null
    private var tvStepDetail: TextView? = null
    private var botFaceView: MiniBotAvatarView? = null
    private var isAdded = false

    fun show() {
        mainHandler.post {
            if (isAdded) return@post
            try {
                // 1. फुल-स्क्रीन ट्रांसपेरेंट कैनवास (जिसमें Cute Flying AI Bot और Tap Ripple दिखेगा)
                val canvasParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                )
                pointerCanvasView = CuteBotCanvasView(context)
                windowManager.addView(pointerCanvasView, canvasParams)

                // 2. ऊपर दिखने वाला Floating Mini AI Status Tab
                val tabParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = dpToPx(44)
                }

                miniTabLayout = buildMiniTabUi(tabParams)
                windowManager.addView(miniTabLayout, tabParams)
                isAdded = true
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
                miniTabLayout?.let { windowManager.removeView(it) }
            } catch (_: Exception) {
            } finally {
                pointerCanvasView = null
                miniTabLayout = null
                isAdded = false
            }
        }
    }

    fun updateStatus(stepBadge: String, detailMessage: String, mood: String = "WORKING") {
        mainHandler.post {
            if (!isAdded) show()
            tvStepTitle?.text = stepBadge
            tvStepDetail?.text = detailMessage
            botFaceView?.setMood(mood)
        }
    }

    fun moveBotAndShowTap(targetX: Float, targetY: Float, actionLabel: String) {
        mainHandler.post {
            if (!isAdded) show()
            pointerCanvasView?.animateBotTo(targetX, targetY, actionLabel)
        }
    }

    private fun buildMiniTabUi(tabParams: WindowManager.LayoutParams): LinearLayout {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E60B1120"))
                cornerRadius = dpToPx(28).toFloat()
                setStroke(dpToPx(2), Color.parseColor("#00E5FF"))
            }
        }

        // Cute Animated AI Robot Face Icon
        botFaceView = MiniBotAvatarView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(36), dpToPx(36)).apply {
                marginEnd = dpToPx(10)
            }
        }
        root.addView(botFaceView)

        // Live Step & Processing Text
        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dpToPx(200), LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        tvStepTitle = TextView(context).apply {
            text = "AURADROID AI • LIVE"
            setTextColor(Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }

        tvStepDetail = TextView(context).apply {
            text = "Initializing AI Agent..."
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 2
        }

        textCol.addView(tvStepTitle)
        textCol.addView(tvStepDetail)
        root.addView(textCol)

        // Emergency Stop Mini Button inside Floating Tab
        val stopBtn = TextView(context).apply {
            text = "■ STOP"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EF4444"))
                cornerRadius = dpToPx(16).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dpToPx(8)
            }
            setOnClickListener {
                AutoTouchService.instance?.stopCurrentJob()
            }
        }
        root.addView(stopBtn)

        // Allow dragging the Mini Tab up/down on screen
        var initialY = 0
        var touchY = 0f
        root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialY = tabParams.y
                    touchY = event.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    tabParams.y = (initialY + (event.rawY - touchY).toInt()).coerceIn(20, 1600)
                    try {
                        windowManager.updateViewLayout(root, tabParams)
                    } catch (_: Exception) {}
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
     * Mini Animated Robot Avatar inside the Top Floating Tab
     */
    private class MiniBotAvatarView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var pulsePhase = 0f
        private var currentMood = "WORKING"

        private val ticker = object : Runnable {
            override fun run() {
                pulsePhase += 0.18f
                invalidate()
                postDelayed(this, 50)
            }
        }

        init {
            post(ticker)
        }

        fun setMood(mood: String) {
            currentMood = mood
            invalidate()
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(ticker)
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val r = (width.coerceAtMost(height) / 2f) - 4f

            // Outer glowing aura
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = when (currentMood) {
                "DONE" -> Color.parseColor("#10B981")
                "THINKING" -> Color.parseColor("#A855F7")
                else -> Color.parseColor("#00E5FF")
            }
            val auraRadius = r + sin(pulsePhase) * 2.5f
            canvas.drawCircle(cx, cy, auraRadius, paint)

            // Cute Robot Head Fill
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#1E293B")
            canvas.drawCircle(cx, cy, r - 2f, paint)

            // Cute Robot Eyes (Blink occasionally)
            val isBlinking = (sin(pulsePhase * 0.7f) > 0.92f)
            paint.color = if (currentMood == "DONE") Color.parseColor("#10B981") else Color.parseColor("#00E5FF")

            val eyeOffsetX = r * 0.35f
            val eyeY = cy - r * 0.1f
            if (isBlinking) {
                paint.strokeWidth = 3f
                canvas.drawLine(cx - eyeOffsetX - 4f, eyeY, cx - eyeOffsetX + 4f, eyeY, paint)
                canvas.drawLine(cx + eyeOffsetX - 4f, eyeY, cx + eyeOffsetX + 4f, eyeY, paint)
            } else {
                canvas.drawCircle(cx - eyeOffsetX, eyeY, 4.2f, paint)
                canvas.drawCircle(cx + eyeOffsetX, eyeY, 4.2f, paint)
            }

            // Cute Smile Curve
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f
            val smileRect = RectF(cx - r * 0.3f, cy + 1f, cx + r * 0.3f, cy + r * 0.42f)
            canvas.drawArc(smileRect, 15f, 150f, false, paint)
        }
    }

    /**
     * Full-screen Flying Cute AI Bot Cursor + Neon Tap Ripple Animation
     */
    private class CuteBotCanvasView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
        }

        private var botX = 540f
        private var botY = 400f
        private var startX = 540f
        private var startY = 400f
        private var targetX = 540f
        private var targetY = 400f
        private var rippleProgress = 0f
        private var hoverPhase = 0f
        private var currentLabel = "AI Ready"

        private var moveAnimator: ValueAnimator? = null
        private var rippleAnimator: ValueAnimator? = null

        private val hoverRunnable = object : Runnable {
            override fun run() {
                hoverPhase += 0.2f
                invalidate()
                postDelayed(this, 40)
            }
        }

        init {
            post(hoverRunnable)
        }

        fun animateBotTo(destX: Float, destY: Float, label: String) {
            startX = botX
            startY = botY
            targetX = destX
            targetY = destY
            currentLabel = label

            moveAnimator?.cancel()
            moveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 420
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
                startDelay = 350
                duration = 700
                addUpdateListener { anim ->
                    rippleProgress = anim.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun stopAnimations() {
            removeCallbacks(hoverRunnable)
            moveAnimator?.cancel()
            rippleAnimator?.cancel()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // 1. Draw glowing flight trail line from previous point to target
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f
            paint.color = Color.argb(90, 0, 229, 255)
            canvas.drawLine(startX, startY, botX, botY, paint)

            // 2. Draw Expanding Tap Ripple Waves at Target
            if (rippleProgress > 0f && rippleProgress < 1f) {
                val alpha = ((1f - rippleProgress) * 230).toInt().coerceIn(0, 255)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 6f
                paint.color = Color.argb(alpha, 0, 229, 255)
                canvas.drawCircle(targetX, targetY, 20f + rippleProgress * 95f, paint)

                paint.strokeWidth = 4f
                paint.color = Color.argb(alpha, 168, 85, 247)
                canvas.drawCircle(targetX, targetY, 10f + rippleProgress * 55f, paint)
            }

            // 3. Draw Cute Floating AI Drone/Bot at (botX, botY)
            val floatOffsetY = sin(hoverPhase) * 6f
            val cx = botX
            val cy = botY + floatOffsetY

            // Outer Neon Halo
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(65, 0, 229, 255)
            canvas.drawCircle(cx, cy, 42f, paint)

            // Bot Body
            paint.color = Color.parseColor("#0F172A")
            canvas.drawCircle(cx, cy, 28f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4.5f
            paint.color = Color.parseColor("#00E5FF")
            canvas.drawCircle(cx, cy, 28f, paint)

            // Cute Antenna on Top of Bot
            canvas.drawLine(cx, cy - 28f, cx, cy - 40f, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#A855F7")
            canvas.drawCircle(cx, cy - 43f, 6f, paint)

            // Cute Glowing Eyes inside Bot Cursor
            paint.color = Color.parseColor("#00E5FF")
            canvas.drawCircle(cx - 9f, cy - 3f, 4.5f, paint)
            canvas.drawCircle(cx + 9f, cy - 3f, 4.5f, paint)

            // Cute Bot Smile
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            val smile = RectF(cx - 8f, cy + 1f, cx + 8f, cy + 11f)
            canvas.drawArc(smile, 15f, 150f, false, paint)

            // 4. Floating Action Label Tag next to the Cute Bot
            if (currentLabel.isNotEmpty()) {
                val textWidth = textPaint.measureText(currentLabel)
                val tagLeft = (cx + 36f).coerceAtMost(width - textWidth - 40f).coerceAtLeast(16f)
                val tagTop = (cy - 22f).coerceAtLeast(60f)
                val tagRect = RectF(tagLeft, tagTop, tagLeft + textWidth + 28f, tagTop + 46f)

                paint.style = Paint.Style.FILL
                paint.color = Color.argb(220, 15, 23, 42)
                canvas.drawRoundRect(tagRect, 16f, 16f, paint)

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2.5f
                paint.color = Color.parseColor("#00E5FF")
                canvas.drawRoundRect(tagRect, 16f, 16f, paint)

                canvas.drawText(currentLabel, tagLeft + 14f, tagTop + 32f, textPaint)
            }
        }
    }
}
