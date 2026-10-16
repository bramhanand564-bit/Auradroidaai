package com.auradroid.agent

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

object WebPipelineManager {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentWebJob: Job? = null

    @Volatile
    var isWebTaskRunning: Boolean = false
        private set

    /**
     * Both stopCurrentJob() and stopEverything(context) are provided so AiOverlayHud and MainActivity
     * always compile and stop cleanly without any missing reference!
     */
    fun stopCurrentJob() {
        isWebTaskRunning = false
        currentWebJob?.cancel()
        currentWebJob = null
    }

    fun stopEverything(context: Context? = null) {
        stopCurrentJob()
        context?.let {
            VideoQueueManager.appendLog(it, "🛑 Nax Bot Web Pipeline stopped by user.")
        }
    }

    /**
     * Master Customizable Story Series (Part 1, Part 2, Part 3 / Side-Story / Standalone) +
     * Custom Video Duration (8s, 10s, 12s, 15s) + First-Run Live Calibration -> YouTube Shorts Pipeline!
     */
    fun startFlowToYouTubePipeline(
        context: Context,
        apiKey: String,
        model: String,
        flowTopic: String,
        folderPath: String,
        dailyVideoLimit: Int = 1
    ) {
        stopCurrentJob()
        val hud = AutoTouchService.instance?.overlayHud
        val webView = hud?.getOrCreateSharedWebView()
        if (webView == null) {
            VideoQueueManager.appendLog(context, "⚠️ Accessibility Service (Nax Bot) is OFF! Please enable Auto-Touch first.")
            return
        }

        isWebTaskRunning = true
        currentWebJob = scope.launch {
            try {
                val storyMode = VideoQueueManager.getStoryMode(context)
                val nextEpNum = VideoQueueManager.getNextEpisodeNumber(context)
                val characterLock = VideoQueueManager.getCharacterVisualLock(context)
                val customTwist = VideoQueueManager.getStoryCustomTwist(context)
                val targetDurationSec = VideoQueueManager.getTargetVideoDurationSec(context)
                val previousContext = VideoQueueManager.getPreviousEpisodesContext(context)

                // Check First-Run Live Test & Learn calibration state
                val firstRunLearnEnabled = VideoQueueManager.isFirstRunLiveLearnEnabled(context)
                val isAlreadyVerified = VideoQueueManager.isSkillLiveVerified(context, "GOOGLE_FLOW")
                val runInLiveCalibrationWindow = firstRunLearnEnabled && !isAlreadyVerified

                if (runInLiveCalibrationWindow) {
                    hud.openForFirstRunLiveCalibration()
                    VideoQueueManager.appendLog(
                        context,
                        "🎯 First-Run Live Calibration Active: Opening live window so Nax Bot learns exact screenshots & touch coordinates!"
                    )
                } else {
                    VideoQueueManager.appendLog(
                        context,
                        "🌐 Verified Background Mode Active: Running silently in background while your phone stays free!"
                    )
                }

                hud.updateStatus("Nax Bot • Part $nextEpNum", "Writing ${targetDurationSec}s $storyMode story...", "THINKING")
                VideoQueueManager.appendLog(
                    context,
                    "🎬 Generating $storyMode Episode #$nextEpNum (${targetDurationSec}s) using Gemini 3.8/3.1..."
                )

                val episodePlan = GeminiBrain.generateEpisodicStoryPrompt(
                    apiKey = apiKey,
                    model = model,
                    seriesTheme = flowTopic,
                    characterLock = characterLock,
                    storyMode = storyMode,
                    episodeNumber = nextEpNum,
                    previousEpisodesContext = previousContext,
                    customUserTwist = customTwist,
                    targetDurationSec = targetDurationSec
                )

                VideoQueueManager.appendLog(
                    context,
                    "📖 Part ${episodePlan.episodeNumber}: ${episodePlan.episodeTitle}\nPrompt (${episodePlan.durationSec}s): ${episodePlan.videoPrompt}"
                )

                val flowGoal = "Create 1 Google Flow video (${episodePlan.durationSec}s) using this exact prompt: \"${episodePlan.videoPrompt}\" and download the MP4."
                val downloadedFile = executeGoogleFlowSingleVideoRun(
                    context = context,
                    webView = webView,
                    hud = hud,
                    apiKey = apiKey,
                    model = model,
                    promptToCreate = episodePlan.videoPrompt,
                    targetDurationSec = episodePlan.durationSec,
                    folderPath = folderPath,
                    fullGoalDescription = flowGoal,
                    isLiveCalibrationRun = runInLiveCalibrationWindow
                )

                if (downloadedFile != null) {
                    // Save episode to SQLite Story Arc memory & clear one-time custom twist
                    VideoQueueManager.recordStoryEpisode(
                        context = context,
                        episodeNum = episodePlan.episodeNumber,
                        storyMode = episodePlan.storyMode,
                        seriesTheme = flowTopic,
                        episodeTitle = episodePlan.episodeTitle,
                        plotSummary = episodePlan.plotSummary,
                        videoPrompt = episodePlan.videoPrompt,
                        durationSec = episodePlan.durationSec
                    )
                    if (customTwist.isNotBlank()) {
                        VideoQueueManager.setStoryCustomTwist(context, "")
                    }

                    // Mark skill as Live-Verified so future runs execute in Background automatically!
                    if (runInLiveCalibrationWindow) {
                        VideoQueueManager.markSkillLiveVerified(context, "GOOGLE_FLOW", true)
                        hud.collapseToCompactStage()
                        VideoQueueManager.appendLog(
                            context,
                            "✅ First-Run Calibration Verified! Future Google Flow tasks will run in True Background."
                        )
                    }

                    hud.updateStatus("Nax Bot • Saved!", "Uploading Part ${episodePlan.episodeNumber} to YT...", "UPLOADING")
                    VideoQueueManager.appendLog(
                        context,
                        "✅ Part ${episodePlan.episodeNumber} saved (${downloadedFile.name}). Launching YouTube Shorts Auto-Upload..."
                    )
                    delay(1500)

                    AutoTouchService.instance?.startYouTubeUploadJob(
                        context = context,
                        apiKey = apiKey,
                        model = model,
                        folderPath = folderPath,
                        topicInstruction = "${episodePlan.episodeTitle} - ${episodePlan.plotSummary}"
                    )
                } else {
                    hud.updateStatus("Download Timeout", "Tap video card manually to save", "SAD")
                    VideoQueueManager.appendLog(
                        context,
                        "⚠️ Video rendered on Google Flow, but automatic MP4 stream capture timed out. Tap the card in Browser tab to save."
                    )
                }
            } catch (_: CancellationException) {
                hud.updateStatus("Nax Bot", "Stopped", "IDLE")
            } catch (e: Exception) {
                hud.updateStatus("Nax Bot Error", e.message?.take(24) ?: "Error", "ERROR")
                VideoQueueManager.appendLog(context, "❌ Flow Pipeline Error: ${e.message}")
            } finally {
                isWebTaskRunning = false
            }
        }
    }

