package com.auradroid.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
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
        if (AutoTouchService.isJobRunning) return
        AutoTouchService.isJobRunning = true
        service.overlayHud?.show()
        service.overlayHud?.setCursorOverlayVisible(false)

        try {
            service.overlayHud?.updateStatus("Flow 8s", "Creating policy-safe prompt...", "THINKING")
            val freshPrompt = GeminiBrain.generateDailyFlowPrompt(apiKey, model, topicIdea)
            VideoQueueManager.appendLog(service, "🎬 Safe Flow 8s Prompt: $freshPrompt")

            val goal = """
                We are on Google Flow ($flowUrl).
                1. If on landing page, click '+ New project'.
                2. Ensure mode is 'Video • 720p • 8s • x1'.
                3. Type prompt ONCE: "$freshPrompt"
                4. Wait for 100% render, download MP4 and CHAIN_YOUTUBE.
            """.trimIndent()

            AutoTouchService.isJobRunning = false
            runBackgroundWebTask(service, apiKey, model, flowUrl, goal, folderPath, chainYouTubeAfterDownload = true, forceChainYouTube = true, exactFlowPrompt = freshPrompt)
        } catch (e: CancellationException) {
            // Clean stop
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
        if (AutoTouchService.isJobRunning) return
        AutoTouchService.isJobRunning = true
        service.overlayHud?.show()
        service.overlayHud?.setCursorOverlayVisible(false)

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
        var previousWebScreenshotB64: String? = null
        var previousDomHash = 0
        var consecutiveVerificationFails = 0

        try {
            val currentLoadedUrl = webView?.url ?: ""
            if (initialUrl.isNotBlank() && initialUrl.startsWith("http") && !currentLoadedUrl.startsWith(initialUrl.take(22), true)) {
                service.overlayHud?.updateStatus("Web • Opening", initialUrl, "WORKING")
                VideoQueueManager.appendLog(service, "🌐 Opening: $initialUrl")
                webView?.loadUrl(initialUrl)
                delay(5000)
            }

            val history = mutableListOf<String>()

            for (step in 1..maxSteps) {
                val newlyDownloaded = VideoQueueManager.findNewlyDownloadedVideo(startTimeMs, folderPath)
                if (newlyDownloaded != null && (chainYouTubeAfterDownload || forceChainYouTube || userGoal.contains("youtube", true))) {
                    VideoQueueManager.appendLog(service, "🎬 Downloaded Video Ready: ${newlyDownloaded.name}!")
                    AutoTouchService.isJobRunning = false
                    runYouTubeAutoUpload(service, apiKey, model, folderPath, userGoal)
                    return
                }

                val currentUrl = webView?.url ?: initialUrl
                if (!flowPromptSubmitted && currentUrl.contains("labs.google/fx", true)) {
                    ensureGoogleFlowVideo8sMode(webView)
                    delay(1500)
                }

                val domSnapshot = extractWebDomAndDrawBadges(webView)
                delay(180)

                val currentWebScreenshotB64 = if (VideoQueueManager.isScreenVisionEnabled(service)) {
                    service.overlayHud?.captureWebViewScreenshotBase64()
                } else null

                clearWebVisualBadges(webView)

                val currentDomHash = domSnapshot.take(600).hashCode()
                val screenChangedStatus = if (step == 1) "Initial step." else if (currentDomHash != previousDomHash) {
                    consecutiveVerificationFails = 0
                    "SCREEN_CHANGED: YES"
                } else {
                    consecutiveVerificationFails++
                    "SCREEN_CHANGED: NO"
                }
                previousDomHash = currentDomHash
                val useHighModelNow = autoHighModelEnabled && consecutiveVerificationFails >= 2

                if (flowPromptSubmitted) {
                    val elapsedSec = (System.currentTimeMillis() - submittedAtTimeMs) / 1000L
                    val renderState = checkGoogleFlowRenderState(webView)

                    if (elapsedSec < 12L || renderState.startsWith("RENDERING:")) {
                        val pct = renderState.removePrefix("RENDERING:").ifBlank { "${elapsedSec}s" }
                        service.overlayHud?.updateStatus("Flow ($pct)", "Waiting for 8s video...", "WAITING")
                        previousWebScreenshotB64 = currentWebScreenshotB64
                        delay(4500)
                        continue
                    }

                    if (renderState.startsWith("FAILED:")) {
                        service.overlayHud?.updateStatus("Flow Error", "Prompt rejected", "ERROR")
                        AutoTouchService.isJobRunning = false
                        return
                    }

                    downloadAttempts++
                    service.overlayHud?.updateStatus("Flow • Saving", "Downloading MP4 (#$downloadAttempts)...", "WORKING")
                    WebActionVerifier.openPlayAndDownloadFlowVideo(service, webView)
                    previousWebScreenshotB64 = currentWebScreenshotB64
                    delay(5000)

                    val downloadedNow = VideoQueueManager.findNewlyDownloadedVideo(startTimeMs, folderPath)
                    if (downloadedNow != null) {
                        VideoQueueManager.recordLearnedLesson(service, "Successfully downloaded Flow video", "GOOGLE_FLOW")
                        AutoTouchService.isJobRunning = false
                        runYouTubeAutoUpload(service, apiKey, model, folderPath, userGoal)
                        return
                    }
                    if (downloadAttempts >= 12) {
                        AutoTouchService.isJobRunning = false
                        runYouTubeAutoUpload(service, apiKey, model, folderPath, userGoal)
                        return
                    }
                    continue
                }

                val domainKey = if (currentUrl.contains("labs.google", true)) "GOOGLE_FLOW" else "WEB"
                val learnedLessons = VideoQueueManager.getLearnedLessonsSummary(service, domainKey)

                val decision = GeminiBrain.decideNextWebAction(
                    apiKey = apiKey, model = model, goal = userGoal,
                    pageUrl = currentUrl, pageTextAndElements = domSnapshot,
                    history = history, memoryVault = memoryVault,
                    learnedLessons = learnedLessons, screenChangedStatus = screenChangedStatus,
                    screenshotBase64 = currentWebScreenshotB64, previousScreenshotBase64 = previousWebScreenshotB64,
                    useHighModel = useHighModelNow, highModelName = highModelName, useGoogleSearch = googleSearchEnabled
                )

                previousWebScreenshotB64 = currentWebScreenshotB64

                if (decision.learnedRule.isNotBlank()) {
                    VideoQueueManager.recordLearnedLesson(service, decision.learnedRule, domainKey)
                }

                service.overlayHud?.updateStatus("Web • Step $step", decision.reason, if (decision.action == "WAIT") "WAITING" else "WORKING")

                when (decision.action) {
                    "CLICK_EL" -> {
                        val clickRes = WebActionVerifier.clickElementWithRealTouchFallback(service, webView, decision.elementIndex)
                        history.add("Step $step: CLICK_EL(${decision.elementIndex}) -> $clickRes")
                        if (clickRes.contains("arrow_forward", true) || clickRes.contains("Create", true)) {
                            flowPromptSubmitted = true
                            submittedAtTimeMs = System.currentTimeMillis()
                        }
                    }
                    "CLICK_XY" -> if (decision.tapX > 0f && decision.tapY > 0f) WebActionVerifier.dispatchRealFingerTapOnWebView(webView, decision.tapX, decision.tapY)
                    "TYPE_EL" -> {
                        val promptToUse = exactFlowPrompt.ifBlank { decision.textToType }
                        ensureGoogleFlowVideo8sMode(webView)
                        delay(800)
                        val typeRes = performWebJsTypeAndSubmit(webView, decision.elementIndex, promptToUse)
                        history.add("Step $step: TYPE_EL -> $typeRes")
                        if (typeRes.startsWith("SUBMITTED_OK") || typeRes.contains("TYPED_LEN:")) {
                            flowPromptSubmitted = true
                            submittedAtTimeMs = System.currentTimeMillis()
                            delay(4500)
                        }
                    }
                    "DOWNLOAD_VIDEO", "DOWNLOAD_URL" -> WebActionVerifier.openPlayAndDownloadFlowVideo(service, webView)
                    "WAIT" -> delay(6000)
                    "CHAIN_YOUTUBE" -> {
                        WebActionVerifier.openPlayAndDownloadFlowVideo(service, webView)
                        delay(4000)
                        AutoTouchService.isJobRunning = false
                        runYouTubeAutoUpload(service, apiKey, model, folderPath, userGoal)
                        return
                    }
                    "DONE" -> {
                        AutoTouchService.isJobRunning = false
                        service.overlayHud?.updateStatus("✅ Done", decision.reason, "DONE")
                        return
                    }
                }
                delay(stepDelay)
            }
        } catch (e: CancellationException) {
            // Clean stop
        } catch (e: Exception) {
            AutoTouchService.isJobRunning = false
            service.overlayHud?.updateStatus("Web Error", e.message ?: "Failed", "ERROR")
        } finally {
            clearWebVisualBadges(webView)
            AutoTouchService.isJobRunning = false
        }
    }

    suspend fun runYouTubeAutoUpload(
        service: AutoTouchService,
        apiKey: String,
        model: String,
        folderPath: String,
        userPrompt: String,
        createAiVideoIfEmpty: Boolean = false
    ) {
        if (AutoTouchService.isJobRunning) return
        AutoTouchService.isJobRunning = true
        service.overlayHud?.show()
        service.overlayHud?.setCursorOverlayVisible(true)

        try {
            service.overlayHud?.updateStatus("YT Upload", "Scanning folder...", "WORKING")
            val nextVideo = VideoQueueManager.getNextPendingVideo(service, folderPath)
            if (nextVideo == null) {
                if (createAiVideoIfEmpty) {
                    AutoTouchService.isJobRunning = false
                    runGoogleFlowPipeline(service, apiKey, model, VideoQueueManager.getPinnedFlowUrl(service), userPrompt, 1, folderPath)
                    return
                }
                AutoTouchService.isJobRunning = false
                service.overlayHud?.setCursorOverlayVisible(false)
                service.overlayHud?.updateStatus("Queue Empty", "No pending video", "ERROR")
                return
            }

            val metadata = GeminiBrain.generateYouTubeMetadata(apiKey, model, nextVideo.name, userPrompt)
            copyToClipboard(service, metadata.title)
            launchYouTubeWithVideo(service, nextVideo)
            delay(5500)

            val (realW, realH) = service.getRealScreenDimensions()
            var titleFilled = false

            for (step in 1..25) {
                val nodes = captureScreenNodes(service)
                val nextBtn = nodes.firstOrNull { it.text.equals("Next", true) || it.contentDesc.equals("Next", true) }
                if (!titleFilled && nextBtn != null) {
                    PhoneAgentManager.animateAndPhysicalTapNode(service, nextBtn, "Tap: Next")
                    delay(4000)
                    continue
                }
                if (!titleFilled) {
                    PhoneAgentManager.animateAndTapXY(service, realW * 0.52f, realH * 0.138f, "Tap: Title")
                    delay(2200)
                    insertCaptionSingleClean(service, metadata.title)
                    titleFilled = true
                    delay(1800)
                    service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                    delay(2800)
                    continue
                }
                if (titleFilled) {
                    val uploadBtn = captureScreenNodes(service).firstOrNull { it.text.equals("Upload Short", true) || it.text.equals("Upload", true) }
                    if (uploadBtn != null) PhoneAgentManager.animateAndPhysicalTapNode(service, uploadBtn, "Upload")
                    else PhoneAgentManager.animateAndTapXY(service, realW * 0.5f, realH * 0.96f, "Upload")
                    VideoQueueManager.markVideoUploaded(service, nextVideo)
                    AutoTouchService.isJobRunning = false
                    service.overlayHud?.setCursorOverlayVisible(false)
                    service.overlayHud?.updateStatus("🎉 Published!", nextVideo.name, "DONE")
                    break
                }
                delay(2000)
            }
        } catch (e: CancellationException) {
            // Clean stop
        } catch (e: Exception) {
            AutoTouchService.isJobRunning = false
            service.overlayHud?.setCursorOverlayVisible(false)
            service.overlayHud?.updateStatus("Upload Error", e.message ?: "Error", "ERROR")
        } finally {
            AutoTouchService.isJobRunning = false
        }
    }

    private suspend fun ensureGoogleFlowVideo8sMode(webView: WebView?): String = suspendCancellableCoroutine { cont ->
        if (webView == null) { cont.resume("NO_WEBVIEW"); return@suspendCancellableCoroutine }
        webView.evaluateJavascript("(function(){ var btn=Array.from(document.querySelectorAll('div,button')).find(e=>e.innerText.includes('Nano Banana')||e.innerText.includes('Image')); if(btn){btn.click(); setTimeout(()=>{ document.querySelectorAll('span,div,button').forEach(e=>{if(e.innerText==='Video'||e.innerText==='8s')e.click();}); },350); return 'SWITCHED';} return 'OK'; })();") { res ->
            if (cont.isActive) cont.resume(res?.trim('"') ?: "OK")
        }
    }

    private suspend fun checkGoogleFlowRenderState(webView: WebView?): String = suspendCancellableCoroutine { cont ->
        if (webView == null) { cont.resume("NO_WEBVIEW"); return@suspendCancellableCoroutine }
        webView.evaluateJavascript("(function(){ var t=document.body?document.body.innerText:''; var m=t.match(/\\b(\\d{1,3})\\s*%/); if(m)return 'RENDERING:'+m[1]+'%'; if(document.querySelectorAll('video').length>0)return 'READY_VIDEO_PLAYER'; if(t.indexOf('Failed')!==-1)return 'FAILED:POLICY'; if(t.indexOf('Start creating')===-1)return 'RENDERING:Progress'; return 'IDLE'; })();") { res ->
            if (cont.isActive) cont.resume(res?.trim('"') ?: "IDLE")
        }
    }

    private suspend fun extractWebDomAndDrawBadges(webView: WebView?): String = suspendCancellableCoroutine { cont ->
        if (webView == null) { cont.resume("Empty"); return@suspendCancellableCoroutine }
        webView.evaluateJavascript("(function(){ document.querySelectorAll('.aura-vision-badge').forEach(b=>b.remove()); var nodes=document.querySelectorAll('a,button,input,textarea,[role=\"button\"],[contenteditable=\"true\"],video'); var out=[]; for(var i=0;i<nodes.length&&out.length<60;i++){ var r=nodes[i].getBoundingClientRect(); if(r.width>5&&r.height>5){ nodes[i].setAttribute('data-aura-idx',i); out.push('['+i+'] <'+nodes[i].tagName.toLowerCase()+'> '+(nodes[i].innerText||nodes[i].placeholder||'').substring(0,40)); } } return out.join('\\n'); })();") { res ->
            if (cont.isActive) cont.resume(res?.trim('"')?.replace("\\n", "\n") ?: "")
        }
    }

    private fun clearWebVisualBadges(webView: WebView?) {
        webView?.evaluateJavascript("document.querySelectorAll('.aura-vision-badge').forEach(b=>b.remove());", null)
    }

    private suspend fun performWebJsTypeAndSubmit(webView: WebView?, index: Int, text: String): String = suspendCancellableCoroutine { cont ->
        if (webView == null) { cont.resume("NO_WEBVIEW"); return@suspendCancellableCoroutine }
        val safe = JSONObject.quote(text)
        webView.evaluateJavascript("(function(){ var t=document.querySelector('[data-aura-idx=\"$index\"]')||document.querySelector('textarea'); if(!t)return 'NO_BOX'; t.focus(); try{document.execCommand('insertText',false,$safe);}catch(e){t.value=$safe;} setTimeout(()=>{ var b=Array.from(document.querySelectorAll('button,[role=\"button\"]')).find(e=>(e.innerText||'').includes('arrow_forward')||e.innerText==='Create'); if(b)b.click(); },400); return 'SUBMITTED_OK'; })();") { res ->
            if (cont.isActive) cont.resume(res?.trim('"') ?: "OK")
        }
    }

    private fun copyToClipboard(context: Context, text: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("title", text))
    }

    private fun launchYouTubeWithVideo(context: Context, videoFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", videoFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/*"; setPackage("com.google.android.youtube")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { context.startActivity(intent) } catch (_: Exception) {}
    }

    private fun insertCaptionSingleClean(context: Context, text: String) {
        // This manager is not an AccessibilityService; delegate accessibility input to the active service.
        copyToClipboard(context, text)
        val service = AutoTouchService.instance ?: return
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        service.rootInActiveWindow
            ?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun captureScreenNodes(service: AutoTouchService): List<UiNodeSnapshot> = PhoneAgentManager.captureScreenNodes(service)
}
