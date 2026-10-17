package com.auradroid.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

data class UiNodeSnapshot(
    val index: Int,
    val node: AccessibilityNodeInfo,
    val packageName: String,
    val windowType: Int,
    val className: String,
    val text: String,
    val contentDesc: String,
    val hintText: String,
    val viewId: String,
    val isEditable: Boolean,
    val isFocused: Boolean,
    val isClickable: Boolean,
    val supportsSetText: Boolean,
    val centerX: Float,
    val centerY: Float
)

class AutoTouchService : AccessibilityService() {

    companion object {
        var instance: AutoTouchService? = null
        var isJobRunning = false
        private const val CHANNEL_ID = "auradroid_keepalive_channel"
    }

    private val crashGuard = CoroutineExceptionHandler { _, throwable ->
        if (throwable is CancellationException) return@CoroutineExceptionHandler
        VideoQueueManager.appendLog(this, "⚠️ Handled Error: ${throwable.message}")
        VideoQueueManager.recordLearnedLesson(this, "Avoid error: ${throwable.message}", "GLOBAL")
        isJobRunning = false
        overlayHud?.setCursorOverlayVisible(false)
        overlayHud?.updateStatus("Error", throwable.message ?: "Stopped", "ERROR")
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob() + crashGuard)
    private var activeJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    var overlayHud: AiOverlayHud? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        recordServiceState(true)
        postKeepAliveNotification("AuraDroid Companion Active & Ready")
        overlayHud = AiOverlayHud(this)
        overlayHud?.show()
        overlayHud?.setCursorOverlayVisible(false)
        overlayHud?.updateStatus("Ready", "Tap 🌐 for Browser", "IDLE")
        VideoQueueManager.appendLog(this, "✅ Modular Companion Connected (Auto-Model & Verification Active)!")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        recordServiceState(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        recordServiceState(false)
        releaseWakeLock()
        overlayHud?.hide()
        super.onDestroy()
        instance = null
        serviceScope.cancel()
    }