    /**
     * Universal Web Agent Runner for any custom goal typed in Tab 1 or Tab 2
     */
    fun startAutonomousWebAgent(
        context: Context,
        apiKey: String,
        model: String,
        goal: String,
        startUrl: String,
        folderPath: String
    ) {
        val lowerGoal = goal.lowercase()
        if (lowerGoal.contains("flow") || startUrl.contains("labs.google/fx", true) || startUrl.contains("flow.google", true)) {
            startFlowToYouTubePipeline(
                context = context,
                apiKey = apiKey,
                model = model,
                flowTopic = goal.ifBlank { VideoQueueManager.getFlowTopic(context) },
                folderPath = folderPath,
                dailyVideoLimit = VideoQueueManager.getDailyVideoLimit(context)
            )
            return
        }

        stopCurrentJob()
        val hud = AutoTouchService.instance?.overlayHud
        val webView = hud?.getOrCreateSharedWebView() ?: return

        isWebTaskRunning = true
        currentWebJob = scope.launch {
            try {
                val firstRunLearn = VideoQueueManager.isFirstRunLiveLearnEnabled(context)
                val verified = VideoQueueManager.isSkillLiveVerified(context, "CUSTOM_WEB")
                if (firstRunLearn && !verified) {
                    hud.openForFirstRunLiveCalibration()
                }

                if (startUrl.isNotBlank() && webView.url != startUrl) {
                    webView.loadUrl(startUrl)
                    delay(3500)
                }

                val history = mutableListOf<String>()
                var prevSnap: DomSnapshot? = null
                var prevShotB64: String? = null
                val trainedBlueprint = VideoQueueManager.getTrainedSkillBlueprint(context, "CUSTOM_WEB")

                for (step in 1..32) {
                    if (!isWebTaskRunning) break
                    hud.updateStatus("Web • Step $step", "Thinking...", "THINKING")

                    val snap = WebActionVerifier.captureSnapshot(webView)
                    val curShotB64 = if (VideoQueueManager.isScreenVisionEnabled(context)) {
                        hud.captureWebViewScreenshotBase64()
                    } else null

                    val verifyRes = WebActionVerifier.verifyActionOutcome(
                        before = prevSnap,
                        after = snap,
                        actionTaken = history.lastOrNull() ?: "START",
                        expectedOutcome = "Page update"
                    )

                    val escalateHigh = VideoQueueManager.isAutoHighModelEnabled(context) && verifyRes.consecutiveFailures >= 2
                    val decision = GeminiBrain.decideNextWebAction(
                        apiKey = apiKey,
                        model = model,
                        goal = goal,
                        pageUrl = snap.url,
                        pageTextAndElements = snap.interactiveElementsText,
                        history = history,
                        memoryVault = VideoQueueManager.getMemoryVault(context),
                        learnedLessons = VideoQueueManager.getLearnedLessonsSummary(context, "GOOGLE_FLOW"),
                        trainedBlueprint = trainedBlueprint,
                        screenChangedStatus = verifyRes.verificationReport,
                        screenshotBase64 = curShotB64,
                        previousScreenshotBase64 = prevShotB64,
                        useHighModel = escalateHigh,
                        highModelName = VideoQueueManager.getHighModelName(context),
                        useGoogleSearch = VideoQueueManager.isGoogleSearchGroundingEnabled(context) && escalateHigh
                    )

                    if (decision.learnedRule.isNotBlank()) {
                        VideoQueueManager.recordLearnedLesson(context, decision.learnedRule, "WEB_AGENT")
                    }

                    hud.updateStatus("Web • ${decision.action}", decision.reason, "AUTO")
                    VideoQueueManager.appendLog(context, "🌐 [Step $step] ${decision.action}: ${decision.reason}")

                    when (decision.action) {
                        "DONE" -> {
                            VideoQueueManager.markSkillLiveVerified(context, "CUSTOM_WEB", true)
                            hud.updateStatus("Nax Bot • Done", decision.reason, "HAPPY")
                            break
                        }
                        "CLICK_EL" -> {
                            clickDomElementOrFallbackTouch(webView, hud, decision.elementIndex, decision.tapX, decision.tapY, decision.reason)
                        }
                        "CLICK_XY" -> {
                            WebActionVerifier.dispatchRealWebTouch(webView, hud, decision.tapX, decision.tapY, 540f, 960f, decision.reason)
                        }
                        "TYPE_EL" -> {
                            typeIntoDomElement(webView, decision.elementIndex, decision.textToType)
                        }
                        "SCROLL" -> {
                            webView.evaluateJavascript("window.scrollBy(0, 420);", null)
                        }
                        "LOAD_URL" -> {
                            if (decision.url.isNotBlank()) webView.loadUrl(decision.url)
                        }
                        "DOWNLOAD_VIDEO" -> {
                            WebActionVerifier.attemptOpenPlayAndDownloadVideo(webView, hud, step)
                        }
                        "WAIT" -> {
                            delay(3000)
                        }
                    }

                    history.add("Step $step: ${decision.action} (${decision.reason})")
                    prevSnap = snap
                    prevShotB64 = curShotB64
                    delay(VideoQueueManager.getStepDelayMs(context))
                }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                VideoQueueManager.appendLog(context, "⚠️ Web Agent Error: ${e.message}")
            } finally {
                isWebTaskRunning = false
            }
        }
    }

