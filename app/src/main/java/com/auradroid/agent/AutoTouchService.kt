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
import android.util.DisplayMetrics
import android.view.WindowManager
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

    @Suppress("DEPRECATION")
    private fun getRealScreenDimensions(): Pair<Float, Float> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            Pair(bounds.width().toFloat(), bounds.height().toFloat())
        } else {
            val dm = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            Pair(dm.widthPixels.toFloat(), dm.heightPixels.toFloat())
        }
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
                    overlayHud?.updateStatus("QUEUE EMPTY", "Tap 'Reset Uploaded List' or add video!", "DONE")
                    VideoQueueManager.appendLog(
                        this@AutoTouchService,
                        "⚠️ No pending video! Tap 'Reset Uploaded List' in app to re-test."
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

                copyToClipboard(metadata.title)

                VideoQueueManager.appendLog(this@AutoTouchService, "✨ AI Title: ${metadata.title}")
                overlayHud?.updateStatus("STEP 3/5 • YOUTUBE", "Opening YouTube Upload Screen...", "WORKING")
                VideoQueueManager.appendLog(this@AutoTouchService, "🚀 Launching YouTube with video file...")

                launchYouTubeWithVideo(nextVideo)
                delay(5500)

                var nextClickCount = 0
                var titleFilled = false
                var descFilled = false

                val (realW, realH) = getRealScreenDimensions()
                VideoQueueManager.appendLog(this@AutoTouchService, "📱 Real Screen Size: ${realW.toInt()}x${realH.toInt()}")

                for (step in 1..25) {
                    if (!isActive) break
                    val nodes = captureScreenNodes()
                    val appNodes = nodes.filter { it.windowType != AccessibilityWindowInfo.TYPE_INPUT_METHOD }

                    // Sirf tab wait karein jab abhi Next bhi na daba ho aur screen khali ho
                    if (appNodes.isEmpty() && nextClickCount < 2) {
                        VideoQueueManager.appendLog(this@AutoTouchService, "👁️ Screen Read (Step $step): Waiting for UI...")
                        delay(2000)
                        continue
                    }

                    val nextBtn = appNodes.firstOrNull {
                        it.text.equals("Next", true) ||
                        it.contentDesc.equals("Next", true) ||
                        it.text.equals("Done", true) ||
                        it.contentDesc.equals("Done", true)
                    }

                    val isOnAddDetailsScreen = isAddDetailsScreenVisible(appNodes) || (nextClickCount >= 2 && nextBtn == null)

                    VideoQueueManager.appendLog(
                        this@AutoTouchService,
                        "👁️ Screen Read (Step $step): Nodes=${appNodes.size} | NextBtn=${nextBtn != null} | AddDetails=$isOnAddDetailsScreen"
                    )

                    // STEP A: Trimmer/Editor screen par 'Next' dabayein
                    if (!isOnAddDetailsScreen && !titleFilled && nextBtn != null) {
                        nextClickCount++
                        overlayHud?.updateStatus("STEP 3/5 • EDITOR ($nextClickCount)", "Screen Read: Tapping 'Next'...", "WORKING")
                        VideoQueueManager.appendLog(this@AutoTouchService, "👆 Trimmer/Editor Screen: Tapping 'Next' (#$nextClickCount)...")
                        animateAndPhysicalTapNode(nextBtn, "Tap: Next")
                        delay(4000)
                        continue
                    }

                    // STEP B: 'Add details' screen par 'Caption your Short' / Title fill karein
                    if (!titleFilled && (isOnAddDetailsScreen || nextClickCount >= 2)) {
                        copyToClipboard(metadata.title)

                        val captionNode = appNodes.firstOrNull {
                            val combined = "${it.text} ${it.contentDesc} ${it.hintText} ${it.viewId}"
                            combined.contains("Caption your Short", true) ||
                            combined.contains("Create a title", true) ||
                            combined.contains("Add a title", true) ||
                            combined.contains("caption", true)
                        } ?: appNodes.firstOrNull {
                            it.isEditable || it.supportsSetText
                        }

                        // 1080x2408 par 'Caption your Short' box ka exact location: X=550, Y=330
                        val capX = captionNode?.centerX ?: (realW * 0.52f)
                        val capY = captionNode?.centerY ?: (realH * 0.138f)

                        overlayHud?.updateStatus("STEP 4/5 • CAPTION", "Focusing 'Caption your Short'...", "WORKING")
                        VideoQueueManager.appendLog(this@AutoTouchService, "👆 Tapping 'Caption your Short' at (${capX.toInt()}, ${capY.toInt()})...")
                        animateAndTapXY(capX, capY, "Tap: Caption")
                        delay(2200)

                        overlayHud?.updateStatus("STEP 4/5 • TYPING", "Pasting Title...", "WORKING")
                        VideoQueueManager.appendLog(this@AutoTouchService, "✍️ Inserting AI Title: ${metadata.title}")

                        val inserted = insertCaptionSingleClean(metadata.title, capX, capY, realW, realH)
                        VideoQueueManager.appendLog(this@AutoTouchService, "✅ Single Title Insert Completed: $inserted")

                        titleFilled = true
                        delay(1800)

                        // Keyboard band karein taaki niche ka safed 'Upload Short' button poori tarah dikhe
                        overlayHud?.updateStatus("STEP 4/5 • KEYBOARD", "Closing keyboard...", "WORKING")
                        VideoQueueManager.appendLog(this@AutoTouchService, "⌨️ Closing keyboard...")
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        delay(2800)
                        continue
                    }

                    // STEP C: Long Video ke liye Description (Shorts mein skip ho jayega)
                    if (titleFilled && !descFilled) {
                        val addDescNode = appNodes.firstOrNull {
                            val combined = "${it.text} ${it.contentDesc} ${it.hintText}"
                            combined.contains("Add description", true)
                        }
                        if (addDescNode != null) {
                            overlayHud?.updateStatus("STEP 4/5 • DESCRIPTION", "Filling AI Description...", "WORKING")
                            animateAndPhysicalTapNode(addDescNode, "Tap: Description")
                            delay(2000)
                            insertCaptionSingleClean(metadata.description, realW * 0.5f, realH * 0.25f, realW, realH)
                            descFilled = true
                            delay(1800)
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            delay(2200)
                            continue
                        } else {
                            descFilled = true
                        }
                    }

                    // STEP D: Final White 'Upload Short' Button ke Exact Center (X=540, Y=2321 on 1080x2408) par tap karein!
                    if (titleFilled) {
                        if (isKeyboardOpen()) {
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            delay(2500)
                        }

                        // 1080x2408 screen par safed 'Upload Short' button Y=2263 se Y=2379 tak hai (Center = 2321 = 96.4% of 2408)
                        val exactUploadX = realW * 0.50f
                        val exactUploadYPoints = listOf(
                            realH * 0.964f, // 2321 px (Exact Center of white pill)
                            realH * 0.958f, // 2306 px (Upper-middle of white pill)
                            realH * 0.968f  // 2331 px (Lower-middle of white pill)
                        )

                        for (attempt in 1..3) {
                            val currentNodes = captureScreenNodes().filter {
                                it.windowType != AccessibilityWindowInfo.TYPE_INPUT_METHOD
                            }

                            // Check karein ki kya hum YouTube ke main Home/Shorts feed par aa chuke hain
                            val onMainYouTubeFeed = currentNodes.any {
                                it.text.equals("Subscriptions", true) ||
                                it.contentDesc.contains("Subscriptions", true) ||
                                it.text.equals("Home", true) ||
                                it.contentDesc.equals("Home", true)
                            } && !isAddDetailsScreenVisible(currentNodes)

                            if (onMainYouTubeFeed && attempt > 1) {
                                VideoQueueManager.appendLog(this@AutoTouchService, "✅ Verified: Left 'Add details' & reached YouTube feed!")
                                break
                            }

                            // Agar 'Select audience' screen aayi ho toh 'No, it's not made for kids' chunein
                            val notForKids = currentNodes.firstOrNull {
                                "${it.text} ${it.contentDesc}".contains("not made for kids", true)
                            }
                            if (notForKids != null) {
                                animateAndPhysicalTapNode(notForKids, "Not For Kids")
                                delay(2000)
                            }

                            val uploadBtnNode = currentNodes.firstOrNull {
                                val c = "${it.text} ${it.contentDesc} ${it.hintText} ${it.viewId}"
                                it.centerY > realH * 0.88f && (
                                    c.contains("Upload Short", true) ||
                                    it.text.equals("Upload", true) ||
                                    it.contentDesc.equals("Upload", true) ||
                                    c.contains("upload_bottom_button", true)
                                )
                            }

                            val tapX = uploadBtnNode?.centerX ?: exactUploadX
                            val tapY = if (uploadBtnNode != null && uploadBtnNode.centerY >= realH * 0.94f) {
                                uploadBtnNode.centerY
                            } else {
                                exactUploadYPoints[(attempt - 1) % exactUploadYPoints.size]
                            }

                            overlayHud?.updateStatus(
                                "STEP 5/5 • UPLOAD (#$attempt)",
                                "Tapping Upload Short (${tapX.toInt()}, ${tapY.toInt()})...",
                                "WORKING"
                            )
                            VideoQueueManager.appendLog(
                                this@AutoTouchService,
                                "🎯 Tapping 'Upload Short' Center at (${tapX.toInt()}, ${tapY.toInt()}) [Attempt #$attempt]..."
                            )

                            // Asli Physical Finger Touch سیدھا safed button ke beechon-beech
                            animateAndTapXY(tapX, tapY, "Tap: Upload Short")
                            delay(600)

                            if (uploadBtnNode != null) {
                                clickNodeAndParents(uploadBtnNode.node)
                            }

                            delay(3500)
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

    private suspend fun insertCaptionSingleClean(
        text: String,
        boxX: Float,
        boxY: Float,
        realW: Float,
        realH: Float
    ): Boolean {
        copyToClipboard(text)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }

        // Method 1: Direct ACTION_SET_TEXT on focused input
        val focusedInput = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedInput != null) {
            focusedInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        delay(1000)
        if (isTitleAlreadyPresentOnScreen(text)) {
            VideoQueueManager.appendLog(this@AutoTouchService, "👁️ Screen Read: Title set via Direct SetText.")
            return true
        }

        // Method 2: Tap Keyboard (Gboard) Clipboard Chip (Bilkul 1000281411.mp4 video ki tarah!)
        val allNodes = captureScreenNodes()
        val keyboardNodes = allNodes.filter {
            it.windowType == AccessibilityWindowInfo.TYPE_INPUT_METHOD
        }
        val titlePrefix = text.take(10).trim()
        val clipboardChip = keyboardNodes.firstOrNull {
            (titlePrefix.isNotEmpty() && (it.text.contains(titlePrefix, true) || it.contentDesc.contains(titlePrefix, true))) ||
            it.text.equals("Paste", true) ||
            it.contentDesc.equals("Paste", true) ||
            it.text.contains("चिपकाएं")
        }

        if (clipboardChip != null) {
            VideoQueueManager.appendLog(this@AutoTouchService, "📋 Tapping Keyboard Clipboard Chip at (${clipboardChip.centerX.toInt()}, ${clipboardChip.centerY.toInt()})...")
            animateAndPhysicalTapNode(clipboardChip, "Tap: Clipboard")
            delay(1600)
            return true
        }

        // Method 3: Agar Keyboard khula hai aur Gboard chip node scan mein na aaya ho, toh Gboard Clipboard suggestion bar (X=490, Y=1465 on 1080x2408) ya Long-Press Paste karein
        if (!isTitleAlreadyPresentOnScreen(text)) {
            VideoQueueManager.appendLog(this@AutoTouchService, "📋 Long-pressing Caption box for Paste popup...")
            overlayHud?.moveBotAndShowTap(boxX, boxY, "Hold: Paste")
            performLongPressGesture(boxX, boxY, 700L)
            delay(1500)

            val pastePopupBtn = captureScreenNodes().firstOrNull {
                it.text.equals("Paste", true) ||
                it.contentDesc.equals("Paste", true) ||
                it.text.contains("चिपकाएं", true) ||
                it.contentDesc.contains("चिपकाएं", true)
            }

            if (pastePopupBtn != null) {
                VideoQueueManager.appendLog(this@AutoTouchService, "👆 Tapping native 'Paste' popup button!")
                animateAndPhysicalTapNode(pastePopupBtn, "Tap: Paste")
                delay(1400)
                return true
            }
        }

        return isTitleAlreadyPresentOnScreen(text)
    }

    private fun isTitleAlreadyPresentOnScreen(expectedTitle: String): Boolean {
        val prefix = expectedTitle.take(10).trim()
        val appNodes = captureScreenNodes().filter {
            it.windowType != AccessibilityWindowInfo.TYPE_INPUT_METHOD
        }
        val hasTitleWords = prefix.isNotEmpty() && appNodes.any {
            it.text.contains(prefix, true) || it.contentDesc.contains(prefix, true)
        }
        val placeholderGone = appNodes.none {
            it.text.contains("Caption your Short", true) || it.hintText.contains("Caption your Short", true)
        } && appNodes.any {
            it.text.equals("Title", true) || it.contentDesc.contains("Title", true)
        }

        return hasTitleWords || placeholderGone
    }

    private fun isAddDetailsScreenVisible(appNodes: List<UiNodeSnapshot>): Boolean {
        return appNodes.any {
            val c = "${it.text} ${it.contentDesc} ${it.hintText}"
            c.contains("Add details", true) ||
            c.contains("Caption your Short", true) ||
            c.contains("Upload Short", true) ||
            c.contains("Select audience", true) ||
            c.contains("Visibility", true)
        }
    }

    private fun isKeyboardOpen(): Boolean {
        return try {
            windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        } catch (_: Exception) {
            false
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ai_title", text))
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
                val (realW, realH) = getRealScreenDimensions()
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

                    overlayHud?.updateStatus("STEP $step • THINKING", "Gemini reading screen...", "THINKING")
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

                    executeDecision(decision, nodes, realW, realH)
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

    private suspend fun executeDecision(decision: AgentDecision, nodes: List<UiNodeSnapshot>, realW: Float, realH: Float) {
        when (decision.action.uppercase()) {
            "CLICK_NODE" -> {
                val target = nodes.firstOrNull { it.index == decision.nodeIndex }
                if (target != null) animateAndPhysicalTapNode(target, "Tap: ${target.text.ifEmpty { target.contentDesc }.take(14)}")
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
                insertCaptionSingleClean(decision.textToType, tx, ty, realW, realH)
            }
            "SCROLL" -> performScrollDownGesture()
            "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    /**
     * Exact Working Scanner from 1000281411.mp4
     */
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

    private fun clickNodeAndParents(node: AccessibilityNodeInfo): Boolean {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 5) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            parent = parent.parent
            depth++
        }
        return false
    }

    private suspend fun animateAndPhysicalTapNode(snapshot: UiNodeSnapshot, label: String) {
        overlayHud?.moveBotAndShowTap(snapshot.centerX, snapshot.centerY, label)
        delay(450)
        performTapGesture(snapshot.centerX, snapshot.centerY)
        delay(200)
        clickNodeAndParents(snapshot.node)
    }

    private suspend fun animateAndTapXY(x: Float, y: Float, label: String) {
        overlayHud?.moveBotAndShowTap(x, y, label)
        delay(450)
        performTapGesture(x, y)
    }

    fun performTapGesture(x: Float, y: Float) {
        val (realW, realH) = getRealScreenDimensions()
        val safeX = x.coerceIn(5f, realW - 5f)
        val safeY = y.coerceIn(5f, realH - 5f)

        val path = Path().apply { moveTo(safeX, safeY) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 150)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    private fun performLongPressGesture(x: Float, y: Float, durationMs: Long) {
        val (realW, realH) = getRealScreenDimensions()
        val safeX = x.coerceIn(5f, realW - 5f)
        val safeY = y.coerceIn(5f, realH - 5f)

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
