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

    fun stopCurrentJob() {
        isWebTaskRunning = false
        currentWebJob?.cancel()
        currentWebJob = null
    }

    fun stopEverything(context: Context? = null) {
        stopCurrentJob()
        context?.let {
            VideoQueueManager.appendLog(it, "🛑 Nax Bot Cognitive State-Machine stopped by user.")
        }
    }

    /**
     * Master Cognitive State-Machine Pipeline:
     * 1. Generates Episodic Story Prompt (Part 1, 2, 3 / Side-Story / Custom Duration)
     * 2. Uses First-Run Phone Calibration on run #1, then switches to 1x1 Pixel Invisible Stealth Mode!
     * 3. Executes Dynamic State-Machine (auto-kills popups, uses DOM-Less Vision XY fallback, 4-Sense MP4 capture)
     * 4. Extracts 2 video frames with Vision AI to write a viral YouTube Shorts title & triggers upload!
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

                val firstRunLearnEnabled = VideoQueueManager.isFirstRunLiveLearnEnabled(context)
                val isAlreadyVerified = VideoQueueManager.isSkillLiveVerified(context, "GOOGLE_FLOW")
                val runInLiveCalibrationWindow = firstRunLearnEnabled && !isAlreadyVerified

                if (runInLiveCalibrationWindow) {
                    hud.openForFirstRunLiveCalibration()
                    VideoQueueManager.appendDetailedLog(
                        context = context,
                        modelUsed = model,
                        clickSense = "1st-Run Phone Calibration",
                        actionDesc = "Opened Live Calibration Window",
                        whyReason = "Learning live visual coordinates & verifying workflow on phone screen",
                        verifyStatus = "ACTIVE"
                    )
                } else {
                    // Idea 4 Upgrade: Activate 1x1 Pixel Invisible Foreground Canvas so Android OS never freezes WebView!
                    hud.enableInvisible1x1StealthMode()
                    VideoQueueManager.appendDetailedLog(
                        context = context,
                        modelUsed = model,
                        clickSense = "1x1 Pixel Stealth Mode",
                        actionDesc = "Active in 1x1 Invisible Foreground Canvas",
                        whyReason = "Prevents Android OS from throttling WebView JS & rendering timers",
                        verifyStatus = "STEALTH ON"
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
                    actionDesc = "Part ${episodePlan.episodeNumber}: ${episodePlan.episodeTitle}",
                    whyReason = episodePlan.plotSummary,
                    verifyStatus = "${episodePlan.durationSec}s Prompt Ready"
                )

                val downloadedFile = executeGoogleFlowStateMachine(
                    context = context,
                    webView = webView,
                    hud = hud,
                    apiKey = apiKey,
                    model = model,
                    promptToCreate = episodePlan.videoPrompt,
                    targetDurationSec = episodePlan.durationSec,
                    folderPath = folderPath
                )

                // Restore compact Nax Bot badge after 1x1 stealth or live window run
                hud.collapseToCompactStage()

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
                        VideoQueueManager.appendLog(
                            context,
                            "✅ 1st-Run Calibration Verified! Future runs will use 1x1 Pixel Invisible Stealth Mode."
                        )
                    }

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
                                whyReason = "Inspected actual MP4 video frames for viral CTR",
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
                hud.collapseToCompactStage()
                hud.updateStatus("Nax Bot", "Stopped", "IDLE")
            } catch (e: Exception) {
                hud.collapseToCompactStage()
                hud.updateStatus("Nax Bot Error", e.message?.take(24) ?: "Error", "ERROR")
                VideoQueueManager.appendLog(context, "❌ State-Machine Pipeline Error: ${e.message}")
            } finally {
                isWebTaskRunning = false
            }
        }
    }

    /**
     * Universal Cognitive State-Machine Web Agent with Vector RAG Memory & DOM-Less Pure Vision
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
                } else {
                    hud.enableInvisible1x1StealthMode()
                }

                if (startUrl.isNotBlank() && webView.url != startUrl) {
                    webView.loadUrl(startUrl)
                    delay(3000)
                }

                val history = mutableListOf<String>()
                var prevShotB64: String? = null
                var currentSense = 1
                var lastVerifyReport = "State-Machine initialized."
                val trainedBlueprint = VideoQueueManager.getTrainedSkillBlueprint(context, "CUSTOM_WEB")

                for (cycle in 1..40) {
                    if (!isWebTaskRunning) break

                    val snap = WebActionVerifier.captureSnapshot(webView)

                    // Idea 2: Self-Healing Popup Auto-Killer before evaluating state!
                    if (snap.hasBlockingPopup) {
                        val (killed, msg) = WebActionVerifier.detectAndDismissPopups(webView, hud)
                        if (killed) {
                            VideoQueueManager.appendDetailedLog(
                                context = context,
                                modelUsed = "State-Machine",
                                clickSense = "Popup Auto-Killer",
                                actionDesc = msg,
                                whyReason = "Cleared blocking overlay automatically",
                                verifyStatus = "✅ SELF-HEALED"
                            )
                            delay(1000)
                            continue
                        }
                    }

                    val curShotB64 = if (VideoQueueManager.isScreenVisionEnabled(context)) {
                        hud.captureWebViewScreenshotBase64()
                    } else null

                    // Idea 3: Retrieve Vector RAG Memories matching current screen & obstacle state!
                    val ragQuery = "$goal ${snap.url} ${snap.detectedFlowState} $lastVerifyReport"
                    val ragMemories = VideoQueueManager.retrieveRelevantRagMemories(context, ragQuery, "WEB_AGENT")

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
                        learnedLessons = ragMemories,
                        trainedBlueprint = trainedBlueprint,
                        screenChangedStatus = lastVerifyReport,
                        screenshotBase64 = curShotB64,
                        previousScreenshotBase64 = prevShotB64,
                        useHighModel = escalateHigh,
                        highModelName = VideoQueueManager.getHighModelName(context),
                        useGoogleSearch = VideoQueueManager.isGoogleSearchGroundingEnabled(context) && escalateHigh
                    )

                    if (decision.learnedRule.isNotBlank()) {
                        VideoQueueManager.recordLearnedLesson(context, decision.learnedRule, "WEB_AGENT")
                    }

                    var senseUsed = WebActionVerifier.getSenseName(currentSense)
                    when (decision.action) {
                        "DONE" -> {
                            VideoQueueManager.markSkillLiveVerified(context, "CUSTOM_WEB", true)
                            hud.collapseToCompactStage()
                            hud.updateStatus("Nax Bot • Done", decision.reason, "HAPPY")
                            break
                        }
                        "DISMISS_POPUP" -> {
                            val (killed, _) = WebActionVerifier.detectAndDismissPopups(webView, hud)
                            if (!killed && decision.tapX > 0f && decision.tapY > 0f) {
                                WebActionVerifier.dispatchRealWebTouch(webView, hud, decision.tapX, decision.tapY, label = "Dismiss")
                            }
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
                            senseUsed = "Sense-3: Pure Vision XY (${decision.tapX.toInt()},${decision.tapY.toInt()})"
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
                            WebActionVerifier.attemptOpenPlayAndDownloadVideo(webView, hud, cycle)
                        }
                        "WAIT" -> {
                            delay(1400)
                        }
                    }

                    val (_, verifyRes) = WebActionVerifier.confirmStepInOneSecond(
                        webView = webView,
                        before = snap,
                        actionTaken = "${decision.currentState}:${decision.action}",
                        expectedOutcome = decision.expectedOutcome.ifBlank { decision.reason },
                        waitMs = VideoQueueManager.getStepDelayMs(context)
                    )

                    currentSense = verifyRes.recommendedSense
                    lastVerifyReport = verifyRes.verificationReport

                    VideoQueueManager.appendDetailedLog(
                        context = context,
                        modelUsed = activeModelLabel,
                        clickSense = senseUsed,
                        actionDesc = "[${decision.currentState}] ${decision.action}",
                        whyReason = decision.reason,
                        verifyStatus = if (verifyRes.passed) "✅ 1s CONFIRMED" else "⚠️ RETRY SENSE-$currentSense"
                    )

                    history.add("Cycle $cycle [${decision.currentState}]: ${decision.action} -> ${if (verifyRes.passed) "CONFIRMED" else "RETRY"}")
                    prevShotB64 = curShotB64
                }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                VideoQueueManager.appendLog(context, "⚠️ Web Agent Error: ${e.message}")
            } finally {
                hud.collapseToCompactStage()
                isWebTaskRunning = false
            }
        }
    }

    /**
     * Idea 1, 2, 3, 4 & 5 Combined:
     * Goal-Oriented Dynamic State-Machine for Google Flow!
     * Evaluates the live FlowState every second, auto-heals popups, uses DOM-Less Vision XY fallback if HTML changes,
     * and never types the prompt twice.
     */
    private suspend fun executeGoogleFlowStateMachine(
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
            webView.loadUrl(pinnedUrl)
            delay(3500)
        }

        var promptAlreadySubmitted = false
        var downloadAttempts = 0
        var senseLevel = 1

        for (cycle in 1..52) {
            if (!isWebTaskRunning) return null

            // Goal Check 1: Is the MP4 file already saved in the user's selected folder?
            VideoQueueManager.findNewlyDownloadedVideo(startTimestampMs, folderPath)?.let { return it }

            // Goal Check 2: Did Sense-1 Background Network Interceptor catch the direct .mp4 URL?
            val sniffedUrl = AiOverlayHud.lastSniffedMp4Url
            if (promptAlreadySubmitted && !sniffedUrl.isNullOrBlank()) {
                AiOverlayHud.lastSniffedMp4Url = null
                hud.enqueueDirectVideoDownload(sniffedUrl)
                delay(4200)
                VideoQueueManager.findNewlyDownloadedVideo(startTimestampMs, folderPath)?.let { return it }
            }

            val snap = WebActionVerifier.captureSnapshot(webView)

            // Dynamic State-Machine Router
            when (snap.detectedFlowState) {
                FlowState.STATE_POPUP_OBSTACLE -> {
                    val (killed, msg) = WebActionVerifier.detectAndDismissPopups(webView, hud)
                    VideoQueueManager.appendDetailedLog(
                        context = context,
                        modelUsed = "State-Machine",
                        clickSense = "Popup Auto-Killer",
                        actionDesc = msg,
                        whyReason = "Obstacle detected in STATE_POPUP_OBSTACLE",
                        verifyStatus = if (killed) "✅ CLEARED" else "⏳ RETRY"
                    )
                    delay(1100)
                }

                FlowState.STATE_RENDERING -> {
                    promptAlreadySubmitted = true
                    delay(3600)
                }

                FlowState.STATE_VIDEO_COMPLETED -> {
                    if (promptAlreadySubmitted) {
                        downloadAttempts++
                        val (triggered, senseMsg) = WebActionVerifier.attemptOpenPlayAndDownloadVideo(webView, hud, downloadAttempts)
                        VideoQueueManager.appendDetailedLog(
                            context = context,
                            modelUsed = model,
                            clickSense = "State-5 Save #$downloadAttempts",
                            actionDesc = senseMsg,
                            whyReason = "STATE_VIDEO_COMPLETED reached",
                            verifyStatus = if (triggered) "✅ STREAM CAPTURED" else "⏳ 1s VERIFYING"
                        )
                        delay(if (triggered) 4500L else 2600L)
                        VideoQueueManager.findNewlyDownloadedVideo(startTimestampMs, folderPath)?.let { return it }
                        if (downloadAttempts >= 14) break
                    } else {
                        // Existing project has old cards; we still need to submit our new episode prompt!
                        val submitted = submitPromptInComposerState(context, webView, hud, model, snap, promptToCreate, targetDurationSec)
                        if (submitted) promptAlreadySubmitted = true
                    }
                }

                FlowState.STATE_LANDING -> {
                    if (!promptAlreadySubmitted) {
                        clickNewProjectWithVisionFallback(webView, hud, senseLevel)
                        val (_, confirmRes) = WebActionVerifier.confirmStepInOneSecond(
                            webView = webView,
                            before = snap,
                            actionTaken = "STATE_LANDING_NEW_PROJECT",
                            expectedOutcome = "Transition to STATE_COMPOSER_READY",
                            waitMs = 1500L
                        )
                        senseLevel = confirmRes.recommendedSense
                        VideoQueueManager.appendDetailedLog(
                            context = context,
                            modelUsed = model,
                            clickSense = WebActionVerifier.getSenseName(senseLevel),
                            actionDesc = "Tapped '+ New project'",
                            whyReason = "STATE_LANDING -> Opening workspace",
                            verifyStatus = if (confirmRes.passed) "✅ 1s CONFIRMED" else "⚠️ SENSE-$senseLevel"
                        )
                    }
                }

                FlowState.STATE_MODE_MISMATCH -> {
                    if (!promptAlreadySubmitted) {
                        WebActionVerifier.ensureFlowVideoMode8s(webView, hud, targetDurationSec)
                        delay(1100)
                    }
                }

                FlowState.STATE_COMPOSER_READY, FlowState.STATE_ANALYZING -> {
                    if (!promptAlreadySubmitted) {
                        val submitted = submitPromptInComposerState(context, webView, hud, model, snap, promptToCreate, targetDurationSec)
                        if (submitted) {
                            promptAlreadySubmitted = true
                            delay(3000)
                        } else {
                            // If neither landing button nor composer was found via DOM, use DOM-Less Vision Fallback!
                            val visualNewProj = WebActionVerifier.findVisualTargetCoordinates(
                                webView,
                                listOf("new project", "+ new project", "create")
                            )
                            if (visualNewProj != null) {
                                WebActionVerifier.dispatchRealWebTouch(
                                    webView = webView,
                                    hud = hud,
                                    cssX = visualNewProj.first,
                                    cssY = visualNewProj.second,
                                    label = "Vision: New Project"
                                )
                            }
                            delay(1800)
                        }
                    } else {
                        downloadAttempts++
                        WebActionVerifier.attemptOpenPlayAndDownloadVideo(webView, hud, downloadAttempts)
                        delay(2800)
                    }
                }
            }
        }

        return VideoQueueManager.findNewlyDownloadedVideo(startTimestampMs, folderPath)
    }

    private suspend fun submitPromptInComposerState(
        context: Context,
        webView: WebView,
        hud: AiOverlayHud,
        model: String,
        beforeSnap: DomSnapshot,
        promptToCreate: String,
        targetDurationSec: Int
    ): Boolean {
        WebActionVerifier.ensureFlowVideoMode8s(webView, hud, targetDurationSec)
        delay(600)
        val typed = typeAndSubmitFlowPromptOnce(webView, hud, promptToCreate)
        if (!typed) return false

        val (afterSnap, confirmRes) = WebActionVerifier.confirmStepInOneSecond(
            webView = webView,
            before = beforeSnap,
            actionTaken = "STATE_COMPOSER_SUBMIT",
            expectedOutcome = "Transition to STATE_RENDERING (0%..99%)",
            waitMs = 1800L
        )
        val verified = confirmRes.passed || afterSnap.isFlowRenderingInProgress
        VideoQueueManager.appendDetailedLog(
            context = context,
            modelUsed = model,
            clickSense = "Stealth React Setter + Human Tap",
            actionDesc = "Submitted ${targetDurationSec}s Story Prompt",
            whyReason = "STATE_COMPOSER_READY -> Credit-Lock Active",
            verifyStatus = if (verified) "✅ 1s CONFIRMED LOCK" else "⏳ CHECKING RENDER"
        )
        return true
    }

    private suspend fun clickNewProjectWithVisionFallback(
        webView: WebView,
        hud: AiOverlayHud,
        senseLevel: Int
    ): Boolean {
        val visualCoords = WebActionVerifier.findVisualTargetCoordinates(
            webView = webView,
            targetKeywords = listOf("+ new project", "new project")
        )

        return suspendCancellableCoroutine { cont ->
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
                    return JSON.stringify({ found: false, vw: vw, vh: vh });
                })();
            """.trimIndent()

            mainHandler.post {
                webView.evaluateJavascript(js) { raw ->
                    try {
                        val clean = raw?.trim()?.removeSurrounding("\"")?.replace("\\\"", "\"") ?: "{}"
                        val obj = JSONObject(clean)
                        val vw = obj.optDouble("vw", 540.0).toFloat()
                        val vh = obj.optDouble("vh", 960.0).toFloat()
                        val targetX = if (obj.optBoolean("found", false)) {
                            obj.optDouble("x", 270.0).toFloat()
                        } else {
                            visualCoords?.first ?: 270f
                        }
                        val targetY = if (obj.optBoolean("found", false)) {
                            obj.optDouble("y", 700.0).toFloat()
                        } else {
                            visualCoords?.second ?: 700f
                        }
                        val offsetX = if (senseLevel >= 3) 12f else 0f

                        scope.launch {
                            WebActionVerifier.dispatchRealWebTouch(
                                webView = webView,
                                hud = hud,
                                cssX = targetX + offsetX,
                                cssY = targetY,
                                viewportCssWidth = vw,
                                viewportCssHeight = vh,
                                label = "+ New project"
                            )
                        }
                        if (cont.isActive) cont.resume(true)
                    } catch (_: Exception) {
                        if (cont.isActive) cont.resume(false)
                    }
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
