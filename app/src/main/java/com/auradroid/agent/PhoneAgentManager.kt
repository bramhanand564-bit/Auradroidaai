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
            if (!initialSettingAction.isNullOrBlank()) {
                openSystemSettingOrApp(service, initialSettingAction)
                delay(2500)
            }

            service.overlayHud?.updateStatus("Universal Agent", "Goal: ${userGoal.take(26)}...", "THINKING")
            VideoQueueManager.appendLog(service, "🤖 Starting Universal Phone Agent: $userGoal")

            val history = mutableListOf<String>()
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

                val activePackage = nodes.firstOrNull()?.packageName.orEmpty()
                val activeWindowType = nodes.firstOrNull()?.windowType?.toString().orEmpty()
                val screenDump = buildString {
                    appendLine("REAL ANDROID SCREEN CONTEXT:")
                    appendLine("Foreground app package: ${activePackage.ifBlank { "UNKNOWN" }}")
                    appendLine("Accessibility window type: ${activeWindowType.ifBlank { "UNKNOWN" }}")
                    appendLine("Visible node count: ${nodes.size}")
                    appendLine("VISIBLE NODES (index, app package, text, description, hint, view id, edit/click, screen coordinates):")
                    nodes.forEach {
                        appendLine("[${it.index}] pkg='${it.packageName}' text='${it.text}' desc='${it.contentDesc}' hint='${it.hintText}' id='${it.viewId}' edit=${it.isEditable} click=${it.isClickable} xy=(${it.centerX.toInt()},${it.centerY.toInt()})")
                    }
                }

                val currentScreenshotB64 = service.captureScreenshotBase64()
                val currentSig = screenDump.hashCode()

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
                    if (consecutiveAiFailures >= 2) {
                        val recovered = recoverSearchSubmission(service, nodes, userGoal)
                        if (recovered != null) {
                            history.add("Step $step: AI fallback - $recovered")
                            VideoQueueManager.appendLog(service, "🛠️ Safe UI fallback: $recovered")
                            consecutiveAiFailures = 0
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
                        if (consecutiveNoProgressWaits >= 2) {
                            val recovered = recoverSearchSubmission(service, nodes, userGoal)
                            if (recovered != null) {
                                history.add("Step $step: WAIT-loop recovery - $recovered")
                                VideoQueueManager.appendLog(service, "🛠️ Recovered from repeated WAIT: $recovered")
                                consecutiveNoProgressWaits = 0
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

                service.overlayHud?.setCursorOverlayVisible(true)
                val executedSummary = executeSmartDecision(
                    service = service,
                    decision = decision,
                    nodes = nodes,
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
                    openSystemSettingOrApp(service, decision.appPackageOrSetting)
                    return "Opened ${decision.appPackageOrSetting}"
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

        if (typedSearch != null) {
            val looksLikeSearchField = typedSearch.isFocused ||
                typedSearch.hintText.contains("search", true) ||
                typedSearch.viewId.contains("search", true) ||
                typedSearch.contentDesc.contains("search", true)
            if (looksLikeSearchField &&
                typedSearch.node.performAction(AccessibilityNodeInfo.ACTION_IME_ENTER)
            ) {
                return "Submitted existing search query via keyboard action"
            }
        }

        val searchButton = nodes.firstOrNull { node ->
            node.isClickable && (
                node.text.equals("Search", true) ||
                node.contentDesc.equals("Search", true) ||
                node.text.equals("Go", true) ||
                node.contentDesc.equals("Go", true) ||
                node.viewId.contains("search_submit", true) ||
                node.viewId.contains("search_go", true)
            )
        }
        if (searchButton != null) {
            animateAndPhysicalTapNode(service, searchButton, "Tap: Search")
            return "Tapped visible Search/Go control"
        }

        return null
    }

    fun openSystemSettingOrApp(service: AutoTouchService, target: String) {
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
            else -> pm.getLaunchIntentForPackage(cleanTarget)
        } ?: Intent(Settings.ACTION_SETTINGS)

        try {
            actionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            service.startActivity(actionIntent)
        } catch (_: Exception) {
            val fallback = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            service.startActivity(fallback)
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
