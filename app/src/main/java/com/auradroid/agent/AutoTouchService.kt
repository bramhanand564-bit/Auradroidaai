package com.auradroid.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.webkit.WebView
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.hypot

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
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var activeJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    var overlayHud: AiOverlayHud? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlayHud = AiOverlayHud(this)
        overlayHud?.show()
        overlayHud?.setCursorOverlayVisible(false)
        VideoQueueManager.appendLog(this, "✅ Universal Companion Connected & Ready!")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        releaseWakeLock()
        overlayHud?.hide()
        super.onDestroy()
        instance = null
        serviceScope.cancel()
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
        activeJob?.cancel()
        isJobRunning = false
        releaseWakeLock()
        overlayHud?.setCursorOverlayVisible(false)
        overlayHud?.updateStatus("Stopped", "Ready for next instruction", "DONE")
        VideoQueueManager.appendLog(this, "🛑 All active tasks stopped by user.")
    }

    @Suppress("DEPRECATION")
    private fun getRealScreenDimensions(): Pair<Float, Float> {
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

    private suspend fun captureScreenshotBase64(): String? {
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
                                    scaled.compress(Bitmap.CompressFormat.JPEG, 60, baos)
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
    // ENGINE 1: BACKGROUND WEB STUDIO AGENT
    // ========================================================================
    fun startBackgroundWebTask(
        apiKey: String,
        model: String,
        initialUrl: String,
        userGoal: String,
        folderPath: String
    ) {
        if (isJobRunning) {
            VideoQueueManager.appendLog(this, "⚠️ A task is already running!")
            return
        }

        activeJob = serviceScope.launch {
            isJobRunning = true
            acquireWakeLock()
            overlayHud?.show()
            overlayHud?.setCursorOverlayVisible(false)

            val startTimeMs = System.currentTimeMillis()
            val webView = overlayHud?.getOrCreateSharedWebView()
            val memoryVault = VideoQueueManager.getMemoryVault(this@AutoTouchService)
            val unstoppable = VideoQueueManager.is24HourUnstoppableEnabled(this@AutoTouchService)
            val stepDelay = VideoQueueManager.getStepDelayMs(this@AutoTouchService)
            val maxSteps = if (unstoppable) 2000 else 45

            try {
                if (initialUrl.isNotBlank() && initialUrl.startsWith("http")) {
                    overlayHud?.updateStatus("Web Pod • Opening", initialUrl, "WORKING")
                    VideoQueueManager.appendLog(this@AutoTouchService, "🌐 Opening in Background Web Pod: $initialUrl")
                    webView?.loadUrl(initialUrl)
                    delay(4500)
                }

                val history = mutableListOf<String>()

                for (step in 1..maxSteps) {
                    if (!isActive) break

                    val newlyDownloaded = VideoQueueManager.findNewlyDownloadedVideo(startTimeMs, folderPath)
                    if (newlyDownloaded != null && userGoal.contains("youtube", true)) {
                        VideoQueueManager.appendLog(
                            this@AutoTouchService,
                            "🎬 Downloaded Video Detected: ${newlyDownloaded.name}! Chaining to YouTube Upload..."
                        )
                        isJobRunning = false
                        startYouTubeAutoUpload(apiKey, model, folderPath, userGoal)
                        return@launch
                    }

                    val currentUrl = webView?.url ?: initialUrl
                    val domSnapshot = extractWebDomSummary(webView)

                    overlayHud?.updateStatus("Web Pod • Step $step", "AI inspecting webpage...", "THINKING")
                    val decision = GeminiBrain.decideNextWebAction(
                        apiKey = apiKey,
                        model = model,
                        goal = userGoal,
                        pageUrl = currentUrl,
                        pageTextAndElements = domSnapshot,
                        history = history,
                        memoryVault = memoryVault
                    )

                    val mood = if (decision.action == "WAIT") "WAITING" else "WORKING"
                    overlayHud?.updateStatus("Web Pod • ${decision.action}", decision.reason, mood)
                    VideoQueueManager.appendLog(this@AutoTouchService, "🌐 Web Step $step [${decision.action}]: ${decision.reason}")
                    history.add("Step $step: ${decision.action} - ${decision.reason}")

                    when (decision.action) {
                        "CLICK_EL" -> performWebJsClick(webView, decision.elementIndex)
                        "TYPE_EL" -> performWebJsType(webView, decision.elementIndex, decision.textToType)
                        "SCROLL" -> webView?.evaluateJavascript("window.scrollBy(0, 550);", null)
                        "LOAD_URL" -> {
                            if (decision.url.startsWith("http")) {
                                webView?.loadUrl(decision.url)
                                delay(3500)
                            }
                        }
                        "DOWNLOAD_URL" -> {
                            if (decision.url.startsWith("http")) webView?.loadUrl(decision.url)
                        }
                        "WAIT" -> delay(5000)
                        "CHAIN_YOUTUBE" -> {
                            isJobRunning = false
                            startYouTubeAutoUpload(apiKey, model, folderPath, userGoal)
                            return@launch
                        }
                        "DONE" -> {
                            overlayHud?.updateStatus("✅ Web Task Done", decision.reason, "DONE")
                            VideoQueueManager.appendLog(this@AutoTouchService, "✅ Background Web Task Completed: ${decision.reason}")
                            break
                        }
                    }

                    delay(stepDelay)
                }
            } catch (e: Exception) {
                overlayHud?.updateStatus("Web Error", e.message ?: "Failed", "ERROR")
                VideoQueueManager.appendLog(this@AutoTouchService, "❌ Web Agent Error: ${e.message}")
            } finally {
                releaseWakeLock()
                isJobRunning = false
            }
        }
    }

    private suspend fun extractWebDomSummary(webView: WebView?): String = suspendCancellableCoroutine { cont ->
        if (webView == null) {
            cont.resume("Empty WebView")
            return@suspendCancellableCoroutine
        }
        val js = """
            (function() {
                var items = document.querySelectorAll('a, button, input, textarea, select, [role="button"], video');
                var out = [];
                for (var i = 0; i < items.length && out.length < 65; i++) {
                    var el = items[i];
                    var rect = el.getBoundingClientRect();
                    if (rect.width > 5 && rect.height > 5) {
                        el.setAttribute('data-aura-idx', i);
                        var tag = el.tagName.toLowerCase();
                        var txt = (el.innerText || el.value || el.placeholder || el.getAttribute('aria-label') || el.src || '').trim().substring(0, 65);
                        out.push('[' + i + '] <' + tag + '> ' + txt);
                    }
                }
                var bodyText = (document.body ? document.body.innerText : '').substring(0, 1400).replace(/\s+/g, ' ');
                return 'PAGE_TEXT: ' + bodyText + '\nELEMENTS:\n' + out.join('\n');
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { result ->
            if (cont.isActive) {
                cont.resume(result?.trim('"')?.replace("\\n", "\n") ?: "No DOM")
            }
        }
    }

    private fun performWebJsClick(webView: WebView?, index: Int) {
        val js = """
            (function() {
                var el = document.querySelector('[data-aura-idx="$index"]');
                if (el) { el.scrollIntoView({block:'center'}); el.click(); return true; }
                return false;
            })();
        """.trimIndent()
        webView?.evaluateJavascript(js, null)
    }

    private fun performWebJsType(webView: WebView?, index: Int, text: String) {
        val safeText = JSONObject.quote(text)
        val js = """
            (function() {
                var el = document.querySelector('[data-aura-idx="$index"]') || document.querySelector('textarea, input[type="text"], input:not([type])');
                if (el) {
                    el.focus();
                    el.value = $safeText;
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                    return true;
                }
                return false;
            })();
        """.trimIndent()
        webView?.evaluateJavascript(js, null)
    }

    // ========================================================================
    // ENGINE 2: TESTED & WORKING YOUTUBE SHORTS AUTO-UPLOADER
    // ========================================================================
    fun startYouTubeAutoUpload(
        apiKey: String,
        model: String,
        folderPath: String,
        userPrompt: String
    ) {
        if (isJobRunning) {
            VideoQueueManager.appendLog(this, "⚠️ A task is already running!")
            return
        }

        activeJob = serviceScope.launch {
            isJobRunning = true
            acquireWakeLock()
            overlayHud?.show()
            overlayHud?.setCursorOverlayVisible(true)
            try {
                overlayHud?.updateStatus("Step 1/5 • Folder", "Scanning folder for video...", "WORKING")
                val nextVideo = VideoQueueManager.getNextPendingVideo(this@AutoTouchService, folderPath)
                if (nextVideo == null) {
                    overlayHud?.updateStatus("Queue Empty", "Tap 'Reset History' or add video!", "ERROR")
                    VideoQueueManager.appendLog(this@AutoTouchService, "⚠️ No pending video in folder!")
                    delay(3500)
                    overlayHud?.setCursorOverlayVisible(false)
                    isJobRunning = false
                    return@launch
                }

                overlayHud?.updateStatus("Step 2/5 • Gemini", "Generating viral Title...", "THINKING")
                val metadata = GeminiBrain.generateYouTubeMetadata(
                    apiKey = apiKey,
                    model = model,
                    fileName = nextVideo.name,
                    userInstruction = userPrompt
                )

                copyToClipboard(metadata.title)
                overlayHud?.updateStatus("Step 3/5 • YouTube", "Opening YouTube Upload...", "WORKING")
                launchYouTubeWithVideo(nextVideo)
                delay(5500)

                var nextClickCount = 0
                var titleFilled = false
                var descFilled = false
                val (realW, realH) = getRealScreenDimensions()

                for (step in 1..25) {
                    if (!isActive) break
                    val nodes = captureScreenNodes()
                    val appNodes = nodes.filter { it.windowType != AccessibilityWindowInfo.TYPE_INPUT_METHOD }

                    if (appNodes.isEmpty() && nextClickCount < 2) {
                        delay(2000)
                        continue
                    }

                    val nextBtn = appNodes.firstOrNull {
                        it.text.equals("Next", true) ||
                        it.contentDesc.equals("Next", true) ||
                        it.text.equals("Done", true) ||
                        it.contentDesc.equals("Done", true)
                    }

                    val isOnAddDetailsScreen = isAddDetailsScreenVisible(appNodes) || (nextClickCount >= 2 && nextBtn == null)

                    if (!isOnAddDetailsScreen && !titleFilled && nextBtn != null) {
                        nextClickCount++
                        overlayHud?.updateStatus("Step 3/5 • Editor ($nextClickCount)", "Tapping 'Next'...", "WORKING")
                        animateAndPhysicalTapNode(nextBtn, "Tap: Next")
                        delay(4000)
                        continue
                    }

                    if (!titleFilled && (isOnAddDetailsScreen || nextClickCount >= 2)) {
                        copyToClipboard(metadata.title)
                        val captionNode = appNodes.firstOrNull {
                            val combined = "${it.text} ${it.contentDesc} ${it.hintText} ${it.viewId}"
                            combined.contains("Caption your Short", true) ||
                            combined.contains("Create a title", true) ||
                            combined.contains("caption", true)
                        } ?: appNodes.firstOrNull { it.isEditable || it.supportsSetText }

                        val capX = captionNode?.centerX ?: (realW * 0.52f)
                        val capY = captionNode?.centerY ?: (realH * 0.138f)

                        overlayHud?.updateStatus("Step 4/5 • Caption", "Focusing Caption Box...", "WORKING")
                        animateAndTapXY(capX, capY, "Tap: Caption")
                        delay(2200)

                        overlayHud?.updateStatus("Step 4/5 • Typing", "Pasting AI Title...", "WORKING")
                        insertCaptionSingleClean(metadata.title, capX, capY)

                        titleFilled = true
                        delay(1800)
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        delay(2800)
                        continue
                    }

                    if (titleFilled && !descFilled) {
                        val addDescNode = appNodes.firstOrNull {
                            "${it.text} ${it.contentDesc} ${it.hintText}".contains("Add description", true)
                        }
                        if (addDescNode != null) {
                            animateAndPhysicalTapNode(addDescNode, "Tap: Description")
                            delay(2000)
                            insertCaptionSingleClean(metadata.description, realW * 0.5f, realH * 0.25f)
                            descFilled = true
                            delay(1800)
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            delay(2200)
                            continue
                        } else {
                            descFilled = true
                        }
                    }

                    if (titleFilled) {
                        if (isKeyboardOpen()) {
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            delay(2500)
                        }

                        val exactUploadX = realW * 0.50f
                        val exactUploadYPoints = listOf(realH * 0.964f, realH * 0.958f, realH * 0.968f)

                        for (attempt in 1..3) {
                            val currentNodes = captureScreenNodes().filter {
                                it.windowType != AccessibilityWindowInfo.TYPE_INPUT_METHOD
                            }
                            val onMainYouTubeFeed = currentNodes.any {
                                it.text.equals("Subscriptions", true) || it.text.equals("Home", true)
                            } && !isAddDetailsScreenVisible(currentNodes)

                            if (onMainYouTubeFeed && attempt > 1) break

                            val uploadBtnNode = currentNodes.firstOrNull {
                                val c = "${it.text} ${it.contentDesc} ${it.hintText} ${it.viewId}"
                                it.centerY > realH * 0.88f && (
                                    c.contains("Upload Short", true) ||
                                    it.text.equals("Upload", true) ||
                                    c.contains("upload_bottom_button", true)
                                )
                            }

                            val tapX = uploadBtnNode?.centerX ?: exactUploadX
                            val tapY = if (uploadBtnNode != null && uploadBtnNode.centerY >= realH * 0.94f) {
                                uploadBtnNode.centerY
                            } else {
                                exactUploadYPoints[(attempt - 1) % exactUploadYPoints.size]
                            }

                            overlayHud?.updateStatus("Step 5/5 • Upload", "Tapping 'Upload Short' (#$attempt)...", "WORKING")
                            animateAndTapXY(tapX, tapY, "Tap: Upload Short")
                            delay(600)
                            if (uploadBtnNode != null) clickNodeAndParents(uploadBtnNode.node)
                            delay(3500)
                        }

                        VideoQueueManager.markVideoUploaded(this@AutoTouchService, nextVideo)
                        overlayHud?.updateStatus("🎉 Upload Success!", "'${nextVideo.name.take(18)}' Published!", "DONE")
                        delay(3500)
                        break
                    }
                    delay(2000)
                }
            } catch (e: Exception) {
                overlayHud?.updateStatus("Upload Error", e.message ?: "Error", "ERROR")
                VideoQueueManager.appendLog(this@AutoTouchService, "❌ Error: ${e.message}")
            } finally {
                overlayHud?.setCursorOverlayVisible(false)
                releaseWakeLock()
                isJobRunning = false
            }
        }
    }

    // ========================================================================
    // ENGINE 3: UNIVERSAL 24/7 PHONE AGENT (With Smart 3-Dot & Coordinate Fix!)
    // ========================================================================
    fun startUniversalAiTask(
        apiKey: String,
        model: String,
        userGoal: String
    ) {
        if (isJobRunning) {
            VideoQueueManager.appendLog(this, "⚠️ Agent is already busy!")
            return
        }

        activeJob = serviceScope.launch {
            isJobRunning = true
            acquireWakeLock()
            overlayHud?.show()
            overlayHud?.setCursorOverlayVisible(true)

            val (realW, realH) = getRealScreenDimensions()
            val memoryVault = VideoQueueManager.getMemoryVault(this@AutoTouchService)
            val unstoppable = VideoQueueManager.is24HourUnstoppableEnabled(this@AutoTouchService)
            val stepDelay = VideoQueueManager.getStepDelayMs(this@AutoTouchService)
            val maxSteps = if (unstoppable) 2000 else 35

            try {
                overlayHud?.updateStatus("Universal Agent", "Goal: ${userGoal.take(28)}...", "THINKING")
                VideoQueueManager.appendLog(this@AutoTouchService, "🤖 Starting Universal Phone Agent: $userGoal")
                val history = mutableListOf<String>()
                var lastTapX = -1f
                var lastTapY = -1f
                var repeatTapCount = 0

                for (step in 1..maxSteps) {
                    if (!isActive) break
                    val nodes = captureScreenNodes()
                    val screenDump = nodes.joinToString("\n") {
                        "[${it.index}] text='${it.text}' desc='${it.contentDesc}' hint='${it.hintText}' id='${it.viewId}' edit=${it.isEditable} click=${it.isClickable} xy=(${it.centerX.toInt()},${it.centerY.toInt()})"
                    }

                    val screenshotB64 = captureScreenshotBase64()

                    overlayHud?.updateStatus("Step $step • Thinking", "Reading screen & vision...", "THINKING")
                    val decision = GeminiBrain.decideNextUiAction(
                        apiKey = apiKey,
                        model = model,
                        goal = userGoal,
                        screenSummary = screenDump.take(6500),
                        history = history,
                        memoryVault = memoryVault,
                        screenshotBase64 = screenshotB64,
                        screenWidth = realW.toInt(),
                        screenHeight = realH.toInt()
                    )

                    val stepMood = when (decision.action) {
                        "DONE" -> "DONE"
                        "WAIT" -> "WAITING"
                        else -> "WORKING"
                    }
                    overlayHud?.updateStatus("Step $step • ${decision.action}", decision.reason, stepMood)
                    VideoQueueManager.appendLog(this@AutoTouchService, "🤖 Step $step [${decision.action}]: ${decision.reason}")

                    if (decision.action.equals("DONE", true)) {
                        overlayHud?.updateStatus("🎉 Task Complete!", decision.reason, "DONE")
                        VideoQueueManager.appendLog(this@AutoTouchService, "✅ Task Completed: ${decision.reason}")
                        delay(3000)
                        break
                    }

                    val executedSummary = executeSmartDecision(
                        decision = decision,
                        nodes = nodes,
                        realW = realW,
                        realH = realH,
                        lastX = lastTapX,
                        lastY = lastTapY,
                        repeatCount = repeatTapCount
                    ) { tapX, tapY ->
                        if (hypot(tapX - lastTapX, tapY - lastTapY) < 25f) {
                            repeatTapCount++
                        } else {
                            repeatTapCount = 0
                        }
                        lastTapX = tapX
                        lastTapY = tapY
                    }

                    history.add("Step $step: ${decision.action} ($executedSummary) - ${decision.reason}")
                    delay(stepDelay)
                }
            } catch (e: Exception) {
                overlayHud?.updateStatus("Agent Error", e.message ?: "Failed", "ERROR")
                VideoQueueManager.appendLog(this@AutoTouchService, "❌ Agent Error: ${e.message}")
                delay(3000)
            } finally {
                overlayHud?.setCursorOverlayVisible(false)
                releaseWakeLock()
                isJobRunning = false
            }
        }
    }

    /**
     * SMART TARGET RESOLVER & MAGNETIC BUTTON SNAPPER:
     * 1. Fixes the "Three-Dot Menu" tap bug by snapping directly to 'More options' / 'अधिक विकल्प' or the top-right action bar node.
     * 2. Converts 0..1000 normalized coordinates to actual screen pixels (1080x2408) if Gemini outputs normalized Y inside the status bar.
     * 3. Magnet-snaps any CLICK_XY to the nearest clickable UI node within 140px so touches never miss!
     */
    private suspend fun executeSmartDecision(
        decision: AgentDecision,
        nodes: List<UiNodeSnapshot>,
        realW: Float,
        realH: Float,
        lastX: Float,
        lastY: Float,
        repeatCount: Int,
        onTapRecorded: (Float, Float) -> Unit
    ): String {
        val reasonLower = decision.reason.lowercase()

        when (decision.action.uppercase()) {
            "OPEN_APP" -> {
                if (decision.appPackageOrSetting.isNotBlank()) {
                    openSystemSettingOrApp(decision.appPackageOrSetting)
                    return "Opened ${decision.appPackageOrSetting}"
                }
            }
            "CLICK_NODE", "CLICK_XY" -> {
                // 1. Semantic Check: Is the AI trying to click the 3-dot menu / More options?
                val wantsThreeDotMenu = reasonLower.contains("three-dot") ||
                        reasonLower.contains("3-dot") ||
                        reasonLower.contains("three dot") ||
                        reasonLower.contains("more options") ||
                        reasonLower.contains("menu")

                if (wantsThreeDotMenu) {
                    val threeDotNode = nodes.firstOrNull {
                        it.contentDesc.contains("More options", true) ||
                        it.contentDesc.contains("अधिक विकल्प", true) ||
                        it.viewId.contains("menuitem_overflow", true) ||
                        it.viewId.contains("action_more", true)
                    } ?: nodes.filter {
                        // Top-right App Bar region (below status bar: y between 120px and 280px, x on right 22% of screen)
                        it.centerX > realW * 0.78f && it.centerY in (realH * 0.05f)..(realH * 0.13f)
                    }.maxByOrNull { it.centerX }

                    if (threeDotNode != null) {
                        VideoQueueManager.appendLog(
                            this,
                            "🎯 Magnetic Snap to 3-Dot Menu ('${threeDotNode.contentDesc.ifEmpty { "Overflow" }}') at (${threeDotNode.centerX.toInt()},${threeDotNode.centerY.toInt()})"
                        )
                        onTapRecorded(threeDotNode.centerX, threeDotNode.centerY)
                        animateAndPhysicalTapNode(threeDotNode, "Tap: 3-Dot Menu")
                        return "Tapped 3-Dot Node (${threeDotNode.centerX.toInt()},${threeDotNode.centerY.toInt()})"
                    }
                }

                // 2. If CLICK_NODE was chosen and valid, tap that exact node
                if (decision.action.equals("CLICK_NODE", true)) {
                    val targetNode = nodes.firstOrNull { it.index == decision.nodeIndex }
                    if (targetNode != null) {
                        val label = targetNode.text.ifEmpty { targetNode.contentDesc }.take(15).ifEmpty { "Button" }
                        onTapRecorded(targetNode.centerX, targetNode.centerY)
                        animateAndPhysicalTapNode(targetNode, "Tap: $label")
                        return "Tapped Node #${targetNode.index} ($label)"
                    }
                }

                // 3. Coordinate Normalization & Status-Bar Guard for CLICK_XY
                var rawX = decision.x
                var rawY = decision.y

                // If Gemini returned 0..1000 normalized coordinates (e.g. y=74 for top app bar on a 2408px screen!)
                val statusBarLimitY = realH * 0.052f // ~125px on 2408 height
                if (rawY in 10f..statusBarLimitY && rawX <= 1000f) {
                    val convertedX = (rawX / 1000f) * realW
                    val convertedY = (rawY / 1000f) * realH
                    VideoQueueManager.appendLog(
                        this,
                        "🔧 Auto-Converted 1000-Grid (${rawX.toInt()},${rawY.toInt()}) -> Real Pixels (${convertedX.toInt()},${convertedY.toInt()})"
                    )
                    rawX = convertedX
                    rawY = convertedY.coerceAtLeast(statusBarLimitY + 35f)
                } else if (rawY < statusBarLimitY) {
                    // Never allow tapping inside the Android Status Bar! Push down to the App Toolbar
                    rawY = (statusBarLimitY + 55f)
                }

                // 4. Magnetic Snap: Find the closest clickable UI node within 145px
                val nearestNode = nodes.filter {
                    it.centerY >= statusBarLimitY && (it.isClickable || it.text.isNotEmpty() || it.contentDesc.isNotEmpty())
                }.minByOrNull {
                    hypot(it.centerX - rawX, it.centerY - rawY)
                }

                if (nearestNode != null && hypot(nearestNode.centerX - rawX, nearestNode.centerY - rawY) <= 145f) {
                    val label = nearestNode.text.ifEmpty { nearestNode.contentDesc }.take(14).ifEmpty { "Tap" }
                    VideoQueueManager.appendLog(
                        this,
                        "🧲 Magnet-Snapped XY(${rawX.toInt()},${rawY.toInt()}) -> Node '$label' (${nearestNode.centerX.toInt()},${nearestNode.centerY.toInt()})"
                    )
                    onTapRecorded(nearestNode.centerX, nearestNode.centerY)
                    animateAndPhysicalTapNode(nearestNode, "Tap: $label")
                    return "Magnet-Tapped '$label' (${nearestNode.centerX.toInt()},${nearestNode.centerY.toInt()})"
                }

                // 5. Anti-Stuck Guard: If we already tapped this exact spot 2+ times, shift slightly or pick a matching keyword node
                if (repeatCount >= 2 && nearestNode != null) {
                    onTapRecorded(nearestNode.centerX, nearestNode.centerY)
                    animateAndPhysicalTapNode(nearestNode, "Retry Tap")
                    return "Anti-Stuck Node Tap (${nearestNode.centerX.toInt()},${nearestNode.centerY.toInt()})"
                }

                onTapRecorded(rawX, rawY)
                animateAndTapXY(rawX, rawY, "Tap: (${rawX.toInt()},${rawY.toInt()})")
                return "Tapped XY (${rawX.toInt()},${rawY.toInt()})"
            }
            "TYPE" -> {
                val target = nodes.firstOrNull { it.index == decision.nodeIndex }
                    ?: nodes.firstOrNull { it.isEditable || it.supportsSetText }
                val tx = target?.centerX ?: (realW * 0.5f)
                val ty = target?.centerY ?: (realH * 0.25f)
                animateAndTapXY(tx, ty, "Focus Input")
                delay(1200)
                insertCaptionSingleClean(decision.textToType, tx, ty)
                return "Typed '${decision.textToType.take(15)}'"
            }
            "SCROLL" -> {
                performScrollDownGesture()
                return "Scrolled Down"
            }
            "BACK" -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                return "Pressed Back"
            }
        }
        return "Waited"
    }

    private fun openSystemSettingOrApp(target: String) {
        val cleanTarget = target.trim()
        val actionIntent = when (cleanTarget.uppercase()) {
            "SETTINGS" -> Intent(Settings.ACTION_SETTINGS)
            "BATTERY" -> Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
            "WIFI" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            "DISPLAY" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "STORAGE" -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
            "SOUND" -> Intent(Settings.ACTION_SOUND_SETTINGS)
            "BLUETOOTH" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "APPS" -> Intent(Settings.ACTION_APPLICATION_SETTINGS)
            "WHATSAPP" -> packageManager.getLaunchIntentForPackage("com.whatsapp")
                ?: packageManager.getLaunchIntentForPackage("com.whatsapp.w4b")
            else -> packageManager.getLaunchIntentForPackage(cleanTarget)
        } ?: Intent(Settings.ACTION_SETTINGS)

        try {
            actionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(actionIntent)
        } catch (_: Exception) {
            val fallback = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(fallback)
        }
    }

    private suspend fun insertCaptionSingleClean(text: String, boxX: Float, boxY: Float): Boolean {
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

        val allNodes = captureScreenNodes()
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
            animateAndPhysicalTapNode(clipboardChip, "Tap: Clipboard")
            delay(1600)
            return true
        }

        if (!isTitleAlreadyPresentOnScreen(text)) {
            overlayHud?.moveBotAndShowTap(boxX, boxY, "Hold: Paste")
            performLongPressGesture(boxX, boxY, 700L)
            delay(1500)

            val pastePopupBtn = captureScreenNodes().firstOrNull {
                it.text.equals("Paste", true) ||
                it.contentDesc.equals("Paste", true) ||
                it.text.contains("चिपकाएं", true) ||
                it.contentDesc.contains("चिपकाएं", true)
            }

            if (pastePopupBtn != null) {
                animateAndPhysicalTapNode(pastePopupBtn, "Tap: Paste")
                delay(1400)
                return true
            }
        }

        return isTitleAlreadyPresentOnScreen(text)
    }

    private fun isTitleAlreadyPresentOnScreen(expectedTitle: String): Boolean {
        val prefix = expectedTitle.take(10).trim()
        val appNodes = captureScreenNodes().filter {
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

    private fun isAddDetailsScreenVisible(appNodes: List<UiNodeSnapshot>): Boolean {
        return appNodes.any {
            val c = "${it.text} ${it.contentDesc}${it.hintText}"
            c.contains("Add details", true) ||
            c.contains("Caption your Short", true) ||
            c.contains("Upload Short", true) ||
            c.contains("Select audience", true) ||
            c.contains("Visibility", true)
        }
    }

    private fun isKeyboardOpen(): Boolean {
        return try {
            windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        } catch (_: Exception) {
            false
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ai_title", text))
    }

    private fun launchYouTubeWithVideo(videoFile: File) {
        val uri = FileProvider.getUriForFile(
            this,
            "${applicationContext.packageName}.provider",
            videoFile
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            setPackage("com.google.android.youtube")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            val fallback = packageManager.getLaunchIntentForPackage("com.google.android.youtube")
            if (fallback != null) {
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(fallback)
            }
        }
    }

    /**
     * Filters out SystemUI Status Bar (`com.android.systemui`) & our own Overlay so the AI
     * only sees real interactive App Nodes and Keyboard Nodes!
     */
    private fun captureScreenNodes(): List<UiNodeSnapshot> {
        val list = mutableListOf<UiNodeSnapshot>()
        var counter = 0

        val windowRoots = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()
        try {
            for (win in windows) {
                val r = win.root ?: continue
                val pkg = r.packageName?.toString() ?: ""
                if (pkg != packageName && !pkg.contains("systemui", true)) {
                    windowRoots.add(Pair(win.type, r))
                }
            }
        } catch (_: Exception) {}

        if (windowRoots.isEmpty()) {
            rootInActiveWindow?.let {
                val pkg = it.packageName?.toString() ?: ""
                if (pkg != packageName && !pkg.contains("systemui", true)) {
                    windowRoots.add(Pair(AccessibilityWindowInfo.TYPE_APPLICATION, it))
                }
            }
        }

        fun traverse(node: AccessibilityNodeInfo?, winType: Int) {
            if (node == null) return
            val pkg = node.packageName?.toString() ?: ""
            if (pkg == packageName || pkg.contains("systemui", true)) return

            val cls = node.className?.toString() ?: ""
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.hintText?.toString()?.trim() ?: ""
            } else ""
            val viewId = node.viewIdResourceName?.substringAfter("/") ?: ""
            val clickable = node.isClickable
            val focused = node.isFocused || node.isAccessibilityFocused
            val actionIds = node.actionList?.map { it.id } ?: emptyList()
            val canSetText = actionIds.contains(AccessibilityNodeInfo.ACTION_SET_TEXT) ||
                             actionIds.contains(AccessibilityNodeInfo.ACTION_PASTE)
            val editable = node.isEditable || cls.contains("EditText", true) || canSetText

            if (text.isNotEmpty() || desc.isNotEmpty() || hint.isNotEmpty() || clickable || editable || focused) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    list.add(
                        UiNodeSnapshot(
                            index = counter++,
                            node = node,
                            packageName = pkg,
                            windowType = winType,
                            className = cls,
                            text = text,
                            contentDesc = desc,
                            hintText = hint,
                            viewId = viewId,
                            isEditable = editable,
                            isFocused = focused,
                            isClickable = clickable,
                            supportsSetText = canSetText,
                            centerX = rect.exactCenterX(),
                            centerY = rect.exactCenterY()
                        )
                    )
                }
            }

            for (i in 0 until node.childCount) {
                traverse(node.getChild(i), winType)
            }
        }

        for ((wType, root) in windowRoots) {
            traverse(root, wType)
        }
        return list
    }

    private fun clickNodeAndParents(node: AccessibilityNodeInfo): Boolean {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 5) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            parent = parent.parent
            depth++
        }
        return false
    }

    private suspend fun animateAndPhysicalTapNode(snapshot: UiNodeSnapshot, label: String) {
        overlayHud?.moveBotAndShowTap(snapshot.centerX, snapshot.centerY, label)
        delay(420)
        performTapGesture(snapshot.centerX, snapshot.centerY)
        delay(180)
        clickNodeAndParents(snapshot.node)
    }

    private suspend fun animateAndTapXY(x: Float, y: Float, label: String) {
        overlayHud?.moveBotAndShowTap(x, y, label)
        delay(420)
        performTapGesture(x, y)
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

    private fun performLongPressGesture(x: Float, y: Float, durationMs: Long) {
        val (realW, realH) = getRealScreenDimensions()
        val safeX = x.coerceIn(5f, realW - 5f)
        val safeY = y.coerceIn(5f, realH - 5f)

        val path = Path().apply { moveTo(safeX, safeY) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    private fun performScrollDownGesture() {
        val path = Path().apply {
            moveTo(540f, 1650f)
            lineTo(540f, 550f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 400)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }
}