    private fun recordServiceState(connected: Boolean) {
        try {
            getSharedPreferences(VideoQueueManager.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("service_connected", connected)
                .putLong("service_timestamp", System.currentTimeMillis())
                .apply()
        } catch (_: Exception) {}
    }

    fun postKeepAliveNotification(statusText: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val chan = NotificationChannel(
                    CHANNEL_ID,
                    "AuraDroid 24/7 Active Companion",
                    NotificationManager.IMPORTANCE_LOW
                )
                nm.createNotificationChannel(chan)
            }
            val notif = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("AuraDroid OS • Verified AI Agent")
                .setContentText(statusText)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            nm.notify(1001, notif)
        } catch (_: Exception) {}
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AuraDroid::AutonomousAgentLock")
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(24 * 60 * 60 * 1000L)
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
    }

    fun stopCurrentJob() {
        isJobRunning = false
        WebPipelineManager.stopCurrentJob()
        activeJob?.cancel()
        releaseWakeLock()
        overlayHud?.setCursorOverlayVisible(false)
        overlayHud?.updateStatus("Stopped", "Task stopped by user", "DONE")
        postKeepAliveNotification("Ready for next instruction")
        VideoQueueManager.appendLog(this, "🛑 All active tasks stopped by user.")
    }

    @Suppress("DEPRECATION")
    fun getRealScreenDimensions(): Pair<Float, Float> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            Pair(bounds.width().toFloat(), bounds.height().toFloat())
        } else {
            val dm = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            Pair(dm.widthPixels.toFloat(), dm.heightPixels.toFloat())
        }
    }

    suspend fun captureScreenshotBase64(): String? {
        if (!VideoQueueManager.isScreenVisionEnabled(this)) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { cont ->
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: ScreenshotResult) {
                            try {
                                val hwBitmap = Bitmap.wrapHardwareBuffer(
                                    screenshotResult.hardwareBuffer,
                                    screenshotResult.colorSpace
                                )
                                val softBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                screenshotResult.hardwareBuffer.close()
                                hwBitmap?.recycle()

                                if (softBitmap != null) {
                                    val scaled = Bitmap.createScaledBitmap(softBitmap, 540, 1200, true)
                                    softBitmap.recycle()
                                    val baos = ByteArrayOutputStream()
                                    scaled.compress(Bitmap.CompressFormat.JPEG, 62, baos)
                                    scaled.recycle()
                                    val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                                    if (cont.isActive) cont.resume(b64)
                                } else {
                                    if (cont.isActive) cont.resume(null)
                                }
                            } catch (e: Exception) {
                                if (cont.isActive) cont.resume(null)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    // ========================================================================
    // MODULAR PIPELINE LAUNCHERS
    // ========================================================================
    fun startGoogleFlowToYouTubePipeline(
        apiKey: String,
        model: String,
        flowUrl: String,
        topicIdea: String,
        dailyVideoCount: Int,
        folderPath: String
    ) {
        activeJob = serviceScope.launch {
            acquireWakeLock()
            try {
                if (flowUrl.isNotBlank()) {
                    VideoQueueManager.savePinnedFlowUrl(this@AutoTouchService, flowUrl)
                }
                WebPipelineManager.startFlowToYouTubePipeline(
                    context = this@AutoTouchService,
                    apiKey = apiKey,
                    model = model,
                    flowTopic = topicIdea,
                    folderPath = folderPath,
                    dailyVideoLimit = dailyVideoCount
                )
                while (WebPipelineManager.isWebTaskRunning) delay(500)
            } finally {
                releaseWakeLock()
            }
        }
    }

    fun startBackgroundWebTask(
        apiKey: String,
        model: String,
        initialUrl: String,
        userGoal: String,
        folderPath: String,
        chainYouTubeAfterDownload: Boolean = false,
        forceChainYouTube: Boolean = false,
        exactFlowPrompt: String = ""
    ) {
        activeJob = serviceScope.launch {
            acquireWakeLock()
            try {
                val effectiveGoal = buildString {
                    append(userGoal)
                    if (exactFlowPrompt.isNotBlank() && !userGoal.contains(exactFlowPrompt)) {
                        append("\n\nUse this exact video prompt: ")
                        append(exactFlowPrompt)
                    }
                    if (chainYouTubeAfterDownload || forceChainYouTube) {
                        append("\n\nAfter the video is downloaded, continue to YouTube Shorts upload and verify the result.")
                    }
                }
                WebPipelineManager.startAutonomousWebAgent(
                    context = this@AutoTouchService,
                    apiKey = apiKey,
                    model = model,
                    goal = effectiveGoal,
                    startUrl = initialUrl,
                    folderPath = folderPath
                )
                while (WebPipelineManager.isWebTaskRunning) delay(500)
            } finally {
                releaseWakeLock()
            }
        }
    }

    fun startYouTubeAutoUpload(
        apiKey: String,
        model: String,
        folderPath: String,
        userPrompt: String,
        createAiVideoIfEmpty: Boolean = false
    ) {
        activeJob = serviceScope.launch {
            acquireWakeLock()
            try {
                WebPipelineManager.runYouTubeAutoUpload(
                    this@AutoTouchService, apiKey, model, folderPath, userPrompt, createAiVideoIfEmpty
                )
            } finally {
                releaseWakeLock()
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun startYouTubeUploadJob(
        context: Context,
        apiKey: String,
        model: String,
        folderPath: String,
        topicInstruction: String
    ) {
        startYouTubeAutoUpload(
            apiKey = apiKey,
            model = model,
            folderPath = folderPath,
            userPrompt = topicInstruction
        )
    }

    fun startUniversalAiTask(
        apiKey: String,
        model: String,
        userGoal: String,
        initialSettingAction: String? = null
    ) {
        activeJob = serviceScope.launch {
            acquireWakeLock()
            try {
                PhoneAgentManager.runUniversalAiTask(
                    this@AutoTouchService, apiKey, model, userGoal, initialSettingAction
                )
            } finally {
                releaseWakeLock()
            }
        }
    }

    // ========================================================================
    // SHARED TEXT ENTRY, CLIPBOARD & GESTURE HELPERS
    // ========================================================================
    suspend fun insertCaptionSingleClean(text: String, boxX: Float, boxY: Float): Boolean {
        copyToClipboard(text)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }

        val focusedInput = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedInput != null) {
            focusedInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        delay(1000)
        if (isTitleAlreadyPresentOnScreen(text)) return true

        val allNodes = PhoneAgentManager.captureScreenNodes(this)
        val keyboardNodes = allNodes.filter {
            it.windowType == AccessibilityWindowInfo.TYPE_INPUT_METHOD
        }
        val titlePrefix = text.take(10).trim()
        val clipboardChip = keyboardNodes.firstOrNull {
            (titlePrefix.isNotEmpty() && (it.text.contains(titlePrefix, true) || it.contentDesc.contains(titlePrefix, true))) ||
            it.text.equals("Paste", true) ||
            it.contentDesc.equals("Paste", true) ||
            it.text.contains("चिपकाएं")
        }

        if (clipboardChip != null) {
            PhoneAgentManager.animateAndPhysicalTapNode(this, clipboardChip, "Tap: Clipboard")
            delay(1600)
            return true
        }

        if (!isTitleAlreadyPresentOnScreen(text)) {
            overlayHud?.moveBotAndShowTap(boxX, boxY, "Hold: Paste")
            performLongPressGesture(boxX, boxY, 700L)
            delay(1500)

            val pastePopupBtn = PhoneAgentManager.captureScreenNodes(this).firstOrNull {
                it.text.equals("Paste", true) ||
                it.contentDesc.equals("Paste", true) ||
                it.text.contains("चिपकाएं", true) ||
                it.contentDesc.contains("चिपकाएं", true)
            }

            if (pastePopupBtn != null) {
                PhoneAgentManager.animateAndPhysicalTapNode(this, pastePopupBtn, "Tap: Paste")
                delay(1400)
                return true
            }
        }

        return isTitleAlreadyPresentOnScreen(text)
    }

    private fun isTitleAlreadyPresentOnScreen(expectedTitle: String): Boolean {
        val prefix = expectedTitle.take(10).trim()
        val appNodes = PhoneAgentManager.captureScreenNodes(this).filter {
            it.windowType != AccessibilityWindowInfo.TYPE_INPUT_METHOD
        }
        val hasTitleWords = prefix.isNotEmpty() && appNodes.any {
            it.text.contains(prefix, true) || it.contentDesc.contains(prefix, true)
        }
        val placeholderGone = appNodes.none {
            it.text.contains("Caption your Short", true) || it.hintText.contains("Caption your Short", true)
        } && appNodes.any {
            it.text.equals("Title", true) || it.contentDesc.contains("Title", true)
        }

        return hasTitleWords || placeholderGone
    }

    fun isKeyboardOpen(): Boolean {
        return try {
            windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        } catch (_: Exception) {
            false
        }
    }

    fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ai_title", text))
    }

    fun clickNodeAndParents(node: AccessibilityNodeInfo): Boolean {
        if (runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)) {
            return true
        }
        var parent = runCatching { node.parent }.getOrNull()
        var depth = 0
        while (parent != null && depth < 5) {
            val currentParent = parent ?: break
            val clicked = runCatching {
                currentParent.isClickable &&
                    currentParent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }.getOrDefault(false)
            if (clicked) return true
            parent = runCatching { currentParent.parent }.getOrNull()
            depth++
        }
        return false
    }

    fun performTapGesture(x: Float, y: Float) {
        val (realW, realH) = getRealScreenDimensions()
        val safeX = x.coerceIn(5f, realW - 5f)
        val safeY = y.coerceIn(5f, realH - 5f)

        val path = Path().apply { moveTo(safeX, safeY) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 150)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    fun performLongPressGesture(x: Float, y: Float, durationMs: Long) {
        val (realW, realH) = getRealScreenDimensions()
        val safeX = x.coerceIn(5f, realW - 5f)
        val safeY = y.coerceIn(5f, realH - 5f)

        val path = Path().apply { moveTo(safeX, safeY) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    fun performScrollDownGesture() {
        // Use the actual display dimensions; fixed 540x1650 coordinates miss on many phones
        // and can turn into unintended gestures near the status/navigation areas.
        val (realW, realH) = getRealScreenDimensions()
        if (realW <= 0f || realH <= 0f) {
            VideoQueueManager.appendLog(this, "🛡️ Scroll blocked: invalid display dimensions.")
            return
        }
        val x = (realW * 0.5f).coerceIn(8f, realW - 8f)
        val startY = (realH * 0.78f).coerceIn(realH * 0.55f, realH - 24f)
        val endY = (realH * 0.34f).coerceIn(realH * 0.12f, startY - 80f)
        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 400)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val accepted = dispatchGesture(gesture, null, null)
        if (!accepted) {
            VideoQueueManager.appendLog(this, "⚠️ Scroll gesture was not accepted by Android; no screen change is assumed.")
        }
    }
}
