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
        VideoQueueManager.appendLog(this, "⚠️ Handled Error: ${throwable.message}")
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
        postKeepAliveNotification("AuraDroid Companion Active & Ready")
        overlayHud = AiOverlayHud(this)
        overlayHud?.show()
        overlayHud?.setCursorOverlayVisible(false)
        overlayHud?.updateStatus("Ready", "Tap 🌐 for Browser", "IDLE")
        VideoQueueManager.appendLog(this, "✅ Universal Companion Connected (24/7 Protected)!")
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
                .setContentTitle("AuraDroid OS • 24/7 Active")
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
        activeJob?.cancel()
        isJobRunning = false
        releaseWakeLock()
        overlayHud?.setCursorOverlayVisible(false)
        overlayHud?.updateStatus("Stopped", "Task stopped", "DONE")
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
                val countToMake = dailyVideoCount.coerceIn(1, 3)
                overlayHud?.updateStatus(
                    "Flow 8s (1/$countToMake)",
                    "Creating fresh 8s story prompt...",
                    "THINKING"
                )
                val freshPrompt = GeminiBrain.generateDailyFlowPrompt(apiKey, model, topicIdea)
                VideoQueueManager.appendLog(this@AutoTouchService, "🎬 Flow 8s Prompt: $freshPrompt")

                val goal = """
                    We are on Google Flow ($flowUrl).
                    1. Type this exact 8-second video prompt into the prompt input box: "$freshPrompt"
                    2. Click the Generate / Create button ONCE (do not waste credits).
                    3. Wait patiently while the 8-second video generates.
                    4. Once the video is ready, download it or trigger DOWNLOAD_VIDEO, then CHAIN_YOUTUBE.
                """.trimIndent()

                isJobRunning = false
                startBackgroundWebTask(
                    apiKey = apiKey,
                    model = model,
                    initialUrl = flowUrl,
                    userGoal = goal,
                    folderPath = folderPath,
                    chainYouTubeAfterDownload = true,
                    forceChainYouTube = true
                )
            } catch (e: Exception) {
                isJobRunning = false
                overlayHud?.updateStatus("Flow Error", e.message ?: "Error", "ERROR")
                VideoQueueManager.appendLog(this@AutoTouchService, "❌ Flow Pipeline Error: ${e.message}")
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
        forceChainYouTube: Boolean = false
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
            val maxSteps = if (unstoppable) 2000 else 60

            try {
                val currentLoadedUrl = webView?.url ?: ""
                if (initialUrl.isNotBlank() && initialUrl.startsWith("http") &&
                    !currentLoadedUrl.startsWith(initialUrl.take(24), true)
                ) {
                    overlayHud?.updateStatus("Web Pod • Opening", initialUrl, "WORKING")
                    VideoQueueManager.appendLog(this@AutoTouchService, "🌐 Opening in Browser: $initialUrl")
                    webView?.loadUrl(initialUrl)
                    delay(5000)
                }

                val history = mutableListOf<String>()

                for (step in 1..maxSteps) {
                    if (!isActive) break

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
                    val domSnapshot = extractWebDomSummary(webView)

                    overlayHud?.updateStatus("Web • Step $step", "Inspecting page...", "THINKING")
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
                    overlayHud?.updateStatus("Web • ${decision.action}", decision.reason, mood)
                    VideoQueueManager.appendLog(this@AutoTouchService, "🌐 Web Step $step [${decision.action}]: ${decision.reason}")
                    history.add("Step $step: ${decision.action} - ${decision.reason}")

                    when (decision.action) {
                        "CLICK_EL" -> performWebJsClick(webView, decision.elementIndex)
                        "TYPE_EL" -> performWebJsType(webView, decision.elementIndex, decision.textToType)
                        "SCROLL" -> webView?.evaluateJavascript("window.scrollBy(0, 550);", null)
                        "LOAD_URL" -> {
                            if (decision.url.startsWith("http")) {
                                webView?.loadUrl(decision.url)
                                delay(4000)
                            }
                        }
                        "DOWNLOAD_VIDEO", "DOWNLOAD_URL" -> {
                            triggerVideoDownloadInWebView(webView, decision.url)
                            delay(4500)
                        }
                        "WAIT" -> delay(6000)
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
            } catch (e: Exception) {
                isJobRunning = false
                overlayHud?.updateStatus("Web Error", e.message ?: "Failed", "ERROR")
                VideoQueueManager.appendLog(this@AutoTouchService, "❌ Web Agent Error: ${e.message}")
            } finally {
                releaseWakeLock()
                isJobRunning = false
            }
        }
    }

    private fun triggerVideoDownloadInWebView(webView: WebView?, directUrl: String) {
        if (directUrl.startsWith("http")) {
            webView?.loadUrl(directUrl)
            return
        }
        val js = """
            (function() {
                var dlBtn = document.querySelector('a[download], button[aria-label*="Download" i], button[title*="Download" i]');
                if (dlBtn) { dlBtn.click(); }
                var vids = document.querySelectorAll('video');
                for (var i = vids.length - 1; i >= 0; i--) {
                    var src = vids[i].currentSrc || vids[i].src;
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
                        var a = document.createElement('a');
                        a.href = src;
                        a.download = 'flow_8s_' + Date.now() + '.mp4';
                        document.body.appendChild(a);
                        a.click();
                        return 'HTTP_DOWNLOADED';
                    }
                }
                return 'NO_VIDEO_FOUND';
            })();
        """.trimIndent()
        webView?.evaluateJavascript(js, null)
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
                for (var i = 0; i < items.length && out.length < 70; i++) {
                    var el = items[i];
                    var rect = el.getBoundingClientRect();
                    if (rect.width > 5 && rect.height > 5) {
                        el.setAttribute('data-aura-idx', i);
                        var tag = el.tagName.toLowerCase();
                        var txt = (el.innerText || el.value || el.placeholder || el.getAttribute('aria-label') || el.src || '').trim().substring(0, 70);
                        out.push('[' + i + '] <' + tag + '> ' + txt);
                    }
                }
                var bodyText = (document.body ? document.body.innerText : '').substring(0, 1500).replace(/\s+/g, ' ');
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

                for (step in 1..maxSteps) {
                    if (!isActive) break
                    val nodes = captureScreenNodes()
                    val screenDump = nodes.joinToString("\n") {
                        "[${it.index}] text='${it.text}' desc='${it.contentDesc}' hint='${it.hintText}' id='${it.viewId}' edit=${it.isEditable} click=${it.isClickable} xy=(${it.centerX.toInt()},${it.centerY.toInt()})"
                    }

                    val screenshotB64 = captureScreenshotBase64()

                    overlayHud?.updateStatus("Step $step • Thinking", "Reading screen & vision...", "THINKING")
                    var decision = GeminiBrain.decideNextUiAction(
                        apiKey = apiKey,
                        model = model,
                        goal = userGoal,
                        screenSummary = screenDump.take(6500),
                        history = history,
                        memoryVault = memoryVault,
                        screenshotBase64 = screenshotB64,
                        screenWidth = realW.toInt(),
                        screenHeight = realH.toInt(),
                        isContinuousMonitorMode = isContinuousMonitor
                    )

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
            "BATTERY" -> Intent("android.settings.POWER_USAGE_SUMMARY")
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