    /**
     * Strict 1-Video Credit-Lock Execution for Google Flow:
     * Opens project -> Sets Video 8s/10s x1 mode -> Types prompt ONCE -> Waits for 0%..100% -> Extracts/Downloads MP4.
     */
    private suspend fun executeGoogleFlowSingleVideoRun(
        context: Context,
        webView: WebView,
        hud: AiOverlayHud,
        apiKey: String,
        model: String,
        promptToCreate: String,
        targetDurationSec: Int,
        folderPath: String,
        fullGoalDescription: String,
        isLiveCalibrationRun: Boolean
    ): java.io.File? {
        val startTimestampMs = System.currentTimeMillis()
        AiOverlayHud.lastSniffedMp4Url = null
        WebActionVerifier.resetTracker()

        val pinnedUrl = VideoQueueManager.getPinnedFlowUrl(context)
        val curUrl = webView.url ?: ""
        if (!curUrl.contains("labs.google/fx", true) && !curUrl.contains("flow.google", true)) {
            hud.updateStatus("Web • Opening", pinnedUrl, "WORKING")
            webView.loadUrl(pinnedUrl)
            delay(4000)
        }

        var promptAlreadySubmitted = false
        var downloadAttempts = 0

        for (step in 1..42) {
            if (!isWebTaskRunning) return null

            // 1. Check if MP4 file already finished downloading into AutoVideos / Downloads folder!
            val newlySavedFile = VideoQueueManager.findNewlyDownloadedVideo(startTimestampMs, folderPath)
            if (newlySavedFile != null) {
                return newlySavedFile
            }

            // 2. Check if background network interceptor caught a direct MP4 stream URL!
            val sniffedUrl = AiOverlayHud.lastSniffedMp4Url
            if (promptAlreadySubmitted && !sniffedUrl.isNullOrBlank()) {
                AiOverlayHud.lastSniffedMp4Url = null
                hud.enqueueDirectVideoDownload(sniffedUrl)
                delay(4500)
                VideoQueueManager.findNewlyDownloadedVideo(startTimestampMs, folderPath)?.let { return it }
            }

            val snap = WebActionVerifier.captureSnapshot(webView)

            // 3. If Flow is currently rendering (0% .. 99%), strictly WAIT and never re-type!
            if (snap.isFlowRenderingInProgress) {
                promptAlreadySubmitted = true
                hud.updateStatus(
                    "Flow Rendering (${snap.renderingProgressLabel})",
                    "Waiting for ${targetDurationSec}s video to finish...",
                    "RENDERING"
                )
                delay(4500)
                continue
            }

            // 4. If prompt was submitted and rendering reached 100% or completed card is visible -> Open/Play/Download!
            if (promptAlreadySubmitted && (snap.renderingProgressLabel == "100%" || snap.hasCompletedProjectCard || snap.hasActiveVideoStream || downloadAttempts > 0)) {
                downloadAttempts++
                hud.updateStatus("Flow Ready • Saving", "Opening card, playing & saving MP4 (#$downloadAttempts)", "DOWNLOADING")
                val (triggered, msg) = WebActionVerifier.attemptOpenPlayAndDownloadVideo(webView, hud, downloadAttempts)
                VideoQueueManager.appendLog(context, "📥 [Save Attempt #$downloadAttempts] $msg")
                delay(if (triggered) 5000L else 3200L)
                VideoQueueManager.findNewlyDownloadedVideo(startTimestampMs, folderPath)?.let { return it }
                if (downloadAttempts >= 12) break
                continue
            }

            // 5. Before submitting prompt: check if we are on the "+ New project" landing screen
            if (!promptAlreadySubmitted && snap.interactiveElementsText.contains("+ New project", true)) {
                hud.updateStatus("Web • CLICK_EL", "Clicking '+ New project' to start", "WORKING")
                clickNewProjectButton(webView, hud)
                delay(3200)
                continue
            }

            // 6. Ensure Video • 8s/10s • x1 mode is selected (never waste credits on Nano Banana Image / x2)
            if (!promptAlreadySubmitted) {
                WebActionVerifier.ensureFlowVideoMode8s(webView, hud, targetDurationSec)
                delay(800)
            }

            // 7. Submit the prompt ONCE into the bottom composer box ("What do you want to create?")
            if (!promptAlreadySubmitted) {
                hud.updateStatus("Web • TYPE_EL", "Typing ${targetDurationSec}s story prompt...", "WORKING")
                val submitted = typeAndSubmitFlowPromptOnce(webView, hud, promptToCreate)
                if (submitted) {
                    promptAlreadySubmitted = true
                    VideoQueueManager.appendLog(
                        context,
                        "🔒 Credit-Lock Active: 1 Prompt submitted! Waiting for 0% -> 100% rendering..."
                    )
                    delay(4500)
                    continue
                }
            }

            delay(3000)
        }

        return VideoQueueManager.findNewlyDownloadedVideo(startTimestampMs, folderPath)
    }

