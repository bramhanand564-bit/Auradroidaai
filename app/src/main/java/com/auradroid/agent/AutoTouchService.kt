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

                val dm = resources.displayMetrics
                val screenW = dm.widthPixels.toFloat()
                val screenH = dm.heightPixels.toFloat()

                for (step in 1..28) {
                    if (!isActive) break
                    val nodes = captureScreenNodes()
                    val appNodes = nodes.filter { it.windowType != AccessibilityWindowInfo.TYPE_INPUT_METHOD }

                    if (appNodes.isEmpty()) {
                        VideoQueueManager.appendLog(this@AutoTouchService, "👁️ Screen Read (Step $step): Waiting for UI window...")
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

                    // Har step par live screen status log karein
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
                        delay(3800)
                        continue
                    }

                    // STEP B: 'Add details' screen par 'Caption your Short' / Title sirf EK baar fill karein
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

                        val capX = captionNode?.centerX ?: (screenW * 0.54f)
                        val capY = captionNode?.centerY ?: (screenH * 0.14f)

                        overlayHud?.updateStatus("STEP 4/5 • CAPTION", "Screen Read: Focusing Caption Box...", "WORKING")
                        VideoQueueManager.appendLog(this@AutoTouchService, "👆 Tapping 'Caption your Short' box...")
                        animateAndTapXY(capX, capY, "Tap: Caption")
                        delay(2200)

                        overlayHud?.updateStatus("STEP 4/5 • TYPING", "Pasting Title (Single Clean Paste)...", "WORKING")
                        VideoQueueManager.appendLog(this@AutoTouchService, "✍️ Inserting AI Title: ${metadata.title}")

                        val inserted = insertCaptionSingleClean(metadata.title, capX, capY)
                        VideoQueueManager.appendLog(this@AutoTouchService, "✅ Single Title Insert Verified: $inserted")

                        titleFilled = true
                        delay(1800)

                        // Keyboard band karein taaki niche ka 'Upload Short' button poori tarah active ho jaye
                        if (isKeyboardOpen() || !isBottomUploadButtonVisible(screenH)) {
                            overlayHud?.updateStatus("STEP 4/5 • KEYBOARD", "Closing keyboard...", "WORKING")
                            VideoQueueManager.appendLog(this@AutoTouchService, "⌨️ Closing keyboard...")
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            delay(2800)
                        }
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
                            insertCaptionSingleClean(metadata.description, screenW * 0.5f, screenH * 0.25f)
                            descFilled = true
                            delay(1800)
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            delay(2200)
                            continue
                        } else {
                            descFilled = true
                        }
                    }

                    // STEP D: Final 'Upload Short' / 'Upload' button par PHYSICAL TAP karein aur Screen Read se verify karein
                    if (titleFilled) {
                        if (isKeyboardOpen()) {
                            VideoQueueManager.appendLog(this@AutoTouchService, "⌨️ Hiding open keyboard before Upload tap...")
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            delay(2500)
                        }

                        var uploadConfirmed = false

                        for (attempt in 1..4) {
                            val currentAppNodes = captureScreenNodes().filter {
                                it.windowType != AccessibilityWindowInfo.TYPE_INPUT_METHOD
                            }

                            // Agar screen par nodes dikh rahe hain aur 'Add details' / 'Upload Short' ab hat chuka hai -> Upload Started!
                            if (currentAppNodes.isNotEmpty() && !isAddDetailsScreenVisible(currentAppNodes) && attempt > 1) {
                                VideoQueueManager.appendLog(
                                    this@AutoTouchService,
                                    "👁️ Screen Read Verify: 'Add details' screen closed! Upload has started."
                                )
                                uploadConfirmed = true
                                break
                            }

                            // Agar YouTube ne 'Select audience' popup khol diya ho toh 'No, it's not made for kids' chunein
                            val notForKids = currentAppNodes.firstOrNull {
                                val c = "${it.text} ${it.contentDesc}"
                                c.contains("not made for kids", true)
                            }
                            if (notForKids != null) {
                                VideoQueueManager.appendLog(this@AutoTouchService, "👆 Selecting 'No, it's not made for kids'...")
                                animateAndPhysicalTapNode(notForKids, "Not For Kids")
                                delay(2000)
                            }

                            // Screen ke niche wale hisse mein 'Upload Short' / 'Upload' node dhoondhein
                            val uploadBtn = currentAppNodes.firstOrNull {
                                val c = "${it.text} ${it.contentDesc} ${it.hintText} ${it.viewId}"
                                it.centerY > screenH * 0.72f && (
                                    c.contains("Upload Short", true) ||
                                    it.text.equals("Upload", true) ||
                                    it.contentDesc.equals("Upload", true) ||
                                    c.contains("Upload video", true) ||
                                    it.text.equals("Publish", true) ||
                                    c.contains("upload_bottom_button", true)
                                )
                            }

                            overlayHud?.updateStatus(
                                "STEP 5/5 • UPLOAD (#$attempt)",
                                "Physical Tap on 'Upload Short'...",
                                "WORKING"
                            )

                            if (uploadBtn != null) {
                                VideoQueueManager.appendLog(
                                    this@AutoTouchService,
                                    "🎯 Physical Tap on 'Upload Short' at (${uploadBtn.centerX.toInt()}, ${uploadBtn.centerY.toInt()}) [Attempt #$attempt]..."
                                )
                                animateAndPhysicalTapNode(uploadBtn, "Tap: Upload Short")
                            } else {
                                val upX = screenW * 0.50f
                                val upY = screenH * 0.948f
                                VideoQueueManager.appendLog(
                                    this@AutoTouchService,
                                    "🎯 Physical Tap on Bottom 'Upload Short' Coordinates (${upX.toInt()}, ${upY.toInt()}) [Attempt #$attempt]..."
                                )
                                animateAndTapXY(upX, upY, "Tap: Upload Short")
                            }

                            delay(3800)

                            // Tap ke baad dobara screen read karke check karein
                            val afterTapNodes = captureScreenNodes().filter {
                                it.windowType != AccessibilityWindowInfo.TYPE_INPUT_METHOD
                            }
                            if (afterTapNodes.isNotEmpty() && !isAddDetailsScreenVisible(afterTapNodes)) {
                                VideoQueueManager.appendLog(
                                    this@AutoTouchService,
                                    "✅ Screen Read Verified: YouTube left 'Add details' page & started uploading!"
                                )
                                uploadConfirmed = true
                                break
                            }
                        }

                        if (uploadConfirmed) {
                            VideoQueueManager.markVideoUploaded(this@AutoTouchService, nextVideo)
                            overlayHud?.updateStatus("🎉 UPLOAD SUCCESS!", "'${nextVideo.name.take(18)}' Uploading!", "DONE")
                            VideoQueueManager.appendLog(
                                this@AutoTouchService,
                                "🎉 REPORT: '${nextVideo.name}' successfully submitted for Public upload with Title: '${metadata.title}'!"
                            )
                        } else {
                            overlayHud?.updateStatus("⚠️ CHECK YOUTUBE", "Tapped Upload 4 times, please check screen.", "DONE")
                            VideoQueueManager.appendLog(
                                this@AutoTouchService,
                                "⚠️ Tapped 'Upload Short' 4 times, but 'Add details' screen remained visible."
                            )
                        }

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
     * Single Clean Paste (Prevents Double-Pasting!):
     * Har step ke baad screen read karke check karta hai ki kya text box mein aa chuka hai.
     * Agar Method 1 ya Method 2 se text aa gaya, toh agla method bilkul nahi chalayega!
     */
    private suspend fun insertCaptionSingleClean(text: String, boxX: Float, boxY: Float): Boolean {
        copyToClipboard(text)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }

        // Method 1: Try ACTION_SET_TEXT (ye text ko replace karta hai, double nahi karta)
        val focusedInput = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedInput != null) {
            focusedInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } else {
            val editCandidate = captureScreenNodes().firstOrNull {
                it.windowType == AccessibilityWindowInfo.TYPE_APPLICATION &&
                (it.isFocused || it.isEditable || it.supportsSetText)
            }
            editCandidate?.node?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        delay(1200)
        if (isTitleAlreadyPresentOnScreen(text)) {
            VideoQueueManager.appendLog(this@AutoTouchService, "👁️ Screen Read: Title entered via Method 1 (Direct SetText).")
            return true
        }

        // Method 2: Tap Gboard / Keyboard Clipboard Suggestion Chip ONLY ONCE
        val keyboardNodes = captureScreenNodes().filter {
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
            VideoQueueManager.appendLog(this@AutoTouchService, "📋 Tapping Keyboard Clipboard Chip once...")
            animateAndPhysicalTapNode(clipboardChip, "Tap: Clipboard")
            delay(1600)
            // Keyboard chip tap hone par text 100% paste ho jata hai, isliye ab Long-Press Paste NAHI karenge!
            return true
        }

        // Method 3: Sirf tab Long-press Paste karein jab Keyboard Clipboard Chip NA mili ho aur box abhi bhi khali ho
        if (!isTitleAlreadyPresentOnScreen(text)) {
            VideoQueueManager.appendLog(this@AutoTouchService, "📋 Long-pressing Caption box for native Paste popup...")
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
                VideoQueueManager.appendLog(this@AutoTouchService, "👆 Tapping native 'Paste' popup button once!")
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
            it.windowType == AccessibilityWindowInfo.TYPE_APPLICATION
        }
        // Check 1: Kya humare title ke shuruati words screen par dikh rahe hain?
        val hasTitleWords = prefix.isNotEmpty() && appNodes.any {
            it.text.contains(prefix, true) || it.contentDesc.contains(prefix, true)
        }
        // Check 2: Jab YouTube Shorts mein caption likh jata hai, toh 'Caption your Short' placeholder hat kar 'Title' label aa jata hai
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
            c.contains("Select audience", true)
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
            it.centerY > screenH * 0.72f && (c.contains("Upload Short", true) || it.text.equals("Upload", true))
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
                insertCaptionSingleClean(decision.textToType, tx, ty)
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

    /**
     * Hamesha Physical Finger Tap (performTapGesture) + Accessibility Click dono bhejega!
     * Isse YouTube ka 'Upload Short' button 100% dabega.
     */
    private suspend fun animateAndPhysicalTapNode(snapshot: UiNodeSnapshot, label: String) {
        overlayHud?.moveBotAndShowTap(snapshot.centerX, snapshot.centerY, label)
        delay(450)

        // 1. Pehle asli Physical Finger Touch Gesture bhejein (sabse bharosemand)
        performTapGesture(snapshot.centerX, snapshot.centerY)

        // 2. Saath hi Accessibility click backup bhi bhejein
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
        val stroke = GestureDescription.StrokeDescription(path, 0, 150)
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
