package com.auradroid.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import java.io.File

data class UiNodeSnapshot(
    val index: Int,
    val node: AccessibilityNodeInfo,
    val text: String,
    val contentDesc: String,
    val viewId: String,
    val isEditable: Boolean,
    val isClickable: Boolean,
    val centerX: Float,
    val centerY: Float
)

class AutoTouchService : AccessibilityService() {

    companion object {
        var instance: AutoTouchService? = null
        var isJobRunning = false
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var activeJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        VideoQueueManager.appendLog(this, "✅ Auto-Touch Service Connected & Active!")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        serviceScope.cancel()
    }

    fun stopCurrentJob() {
        activeJob?.cancel()
        isJobRunning = false
        VideoQueueManager.appendLog(this, "🛑 Agent Stopped by User.")
    }

    /**
     * Complete Automated YouTube Video Upload Workflow:
     * 1. Checks folder & picks next un-uploaded video
     * 2. Generates Title & Description using Gemini API
     * 3. Opens YouTube Upload UI with the selected video
     * 4. Uses Screen Reading + Auto-Touch + Gemini Brain to type Title, Description & click Next/Upload
     * 5. Marks video as uploaded & writes final report
     */
    fun startYouTubeAutoUpload(
        apiKey: String,
        model: String,
        folderPath: String,
        userPrompt: String
    ) {
        if (isJobRunning) {
            VideoQueueManager.appendLog(this, "⚠️ A task is already running!")
            return
        }

        activeJob = serviceScope.launch {
            isJobRunning = true
            try {
                VideoQueueManager.appendLog(this@AutoTouchService, "📂 Checking folder: $folderPath")
                val nextVideo = VideoQueueManager.getNextPendingVideo(this@AutoTouchService, folderPath)
                if (nextVideo == null) {
                    VideoQueueManager.appendLog(this@AutoTouchService, "✅ All videos in folder are already uploaded! Nothing pending.")
                    isJobRunning = false
                    return@launch
                }

                VideoQueueManager.appendLog(this@AutoTouchService, "🎬 Selected Next Video: ${nextVideo.name}")
                VideoQueueManager.appendLog(this@AutoTouchService, "🧠 Asking Gemini ($model) for viral Title & Description...")

                val metadata = GeminiBrain.generateYouTubeMetadata(
                    apiKey = apiKey,
                    model = model,
                    fileName = nextVideo.name,
                    userInstruction = userPrompt
                )

                VideoQueueManager.appendLog(this@AutoTouchService, "✨ AI Title: ${metadata.title}")
                VideoQueueManager.appendLog(this@AutoTouchService, "🚀 Launching YouTube with video file...")

                launchYouTubeWithVideo(nextVideo)
                delay(5500) // Wait for YouTube upload screen to load

                var titleFilled = false
                var descFilled = false
                val actionHistory = mutableListOf<String>()

                for (step in 1..18) {
                    if (!isActive) break
                    val nodes = captureScreenNodes()
                    if (nodes.isEmpty()) {
                        delay(2000)
                        continue
                    }

                    // Fast Smart Rules + Gemini Assisted Hybrid Loop for YouTube UI
                    val screenTextDump = nodes.joinToString("\n") {
                        "[${it.index}] text='${it.text}' desc='${it.contentDesc}' id='${it.viewId}' edit=${it.isEditable} click=${it.isClickable} xy=(${it.centerX.toInt()},${it.centerY.toInt()})"
                    }

                    // Step A: Fill Title if an editable title field is visible
                    if (!titleFilled) {
                        val titleNode = nodes.firstOrNull {
                            it.isEditable && (
                                it.text.contains("title", true) ||
                                it.contentDesc.contains("title", true) ||
                                it.viewId.contains("title", true)
                            )
                        } ?: nodes.firstOrNull { it.isEditable }

                        if (titleNode != null) {
                            VideoQueueManager.appendLog(this@AutoTouchService, "✍️ Typing AI Title into field...")
                            typeTextIntoNode(titleNode.node, metadata.title)
                            titleFilled = true
                            actionHistory.add("Typed Title: ${metadata.title}")
                            delay(2500)
                            continue
                        }
                    }

                    // Step B: Fill Description if visible
                    if (titleFilled && !descFilled) {
                        val descEditNode = nodes.firstOrNull {
                            it.isEditable && (it.text.contains("description", true) || it.viewId.contains("description", true))
                        }
                        if (descEditNode != null) {
                            VideoQueueManager.appendLog(this@AutoTouchService, "✍️ Typing AI Description...")
                            typeTextIntoNode(descEditNode.node, metadata.description)
                            descFilled = true
                            actionHistory.add("Typed Description")
                            delay(2000)
                            performGlobalAction(GLOBAL_ACTION_BACK) // Close description sub-screen if opened
                            delay(2000)
                            continue
                        }

                        val addDescRow = nodes.firstOrNull {
                            it.text.contains("Add description", true) || it.contentDesc.contains("Add description", true)
                        }
                        if (addDescRow != null) {
                            VideoQueueManager.appendLog(this@AutoTouchService, "👆 Opening Description section...")
                            clickNodeOrCoordinates(addDescRow)
                            delay(2500)
                            val innerEdit = captureScreenNodes().firstOrNull { it.isEditable }
                            if (innerEdit != null) {
                                typeTextIntoNode(innerEdit.node, metadata.description)
                                descFilled = true
                                actionHistory.add("Typed Description inside editor")
                                delay(2000)
                                performGlobalAction(GLOBAL_ACTION_BACK)
                                delay(2000)
                                continue
                            }
                        }
                    }

                    // Step C: Check for final "Upload" or "Upload Short" or "Publish" button
                    val uploadBtn = nodes.firstOrNull {
                        (it.text.equals("Upload", true) ||
                         it.text.equals("Upload video", true) ||
                         it.text.equals("Upload Short", true) ||
                         it.text.equals("Publish", true) ||
                         it.contentDesc.equals("Upload", true)) && it.isClickable
                    }
                    if (uploadBtn != null && titleFilled) {
                        VideoQueueManager.appendLog(this@AutoTouchService, "🎯 Clicking Final UPLOAD Button!")
                        clickNodeOrCoordinates(uploadBtn)
                        VideoQueueManager.markVideoUploaded(this@AutoTouchService, nextVideo)
                        VideoQueueManager.appendLog(
                            this@AutoTouchService,
                            "🎉 REPORT: '${nextVideo.name}' successfully submitted for Public upload with Title: '${metadata.title}'!"
                        )
                        break
                    }

                    // Step D: Check for "Not made for kids" option if shown
                    val notForKidsNode = nodes.firstOrNull {
                        it.text.contains("not made for kids", true) || it.contentDesc.contains("not made for kids", true)
                    }
                    if (notForKidsNode != null) {
                        VideoQueueManager.appendLog(this@AutoTouchService, "👆 Selecting 'No, it's not made for kids'...")
                        clickNodeOrCoordinates(notForKidsNode)
                        delay(1500)
                    }

                    // Step E: Check for "Next" button if Title is filled
                    val nextBtn = nodes.firstOrNull {
                        it.text.equals("Next", true) || it.contentDesc.equals("Next", true)
                    }
                    if (nextBtn != null && titleFilled) {
                        VideoQueueManager.appendLog(this@AutoTouchService, "👆 Clicking 'Next'...")
                        clickNodeOrCoordinates(nextBtn)
                        actionHistory.add("Clicked Next")
                        delay(3000)
                        continue
                    }

                    // Step F: Ask Gemini Brain to inspect current screen and decide next touch
                    val goalPrompt = """
                        We are uploading video '${nextVideo.name}' on YouTube.
                        Title to fill: "${metadata.title}" (Already filled: $titleFilled)
                        Description to fill: "${metadata.description.take(100)}..." (Already filled: $descFilled)
                        Then click Next, set Public if needed, and click Upload/Publish.
                    """.trimIndent()

                    val decision = GeminiBrain.decideNextUiAction(
                        apiKey = apiKey,
                        model = model,
                        goal = goalPrompt,
                        screenSummary = screenTextDump.take(5500),
                        history = actionHistory
                    )

                    VideoQueueManager.appendLog(this@AutoTouchService, "🤖 Gemini Step $step: ${decision.action} (${decision.reason})")
                    actionHistory.add("${decision.action}: ${decision.reason}")

                    executeDecision(decision, nodes)
                    if (decision.action.equals("DONE", true)) {
                        VideoQueueManager.markVideoUploaded(this@AutoTouchService, nextVideo)
                        VideoQueueManager.appendLog(this@AutoTouchService, "🎉 REPORT: Upload workflow completed for ${nextVideo.name}!")
                        break
                    }
                    delay(3500)
                }

            } catch (e: Exception) {
                VideoQueueManager.appendLog(this@AutoTouchService, "❌ Error: ${e.message}")
            } finally {
                isJobRunning = false
            }
        }
    }

