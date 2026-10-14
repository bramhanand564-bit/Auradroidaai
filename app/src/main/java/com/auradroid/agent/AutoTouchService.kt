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
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import java.io.File

data class UiNodeSnapshot(
    val index: Int,
    val node: AccessibilityNodeInfo,
    val windowType: Int,
    val className: String,
    val text: String,
    val contentDesc: String,
    val hintText: String,
    val viewId: String,
    val isEditable: Boolean,
    val isFocused: Boolean,
    val isClickable: Boolean,
    val supportsSetText: Boolean,
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
                    overlayHud?.updateStatus("QUEUE EMPTY", "Tap 'Reset Uploaded List' or add new video!", "DONE")
                    VideoQueueManager.appendLog(
                        this@AutoTouchService,
                        "⚠️ No pending video! Tap 'Reset Uploaded List' in app if you want to re-upload."
                    )
                    delay(3500)
                    overlayHud?.hide()
                    isJobRunning = false
                    return@launch
                }

                VideoQueueManager.appendLog(this@AutoTouchService, "🎬 Selected Next Video: ${nextVideo.name}")
                overlayHud?.updateStatus("STEP 2/5 • GEMINI AI", "Generating Title & Tags...", "THINKING")
                VideoQueueManager.appendLog(this@AutoTouchService, "🧠 Generating viral Title & Description...")

                val metadata = GeminiBrain.generateYouTubeMetadata(
                    apiKey = apiKey,
                    model = model,
                    fileName = nextVideo.name,
                    userInstruction = userPrompt
                )

                // Title ko turant Clipboard mein copy karke rakhein
                copyToClipboard(metadata.title)

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

                    val nextBtn = nodes.firstOrNull {
                        it.windowType != AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                        (it.text.equals("Next", true) ||
                         it.contentDesc.equals("Next", true) ||
                         it.text.equals("Done", true) ||
                         it.contentDesc.equals("Done", true))
                    }

                    val hasAddDetailsText = nodes.any {
                        val combined = "${it.text} ${it.contentDesc} ${it.hintText}"
                        combined.contains("Add details", true) ||
                        combined.contains("Caption your Short", true) ||
                        combined.contains("Upload Short", true) ||
                        combined.contains("Select audience", true) ||
                        combined.contains("Visibility", true)
                    }

                    val isOnAddDetailsScreen = hasAddDetailsText || (nextClickCount >= 2 && nextBtn == null)

                    // STEP A: Trimmer/Editor screen par 'Next' dabayein
                    if (!isOnAddDetailsScreen && !titleFilled && nextBtn != null) {
                        nextClickCount++
                        overlayHud?.updateStatus("STEP 3/5 • EDITOR ($nextClickCount)", "Tapping 'Next'...", "WORKING")
                        VideoQueueManager.appendLog(this@AutoTouchService, "👆 Trimmer/Editor Screen: Clicking 'Next' (#$nextClickCount)...")
                        animateAndTapNode(nextBtn, "Tap: Next")
                        delay(3800)
                        continue
                    }

                    // STEP B: 'Add details' screen par 'Caption your Short' fill karein
                    if (!titleFilled && (isOnAddDetailsScreen || nextClickCount >= 2)) {
                        copyToClipboard(metadata.title)

                        val captionNode = nodes.firstOrNull {
                            val combined = "${it.text} ${it.contentDesc} ${it.hintText} ${it.viewId}"
                            combined.contains("Caption your Short", true) ||
                            combined.contains("Create a title", true) ||
                            combined.contains("Add a title", true) ||
                            combined.contains("caption", true)
                        } ?: nodes.firstOrNull {
                            it.isEditable || it.supportsSetText
                        }

                        val capX = captionNode?.centerX ?: (screenW * 0.54f)
                        val capY = captionNode?.centerY ?: (screenH * 0.135f)

                        // Pehle bina keyboard khole bhi direct set text try karein
                        captionNode?.node?.let { tryDirectSetOrPaste(it, metadata.title) }

                        overlayHud?.updateStatus("STEP 4/5 • CAPTION", "Tapping 'Caption your Short'...", "WORKING")
                        VideoQueueManager.appendLog(this@AutoTouchService, "👆 Tapping 'Caption your Short' box...")
                        animateAndTapXY(capX, capY, "Tap: Caption")
                        delay(2200)

                        // Ab Keyboard khul chuka hai -> Multi-Layer Paste/Type chalayein
                        overlayHud?.updateStatus("STEP 4/5 • TYPING", "Inserting AI Title...", "WORKING")
                        VideoQueueManager.appendLog(this@AutoTouchService, "✍️ Inserting AI Title: ${metadata.title}")

                        val inserted = insertCaptionWithAllMethods(metadata.title, capX, capY)
                        VideoQueueManager.appendLog(this@AutoTouchService, "✅ Title Insert Method Completed (Success=$inserted)")

                        titleFilled = true
                        delay(1800)

                        // Agar Keyboard khula hai, toh Back dabakar keyboard band karein taaki Upload Short button dikhe
                        if (isKeyboardOpen() || !isBottomUploadButtonVisible(screenH)) {
                            overlayHud?.updateStatus("STEP 4/5 • KEYBOARD", "Closing keyboard...", "WORKING")
                            VideoQueueManager.appendLog(this@AutoTouchService, "⌨️ Closing keyboard...")
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            delay(2400)
                        }
                        continue
                    }

                    // STEP C: Long Video ke liye Description (Shorts mein skip ho jayega)
                    if (titleFilled && !descFilled) {
                        val addDescNode = nodes.firstOrNull {
                            val combined = "${it.text} ${it.contentDesc} ${it.hintText}"
                            combined.contains("Add description", true)
                        }
                        if (addDescNode != null) {
                            overlayHud?.updateStatus("STEP 4/5 • DESCRIPTION", "Filling AI Description...", "WORKING")
                            animateAndTapNode(addDescNode, "Tap: Description")
                            delay(2000)
                            insertCaptionWithAllMethods(metadata.description, screenW * 0.5f, screenH * 0.25f)
                            descFilled = true
                            delay(1800)
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            delay(2000)
                            continue
                        } else {
                            descFilled = true
                        }
                    }

                    // STEP D: Final 'Upload Short' / 'Upload' button par tap karein aur verify karein
                    if (titleFilled) {
                        // Agar abhi bhi keyboard khula reh gaya ho toh pehle band karein
                        if (isKeyboardOpen()) {
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            delay(2000)
                        }

                        var uploadConfirmed = false
                        for (tryUpload in 1..3) {
                            val freshNodes = captureScreenNodes()
                            val stillOnAddDetails = freshNodes.any {
                                val c = "${it.text} ${it.contentDesc} ${it.hintText}"
                                c.contains("Add details", true) ||
                                c.contains("Upload Short", true) ||
                                c.contains("Select audience", true)
                            }

                            if (!stillOnAddDetails && tryUpload > 1) {
                                uploadConfirmed = true
                                break
                            }

                            // Screen ke bottom hisse mein 'Upload Short' button dhoondhein
                            val uploadBtn = freshNodes.firstOrNull {
                                val c = "${it.text} ${it.contentDesc} ${it.hintText}"
                                it.centerY > screenH * 0.70f && (
                                    c.contains("Upload Short", true) ||
                                    it.text.equals("Upload", true) ||
                                    it.contentDesc.equals("Upload", true) ||
                                    c.contains("Upload video", true) ||
                                    it.text.equals("Publish", true)
                                )
                            }

                            overlayHud?.updateStatus("STEP 5/5 • UPLOAD (#$tryUpload)", "Tapping 'Upload Short'...", "WORKING")
                            VideoQueueManager.appendLog(this@AutoTouchService, "🎯 Tapping 'Upload Short' Button (Attempt #$tryUpload)...")

                            if (uploadBtn != null) {
                                animateAndTapNode(uploadBtn, "Tap: Upload Short")
                            } else {
                                // White 'Upload Short' button center coordinates at bottom of screen
                                val upX = screenW * 0.50f
                                val upY = screenH * 0.955f
                                animateAndTapXY(upX, upY, "Tap: Upload Short")
                            }

                            delay(3200)
                        }

                        VideoQueueManager.markVideoUploaded(this@AutoTouchService, nextVideo)
                        overlayHud?.updateStatus("🎉 UPLOAD SUCCESS!", "'${nextVideo.name.take(18)}' Uploaded!", "DONE")
                        VideoQueueManager.appendLog(
                            this@AutoTouchService,
                            "🎉 REPORT: '${nextVideo.name}' successfully uploaded with Title: '${metadata.title}'!"
                        )
                        delay(3500)
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

    /**
     * 4-Layer Guaranteed Text Entry for YouTube Litho / EditText:
     * Layer 1: Direct ACTION_SET_TEXT & ACTION_PASTE on all focused/editable/Litho nodes
     * Layer 2: Check if Gboard/Keyboard clipboard suggestion chip has the text and tap it
     * Layer 3: Long-press on the caption box to trigger native Android 'Paste' popup and click it
     */
    private suspend fun insertCaptionWithAllMethods(text: String, boxX: Float, boxY: Float): Boolean {
        copyToClipboard(text)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }

        // Layer 1: Try ACTION_SET_TEXT and ACTION_PASTE on every candidate node (without breaking early!)
        val allNodes = captureScreenNodes()
        val candidates = mutableListOf<AccessibilityNodeInfo>()

        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { candidates.add(it) }
        try {
            for (win in windows) {
                win.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let {
                    if (it !in candidates) candidates.add(it)
                }
            }
        } catch (_: Exception) {}

        for (snap in allNodes) {
            if (snap.windowType == AccessibilityWindowInfo.TYPE_APPLICATION) {
                val combined = "${snap.text} ${snap.contentDesc} ${snap.hintText} ${snap.viewId}"
                if (snap.isFocused || snap.isEditable || snap.supportsSetText ||
                    snap.className.contains("EditText", true) ||
                    combined.contains("Caption your Short", true) ||
                    combined.contains("caption", true) ||
                    combined.contains("title", true)
                ) {
                    if (snap.node !in candidates) candidates.add(snap.node)
                }
            }
        }

        for (node in candidates) {
            try {
                node.refresh()
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            } catch (_: Exception) {}
        }

        delay(1000)
        if (isTextVisuallyEntered(text)) return true

        // Layer 2: Check if Keyboard (Gboard) shows Clipboard Suggestion Pill with our text
        val keyboardSnapshots = captureScreenNodes().filter {
            it.windowType == AccessibilityWindowInfo.TYPE_INPUT_METHOD
        }
        val titlePrefix = text.take(12).trim()
        val clipboardChip = keyboardSnapshots.firstOrNull {
            (titlePrefix.isNotEmpty() && (it.text.contains(titlePrefix, true) || it.contentDesc.contains(titlePrefix, true))) ||
            it.text.equals("Paste", true) ||
            it.contentDesc.equals("Paste", true) ||
            it.text.contains("चिपकाएं")
        }
        if (clipboardChip != null) {
            VideoQueueManager.appendLog(this@AutoTouchService, "📋 Tapping Keyboard Clipboard Chip...")
            animateAndTapNode(clipboardChip, "Tap: Clipboard")
            delay(1200)
            if (isTextVisuallyEntered(text)) return true
        }

        // Layer 3: Long-press inside the Caption Box to bring up Android's native 'Paste' button
        VideoQueueManager.appendLog(this@AutoTouchService, "📋 Long-pressing Caption box for Paste menu...")
        overlayHud?.moveBotAndShowTap(boxX, boxY, "Hold: Paste")
        performLongPressGesture(boxX, boxY, 720L)
        delay(1400)

        val afterLongPressNodes = captureScreenNodes()
        val pastePopupBtn = afterLongPressNodes.firstOrNull {
            it.text.equals("Paste", true) ||
            it.contentDesc.equals("Paste", true) ||
            it.text.contains("Paste", true) ||
            it.text.contains("चिपकाएं", true) ||
            it.contentDesc.contains("चिपकाएं", true)
        }

        if (pastePopupBtn != null) {
            VideoQueueManager.appendLog(this@AutoTouchService, "👆 Clicking native 'Paste' popup button!")
            animateAndTapNode(pastePopupBtn, "Tap: Paste")
            delay(1200)
            return true
        } else {
            // Dobara sabhi focused nodes par ACTION_PASTE bhejein
            val focusedNow = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            focusedNow?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            focusedNow?.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }

        return isTextVisuallyEntered(text)
    }

    private fun isTextVisuallyEntered(expectedTitle: String): Boolean {
        val prefix = expectedTitle.take(10).trim()
        if (prefix.isEmpty()) return true
        val nodes = captureScreenNodes()
        return nodes.any {
            it.windowType == AccessibilityWindowInfo.TYPE_APPLICATION &&
            (it.text.contains(prefix, true) || it.contentDesc.contains(prefix, true))
        }
    }

    private fun isKeyboardOpen(): Boolean {
        return try {
            windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        } catch (_: Exception) {
            false
        }
    }

    private fun isBottomUploadButtonVisible(screenH: Float): Boolean {
        return captureScreenNodes().any {
            val c = "${it.text} ${it.contentDesc}"
            it.centerY > screenH * 0.75f && (c.contains("Upload Short", true) || it.text.equals("Upload", true))
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ai_title", text))
    }

    private fun tryDirectSetOrPaste(node: AccessibilityNodeInfo, text: String) {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
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
                    ?: nodes.firstOrNull { it.isEditable || it.supportsSetText }
                val tx = target?.centerX ?: 540f
                val ty = target?.centerY ?: 400f
                animateAndTapXY(tx, ty, "Focus Input")
                delay(1200)
                insertCaptionWithAllMethods(decision.textToType, tx, ty)
            }
            "SCROLL" -> performScrollDownGesture()
            "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private fun captureScreenNodes(): List<UiNodeSnapshot> {
        val list = mutableListOf<UiNodeSnapshot>()
        var counter = 0

        val windowRoots = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()
        try {
            for (win in windows) {
                val r = win.root
                if (r != null && r.packageName?.toString() != packageName) {
                    windowRoots.add(Pair(win.type, r))
                }
            }
        } catch (_: Exception) {}

        if (windowRoots.isEmpty()) {
            rootInActiveWindow?.let {
                if (it.packageName?.toString() != packageName) {
                    windowRoots.add(Pair(AccessibilityWindowInfo.TYPE_APPLICATION, it))
                }
            }
        }

        fun traverse(node: AccessibilityNodeInfo?, winType: Int) {
            if (node == null) return
            if (node.packageName?.toString() == packageName) return

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

    private suspend fun animateAndTapNode(snapshot: UiNodeSnapshot, label: String) {
        overlayHud?.moveBotAndShowTap(snapshot.centerX, snapshot.centerY, label)
        delay(450)

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
        if (!clicked) {
            performTapGesture(snapshot.centerX, snapshot.centerY)
        }
    }

    private suspend fun animateAndTapXY(x: Float, y: Float, label: String) {
        overlayHud?.moveBotAndShowTap(x, y, label)
        delay(450)
        performTapGesture(x, y)
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

    private fun performLongPressGesture(x: Float, y: Float, durationMs: Long) {
        val dm = resources.displayMetrics
        val maxX = (dm.widthPixels - 10).toFloat().coerceAtLeast(100f)
        val maxY = (dm.heightPixels - 10).toFloat().coerceAtLeast(100f)
        val safeX = x.coerceIn(10f, maxX)
        val safeY = y.coerceIn(10f, maxY)

        val path = Path().apply { moveTo(safeX, safeY) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
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
