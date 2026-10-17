package com.auradroid.agent

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.hypot

object PhoneAgentManager {

    suspend fun runUniversalAiTask(
        service: AutoTouchService,
        apiKey: String,
        model: String,
        userGoal: String,
        initialSettingAction: String? = null
    ) {
        if (AutoTouchService.isJobRunning) {
            VideoQueueManager.appendLog(service, "⚠️ Agent is already busy!")
            return
        }

        AutoTouchService.isJobRunning = true
        // Collapse any open Mini Window so native phone apps (WhatsApp, Settings, etc.) are completely visible
        service.overlayHud?.collapseToCompactStage()
        service.overlayHud?.show()
        service.overlayHud?.setCursorOverlayVisible(true)

        val (realW, realH) = service.getRealScreenDimensions()
        val memoryVault = VideoQueueManager.getMemoryVault(service)
        val unstoppable = VideoQueueManager.is24HourUnstoppableEnabled(service)
        val stepDelay = VideoQueueManager.getStepDelayMs(service)
        val highModelName = VideoQueueManager.getHighModelName(service)
        val autoHighModelEnabled = VideoQueueManager.isAutoHighModelEnabled(service)
        val googleSearchEnabled = VideoQueueManager.isGoogleSearchGroundingEnabled(service)

        val goalLower = userGoal.lowercase()
        // Do not treat every task containing the word "wait" or "reply" as an
        // unbounded monitoring job. Long-running mode must be explicitly requested.
        val isContinuousMonitor = goalLower.contains("monitor") ||
                goalLower.contains("keep checking") ||
                goalLower.contains("continuously") ||
                goalLower.contains("karte raho") ||
                goalLower.contains("karte rahana") ||
                goalLower.contains("lagatar") ||
                goalLower.contains("wait for a reply") ||
                goalLower.contains("incoming reply") ||
                goalLower.contains("new message notification")

        val maxSteps = if (isContinuousMonitor || unstoppable) 5000 else 40

        try {
            val history = mutableListOf<String>()
            var pinnedNativePackage: String? = null

            if (!initialSettingAction.isNullOrBlank()) {
                if (!openSystemSettingOrApp(service, initialSettingAction)) {
                    service.overlayHud?.updateStatus("Target not opened", "Could not resolve: $initialSettingAction", "ERROR")
                    VideoQueueManager.appendLog(service, "⛔ Target resolver refused '$initialSettingAction'; no unrelated app was opened.")
                    return
                }
                delay(1800)
            } else if (isExplicitNativeAppRequest(service, userGoal)) {
                val preferredApp = inferPreferredNativeApp(service, userGoal)
                if (preferredApp == null) {
                    service.overlayHud?.updateStatus("App not found", "No matching installed app. Won't switch to Chrome by guess.", "ERROR")
                    VideoQueueManager.appendLog(service, "⛔ Native-app intent detected for '$userGoal', but no matching launchable app was found. No browser/Settings fallback was used.")
                    return
                }

                val launched = openSystemSettingOrApp(service, preferredApp.packageName)
                if (!launched) {
                    service.overlayHud?.updateStatus("Launch failed", "Could not open ${preferredApp.label}", "ERROR")
                    VideoQueueManager.appendLog(service, "⛔ App launch failed for '${preferredApp.label}' (${preferredApp.packageName}). Task stopped rather than acting in the wrong app.")
                    return
                }

                val expectedPackage = preferredApp.packageName
                var actualPackage = ""
                for (attempt in 0 until 6) {
                    delay(350)
                    actualPackage = foregroundAppPackage(service)
                    if (actualPackage == expectedPackage) break
                }
                if (actualPackage != expectedPackage) {
                    service.overlayHud?.updateStatus("Wrong app blocked", "Expected ${preferredApp.label}; foreground is ${actualPackage.ifBlank { "unknown" }}", "ERROR")
                    VideoQueueManager.appendLog(service, "⛔ App-routing verification failed. Expected '${preferredApp.label}' ($expectedPackage), observed '${actualPackage.ifBlank { "unknown" }}'. No further taps were issued.")
                    return
                }
                pinnedNativePackage = expectedPackage
                val routeNote = "Native-app router opened and verified '${preferredApp.label}' ($expectedPackage); task goal preserved."
                history.add(routeNote)
                VideoQueueManager.appendLog(service, "✅ $routeNote")
            }

            service.overlayHud?.updateStatus("Universal Agent", "Goal: ${userGoal.take(26)}...", "THINKING")
            VideoQueueManager.appendLog(service, "🤖 Starting Universal Phone Agent: $userGoal")
            var lastTapX = -1f
            var lastTapY = -1f
            var repeatTapCount = 0
            var previousPhoneScreenshotB64: String? = null
            var previousScreenSignature = 0
            var consecutiveVerificationFails = 0
            var consecutiveAiFailures = 0
            var consecutiveNoProgressWaits = 0

            for (step in 1..maxSteps) {
                val nodes = captureScreenNodes(service)

                // Build an internal semantic shortlist for AI selection. These targets are never drawn on the phone screen.
                val clickableTargets = nodes
                    .filter { it.isClickable }
                    .sortedWith(compareByDescending<UiNodeSnapshot> {
                        (if (it.text.isNotBlank()) 3 else 0) +
                        (if (it.contentDesc.isNotBlank()) 2 else 0) +
                        (if (it.windowType == AccessibilityWindowInfo.TYPE_APPLICATION) 1 else 0)
                    })
                    .fold(mutableListOf<UiNodeSnapshot>()) { kept, node ->
                        if (kept.none {
                            kotlin.math.abs(it.centerX - node.centerX) < 20f &&
                            kotlin.math.abs(it.centerY - node.centerY) < 20f
                        }) kept.add(node)
                        kept
                    }
                    .take(48)
                // Keep the compact status pod off search boxes and keyboard suggestions while a real text field is focused.
                service.overlayHud?.hidePodForFocusedTextInput(nodes.any { it.isEditable && it.isFocused })

                // AUTO-SEND GUARD FOR WHATSAPP/CHAT:
                // If reply text is sitting inside the bottom message input box AND the green Send button is visible, tap Send immediately!
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
                    service.overlayHud?.setCursorOverlayVisible(true)
                    service.overlayHud?.updateStatus("Step $step • Sending", "Tapping Send button...", "WORKING")
                    VideoQueueManager.appendLog(service, "📨 Auto-Sending typed message via Send button!")
                    animateAndPhysicalTapNode(service, sendBtnNode, "Tap: Send")
                    history.add("Step $step: Auto-Tapped Send button for '${unsentInputNode.text.take(20)}'")
                    delay(2500)
                    continue
                }

                // Android exposes the IME as a separate accessibility window. Prefer
                // the actual app window so an open keyboard is not misidentified as the app.
                val foregroundAppNode = nodes.firstOrNull {
                    it.windowType == AccessibilityWindowInfo.TYPE_APPLICATION &&
                        it.packageName != service.packageName
                } ?: nodes.firstOrNull { it.packageName != service.packageName }
                val activePackage = foregroundAppNode?.packageName.orEmpty()
                val activeWindowType = foregroundAppNode?.windowType?.toString().orEmpty()
                val screenDump = buildString {
                    appendLine("REAL ANDROID SCREEN CONTEXT:")
                    appendLine("Foreground app package: ${activePackage.ifBlank { "UNKNOWN" }}")
                    appendLine("Accessibility window type: ${activeWindowType.ifBlank { "UNKNOWN" }}")
                    appendLine("Visible node count: ${nodes.size}")
                    appendLine("CLICKABLE ACCESSIBILITY TARGET CANDIDATES (internal node indexes only; NO on-screen markers are drawn):")
                    clickableTargets.forEach { n ->
                        appendLine("TARGET index=${n.index} label='${n.text.ifBlank { n.contentDesc }.ifBlank { n.viewId }.ifBlank { n.className }.take(70)}' window=${n.windowType} xy=(${n.centerX.toInt()},${n.centerY.toInt()})")
                    }
                    appendLine("VISIBLE NODES (index, app package, text, description, hint, view id, edit/click, screen coordinates):")
                    nodes.forEach {
                        appendLine("[${it.index}] pkg='${it.packageName}' text='${it.text}' desc='${it.contentDesc}' hint='${it.hintText}' id='${it.viewId}' edit=${it.isEditable} click=${it.isClickable} xy=(${it.centerX.toInt()},${it.centerY.toInt()})")
                    }
                }

                // Compare accessibility state and the freshly captured phone screenshot.
                // This catches visual changes even when the accessibility node text is unchanged.
                val currentScreenshotB64 = service.captureScreenshotBase64()
                val currentSig = 31 * screenDump.hashCode() + currentScreenshotB64.hashCode()

                val screenChangedStatus = if (step == 1) {
                    "Initial step."
                } else if (currentSig != previousScreenSignature) {
                    consecutiveVerificationFails = 0
                    consecutiveNoProgressWaits = 0
                    "SCREEN_CHANGED: YES (The UI updated after the last action)."
                } else {
                    consecutiveVerificationFails++
                    "SCREEN_CHANGED: NO (Warning: The screen UI did NOT change after the last action! Pick a different node or coordinate)."
                }
                previousScreenSignature = currentSig

                val useHighModelNow = autoHighModelEnabled &&
                        (consecutiveVerificationFails >= 2 || consecutiveAiFailures >= 1)
                val domainKey = if (goalLower.contains("whatsapp")) "WHATSAPP" else "ANDROID_UI"
                val learnedLessons = VideoQueueManager.getLearnedLessonsSummary(service, domainKey)

                val thinkLabel = if (useHighModelNow) "High-Brain Verifying..." else "Multi-frame & memory check..."
                service.overlayHud?.updateStatus("Step $step • Thinking", thinkLabel, "THINKING")

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
                    isContinuousMonitorMode = isContinuousMonitor,
                    useHighModel = useHighModelNow,
                    highModelName = highModelName,
                    useGoogleSearch = googleSearchEnabled && useHighModelNow
                )

                previousPhoneScreenshotB64 = currentScreenshotB64

                // Keep native-app tasks pinned to the verified package. The model cannot
                // silently switch this task to Chrome by emitting an OPEN_APP URL/package guess.
                val pinnedPackage = pinnedNativePackage
                if (pinnedPackage != null &&
                    decision.action.equals("OPEN_APP", true) &&
                    !hasExplicitWebDestination(userGoal)
                ) {
                    val requested = resolveInstalledApp(service, decision.appPackageOrSetting)
                    if (requested == null || requested.packageName != pinnedPackage) {
                        val blockedTarget = decision.appPackageOrSetting.ifBlank { "(empty target)" }
                        VideoQueueManager.appendLog(
                            service,
                            "🛡️ Blocked unintended app switch to '$blockedTarget'; keeping verified native package $pinnedPackage."
                        )
                        decision = decision.copy(
                            action = "WAIT",
                            reason = "App-switch safety guard: stay in the verified native app for the user's task."
                        )
                    }
                }

                if (decision.reason.startsWith("AI_ERROR:", ignoreCase = true)) {
                    consecutiveAiFailures++
                    VideoQueueManager.appendLog(
                        service,
                        "⚠️ AI decision failed ($consecutiveAiFailures/3): ${decision.reason.take(170)}"
                    )
                    service.overlayHud?.updateStatus(
                        "AI retry $consecutiveAiFailures/3",
                        "Refreshing phone context and retrying${if (autoHighModelEnabled) " with recovery model" else ""}…",
                        "VERIFYING"
                    )

                    // After two model failures, still make a narrow, evidence-based recovery
                    // attempt if a search query is already entered on the real phone.
                    if (consecutiveAiFailures == 2) {
                        val recovered = recoverSearchSubmission(service, nodes, userGoal)
                        if (recovered != null) {
                            history.add("Step $step: AI fallback - $recovered")
                            VideoQueueManager.appendLog(service, "🛠️ Safe UI fallback: $recovered")
                            delay(2200)
                            continue
                        }
                    }

                    if (consecutiveAiFailures >= 3) {
                        service.overlayHud?.updateStatus(
                            "AI temporarily unavailable",
                            "Stopped safely after 3 failed decisions. Check API key, network, and model settings, then retry.",
                            "ERROR"
                        )
                        VideoQueueManager.appendLog(
                            service,
                            "⛔ Stopped phone task after 3 AI errors; no blind taps were issued."
                        )
                        break
                    }

                    history.add("Step $step: AI_ERROR retry ${decision.reason.take(100)}")
                    delay(1200)
                    continue
                } else {
                    consecutiveAiFailures = 0
                }

                if (decision.learnedRule.isNotBlank()) {
                    VideoQueueManager.recordLearnedLesson(service, decision.learnedRule, domainKey)
                    VideoQueueManager.appendLog(service, "🧠 New Brain Rule Learned: ${decision.learnedRule}")
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
                service.overlayHud?.updateStatus("Step $step • ${decision.action}", decision.reason, stepMood)
                VideoQueueManager.appendLog(service, "🤖 Step $step [${decision.action}]:${decision.reason}")

                if (decision.action.equals("DONE", true)) {
                    AutoTouchService.isJobRunning = false
                    service.overlayHud?.setCursorOverlayVisible(false)
                    service.overlayHud?.updateStatus("🎉 Task Complete!", decision.reason, "DONE")
                    VideoQueueManager.appendLog(service, "✅ Task Completed: ${decision.reason}")
                    break
                }

                if (decision.action.equals("WAIT", true)) {
                    service.overlayHud?.setCursorOverlayVisible(false)
                    val waitReason = decision.reason.lowercase()
                    val evidenceOfTransientState = listOf(
                        "loading", "buffering", "processing", "connecting",
                        "upload in progress", "rendering", "monitoring chat",
                        "waiting for new", "please wait"
                    ).any { waitReason.contains(it) }

                    if (!isContinuousMonitor &&
                        screenChangedStatus.contains("SCREEN_CHANGED: NO") &&
                        !evidenceOfTransientState
                    ) {
                        consecutiveNoProgressWaits++
                        if (consecutiveNoProgressWaits == 2) {
                            val recovered = recoverSearchSubmission(service, nodes, userGoal)
                            if (recovered != null) {
                                history.add("Step $step: WAIT-loop recovery - $recovered")
                                VideoQueueManager.appendLog(service, "🛠️ Recovered from repeated WAIT: $recovered")
                                delay(2200)
                                continue
                            }
                        }
                        if (consecutiveNoProgressWaits >= 3) {
                            service.overlayHud?.updateStatus(
                                "Task paused safely",
                                "The screen did not change after repeated WAIT decisions. No blind taps were made; review the phone screen and retry.",
                                "ERROR"
                            )
                            VideoQueueManager.appendLog(
                                service,
                                "⛔ Paused after 3 no-progress WAIT decisions to prevent a stuck loop."
                            )
                            break
                        }
                    } else {
                        consecutiveNoProgressWaits = 0
                    }

                    delay(if (evidenceOfTransientState) 2500 else 1200)
                    history.add("Step $step: WAIT -${decision.reason}")
                    continue
                }

                var executionNodes = nodes
                if (decision.action.equals("CLICK_NODE", true) || decision.action.equals("TYPE", true)) {
                    val originalTarget = nodes.firstOrNull { it.index == decision.nodeIndex }
                    if (originalTarget == null) {
                        val blocked = "Fresh-target guard blocked ${decision.action}: model index ${decision.nodeIndex} was not in the captured snapshot."
                        VideoQueueManager.appendLog(service, "🛡️ $blocked")
                        history.add("Step $step: $blocked")
                        delay(500)
                        continue
                    }

                    // The model response can arrive seconds after the original screen snapshot.
                    // Re-scan and bind by package + stable label/view-id, never by stale index alone.
                    val latestNodes = captureScreenNodes(service)
                    val liveTarget = remapToLiveTarget(
                        original = originalTarget,
                        currentNodes = latestNodes,
                        forTyping = decision.action.equals("TYPE", true)
                    )
                    if (liveTarget == null) {
                        val blocked = "Fresh-target guard rejected stale/missing target '${originalTarget.text.ifBlank { originalTarget.contentDesc }.ifBlank { originalTarget.viewId }}'; no tap or typing was performed."
                        VideoQueueManager.appendLog(service, "🛡️ $blocked")
                        history.add("Step $step: $blocked")
                        delay(500)
                        continue
                    }
                    executionNodes = latestNodes
                    decision = decision.copy(nodeIndex = liveTarget.index)
                }

                service.overlayHud?.setCursorOverlayVisible(true)
                val executedSummary = executeSmartDecision(
                    service = service,
                    decision = decision,
                    nodes = executionNodes,
                    realW = realW,
                    realH = realH,
                    repeatCount = repeatTapCount
                ) { tapX, tapY ->
                    if (hypot(tapX - lastTapX, tapY - lastTapY) < 25f) {
                        repeatTapCount++
                        if (repeatTapCount >= 2) {
                            VideoQueueManager.recordLearnedLesson(
                                service,
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

                history.add("Step $step:${decision.action} ($executedSummary) -${decision.reason}")
                delay(stepDelay)
            }
        } catch (e: CancellationException) {
            // Clean user stop
        } catch (e: Exception) {
            AutoTouchService.isJobRunning = false
            service.overlayHud?.setCursorOverlayVisible(false)
            service.overlayHud?.updateStatus("Agent Error", e.message ?: "Failed", "ERROR")
            VideoQueueManager.appendLog(service, "❌ Agent Error: ${e.message}")
        } finally {
            service.overlayHud?.setCursorOverlayVisible(false)
            service.overlayHud?.hidePodForFocusedTextInput(false)
            AutoTouchService.isJobRunning = false
        }
    }

    private suspend fun executeSmartDecision(
        service: AutoTouchService,
        decision: AgentDecision,
        nodes: List<UiNodeSnapshot>,
        realW: Float,
        realH: Float,
        repeatCount: Int,
        onTapRecorded: (Float, Float) -> Unit
    ): String {
        val reasonLower = decision.reason.lowercase()

        when (decision.action.uppercase()) {
            "OPEN_APP" -> {
                if (decision.appPackageOrSetting.isNotBlank()) {
                    val launched = openSystemSettingOrApp(service, decision.appPackageOrSetting)
                    return if (launched) {
                        "Launch requested for '${decision.appPackageOrSetting}'; foreground package will be checked on the next step"
                    } else {
                        "Launch refused for '${decision.appPackageOrSetting}': no matching installed app/setting; no random fallback opened"
                    }
                }
            }
            "CLICK_NODE", "CLICK_XY" -> {
                // 1. Semantic Snap for WhatsApp/Chat Send button
                if (reasonLower.contains("send") || reasonLower.contains("reply")) {
                    val sendNode = nodes.firstOrNull {
                        it.contentDesc.equals("Send", true) ||
                        it.contentDesc.equals("भेजें", true) ||
                        it.viewId.equals("send", true)
                    }
                    if (sendNode != null) {
                        onTapRecorded(sendNode.centerX, sendNode.centerY)
                        animateAndPhysicalTapNode(service, sendNode, "Tap: Send")
                        return "Tapped Send Node (${sendNode.centerX.toInt()},${sendNode.centerY.toInt()})"
                    }
                }

                // 2. Semantic Snap for Top-Right 3-Dot Menu
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
                        animateAndPhysicalTapNode(service, threeDotNode, "Tap: 3-Dot Menu")
                        return "Tapped 3-Dot Node (${threeDotNode.centerX.toInt()},${threeDotNode.centerY.toInt()})"
                    }
                }

                if (decision.action.equals("CLICK_NODE", true)) {
                    val targetNode = nodes.firstOrNull { it.index == decision.nodeIndex }
                    if (targetNode != null) {
                        val label = targetNode.text.ifEmpty { targetNode.contentDesc }.take(15).ifEmpty { "Button" }
                        onTapRecorded(targetNode.centerX, targetNode.centerY)
                        animateAndPhysicalTapNode(service, targetNode, "Tap: $label")
                        return "Tapped Node #${targetNode.index} ($label)"
                    }
                }

                // The vision screenshot is deliberately resized to 540x1200 before it is sent
                // to Gemini. Models often return coordinates in that image-space even when
                // asked for physical pixels, so convert them explicitly before touching.
                var rawX = decision.x
                var rawY = decision.y
                val statusBarLimitY = realH * 0.052f
                val visionWidth = 540f
                val visionHeight = 1200f
                rawX = rawX / visionWidth * realW
                rawY = rawY / visionHeight * realH
                rawX = rawX.coerceIn(5f, realW - 5f)
                rawY = rawY.coerceIn(statusBarLimitY + 5f, realH - 5f)

                val nearestNode = nodes.filter {
                    it.centerY >= statusBarLimitY && (it.isClickable || it.text.isNotEmpty() || it.contentDesc.isNotEmpty())
                }.minByOrNull {
                    hypot(
                        (it.centerX - rawX).toDouble(),
                        (it.centerY - rawY).toDouble()
                    )
                }

                if (
                    nearestNode != null &&
                    hypot(
                        (nearestNode.centerX - rawX).toDouble(),
                        (nearestNode.centerY - rawY).toDouble()
                    ) <= 145.0
                ) {
                    val label = nearestNode.text.ifEmpty { nearestNode.contentDesc }.take(14).ifEmpty { "Tap" }
                    onTapRecorded(nearestNode.centerX, nearestNode.centerY)
                    animateAndPhysicalTapNode(service, nearestNode, "Tap: $label")
                    return "Magnet-Tapped '$label'"
                }

                if (repeatCount >= 2 && nearestNode != null) {
                    onTapRecorded(nearestNode.centerX, nearestNode.centerY)
                    animateAndPhysicalTapNode(service, nearestNode, "Retry Tap")
                    return "Anti-Stuck Node Tap"
                }

                onTapRecorded(rawX, rawY)
                animateAndTapXY(service, rawX, rawY, "Tap: (${rawX.toInt()},${rawY.toInt()})")
                return "Tapped XY (${rawX.toInt()},${rawY.toInt()})"
            }
            "TYPE" -> {
                val target = nodes.firstOrNull { it.index == decision.nodeIndex }
                    ?: nodes.firstOrNull { it.isEditable || it.supportsSetText }
                val tx = target?.centerX ?: (realW * 0.45f)
                val ty = target?.centerY ?: (realH * 0.94f)
                animateAndTapXY(service, tx, ty, "Focus Input")
                delay(1200)
                service.insertCaptionSingleClean(decision.textToType, tx, ty)
                delay(900)

                // Immediately check if a Send button (like in WhatsApp) is now visible, and tap it automatically!
                val freshNodes = captureScreenNodes(service)
                val sendBtn = freshNodes.firstOrNull {
                    it.contentDesc.equals("Send", true) ||
                    it.contentDesc.equals("भेजें", true) ||
                    it.viewId.equals("send", true)
                }
                if (sendBtn != null) {
                    animateAndPhysicalTapNode(service, sendBtn, "Tap: Send")
                    return "Typed & Sent '${decision.textToType.take(18)}'"
                }

                return "Typed '${decision.textToType.take(18)}'"
            }
            "SCROLL" -> {
                service.performScrollDownGesture()
                return "Scrolled Down"
            }
            "BACK" -> {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                return "Pressed Back"
            }
        }
        return "Waited"
    }

    /**
     * Rebind an AI-selected accessibility target to the newest live screen snapshot.
     * A reused numeric index is never sufficient: package/window and a stable view id
     * or exact semantic label must match before a tap or typing action can execute.
     */
    private fun remapToLiveTarget(
        original: UiNodeSnapshot,
        currentNodes: List<UiNodeSnapshot>,
        forTyping: Boolean
    ): UiNodeSnapshot? {
        val originalLabels = listOf(original.text, original.contentDesc, original.hintText)
            .map { normalizeAppText(it) }
            .filter { it.isNotBlank() }
            .toSet()
        if (original.viewId.isBlank() && originalLabels.isEmpty()) return null

        return currentNodes.asSequence()
            .filter {
                it.packageName == original.packageName &&
                    it.windowType == original.windowType &&
                    (!forTyping || it.isEditable || it.supportsSetText)
            }
            .mapNotNull { candidate ->
                val sameViewId = original.viewId.isNotBlank() && candidate.viewId == original.viewId
                val candidateLabels = listOf(candidate.text, candidate.contentDesc, candidate.hintText)
                    .map { normalizeAppText(it) }
                    .filter { it.isNotBlank() }
                    .toSet()
                val sameLabel = originalLabels.any { it in candidateLabels }
                if (!sameViewId && !sameLabel) return@mapNotNull null

                val distance = hypot(
                    (candidate.centerX - original.centerX).toDouble(),
                    (candidate.centerY - original.centerY).toDouble()
                )
                // Prefer matching view IDs, then exact visible labels, with proximity as a tie-breaker.
                val score = (if (sameViewId) 1000 else 0) +
                    (if (sameLabel) 250 else 0) +
                    (if (candidate.className == original.className) 30 else 0) -
                    distance.toInt().coerceAtMost(500)
                candidate to score
            }
            .maxByOrNull { it.second }
            ?.first
    }

    /**
     * Safe recovery for a clearly typed search query when the AI service is unavailable
     * or has repeatedly requested WAIT. It never invents a query or opens random results.
     */
    private suspend fun recoverSearchSubmission(
        service: AutoTouchService,
        nodes: List<UiNodeSnapshot>,
        userGoal: String
    ): String? {
        val goal = userGoal.lowercase()
        val isSearchTask = listOf("search", "find", "look for", "bhojpuri", "query")
            .any { goal.contains(it) }
        if (!isSearchTask) return null

        val typedSearch = nodes
            .filter { it.isEditable && it.text.isNotBlank() }
            .maxByOrNull { node ->
                (if (node.isFocused) 4 else 0) +
                (if (node.hintText.contains("search", true)) 3 else 0) +
                (if (node.viewId.contains("search", true)) 3 else 0) +
                (if (node.text.isNotBlank()) 1 else 0)
            }

        val looksLikeSearchField = typedSearch != null && (
            typedSearch.isFocused ||
            typedSearch.hintText.contains("search", true) ||
            typedSearch.viewId.contains("search", true) ||
            typedSearch.contentDesc.contains("search", true)
        )

        // Use the keyboard's visible labeled action node instead of a non-portable IME
        // action constant. Different Android keyboards expose Search, Go, or Enter.
        val searchButton = nodes.firstOrNull { node ->
            node.isClickable && (
                node.text.equals("Search", true) ||
                node.contentDesc.equals("Search", true) ||
                node.text.equals("Go", true) ||
                node.contentDesc.equals("Go", true) ||
                node.text.equals("Enter", true) ||
                node.contentDesc.equals("Enter", true) ||
                node.viewId.contains("search_submit", true) ||
                node.viewId.contains("search_go", true) ||
                node.viewId.contains("ime_action", true)
            )
        }
        if (searchButton != null && (looksLikeSearchField || searchButton.viewId.contains("search", true))) {
            animateAndPhysicalTapNode(service, searchButton, "Tap: Search")
            return "Tapped visible Search/Go/Enter keyboard action"
        }

        return null
    }

    private data class LaunchableApp(
        val packageName: String,
        val label: String,
        val normalizedLabel: String
    )

    private fun normalizeAppText(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")

    private fun installedLaunchableApps(service: AutoTouchService): List<LaunchableApp> {
        val pm = service.packageManager
        return try {
            pm.getInstalledApplications(0).mapNotNull { info ->
                if (info.packageName == service.packageName) return@mapNotNull null
                if (pm.getLaunchIntentForPackage(info.packageName) == null) return@mapNotNull null
                val label = runCatching { pm.getApplicationLabel(info).toString().trim() }
                    .getOrDefault(info.packageName)
                if (label.isBlank()) return@mapNotNull null
                LaunchableApp(info.packageName, label, normalizeAppText(label))
            }.distinctBy { it.packageName }
        } catch (e: Exception) {
            VideoQueueManager.appendLog(service, "⚠️ Installed-app inventory unavailable: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    private fun resolveInstalledApp(service: AutoTouchService, target: String): LaunchableApp? {
        val cleanTarget = target.trim()
        if (cleanTarget.isBlank() || cleanTarget.contains("://") ||
            Regex("""\bwww\.""", RegexOption.IGNORE_CASE).containsMatchIn(cleanTarget)) return null

        val apps = installedLaunchableApps(service)
        apps.firstOrNull { it.packageName.equals(cleanTarget, true) }?.let { return it }

        val wanted = normalizeAppText(cleanTarget)
        if (wanted.isBlank()) return null
        val isTwitterAlias = wanted in setOf("twitter", "x", "x app", "twitter app", "floks twitter", "flocks twitter", "flox twitter", "flocs twitter")
        if (!isTwitterAlias) {
            apps.firstOrNull { it.normalizedLabel == wanted }?.let { return it }
        }

        // Twitter/X aliases: the explicitly preferred Flox/Floks/Flocs client wins
        // over a browser or a generic similarly named app when the request is ambiguous.
        if (isTwitterAlias) {
            val ranked = apps.mapNotNull { app ->
                val label = app.normalizedLabel
                val packageId = app.packageName.lowercase()
                val customClient = listOf("flox", "floks", "flocks", "flocs", "flox").any { label.contains(it) || packageId.contains(it) }
                val twitterLabel = label.contains("twitter") || packageId.contains("twitter")
                val xLabel = label == "x" || label.startsWith("x ") || packageId.contains("twitter")
                val score = when {
                    customClient && twitterLabel -> 1200
                    wanted.contains("floks") || wanted.contains("flocks") || wanted.contains("flox") || wanted.contains("flocs") ->
                        (if (customClient) 900 else 0) + (if (twitterLabel) 100 else 0)
                    label == wanted -> 1000
                    customClient -> 800
                    twitterLabel -> 400
                    xLabel -> 250
                    else -> 0
                }
                if (score > 0) app to score else null
            }.sortedByDescending { it.second }
            ranked.firstOrNull()?.first?.let { return it }
        }

        // Resolve natural-language app labels from model output; never interpret a URL as a package.
        return apps
            .filter { app ->
                app.normalizedLabel.length >= 3 &&
                    (app.normalizedLabel.contains(wanted) || wanted.contains(app.normalizedLabel))
            }
            .maxByOrNull { app ->
                (if (app.normalizedLabel.contains(wanted)) 100 else 0) + app.normalizedLabel.length
            }
    }

    private fun hasExplicitWebDestination(goal: String): Boolean {
        val g = goal.lowercase()
        return listOf(
            "website", "web site", "webpage", "web page", "browser", "chrome",
            "in the browser", "in chrome", "use chrome", "open the site", "visit the site",
            "open url", "http://", "https://", "www."
        ).any { g.contains(it) }
    }

    private fun isExplicitNativeAppRequest(service: AutoTouchService, goal: String): Boolean {
        if (hasExplicitWebDestination(goal)) return false
        val g = goal.lowercase()
        val hasLaunchVerb = listOf(
            "open", "launch", "start", "switch to", "use the app", "use app",
            "app kholo", "app khol", "kholo", "khol do", "chalao", "open app"
        ).any { g.contains(it) }
        if (!hasLaunchVerb) return false

        val knownAppMention = Regex("""\btwitter\b|twitter\.com|x\.com|\bx\s+app\b|\bwhatsapp\b|\binstagram\b|\byoutube\b|\btelegram\b|\bfacebook\b|\bflox\b|\bfloks\b|\bflocks\b|\bflocs\b""")
            .containsMatchIn(g)
        if (knownAppMention || g.contains(" app") || g.contains("application")) return true

        // Generic app names are resolved from the phone's actual installed-app catalog.
        val normalizedGoal = normalizeAppText(goal)
        return installedLaunchableApps(service).any {
            it.normalizedLabel.length >= 3 && normalizedGoal.contains(it.normalizedLabel)
        }
    }

    private fun inferPreferredNativeApp(service: AutoTouchService, goal: String): LaunchableApp? {
        val normalizedGoal = normalizeAppText(goal)
        val apps = installedLaunchableApps(service)
        if (apps.isEmpty()) return null
        val words = goal.lowercase()
        val mentionsTwitter = Regex("""\btwitter\b|twitter\.com|x\.com|\bx\s+app\b|\bopen x\b""")
            .containsMatchIn(words)
        val explicitlyNamesFloxClient = Regex("""\bflox\b|\bfloks\b|\bflocks\b|\bflocs\b""")
            .containsMatchIn(words)

        // In an ambiguous Twitter/X request, prefer the user's stated Floks/Flox-style
        // client over Twitter in Chrome or the generic X label. Exact custom-client names
        // in the request still win when explicitly named.
        if (mentionsTwitter && !explicitlyNamesFloxClient) {
            val preferred = apps.mapNotNull { app ->
                val label = app.normalizedLabel
                val packageId = app.packageName.lowercase()
                val customClient = listOf("flox", "floks", "flocks", "flocs").any { label.contains(it) || packageId.contains(it) }
                val twitterLabel = label.contains("twitter") || packageId.contains("twitter")
                val officialX = label == "x" || label.startsWith("x ") || packageId.contains("twitter")
                val score = when {
                    customClient && twitterLabel -> 1000
                    customClient -> 800
                    twitterLabel -> 600
                    officialX -> 450
                    else -> 0
                }
                if (score > 0) app to score else null
            }.maxByOrNull { it.second }?.first
            if (preferred != null) return preferred
        }

        // Honor an exact installed app label mentioned in the request before aliases.
        apps
            .filter { it.normalizedLabel.length >= 3 && normalizedGoal.contains(it.normalizedLabel) }
            .maxByOrNull { it.normalizedLabel.length }
            ?.let { return it }

        if (mentionsTwitter) {
            return apps.mapNotNull { app ->
                val label = app.normalizedLabel
                val packageId = app.packageName.lowercase()
                val customClient = listOf("flox", "floks", "flocks", "flocs").any { label.contains(it) || packageId.contains(it) }
                val twitterLabel = label.contains("twitter") || packageId.contains("twitter")
                val officialX = label == "x" || label.startsWith("x ") || packageId.contains("twitter")
                val score = when {
                    customClient && twitterLabel -> 1000
                    customClient -> 800
                    twitterLabel -> 600
                    officialX -> 450
                    else -> 0
                }
                if (score > 0) app to score else null
            }.maxByOrNull { it.second }?.first
        }

        return apps
            .filter { it.normalizedLabel.length >= 3 && normalizedGoal.contains(it.normalizedLabel) }
            .maxByOrNull { it.normalizedLabel.length }
    }

    private fun foregroundAppPackage(service: AutoTouchService): String {
        return try {
            val candidates = service.windows.mapNotNull { window ->
                val root = window.root ?: return@mapNotNull null
                val pkg = root.packageName?.toString().orEmpty()
                if (pkg.isBlank() || pkg == service.packageName || pkg.contains("systemui", true)) {
                    null
                } else {
                    window.type to pkg
                }
            }
            candidates.firstOrNull { it.first == AccessibilityWindowInfo.TYPE_APPLICATION }?.second
                ?: candidates.firstOrNull()?.second.orEmpty()
        } catch (_: Exception) {
            service.rootInActiveWindow?.packageName?.toString().orEmpty()
        }
    }

    fun openSystemSettingOrApp(service: AutoTouchService, target: String): Boolean {
        val cleanTarget = target.trim()
        val pm = service.packageManager
        val actionIntent = when (cleanTarget.uppercase()) {
            "SETTINGS" -> Intent(Settings.ACTION_SETTINGS)
            "BATTERY" -> Intent(Intent.ACTION_MAIN).setClassName("com.android.settings", "com.android.settings.Settings\$PowerUsageSummaryActivity")
            "WIFI" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            "DISPLAY" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "STORAGE" -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
            "SOUND" -> Intent(Settings.ACTION_SOUND_SETTINGS)
            "BLUETOOTH" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "APPS" -> Intent(Settings.ACTION_APPLICATION_SETTINGS)
            "WHATSAPP" -> pm.getLaunchIntentForPackage("com.whatsapp")
                ?: pm.getLaunchIntentForPackage("com.whatsapp.w4b")
            else -> {
                if (cleanTarget.contains("://") || Regex("""\bwww\.""", RegexOption.IGNORE_CASE).containsMatchIn(cleanTarget)) {
                    null
                } else {
                    resolveInstalledApp(service, cleanTarget)?.let { pm.getLaunchIntentForPackage(it.packageName) }
                }
            }
        }

        if (actionIntent == null) {
            VideoQueueManager.appendLog(service, "⛔ No installed-app or supported-setting match for '$cleanTarget'; refusing unrelated Settings/Chrome fallback.")
            return false
        }

        return try {
            actionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            service.startActivity(actionIntent)
            true
        } catch (e: Exception) {
            VideoQueueManager.appendLog(service, "⛔ Could not launch '$cleanTarget': ${e.javaClass.simpleName}. No unrelated fallback opened.")
            false
        }
    }

    /**
     * Full Multi-Window Screen Node Scanner (Preserves `windowType` so Keyboard vs App nodes are accurately separated!)
     */
    fun captureScreenNodes(service: AutoTouchService): List<UiNodeSnapshot> {
        val list = mutableListOf<UiNodeSnapshot>()
        var counter = 0
        val ownPkg = service.packageName

        val windowRoots = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()
        try {
            for (win in service.windows) {
                val r = win.root ?: continue
                val pkg = r.packageName?.toString() ?: ""
                if (pkg != ownPkg && !pkg.contains("systemui", true)) {
                    windowRoots.add(Pair(win.type, r))
                }
            }
        } catch (_: Exception) {}

        if (windowRoots.isEmpty()) {
            service.rootInActiveWindow?.let {
                val pkg = it.packageName?.toString() ?: ""
                if (pkg != ownPkg && !pkg.contains("systemui", true)) {
                    windowRoots.add(Pair(AccessibilityWindowInfo.TYPE_APPLICATION, it))
                }
            }
        }

        fun traverse(node: AccessibilityNodeInfo?, winType: Int) {
            if (node == null) return
            val pkg = node.packageName?.toString() ?: ""
            if (pkg == ownPkg || pkg.contains("systemui", true)) return

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

    suspend fun animateAndPhysicalTapNode(service: AutoTouchService, snapshot: UiNodeSnapshot, label: String) {
        // Show the hand exactly over the node before activating it.
        service.overlayHud?.moveBotAndShowTap(snapshot.centerX, snapshot.centerY, label)
        delay(520)

        // Accessibility semantic click is more accurate than a coordinate gesture.
        // Only fall back to a physical gesture if the node and its clickable parents reject it;
        // this also avoids the previous double-tap (gesture followed by ACTION_CLICK).
        if (service.clickNodeAndParents(snapshot.node)) {
            delay(180)
            return
        }
        service.performTapGesture(snapshot.centerX, snapshot.centerY)
        delay(180)
    }

    suspend fun animateAndTapXY(service: AutoTouchService, x: Float, y: Float, label: String) {
        service.overlayHud?.moveBotAndShowTap(x, y, label)
        delay(420)
        service.performTapGesture(x, y)
    }
}
