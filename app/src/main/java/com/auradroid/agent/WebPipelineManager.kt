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
import kotlinx.coroutines.withContext
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
     * always compile and stop cleanly without any missing method error!
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
     * Upload an already-downloaded local video through the existing web-agent workflow.
     * If requested and no pending video exists, create one using the established Flow pipeline.
     */
    fun runYouTubeAutoUpload(
        context: Context,
        apiKey: String,
        model: String,
        folderPath: String,
        userPrompt: String,
        createAiVideoIfEmpty: Boolean = false
    ) {
        val pendingVideo = VideoQueueManager.getNextPendingVideo(context, folderPath)
        if (pendingVideo == null) {
            if (createAiVideoIfEmpty) {
                startFlowToYouTubePipeline(
                    context = context,
                    apiKey = apiKey,
                    model = model,
                    flowTopic = userPrompt.ifBlank { VideoQueueManager.getFlowTopic(context) },
                    folderPath = folderPath,
                    dailyVideoLimit = 1
                )
            } else {
                val message = "No pending video found in the selected folder: $folderPath"
                AutoTouchService.instance?.overlayHud?.updateStatus("YouTube Upload", message, "ERROR")
                VideoQueueManager.appendLog(context, "⚠️ $message")
            }
            return
        }

        val uploadGoal = """
            Upload the existing local video file "${pendingVideo.name}" from the user's selected folder
            to YouTube Studio as a YouTube Short. Open the upload flow, choose this exact video file,
            and use the following title/description instructions when the relevant fields are available:
            $userPrompt

            Verify the final YouTube Studio confirmation before reporting completion. If the file picker
            cannot access the selected file or any step fails, explain the blocker; do not claim success.
        """.trimIndent()

        startAutonomousWebAgent(
            context = context,
            apiKey = apiKey,
            model = model,
            goal = uploadGoal,
            startUrl = "https://studio.youtube.com/",
            folderPath = folderPath,
            skipFlowRouting = true
        )
    }

    /**
     * Master Customizable Story Series (Part 1, Part 2, Part 3 / Side-Story / Standalone) +
     * 1-Second Live AI Step Confirmation + First-Run Phone Calibration +
     * Viral Video Frame Vision SEO -> YouTube Shorts Pipeline!
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
                val oneSecConfirm = VideoQueueManager.isOneSecAiConfirmEnabled(context)

                // Check First-Run Phone/Live Calibration vs Verified Background Mode
                val firstRunLearnEnabled = VideoQueueManager.isFirstRunLiveLearnEnabled(context)
                val isAlreadyVerified = VideoQueueManager.isSkillLiveVerified(context, "GOOGLE_FLOW")
                val runInLiveCalibrationWindow = firstRunLearnEnabled && !isAlreadyVerified

                if (runInLiveCalibrationWindow) {
                    hud.openForFirstRunLiveCalibration()
                    VideoQueueManager.appendDetailedLog(
                        context = context,
                        modelUsed = model,
                        clickSense = "1st-Run Phone Learn",
                        actionDesc = "Opened Live Calibration Window",
                        whyReason = "First time running GOOGLE_FLOW: learning live screenshots & touch coordinates",
                        verifyStatus = "ACTIVE"
                    )
                } else {
                    VideoQueueManager.appendDetailedLog(
                        context = context,
                        modelUsed = model,
                        clickSense = "Verified Background",
                        actionDesc = "Running in True Background Canvas",
                        whyReason = "Task already learned & verified; keeping user phone screen free",
                        verifyStatus = "1s AI Confirm ON=$oneSecConfirm"
                    )
                }

                hud.updateStatus("Nax Bot • Part $nextEpNum", "Writing ${targetDurationSec}s $storyMode story...", "THINKING")

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

                VideoQueueManager.appendDetailedLog(
                    context = context,
                    modelUsed = model,
                    clickSense = "Showrunner AI",
                    actionDesc = "Created Part ${episodePlan.episodeNumber}: ${episodePlan.episodeTitle}",
                    whyReason = episodePlan.plotSummary,
                    verifyStatus = "${episodePlan.durationSec}s Prompt Locked"
                )

                val downloadedFile = executeGoogleFlowWithOneSecAiConfirm(
                    context = context,
                    webView = webView,
                    hud = hud,
                    apiKey = apiKey,
                    model = model,
                    promptToCreate = episodePlan.videoPrompt,
                    targetDurationSec = episodePlan.durationSec,
                    folderPath = folderPath
                )

                if (downloadedFile != null) {
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

                    if (runInLiveCalibrationWindow) {
                        VideoQueueManager.markSkillLiveVerified(context, "GOOGLE_FLOW", true)
                        hud.collapseToCompactStage()
                        VideoQueueManager.appendLog(
                            context,
                            "✅ 1st-Run Calibration Verified! Future Google Flow runs will execute in Background."
                        )
                    }

                    // Viral Frame Vision SEO: Extract 2 actual photos from the MP4 so AI sees the video!
                    var richTopicInstruction = "Part ${episodePlan.episodeNumber}: ${episodePlan.episodeTitle} - ${episodePlan.plotSummary}"
                    if (VideoQueueManager.isVideoFrameVisionSeoEnabled(context)) {
                        hud.updateStatus("Vision SEO • AI", "Inspecting 2 video frames for viral title...", "SEARCHING")
                        val framesB64 = withContext(Dispatchers.IO) {
                            VideoQueueManager.extractVideoFramesBase64(downloadedFile)
                        }
                        if (framesB64.isNotEmpty()) {
                            val viralMeta = GeminiBrain.generateYouTubeMetadataWithVision(
                                apiKey = apiKey,
                                model = model,
                                videoFramesBase64 = framesB64,
                                fileName = downloadedFile.name,
                                userInstruction = flowTopic,
                                episodeNum = episodePlan.episodeNumber,
                                episodeTitle = episodePlan.episodeTitle,
                                storySummary = episodePlan.plotSummary
                            )
                            richTopicInstruction = "${viralMeta.title}\n${viralMeta.description}"
                            VideoQueueManager.appendDetailedLog(
                                context = context,
                                modelUsed = VideoQueueManager.getHighModelName(context),
                                clickSense = "Frame Vision SEO (${framesB64.size} frames)",
                                actionDesc = "Viral Title: ${viralMeta.title}",
                                whyReason = "Analyzed actual video frames for high CTR",
                                verifyStatus = "READY FOR YT"
                            )
                        }
                    }

                    hud.updateStatus("Nax Bot • Saved!", "Uploading Part ${episodePlan.episodeNumber} to YT...", "UPLOADING")
                    delay(1200)

                    AutoTouchService.instance?.startYouTubeUploadJob(
                        context = context,
                        apiKey = apiKey,
                        model = model,
                        folderPath = folderPath,
                        topicInstruction = richTopicInstruction
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
     * Universal Web Agent Runner with 1-Second Live AI Step Confirmation & 4-Sense Clicking
     */
    fun startAutonomousWebAgent(
        context: Context,
        apiKey: String,
        model: String,
        goal: String,
        startUrl: String,
        folderPath: String,
        skipFlowRouting: Boolean = false
    ) {
        val lowerGoal = goal.lowercase()
        if (!skipFlowRouting && (lowerGoal.contains("flow") || startUrl.contains("labs.google/fx", true) || startUrl.contains("flow.google", true))) {
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
                    delay(3000)
                }

                val history = mutableListOf<String>()
                var prevSnap: DomSnapshot? = null
                var prevShotB64: String? = null
                var currentSense = 1
                var lastVerifyReport = "Initial state ready."
                val trainedBlueprint = VideoQueueManager.getTrainedSkillBlueprint(context, "CUSTOM_WEB")

                for (step in 1..36) {
                    if (!isWebTaskRunning) break
                    hud.updateStatus("Web • Step $step", "1s AI Confirming...", "THINKING")

                    val snap = WebActionVerifier.captureSnapshot(webView)
                    val curShotB64 = if (VideoQueueManager.isScreenVisionEnabled(context)) {
                        hud.captureWebViewScreenshotBase64()
                    } else null

                    val escalateHigh = VideoQueueManager.isAutoHighModelEnabled(context) && currentSense >= 3
                    val activeModelLabel = if (escalateHigh) VideoQueueManager.getHighModelName(context) else model

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
                        screenChangedStatus = lastVerifyReport,
                        screenshotBase64 = curShotB64,
                        previousScreenshotBase64 = prevShotB64,
                        useHighModel = escalateHigh,
                        highModelName = VideoQueueManager.getHighModelName(context),
                        useGoogleSearch = VideoQueueManager.isGoogleSearchGroundingEnabled(context) && escalateHigh
                    )

                    hud.updateStatus("Web • ${decision.action}", decision.reason, "AUTO")

                    var senseUsed = WebActionVerifier.getSenseName(currentSense)
                    when (decision.action) {
                        "DONE" -> {
                            VideoQueueManager.markSkillLiveVerified(context, "CUSTOM_WEB", true)
                            hud.updateStatus("Nax Bot • Done", decision.reason, "HAPPY")
                            break
                        }
                        "CLICK_EL" -> {
                            senseUsed = WebActionVerifier.executeMultiSenseClick(
                                webView = webView,
                                hud = hud,
                                elementIndex = decision.elementIndex,
                                fallbackCssX = decision.tapX,
                                fallbackCssY = decision.tapY,
                                senseLevel = currentSense,
                                label = decision.reason
                            )
                        }
                        "CLICK_XY" -> {
                            WebActionVerifier.dispatchRealWebTouch(
                                webView = webView,
                                hud = hud,
                                cssX = decision.tapX,
                                cssY = decision.tapY,
                                label = decision.reason
                            )
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
                            delay(1500)
                        }
                    }

                    // 1-Second Live AI Confirmation Check after every step!
                    val (afterSnap, verifyRes) = WebActionVerifier.confirmStepInOneSecond(
                        webView = webView,
                        before = snap,
                        actionTaken = decision.action,
                        expectedOutcome = decision.reason,
                        waitMs = VideoQueueManager.getStepDelayMs(context)
                    )

                    currentSense = verifyRes.recommendedSense
                    lastVerifyReport = verifyRes.verificationReport

                    VideoQueueManager.appendDetailedLog(
                        context = context,
                        modelUsed = activeModelLabel,
                        clickSense = senseUsed,
                        actionDesc = "${decision.action} (#${decision.elementIndex})",
                        whyReason = decision.reason,
                        verifyStatus = if (verifyRes.passed) "✅ 1s CONFIRMED" else "⚠️ RETRY SENSE-$currentSense"
                    )

                    history.add("Step $step: ${decision.action} -> ${if (verifyRes.passed) "CONFIRMED" else "FAILED"}")
                    prevSnap = afterSnap
                    prevShotB64 = curShotB64
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
     * Google Flow Execution Engine guided by the Pre-Trained Master JSON Blueprint,
     * where AI confirms EVERY single step after 1 second and never skips ahead blindly!
     */
    private suspend fun executeGoogleFlowWithOneSecAiConfirm(
        context: Context,
        webView: WebView,
        hud: AiOverlayHud,
        apiKey: String,
        model: String,
        promptToCreate: String,
        targetDurationSec: Int,
        folderPath: String
    ): java.io.File? {
        val startTimestampMs = System.currentTimeMillis()
        AiOverlayHud.lastSniffedMp4Url = null
        WebActionVerifier.resetTracker()

        val pinnedUrl = VideoQueueManager.getPinnedFlowUrl(context)
        val curUrl = webView.url ?: ""
        if (!curUrl.contains("labs.google/fx", true) && !curUrl.contains("flow.google", true)) {
            hud.updateStatus("Web • Opening", pinnedUrl, "WORKING")
            webView.loadUrl(pinnedUrl)
            delay(3500)
        }

        var promptAlreadySubmitted = false
        var downloadAttempts = 0
        var newProjectSenseLevel = 1

        for (step in 1..48) {
            if (!isWebTaskRunning) return null

            // Check 1: Did MP4 file finish saving in user's selected storage folder?
            VideoQueueManager.findNewlyDownloadedVideo(startTimestampMs, folderPath)?.let { return it }

            // Check 2: Did Sense-1 Background Network Sniffer catch the MP4 stream?
            val sniffedUrl = AiOverlayHud.lastSniffedMp4Url
            if (promptAlreadySubmitted && !sniffedUrl.isNullOrBlank()) {
                AiOverlayHud.lastSniffedMp4Url = null
                hud.enqueueDirectVideoDownload(sniffedUrl)
                delay(4000)
                VideoQueueManager.findNewlyDownloadedVideo(startTimestampMs, folderPath)?.let { return it }
            }

            val beforeSnap = WebActionVerifier.captureSnapshot(webView)

            // Step 4 Confirmation: While Google Flow is rendering (0% .. 99%), confirm progress every 1-3s and wait
            if (beforeSnap.isFlowRenderingInProgress) {
                promptAlreadySubmitted = true
                hud.updateStatus(
                    "Rendering (${beforeSnap.renderingProgressLabel})",
                    "1s AI Confirm: Waiting for 100%...",
                    "RENDERING"
                )
                delay(3800)
                continue
            }

            // Step 5 Confirmation: Rendering reached 100% or completed card is visible -> 4-Sense Open/Play/Download
            if (promptAlreadySubmitted && (beforeSnap.renderingProgressLabel == "100%" || beforeSnap.hasCompletedProjectCard || beforeSnap.hasActiveVideoStream || downloadAttempts > 0)) {
                downloadAttempts++
                hud.updateStatus("100% Ready • Saving", "4-Sense MP4 Capture (#$downloadAttempts)", "DOWNLOADING")
                val (triggered, senseMsg) = WebActionVerifier.attemptOpenPlayAndDownloadVideo(webView, hud, downloadAttempts)
                VideoQueueManager.appendDetailedLog(
                    context = context,
                    modelUsed = model,
                    clickSense = "4-Sense Save #$downloadAttempts",
                    actionDesc = senseMsg,
                    whyReason = "100% completed video card detected",
                    verifyStatus = if (triggered) "✅ STREAM CAPTURED" else "⏳ 1s VERIFYING PLAYER"
                )
                delay(if (triggered) 4500L else 2600L)
                VideoQueueManager.findNewlyDownloadedVideo(startTimestampMs, folderPath)?.let { return it }
                if (downloadAttempts >= 14) break
                continue
            }

            // Step 1 Confirmation: Landing screen "+ New project" button -> Confirm after 1s that composer opened!
            if (!promptAlreadySubmitted && beforeSnap.interactiveElementsText.contains("New project", true)) {
                hud.updateStatus("Step 1 • New Project", "Clicking + New project (${WebActionVerifier.getSenseName(newProjectSenseLevel)})", "WORKING")
                clickNewProjectButtonWithSense(webView, hud, newProjectSenseLevel)

                val (_, confirmRes) = WebActionVerifier.confirmStepInOneSecond(
                    webView = webView,
                    before = beforeSnap,
                    actionTaken = "CLICK_NEW_PROJECT",
                    expectedOutcome = "Composer box 'What do you want to create?' should open",
                    waitMs = 1500L
                )
                newProjectSenseLevel = confirmRes.recommendedSense
                VideoQueueManager.appendDetailedLog(
                    context = context,
                    modelUsed = model,
                    clickSense = WebActionVerifier.getSenseName(newProjectSenseLevel),
                    actionDesc = "Clicked '+ New project'",
                    whyReason = "Blueprint Step 1: Open workspace",
                    verifyStatus = if (confirmRes.passed) "✅ 1s CONFIRMED" else "⚠️ RETRY WITH SENSE-$newProjectSenseLevel"
                )
                continue
            }

            // Step 2 Confirmation: Ensure Video • 8s/10s • x1 mode is locked (never Nano Banana Image / x2)
            if (!promptAlreadySubmitted) {
                val modeStatus = WebActionVerifier.ensureFlowVideoMode8s(webView, hud, targetDurationSec)
                if (modeStatus.contains("OPENED_MODE_PILL") || modeStatus.contains("SWITCHED_OPTIONS")) {
                    delay(1100)
                }
            }

            // Step 3 Confirmation: Type prompt into bottom composer and confirm after 1s that rendering started!
            if (!promptAlreadySubmitted) {
                hud.updateStatus("Step 3 • Prompt", "Typing ${targetDurationSec}s prompt & 1s confirming...", "WORKING")
                val submitted = typeAndSubmitFlowPromptOnce(webView, hud, promptToCreate)
                if (submitted) {
                    val (afterSubmitSnap, confirmRes) = WebActionVerifier.confirmStepInOneSecond(
                        webView = webView,
                        before = beforeSnap,
                        actionTaken = "SUBMIT_FLOW_PROMPT",
                        expectedOutcome = "Rendering card (0%..100%) appears",
                        waitMs = 1800L
                    )
                    if (confirmRes.passed || afterSubmitSnap.isFlowRenderingInProgress) {
                        promptAlreadySubmitted = true
                        VideoQueueManager.appendDetailedLog(
                            context = context,
                            modelUsed = model,
                            clickSense = "React Setter + 3x Submit Tap",
                            actionDesc = "Prompt Submitted & Locked",
                            whyReason = "Blueprint Step 3 confirmed in 1s; preventing duplicate typing",
                            verifyStatus = "✅ 1s CONFIRMED LOCK"
                        )
                        delay(3000)
                        continue
                    }
                }
            }

            delay(1800)
        }

        return VideoQueueManager.findNewlyDownloadedVideo(startTimestampMs, folderPath)
    }

    private suspend fun clickNewProjectButtonWithSense(
        webView: WebView,
        hud: AiOverlayHud,
        senseLevel: Int
    ): Boolean = suspendCancellableCoroutine { cont ->
        val js = """
            (function() {
                var vw = window.innerWidth ? window.innerWidth : 540;
                var vh = window.innerHeight ? window.innerHeight : 960;
                var btns = Array.from(document.querySelectorAll('button, a, [role="button"], div'));
                for (var i = 0; i < btns.length; i++) {
                    var b = btns[i];
                    var t = (b.innerText ? b.innerText : (b.getAttribute('aria-label') ? b.getAttribute('aria-label') : '')).trim();
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
                        val offsetX = if (senseLevel >= 3) 12f else 0f
                        scope.launch {
                            WebActionVerifier.dispatchRealWebTouch(
                                webView = webView,
                                hud = hud,
                                cssX = obj.optDouble("x", 270.0).toFloat() + offsetX,
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

    private suspend fun typeAndSubmitFlowPromptOnce(
        webView: WebView,
        hud: AiOverlayHud,
        promptText: String
    ): Boolean = suspendCancellableCoroutine { cont ->
        val escapedPrompt = JSONObject.quote(promptText)
        val js = """
            (function() {
                var vw = window.innerWidth ? window.innerWidth : 540;
                var vh = window.innerHeight ? window.innerHeight : 960;
                var inputs = Array.from(document.querySelectorAll('textarea, [contenteditable="true"], input[type="text"]'));
                var targetBox = null;

                for (var i = 0; i < inputs.length; i++) {
                    var el = inputs[i];
                    var r = el.getBoundingClientRect();
                    var ph = ((el.placeholder || '') + ' ' + (el.getAttribute('aria-label') || '') + ' ' + (el.innerText || '')).toLowerCase();
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

    private fun typeIntoDomElement(webView: WebView, elementIndex: Int, text: String) {
        val escaped = JSONObject.quote(text)
        val js = """
            (function() {
                var list = window.__naxElements ? window.__naxElements : [];
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
