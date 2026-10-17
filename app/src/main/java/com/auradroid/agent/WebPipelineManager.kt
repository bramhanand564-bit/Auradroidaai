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

        val flowTaskId = TaskExecutionStore.start(
            context,
            "Generate video with Google Flow: $flowTopic",
            AutomationMode.BACKGROUND_WEB,
            "GOOGLE_FLOW"
        )
        TaskRunController.begin(flowTaskId)
        VideoQueueManager.appendLog(context, "🧭 Flow route: GOOGLE_FLOW • taskId=$flowTaskId")
        isWebTaskRunning = true
        currentWebJob = scope.launch {
            var flowStageCompleted = false
            var terminalFlowStatus: TaskStatus? = null
            var terminalFlowMessage = ""
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
                    folderPath = folderPath,
                    taskRunId = flowTaskId
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

                    // Complete the video-generation stage, then hand off to a separately tracked
                    // YouTube upload task. This avoids marking upload success before it is verified.
                    TaskExecutionStore.update(
                        context, flowTaskId, TaskStatus.COMPLETED, message =
                        "Google Flow video saved and inspected: ${downloadedFile.name}. YouTube upload is a separate task."
                    )
                    flowStageCompleted = true
                    TaskRunController.finish(flowTaskId)
                    VideoQueueManager.appendLog(context, "✅ Flow generation stage verified; YouTube upload will be tracked as a separate task.")

                    hud.updateStatus("Nax Bot • Saved!", "Starting YouTube upload for Part ${episodePlan.episodeNumber}...", "UPLOADING")
                    delay(1200)

                    AutoTouchService.instance?.startYouTubeUploadJob(
                        context = context,
                        apiKey = apiKey,
                        model = model,
                        folderPath = folderPath,
                        topicInstruction = richTopicInstruction
                    )
                } else {
                    terminalFlowStatus = TaskStatus.PAUSED
                    terminalFlowMessage = "Google Flow did not yield a verified local video. No upload was started."
                    hud.updateStatus("Download Timeout", "Video download was not verified; upload stopped.", "SAD")
                    VideoQueueManager.appendLog(
                        context,
                        "⚠️ Video rendered on Google Flow, but automatic MP4 stream capture timed out. Tap the card in Browser tab to save."
                    )
                }
            } catch (_: CancellationException) {
                terminalFlowStatus = TaskStatus.CANCELLED
                terminalFlowMessage = "Google Flow task cancelled; no upload success was claimed."
                hud.updateStatus("Nax Bot", "Stopped", "IDLE")
            } catch (e: Exception) {
                terminalFlowStatus = TaskStatus.FAILED
                terminalFlowMessage = e.message ?: "Google Flow pipeline failed before a verified video was saved."
                hud.updateStatus("Nax Bot Error", e.message?.take(24) ?: "Error", "ERROR")
                VideoQueueManager.appendLog(context, "❌ Flow Pipeline Error: ${e.message}")
            } finally {
                if (!flowStageCompleted) {
                    val status = terminalFlowStatus ?: TaskStatus.PAUSED
                    val message = terminalFlowMessage.ifBlank {
                        "Google Flow stage stopped without a verified output file."
                    }
                    TaskExecutionStore.update(context, flowTaskId, status, message = message)
                    VideoQueueManager.appendLog(context, "📋 Flow task state saved: $status • $message")
                }
                TaskRunController.finish(flowTaskId)
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
        val explicitlyRequestsFlow = Regex("""\b(flow|google flow)\b""", RegexOption.IGNORE_CASE).containsMatchIn(goal) ||
            lowerGoal.contains("labs.google/fx") || lowerGoal.contains("flow.google")
        if (!skipFlowRouting && explicitlyRequestsFlow) {
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
        val webView = hud?.getOrCreateSharedWebView()
        if (hud == null || webView == null) {
            VideoQueueManager.appendLog(context, "⛔ Web task not started: Accessibility service/WebView is unavailable. Enable Auto-Touch and retry.")
            return
        }

        val route = AppAutomationRouter.chooseRoute(
            goal = goal,
            suggestedMode = "WEB",
            suggestedUrl = startUrl,
            pinnedFlowUrl = VideoQueueManager.getPinnedFlowUrl(context)
        )
        val resolvedStartUrl = route.startUrl
        val webAdapterId = AppAutomationRouter.memoryDomainForWebGoal(goal, resolvedStartUrl)
        val taskRunId = TaskExecutionStore.start(context, goal, AutomationMode.BACKGROUND_WEB, webAdapterId)
        TaskRunController.begin(taskRunId)
        VideoQueueManager.appendLog(
            context,
            "🧭 Web route: ${route.mode} • adapter=$webAdapterId • url=$resolvedStartUrl • ${route.reason}"
        )

        isWebTaskRunning = true
        currentWebJob = scope.launch {
            var taskCompleted = false
            var terminalTaskStatus: TaskStatus? = null
            var terminalTaskMessage = ""
            try {
                val firstRunLearn = VideoQueueManager.isFirstRunLiveLearnEnabled(context)
                val verified = VideoQueueManager.isSkillLiveVerified(context, webAdapterId)
                if (firstRunLearn && !verified) {
                    hud.openForFirstRunLiveCalibration()
                }

                if (resolvedStartUrl.isBlank() || AppAutomationRouter.normalizeHttpUrl(resolvedStartUrl) == null) {
                    terminalTaskStatus = TaskStatus.FAILED
                    terminalTaskMessage = "No safe HTTPS destination could be resolved for this web task."
                    throw IllegalArgumentException(terminalTaskMessage)
                }
                if (webView.url != resolvedStartUrl) {
                    webView.loadUrl(resolvedStartUrl)
                    delay(3000)
                }

                val history = mutableListOf<String>()
                var prevSnap: DomSnapshot? = null
                var prevShotB64: String? = null
                var currentSense = 1
                var lastVerifyReport = "Initial state ready."
                var unverifiedDoneCount = 0
                // A failed step remains pending until a later live snapshot proves that the original outcome occurred.
                var pendingBeforeSnapshot: DomSnapshot? = null
                var pendingAction: String? = null
                var pendingExpectedOutcome = ""
                var pendingReason = ""
                var pendingTargetOriginalElements = ""
                var pendingTargetIndex = -1
                var pendingTargetX = -1f
                var pendingTargetY = -1f
                var pendingTargetWasRisky = false
                var pendingObserveCycles = 0
                var pendingRecoveryRejectedCount = 0
                var pendingRecoveryCycles = 0
                val trainedBlueprint = VideoQueueManager.getTrainedSkillBlueprint(context, webAdapterId)

                for (step in 1..36) {
                    if (!isWebTaskRunning) break
                    when (TaskRunController.awaitBeforeStep(context, taskRunId, step)) {
                        TaskGateResult.STOP -> {
                            terminalTaskStatus = TaskStatus.CANCELLED
                            terminalTaskMessage = "Web task stopped while paused/cancelled; no completion was claimed."
                            break
                        }
                        TaskGateResult.REFRESH -> continue
                        TaskGateResult.READY -> Unit
                    }
                    TaskExecutionStore.update(
                        context, taskRunId, TaskStatus.VERIFYING, step,
                        "Inspecting the current DOM and verifying the previous action before advancing."
                    )
                    hud.updateStatus("Web • Step $step", "Verifying current page state...", "THINKING")

                    val snap = WebActionVerifier.captureSnapshot(webView)

                    // Before asking AI to do anything else, re-check the previous pending step against
                    // the newest live DOM. This catches delayed success and prevents duplicate submits.
                    val livePendingBefore = pendingBeforeSnapshot
                    val livePendingAction = pendingAction
                    if (livePendingBefore != null && livePendingAction != null) {
                        val pendingCheck = WebActionVerifier.verifyActionOutcome(
                            before = livePendingBefore,
                            after = snap,
                            actionTaken = livePendingAction,
                            expectedOutcome = pendingExpectedOutcome,
                            countFailure = false
                        )
                        if (pendingCheck.passed) {
                            history.add("Pending step verified before next decision: $livePendingAction — $pendingReason")
                            VideoQueueManager.appendDetailedLog(
                                context = context,
                                modelUsed = model,
                                clickSense = "Fresh DOM Pending Check",
                                actionDesc = "Recovered/observed pending step",
                                whyReason = pendingReason,
                                verifyStatus = "✅ ${pendingCheck.verificationReport}"
                            )
                            pendingBeforeSnapshot = null
                            pendingAction = null
                            pendingExpectedOutcome = ""
                            pendingReason = ""
                            pendingTargetOriginalElements = ""
                            pendingTargetIndex = -1
                            pendingTargetX = -1f
                            pendingTargetY = -1f
                            pendingTargetWasRisky = false
                            pendingObserveCycles = 0
                            pendingRecoveryRejectedCount = 0
                            pendingRecoveryCycles = 0
                            lastVerifyReport = "PENDING STEP CONFIRMED from a fresh DOM snapshot. It is now safe to continue."
                        } else if (pendingTargetWasRisky) {
                            pendingObserveCycles++
                            if (pendingObserveCycles <= 4) {
                                lastVerifyReport = "WAITING FOR RISKY STEP RESULT: '$pendingReason' has no confirmation yet. No second submit/upload/publish/send/delete will be issued; observing again (${pendingObserveCycles}/4)."
                                hud.updateStatus(
                                    "Web • Verifying Result",
                                    "Checking whether the previous action finished. No duplicate action will be sent.",
                                    "THINKING"
                                )
                                delay(1400L)
                                continue
                            } else {
                                hud.updateStatus(
                                    "Web • Result Unverified",
                                    "The action may have started, but the page did not prove its result. Stopped safely.",
                                    "ERROR"
                                )
                                VideoQueueManager.appendLog(
                                    context,
                                    "⚠️ Web task stopped safely: risky action '$pendingReason' remained unverified after four fresh DOM observations. It was not repeated."
                                )
                                break
                            }
                        }
                    }

                    // Background Web tasks must not depend on phone/display screenshots.
                    // Use the page's live DOM/accessibility-like semantics, URL, loading state and task result.
                    // Keep screenshot vision reserved for the foreground phone agent.
                    val curShotB64: String? = null

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
                        learnedLessons = VideoQueueManager.getLearnedLessonsSummary(context, webAdapterId),
                        trainedBlueprint = trainedBlueprint,
                        screenChangedStatus = lastVerifyReport,
                        screenshotBase64 = curShotB64,
                        previousScreenshotBase64 = prevShotB64,
                        useHighModel = escalateHigh,
                        highModelName = VideoQueueManager.getHighModelName(context),
                        useGoogleSearch = VideoQueueManager.isGoogleSearchGroundingEnabled(context) && escalateHigh
                    )

                    // The model is not allowed to start a different task step while a safe step is pending.
                    // Only the same re-identified target, WAIT, or SCROLL may recover it.
                    val currentlyPendingAction = pendingAction
                    if (currentlyPendingAction != null) {
                        val pendingBase = pendingBeforeSnapshot
                        val resolvedPendingTarget = if (pendingBase != null && pendingTargetIndex >= 0) {
                            WebActionVerifier.resolveElementIndexByLabel(
                                originalElements = pendingTargetOriginalElements,
                                currentElements = snap.interactiveElementsText,
                                originalIndex = pendingTargetIndex
                            )
                        } else -1

                        val aiTargetLine = snap.interactiveElementsText.lineSequence()
                            .firstOrNull { it.trimStart().startsWith("[${decision.elementIndex}] ") }.orEmpty()
                        val aiTargetLooksLikeDownloadControl = aiTargetLine.contains("download", true) ||
                            aiTargetLine.contains("play", true) || aiTargetLine.contains("video", true) ||
                            aiTargetLine.contains("save", true)
                        val allowedRecovery = decision.action == "WAIT" || decision.action == "SCROLL" ||
                            when (currentlyPendingAction) {
                                "CLICK_EL" -> decision.action == "CLICK_EL" &&
                                    resolvedPendingTarget >= 0 && decision.elementIndex == resolvedPendingTarget
                                "TYPE_EL" -> decision.action == "TYPE_EL" &&
                                    resolvedPendingTarget >= 0 && decision.elementIndex == resolvedPendingTarget
                                "CLICK_XY" -> decision.action == "CLICK_XY" &&
                                    pendingTargetX >= 0f && pendingTargetY >= 0f &&
                                    kotlin.math.abs(decision.tapX - pendingTargetX) <= 24f &&
                                    kotlin.math.abs(decision.tapY - pendingTargetY) <= 24f
                                "LOAD_URL" -> decision.action == "LOAD_URL" &&
                                    decision.url.isNotBlank() && decision.url == pendingExpectedOutcome
                                "DOWNLOAD_VIDEO" -> decision.action == "DOWNLOAD_VIDEO" ||
                                    (decision.action == "CLICK_EL" && aiTargetLooksLikeDownloadControl)
                                else -> decision.action == currentlyPendingAction
                            }
                        if (!allowedRecovery) {
                            pendingRecoveryRejectedCount++
                            lastVerifyReport = "BLOCKED NEXT STEP: '${pendingReason}' is still pending. AI proposed '${decision.action}', which is not a valid recovery for that step. Re-observe or recover the same target."
                            history.add("Step $step blocked: new action ${decision.action} while ${currentlyPendingAction} remained pending.")
                            VideoQueueManager.appendDetailedLog(
                                context = context,
                                modelUsed = model,
                                clickSense = "Pending-Step Gate",
                                actionDesc = "Blocked unrelated action: ${decision.action}",
                                whyReason = pendingReason,
                                verifyStatus = "⛔ STEP NOT ADVANCED (${pendingRecoveryRejectedCount}/3)"
                            )
                            if (pendingRecoveryRejectedCount >= 3) {
                                hud.updateStatus("Web • Recovery Blocked", "Previous step is unconfirmed; agent repeatedly tried to skip ahead.", "ERROR")
                                VideoQueueManager.appendLog(context, "⚠️ Web task stopped: AI tried to advance past an unverified step three times.")
                                break
                            }
                            delay(450L)
                            continue
                        }
                    }

                    // Pause can arrive while Gemini is deciding. Never dispatch a stale DOM target
                    // after resume; refresh the page snapshot and request a new decision first.
                    when (TaskRunController.awaitBeforeStep(context, taskRunId, step)) {
                        TaskGateResult.STOP -> {
                            terminalTaskStatus = TaskStatus.CANCELLED
                            terminalTaskMessage = "Web task stopped before the next action was dispatched."
                            break
                        }
                        TaskGateResult.REFRESH -> continue
                        TaskGateResult.READY -> Unit
                    }

                    hud.updateStatus("Web • ${decision.action}", decision.reason, "AUTO")

                    var senseUsed = WebActionVerifier.getSenseName(currentSense)
                    var inlineAfterSnap: DomSnapshot? = null
                    var inlineVerifyRes: StepVerificationResult? = null
                    when (decision.action) {
                        "DONE" -> {
                            // Do not let the model jump past a step whose result is still unconfirmed.
                            val pendingBefore = pendingBeforeSnapshot
                            val pendingName = pendingAction
                            if (pendingBefore != null && pendingName != null) {
                                val pendingCheck = WebActionVerifier.verifyActionOutcome(
                                    before = pendingBefore,
                                    after = snap,
                                    actionTaken = pendingName,
                                    expectedOutcome = pendingExpectedOutcome,
                                    countFailure = false
                                )
                                if (!pendingCheck.passed) {
                                    unverifiedDoneCount++
                                    lastVerifyReport = "DONE REJECTED: prior step '$pendingReason' is still unconfirmed in the live DOM. Recover that same step; do not advance."
                                    history.add("Step $step: DONE rejected because pending '$pendingName' was not verified.")
                                    VideoQueueManager.appendDetailedLog(
                                        context = context,
                                        modelUsed = activeModelLabel,
                                        clickSense = "Pending Step Gate",
                                        actionDesc = "Rejected DONE while step pending",
                                        whyReason = pendingReason,
                                        verifyStatus = "⚠️ PENDING STEP NOT CONFIRMED"
                                    )
                                    if (unverifiedDoneCount >= 3) {
                                        hud.updateStatus("Web • Not Verified", "Previous step has no proof of completion. Stopped safely.", "ERROR")
                                        VideoQueueManager.appendLog(context, "⚠️ Web task stopped: the previous step remained unverified after repeated DONE decisions.")
                                        break
                                    }
                                    continue
                                } else {
                                    history.add("Pending step verified from fresh DOM evidence: $pendingName — $pendingReason")
                                    pendingBeforeSnapshot = null
                                    pendingAction = null
                                    pendingExpectedOutcome = ""
                                    pendingReason = ""
                                    pendingTargetOriginalElements = ""
                                    pendingTargetIndex = -1
                                    pendingTargetX = -1f
                                    pendingTargetY = -1f
                                    pendingTargetWasRisky = false
                                    pendingObserveCycles = 0
                                    pendingRecoveryRejectedCount = 0
                                }
                            }
                            val doneEvidence = WebActionVerifier.verifyGoalCompletion(goal, snap)
                            if (doneEvidence != null) {
                                TaskExecutionStore.update(
                                    context, taskRunId, TaskStatus.COMPLETED, step,
                                    "Verified completion: $doneEvidence"
                                )
                                taskCompleted = true
                                VideoQueueManager.appendDetailedLog(
                                    context = context,
                                    modelUsed = activeModelLabel,
                                    clickSense = "Goal Evidence Check",
                                    actionDesc = "AI proposed DONE",
                                    whyReason = decision.reason,
                                    verifyStatus = "✅ $doneEvidence"
                                )
                                hud.updateStatus("Nax Bot • Done", doneEvidence, "HAPPY")
                                break
                            }

                            unverifiedDoneCount++
                            lastVerifyReport = "DONE REJECTED: no task-specific completion evidence on the live page. Verify the pending task or report the blocker; do not claim success."
                            history.add("Step $step: DONE rejected — completion evidence not found.")
                            VideoQueueManager.appendDetailedLog(
                                context = context,
                                modelUsed = activeModelLabel,
                                clickSense = "Goal Evidence Check",
                                actionDesc = "Rejected unsupported DONE decision",
                                whyReason = decision.reason,
                                verifyStatus = "⚠️ NO COMPLETION EVIDENCE"
                            )
                            if (unverifiedDoneCount >= 3) {
                                terminalTaskStatus = TaskStatus.PAUSED
                                terminalTaskMessage = "Stopped safely: the web page did not provide completion evidence after three checks."
                                hud.updateStatus("Web • Not Verified", "AI reported DONE repeatedly, but the page gave no proof.", "ERROR")
                                VideoQueueManager.appendLog(context, "⚠️ Web task stopped: DONE was rejected 3 times because completion evidence was absent.")
                                break
                            }
                            TaskExecutionStore.update(
                                context, taskRunId, TaskStatus.RUNNING, step,
                                "Completion not verified; re-checking live page instead of reporting success."
                            )
                            delay(700)
                            continue
                        }
                        "CLICK_EL" -> {
                            val originalTargetLine = snap.interactiveElementsText.lineSequence()
                                .firstOrNull { it.trimStart().startsWith("[${decision.elementIndex}] ") }.orEmpty()
                            val repeatUnsafe = WebActionVerifier.isNonRepeatableAction("$originalTargetLine ${decision.reason}")
                            val tryLevels = if (repeatUnsafe) listOf(currentSense) else
                                ((currentSense.coerceIn(1, 4)..4).toList() + (1 until currentSense.coerceIn(1, 4)).toList())

                            for ((attemptNo, senseLevel) in tryLevels.withIndex()) {
                                val attemptBefore = if (attemptNo == 0) snap else WebActionVerifier.captureSnapshot(webView)
                                val resolvedIndex = if (attemptNo == 0) decision.elementIndex else
                                    WebActionVerifier.resolveElementIndexByLabel(
                                        originalElements = snap.interactiveElementsText,
                                        currentElements = attemptBefore.interactiveElementsText,
                                        originalIndex = decision.elementIndex
                                    )
                                if (resolvedIndex < 0) {
                                    lastVerifyReport = "RETRY STOPPED: original clickable target could not be safely re-identified after the page changed."
                                    break
                                }

                                senseUsed = WebActionVerifier.executeMultiSenseClick(
                                    webView = webView,
                                    hud = hud,
                                    elementIndex = resolvedIndex,
                                    fallbackCssX = decision.tapX,
                                    fallbackCssY = decision.tapY,
                                    senseLevel = senseLevel,
                                    label = decision.reason
                                )

                                val (attemptAfter, attemptVerify) = WebActionVerifier.confirmStepInOneSecond(
                                    webView = webView,
                                    before = attemptBefore,
                                    actionTaken = "CLICK_EL",
                                    expectedOutcome = decision.reason,
                                    waitMs = maxOf(1050L, VideoQueueManager.getStepDelayMs(context))
                                )
                                inlineAfterSnap = attemptAfter
                                inlineVerifyRes = attemptVerify
                                if (attemptVerify.passed) break

                                // Never blindly repeat a potentially irreversible action.
                                if (repeatUnsafe) {
                                    lastVerifyReport = "CLICK NOT CONFIRMED after one attempt. This target may have side effects; re-inspect page state before any retry."
                                    break
                                }
                                if (attemptNo < tryLevels.lastIndex) delay(250)
                            }
                        }
                        "CLICK_XY" -> {
                            WebActionVerifier.dispatchRealWebTouch(
                                webView = webView,
                                hud = hud,
                                cssX = decision.tapX,
                                cssY = decision.tapY,
                                viewportCssWidth = snap.viewportCssWidth,
                                viewportCssHeight = snap.viewportCssHeight,
                                label = decision.reason
                            )
                        }
                        "TYPE_EL" -> {
                            val typeLevels = listOf(1, 2, 3)
                            for ((attemptNo, strategy) in typeLevels.withIndex()) {
                                val attemptBefore = if (attemptNo == 0) snap else WebActionVerifier.captureSnapshot(webView)
                                val resolvedIndex = if (attemptNo == 0) decision.elementIndex else
                                    WebActionVerifier.resolveElementIndexByLabel(
                                        originalElements = snap.interactiveElementsText,
                                        currentElements = attemptBefore.interactiveElementsText,
                                        originalIndex = decision.elementIndex
                                    )
                                if (resolvedIndex < 0) {
                                    lastVerifyReport = "Typing recovery stopped: editable field could not be safely re-identified."
                                    break
                                }
                                val typed = typeIntoDomElement(webView, resolvedIndex, decision.textToType, strategy)
                                if (!typed) {
                                    lastVerifyReport = "Type strategy $strategy did not confirm the field value."
                                    if (attemptNo < typeLevels.lastIndex) delay(180)
                                    continue
                                }
                                val (attemptAfter, attemptVerify) = WebActionVerifier.confirmStepInOneSecond(
                                    webView = webView,
                                    before = attemptBefore,
                                    actionTaken = "TYPE_EL",
                                    expectedOutcome = decision.textToType,
                                    waitMs = maxOf(1050L, VideoQueueManager.getStepDelayMs(context))
                                )
                                inlineAfterSnap = attemptAfter
                                inlineVerifyRes = attemptVerify
                                senseUsed = "Type Strategy $strategy • ${if (attemptVerify.passed) "confirmed" else "unconfirmed"}"
                                if (attemptVerify.passed) break
                                if (attemptNo < typeLevels.lastIndex) delay(180)
                            }
                        }
                        "SCROLL" -> {
                            val delta = if (decision.reason.contains("up", ignoreCase = true)) -420 else 420
                            val scrollJs = """
                                (function() {
                                    var beforeY = window.scrollY || 0;
                                    window.scrollBy(0, $delta);
                                    var moved = (window.scrollY || 0) !== beforeY;
                                    var best = null;
                                    if (!moved) {
                                        var nodes = Array.from(document.querySelectorAll('*'));
                                        var bestArea = 0;
                                        for (var i = 0; i < nodes.length; i++) {
                                            var el = nodes[i];
                                            var cs = getComputedStyle(el);
                                            if (!/(auto|scroll)/.test(cs.overflowY) || el.scrollHeight <= el.clientHeight + 4) continue;
                                            var r = el.getBoundingClientRect();
                                            if (r.width < 30 || r.height < 30 || r.bottom < 0 || r.top > innerHeight) continue;
                                            var area = r.width * r.height;
                                            if (area > bestArea) { best = el; bestArea = area; }
                                        }
                                        if (best) best.scrollTop += $delta;
                                    }
                                    return JSON.stringify({
                                        windowBefore: beforeY,
                                        windowAfter: window.scrollY || 0,
                                        nestedScrollAttempted: !!best,
                                        delta: $delta
                                    });
                                })();
                            """.trimIndent()
                            webView.evaluateJavascript(scrollJs, null)
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

                    // Reuse the last fresh verification if the click recovery ladder already performed it.
                    // All other actions are verified here before a new task decision is requested.
                    val expectedOutcome = when (decision.action) {
                        "TYPE_EL" -> decision.textToType
                        "LOAD_URL" -> decision.url
                        else -> decision.reason
                    }
                    val afterSnap: DomSnapshot
                    val verifyRes: StepVerificationResult
                    if (inlineAfterSnap != null && inlineVerifyRes != null) {
                        afterSnap = inlineAfterSnap!!
                        verifyRes = inlineVerifyRes!!
                    } else {
                        val pair = WebActionVerifier.confirmStepInOneSecond(
                            webView = webView,
                            before = snap,
                            actionTaken = decision.action,
                            expectedOutcome = expectedOutcome,
                            waitMs = maxOf(1050L, VideoQueueManager.getStepDelayMs(context))
                        )
                        afterSnap = pair.first
                        verifyRes = pair.second
                    }

                    currentSense = verifyRes.recommendedSense
                    lastVerifyReport = verifyRes.verificationReport
                    unverifiedDoneCount = 0

                    // Pending-step gate: failed action becomes the only active step. New AI actions are
                    // recovery attempts until the original outcome is visible in a fresh DOM snapshot.
                    if (pendingAction == null) {
                        if (!verifyRes.passed && decision.action != "WAIT") {
                            pendingBeforeSnapshot = snap
                            pendingAction = decision.action
                            pendingExpectedOutcome = expectedOutcome
                            pendingReason = decision.reason
                            pendingTargetOriginalElements = snap.interactiveElementsText
                            pendingTargetIndex = if (decision.action == "CLICK_EL" || decision.action == "TYPE_EL") decision.elementIndex else -1
                            pendingTargetX = decision.tapX
                            pendingTargetY = decision.tapY
                            val firstTargetLine = snap.interactiveElementsText.lineSequence()
                                .firstOrNull { it.trimStart().startsWith("[${decision.elementIndex}] ") }.orEmpty()
                            pendingTargetWasRisky = WebActionVerifier.isNonRepeatableAction("$firstTargetLine ${decision.reason}") ||
                                decision.action == "SUBMIT_FLOW_PROMPT"
                            pendingObserveCycles = 0
                            pendingRecoveryRejectedCount = 0
                            pendingRecoveryCycles = 0
                            lastVerifyReport = "PENDING STEP NOT CONFIRMED: ${decision.action} — ${verifyRes.verificationReport} Recover this step only; no next task step may begin."
                        }
                    } else {
                        val pendingBefore = pendingBeforeSnapshot
                        val pendingName = pendingAction
                        val pendingResult = if (pendingBefore != null && pendingName != null) {
                            WebActionVerifier.verifyActionOutcome(
                                before = pendingBefore,
                                after = afterSnap,
                                actionTaken = pendingName,
                                expectedOutcome = pendingExpectedOutcome,
                                countFailure = false
                            )
                        } else null

                        if (pendingResult?.passed == true) {
                            history.add("Pending step now confirmed after recovery: ${pendingName} — ${pendingReason}")
                            pendingBeforeSnapshot = null
                            pendingAction = null
                            pendingExpectedOutcome = ""
                            pendingReason = ""
                            pendingTargetOriginalElements = ""
                            pendingTargetIndex = -1
                            pendingTargetX = -1f
                            pendingTargetY = -1f
                            pendingTargetWasRisky = false
                            pendingObserveCycles = 0
                            pendingRecoveryRejectedCount = 0
                            lastVerifyReport = "PENDING STEP CONFIRMED by fresh DOM evidence. Current recovery action status: ${verifyRes.verificationReport}"
                            if (!verifyRes.passed && decision.action != "WAIT") {
                                pendingBeforeSnapshot = snap
                                pendingAction = decision.action
                                pendingExpectedOutcome = expectedOutcome
                                pendingReason = decision.reason
                                pendingTargetOriginalElements = snap.interactiveElementsText
                                pendingTargetIndex = if (decision.action == "CLICK_EL" || decision.action == "TYPE_EL") decision.elementIndex else -1
                                pendingTargetX = decision.tapX
                                pendingTargetY = decision.tapY
                                val retryTargetLine = snap.interactiveElementsText.lineSequence()
                                    .firstOrNull { it.trimStart().startsWith("[${decision.elementIndex}] ") }.orEmpty()
                                pendingTargetWasRisky = WebActionVerifier.isNonRepeatableAction("$retryTargetLine ${decision.reason}") ||
                                    decision.action == "SUBMIT_FLOW_PROMPT"
                                pendingObserveCycles = 0
                                pendingRecoveryRejectedCount = 0
                                pendingRecoveryCycles = 0
                                lastVerifyReport = "Previous step confirmed; current recovery action is still pending: ${verifyRes.verificationReport}"
                            }
                        } else {
                            val stillPendingName = pendingAction ?: decision.action
                            pendingRecoveryCycles++
                            lastVerifyReport = "PENDING STEP STILL UNCONFIRMED: ${pendingReason}. Recovery action ${decision.action} reported: ${verifyRes.verificationReport}. Do not advance; choose another safe recovery method or report the blocker."
                            if (pendingRecoveryCycles >= 4) {
                                hud.updateStatus("Web • Step Failed", "The same step remained unverified after 4 recovery attempts. Stopped without claiming success.", "ERROR")
                                VideoQueueManager.appendLog(
                                    context,
                                    "⚠️ Web task stopped: pending step '${pendingReason}' could not be verified after 4 recovery attempts."
                                )
                                break
                            }
                        }
                    }

                    VideoQueueManager.appendDetailedLog(
                        context = context,
                        modelUsed = activeModelLabel,
                        clickSense = senseUsed,
                        actionDesc = "${decision.action} (#${decision.elementIndex})",
                        whyReason = decision.reason,
                        verifyStatus = if (verifyRes.passed) "✅ 1s CONFIRMED" else "⚠️ RETRY SENSE-$currentSense"
                    )

                    history.add("Step $step: ${decision.action} -> ${if (verifyRes.passed) "CONFIRMED" else "FAILED"}")
                    TaskExecutionStore.update(
                        context, taskRunId, TaskStatus.RUNNING, step,
                        if (verifyRes.passed) "Action verified; proceeding to the next step." else "Action is uncertain; the next step must re-check live page state."
                    )
                    prevSnap = afterSnap
                    prevShotB64 = curShotB64
                }
            } catch (_: CancellationException) {
                terminalTaskStatus = TaskStatus.CANCELLED
                terminalTaskMessage = "Web task cancelled; completion was not claimed."
            } catch (e: Exception) {
                terminalTaskStatus = TaskStatus.FAILED
                terminalTaskMessage = e.message ?: "Web agent failed before completion was verified."
                VideoQueueManager.appendLog(context, "⚠️ Web Agent Error: ${e.message}")
            } finally {
                if (!taskCompleted) {
                    val status = terminalTaskStatus ?: TaskStatus.PAUSED
                    val message = terminalTaskMessage.ifBlank {
                        "Web execution ended without verified completion; inspect the final page before retrying."
                    }
                    TaskExecutionStore.update(context, taskRunId, status, message = message)
                    if (status != TaskStatus.COMPLETED) {
                        VideoQueueManager.appendLog(context, "📋 Web task state saved: $status • $message")
                    }
                    VideoQueueManager.appendLog(context, "📊 Recorded task mix: ${TaskAnalytics.taskMixSummary(context)}")
                }
                TaskRunController.finish(taskRunId)
                VideoQueueManager.appendLog(context, "⏹ Web task controller released; latest task state is saved.")
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
        folderPath: String,
        taskRunId: String
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
        var promptSubmissionAttempted = false
        var promptSubmitBeforeSnapshot: DomSnapshot? = null
        var primarySubmitDispatched = false
        var promptFallbackAttempted = false
        var promptSubmitWaitCycles = 0
        var modeSetupAttempts = 0
        var downloadAttempts = 0
        var newProjectSenseLevel = 1

        for (step in 1..48) {
            if (!isWebTaskRunning) return null
            when (TaskRunController.awaitBeforeStep(context, taskRunId, step)) {
                TaskGateResult.STOP -> return null
                TaskGateResult.REFRESH -> continue
                TaskGateResult.READY -> Unit
            }
            TaskExecutionStore.update(
                context, taskRunId, TaskStatus.VERIFYING, step,
                "Checking Google Flow render/download state before taking another action."
            )

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

            // After one submit attempt, observe the background DOM instead of typing/submitting again.
            if (promptSubmissionAttempted && !promptAlreadySubmitted) {
                if (WebActionVerifier.isFlowPromptAccepted(beforeSnap, promptSubmitBeforeSnapshot)) {
                    promptAlreadySubmitted = true
                    promptSubmitWaitCycles = 0
                    VideoQueueManager.appendDetailedLog(
                        context = context,
                        modelUsed = model,
                        clickSense = "Prompt Result Evidence",
                        actionDesc = "Prompt acceptance detected from live DOM",
                        whyReason = "A render/progress/result signal appeared after one submit attempt.",
                        verifyStatus = "✅ PROMPT ACCEPTED"
                    )
                    continue
                }

                promptSubmitWaitCycles++
                if (promptSubmitWaitCycles < 4) {
                    hud.updateStatus(
                        "Prompt • Verifying",
                        "Waiting for actual render evidence. Submission will not be repeated.",
                        "THINKING"
                    )
                    delay(1800L)
                    continue
                }

                if (!promptFallbackAttempted && !primarySubmitDispatched) {
                    promptFallbackAttempted = true
                    val fallbackTapped = retryFlowPromptSubmitByTouch(webView, hud, promptToCreate)
                    if (fallbackTapped) {
                        promptSubmitWaitCycles = 0
                        VideoQueueManager.appendDetailedLog(
                            context = context,
                            modelUsed = model,
                            clickSense = "Single Touch Recovery",
                            actionDesc = "Used physical touch once after DOM submit showed no progress",
                            whyReason = "The same prompt field still contained the exact text and an enabled submit control was present.",
                            verifyStatus = "⏳ WAITING FOR RENDER EVIDENCE"
                        )
                        delay(1800L)
                        continue
                    }
                }

                hud.updateStatus(
                    "Prompt • Not Verified",
                    "No render confirmation. Stopped to avoid duplicate generation.",
                    "ERROR"
                )
                VideoQueueManager.appendLog(
                    context,
                    "⚠️ Google Flow prompt submission was not confirmed after one DOM submit and one bounded recovery. Stopped without retyping/resubmitting."
                )
                break
            }

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

            // Step 2: The mode selector changes at most one option per pass. Re-snapshot before proceeding.
            if (!promptAlreadySubmitted) {
                val modeStatus = WebActionVerifier.ensureFlowVideoMode8s(webView, hud, targetDurationSec)
                if (!modeStatus.startsWith("OK_VIDEO_MODE")) {
                    modeSetupAttempts++
                    VideoQueueManager.appendDetailedLog(
                        context = context,
                        modelUsed = model,
                        clickSense = "Flow Mode Setup",
                        actionDesc = modeStatus,
                        whyReason = "Select/verify one setting, then capture a fresh DOM snapshot.",
                        verifyStatus = "⏳ MODE STEP PENDING ($modeSetupAttempts/10)"
                    )
                    if (modeSetupAttempts >= 10) {
                        hud.updateStatus("Flow • Mode Unverified", "Could not confirm Video / 9:16 / $targetDurationSec-second / x1 settings.", "ERROR")
                        VideoQueueManager.appendLog(context, "⚠️ Google Flow stopped: mode configuration could not be confirmed after 10 safe attempts.")
                        break
                    }
                    delay(850L)
                    continue
                }
                modeSetupAttempts = 0
            }

            // Step 3 Confirmation: Type prompt into bottom composer and confirm after 1s that rendering started!
            if (!promptAlreadySubmitted) {
                hud.updateStatus("Step 3 • Prompt", "Typing ${targetDurationSec}s prompt & 1s confirming...", "WORKING")
                val submitStatus = typeAndSubmitFlowPromptOnce(webView, hud, promptToCreate)
                if (submitStatus == "SUBMITTED" || submitStatus == "TYPED_ONLY") {
                    // Never use the physical fallback after a DOM submit was already dispatched.
                    promptSubmissionAttempted = true
                    promptSubmitBeforeSnapshot = beforeSnap
                    primarySubmitDispatched = submitStatus == "SUBMITTED"
                    val (afterSubmitSnap, confirmRes) = WebActionVerifier.confirmStepInOneSecond(
                        webView = webView,
                        before = beforeSnap,
                        actionTaken = "SUBMIT_FLOW_PROMPT",
                        expectedOutcome = "A real rendering/progress/result signal appears",
                        waitMs = 1800L
                    )
                    if (WebActionVerifier.isFlowPromptAccepted(afterSubmitSnap, beforeSnap)) {
                        promptAlreadySubmitted = true
                        VideoQueueManager.appendDetailedLog(
                            context = context,
                            modelUsed = model,
                            clickSense = "Single DOM Submit",
                            actionDesc = "Prompt acceptance verified",
                            whyReason = "Fresh DOM showed render/progress/result evidence, not merely a changed input.",
                            verifyStatus = "✅ PROMPT ACCEPTED"
                        )
                        delay(1800L)
                        continue
                    } else {
                        promptSubmitWaitCycles = 0
                        VideoQueueManager.appendDetailedLog(
                            context = context,
                            modelUsed = model,
                            clickSense = if (primarySubmitDispatched) "Single DOM Submit" else "Composer Filled",
                            actionDesc = if (primarySubmitDispatched) "DOM submit dispatched once; waiting for result evidence" else "Prompt filled; no submit control activated yet",
                            whyReason = confirmRes.verificationReport,
                            verifyStatus = "⏳ DO NOT RESUBMIT"
                        )
                        hud.updateStatus("Prompt • Verifying", "Waiting for a rendering/result signal before any recovery.", "THINKING")
                        delay(1800L)
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
                            // Sense 1 activates through DOM; later senses return coordinates only.
                            if ($senseLevel === 1) b.click();
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
                        if (senseLevel == 1) {
                            if (cont.isActive) cont.resume(true)
                        } else {
                            val offsetX = if (senseLevel >= 4) 12f else 0f
                            scope.launch {
                                val touchOk = WebActionVerifier.dispatchRealWebTouch(
                                    webView = webView,
                                    hud = hud,
                                    cssX = obj.optDouble("x", 270.0).toFloat() + offsetX,
                                    cssY = obj.optDouble("y", 700.0).toFloat(),
                                    viewportCssWidth = obj.optDouble("vw", 540.0).toFloat(),
                                    viewportCssHeight = obj.optDouble("vh", 960.0).toFloat(),
                                    holdDurationMs = if (senseLevel >= 3) 150L else 95L,
                                    label = "+ New project"
                                )
                                if (cont.isActive) cont.resume(touchOk)
                            }
                        }
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
    ): String = suspendCancellableCoroutine { cont ->
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

                var actualPrompt = ('value' in targetBox) ? String(targetBox.value || '') :
                    String(targetBox.innerText || targetBox.textContent || '');
                function normalizeText(s) { return String(s || '').replace(/\\s+/g, ' ').trim(); }
                if (normalizeText(actualPrompt) !== normalizeText(textToInsert)) {
                    return JSON.stringify({ status: 'TYPE_FAILED' });
                }

                var btns = Array.from(document.querySelectorAll('button, [role="button"]'));
                var submitBtn = null;
                for (var j = 0; j < btns.length; j++) {
                    var b = btns[j];
                    var br = b.getBoundingClientRect();
                    var style = window.getComputedStyle(b);
                    if (br.width < 12 || br.height < 12 || br.bottom <= 0 || br.top >= vh ||
                        br.right <= 0 || br.left >= vw || style.display === 'none' ||
                        style.visibility === 'hidden' || b.disabled || b.getAttribute('aria-disabled') === 'true') continue;
                    if (br.top < 250) continue;
                    var btxt = ((b.innerText || '') + ' ' + (b.getAttribute('aria-label') || '') + ' ' + (b.title || '')).toLowerCase();
                    if (btxt.indexOf('arrow_forward') !== -1 || btxt.indexOf('generate') !== -1 ||
                        btxt.indexOf('submit prompt') !== -1 || btxt.trim() === 'submit' ||
                        btxt.trim() === 'send' || btxt.indexOf('create video') !== -1) {
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
                    // Preserve whether submit was actually activated so recovery never double-submits.
                    if (cont.isActive) cont.resume(status)
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume("ERROR")
                }
            }
        }
    }

    /**
     * One carefully gated physical-touch fallback for Google Flow submission.
     * It will only tap if the exact prompt is still in the composer, the submit control is visible/enabled,
     * and the page has no visible generation/loading evidence. It never types or DOM-clicks again.
     */
    private suspend fun retryFlowPromptSubmitByTouch(
        webView: WebView,
        hud: AiOverlayHud,
        promptText: String
    ): Boolean = suspendCancellableCoroutine { cont ->
        val escaped = JSONObject.quote(promptText)
        val js = """
            (function() {
                var vw = window.innerWidth || 540;
                var vh = window.innerHeight || 960;
                var body = (document.body && document.body.innerText) ? document.body.innerText : '';
                if (/\b(generating|rendering|queued for generation|generation started|video is being created)\b/i.test(body)) {
                    return JSON.stringify({ status: 'BUSY' });
                }
                var fields = Array.from(document.querySelectorAll('textarea, [contenteditable="true"], input[type="text"]'));
                var target = null;
                for (var i = 0; i < fields.length; i++) {
                    var el = fields[i];
                    var r = el.getBoundingClientRect();
                    var label = ((el.placeholder || '') + ' ' + (el.getAttribute('aria-label') || '') +
                        ' ' + (el.getAttribute('name') || '')).toLowerCase();
                    if (r.width < 100 || r.height < 24 || r.bottom < 0 || r.top > vh) continue;
                    if (label.indexOf('search') !== -1) continue;
                    if (label.indexOf('what do you want to create') !== -1 || label.indexOf('prompt') !== -1 || el.isContentEditable) {
                        target = el;
                        break;
                    }
                }
                if (!target) return JSON.stringify({ status: 'NO_COMPOSER' });
                var actual = ('value' in target) ? String(target.value || '') : String(target.innerText || target.textContent || '');
                if (actual.trim() !== $escaped.trim()) return JSON.stringify({ status: 'PROMPT_MISMATCH' });

                var buttons = Array.from(document.querySelectorAll('button, [role="button"]'));
                for (var j = 0; j < buttons.length; j++) {
                    var b = buttons[j];
                    var br = b.getBoundingClientRect();
                    var text = ((b.innerText || '') + ' ' + (b.getAttribute('aria-label') || '') + ' ' + (b.title || '')).toLowerCase();
                    if (br.width < 12 || br.height < 12 || br.bottom < 0 || br.top > vh) continue;
                    if (br.top < vh * 0.55) continue;
                    if (b.disabled || b.getAttribute('aria-disabled') === 'true') continue;
                    if (text.indexOf('arrow_forward') === -1 && text.indexOf('generate') === -1 &&
                        text.indexOf('create') === -1 && text.indexOf('submit') === -1) continue;
                    return JSON.stringify({
                        status: 'READY_FOR_TOUCH',
                        x: Math.round(br.left + br.width / 2),
                        y: Math.round(br.top + br.height / 2),
                        vw: vw,
                        vh: vh
                    });
                }
                return JSON.stringify({ status: 'NO_ENABLED_SUBMIT' });
            })();
        """.trimIndent()

        mainHandler.post {
            webView.evaluateJavascript(js) { raw ->
                try {
                    val clean = raw?.trim()?.removeSurrounding("\"")?.replace("\\\"", "\"") ?: "{}"
                    val obj = JSONObject(clean)
                    if (obj.optString("status") != "READY_FOR_TOUCH") {
                        if (cont.isActive) cont.resume(false)
                    } else {
                        scope.launch {
                            val touched = WebActionVerifier.dispatchRealWebTouch(
                                webView = webView,
                                hud = hud,
                                cssX = obj.optDouble("x", 495.0).toFloat(),
                                cssY = obj.optDouble("y", 890.0).toFloat(),
                                viewportCssWidth = obj.optDouble("vw", 540.0).toFloat(),
                                viewportCssHeight = obj.optDouble("vh", 960.0).toFloat(),
                                holdDurationMs = 150L,
                                label = "Generate • one fallback"
                            )
                            if (cont.isActive) cont.resume(touched)
                        }
                    }
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(false)
                }
            }
        }
    }

    /**
     * Three idempotent ways to put an exact value into a DOM field.
     * The callback confirms field content; the caller then performs an independent fresh-snapshot check.
     */
    private suspend fun typeIntoDomElement(
        webView: WebView,
        elementIndex: Int,
        text: String,
        strategy: Int
    ): Boolean = suspendCancellableCoroutine { cont ->
        val escaped = JSONObject.quote(text)
        val js = """
            (function() {
                var list = window.__naxElements || [];
                var el = list[$elementIndex];
                if (!el) return JSON.stringify({ ok: false, error: 'STALE_TARGET' });
                var tag = (el.tagName || '').toLowerCase();
                var editable = tag === 'input' || tag === 'textarea' || tag === 'select' || el.isContentEditable;
                if (!editable || el.disabled || el.getAttribute('aria-disabled') === 'true') {
                    return JSON.stringify({ ok: false, error: 'NOT_EDITABLE_OR_DISABLED' });
                }

                var textToSet = $escaped;
                el.focus();
                try {
                    if (el.isContentEditable) {
                        if ($strategy === 1) {
                            el.textContent = textToSet;
                        } else if ($strategy === 2) {
                            document.execCommand('selectAll', false, null);
                            document.execCommand('insertText', false, textToSet);
                            if (String(el.innerText || el.textContent || '') !== textToSet) {
                                el.textContent = textToSet;
                            }
                        } else {
                            el.textContent = textToSet;
                        }
                    } else if ($strategy === 1) {
                        var proto = tag === 'textarea' ? HTMLTextAreaElement.prototype :
                            (tag === 'select' ? HTMLSelectElement.prototype : HTMLInputElement.prototype);
                        var descriptor = Object.getOwnPropertyDescriptor(proto, 'value');
                        if (descriptor && descriptor.set) descriptor.set.call(el, textToSet);
                        else el.value = textToSet;
                    } else if ($strategy === 2) {
                        el.value = textToSet;
                    } else {
                        if (typeof el.setRangeText === 'function' && tag !== 'select') {
                            el.setRangeText(textToSet, 0, String(el.value || '').length, 'end');
                        } else {
                            el.value = textToSet;
                        }
                    }
                    try {
                        el.dispatchEvent(new InputEvent('input', {
                            bubbles: true, cancelable: false,
                            inputType: 'insertText', data: textToSet
                        }));
                    } catch (e) {
                        el.dispatchEvent(new Event('input', { bubbles: true }));
                    }
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                    el.dispatchEvent(new KeyboardEvent('keyup', { bubbles: true, key: 'Unidentified' }));
                } catch (e) {
                    return JSON.stringify({ ok: false, error: 'SET_FAILED' });
                }

                var actual = ('value' in el) ? String(el.value || '') :
                    String(el.innerText || el.textContent || '');
                var ok = actual === textToSet || actual.trim() === textToSet.trim();
                return JSON.stringify({ ok: ok, length: actual.length, strategy: $strategy });
            })();
        """.trimIndent()

        mainHandler.post {
            webView.evaluateJavascript(js) { raw ->
                try {
                    val clean = raw?.trim()?.removeSurrounding("\"")?.replace("\\\"", "\"") ?: "{}"
                    val obj = JSONObject(clean)
                    if (cont.isActive) cont.resume(obj.optBoolean("ok", false))
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(false)
                }
            }
        }
    }
}