    /**
     * Universal Multi-Tasking Agent: Can control ANY app based on natural language command
     */
    fun startUniversalAiTask(apiKey: String, model: String, userGoal: String) {
        if (isJobRunning) {
            VideoQueueManager.appendLog(this, "⚠️ Agent is already busy!")
            return
        }

        activeJob = serviceScope.launch {
            isJobRunning = true
            try {
                VideoQueueManager.appendLog(this@AutoTouchService, "🌐 Starting Multi-Task Agent: $userGoal")
                val history = mutableListOf<String>()

                // Take user to Home screen first if they want to open another app
                performGlobalAction(GLOBAL_ACTION_HOME)
                delay(2000)

                for (step in 1..20) {
                    if (!isActive) break
                    val nodes = captureScreenNodes()
                    val screenDump = nodes.joinToString("\n") {
                        "[${it.index}] text='${it.text}' desc='${it.contentDesc}' id='${it.viewId}' edit=${it.isEditable} click=${it.isClickable} xy=(${it.centerX.toInt()},${it.centerY.toInt()})"
                    }

                    val decision = GeminiBrain.decideNextUiAction(
                        apiKey = apiKey,
                        model = model,
                        goal = userGoal,
                        screenSummary = screenDump.take(6000),
                        history = history
                    )

                    VideoQueueManager.appendLog(this@AutoTouchService, "🤖 Step $step [${decision.action}]: ${decision.reason}")
                    history.add("Step $step: ${decision.action} - ${decision.reason}")

                    if (decision.action.equals("DONE", true)) {
                        VideoQueueManager.appendLog(this@AutoTouchService, "✅ Task Completed: ${decision.reason}")
                        break
                    }

                    executeDecision(decision, nodes)
                    delay(3500)
                }
            } catch (e: Exception) {
                VideoQueueManager.appendLog(this@AutoTouchService, "❌ Agent Error: ${e.message}")
            } finally {
                isJobRunning = false
            }
        }
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
            // Fallback: Launch YouTube main app if direct share fails
            val fallback = packageManager.getLaunchIntentForPackage("com.google.android.youtube")
            if (fallback != null) {
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(fallback)
            }
        }
    }

    private fun executeDecision(decision: AgentDecision, nodes: List<UiNodeSnapshot>) {
        when (decision.action.uppercase()) {
            "CLICK_NODE" -> {
                val target = nodes.firstOrNull { it.index == decision.nodeIndex }
                if (target != null) clickNodeOrCoordinates(target)
                else performTapGesture(decision.x, decision.y)
            }
            "CLICK_XY" -> performTapGesture(decision.x, decision.y)
            "TYPE" -> {
                val target = nodes.firstOrNull { it.index == decision.nodeIndex }
                    ?: nodes.firstOrNull { it.isEditable }
                if (target != null && decision.textToType.isNotEmpty()) {
                    typeTextIntoNode(target.node, decision.textToType)
                }
            }
            "SCROLL" -> performScrollDownGesture()
            "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private fun captureScreenNodes(): List<UiNodeSnapshot> {
        val root = rootInActiveWindow ?: return emptyList()
        val list = mutableListOf<UiNodeSnapshot>()
        var counter = 0

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser) return
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val viewId = node.viewIdResourceName?.substringAfter("/") ?: ""
            val clickable = node.isClickable
            val editable = node.isEditable

            if (text.isNotEmpty() || desc.isNotEmpty() || clickable || editable) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    list.add(
                        UiNodeSnapshot(
                            index = counter++,
                            node = node,
                            text = text,
                            contentDesc = desc,
                            viewId = viewId,
                            isEditable = editable,
                            isClickable = clickable,
                            centerX = rect.exactCenterX(),
                            centerY = rect.exactCenterY()
                        )
                    )
                }
            }
            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }

        traverse(root)
        return list
    }

    private fun clickNodeOrCoordinates(snapshot: UiNodeSnapshot) {
        val clicked = snapshot.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!clicked) {
            performTapGesture(snapshot.centerX, snapshot.centerY)
        }
    }

    private fun typeTextIntoNode(node: AccessibilityNodeInfo, text: String) {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!success) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("ai_text", text))
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
    }

    fun performTapGesture(x: Float, y: Float) {
        val safeX = x.coerceIn(10f, 1070f)
        val safeY = y.coerceIn(10f, 2300f)
        val path = Path().apply { moveTo(safeX, safeY) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 120)
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
