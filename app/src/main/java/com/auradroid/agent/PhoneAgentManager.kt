package com.auradroid.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
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
        if (AutoTouchService.isJobRunning) return
        AutoTouchService.isJobRunning = true
        service.overlayHud?.show()
        service.overlayHud?.setCursorOverlayVisible(true)

        val (realW, realH) = service.getRealScreenDimensions()
        val memoryVault = VideoQueueManager.getMemoryVault(service)
        val stepDelay = VideoQueueManager.getStepDelayMs(service)
        val highModelName = VideoQueueManager.getHighModelName(service)
        val autoHighModelEnabled = VideoQueueManager.isAutoHighModelEnabled(service)
        val googleSearchEnabled = VideoQueueManager.isGoogleSearchGroundingEnabled(service)

        val goalLower = userGoal.lowercase()
        val isContinuousMonitor = goalLower.contains("monitor") || goalLower.contains("wait") ||
                goalLower.contains("karte raho") || goalLower.contains("lagatar") || goalLower.contains("baat chet")

        val maxSteps = if (isContinuousMonitor || VideoQueueManager.is24HourUnstoppableEnabled(service)) 5000 else 40

        try {
            if (!initialSettingAction.isNullOrBlank()) {
                delay(2500)
            }

            val history = mutableListOf<String>()
            var previousPhoneScreenshotB64: String? = null
            var previousScreenSignature = 0
            var consecutiveVerificationFails = 0

            for (step in 1..maxSteps) {
                val nodes = captureScreenNodes(service)

                val unsentInputNode = nodes.firstOrNull {
                    it.isEditable && it.text.isNotBlank() &&
                    !it.text.equals("Message", true) && !it.text.equals("Type a message", true)
                }
                val sendBtnNode = nodes.firstOrNull {
                    it.contentDesc.equals("Send", true) || it.contentDesc.equals("भेजें", true) || it.viewId.equals("send", true)
                }
                if (unsentInputNode != null && sendBtnNode != null) {
                    animateAndPhysicalTapNode(service, sendBtnNode, "Tap: Send")
                    delay(2500)
                    continue
                }

                val screenDump = nodes.joinToString("\n") {
                    "[${it.index}] text='${it.text}' desc='${it.contentDesc}' hint='${it.hintText}' id='${it.viewId}' edit=${it.isEditable} click=${it.isClickable} xy=(${it.centerX.toInt()},${it.centerY.toInt()})"
                }

                val currentScreenshotB64 = service.captureScreenshotBase64()
                val currentSig = screenDump.take(800).hashCode()
                val screenChangedStatus = if (step == 1) "Initial step." else if (currentSig != previousScreenSignature) {
                    consecutiveVerificationFails = 0
                    "SCREEN_CHANGED: YES"
                } else {
                    consecutiveVerificationFails++
                    "SCREEN_CHANGED: NO"
                }
                previousScreenSignature = currentSig

                val useHighModelNow = autoHighModelEnabled && consecutiveVerificationFails >= 2
                val domainKey = if (goalLower.contains("whatsapp")) "WHATSAPP" else "ANDROID_UI"
                val learnedLessons = VideoQueueManager.getLearnedLessonsSummary(service, domainKey)

                val decision = GeminiBrain.decideNextUiAction(
                    apiKey = apiKey, model = model, goal = userGoal,
                    screenSummary = screenDump.take(6500), history = history,
                    memoryVault = memoryVault, learnedLessons = learnedLessons,
                    screenChangedStatus = screenChangedStatus,
                    screenshotBase64 = currentScreenshotB64,
                    previousScreenshotBase64 = previousPhoneScreenshotB64,
                    screenWidth = realW.toInt(), screenHeight = realH.toInt(),
                    isContinuousMonitorMode = isContinuousMonitor,
                    useHighModel = useHighModelNow, highModelName = highModelName,
                    useGoogleSearch = googleSearchEnabled
                )

                previousPhoneScreenshotB64 = currentScreenshotB64

                if (decision.learnedRule.isNotBlank()) {
                    VideoQueueManager.recordLearnedLesson(service, decision.learnedRule, domainKey)
                }

                if (isContinuousMonitor && decision.action.equals("DONE", true)) {
                    continue
                }

                if (decision.action.equals("DONE", true)) {
                    AutoTouchService.isJobRunning = false
                    service.overlayHud?.setCursorOverlayVisible(false)
                    break
                }
                if (decision.action.equals("WAIT", true)) {
                    delay(4500)
                    continue
                }

                service.overlayHud?.setCursorOverlayVisible(true)
                executeSmartDecision(service, decision, nodes, realW, realH)
                delay(stepDelay)
            }
        } catch (e: Exception) {
            AutoTouchService.isJobRunning = false
            service.overlayHud?.setCursorOverlayVisible(false)
        } finally {
            AutoTouchService.isJobRunning = false
        }
    }

    private suspend fun executeSmartDecision(
        service: AutoTouchService, decision: AgentDecision, nodes: List<UiNodeSnapshot>,
        realW: Float, realH: Float
    ) {
        when (decision.action.uppercase()) {
            "CLICK_NODE", "CLICK_XY" -> {
                if (decision.action.equals("CLICK_NODE", true)) {
                    val targetNode = nodes.firstOrNull { it.index == decision.nodeIndex }
                    if (targetNode != null) {
                        animateAndPhysicalTapNode(service, targetNode, "Tap")
                        return
                    }
                }
                animateAndTapXY(service, decision.x, decision.y, "Tap")
            }
            "TYPE" -> {
                val target = nodes.firstOrNull { it.index == decision.nodeIndex } ?: nodes.firstOrNull { it.isEditable }
                val tx = target?.centerX ?: (realW * 0.45f); val ty = target?.centerY ?: (realH * 0.94f)
                animateAndTapXY(service, tx, ty, "Focus")
                delay(1200)
            }
            "SCROLL" -> {
                val path = Path().apply { moveTo(540f, 1650f); lineTo(540f, 550f) }
                service.dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 400)).build(), null, null)
            }
            "BACK" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }

    fun captureScreenNodes(service: AccessibilityService): List<UiNodeSnapshot> {
        val list = mutableListOf<UiNodeSnapshot>(); var counter = 0
        service.rootInActiveWindow?.let { root ->
            fun traverse(node: AccessibilityNodeInfo?) {
                if (node == null) return
                val text = node.text?.toString()?.trim() ?: ""
                val desc = node.contentDescription?.toString()?.trim() ?: ""
                if (text.isNotEmpty() || desc.isNotEmpty() || node.isClickable || node.isEditable) {
                    val rect = Rect(); node.getBoundsInScreen(rect)
                    if (rect.width() > 0 && rect.height() > 0) {
                        list.add(UiNodeSnapshot(counter++, node, node.packageName?.toString() ?: "", 0, node.className?.toString() ?: "", text, desc, "", node.viewIdResourceName?.substringAfter("/") ?: "", node.isEditable, node.isFocused, node.isClickable, true, rect.exactCenterX(), rect.exactCenterY()))
                    }
                }
                for (i in 0 until node.childCount) traverse(node.getChild(i))
            }
            traverse(root)
        }
        return list
    }

    suspend fun animateAndPhysicalTapNode(service: AutoTouchService, snapshot: UiNodeSnapshot, label: String) {
        service.overlayHud?.moveBotAndShowTap(snapshot.centerX, snapshot.centerY, label)
        delay(420); service.performTapGesture(snapshot.centerX, snapshot.centerY)
    }

    suspend fun animateAndTapXY(service: AutoTouchService, x: Float, y: Float, label: String) {
        service.overlayHud?.moveBotAndShowTap(x, y, label)
        delay(420); service.performTapGesture(x, y)
    }

    fun performTapGesture(service: AccessibilityService, x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        service.dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 150)).build(), null, null)
    }
}
