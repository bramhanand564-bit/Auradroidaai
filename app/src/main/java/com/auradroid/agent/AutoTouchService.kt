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
    val className: String,
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
                    VideoQueueManager.appendLog(this@AutoTouchService, "✅ All videos in folder are already uploaded!")
                    isJobRunning = false
                    return@launch
                }

                VideoQueueManager.appendLog(this@AutoTouchService, "🎬 Selected Next Video: ${nextVideo.name}")
                VideoQueueManager.appendLog(this@AutoTouchService, "🧠 Generating viral Title & Description...")

                val metadata = GeminiBrain.generateYouTubeMetadata(
                    apiKey = apiKey,
                    model = model,
                    fileName = nextVideo.name,
                    userInstruction = userPrompt
                )

                VideoQueueManager.appendLog(this@AutoTouchService, "✨ AI Title: ${metadata.title}")
                VideoQueueManager.appendLog(this@AutoTouchService, "🚀 Launching YouTube with video file...")

                launchYouTubeWithVideo(nextVideo)
                delay(5500)

                var titleFilled = false
                var descFilled = false
                val actionHistory = mutableListOf<String>()

                for (step in 1..25) {
                    if (!isActive) break
                    val nodes = captureScreenNodes()
                    if (nodes.isEmpty()) {
                        delay(2000)
                        continue
                    }

                    // पहचानें कि क्या हम 'Add details' / Final Upload स्क्रीन पर पहुंच चुके हैं
                    val isOnAddDetailsScreen = nodes.any {
                        it.text.contains("Add details", true) ||
                        it.contentDesc.contains("Add details", true) ||
                        it.text.contains("Caption your Short", true) ||
                        it.contentDesc.contains("Caption your Short", true) ||
                        it.text.contains("Upload Short", true) ||
                        it.contentDesc.contains("Upload Short", true) ||
                        it.text.contains("Select audience", true)
                    }

                    // 1. यदि अभी तक 'Add details' स्क्रीन नहीं आई है, तो Trimmer/Editor के 'Next' या 'Done' बटन को दबाएं
                    if (!isOnAddDetailsScreen && !titleFilled) {
                        val preNextBtn = nodes.firstOrNull {
                            it.text.equals("Next", true) ||
                            it.contentDesc.equals("Next", true) ||
                            it.text.equals("Done", true) ||
                            it.contentDesc.equals("Done", true)
                        }
                        if (preNextBtn != null) {
                            VideoQueueManager.appendLog(this@AutoTouchService, "👆 Trimmer/Editor Screen: Clicking 'Next'...")
                            tapAndClickNode(preNextBtn)
                            actionHistory.add("Clicked Next on editor/trimmer")
                            delay(3500)
                            continue
                        }
                    }

                    // 2. जब हम 'Add details' स्क्रीन पर हों और Title/Caption अभी नहीं भरा गया हो
                    if (!titleFilled) {
                        // पहले देखें कि क्या कोई EditText पहले से खुला है या 'Caption your Short' लिखा दिख रहा है
                        val captionTarget = nodes.firstOrNull {
                            it.text.contains("Caption your Short", true) ||
                            it.contentDesc.contains("Caption your Short", true) ||
                            it.text.contains("Create a title", true) ||
                            it.contentDesc.contains("Create a title", true) ||
                            it.text.contains("Add a title", true) ||
                            it.viewId.contains("caption", true) ||
                            it.viewId.contains("title_edit", true)
                        } ?: nodes.firstOrNull {
                            it.isEditable || it.className.contains("EditText", true)
                        }

                        if (captionTarget != null || isOnAddDetailsScreen) {
                            VideoQueueManager.appendLog(this@AutoTouchService, "👆 Tapping 'Caption your Short' box to focus...")
                            if (captionTarget != null) {
                                tapAndClickNode(captionTarget)
                            } else {
                                // 'Add details' स्क्रीन पर थंबनेल के बगल में Caption बॉक्स के कोऑर्डिनेट्स
                                performTapGesture(580f, 320f)
                            }
                            delay(2000)

                            // अब खुले हुए इनपुट बॉक्स (Focused EditText) को पकड़ें और उसमें टाइटल डालें
                            val refreshedNodes = captureScreenNodes()
                            val activeInputNode = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                                ?: refreshedNodes.firstOrNull { it.isEditable || it.className.contains("EditText", true) }?.node
                                ?: captionTarget?.node

                            VideoQueueManager.appendLog(this@AutoTouchService, "✍️ Typing AI Title: ${metadata.title}")
                            if (activeInputNode != null) {
                                typeTextIntoNode(activeInputNode, metadata.title)
                            } else {
                                pasteViaClipboardAnywhere(metadata.title)
                            }

                            titleFilled = true
                            actionHistory.add("Typed Title: ${metadata.title}")
                            delay(2000)

                            // यदि कीबोर्ड खुलने से नीचे का 'Upload Short' बटन छुप गया हो, तो एक बार Back दबाकर कीबोर्ड बंद करें
                            val afterTypeNodes = captureScreenNodes()
                            val uploadVisible = afterTypeNodes.any {
                                it.text.contains("Upload Short", true) ||
                                it.contentDesc.contains("Upload Short", true) ||
                                it.text.equals("Upload", true)
                            }
                            if (!uploadVisible) {
                                VideoQueueManager.appendLog(this@AutoTouchService, "⌨️ Closing keyboard to reveal Upload button...")
                                performGlobalAction(GLOBAL_ACTION_BACK)
                                delay(2000)
                            }
                            continue
                        }
                    }

                    // 3. Long Video के लिए Description (यदि स्क्रीन पर 'Add description' मौजूद हो)
                    if (titleFilled && !descFilled) {
                        val addDescRow = nodes.firstOrNull {
                            it.text.contains("Add description", true) || it.contentDesc.contains("Add description", true)
                        }
                        if (addDescRow != null) {
                            VideoQueueManager.appendLog(this@AutoTouchService, "👆 Opening Description editor...")
                            tapAndClickNode(addDescRow)
                            delay(2000)
                            val innerEdit = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                                ?: captureScreenNodes().firstOrNull { it.isEditable || it.className.contains("EditText", true) }?.node
                            if (innerEdit != null) {
                                typeTextIntoNode(innerEdit, metadata.description)
                                descFilled = true
                                delay(1800)
                                performGlobalAction(GLOBAL_ACTION_BACK)
                                delay(1800)
                                continue
                            }
                        } else {
                            descFilled = true
                        }
                    }

                    // 4. फाइनल 'Upload Short' या 'Upload' बटन ढूंढें और टच करें
                    val uploadBtn = nodes.firstOrNull {
                        it.text.equals("Upload Short", true) ||
                        it.contentDesc.equals("Upload Short", true) ||
                        it.text.contains("Upload Short", true) ||
                        it.text.equals("Upload video", true) ||
                        it.text.equals("Upload", true) ||
                        it.contentDesc.equals("Upload", true) ||
                        it.text.equals("Publish", true)
                    }

                    if (uploadBtn != null && titleFilled) {
                        VideoQueueManager.appendLog(this@AutoTouchService, "🎯 Tapping Final 'Upload Short' Button!")
                        tapAndClickNode(uploadBtn)
                        delay(1500)
                        // सुरक्षा के लिए कोऑर्डिनेट्स पर भी टैप सुनिश्चित करें
                        performTapGesture(uploadBtn.centerX, uploadBtn.centerY)

                        VideoQueueManager.markVideoUploaded(this@AutoTouchService, nextVideo)
                        VideoQueueManager.appendLog(
                            this@AutoTouchService,
                            "🎉 REPORT: '${nextVideo.name}' successfully uploaded with Title: '${metadata.title}'!"
                        )
                        break
                    }

                    // यदि 'Add details' स्क्रीन पर Title भर चुका है लेकिन Litho की वजह से बटन का टेक्स्ट न दिखे, तो नीचे के Upload बटन पर टैप करें
                    if (isOnAddDetailsScreen && titleFilled) {
                        VideoQueueManager.appendLog(this@AutoTouchService, "🎯 Tapping bottom 'Upload Short' button area...")
                        val displayMetrics = resources.displayMetrics
                        val bottomX = displayMetrics.widthPixels / 2f
                        val bottomY = displayMetrics.heightPixels * 0.93f
                        performTapGesture(bottomX, bottomY)

                        VideoQueueManager.markVideoUploaded(this@AutoTouchService, nextVideo)
                        VideoQueueManager.appendLog(
                            this@AutoTouchService,
                            "🎉 REPORT: '${nextVideo.name}' successfully uploaded with Title: '${metadata.title}'!"
                        )
                        break
                    }

                    // 5. यदि Long Video में Title के बाद 'Next' या 'not made for kids' आए
                    val notForKidsNode = nodes.firstOrNull {
                        it.text.contains("not made for kids", true) || it.contentDesc.contains("not made for kids", true)
                    }
                    if (notForKidsNode != null) {
                        tapAndClickNode(notForKidsNode)
                        delay(1500)
                    }

                    val postNextBtn = nodes.firstOrNull {
                        it.text.equals("Next", true) || it.contentDesc.equals("Next", true)
                    }
                    if (postNextBtn != null && titleFilled) {
                        VideoQueueManager.appendLog(this@AutoTouchService, "👆 Clicking 'Next'...")
                        tapAndClickNode(postNextBtn)
                        delay(3000)
                        continue
                    }

                    delay(2500)
                }

            } catch (e: Exception) {
                VideoQueueManager.appendLog(this@AutoTouchService, "❌ Error: ${e.message}")
            } finally {
                isJobRunning = false
            }
        }
    }

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
                if (target != null) tapAndClickNode(target)
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
            val cls = node.className?.toString() ?: ""
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val viewId = node.viewIdResourceName?.substringAfter("/") ?: ""
            val clickable = node.isClickable
            val editable = node.isEditable || cls.contains("EditText", true)

            if (text.isNotEmpty() || desc.isNotEmpty() || clickable || editable) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    list.add(
                        UiNodeSnapshot(
                            index = counter++,
                            node = node,
                            className = cls,
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

    private fun tapAndClickNode(snapshot: UiNodeSnapshot) {
        var clicked = snapshot.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!clicked) {
            var parent = snapshot.node.parent
            var depth = 0
            while (parent != null && depth < 4) {
                if (parent.isClickable) {
                    clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) break
                }
                parent = parent.parent
                depth++
            }
        }
        // हमेशा स्क्रीन कोऑर्डिनेट्स पर फिजिकल टैप जेस्चर भी भेजें ताकि YouTube Litho UI 100% रिस्पॉन्ड करे
        performTapGesture(snapshot.centerX, snapshot.centerY)
    }

    private fun typeTextIntoNode(node: AccessibilityNodeInfo, text: String) {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!success) {
            pasteViaClipboardAnywhere(text, node)
        }
    }

    private fun pasteViaClipboardAnywhere(text: String, targetNode: AccessibilityNodeInfo? = null) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ai_text", text))
        val focused = targetNode ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            if (!focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }
        }
    }

    fun performTapGesture(x: Float, y: Float) {
        val displayMetrics = resources.displayMetrics
        val maxX = (displayMetrics.widthPixels - 10).toFloat().coerceAtLeast(100f)
        val maxY = (displayMetrics.heightPixels - 10).toFloat().coerceAtLeast(100f)
        val safeX = x.coerceIn(10f, maxX)
        val safeY = y.coerceIn(10f, maxY)

        val path = Path().apply { moveTo(safeX, safeY) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 130)
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
