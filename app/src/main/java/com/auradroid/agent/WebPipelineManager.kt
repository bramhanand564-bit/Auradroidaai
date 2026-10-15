package com.auradroid.agent

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityWindowInfo
import android.webkit.WebView
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

object WebPipelineManager {

    suspend fun runGoogleFlowPipeline(
        service: AutoTouchService,
        apiKey: String,
        model: String,
        flowUrl: String,
        topicIdea: String,
        dailyVideoCount: Int,
        folderPath: String
    ) {
        if (AutoTouchService.isJobRunning) {
            VideoQueueManager.appendLog(service, "⚠️ A task is already running!")
            return
        }
        AutoTouchService.isJobRunning = true
        service.overlayHud?.show()
        service.overlayHud?.setCursorOverlayVisible(false)

        try {
            service.overlayHud?.updateStatus("Flow 8s (Credit Lock: 1)", "Creating policy-safe prompt...", "THINKING")
            val freshPrompt = GeminiBrain.generateDailyFlowPrompt(apiKey, model, topicIdea)
            VideoQueueManager.appendLog(service, "🎬 Safe Flow 8s Prompt: $freshPrompt")

            val goal = """
                We are on Google Flow ($flowUrl).
                1. If on landing page, click '+ New project'.
                2. Ensure mode is 'Video • 720p • 8s • x1' (NOT Nano Banana Image mode).
                3. Type this exact 8-second video prompt ONCE into 'What do you want to create?': "$freshPrompt"
                4. Wait while the 1 video renders from 0% to 100%, then open the finished video card, play & download it (DOWNLOAD_VIDEO) and CHAIN_YOUTUBE.
            """.trimIndent()

            AutoTouchService.isJobRunning = false
            runBackgroundWebTask(
                service = service,
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
            AutoTouchService.isJobRunning = false
            service.overlayHud?.updateStatus("Flow Error", e.message ?: "Error", "ERROR")
            VideoQueueManager.appendLog(service, "❌ Flow Pipeline Error: ${e.message}")
        }
    }

    suspend fun runBackgroundWebTask(
        service: AutoTouchService,
        apiKey: String,
        model: String,
        initialUrl: String,
        userGoal: String,
        folderPath: String,
        chainYouTubeAfterDownload: Boolean = false,
        forceChainYouTube: Boolean = false,
        exactFlowPrompt: String = ""
    ) {
        if (AutoTouchService.isJobRunning) {
            VideoQueueManager.appendLog(service, "⚠️ A task is already running!")
            return
        }
        AutoTouchService.isJobRunning = true
        service.overlayHud?.show()
        service.overlayHud?.setCursorOverlayVisible(false)

        val shouldChainYt = chainYouTubeAfterDownload || forceChainYouTube
        val startTimeMs = System.currentTimeMillis()
        val webView = service.overlayHud?.getOrCreateSharedWebView()
        val memoryVault = VideoQueueManager.getMemoryVault(service)
        val stepDelay = VideoQueueManager.getStepDelayMs(service)
        val highModelName = VideoQueueManager.getHighModelName(service)
        val autoHighModelEnabled = VideoQueueManager.isAutoHighModelEnabled(service)
        val googleSearchEnabled = VideoQueueManager.isGoogleSearchGroundingEnabled(service)
        val maxSteps = if (VideoQueueManager.is24HourUnstoppableEnabled(service)) 2000 else 80

        var flowPromptSubmitted = false
        var submittedAtTimeMs = 0L
        var downloadAttempts = 0
        var idleRetryCount = 0
        var previousWebScreenshotB64: String? = null
        var previousDomHash = 0
        var previousUrl = ""
        var lastExpectedOutcome = "Initial page load"
        var consecutiveVerificationFails = 0

        try {
            val currentLoadedUrl = webView?.url ?: ""
            val alreadyOnFlow = currentLoadedUrl.contains("labs.google/fx", true) &&
                    initialUrl.contains("labs.google/fx", true)

            if (initialUrl.isNotBlank() && initialUrl.startsWith("http") && !alreadyOnFlow &&
                !currentLoadedUrl.startsWith(initialUrl.take(22), true)
            ) {
                service.overlayHud?.updateStatus("Web • Opening", initialUrl, "WORKING")
                VideoQueueManager.appendLog(service, "🌐 Opening: $initialUrl")
                webView?.loadUrl(initialUrl)
                delay(5000)
            }

            val history = mutableListOf<String>()

            for (step in 1..maxSteps) {
                val newlyDownloaded = VideoQueueManager.findNewlyDownloadedVideo(startTimeMs, folderPath)
                if (newlyDownloaded != null && (shouldChainYt || userGoal.contains("youtube", true))) {
                    VideoQueueManager.appendLog(service, "🎬 Newly Downloaded Video Ready: ${newlyDownloaded.name}! Launching YouTube...")
                    AutoTouchService.isJobRunning = false
                    runYouTubeAutoUpload(service, apiKey, model, folderPath, userGoal)
                    return
                }

                val currentUrl = webView?.url ?: initialUrl
                if (!flowPromptSubmitted && currentUrl.contains("labs.google/fx", true)) {
                    val modeFix = ensureGoogleFlowVideo8sMode(webView)
                    if (modeFix.startsWith("SWITCHED")) {
                        VideoQueueManager.appendLog(service, "⚙️ Auto-Configured Google Flow Mode: $modeFix")
                        delay(1600)
                    }
                }

                // ====================================================================
                // POST-SUBMIT VERIFIED RENDER & DOWNLOAD LOOP
                // ====================================================================
                if (flowPromptSubmitted) {
                    val elapsedSec = (System.currentTimeMillis() - submittedAtTimeMs) / 1000L
                    val renderState = checkGoogleFlowRenderState(webView)

                    // Guard against False Submit: If 15+ seconds passed and page STILL says "Start creating or drop media" (IDLE_EMPTY),
                    // the prompt did NOT submit! Unlock and retry typing into the bottom prompt box!
                    if (renderState == "IDLE_EMPTY" && elapsedSec >= 14L && idleRetryCount < 3) {
                        idleRetryCount++
                        flowPromptSubmitted = false
                        if (service.isKeyboardOpen()) {
                            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                            delay(1200)
                        }
                        VideoQueueManager.appendLog(service, "⚠️ Screen still on 'Start creating' (Idle). Unlocking to re-type prompt (#$idleRetryCount)...")
                    } else if (elapsedSec < 12L || renderState.startsWith("RENDERING:")) {
                        val pct = renderState.removePrefix("RENDERING:").ifBlank { "${elapsedSec}s" }
                        service.overlayHud?.updateStatus("Flow Rendering ($pct)", "Waiting for 8s video to finish...", "WAITING")
                        VideoQueueManager.appendLog(service, "⏳ Google Flow Rendering: $pct (Elapsed: ${elapsedSec}s)...")
                        delay(4500)
                        continue
                    } else if (renderState.startsWith("FAILED:")) {
                        service.overlayHud?.updateStatus("Flow Policy Error", "Prompt rejected by Google Flow", "ERROR")
                        VideoQueueManager.appendLog(service, "❌ Google Flow Card Failed: $renderState")
                        AutoTouchService.isJobRunning = false
                        return
                    } else {
                        // Video is 100% READY (READY_CARD_THUMBNAIL or READY_VIDEO_PLAYER)!
                        downloadAttempts++
                        service.overlayHud?.updateStatus("Flow Ready • Saving", "Opening card, playing & saving MP4 (#$downloadAttempts)...", "WORKING")
                        val dlStatus = WebActionVerifier.openPlayAndDownloadFlowVideo(service, webView)
                        VideoQueueManager.appendLog(service, "📥 Auto-Download Step #$downloadAttempts ($renderState): $dlStatus")
                        delay(4800)

                        val downloadedNow = VideoQueueManager.findNewlyDownloadedVideo(startTimeMs, folderPath)
                        if (downloadedNow != null) {
                            VideoQueueManager.recordLearnedLesson(service, "Google Flow video downloaded after $downloadAttempts steps.", "GOOGLE_FLOW")
                            VideoQueueManager.appendLog(service, "✅ New Video Saved: ${downloadedNow.name}! Starting YouTube Upload...")
                            AutoTouchService.isJobRunning = false
                            runYouTubeAutoUpload(service, apiKey, model, folderPath, userGoal)
                            return
                        }

                        if (downloadAttempts >= 12) {
                            AutoTouchService.isJobRunning = false
                            service.overlayHud?.updateStatus("Download Timeout", "Tap video card manually to save", "ERROR")
                            VideoQueueManager.appendLog(service, "⚠️ New video download did not complete after 12 attempts; stopping so old video is not uploaded by mistake.")
                            return
                        }
                        continue
                    }
                }

                val domSnapshot = extractWebDomAndDrawBadges(webView)
                delay(180)

                val currentWebScreenshotB64 = if (VideoQueueManager.isScreenVisionEnabled(service)) {
                    service.overlayHud?.captureWebViewScreenshotBase64()
                } else null

                clearWebVisualBadges(webView)

                val verification = if (step == 1) {
                    WebStepVerification(true, true, true, false, "Initial step.")
                } else {
                    WebActionVerifier.verifyWebStepChanged(
                        previousUrl = previousUrl,
                        currentUrl = currentUrl,
                        previousDomHash = previousDomHash,
                        currentDomSnapshot = domSnapshot,
                        expectedOutcome = lastExpectedOutcome
                    )
                }

                if (verification.pageChanged) {
                    consecutiveVerificationFails = 0
                } else {
                    consecutiveVerificationFails++
                }
                previousUrl = currentUrl
                previousDomHash = domSnapshot.take(900).hashCode()

                val useHighModelNow = autoHighModelEnabled && consecutiveVerificationFails >= 1
                val domainKey = if (currentUrl.contains("labs.google", true)) "GOOGLE_FLOW" else "WEB"
                val learnedLessons = VideoQueueManager.getLearnedLessonsSummary(service, domainKey)

                val statusLabel = if (useHighModelNow) "High-Brain Verifying..." else "Thinking..."
                service.overlayHud?.updateStatus("Web • Step $step", statusLabel, "THINKING")

                val decision = GeminiBrain.decideNextWebAction(
                    apiKey = apiKey,
                    model = model,
                    goal = userGoal,
                    pageUrl = currentUrl,
                    pageTextAndElements = domSnapshot,
                    history = history,
                    memoryVault = memoryVault,
                    learnedLessons = learnedLessons,
                    screenChangedStatus = verification.summary,
                    screenshotBase64 = currentWebScreenshotB64,
                    previousScreenshotBase64 = previousWebScreenshotB64,
                    useHighModel = useHighModelNow,
                    highModelName = highModelName,
                    useGoogleSearch = googleSearchEnabled && useHighModelNow
                )

                previousWebScreenshotB64 = currentWebScreenshotB64
                lastExpectedOutcome = decision.expectedOutcome

                if (decision.learnedRule.isNotBlank()) {
                    VideoQueueManager.recordLearnedLesson(service, decision.learnedRule, domainKey)
                    VideoQueueManager.appendLog(service, "🧠 New Brain Rule: ${decision.learnedRule}")
                }

                val mood = if (decision.action == "WAIT") "WAITING" else "WORKING"
                service.overlayHud?.updateStatus("Web • ${decision.action}", decision.reason, mood)
                VideoQueueManager.appendLog(service, "🌐 Web Step $step [${decision.action}]: ${decision.reason}")

                when (decision.action) {
                    "CLICK_EL" -> {
                        val clickRes = WebActionVerifier.clickElementWithRealTouchFallback(service, webView, decision.elementIndex)
                        history.add("Step $step: CLICK_EL(${decision.elementIndex}) -> $clickRes")
                        if (clickRes.contains("arrow_forward", true) || clickRes.contains("Create", true)) {
                            flowPromptSubmitted = true
                            submittedAtTimeMs = System.currentTimeMillis()
                            VideoQueueManager.appendLog(service, "🔒 1-Video Credit Lock Activated!")
                        }
                    }
                    "CLICK_XY" -> {
                        if (decision.tapX > 0f && decision.tapY > 0f) {
                            WebActionVerifier.dispatchRealFingerTapOnWebView(webView, decision.tapX, decision.tapY)
                            history.add("Step $step: CLICK_XY(${decision.tapX.toInt()},${decision.tapY.toInt()})")
                        }
                    }
                    "TYPE_EL" -> {
                        val promptToUse = exactFlowPrompt.ifBlank { decision.textToType }
                        ensureGoogleFlowVideo8sMode(webView)
                        delay(700)
                        val typeRes = performWebJsTypeAndSubmit(webView, decision.elementIndex, promptToUse)
                        VideoQueueManager.appendLog(service, "✍️ Web Type Result: $typeRes")
                        history.add("Step $step: TYPE_EL(${decision.elementIndex}) -> $typeRes")

                        if (typeRes.startsWith("SUBMITTED_OK")) {
                            flowPromptSubmitted = true
                            submittedAtTimeMs = System.currentTimeMillis()
                            VideoQueueManager.appendLog(service, "🔒 Prompt Verified & Submitted! Waiting for 0% -> 100% render...")
                            delay(4500)
                        } else if (typeRes == "ERR_NO_PROMPT_BOX_YET") {
                            if (service.isKeyboardOpen()) {
                                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                                delay(1000)
                            }
                            performSmartWebScroll(webView)
                        }
                    }
                    "SCROLL" -> {
                        val scrollRes = performSmartWebScroll(webView)
                        history.add("Step $step: SCROLL ($scrollRes)")
                    }
                    "LOAD_URL" -> {
                        if (decision.url.startsWith("http")) {
                            webView?.loadUrl(decision.url)
                            delay(4000)
                        }
                        history.add("Step $step: LOAD_URL ${decision.url}")
                    }
                    "DOWNLOAD_VIDEO", "DOWNLOAD_URL" -> {
                        val dlRes = WebActionVerifier.openPlayAndDownloadFlowVideo(service, webView)
                        VideoQueueManager.appendLog(service, "📥 Download Action: $dlRes")
                        delay(4500)
                        history.add("Step $step: DOWNLOAD_VIDEO ($dlRes)")
                    }
                    "WAIT" -> {
                        delay(5000)
                        history.add("Step $step: WAIT - ${decision.reason}")
                    }
                    "CHAIN_YOUTUBE" -> {
                        WebActionVerifier.openPlayAndDownloadFlowVideo(service, webView)
                        delay(4500)
                        val newFile = VideoQueueManager.findNewlyDownloadedVideo(startTimeMs, folderPath)
                        if (newFile != null || !forceChainYouTube) {
                            AutoTouchService.isJobRunning = false
                            runYouTubeAutoUpload(service, apiKey, model, folderPath, userGoal)
                            return
                        }
                    }
                    "DONE" -> {
                        AutoTouchService.isJobRunning = false
                        service.overlayHud?.updateStatus("✅ Web Done", decision.reason, "DONE")
                        VideoQueueManager.appendLog(service, "✅ Web Task Completed: ${decision.reason}")
                        return
                    }
                }
                delay(stepDelay)
            }
        } catch (e: CancellationException) {
            // Clean stop
        } catch (e: Exception) {
            VideoQueueManager.recordLearnedLesson(service, "Web error: ${e.message}", "WEB")
            AutoTouchService.isJobRunning = false
            service.overlayHud?.updateStatus("Web Error", e.message ?: "Failed", "ERROR")
        } finally {
            clearWebVisualBadges(webView)
            AutoTouchService.isJobRunning = false
        }
    }

    // ========================================================================
    // FULL 5-STEP YOUTUBE SHORTS AUTO-UPLOADER (Collapses Mini Window First!)
    // ========================================================================
    suspend fun runYouTubeAutoUpload(
        service: AutoTouchService,
        apiKey: String,
        model: String,
        folderPath: String,
        userPrompt: String,
        createAiVideoIfEmpty: Boolean = false
    ) {
        if (AutoTouchService.isJobRunning) {
            VideoQueueManager.appendLog(service, "⚠️ A task is already running!")
            return
        }
        AutoTouchService.isJobRunning = true

        // CRITICAL FIX: Immediately collapse the Browser Mini Window back to the small bot icon so YouTube is 100% uncovered!
        service.overlayHud?.collapseToCompactStage()
        service.overlayHud?.show()
        service.overlayHud?.setCursorOverlayVisible(true)

        try {
            service.overlayHud?.updateStatus("Step 1/5 • Folder", "Scanning folder for video...", "WORKING")
            val nextVideo = VideoQueueManager.getNextPendingVideo(service, folderPath)
            if (nextVideo == null) {
                if (createAiVideoIfEmpty) {
                    AutoTouchService.isJobRunning = false
                    runGoogleFlowPipeline(service, apiKey, model, VideoQueueManager.getPinnedFlowUrl(service), userPrompt, 1, folderPath)
                    return
                }
                AutoTouchService.isJobRunning = false
                service.overlayHud?.setCursorOverlayVisible(false)
                service.overlayHud?.updateStatus("Queue Empty", "Tap 'Reset History' or make video!", "ERROR")
                VideoQueueManager.appendLog(service, "⚠️ No pending video in folder!")
                return
            }

            VideoQueueManager.appendLog(service, "🎬 Uploading Video (${if (VideoQueueManager.isUploadNewestFirst(service)) "Newest First" else "Oldest First"}): ${nextVideo.name}")
            service.overlayHud?.updateStatus("Step 2/5 • Gemini", "Generating viral Title...", "THINKING")
            val metadata = GeminiBrain.generateYouTubeMetadata(apiKey, model, nextVideo.name, userPrompt)

            copyToClipboard(service, metadata.title)
            service.overlayHud?.updateStatus("Step 3/5 • YouTube", "Opening YouTube Upload...", "WORKING")
            launchYouTubeWithVideo(service, nextVideo)
            delay(5500)

            var nextClickCount = 0
            var titleFilled = false
            var descFilled = false
            val (realW, realH) = service.getRealScreenDimensions()

            for (step in 1..25) {
                val nodes = PhoneAgentManager.captureScreenNodes(service)
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

                // Step 3: Click 'Next' on Trimmer screen and 'Next' on Editor screen
                if (!isOnAddDetailsScreen && !titleFilled && nextBtn != null) {
                    nextClickCount++
                    service.overlayHud?.updateStatus("Step 3/5 • Editor ($nextClickCount)", "Tapping 'Next'...", "WORKING")
                    PhoneAgentManager.animateAndPhysicalTapNode(service, nextBtn, "Tap: Next")
                    delay(4000)
                    continue
                }

                // Step 4: Focus Caption Box & Paste AI Title
                if (!titleFilled && (isOnAddDetailsScreen || nextClickCount >= 2)) {
                    copyToClipboard(service, metadata.title)
                    val captionNode = appNodes.firstOrNull {
                        val combined = "${it.text} ${it.contentDesc} ${it.hintText} ${it.viewId}"
                        combined.contains("Caption your Short", true) ||
                        combined.contains("Create a title", true) ||
                        combined.contains("caption", true)
                    } ?: appNodes.firstOrNull { it.isEditable || it.supportsSetText }

                    val capX = captionNode?.centerX ?: (realW * 0.52f)
                    val capY = captionNode?.centerY ?: (realH * 0.138f)

                    service.overlayHud?.updateStatus("Step 4/5 • Caption", "Focusing Caption Box...", "WORKING")
                    PhoneAgentManager.animateAndTapXY(service, capX, capY, "Tap: Caption")
                    delay(2200)

                    service.overlayHud?.updateStatus("Step 4/5 • Typing", "Pasting AI Title...", "WORKING")
                    service.insertCaptionSingleClean(metadata.title, capX, capY)

                    titleFilled = true
                    delay(1800)
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    delay(2800)
                    continue
                }

                // Optional Description Fill
                if (titleFilled && !descFilled) {
                    val addDescNode = appNodes.firstOrNull {
                        "${it.text} ${it.contentDesc} ${it.hintText}".contains("Add description", true)
                    }
                    if (addDescNode != null) {
                        PhoneAgentManager.animateAndPhysicalTapNode(service, addDescNode, "Tap: Description")
                        delay(2000)
                        service.insertCaptionSingleClean(metadata.description, realW * 0.5f, realH * 0.25f)
                        descFilled = true
                        delay(1800)
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        delay(2200)
                        continue
                    } else {
                        descFilled = true
                    }
                }

                // Step 5: Tap bottom 'Upload Short' button
                if (titleFilled) {
                    if (service.isKeyboardOpen()) {
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        delay(2500)
                    }

                    val exactUploadX = realW * 0.50f
                    val exactUploadYPoints = listOf(realH * 0.964f, realH * 0.958f, realH * 0.968f)

                    for (attempt in 1..3) {
                        val currentNodes = PhoneAgentManager.captureScreenNodes(service).filter {
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

                        service.overlayHud?.updateStatus("Step 5/5 • Upload", "Tapping 'Upload Short' (#$attempt)...", "WORKING")
                        PhoneAgentManager.animateAndTapXY(service, tapX, tapY, "Tap: Upload Short")
                        delay(600)
                        if (uploadBtnNode != null) service.clickNodeAndParents(uploadBtnNode.node)
                        delay(3500)
                    }

                    VideoQueueManager.markVideoUploaded(service, nextVideo)
                    AutoTouchService.isJobRunning = false
                    service.overlayHud?.setCursorOverlayVisible(false)
                    service.overlayHud?.updateStatus("🎉 Upload Success!", "'${nextVideo.name.take(18)}' Published!", "DONE")
                    VideoQueueManager.appendLog(service, "✅ YouTube Upload Published: ${nextVideo.name}")
                    break
                }
                delay(2000)
            }
        } catch (e: CancellationException) {
            // Normal user stop
        } catch (e: Exception) {
            AutoTouchService.isJobRunning = false
            service.overlayHud?.setCursorOverlayVisible(false)
            service.overlayHud?.updateStatus("Upload Error", e.message ?: "Error", "ERROR")
            VideoQueueManager.appendLog(service, "❌ Upload Error: ${e.message}")
        } finally {
            service.overlayHud?.setCursorOverlayVisible(false)
            AutoTouchService.isJobRunning = false
        }
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

    private suspend fun performSmartWebScroll(webView: WebView?): String = suspendCancellableCoroutine { cont ->
        if (webView == null) { cont.resume("NO_WEBVIEW"); return@suspendCancellableCoroutine }
        val js = """
            (function() {
                window.scrollBy({ top: 500, behavior: 'smooth' });
                var containers = document.querySelectorAll('main, [role="main"], section, div');
                for (var i = 0; i < containers.length; i++) {
                    var c = containers[i];
                    if (c.scrollHeight > c.clientHeight + 40) {
                        c.scrollTop += 450;
                    }
                }
                var newProjBtn = Array.from(document.querySelectorAll('button, [role="button"], a')).find(
                    e => (e.innerText || '').indexOf('New project') !== -1
                );
                if (newProjBtn) {
                    newProjBtn.scrollIntoView({ block: 'center' });
                    return 'SCROLLED_TO_NEW_PROJECT';
                }
                return 'SCROLLED_DOWN';
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { res ->
            if (cont.isActive) cont.resume(res?.trim('"') ?: "SCROLLED")
        }
    }

    private suspend fun ensureGoogleFlowVideo8sMode(webView: WebView?): String = suspendCancellableCoroutine { cont ->
        if (webView == null) { cont.resume("NO_WEBVIEW"); return@suspendCancellableCoroutine }
        val js = """
            (function() {
                var allBtns = document.querySelectorAll('button, [role="button"], div');
                var modePill = null;
                for (var i = 0; i < allBtns.length; i++) {
                    var t = (allBtns[i].innerText || '').trim();
                    if ((t.indexOf('Nano Banana') !== -1 || t.indexOf('Image') !== -1 || t.indexOf('x2') !== -1 || t.indexOf('x4') !== -1) && allBtns[i].getBoundingClientRect().top > 220) {
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

    private suspend fun checkGoogleFlowRenderState(webView: WebView?): String = suspendCancellableCoroutine { cont ->
        if (webView == null) { cont.resume("NO_WEBVIEW"); return@suspendCancellableCoroutine }
        val js = """
            (function() {
                var fullText = document.body ? document.body.innerText : '';
                var pctMatch = fullText.match(/\b(\d{1,3})\s*%/);
                if (pctMatch) return 'RENDERING:' + pctMatch[1] + '%';

                var vids = document.querySelectorAll('video');
                for (var v = 0; v < vids.length; v++) {
                    if ((vids[v].currentSrc || vids[v].src || '').length > 4) return 'READY_VIDEO_PLAYER';
                }

                if (fullText.indexOf('Failed') !== -1 || fullText.indexOf('violate') !== -1) return 'FAILED:POLICY';

                var imgs = document.querySelectorAll('img, video, canvas');
                for (var m = 0; m < imgs.length; m++) {
                    var r = imgs[m].getBoundingClientRect();
                    if (r.top > 110 && r.top < 650 && r.width > 70 && r.height > 90) {
                        return 'READY_CARD_THUMBNAIL';
                    }
                }

                if (fullText.indexOf('Start creating or drop media') !== -1) {
                    return 'IDLE_EMPTY';
                }
                return 'RENDERING:Starting...';
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { res ->
            if (cont.isActive) cont.resume(res?.trim('"') ?: "IDLE_EMPTY")
        }
    }

    private suspend fun extractWebDomAndDrawBadges(webView: WebView?): String = suspendCancellableCoroutine { cont ->
        if (webView == null) { cont.resume("Empty"); return@suspendCancellableCoroutine }
        val js = """
            (function() {
                document.querySelectorAll('.aura-vision-badge').forEach(b => b.remove());
                var nodes = document.querySelectorAll('a, button, input, textarea, [role="button"], [role="textbox"], [contenteditable="true"], video');
                var out = [];
                var hasVideo = document.querySelectorAll('video').length > 0 ? 'YES' : 'NO';
                for (var i = 0; i < nodes.length && out.length < 75; i++) {
                    var el = nodes[i];
                    var rect = el.getBoundingClientRect();
                    if (rect.width > 5 && rect.height > 5 && rect.bottom > 0 && rect.top < window.innerHeight) {
                        el.setAttribute('data-aura-idx', i);
                        var tag = el.tagName.toLowerCase();
                        var ph = el.placeholder || el.getAttribute('placeholder') || el.getAttribute('aria-label') || '';
                        var val = (el.value !== undefined ? el.value : (el.isContentEditable ? el.innerText : '')) || '';
                        var txt = (el.innerText || ph || el.src || '').trim().replace(/\s+/g, ' ').substring(0, 60);
                        var valInfo = (tag === 'textarea' || tag === 'input' || el.isContentEditable)
                            ? ' | VALUE="' + val.trim().substring(0, 35) + '" | TOP=' + Math.round(rect.top)
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
                var bodyText = (document.body ? document.body.innerText : '').substring(0, 1500).replace(/\s+/g, ' ');
                return 'HAS_VIDEO_ELEMENT: ' + hasVideo + '\nPAGE_TEXT: ' + bodyText + '\nELEMENTS:\n' + out.join('\n');
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { res ->
            if (cont.isActive) cont.resume(res?.trim('"')?.replace("\\n", "\n") ?: "")
        }
    }

    private fun clearWebVisualBadges(webView: WebView?) {
        webView?.evaluateJavascript("document.querySelectorAll('.aura-vision-badge').forEach(b=>b.remove());", null)
    }

    /**
     * Strictly targets the bottom "What do you want to create?" prompt composer and NEVER types into the top search bar!
     * Returns 'ERR_NO_PROMPT_BOX_YET' if the bottom prompt composer is not mounted/visible yet.
     */
    private suspend fun performWebJsTypeAndSubmit(
        webView: WebView?,
        index: Int,
        text: String
    ): String = suspendCancellableCoroutine { cont ->
        if (webView == null) { cont.resume("NO_WEBVIEW"); return@suspendCancellableCoroutine }
        val safe = JSONObject.quote(text)
        val js = """
            (function() {
                if (document.activeElement && document.activeElement.getBoundingClientRect().top < 170) {
                    document.activeElement.blur();
                }

                var allCandidates = document.querySelectorAll('textarea, [contenteditable="true"], [role="textbox"], input[type="text"], input:not([type])');
                var flowPromptBox = null;

                for (var i = 0; i < allCandidates.length; i++) {
                    var c = allCandidates[i];
                    var rect = c.getBoundingClientRect();
                    var ph = (c.placeholder || c.getAttribute('placeholder') || c.getAttribute('aria-label') || c.innerText || '').toLowerCase();
                    var isSearchBar = ph.indexOf('search') !== -1 || rect.top < 175;
                    if (!isSearchBar && rect.width > 80 && (ph.indexOf('what do you want to create') !== -1 || ph.indexOf('create') !== -1 || ph.indexOf('prompt') !== -1 || c.tagName.toLowerCase() === 'textarea' || c.isContentEditable)) {
                        flowPromptBox = c;
                        break;
                    }
                }

                if (!flowPromptBox) {
                    var indexed = document.querySelector('[data-aura-idx="$index"]');
                    if (indexed && indexed.getBoundingClientRect().top >= 175 && (indexed.tagName.toLowerCase() === 'textarea' || indexed.isContentEditable)) {
                        flowPromptBox = indexed;
                    }
                }

                if (!flowPromptBox) {
                    window.scrollTo(0, document.body.scrollHeight);
                    return 'ERR_NO_PROMPT_BOX_YET';
                }

                flowPromptBox.scrollIntoView({ block: 'center' });
                flowPromptBox.click();
                flowPromptBox.focus();

                try {
                    if (flowPromptBox.select) flowPromptBox.select();
                    else document.execCommand('selectAll', false, null);
                    document.execCommand('insertText', false, $safe);
                } catch (e) {}

                var currentVal = (flowPromptBox.value !== undefined ? flowPromptBox.value : flowPromptBox.innerText) || '';
                if (currentVal.trim().length < 5) {
                    try {
                        var proto = flowPromptBox.tagName.toLowerCase() === 'textarea'
                            ? window.HTMLTextAreaElement.prototype
                            : window.HTMLInputElement.prototype;
                        var nativeSetter = Object.getOwnPropertyDescriptor(proto, 'value') ? Object.getOwnPropertyDescriptor(proto, 'value').set : null;
                        if (nativeSetter) nativeSetter.call(flowPromptBox, $safe);
                        else if (flowPromptBox.isContentEditable) flowPromptBox.innerText = $safe;
                        else flowPromptBox.value = $safe;

                        flowPromptBox.dispatchEvent(new InputEvent('input', { bubbles: true, cancelable: true, inputType: 'insertText', data: $safe }));
                        flowPromptBox.dispatchEvent(new Event('change', { bubbles: true }));
                    } catch (e2) {}
                }

                var finalVal = (flowPromptBox.value !== undefined ? flowPromptBox.value : flowPromptBox.innerText) || '';
                if (finalVal.trim().length < 5) {
                    return 'ERR_TYPE_FAILED';
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

                return 'SUBMITTED_OK_TYPED_LEN:' + finalVal.length;
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { res ->
            if (cont.isActive) cont.resume(res?.trim('"') ?: "ERR")
        }
    }

    private fun copyToClipboard(context: Context, text: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ai_title", text))
    }

    private fun launchYouTubeWithVideo(context: Context, videoFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", videoFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            setPackage("com.google.android.youtube")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { context.startActivity(intent) } catch (_: Exception) {}
    }
}
