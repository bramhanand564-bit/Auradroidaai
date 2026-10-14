package com.auradroid.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
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
    val hintText: String,
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
    private var overlayHud: AiOverlayHud? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlayHud = AiOverlayHud(this)
        VideoQueueManager.appendLog(this, "✅ Auto-Touch Service & Cute AI HUD Connected!")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        overlayHud?.hide()
        super.onDestroy()
        instance = null
        serviceScope.cancel()
    }

    fun stopCurrentJob() {
        activeJob?.cancel()
        isJobRunning = false
        overlayHud?.updateStatus("STOPPED", "Agent stopped by user", "DONE")
        serviceScope.launch {
            delay(1500)
            overlayHud?.hide()
        }
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
            overlayHud?.show()
            try {
                overlayHud?.updateStatus("STEP 1/5 • FOLDER SCAN", "Checking folder for next video...", "WORKING")
                VideoQueueManager.appendLog(this@AutoTouchService, "📂 Checking folder: $folderPath")

                val nextVideo = VideoQueueManager.getNextPendingVideo(this@AutoTouchService, folderPath)
                if (nextVideo == null) {
                    overlayHud?.updateStatus("ALL DONE", "All videos in folder are uploaded!", "DONE")
                    VideoQueueManager.appendLog(this@AutoTouchService, "✅ All videos in folder are already uploaded!")
                    delay(3000)
                    overlayHud?.hide()
                    isJobRunning = false
                    return@launch
                }

                VideoQueueManager.appendLog(this@AutoTouchService, "🎬 Selected Next Video: ${nextVideo.name}")
                overlayHud?.updateStatus("STEP 2/5 • GEMINI AI", "Generating viral Title & Tags for ${nextVideo.name.take(18)}...", "THINKING")
                VideoQueueManager.appendLog(this@AutoTouchService, "🧠 Generating viral Title & Description...")

                val metadata = GeminiBrain.generateYouTubeMetadata(
                    apiKey = apiKey,
                    model = model,
                    fileName = nextVideo.name,
                    userInstruction = userPrompt
                )

                VideoQueueManager.appendLog(this@AutoTouchService, "✨ AI Title: ${metadata.title}")
                overlayHud?.updateStatus("STEP 3/5 • YOUTUBE", "Opening YouTube Upload Screen...", "WORKING")
                VideoQueueManager.appendLog(this@AutoTouchService, "🚀 Launching YouTube with video file...")

                launchYouTubeWithVideo(nextVideo)
                delay(5500)

                var nextClickCount = 0
                var titleFilled = false
                var descFilled = false

                val dm = resources.displayMetrics
                val screenW = dm.widthPixels.toFloat()
                val screenH = dm.heightPixels.toFloat()

                for (step in 1..25) {
                    if (!isActive) break
                    val nodes = captureScreenNodes()

                    // देखें कि क्या स्क्रीन पर अभी 'Next' बटन मौजूद है (Trimmer या Editor स्क्रीन)
                    val nextBtn = nodes.firstOrNull {
                        it.text.equals("Next", true) ||
                        it.contentDesc.equals("Next", true) ||
                        it.text.equals("Done", true) ||
                        it.contentDesc.equals("Done", true)
                    }

                    // देखें कि क्या हम 'Add details' स्क्रीन पर पहुँच गए हैं
                    val hasAddDetailsText = nodes.any {
                        val combined = "${it.text} ${it.contentDesc} ${it.hintText}"
                        combined.contains("Add details", true) ||
                        combined.contains("Caption your Short", true) ||
                        combined.contains("Upload Short", true) ||
                        combined.contains("Select audience", true) ||
                        combined.contains("Visibility", true)
                    }

                    // यदि हम कम से कम 1-2 बार Next दबा चुके हैं और अब स्क्रीन पर Next बटन नहीं है, तो हम Add details पेज पर हैं!
                    val isOnAddDetailsScreen = hasAddDetailsText || (nextClickCount >= 2 && nextBtn == null)

                    // STEP A: Trimmer/Editor स्क्रीन पर 'Next' दबाएं (जब तक Add details पेज न आए)
                    if (!isOnAddDetailsScreen && !titleFilled && nextBtn != null) {
                        nextClickCount++
                        overlayHud?.updateStatus(
                            "STEP 3/5 • EDITOR ($nextClickCount)",
                            "Clicking 'Next' button...",
                            "WORKING"
                        )
                        VideoQueueManager.appendLog(this@AutoTouchService, "👆 Trimmer/Editor Screen: Clicking 'Next' (#$nextClickCount)...")
                        animateAndTapNode(nextBtn, "Tap: Next")
                        delay(3800)
                        continue
                    }

                    // STEP B: 'Add details' स्क्रीन पर 'Caption your Short' / Title भरें
                    if (!titleFilled && (isOnAddDetailsScreen || nextClickCount >= 2)) {
                        overlayHud?.updateStatus("STEP 4/5 • CAPTION", "Tapping 'Caption your Short' box...", "WORKING")
                        VideoQueueManager.appendLog(this@AutoTouchService, "👆 Focusing 'Caption your Short' box...")

                        val captionNode = nodes.firstOrNull {
                            val combined = "${it.text} ${it.contentDesc} ${it.hintText} ${it.viewId}"
                            combined.contains("Caption your Short", true) ||
                            combined.contains("Create a title", true) ||
                            combined.contains("Add a title", true) ||
                            combined.contains("caption", true) ||
                            combined.contains("title_edit", true)
                        } ?: nodes.firstOrNull {
                            it.isEditable || it.className.contains("EditText", true)
                        }

                        if (captionNode != null) {
                            animateAndTapNode(captionNode, "Tap: Caption Box")
                        } else {
                            // YouTube Shorts 'Add details' पेज पर थंबनेल के बगल में 'Caption your Short' का सटीक स्थान
                            val capX = screenW * 0.54f
                            val capY = screenH * 0.135f
                            animateAndTapXY(capX, capY, "Tap: Caption Box")
                        }
                        delay(2200)

                        // अब कीबोर्ड और इनपुट बॉक्स खुल चुका है, इसमें AI Title टाइप/पेस्ट करें
                        overlayHud?.updateStatus("STEP 4/5 • TYPING", "Typing: ${metadata.title.take(28)}...", "WORKING")
                        VideoQueueManager.appendLog(this@AutoTouchService, "✍️ Typing AI Title: ${metadata.title}")

                        val updatedNodes = captureScreenNodes()
                        val focusedEdit = findFocusedOrEditableNode(updatedNodes) ?: captionNode?.node
                        typeOrPasteTextEverywhere(focusedEdit, metadata.title)

                        titleFilled = true
                        delay(2000)

                        // कीबोर्ड बंद करने के लिए एक बार Back दबाएं ताकि नीचे का 'Upload Short' बटन साफ दिखे
                        overlayHud?.updateStatus("STEP 4/5 • KEYBOARD", "Closing keyboard to view Upload button...", "WORKING")
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        delay(2200)
                        continue
                    }

                    // STEP C: यदि Long Video है और 'Add description' दिख रहा है, तो Description भरें
                    if (titleFilled && !descFilled) {
                        val addDescNode = nodes.firstOrNull {
                            val combined = "${it.text} ${it.contentDesc} ${it.hintText}"
                            combined.contains("Add description", true)
                        }
                        if (addDescNode != null) {
                            overlayHud?.updateStatus("STEP 4/5 • DESCRIPTION", "Filling AI Description...", "WORKING")
                            animateAndTapNode(addDescNode, "Tap: Description")
                            delay(2000)
                            val descEdit = findFocusedOrEditableNode(captureScreenNodes())
                            typeOrPasteTextEverywhere(descEdit, metadata.description)
                            descFilled = true
                            delay(1800)
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            delay(1800)
                            continue
                        } else {
                            descFilled = true
                        }
                    }

                    // STEP D: फाइनल 'Upload Short' या 'Upload' बटन पर टैप करें
                    if (titleFilled) {
                        val uploadBtn = nodes.firstOrNull {
                            val combined = "${it.text} ${it.contentDesc} ${it.hintText}"
                            combined.contains("Upload Short", true) ||
                            it.text.equals("Upload", true) ||
                            it.contentDesc.equals("Upload", true) ||
                            combined.contains("Upload video", true) ||
                            it.text.equals("Publish", true)
                        }

                        overlayHud?.updateStatus("STEP 5/5 • PUBLISHING", "Tapping 'Upload Short' button!", "WORKING")
                        VideoQueueManager.appendLog(this@AutoTouchService, "🎯 Tapping Final 'Upload Short' Button!")

                        if (uploadBtn != null) {
                            animateAndTapNode(uploadBtn, "Tap: Upload Short")
                        } else {
                            // YouTube Shorts के 'Add details' पेज पर सबसे नीचे सफेद 'Upload Short' बटन का सटीक स्थान
                            val upX = screenW * 0.50f
                            val upY = screenH * 0.958f
                            animateAndTapXY(upX, upY, "Tap: Upload Short")
                        }

                        delay(2000)
                        VideoQueueManager.markVideoUploaded(this@AutoTouchService, nextVideo)
                        overlayHud?.updateStatus("🎉 UPLOAD SUCCESS!", "'${nextVideo.name.take(20)}' Published!", "DONE")
                        VideoQueueManager.appendLog(
                            this@AutoTouchService,
                            "🎉 REPORT: '${nextVideo.name}' successfully uploaded with Title: '${metadata.title}'!"
                        )
                        delay(4000)
                        break
                    }

                    delay(2000)
                }

            } catch (e: Exception) {
                overlayHud?.updateStatus("❌ ERROR", e.message ?: "Error occurred", "DONE")
                VideoQueueManager.appendLog(this@AutoTouchService, "❌ Error: ${e.message}")
                delay(3000)
            } finally {
                overlayHud?.hide()
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
            overlayHud?.show()
            try {
                overlayHud?.updateStatus("MULTI-TASK AI", "Goal: ${userGoal.take(30)}...", "THINKING")
                VideoQueueManager.appendLog(this@AutoTouchService, "🌐 Starting Multi-Task Agent: $userGoal")
                val history = mutableListOf<String>()

                performGlobalAction(GLOBAL_ACTION_HOME)
                delay(2000)

                for (step in 1..20) {
                    if (!isActive) break
                    val nodes = captureScreenNodes()
                    val screenDump = nodes.joinToString("\n") {
                        "[${it.index}] text='${it.text}' desc='${it.contentDesc}' hint='${it.hintText}' id='${it.viewId}' edit=${it.isEditable} click=${it.isClickable} xy=(${it.centerX.toInt()},${it.centerY.toInt()})"
                    }

                    overlayHud?.updateStatus("STEP $step • THINKING", "Gemini analyzing screen...", "THINKING")
                    val decision = GeminiBrain.decideNextUiAction(
                        apiKey = apiKey,
                        model = model,
                        goal = userGoal,
                        screenSummary = screenDump.take(6000),
                        history = history
                    )

                    overlayHud?.updateStatus("STEP $step • ${decision.action}", decision.reason, "WORKING")
                    VideoQueueManager.appendLog(this@AutoTouchService, "🤖 Step $step [${decision.action}]: ${decision.reason}")
                    history.add("Step $step: ${decision.action} - ${decision.reason}")

                    if (decision.action.equals("DONE", true)) {
                        overlayHud?.updateStatus("✅ TASK DONE", decision.reason, "DONE")
                        VideoQueueManager.appendLog(this@AutoTouchService, "✅ Task Completed: ${decision.reason}")
                        delay(3000)
                        break
                    }

                    executeDecision(decision, nodes)
                    delay(3500)
                }
            } catch (e: Exception) {
                VideoQueueManager.appendLog(this@AutoTouchService, "❌ Agent Error: ${e.message}")
            } finally {
                overlayHud?.hide()
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

    private suspend fun executeDecision(decision: AgentDecision, nodes: List<UiNodeSnapshot>) {
        when (decision.action.uppercase()) {
            "CLICK_NODE" -> {
                val target = nodes.firstOrNull { it.index == decision.nodeIndex }
                if (target != null) animateAndTapNode(target, "Tap: ${target.text.ifEmpty { target.contentDesc }.take(14)}")
                else animateAndTapXY(decision.x, decision.y, "Tap XY")
            }
            "CLICK_XY" -> animateAndTapXY(decision.x, decision.y, "Tap XY")
            "TYPE" -> {
                val target = nodes.firstOrNull { it.index == decision.nodeIndex }
                    ?: nodes.firstOrNull { it.isEditable }
                if (target != null) {
                    animateAndTapNode(target, "Focus Input")
                    delay(800)
                }
                typeOrPasteTextEverywhere(target?.node, decision.textToType)
            }
            "SCROLL" -> performScrollDownGesture()
            "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private fun captureScreenNodes(): List<UiNodeSnapshot> {
        val list = mutableListOf<UiNodeSnapshot>()
        var counter = 0

        val roots = mutableListOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }
        try {
            for (win in windows) {
                val r = win.root
                if (r != null && r.packageName?.toString() != packageName && r !in roots) {
                    roots.add(r)
                }
            }
        } catch (_: Exception) {}

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null) return
            // अपने खुद के ओवरले (com.auradroid.agent) को स्कैन से बाहर रखें
            if (node.packageName?.toString() == packageName) return

            val cls = node.className?.toString() ?: ""
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.hintText?.toString()?.trim() ?: ""
            } else ""
            val viewId = node.viewIdResourceName?.substringAfter("/") ?: ""
            val clickable = node.isClickable
            val editable = node.isEditable || cls.contains("EditText", true)

            if (text.isNotEmpty() || desc.isNotEmpty() || hint.isNotEmpty() || clickable || editable) {
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
                            hintText = hint,
                            viewId = viewId,
                            isEditable = editable,
                            isClickable = clickable,
                            centerX = rect.exactCenterX(),
                            centerY = rect.exactCenterY()
                        )
                    )
                }
            }

            // हमेशा सभी चाइल्ड नोड्स के अंदर जाएं (चाहे पैरेंट का isVisibleToUser कुछ भी हो)
            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }

        for (r in roots) {
            traverse(r)
        }
        return list
    }

    private fun findFocusedOrEditableNode(nodes: List<UiNodeSnapshot>): AccessibilityNodeInfo? {
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
        try {
            for (win in windows) {
                win.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
            }
        } catch (_: Exception) {}
        return nodes.firstOrNull { it.isEditable || it.className.contains("EditText", true) }?.node
    }

    private suspend fun animateAndTapNode(snapshot: UiNodeSnapshot, label: String) {
        overlayHud?.moveBotAndShowTap(snapshot.centerX, snapshot.centerY, label)
        delay(500) // Cute AI Bot को उड़कर उस बटन तक पहुंचने का समय दें

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
        performTapGesture(snapshot.centerX, snapshot.centerY)
    }

    private suspend fun animateAndTapXY(x: Float, y: Float, label: String) {
        overlayHud?.moveBotAndShowTap(x, y, label)
        delay(500) // Cute AI Bot को उड़कर टारगेट कोऑर्डिनेट तक पहुंचने का समय दें
        performTapGesture(x, y)
    }

    private fun typeOrPasteTextEverywhere(targetNode: AccessibilityNodeInfo?, text: String) {
        // 1. पहले क्लिपबोर्ड में टेक्स्ट कॉपी करें
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ai_text", text))

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }

        val candidates = mutableListOf<AccessibilityNodeInfo>()
        targetNode?.let { candidates.add(it) }
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let {
            if (it !in candidates) candidates.add(it)
        }
        try {
            for (win in windows) {
                win.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let {
                    if (it !in candidates) candidates.add(it)
                }
            }
        } catch (_: Exception) {}

        var typedSuccess = false
        for (node in candidates) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                typedSuccess = true
                break
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                typedSuccess = true
                break
            }
        }

        // यदि किसी चाइल्ड EditText में अभी भी सेट न हुआ हो, तो स्क्रीन के सभी Editable नोड्स पर प्रयास करें
        if (!typedSuccess) {
            for (snap in captureScreenNodes().filter { it.isEditable || it.className.contains("EditText", true) }) {
                if (snap.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) ||
                    snap.node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                ) {
                    break
                }
            }
        }
    }

    fun performTapGesture(x: Float, y: Float) {
        val dm = resources.displayMetrics
        val maxX = (dm.widthPixels - 10).toFloat().coerceAtLeast(100f)
        val maxY = (dm.heightPixels - 10).toFloat().coerceAtLeast(100f)
        val safeX = x.coerceIn(10f, maxX)
        val safeY = y.coerceIn(10f, maxY)

        val path = Path().apply { moveTo(safeX, safeY) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 140)
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