    private suspend fun clickNewProjectButton(webView: WebView, hud: AiOverlayHud): Boolean =
        suspendCancellableCoroutine { cont ->
            val js = """
                (function() {
                    var vw = window.innerWidth || 540;
                    var vh = window.innerHeight || 960;
                    var btns = Array.from(document.querySelectorAll('button, a, [role="button"], div'));
                    for (var i = 0; i < btns.length; i++) {
                        var b = btns[i];
                        var t = (b.innerText || b.getAttribute('aria-label') || '').trim();
                        if (t.indexOf('New project') !== -1 || t === '+ New project') {
                            var r = b.getBoundingClientRect();
                            if (r.width > 20 && r.height > 20) {
                                b.click();
                                return JSON.stringify({
                                    found: true,
                                    x: Math.round(r.left + r.width / 2),
                                    y: Math.round(r.top + r.height / 2),
                                    vw: vw,
                                    vh: vh
                                });
                            }
                        }
                    }
                    return JSON.stringify({ found: false });
                })();
            """.trimIndent()

            mainHandler.post {
                webView.evaluateJavascript(js) { raw ->
                    try {
                        val clean = raw?.trim()?.removeSurrounding("\"")?.replace("\\\"", "\"") ?: "{}"
                        val obj = JSONObject(clean)
                        if (obj.optBoolean("found", false)) {
                            scope.launch {
                                WebActionVerifier.dispatchRealWebTouch(
                                    webView = webView,
                                    hud = hud,
                                    cssX = obj.optDouble("x", 270.0).toFloat(),
                                    cssY = obj.optDouble("y", 700.0).toFloat(),
                                    viewportCssWidth = obj.optDouble("vw", 540.0).toFloat(),
                                    viewportCssHeight = obj.optDouble("vh", 960.0).toFloat(),
                                    label = "+ New project"
                                )
                            }
                            if (cont.isActive) cont.resume(true)
                        } else {
                            if (cont.isActive) cont.resume(false)
                        }
                    } catch (_: Exception) {
                        if (cont.isActive) cont.resume(false)
                    }
                }
            }
        }

