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
import androidx.core.app.NotificationCompat
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
        VideoQueueManager.appendLog(this, "✅ Universal Companion Connected (SQLite Brain + Multi-Frame Vision Active)!")
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

    private fun postKeepAliveNotification(statusText: String) {
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
                .setContentTitle("AuraDroid OS • Self-Learning AI")
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
        activeJob?.cancel()
        releaseWakeLock()
        overlayHud?.setCursorOverlayVisible(false)
        overlayHud?.updateStatus("Stopped", "Task stopped by user", "DONE")
        postKeepAliveNotification("Ready for next instruction")
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
    // ENGINE 1: GOOGLE FLOW (1000 CREDITS) 8s VIDEO ➔ YOUTUBE DAILY PIPELINE
    // ========================================================================
    fun startGoogleFlowToYouTubePipeline(
        apiKey: String,
        model: String,
        flowUrl: String,
        topicIdea: String,
        dailyVideoCount: Int,
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

            try {
                overlayHud?.updateStatus(
                    "Flow 8s (Credit Lock: 1)",
                    "Creating policy-safe 8s prompt...",
                    "THINKING"
                )
                val freshPrompt = GeminiBrain.generateDailyFlowPrompt(apiKey, model, topicIdea)
                VideoQueueManager.appendLog(this@AutoTouchService, "🎬 Safe Flow 8s Prompt: $freshPrompt")

                val goal = """
                    We are on Google Flow ($flowUrl).
                    1. If on landing page, click '+ New project'.
                    2. Ensure mode is set to 'Video • 720p • 8s • x1' (NOT Nano Banana Image mode).
                    3. Type this exact 8-second video prompt ONCE into 'What do you want to create?': "$freshPrompt"
                    4. Wait while the 1 video renders from 0% to 100%, then open the finished video card, download it (DOWNLOAD_VIDEO) and CHAIN_YOUTUBE.
                """.trimIndent()

                isJobRunning = false
                startBackgroundWebTask(
                    apiKey = apiKey,
                    model = model,
                    initialUrl = flowUrl,
                    userGoal = goal,
                    folderPath = folderPath,
                    chainYouTubeAfterDownload = true,
                    forceChainYouTube = true,
                    exactFlowPrompt = freshPrompt
                )
            } catch (e: CancellationException) {
                // Normal stop
            } catch (e: Exception) {
                isJobRunning = false
                overlayHud?.updateStatus("Flow Error", e.message ?: "Error", "ERROR")
                VideoQueueManager.appendLog(this@AutoTouchService, "❌ Flow Pipeline Error: ${e.message}")
            }
        }
    }

    // ========================================================================
    // ENGINE 2: BACKGROUND WEB STUDIO AGENT (Fixed Rendering % + Auto Card Download!)
    // ========================================================================
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
        if (isJobRunning) {
            VideoQueueManager.appendLog(this, "⚠️ A task is already running!")
            return
        }

        val shouldChainYt = chainYouTubeAfterDownload || forceChainYouTube

        activeJob = serviceScope.launch {
            isJobRunning = true
            acquireWakeLock()
            postKeepAliveNotification("Running Background Web Agent...")
            overlayHud?.show()
            overlayHud?.setCursorOverlayVisible(false)

            val startTimeMs = System.currentTimeMillis()
            val webView = overlayHud?.getOrCreateSharedWebView()
            val memoryVault = VideoQueueManager.getMemoryVault(this@AutoTouchService)
            val unstoppable = VideoQueueManager.is24HourUnstoppableEnabled(this@AutoTouchService)
            val stepDelay = VideoQueueManager.getStepDelayMs(this@AutoTouchService)
            val maxSteps = if (unstoppable) 2000 else 80

            var flowPromptSubmitted = false
            var submittedAtTimeMs = 0L
            var downloadAttempts = 0
            var previousWebScreenshotB64: String? = null
            var previousDomHash = 0

            try {
                // If already inside a Google Flow project or landing page, DO NOT reload & lose the open project!
                val currentLoadedUrl = webView?.url ?: ""
                val alreadyOnFlow = currentLoadedUrl.contains("labs.google/fx", true) &&
                        initialUrl.contains("labs.google/fx", true)

                if (initialUrl.isNotBlank() && initialUrl.startsWith("http") && !alreadyOnFlow &&
                    !currentLoadedUrl.startsWith(initialUrl.take(22), true)
                ) {
                    overlayHud?.updateStatus("Web Pod • Opening", initialUrl, "WORKING")
                    VideoQueueManager.appendLog(this@AutoTouchService, "🌐 Opening in Browser: $initialUrl")
                    webView?.loadUrl(initialUrl)
                    delay(5000)
                }

                val history = mutableListOf<String>()

                for (step in 1..maxSteps) {
                    if (!isActive) break

                    // Check if the new MP4 file has finished downloading into AutoVideos / Downloads!
                    val newlyDownloaded = VideoQueueManager.findNewlyDownloadedVideo(startTimeMs, folderPath)
                    if (newlyDownloaded != null && (shouldChainYt || userGoal.contains("youtube", true))) {
                        VideoQueueManager.appendLog(
                            this@AutoTouchService,
                            "🎬 Downloaded 8s Video Ready: ${newlyDownloaded.name}! Launching YouTube Auto-Upload..."
                        )
                        isJobRunning = false
                        startYouTubeAutoUpload(apiKey, model, folderPath, userGoal)
                        return@launch
                    }

                    val currentUrl = webView?.url ?: initialUrl

                    if (!flowPromptSubmitted && currentUrl.contains("labs.google/fx", true)) {
                        val modeFix = ensureGoogleFlowVideo8sMode(webView)
                        if (modeFix.startsWith("SWITCHED")) {
                            VideoQueueManager.appendLog(this@AutoTouchService, "⚙️ Auto-Configured Google Flow Mode: $modeFix")
                            delay(1800)
                        }
                    }

                    // HARD 1-VIDEO CREDIT LOCK + ACCURATE RENDERING & DOWNLOAD LOOP
                    if (flowPromptSubmitted) {
                        val elapsedSec = (System.currentTimeMillis() - submittedAtTimeMs) / 1000L
                        val renderState = checkGoogleFlowRenderState(webView)

                        // 1. For the first 12 seconds OR while ANY percentage (0%..100%) is visible on the card, WAIT!
                        if (elapsedSec < 12L || renderState.startsWith("RENDERING:")) {
                            val pct = renderState.removePrefix("RENDERING:").ifBlank { "${elapsedSec}s" }
                            overlayHud?.updateStatus(
                                "Flow Rendering ($pct)",
                                "Waiting for 8s video to finish...",
                                "WAITING"
                            )
                            VideoQueueManager.appendLog(
                                this@AutoTouchService,
                                "⏳ Google Flow Video Rendering: $pct (Elapsed: ${elapsedSec}s)..."
                            )
                            delay(4500)
                            continue
                        }

                        // 2. If Google Flow rejected the prompt, log it clearly
                        if (renderState.startsWith("FAILED:")) {
                            overlayHud?.updateStatus("Flow Policy Error", "Prompt rejected by Google Flow", "ERROR")
                            VideoQueueManager.appendLog(this@AutoTouchService, "❌ Google Flow Card Failed: $renderState")
                            isJobRunning = false
                            return@launch
                        }

                        // 3. Video is 100% READY (thumbnail/player visible, no % text)!
                        // Click the finished card to open the player, then extract & download the MP4!
                        downloadAttempts++
                        overlayHud?.updateStatus(
                            "Flow Ready • Saving",
                            "Opening video & downloading MP4 (#$downloadAttempts)...",
                            "WORKING"
                        )
                        val dlStatus = triggerVideoDownloadInWebView(webView, "")
                        VideoQueueManager.appendLog(
                            this@AutoTouchService,
                            "📥 Auto-Download Step #$downloadAttempts ($renderState): $dlStatus"
                        )
                        delay(4500)

                        val downloadedNow = VideoQueueManager.findNewlyDownloadedVideo(startTimeMs, folderPath)
                        if (downloadedNow != null) {
                            VideoQueueManager.recordLearnedLesson(
                                this@AutoTouchService,
                                "Google Flow video downloaded after $downloadAttempts card/player steps.",
                                "GOOGLE_FLOW"
                            )
                            VideoQueueManager.appendLog(
                                this@AutoTouchService,
                                "✅ Video Downloaded Successfully: ${downloadedNow.name}! Starting YouTube Upload..."
                            )
                            isJobRunning = false
                            startYouTubeAutoUpload(apiKey, model, folderPath, userGoal)
                            return@launch
                        }

                        if (downloadAttempts >= 12) {
                            VideoQueueManager.appendLog(
                                this@AutoTouchService,
                                "⚠️ Download attempts finished, checking folder queue..."
                            )
                            isJobRunning = false
                            startYouTubeAutoUpload(apiKey, model, folderPath, userGoal)
                            return@launch
                        }
                        continue
                    }

                    val domSnapshot = extractWebDomAndDrawBadges(webView)
                    delay(180)

                    val currentWebScreenshotB64 = if (VideoQueueManager.isScreenVisionEnabled(this@AutoTouchService)) {
                        overlayHud?.captureWebViewScreenshotBase64()
                    } else null

                    clearWebVisualBadges(webView)

                    val currentDomHash = domSnapshot.take(600).hashCode()
                    val screenChangedStatus = if (step == 1) {
                        "Initial step."
                    } else if (currentDomHash != previousDomHash) {
                        "SCREEN_CHANGED: YES (The previous action updated the webpage)."
                    } else {
                        "SCREEN_CHANGED: NO (The webpage looks identical to the previous step)."
                    }
                    previousDomHash = currentDomHash

                    val domainKey = if (currentUrl.contains("labs.google", true)) "GOOGLE_FLOW" else "WEB"
                    val learnedLessons = VideoQueueManager.getLearnedLessonsSummary(this@AutoTouchService, domainKey)

                    overlayHud?.updateStatus("Web • Step $step", "CoT + Multi-Frame thinking...", "THINKING")
                    val decision = GeminiBrain.decideNextWebAction(
                        apiKey = apiKey,
                        model = model,
                        goal = userGoal,
                        pageUrl = currentUrl,
                        pageTextAndElements = domSnapshot,
                        history = history,
                        memoryVault = memoryVault,
                        learnedLessons = learnedLessons,
                        screenChangedStatus = screenChangedStatus,
                        screenshotBase64 = currentWebScreenshotB64,
                        previousScreenshotBase64 = previousWebScreenshotB64
                    )

                    previousWebScreenshotB64 = currentWebScreenshotB64

                    if (decision.learnedRule.isNotBlank()) {
                        VideoQueueManager.recordLearnedLesson(this@AutoTouchService, decision.learnedRule, domainKey)
                        VideoQueueManager.appendLog(this@AutoTouchService, "🧠 New Brain Rule Learned: ${decision.learnedRule}")
                    }

                    val mood = if (decision.action == "WAIT") "WAITING" else "WORKING"
                    overlayHud?.updateStatus("Web • ${decision.action}", decision.reason, mood)
                    VideoQueueManager.appendLog(this@AutoTouchService, "🌐 Web Step $step [${decision.action}]: ${decision.reason}")

                    when (decision.action) {
                        "CLICK_EL" -> {
                            val clickRes = performWebJsClick(webView, decision.elementIndex)
                            history.add("Step $step: CLICK_EL(${decision.elementIndex}) -> $clickRes - ${decision.reason}")
                            if (clickRes.contains("arrow_forward", true) || clickRes.contains("Create", true)) {
                                flowPromptSubmitted = true
                                submittedAtTimeMs = System.currentTimeMillis()
                                VideoQueueManager.appendLog(this@AutoTouchService, "🔒 1-Video Credit Lock Activated!")
                            }
                        }
                        "TYPE_EL" -> {
                            val promptToUse = exactFlowPrompt.ifBlank { decision.textToType }
                            ensureGoogleFlowVideo8sMode(webView)
                            delay(800)
                            val typeRes = performWebJsTypeAndSubmit(webView, decision.elementIndex, promptToUse)
                            VideoQueueManager.appendLog(this@AutoTouchService, "✍️ Web Type Result: $typeRes")
                            history.add("Step $step: TYPE_EL(${decision.elementIndex}) -> $typeRes")
                            if (typeRes.startsWith("SUBMITTED_OK") || typeRes.contains("TYPED_LEN:")) {
                                flowPromptSubmitted = true
                                submittedAtTimeMs = System.currentTimeMillis()
                                VideoQueueManager.appendLog(this@AutoTouchService, "🔒 Prompt Submitted Once! Waiting for 0% -> 100% render...")
                                delay(4500)
                            }
                        }
                        "SCROLL" -> {
                            webView?.evaluateJavascript("window.scrollBy(0, 550);", null)
                            history.add("Step $step: SCROLL")
                        }
                        "LOAD_URL" -> {
                            if (decision.url.startsWith("http")) {
                                webView?.loadUrl(decision.url)
                                delay(4000)
                            }
                            history.add("Step $step: LOAD_URL ${decision.url}")
                        }
                        "DOWNLOAD_VIDEO", "DOWNLOAD_URL" -> {
                            val dlRes = triggerVideoDownloadInWebView(webView, decision.url)
                            VideoQueueManager.appendLog(this@AutoTouchService, "📥 Download Trigger: $dlRes")
                            delay(4500)
                            history.add("Step $step: DOWNLOAD_VIDEO ($dlRes)")
                        }
                        "WAIT" -> {
                            delay(5000)
                            history.add("Step $step: WAIT - ${decision.reason}")
                        }
                        "CHAIN_YOUTUBE" -> {
                            triggerVideoDownloadInWebView(webView, "")
                            delay(4000)
                            isJobRunning = false
                            startYouTubeAutoUpload(apiKey, model, folderPath, userGoal)
                            return@launch
                        }
                        "DONE" -> {
                            isJobRunning = false
                            overlayHud?.updateStatus("✅ Web Task Done", decision.reason, "DONE")
                            VideoQueueManager.appendLog(this@AutoTouchService, "✅ Web Task Completed: ${decision.reason}")
                            return@launch
                        }
                    }

                    delay(stepDelay)
                }
            } catch (e: CancellationException) {
                // Clean user stop
            } catch (e: Exception) {
                isJobRunning = false
                overlayHud?.updateStatus("Web Error", e.message ?: "Failed", "ERROR")
                VideoQueueManager.appendLog(this@AutoTouchService, "❌ Web Agent Error: ${e.message}")
            } finally {
                clearWebVisualBadges(webView)
                releaseWakeLock()
                isJobRunning = false
            }
        }
    }

    /**
     * Deeply inspects the entire webpage (including Shadow DOM and plain <div>/<span> tags)
     * to accurately detect if Google Flow is still rendering ("0%", "2%", "25%", "100%")
     * vs when the finished video thumbnail / player is actually ready!
     */
    private suspend fun checkGoogleFlowRenderState(webView: WebView?): String = suspendCancellableCoroutine { cont ->
        if (webView == null) {
            cont.resume("NO_WEBVIEW")
            return@suspendCancellableCoroutine
        }
        val js = """
            (function() {
                function getAllTextDeep(root) {
                    var text = '';
                    var walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT, null, false);
                    var node = walker.currentNode;
                    while (node) {
                        var rect = node.getBoundingClientRect ? node.getBoundingClientRect() : null;
                        if (rect && rect.top > 90 && rect.top < 750 && rect.width > 10 && rect.height > 10) {
                            var ownText = '';
                            for (var i = 0; i < node.childNodes.length; i++) {
                                if (node.childNodes[i].nodeType === 3) {
                                    ownText += node.childNodes[i].nodeValue + ' ';
                                }
                            }
                            if (ownText.trim().length > 0) {
                                text += ' ' + ownText.trim();
                            }
                        }
                        if (node.shadowRoot) {
                            text += ' ' + getAllTextDeep(node.shadowRoot);
                        }
                        node = walker.nextNode();
                    }
                    return text;
                }

                var fullText = getAllTextDeep(document) + ' ' + (document.body ? document.body.innerText : '');
                
                // 1. Check if any percentage (0% to 100%) is still displayed on a rendering card!
                var pctMatch = fullText.match(/\b(\d{1,3})\s*%/);
                if (pctMatch) {
                    return 'RENDERING:' + pctMatch[1] + '%';
                }

                // 2. Check if a video player (<video>) is already open and loaded
                var vids = document.querySelectorAll('video');
                for (var v = 0; v < vids.length; v++) {
                    if ((vids[v].currentSrc || vids[v].src || '').length > 4) {
                        return 'READY_VIDEO_PLAYER';
                    }
                }

                // 3. Check if a completed thumbnail card (with an <img> or play icon, and no '%') is visible in the middle area
                var imgs = document.querySelectorAll('img, video, canvas');
                for (var m = 0; m < imgs.length; m++) {
                    var r = imgs[m].getBoundingClientRect();
                    if (r.top > 110 && r.top < 650 && r.width > 70 && r.height > 90) {
                        return 'READY_CARD_THUMBNAIL';
                    }
                }

                // 4. If neither % nor thumbnail image is visible yet, the card placeholder is still initializing/blurring
                if (fullText.indexOf('Start creating or drop media') === -1) {
                    return 'RENDERING:Starting...';
                }

                return 'IDLE_EMPTY';
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { res ->
            if (cont.isActive) cont.resume(res?.trim('"') ?: "RENDERING:...")
        }
    }

    private suspend fun ensureGoogleFlowVideo8sMode(webView: WebView?): String = suspendCancellableCoroutine { cont ->
        if (webView == null) {
            cont.resume("NO_WEBVIEW")
            return@suspendCancellableCoroutine
        }
        val js = """
            (function() {
                var allBtns = document.querySelectorAll('button, [role="button"], div');
                var modePill = null;
                for (var i = 0; i < allBtns.length; i++) {
                    var t = (allBtns[i].innerText || '').trim();
                    if ((t.indexOf('Nano Banana') !== -1 || t.indexOf('Image') !== -1 || t.indexOf('x2') !== -1 || t.indexOf('x4') !== -1) && allBtns[i].getBoundingClientRect().top > 250) {
                        modePill = allBtns[i];
                        break;
                    }
                }
                if (modePill) {
                    modePill.click();
                    setTimeout(function() {
                        var opts = document.querySelectorAll('button, [role="tab"], [role="button"], span, div');
                        for (var j = 0; j < opts.length; j++) {
                            var ot = (opts[j].innerText || '').trim();
                            if (ot === 'Video' || ot === '9:16' || ot === '8s' || ot === 'x1') {
                                opts[j].click();
                            }
                        }
                    }, 350);
                    return 'SWITCHED_TO_VIDEO_8S_X1';
                }
                return 'ALREADY_VIDEO_MODE';
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { res ->
            if (cont.isActive) cont.resume(res?.trim('"') ?: "OK")
        }
    }

    /**
     * Smart 3-Stage Google Flow Video Downloader:
     * Stage A: If a <video> element is open on the page, directly fetch its blob/http MP4!
     * Stage B: If a Download button or 3-dot menu item is visible in the player, click it!
     * Stage C: If we are on the project grid (`01:48` in video), click the completed video thumbnail/play button to open the player!
     */
    private suspend fun triggerVideoDownloadInWebView(webView: WebView?, directUrl: String): String = suspendCancellableCoroutine { cont ->
        if (webView == null) {
            cont.resume("NO_WEBVIEW")
            return@suspendCancellableCoroutine
        }
        if (directUrl.startsWith("http")) {
            webView.loadUrl(directUrl)
            cont.resume("DIRECT_URL_LOADED")
            return@suspendCancellableCoroutine
        }
        val js = """
            (function() {
                // 1. Direct <video> stream extraction (Fastest & most reliable!)
                var vids = document.querySelectorAll('video');
                for (var i = 0; i < vids.length; i++) {
                    var src = vids[i].currentSrc || vids[i].src;
                    if (!src) {
                        var sourceEl = vids[i].querySelector('source');
                        if (sourceEl) src = sourceEl.src;
                    }
                    if (src && src.startsWith('blob:')) {
                        fetch(src).then(r => r.blob()).then(b => {
                            var reader = new FileReader();
                            reader.onloadend = function() {
                                AuraBlobBridge.saveBase64Video(reader.result, 'flow_8s_' + Date.now() + '.mp4');
                            };
                            reader.readAsDataURL(b);
                        });
                        return 'BLOB_FETCHED';
                    } else if (src && src.startsWith('http')) {
                        fetch(src, { credentials: 'include' }).then(r => r.blob()).then(b => {
                            var reader = new FileReader();
                            reader.onloadend = function() {
                                AuraBlobBridge.saveBase64Video(reader.result, 'flow_8s_' + Date.now() + '.mp4');
                            };
                            reader.readAsDataURL(b);
                        }).catch(function() {
                            var a = document.createElement('a');
                            a.href = src;
                            a.download = 'flow_8s_' + Date.now() + '.mp4';
                            document.body.appendChild(a);
                            a.click();
                        });
                        return 'HTTP_VIDEO_FETCHED';
                    }
                }

                // 2. Check if a Download button or menu item is visible
                var allBtns = document.querySelectorAll('a[download], button, [role="menuitem"], [role="button"]');
                for (var b = 0; b < allBtns.length; b++) {
                    var label = (allBtns[b].innerText || allBtns[b].getAttribute('aria-label') || allBtns[b].getAttribute('title') || '').toLowerCase();
                    if (label.indexOf('download') !== -1 || label === 'file_download') {
                        allBtns[b].click();
                        return 'CLICKED_DOWNLOAD_BTN';
                    }
                }

                // 3. We are on the project grid (like 01:48 in the video)!
                // Click the finished video card / thumbnail / play icon in the middle of the screen to open the video player!
                var clickTargets = document.querySelectorAll('a[href*="/edit/"], img, button, [role="button"], div');
                for (var c = 0; c < clickTargets.length; c++) {
                    var el = clickTargets[c];
                    var rect = el.getBoundingClientRect();
                    var txt = (el.innerText || el.getAttribute('aria-label') || '').trim();
                    if (rect.top > 115 && rect.top < 620 && rect.left >= 10 && rect.width > 65 && rect.height > 80) {
                        if (txt.indexOf('Failed') === -1 && txt.indexOf('%') === -1) {
                            var opts = { bubbles: true, cancelable: true, view: window };
                            el.dispatchEvent(new PointerEvent('pointerdown', opts));
                            el.dispatchEvent(new MouseEvent('mousedown', opts));
                            el.dispatchEvent(new PointerEvent('pointerup', opts));
                            el.dispatchEvent(new MouseEvent('mouseup', opts));
                            el.click();
                            return 'OPENED_FINISHED_VIDEO_CARD';
                        }
                    }
                }
                return 'WAITING_FOR_CARD';
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { res ->
            if (cont.isActive) cont.resume(res?.trim('"') ?: "DONE")
        }
    }

    private suspend fun extractWebDomAndDrawBadges(webView: WebView?): String = suspendCancellableCoroutine { cont ->
        if (webView == null) {
            cont.resume("Empty WebView")
            return@suspendCancellableCoroutine
        }
        val js = """
            (function() {
                var oldBadges = document.querySelectorAll('.aura-vision-badge');
                for (var b = 0; b < oldBadges.length; b++) oldBadges[b].remove();

                function queryAllDeep(root) {
                    var res = [];
                    var nodes = root.querySelectorAll('a, button, input, textarea, select, [role="button"], [role="textbox"], [contenteditable="true"], video');
                    for (var i = 0; i < nodes.length; i++) res.push(nodes[i]);
                    var all = root.querySelectorAll('*');
                    for (var j = 0; j < all.length; j++) {
                        if (all[j].shadowRoot) {
                            res = res.concat(queryAllDeep(all[j].shadowRoot));
                        }
                    }
                    return res;
                }
                var items = queryAllDeep(document);
                var out = [];
                var hasVideo = document.querySelectorAll('video').length > 0 ? 'YES' : 'NO';
                for (var i = 0; i < items.length && out.length < 75; i++) {
                    var el = items[i];
                    var rect = el.getBoundingClientRect();
                    if (rect.width > 5 && rect.height > 5 && rect.bottom > 0 && rect.top < window.innerHeight) {
                        el.setAttribute('data-aura-idx', i);
                        var tag = el.tagName.toLowerCase();
                        var placeholder = el.placeholder || el.getAttribute('placeholder') || el.getAttribute('data-placeholder') || '';
                        var aria = el.getAttribute('aria-label') || '';
                        var val = (el.value !== undefined ? el.value : (el.isContentEditable ? el.innerText : '')) || '';
                        var txt = (el.innerText || placeholder || aria || el.src || '').trim().replace(/\s+/g, ' ').substring(0, 65);
                        var valInfo = (tag === 'textarea' || tag === 'input' || el.isContentEditable || el.getAttribute('role') === 'textbox')
                            ? ' | CURRENT_VALUE="' + val.trim().substring(0, 45) + '" | PLACEHOLDER="' + placeholder + '"'
                            : '';
                        out.push('[' + i + '] <' + tag + '> ' + txt + valInfo);

                        try {
                            var badge = document.createElement('div');
                            badge.className = 'aura-vision-badge';
                            badge.innerText = '[' + i + ']';
                            badge.style.cssText = 'position:fixed;left:' + Math.max(2, Math.min(window.innerWidth - 34, rect.left)) + 'px;top:' + Math.max(2, Math.min(window.innerHeight - 20, rect.top)) + 'px;background:#FACC15;color:#000000;border:1.5px solid #DC2626;border-radius:4px;padding:0px 3px;font-size:10px;font-weight:bold;z-index:2147483647;pointer-events:none;line-height:12px;';
                            document.body.appendChild(badge);
                        } catch (e) {}
                    }
                }
                var bodyText = (document.body ? document.body.innerText : '').substring(0, 1800).replace(/\s+/g, ' ');
                return 'HAS_VIDEO_ELEMENT: ' + hasVideo + '\nPAGE_TEXT: ' + bodyText + '\nELEMENTS:\n' + out.join('\n');
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { result ->
            if (cont.isActive) {
                cont.resume(result?.trim('"')?.replace("\\n", "\n") ?: "No DOM")
            }
        }
    }

    private fun clearWebVisualBadges(webView: WebView?) {
        val js = """
            (function() {
                var oldBadges = document.querySelectorAll('.aura-vision-badge');
                for (var b = 0; b < oldBadges.length; b++) oldBadges[b].remove();
            })();
        """.trimIndent()
        webView?.evaluateJavascript(js, null)
    }

    private suspend fun performWebJsClick(webView: WebView?, index: Int): String = suspendCancellableCoroutine { cont ->
        if (webView == null) {
            cont.resume("No WebView")
            return@suspendCancellableCoroutine
        }
        val js = """
            (function() {
                var el = document.querySelector('[data-aura-idx="$index"]');
                if (!el) return 'NOT_FOUND';
                var clickable = el.closest('button, [role="button"], a') || el;
                clickable.scrollIntoView({block:'center'});
                var opts = { bubbles: true, cancelable: true, view: window };
                clickable.dispatchEvent(new PointerEvent('pointerdown', opts));
                clickable.dispatchEvent(new MouseEvent('mousedown', opts));
                clickable.dispatchEvent(new PointerEvent('pointerup', opts));
                clickable.dispatchEvent(new MouseEvent('mouseup', opts));
                clickable.click();
                return 'CLICKED:' + (clickable.innerText || clickable.getAttribute('aria-label') || clickable.tagName).substring(0, 25);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { res ->
            if (cont.isActive) cont.resume(res ?: "DONE")
        }
    }

    private suspend fun performWebJsTypeAndSubmit(
        webView: WebView?,
        index: Int,
        text: String
    ): String = suspendCancellableCoroutine { cont ->
        if (webView == null) {
            cont.resume("No WebView")
            return@suspendCancellableCoroutine
        }
        val safeText = JSONObject.quote(text)
        val js = """
            (function() {
                var target = document.querySelector('[data-aura-idx="$index"]');
                
                var allCandidates = document.querySelectorAll('textarea, [contenteditable="true"], [role="textbox"], input[type="text"], input:not([type])');
                var flowPromptBox = null;
                for (var i = 0; i < allCandidates.length; i++) {
                    var c = allCandidates[i];
                    var ph = (c.placeholder || c.getAttribute('placeholder') || c.getAttribute('aria-label') || c.innerText || '').toLowerCase();
                    if (ph.indexOf('what do you want to create') !== -1 || ph.indexOf('create') !== -1 || ph.indexOf('prompt') !== -1 || c.tagName.toLowerCase() === 'textarea' || c.isContentEditable) {
                        var rect = c.getBoundingClientRect();
                        if (rect.top > 180 && rect.width > 100) {
                            flowPromptBox = c;
                            break;
                        }
                    }
                }
                if (flowPromptBox) {
                    target = flowPromptBox;
                } else if (!target) {
                    target = allCandidates[allCandidates.length - 1];
                }
                if (!target) return 'NO_INPUT_FOUND';

                target.scrollIntoView({block: 'center'});
                target.click();
                target.focus();

                try {
                    if (target.select) target.select();
                    else document.execCommand('selectAll', false, null);
                    document.execCommand('insertText', false, $safeText);
                } catch (e) {}

                var currentVal = (target.value !== undefined ? target.value : target.innerText) || '';
                if (currentVal.trim().length < 5) {
                    try {
                        var proto = target.tagName.toLowerCase() === 'textarea'
                            ? window.HTMLTextAreaElement.prototype
                            : window.HTMLInputElement.prototype;
                        var nativeSetter = Object.getOwnPropertyDescriptor(proto, 'value') ? Object.getOwnPropertyDescriptor(proto, 'value').set : null;
                        if (nativeSetter) {
                            nativeSetter.call(target, $safeText);
                        } else if (target.isContentEditable) {
                            target.innerText = $safeText;
                        } else {
                            target.value = $safeText;
                        }
                        target.dispatchEvent(new InputEvent('input', { bubbles: true, cancelable: true, inputType: 'insertText', data: $safeText }));
                        target.dispatchEvent(new Event('change', { bubbles: true }));
                    } catch (e2) {}
                }

                setTimeout(function() {
                    var btns = document.querySelectorAll('button, [role="button"]');
                    for (var b = 0; b < btns.length; b++) {
                        var bTxt = (btns[b].innerText || btns[b].getAttribute('aria-label') || '').trim();
                        if (bTxt.indexOf('arrow_forward') !== -1 || bTxt.toLowerCase() === 'create' || bTxt.toLowerCase() === 'generate') {
                            if (!btns[b].disabled) {
                                var opts = { bubbles: true, cancelable: true, view: window };
                                btns[b].dispatchEvent(new PointerEvent('pointerdown', opts));
                                btns[b].dispatchEvent(new MouseEvent('mousedown', opts));
                                btns[b].dispatchEvent(new PointerEvent('pointerup', opts));
                                btns[b].dispatchEvent(new MouseEvent('mouseup', opts));
                                btns[b].click();
                                break;
                            }
                        }
                    }
                }, 500);

                var finalVal = (target.value !== undefined ? target.value : target.innerText) || '';
                return 'SUBMITTED_OK_TYPED_LEN:' + finalVal.length;
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { res ->
            if (cont.isActive) cont.resume(res?.trim('"') ?: "TYPED")
        }
    }

    // ========================================================================
    // ENGINE 3: TESTED & WORKING YOUTUBE SHORTS AUTO-UPLOADER
    // ========================================================================
    fun startYouTubeAutoUpload(
        apiKey: String,
        model: String,
        folderPath: String,
        userPrompt: String,
        createAiVideoIfEmpty: Boolean = false
    ) {
        if (isJobRunning) {
            VideoQueueManager.appendLog(this, "⚠️ A task is already running!")
            return
        }

        activeJob = serviceScope.launch {
            isJobRunning = true
            acquireWakeLock()
            postKeepAliveNotification("Uploading Video to YouTube...")
            overlayHud?.show()
            overlayHud?.setCursorOverlayVisible(true)
            try {
                overlayHud?.updateStatus("Step 1/5 • Folder", "Scanning folder for video...", "WORKING")
                val nextVideo = VideoQueueManager.getNextPendingVideo(this@AutoTouchService, folderPath)
                if (nextVideo == null) {
                    if (createAiVideoIfEmpty) {
                        isJobRunning = false
                        startGoogleFlowToYouTubePipeline(
                            apiKey = apiKey,
                            model = model,
                            flowUrl = VideoQueueManager.getPinnedFlowUrl(this@AutoTouchService),
                            topicIdea = userPrompt,
                            dailyVideoCount = 1,
                            folderPath = folderPath
                        )
                        return@launch
                    }
                    isJobRunning = false
                    overlayHud?.setCursorOverlayVisible(false)
                    overlayHud?.updateStatus("Queue Empty", "Tap 'Reset History' or make video!", "ERROR")
                    VideoQueueManager.appendLog(this@AutoTouchService, "⚠️ No pending video in folder!")
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
                        isJobRunning = false
                        overlayHud?.setCursorOverlayVisible(false)
                        overlayHud?.updateStatus("🎉 Upload Success!", "'${nextVideo.name.take(18)}' Published!", "DONE")
                        postKeepAliveNotification("Last upload: ${nextVideo.name}")
                        break
                    }
                    delay(2000)
                }
            } catch (e: CancellationException) {
                // Normal user stop
            } catch (e: Exception) {
                isJobRunning = false
                overlayHud?.setCursorOverlayVisible(false)
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
    // ENGINE 4: UNIVERSAL PHONE AGENT (Multi-Frame Vision + SQLite Brain!)
    // ========================================================================
    fun startUniversalAiTask(
        apiKey: String,
        model: String,
        userGoal: String,
        initialSettingAction: String? = null
    ) {
        if (isJobRunning) {
            VideoQueueManager.appendLog(this, "⚠️ Agent is already busy!")
            return
        }

        activeJob = serviceScope.launch {
            isJobRunning = true
            acquireWakeLock()
            postKeepAliveNotification("Phone Agent Active: ${userGoal.take(30)}")
            overlayHud?.show()
            overlayHud?.setCursorOverlayVisible(true)

            val (realW, realH) = getRealScreenDimensions()
            val memoryVault = VideoQueueManager.getMemoryVault(this@AutoTouchService)
            val unstoppable = VideoQueueManager.is24HourUnstoppableEnabled(this@AutoTouchService)
            val stepDelay = VideoQueueManager.getStepDelayMs(this@AutoTouchService)

            val goalLower = userGoal.lowercase()
            val isContinuousMonitor = goalLower.contains("monitor") ||
                    goalLower.contains("wait") ||
                    goalLower.contains("karte raho") ||
                    goalLower.contains("karte rahana") ||
                    goalLower.contains("lagatar") ||
                    goalLower.contains("baat chet") ||
                    goalLower.contains("reply")

            val maxSteps = if (isContinuousMonitor || unstoppable) 5000 else 40

            try {
                if (!initialSettingAction.isNullOrBlank()) {
                    openSystemSettingOrApp(initialSettingAction)
                    delay(2500)
                }

                overlayHud?.updateStatus("Universal Agent", "Goal: ${userGoal.take(26)}...", "THINKING")
                VideoQueueManager.appendLog(this@AutoTouchService, "🤖 Starting Universal Phone Agent: $userGoal")
                val history = mutableListOf<String>()
                var lastTapX = -1f
                var lastTapY = -1f
                var repeatTapCount = 0
                var previousPhoneScreenshotB64: String? = null
                var previousScreenSignature = 0

                for (step in 1..maxSteps) {
                    if (!isActive) break
                    val nodes = captureScreenNodes()

                    val unsentInputNode = nodes.firstOrNull {
                        it.isEditable && it.text.isNotBlank() &&
                        !it.text.equals("Message", true) &&
                        !it.text.equals("Type a message", true) &&
                        !it.text.equals("Search...", true)
                    }
                    val sendBtnNode = nodes.firstOrNull {
                        it.contentDesc.equals("Send", true) ||
                        it.contentDesc.equals("भेजें", true) ||
                        it.viewId.equals("send", true) ||
                        it.viewId.contains("send_btn", true)
                    }
                    if (unsentInputNode != null && sendBtnNode != null) {
                        overlayHud?.setCursorOverlayVisible(true)
                        overlayHud?.updateStatus("Step $step • Sending", "Tapping Send button...", "WORKING")
                        VideoQueueManager.appendLog(this@AutoTouchService, "📨 Auto-Sending typed message via Send button!")
                        animateAndPhysicalTapNode(sendBtnNode, "Tap: Send")
                        history.add("Step $step: Auto-Tapped Send button for '${unsentInputNode.text.take(20)}'")
                        delay(2500)
                        continue
                    }

                    val screenDump = nodes.joinToString("\n") {
                        "[${it.index}] text='${it.text}' desc='${it.contentDesc}' hint='${it.hintText}' id='${it.viewId}' edit=${it.isEditable} click=${it.isClickable} xy=(${it.centerX.toInt()},${it.centerY.toInt()})"
                    }

                    val currentScreenshotB64 = captureScreenshotBase64()

                    val currentSig = screenDump.take(800).hashCode()
                    val screenChangedStatus = if (step == 1) {
                        "Initial step."
                    } else if (currentSig != previousScreenSignature) {
                        "SCREEN_CHANGED: YES (The UI changed after the last action)."
                    } else {
                        "SCREEN_CHANGED: NO (Warning: The screen UI did NOT change after the last action! Do not repeat the same failing coordinate)."
                    }
                    previousScreenSignature = currentSig

                    val domainKey = if (goalLower.contains("whatsapp")) "WHATSAPP" else "ANDROID_UI"
                    val learnedLessons = VideoQueueManager.getLearnedLessonsSummary(this@AutoTouchService, domainKey)

                    overlayHud?.updateStatus("Step $step • Thinking", "Multi-frame & memory check...", "THINKING")
                    var decision = GeminiBrain.decideNextUiAction(
                        apiKey = apiKey,
                        model = model,
                        goal = userGoal,
                        screenSummary = screenDump.take(6500),
                        history = history,
                        memoryVault = memoryVault,
                        learnedLessons = learnedLessons,
                        screenChangedStatus = screenChangedStatus,
                        screenshotBase64 = currentScreenshotB64,
                        previousScreenshotBase64 = previousPhoneScreenshotB64,
                        screenWidth = realW.toInt(),
                        screenHeight = realH.toInt(),
                        isContinuousMonitorMode = isContinuousMonitor
                    )

                    previousPhoneScreenshotB64 = currentScreenshotB64

                    if (decision.learnedRule.isNotBlank()) {
                        VideoQueueManager.recordLearnedLesson(this@AutoTouchService, decision.learnedRule, domainKey)
                        VideoQueueManager.appendLog(this@AutoTouchService, "🧠 New Brain Rule Learned: ${decision.learnedRule}")
                    }

                    if (isContinuousMonitor && decision.action.equals("DONE", true)) {
                        decision = decision.copy(
                            action = "WAIT",
                            reason = "Monitoring chat for new incoming reply..."
                        )
                    }

                    val stepMood = when (decision.action) {
                        "DONE" -> "DONE"
                        "WAIT" -> "WAITING"
                        else -> "WORKING"
                    }
                    overlayHud?.updateStatus("Step $step • ${decision.action}", decision.reason, stepMood)
                    VideoQueueManager.appendLog(this@AutoTouchService, "🤖 Step $step [${decision.action}]: ${decision.reason}")

                    if (decision.action.equals("DONE", true)) {
                        isJobRunning = false
                        overlayHud?.setCursorOverlayVisible(false)
                        overlayHud?.updateStatus("🎉 Task Complete!", decision.reason, "DONE")
                        VideoQueueManager.appendLog(this@AutoTouchService, "✅ Task Completed: ${decision.reason}")
                        break
                    }

                    if (decision.action.equals("WAIT", true)) {
                        overlayHud?.setCursorOverlayVisible(false)
                        delay(4500)
                        history.add("Step $step: WAIT - ${decision.reason}")
                        continue
                    }

                    overlayHud?.setCursorOverlayVisible(true)
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
                            if (repeatTapCount >= 2) {
                                VideoQueueManager.recordLearnedLesson(
                                    this@AutoTouchService,
                                    "Tapping repeatedly at (${tapX.toInt()},${tapY.toInt()}) did not change the screen; use CLICK_NODE instead.",
                                    domainKey
                                )
                            }
                        } else {
                            repeatTapCount = 0
                        }
                        lastTapX = tapX
                        lastTapY = tapY
                    }

                    history.add("Step $step: ${decision.action} ($executedSummary) - ${decision.reason}")
                    delay(stepDelay)
                }
            } catch (e: CancellationException) {
                // Clean user stop
            } catch (e: Exception) {
                isJobRunning = false
                overlayHud?.setCursorOverlayVisible(false)
                overlayHud?.updateStatus("Agent Error", e.message ?: "Failed", "ERROR")
                VideoQueueManager.appendLog(this@AutoTouchService, "❌ Agent Error: ${e.message}")
            } finally {
                overlayHud?.setCursorOverlayVisible(false)
                releaseWakeLock()
                isJobRunning = false
            }
        }
    }

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
                if (reasonLower.contains("send") || reasonLower.contains("reply")) {
                    val sendNode = nodes.firstOrNull {
                        it.contentDesc.equals("Send", true) ||
                        it.contentDesc.equals("भेजें", true) ||
                        it.viewId.equals("send", true)
                    }
                    if (sendNode != null) {
                        onTapRecorded(sendNode.centerX, sendNode.centerY)
                        animateAndPhysicalTapNode(sendNode, "Tap: Send")
                        return "Tapped Send Node (${sendNode.centerX.toInt()},${sendNode.centerY.toInt()})"
                    }
                }

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
                        it.centerX > realW * 0.78f && it.centerY in (realH * 0.05f)..(realH * 0.13f)
                    }.maxByOrNull { it.centerX }

                    if (threeDotNode != null) {
                        onTapRecorded(threeDotNode.centerX, threeDotNode.centerY)
                        animateAndPhysicalTapNode(threeDotNode, "Tap: 3-Dot Menu")
                        return "Tapped 3-Dot Node (${threeDotNode.centerX.toInt()},${threeDotNode.centerY.toInt()})"
                    }
                }

                if (decision.action.equals("CLICK_NODE", true)) {
                    val targetNode = nodes.firstOrNull { it.index == decision.nodeIndex }
                    if (targetNode != null) {
                        val label = targetNode.text.ifEmpty { targetNode.contentDesc }.take(15).ifEmpty { "Button" }
                        onTapRecorded(targetNode.centerX, targetNode.centerY)
                        animateAndPhysicalTapNode(targetNode, "Tap: $label")
                        return "Tapped Node #${targetNode.index} ($label)"
                    }
                }

                var rawX = decision.x
                var rawY = decision.y
                val statusBarLimitY = realH * 0.052f

                if (rawY in 10f..statusBarLimitY && rawX <= 1000f) {
                    rawX = (rawX / 1000f) * realW
                    rawY = ((rawY / 1000f) * realH).coerceAtLeast(statusBarLimitY + 35f)
                } else if (rawY < statusBarLimitY) {
                    rawY = (statusBarLimitY + 55f)
                }

                val nearestNode = nodes.filter {
                    it.centerY >= statusBarLimitY && (it.isClickable || it.text.isNotEmpty() || it.contentDesc.isNotEmpty())
                }.minByOrNull {
                    hypot(it.centerX - rawX, it.centerY - rawY)
                }

                if (nearestNode != null && hypot(nearestNode.centerX - rawX, nearestNode.centerY - rawY) <= 145f) {
                    val label = nearestNode.text.ifEmpty { nearestNode.contentDesc }.take(14).ifEmpty { "Tap" }
                    onTapRecorded(nearestNode.centerX, nearestNode.centerY)
                    animateAndPhysicalTapNode(nearestNode, "Tap: $label")
                    return "Magnet-Tapped '$label'"
                }

                if (repeatCount >= 2 && nearestNode != null) {
                    onTapRecorded(nearestNode.centerX, nearestNode.centerY)
                    animateAndPhysicalTapNode(nearestNode, "Retry Tap")
                    return "Anti-Stuck Node Tap"
                }

                onTapRecorded(rawX, rawY)
                animateAndTapXY(rawX, rawY, "Tap: (${rawX.toInt()},${rawY.toInt()})")
                return "Tapped XY (${rawX.toInt()},${rawY.toInt()})"
            }
            "TYPE" -> {
                val target = nodes.firstOrNull { it.index == decision.nodeIndex }
                    ?: nodes.firstOrNull { it.isEditable || it.supportsSetText }
                val tx = target?.centerX ?: (realW * 0.45f)
                val ty = target?.centerY ?: (realH * 0.94f)
                animateAndTapXY(tx, ty, "Focus Input")
                delay(1200)
                insertCaptionSingleClean(decision.textToType, tx, ty)
                delay(900)

                val freshNodes = captureScreenNodes()
                val sendBtn = freshNodes.firstOrNull {
                    it.contentDesc.equals("Send", true) ||
                    it.contentDesc.equals("भेजें", true) ||
                    it.viewId.equals("send", true)
                }
                if (sendBtn != null) {
                    animateAndPhysicalTapNode(sendBtn, "Tap: Send")
                    return "Typed & Sent '${decision.textToType.take(18)}'"
                }

                return "Typed '${decision.textToType.take(18)}'"
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
            "BATTERY" -> Intent(Intent.ACTION_MAIN).setClassName("com.android.settings", "com.android.settings.Settings\$PowerUsageSummaryActivity")
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
            val c = "${it.text} ${it.contentDesc} ${it.hintText}"
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