    /**
     * Strictly targets the bottom prompt box (top >= 175px, ignoring top search bar),
     * sets text via native React setter, and clicks the bottom-right arrow_forward button!
     */
    private suspend fun typeAndSubmitFlowPromptOnce(
        webView: WebView,
        hud: AiOverlayHud,
        promptText: String
    ): Boolean = suspendCancellableCoroutine { cont ->
        val escapedPrompt = JSONObject.quote(promptText)
        val js = """
            (function() {
                var vw = window.innerWidth || 540;
                var vh = window.innerHeight || 960;
                var inputs = Array.from(document.querySelectorAll('textarea, [contenteditable="true"], input[type="text"]'));
                var targetBox = null;

                for (var i = 0; i < inputs.length; i++) {
                    var el = inputs[i];
                    var r = el.getBoundingClientRect();
                    var ph = ((el.placeholder || '') + ' ' + (el.getAttribute('aria-label') || '') + ' ' + (el.innerText || '')).toLowerCase();
                    // Strictly skip top search bar (r.top < 175 or 'search' placeholder)
                    if (r.top < 175 || ph.indexOf('search') !== -1) continue;
                    if (r.width > 100 && r.height > 24) {
                        targetBox = el;
                        if (ph.indexOf('what do you want to create') !== -1 || ph.indexOf('prompt') !== -1) break;
                    }
                }

                if (!targetBox) return JSON.stringify({ status: 'NO_BOX' });

                targetBox.focus();
                targetBox.click();
                var textToInsert = $escapedPrompt;

                if (targetBox.tagName.toLowerCase() === 'textarea' || targetBox.tagName.toLowerCase() === 'input') {
                    var proto = targetBox.tagName.toLowerCase() === 'textarea'
                        ? window.HTMLTextAreaElement.prototype
                        : window.HTMLInputElement.prototype;
                    var nativeSetter = Object.getOwnPropertyDescriptor(proto, 'value');
                    if (nativeSetter && nativeSetter.set) {
                        nativeSetter.set.call(targetBox, textToInsert);
                    } else {
                        targetBox.value = textToInsert;
                    }
                } else {
                    targetBox.innerText = textToInsert;
                }

                targetBox.dispatchEvent(new Event('input', { bubbles: true }));
                targetBox.dispatchEvent(new Event('change', { bubbles: true }));

                // Find the submit / arrow_forward button near the bottom composer
                var btns = Array.from(document.querySelectorAll('button, [role="button"]'));
                var submitBtn = null;
                for (var j = 0; j < btns.length; j++) {
                    var b = btns[j];
                    var br = b.getBoundingClientRect();
                    if (br.top < 250) continue;
                    var btxt = ((b.innerText || '') + ' ' + (b.getAttribute('aria-label') || '') + ' ' + (b.title || '')).toLowerCase();
                    if (btxt.indexOf('arrow_forward') !== -1 || btxt.indexOf('generate') !== -1 || btxt.indexOf('create') !== -1 || btxt.indexOf('send') !== -1 || btxt.indexOf('submit') !== -1) {
                        submitBtn = b;
                        break;
                    }
                }

                if (submitBtn) {
                    var sr = submitBtn.getBoundingClientRect();
                    submitBtn.click();
                    return JSON.stringify({
                        status: 'SUBMITTED',
                        x: Math.round(sr.left + sr.width / 2),
                        y: Math.round(sr.top + sr.height / 2),
                        vw: vw,
                        vh: vh
                    });
                }

                return JSON.stringify({
                    status: 'TYPED_ONLY',
                    x: Math.round(vw - 42),
                    y: Math.round(vh - 65),
                    vw: vw,
                    vh: vh
                });
            })();
        """.trimIndent()

        mainHandler.post {
            webView.evaluateJavascript(js) { raw ->
                try {
                    val clean = raw?.trim()?.removeSurrounding("\"")?.replace("\\\"", "\"") ?: "{}"
                    val obj = JSONObject(clean)
                    val status = obj.optString("status", "NO_BOX")
                    if (status == "SUBMITTED" || status == "TYPED_ONLY") {
                        scope.launch {
                            delay(350)
                            WebActionVerifier.dispatchRealWebTouch(
                                webView = webView,
                                hud = hud,
                                cssX = obj.optDouble("x", 495.0).toFloat(),
                                cssY = obj.optDouble("y", 890.0).toFloat(),
                                viewportCssWidth = obj.optDouble("vw", 540.0).toFloat(),
                                viewportCssHeight = obj.optDouble("vh", 960.0).toFloat(),
                                label = "Generate"
                            )
                        }
                        if (cont.isActive) cont.resume(true)
                    } else {
                        if (cont.isActive) cont.resume(false)
                    }
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(false)
                }
            }
        }
    }

    private suspend fun clickDomElementOrFallbackTouch(
        webView: WebView,
        hud: AiOverlayHud,
        elementIndex: Int,
        fallbackX: Float,
        fallbackY: Float,
        label: String
    ) {
        val coordsJson = suspendCancellableCoroutine<String> { cont ->
            val js = """
                (function() {
                    var vw = window.innerWidth || 540;
                    var vh = window.innerHeight || 960;
                    var list = window.__naxElements || [];
                    var el = list[$elementIndex];
                    if (el) {
                        var r = el.getBoundingClientRect();
                        el.click();
                        return JSON.stringify({
                            ok: true,
                            x: Math.round(r.left + r.width / 2),
                            y: Math.round(r.top + r.height / 2),
                            vw: vw,
                            vh: vh
                        });
                    }
                    return JSON.stringify({ ok: false, vw: vw, vh: vh });
                })();
            """.trimIndent()
            mainHandler.post {
                webView.evaluateJavascript(js) { raw ->
                    val clean = raw?.trim()?.removeSurrounding("\"")?.replace("\\\"", "\"") ?: "{}"
                    if (cont.isActive) cont.resume(clean)
                }
            }
        }

        try {
            val obj = JSONObject(coordsJson)
            val vw = obj.optDouble("vw", 540.0).toFloat()
            val vh = obj.optDouble("vh", 960.0).toFloat()
            val targetX = if (obj.optBoolean("ok", false)) obj.optDouble("x", fallbackX.toDouble()).toFloat() else fallbackX
            val targetY = if (obj.optBoolean("ok", false)) obj.optDouble("y", fallbackY.toDouble()).toFloat() else fallbackY

            if (targetX > 0f && targetY > 0f && VideoQueueManager.isRealWebTouchEnabled(webView.context)) {
                WebActionVerifier.dispatchRealWebTouch(webView, hud, targetX, targetY, vw, vh, label.take(18))
            }
        } catch (_: Exception) {}
    }

    private fun typeIntoDomElement(webView: WebView, elementIndex: Int, text: String) {
        val escaped = JSONObject.quote(text)
        val js = """
            (function() {
                var list = window.__naxElements || [];
                var el = list[$elementIndex];
                if (!el) return;
                el.focus();
                el.value = $escaped;
                el.dispatchEvent(new Event('input', { bubbles: true }));
                el.dispatchEvent(new Event('change', { bubbles: true }));
            })();
        """.trimIndent()
        mainHandler.post { webView.evaluateJavascript(js, null) }
    }
}
