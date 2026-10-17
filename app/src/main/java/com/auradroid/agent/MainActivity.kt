package com.auradroid.agent

import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.speech.RecognizerIntent
import android.util.Base64
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Calendar
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        var onLogUpdated: ((String) -> Unit)? = null
        // Optional observer used by persisted task-state updates.
        var onTaskStatusUpdated: (() -> Unit)? = null
        var onBrowserUrlChanged: ((String) -> Unit)? = null
    }

    // Header Views
    private lateinit var tvActiveSubtitle: TextView
    private lateinit var btnToggleFloatingPod: Button
    private lateinit var tvServiceBadge: TextView

    // 5 Tab Containers
    private lateinit var tabChatLayout: ScrollView
    private lateinit var tabWebLayout: LinearLayout
    private lateinit var tabPipelinesLayout: ScrollView
    private lateinit var tabDoctorLayout: ScrollView
    private lateinit var tabSettingsLayout: ScrollView

    // Bottom Navigation Buttons
    private lateinit var navTabChat: Button
    private lateinit var navTabWeb: Button
    private lateinit var navTabPipelines: Button
    private lateinit var navTabDoctor: Button
    private lateinit var navTabSettings: Button

    // Tab 1: Cognitive Chat, Smart Timer & Planner
    private lateinit var btnClearChat: Button
    private lateinit var tvChatHistory: TextView
    private lateinit var etChatInput: EditText
    private lateinit var btnVoiceMic: Button
    private lateinit var btnSendChat: Button
    private lateinit var tvAutoTimerSummary: TextView
    private lateinit var tvPlanSteps: TextView
    private lateinit var rgExecutionMode: RadioGroup
    private lateinit var rbModeBackgroundWeb: RadioButton
    private lateinit var rbModePhoneScreen: RadioButton
    private lateinit var btnExecutePlan: Button
    private lateinit var btnStopAgent: Button

    // Tab 2: In-App Browser
    private lateinit var btnBrowserBack: Button
    private lateinit var btnBrowserForward: Button
    private lateinit var btnBrowserRefresh: Button
    private lateinit var btnBrowserFlow: Button
    private lateinit var btnBrowserGmail: Button
    private lateinit var btnBrowserDesktop: Button
    private lateinit var btnBrowserDownloads: Button
    private lateinit var etWebUrl: EditText
    private lateinit var btnWebGo: Button
    private lateinit var btnPinFlowUrl: Button
    private lateinit var etWebTaskGoal: EditText
    private lateinit var btnRunWebAgent: Button
    private lateinit var webPreviewContainer: FrameLayout

    // Tab 3: Story Showrunner (State-Machine AI) & Storage Vector RAG Sync
    private lateinit var etFlowTopic: EditText
    private lateinit var etStoryCharacterLock: EditText
    private lateinit var rgStoryMode: RadioGroup
    private lateinit var rbStorySerialMode: RadioButton
    private lateinit var rbStorySideMode: RadioButton
    private lateinit var rbStoryStandaloneMode: RadioButton
    private lateinit var etStoryCustomTwist: EditText
    private lateinit var rgVideoDuration: RadioGroup
    private lateinit var rbDuration8s: RadioButton
    private lateinit var rbDuration10s: RadioButton
    private lateinit var rbDuration12s: RadioButton
    private lateinit var rbDuration15s: RadioButton
    private lateinit var etCustomDurationSec: EditText
    private lateinit var etNextEpisodeNumber: EditText
    private lateinit var btnViewStoryHistory: Button
    private lateinit var btnResetStoryArc: Button
    private lateinit var cbOneSecAiConfirm: CheckBox
    private lateinit var cbFirstRunLiveLearn: CheckBox
    private lateinit var cbVideoFrameVisionSeo: CheckBox
    private lateinit var etDailyVideoLimit: EditText
    private lateinit var cbAutoGenerateFlowFirst: CheckBox
    private lateinit var etHour: EditText
    private lateinit var etMinute: EditText
    private lateinit var btnSaveSchedule: Button
    private lateinit var btnRunFlowToYouTubeNow: Button
    private lateinit var etFolderPath: EditText
    private lateinit var btnPickAnyFolder: Button
    private lateinit var rgUploadOrder: RadioGroup
    private lateinit var rbUploadNewestFirst: RadioButton
    private lateinit var rbUploadOldestFirst: RadioButton
    private lateinit var tvQueueStatus: TextView
    private lateinit var btnScanFolder: Button
    private lateinit var btnResetHistory: Button
    private lateinit var btnRunYouTubeNow: Button

    // Tab 4: RAG Memory & State Editor
    private lateinit var etSkillTaskName: EditText
    private lateinit var btnPickScreenshotsToTrain: Button
    private lateinit var btnStartLiveTeachMode: Button
    private lateinit var etTrainedSkillJson: EditText
    private lateinit var btnSaveTrainedSkillJson: Button
    private lateinit var btnCopyTrainedSkillJson: Button
    private lateinit var btnViewAllTrainedSkills: Button
    private lateinit var etMemoryVault: EditText
    private lateinit var btnSaveMemory: Button

    // Tab 5: Cognitive AI Engines & Detailed State Logs
    private lateinit var btnEnableAccessibility: Button
    private lateinit var btnEnableStorage: Button
    private lateinit var btnDisableBatteryOptimization: Button
    private lateinit var etApiKey: EditText
    private lateinit var etModelName: EditText
    private lateinit var etHighModelName: EditText
    private lateinit var cbAutoHighModel: CheckBox
    private lateinit var cbGoogleSearchGrounding: CheckBox
    private lateinit var cbRealWebTouch: CheckBox
    private lateinit var cbUseScreenVision: CheckBox
    private lateinit var cb24HourUnstoppable: CheckBox
    private lateinit var btnSaveSettings: Button
    private lateinit var tvConsoleLog: TextView

    private var lockedPlanGoal: String = ""
    private var lockedTargetUrl: String = ""

    // Voice Input Launcher
    private val voiceRecognizerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = matches?.firstOrNull() ?: ""
            if (spokenText.isNotBlank()) {
                etChatInput.setText(spokenText)
            }
        }
    }

    // Multi-Screenshot Picker Launcher for Vision OCR State Training
    private val pickScreenshotsLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@registerForActivityResult
        trainAiFromSelectedScreenshots(uris)
    }

    // Custom Storage Folder Picker Launcher (SAF Document Tree)
    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {}

        val resolvedPath = resolveTreeUriToFilePath(treeUri)
        if (resolvedPath.isNotBlank()) {
            etFolderPath.setText(resolvedPath)
            saveAllSettingsToPrefs()
            refreshQueueCount()
            Toast.makeText(this, "📂 Selected Folder: $resolvedPath", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindAllViews()
        loadAllSavedPreferences()
        setupBottomNavigation()
        setupChatAndPlannerActions()
        setupBrowserTabActions()
        setupStoryAndPipelineActions()
        setupTrainModelAndVaultActions()
        setupSettingsActions()

        onLogUpdated = { updatedLogs ->
            runOnUiThread {
                tvConsoleLog.text = updatedLogs
            }
        }

        onBrowserUrlChanged = { newUrl ->
            runOnUiThread {
                if (!etWebUrl.hasFocus()) {
                    etWebUrl.setText(newUrl)
                }
            }
        }

        refreshQueueCount()
        refreshServiceStatusBadge()
    }

    override fun onResume() {
        super.onResume()
        refreshServiceStatusBadge()
        refreshQueueCount()
        etNextEpisodeNumber.setText(VideoQueueManager.getNextEpisodeNumber(this).toString())
    }

    override fun onDestroy() {
        if (onLogUpdated != null) onLogUpdated = null
        if (onBrowserUrlChanged != null) onBrowserUrlChanged = null
        super.onDestroy()
    }

    private fun bindAllViews() {
        tvActiveSubtitle = findViewById(R.id.tvActiveSubtitle)
        btnToggleFloatingPod = findViewById(R.id.btnToggleFloatingPod)
        tvServiceBadge = findViewById(R.id.tvServiceBadge)

        tabChatLayout = findViewById(R.id.tabChatLayout)
        tabWebLayout = findViewById(R.id.tabWebLayout)
        tabPipelinesLayout = findViewById(R.id.tabPipelinesLayout)
        tabDoctorLayout = findViewById(R.id.tabDoctorLayout)
        tabSettingsLayout = findViewById(R.id.tabSettingsLayout)

        navTabChat = findViewById(R.id.navTabChat)
        navTabWeb = findViewById(R.id.navTabWeb)
        navTabPipelines = findViewById(R.id.navTabPipelines)
        navTabDoctor = findViewById(R.id.navTabDoctor)
        navTabSettings = findViewById(R.id.navTabSettings)

        btnClearChat = findViewById(R.id.btnClearChat)
        tvChatHistory = findViewById(R.id.tvChatHistory)
        etChatInput = findViewById(R.id.etChatInput)
        btnVoiceMic = findViewById(R.id.btnVoiceMic)
        btnSendChat = findViewById(R.id.btnSendChat)
        tvAutoTimerSummary = findViewById(R.id.tvAutoTimerSummary)
        tvPlanSteps = findViewById(R.id.tvPlanSteps)
        rgExecutionMode = findViewById(R.id.rgExecutionMode)
        rbModeBackgroundWeb = findViewById(R.id.rbModeBackgroundWeb)
        rbModePhoneScreen = findViewById(R.id.rbModePhoneScreen)
        btnExecutePlan = findViewById(R.id.btnExecutePlan)
        btnStopAgent = findViewById(R.id.btnStopAgent)

        btnBrowserBack = findViewById(R.id.btnBrowserBack)
        btnBrowserForward = findViewById(R.id.btnBrowserForward)
        btnBrowserRefresh = findViewById(R.id.btnBrowserRefresh)
        btnBrowserFlow = findViewById(R.id.btnBrowserFlow)
        btnBrowserGmail = findViewById(R.id.btnBrowserGmail)
        btnBrowserDesktop = findViewById(R.id.btnBrowserDesktop)
        btnBrowserDownloads = findViewById(R.id.btnBrowserDownloads)
        etWebUrl = findViewById(R.id.etWebUrl)
        btnWebGo = findViewById(R.id.btnWebGo)
        btnPinFlowUrl = findViewById(R.id.btnPinFlowUrl)
        etWebTaskGoal = findViewById(R.id.etWebTaskGoal)
        btnRunWebAgent = findViewById(R.id.btnRunWebAgent)
        webPreviewContainer = findViewById(R.id.webPreviewContainer)

        etFlowTopic = findViewById(R.id.etFlowTopic)
        etStoryCharacterLock = findViewById(R.id.etStoryCharacterLock)
        rgStoryMode = findViewById(R.id.rgStoryMode)
        rbStorySerialMode = findViewById(R.id.rbStorySerialMode)
        rbStorySideMode = findViewById(R.id.rbStorySideMode)
        rbStoryStandaloneMode = findViewById(R.id.rbStoryStandaloneMode)
        etStoryCustomTwist = findViewById(R.id.etStoryCustomTwist)
        rgVideoDuration = findViewById(R.id.rgVideoDuration)
        rbDuration8s = findViewById(R.id.rbDuration8s)
        rbDuration10s = findViewById(R.id.rbDuration10s)
        rbDuration12s = findViewById(R.id.rbDuration12s)
        rbDuration15s = findViewById(R.id.rbDuration15s)
        etCustomDurationSec = findViewById(R.id.etCustomDurationSec)
        etNextEpisodeNumber = findViewById(R.id.etNextEpisodeNumber)
        btnViewStoryHistory = findViewById(R.id.btnViewStoryHistory)
        btnResetStoryArc = findViewById(R.id.btnResetStoryArc)
        cbOneSecAiConfirm = findViewById(R.id.cbOneSecAiConfirm)
        cbFirstRunLiveLearn = findViewById(R.id.cbFirstRunLiveLearn)
        cbVideoFrameVisionSeo = findViewById(R.id.cbVideoFrameVisionSeo)
        etDailyVideoLimit = findViewById(R.id.etDailyVideoLimit)
        cbAutoGenerateFlowFirst = findViewById(R.id.cbAutoGenerateFlowFirst)
        etHour = findViewById(R.id.etHour)
        etMinute = findViewById(R.id.etMinute)
        btnSaveSchedule = findViewById(R.id.btnSaveSchedule)
        btnRunFlowToYouTubeNow = findViewById(R.id.btnRunFlowToYouTubeNow)
        etFolderPath = findViewById(R.id.etFolderPath)
        btnPickAnyFolder = findViewById(R.id.btnPickAnyFolder)
        rgUploadOrder = findViewById(R.id.rgUploadOrder)
        rbUploadNewestFirst = findViewById(R.id.rbUploadNewestFirst)
        rbUploadOldestFirst = findViewById(R.id.rbUploadOldestFirst)
        tvQueueStatus = findViewById(R.id.tvQueueStatus)
        btnScanFolder = findViewById(R.id.btnScanFolder)
        btnResetHistory = findViewById(R.id.btnResetHistory)
        btnRunYouTubeNow = findViewById(R.id.btnRunYouTubeNow)

        etSkillTaskName = findViewById(R.id.etSkillTaskName)
        btnPickScreenshotsToTrain = findViewById(R.id.btnPickScreenshotsToTrain)
        btnStartLiveTeachMode = findViewById(R.id.btnStartLiveTeachMode)
        etTrainedSkillJson = findViewById(R.id.etTrainedSkillJson)
        btnSaveTrainedSkillJson = findViewById(R.id.btnSaveTrainedSkillJson)
        btnCopyTrainedSkillJson = findViewById(R.id.btnCopyTrainedSkillJson)
        btnViewAllTrainedSkills = findViewById(R.id.btnViewAllTrainedSkills)
        etMemoryVault = findViewById(R.id.etMemoryVault)
        btnSaveMemory = findViewById(R.id.btnSaveMemory)

        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility)
        btnEnableStorage = findViewById(R.id.btnEnableStorage)
        btnDisableBatteryOptimization = findViewById(R.id.btnDisableBatteryOptimization)
        etApiKey = findViewById(R.id.etApiKey)
        etModelName = findViewById(R.id.etModelName)
        etHighModelName = findViewById(R.id.etHighModelName)
        cbAutoHighModel = findViewById(R.id.cbAutoHighModel)
        cbGoogleSearchGrounding = findViewById(R.id.cbGoogleSearchGrounding)
        cbRealWebTouch = findViewById(R.id.cbRealWebTouch)
        cbUseScreenVision = findViewById(R.id.cbUseScreenVision)
        cb24HourUnstoppable = findViewById(R.id.cb24HourUnstoppable)
        btnSaveSettings = findViewById(R.id.btnSaveSettings)
        tvConsoleLog = findViewById(R.id.tvConsoleLog)
    }

    private fun loadAllSavedPreferences() {
        val prefs = getSharedPreferences(VideoQueueManager.PREFS_NAME, Context.MODE_PRIVATE)

        etApiKey.setText(prefs.getString("api_key", "") ?: "")
        etModelName.setText(prefs.getString("model_name", "gemini-3.8-flash") ?: "gemini-3.8-flash")
        etHighModelName.setText(VideoQueueManager.getHighModelName(this))

        cbAutoHighModel.isChecked = VideoQueueManager.isAutoHighModelEnabled(this)
        cbGoogleSearchGrounding.isChecked = VideoQueueManager.isGoogleSearchGroundingEnabled(this)
        cbRealWebTouch.isChecked = VideoQueueManager.isRealWebTouchEnabled(this)
        cbUseScreenVision.isChecked = VideoQueueManager.isScreenVisionEnabled(this)
        cb24HourUnstoppable.isChecked = VideoQueueManager.is24HourUnstoppableEnabled(this)

        tvAutoTimerSummary.text = VideoQueueManager.getParsedScheduleSummary(this)
        etFlowTopic.setText(VideoQueueManager.getFlowTopic(this))
        etStoryCharacterLock.setText(VideoQueueManager.getCharacterVisualLock(this))
        etStoryCustomTwist.setText(VideoQueueManager.getStoryCustomTwist(this))
        etNextEpisodeNumber.setText(VideoQueueManager.getNextEpisodeNumber(this).toString())

        cbOneSecAiConfirm.isChecked = VideoQueueManager.isOneSecAiConfirmEnabled(this)
        cbFirstRunLiveLearn.isChecked = VideoQueueManager.isFirstRunLiveLearnEnabled(this)
        cbVideoFrameVisionSeo.isChecked = VideoQueueManager.isVideoFrameVisionSeoEnabled(this)

        when (VideoQueueManager.getStoryMode(this)) {
            "SIDE_STORY" -> rbStorySideMode.isChecked = true
            "STANDALONE" -> rbStoryStandaloneMode.isChecked = true
            else -> rbStorySerialMode.isChecked = true
        }

        val savedDur = VideoQueueManager.getTargetVideoDurationSec(this)
        etCustomDurationSec.setText(savedDur.toString())
        when (savedDur) {
            8 -> rbDuration8s.isChecked = true
            10 -> rbDuration10s.isChecked = true
            12 -> rbDuration12s.isChecked = true
            15 -> rbDuration15s.isChecked = true
        }

        etDailyVideoLimit.setText(VideoQueueManager.getDailyVideoLimit(this).toString())
        cbAutoGenerateFlowFirst.isChecked = prefs.getBoolean("auto_flow_first", true)
        etHour.setText(String.format(Locale.US, "%02d", prefs.getInt("schedule_hour", 19)))
        etMinute.setText(String.format(Locale.US, "%02d", prefs.getInt("schedule_minute", 0)))

        val defaultFolder = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "AutoVideos"
        ).absolutePath
        etFolderPath.setText(prefs.getString("folder_path", defaultFolder) ?: defaultFolder)

        if (VideoQueueManager.isUploadNewestFirst(this)) {
            rbUploadNewestFirst.isChecked = true
        } else {
            rbUploadOldestFirst.isChecked = true
        }

        etWebUrl.setText(VideoQueueManager.getPinnedFlowUrl(this))
        etMemoryVault.setText(VideoQueueManager.getMemoryVault(this))

        val initialSkill = etSkillTaskName.text.toString().trim().ifEmpty { "GOOGLE_FLOW" }
        etTrainedSkillJson.setText(VideoQueueManager.getTrainedSkillBlueprint(this, initialSkill))

        tvConsoleLog.text = prefs.getString("live_logs", "[System] Cognitive Engine Initialized.") ?: ""
    }

    private fun setupBottomNavigation() {
        fun switchTab(activeIndex: Int) {
            tabChatLayout.visibility = if (activeIndex == 1) View.VISIBLE else View.GONE
            tabWebLayout.visibility = if (activeIndex == 2) View.VISIBLE else View.GONE
            tabPipelinesLayout.visibility = if (activeIndex == 3) View.VISIBLE else View.GONE
            tabDoctorLayout.visibility = if (activeIndex == 4) View.VISIBLE else View.GONE
            tabSettingsLayout.visibility = if (activeIndex == 5) View.VISIBLE else View.GONE

            val activeColor = Color.parseColor("#38BDF8")
            val inactiveColor = Color.parseColor("#94A3B8")

            navTabChat.setTextColor(if (activeIndex == 1) activeColor else inactiveColor)
            navTabWeb.setTextColor(if (activeIndex == 2) activeColor else inactiveColor)
            navTabPipelines.setTextColor(if (activeIndex == 3) activeColor else inactiveColor)
            navTabDoctor.setTextColor(if (activeIndex == 4) activeColor else inactiveColor)
            navTabSettings.setTextColor(if (activeIndex == 5) activeColor else inactiveColor)

            if (activeIndex == 2) {
                val hud = AutoTouchService.instance?.overlayHud
                if (hud != null) {
                    hud.collapseToCompactStage()
                    hud.attachWebViewToContainer(webPreviewContainer)
                } else {
                    Toast.makeText(
                        this,
                        "Pehle Core Settings tab se Enable Touch (Nax Bot) ON karein!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        navTabChat.setOnClickListener { switchTab(1) }
        navTabWeb.setOnClickListener { switchTab(2) }
        navTabPipelines.setOnClickListener { switchTab(3) }
        navTabDoctor.setOnClickListener { switchTab(4) }
        navTabSettings.setOnClickListener { switchTab(5) }

        btnToggleFloatingPod.setOnClickListener {
            val hud = AutoTouchService.instance?.overlayHud
            if (hud == null) {
                Toast.makeText(this, "Enable Touch service first!", Toast.LENGTH_SHORT).show()
            } else {
                val visible = hud.togglePodVisibility()
                btnToggleFloatingPod.text = if (visible) "Nax: ON" else "Nax: OFF"
            }
        }
    }

    private fun setupChatAndPlannerActions() {
        btnClearChat.setOnClickListener {
            tvChatHistory.text = "🤖 Nax Bot: Main ab State-Machine aur Pure Vision AI se sochta hoon. Mujhe apna task bataiye!"
            tvPlanSteps.text = "Abhi koi plan nahi hai."
            lockedPlanGoal = ""
            lockedTargetUrl = ""
        }

        btnVoiceMic.setOnClickListener {
            try {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Nax Bot ko apna kaam ya time bolkar batayein...")
                }
                voiceRecognizerLauncher.launch(intent)
            } catch (_: Exception) {
                Toast.makeText(this, "Voice mic supported nahi hai.", Toast.LENGTH_SHORT).show()
            }
        }

        btnSendChat.setOnClickListener {
            val userMsg = etChatInput.text.toString().trim()
            if (userMsg.isEmpty()) {
                Toast.makeText(this, "Pehle apna kaam ya timer likhein!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            saveAllSettingsToPrefs()
            val apiKey = etApiKey.text.toString().trim()
            val model = etModelName.text.toString().trim()
            val vault = VideoQueueManager.getMemoryVault(this)

            tvChatHistory.append("\n\n🧑 You: $userMsg")
            etChatInput.setText("")
            btnSendChat.isEnabled = false
            btnSendChat.text = "Nax Bot soch raha hai..."

            lifecycleScope.launch {
                val resp = GeminiBrain.chatAndBuildPlan(
                    apiKey = apiKey,
                    model = model,
                    userMessage = userMsg,
                    chatHistory = tvChatHistory.text.toString(),
                    memoryVault = vault
                )
                btnSendChat.isEnabled = true
                btnSendChat.text = "✨ प्लान बनाएं"

                tvChatHistory.append("\n\n🤖 Nax Bot: ${resp.replyText}")
                tvPlanSteps.text = resp.planSteps
                tvAutoTimerSummary.text = resp.parsedTimerSummary
                VideoQueueManager.saveParsedScheduleSummary(this@MainActivity, resp.parsedTimerSummary)

                tryExtractAndScheduleChatTime("$userMsg ${resp.parsedTimerSummary}")

                lockedPlanGoal = resp.planGoal
                lockedTargetUrl = resp.targetUrl

                if (resp.suggestedMode == "WEB") {
                    rbModeBackgroundWeb.isChecked = true
                } else {
                    rbModePhoneScreen.isChecked = true
                }
                tvActiveSubtitle.text = GeminiBrain.getAutoModelStatusReport()
            }
        }

        btnExecutePlan.setOnClickListener {
            saveAllSettingsToPrefs()
            val apiKey = etApiKey.text.toString().trim()
            val model = etModelName.text.toString().trim()
            val folderPath = etFolderPath.text.toString().trim()
            val goalToRun = lockedPlanGoal.ifBlank {
                etChatInput.text.toString().trim().ifBlank { tvPlanSteps.text.toString() }
            }

            if (AutoTouchService.instance == null) {
                Toast.makeText(this, "Pehle Accessibility Touch ON karein!", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }

            if (rbModeBackgroundWeb.isChecked) {
                val url = lockedTargetUrl.ifBlank { VideoQueueManager.getPinnedFlowUrl(this) }
                WebPipelineManager.startAutonomousWebAgent(
                    context = this,
                    apiKey = apiKey,
                    model = model,
                    goal = goalToRun,
                    startUrl = url,
                    folderPath = folderPath
                )
                Toast.makeText(this, "🌐 Nax Bot 1x1 Stealth State-Machine started!", Toast.LENGTH_SHORT).show()
            } else {
                startPhoneScreenAgentSafely(apiKey, model, goalToRun, folderPath)
            }
        }

        btnStopAgent.setOnClickListener {
            AutoTouchService.instance?.stopCurrentJob()
            WebPipelineManager.stopEverything(this)
            Toast.makeText(this, "🛑 Stopped all active tasks.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Parses HH:MM or natural Hindi/English time expressions from chat without using pipe symbols
     */
    private fun tryExtractAndScheduleChatTime(combinedText: String) {
        val words = combinedText.replace(",", " ").split(Regex("\\s+"))
        for (word in words) {
            if (word.contains(":")) {
                val parts = word.split(":")
                if (parts.size == 2) {
                    val h = parts[0].toIntOrNull()
                    val m = parts[1].toIntOrNull()
                    if (h != null && m != null && h in 0..23 && m in 0..59) {
                        etHour.setText(String.format(Locale.US, "%02d", h))
                        etMinute.setText(String.format(Locale.US, "%02d", m))
                        scheduleDailyPipelineAlarm(h, m)
                        return
                    }
                }
            }
        }

        val lowerWords = words.map { it.lowercase(Locale.US) }
        val timeMarkers = setOf("baje", "बजे", "pm", "am")
        for (i in 0 until lowerWords.size - 1) {
            val num = lowerWords[i].toIntOrNull()
            val nextWord = lowerWords[i + 1]
            if (num != null && num in 1..12 && nextWord in timeMarkers) {
                var hour = num
                val fullLower = combinedText.lowercase(Locale.US)
                val isEveningOrNight = fullLower.contains("raat") ||
                        fullLower.contains("रात") ||
                        fullLower.contains("shaam") ||
                        fullLower.contains("शाम") ||
                        nextWord == "pm"
                if (isEveningOrNight && hour < 12) {
                    hour += 12
                }
                etHour.setText(String.format(Locale.US, "%02d", hour))
                etMinute.setText("00")
                scheduleDailyPipelineAlarm(hour, 0)
                return
            }
        }
    }

    private fun setupBrowserTabActions() {
        btnBrowserBack.setOnClickListener {
            val wv = AutoTouchService.instance?.overlayHud?.getOrCreateSharedWebView()
            if (wv?.canGoBack() == true) wv.goBack()
        }

        btnBrowserForward.setOnClickListener {
            val wv = AutoTouchService.instance?.overlayHud?.getOrCreateSharedWebView()
            if (wv?.canGoForward() == true) wv.goForward()
        }

        btnBrowserRefresh.setOnClickListener {
            AutoTouchService.instance?.overlayHud?.getOrCreateSharedWebView()?.reload()
        }

        btnBrowserFlow.setOnClickListener {
            val flowUrl = VideoQueueManager.getPinnedFlowUrl(this)
            etWebUrl.setText(flowUrl)
            AutoTouchService.instance?.overlayHud?.getOrCreateSharedWebView()?.loadUrl(flowUrl)
        }

        btnBrowserGmail.setOnClickListener {
            val loginUrl = "https://accounts.google.com/"
            etWebUrl.setText(loginUrl)
            AutoTouchService.instance?.overlayHud?.getOrCreateSharedWebView()?.loadUrl(loginUrl)
        }

        btnBrowserDesktop.setOnClickListener {
            val hud = AutoTouchService.instance?.overlayHud ?: return@setOnClickListener
            val isDesk = hud.toggleDesktopMode()
            btnBrowserDesktop.text = if (isDesk) "🖥️ Desktop: ON" else "🖥️ Desktop: OFF"
        }

        btnBrowserDownloads.setOnClickListener {
            val folderPath = etFolderPath.text.toString().trim()
            val report = VideoQueueManager.getDownloadedFilesReport(folderPath)
            AlertDialog.Builder(this)
                .setTitle("📥 Downloaded Videos in Folder")
                .setMessage(report)
                .setPositiveButton("OK", null)
                .show()
        }

        btnWebGo.setOnClickListener {
            var url = etWebUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "https://$url"
                }
                AutoTouchService.instance?.overlayHud?.getOrCreateSharedWebView()?.loadUrl(url)
            }
        }

        btnPinFlowUrl.setOnClickListener {
            val currentUrl = AutoTouchService.instance?.overlayHud?.getOrCreateSharedWebView()?.url
                ?: etWebUrl.text.toString().trim()
            if (currentUrl.isNotBlank()) {
                VideoQueueManager.savePinnedFlowUrl(this, currentUrl)
                etWebUrl.setText(currentUrl)
                Toast.makeText(this, "📌 Pinned URL: $currentUrl", Toast.LENGTH_SHORT).show()
            }
        }

        btnRunWebAgent.setOnClickListener {
            saveAllSettingsToPrefs()
            val apiKey = etApiKey.text.toString().trim()
            val model = etModelName.text.toString().trim()
            val folderPath = etFolderPath.text.toString().trim()
            val url = etWebUrl.text.toString().trim()
            val goal = etWebTaskGoal.text.toString().trim().ifBlank {
                VideoQueueManager.getFlowTopic(this)
            }

            WebPipelineManager.startAutonomousWebAgent(
                context = this,
                apiKey = apiKey,
                model = model,
                goal = goal,
                startUrl = url,
                folderPath = folderPath
            )
            Toast.makeText(this, "▶ Cognitive State-Machine Web Agent Running!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupStoryAndPipelineActions() {
        rgVideoDuration.setOnCheckedChangeListener { _, checkedId ->
            val sec = when (checkedId) {
                R.id.rbDuration8s -> 8
                R.id.rbDuration10s -> 10
                R.id.rbDuration12s -> 12
                R.id.rbDuration15s -> 15
                else -> 10
            }
            etCustomDurationSec.setText(sec.toString())
            VideoQueueManager.setTargetVideoDurationSec(this, sec)
        }

        rgStoryMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbStorySideMode -> "SIDE_STORY"
                R.id.rbStoryStandaloneMode -> "STANDALONE"
                else -> "SERIAL"
            }
            VideoQueueManager.setStoryMode(this, mode)
        }

        rgUploadOrder.setOnCheckedChangeListener { _, checkedId ->
            val newestFirst = (checkedId == R.id.rbUploadNewestFirst)
            VideoQueueManager.setUploadNewestFirst(this, newestFirst)
            refreshQueueCount()
        }

        btnPickAnyFolder.setOnClickListener {
            try {
                folderPickerLauncher.launch(null)
            } catch (_: Exception) {
                Toast.makeText(this, "Folder picker not supported; type path directly.", Toast.LENGTH_SHORT).show()
            }
        }

        btnViewStoryHistory.setOnClickListener {
            val report = VideoQueueManager.getFullStoryArcReport(this)
            AlertDialog.Builder(this)
                .setTitle("📜 Episodic Story Series History (Part 1, 2, 3...)")
                .setMessage(report)
                .setPositiveButton("OK", null)
                .show()
        }

        btnResetStoryArc.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("🔄 Reset Story Series to Part 1?")
                .setMessage("Kya aap pichli story history mita kar Part 1 se nayi series shuru karna chahte hain?")
                .setPositiveButton("Yes, Reset to Ep 1") { _, _ ->
                    VideoQueueManager.resetStoryArcHistory(this)
                    etNextEpisodeNumber.setText("1")
                    Toast.makeText(this, "✅ Story reset to Part 1!", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnSaveSchedule.setOnClickListener {
            saveAllSettingsToPrefs()
            val hour = etHour.text.toString().toIntOrNull()?.coerceIn(0, 23) ?: 19
            val minute = etMinute.text.toString().toIntOrNull()?.coerceIn(0, 59) ?: 0
            scheduleDailyPipelineAlarm(hour, minute)
        }

        btnRunFlowToYouTubeNow.setOnClickListener {
            saveAllSettingsToPrefs()
            if (AutoTouchService.instance == null) {
                Toast.makeText(this, "Pehle Core Settings tab se Enable Touch ON karein!", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }

            val apiKey = etApiKey.text.toString().trim()
            val model = etModelName.text.toString().trim()
            val topic = etFlowTopic.text.toString().trim()
            val folderPath = etFolderPath.text.toString().trim()
            val limit = etDailyVideoLimit.text.toString().toIntOrNull()?.coerceIn(1, 5) ?: 1

            WebPipelineManager.startFlowToYouTubePipeline(
                context = this,
                apiKey = apiKey,
                model = model,
                flowTopic = topic,
                folderPath = folderPath,
                dailyVideoLimit = limit
            )
            Toast.makeText(
                this,
                "🎬 State-Machine Running: Part #${VideoQueueManager.getNextEpisodeNumber(this)} (${VideoQueueManager.getTargetVideoDurationSec(this)}s)!",
                Toast.LENGTH_SHORT
            ).show()
        }

        btnScanFolder.setOnClickListener {
            saveAllSettingsToPrefs()
            refreshQueueCount()
            Toast.makeText(this, "📂 Folder scanned!", Toast.LENGTH_SHORT).show()
        }

        btnResetHistory.setOnClickListener {
            VideoQueueManager.resetUploadedHistory(this)
            refreshQueueCount()
            Toast.makeText(this, "🔄 Uploaded video history cleared!", Toast.LENGTH_SHORT).show()
        }

        btnRunYouTubeNow.setOnClickListener {
            saveAllSettingsToPrefs()
            if (AutoTouchService.instance == null) {
                Toast.makeText(this, "Pehle Accessibility Touch ON karein!", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }
            val apiKey = etApiKey.text.toString().trim()
            val model = etModelName.text.toString().trim()
            val folderPath = etFolderPath.text.toString().trim()
            val baseTopic = etFlowTopic.text.toString().trim()

            val nextVideo = VideoQueueManager.getNextPendingVideo(this, folderPath)
            if (nextVideo == null) {
                Toast.makeText(this, "📂 Is folder mein koi pending MP4 video nahi mili!", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            btnRunYouTubeNow.isEnabled = false
            btnRunYouTubeNow.text = "👁️ Inspecting video frames..."

            lifecycleScope.launch {
                var finalInstruction = baseTopic
                if (VideoQueueManager.isVideoFrameVisionSeoEnabled(this@MainActivity) && apiKey.isNotBlank()) {
                    val frames = withContext(Dispatchers.IO) {
                        VideoQueueManager.extractVideoFramesBase64(nextVideo)
                    }
                    if (frames.isNotEmpty()) {
                        val (epNum, epTitle, epSum) = VideoQueueManager.getLastGeneratedEpisodeInfo(this@MainActivity)
                        val meta = GeminiBrain.generateYouTubeMetadataWithVision(
                            apiKey = apiKey,
                            model = model,
                            videoFramesBase64 = frames,
                            fileName = nextVideo.name,
                            userInstruction = baseTopic,
                            episodeNum = epNum,
                            episodeTitle = epTitle,
                            storySummary = epSum
                        )
                        finalInstruction = "${meta.title}\n${meta.description}"
                        VideoQueueManager.appendDetailedLog(
                            context = this@MainActivity,
                            modelUsed = VideoQueueManager.getHighModelName(this@MainActivity),
                            clickSense = "Frame Vision SEO",
                            actionDesc = "Viral Title: ${meta.title}",
                            whyReason = "Inspected ${frames.size} real frames from ${nextVideo.name}",
                            verifyStatus = "READY FOR YT"
                        )
                    }
                }

                btnRunYouTubeNow.isEnabled = true
                btnRunYouTubeNow.text = "▶ Run Frame Vision Upload"

                AutoTouchService.instance?.startYouTubeUploadJob(
                    context = this@MainActivity,
                    apiKey = apiKey,
                    model = model,
                    folderPath = folderPath,
                    topicInstruction = finalInstruction
                )
                Toast.makeText(this@MainActivity, "▶ Uploading to YouTube Shorts with Viral Vision Title!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun resolveTreeUriToFilePath(treeUri: Uri): String {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val parts = docId.split(":")
            if (parts.isNotEmpty()) {
                val type = parts[0]
                val rel = if (parts.size > 1) parts[1] else ""
                if ("primary".equals(type, ignoreCase = true)) {
                    "${Environment.getExternalStorageDirectory().absolutePath}/$rel".trimEnd('/')
                } else {
                    "/storage/$type/$rel".trimEnd('/')
                }
            } else {
                etFolderPath.text.toString()
            }
        } catch (_: Exception) {
            etFolderPath.text.toString()
        }
    }

    private fun setupTrainModelAndVaultActions() {
        btnPickScreenshotsToTrain.setOnClickListener {
            saveAllSettingsToPrefs()
            if (etApiKey.text.toString().trim().isEmpty()) {
                Toast.makeText(this, "Pehle Core Settings mein Gemini API Key daalein!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(
                this,
                "📸 Start se End tak ke screenshots क्रम से select karein!",
                Toast.LENGTH_LONG
            ).show()
            pickScreenshotsLauncher.launch("image/*")
        }

        btnStartLiveTeachMode.setOnClickListener {
            val hud = AutoTouchService.instance?.overlayHud
            if (hud == null) {
                Toast.makeText(this, "Pehle Enable Touch (Nax Bot) ON karein!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val skillName = etSkillTaskName.text.toString().trim().ifEmpty { "GOOGLE_FLOW" }
            val active = hud.startOrStopLiveTeach(skillName) { savedJson ->
                runOnUiThread {
                    etTrainedSkillJson.setText(savedJson)
                    btnStartLiveTeachMode.text = "🔴 State Record"
                }
            }
            btnStartLiveTeachMode.text = if (active) "💾 Stop & Save" else "🔴 State Record"
        }

        btnSaveTrainedSkillJson.setOnClickListener {
            val skillName = etSkillTaskName.text.toString().trim().ifEmpty { "GOOGLE_FLOW" }
            val jsonText = etTrainedSkillJson.text.toString().trim()
            if (jsonText.isEmpty()) {
                Toast.makeText(this, "State JSON box khali hai!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            VideoQueueManager.saveTrainedSkillBlueprint(this, skillName, jsonText, isVerified = false)
            Toast.makeText(
                this,
                "💾 '$skillName' State-Machine Blueprint synced to RAG Mind!",
                Toast.LENGTH_SHORT
            ).show()
        }

        btnCopyTrainedSkillJson.setOnClickListener {
            val jsonText = etTrainedSkillJson.text.toString().trim()
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("NaxBotStateJson", jsonText))
            Toast.makeText(this, "📤 State JSON copied to clipboard!", Toast.LENGTH_SHORT).show()
        }

        btnViewAllTrainedSkills.setOnClickListener {
            val report = VideoQueueManager.getAllTrainedSkillsSummary(this)
            AlertDialog.Builder(this)
                .setTitle("📚 All Cognitive State Blueprints & Vectors")
                .setMessage(report)
                .setPositiveButton("OK", null)
                .show()
        }

        btnSaveMemory.setOnClickListener {
            val vaultText = etMemoryVault.text.toString().trim()
            VideoQueueManager.saveMemoryVault(this, vaultText)
            val ragVectors = VideoQueueManager.retrieveRelevantRagMemories(
                context = this,
                queryContext = etFlowTopic.text.toString(),
                domainFilter = "GOOGLE_FLOW",
                topK = 15
            )
            AlertDialog.Builder(this)
                .setTitle("🧠 Active Vector RAG Self-Healing Memories")
                .setMessage(ragVectors)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun setupSettingsActions() {
        btnEnableAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnEnableStorage.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        intent.data = Uri.parse("package:$packageName")
                        startActivity(intent)
                    } catch (_: Exception) {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                } else {
                    Toast.makeText(this, "✅ Storage access already granted!", Toast.LENGTH_SHORT).show()
                }
            } else {
                requestPermissions(
                    arrayOf(
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ),
                    101
                )
            }
        }

        btnDisableBatteryOptimization.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    } catch (_: Exception) {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                } else {
                    Toast.makeText(this, "🛡️ Force Active Mode already ON!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnSaveSettings.setOnClickListener {
            saveAllSettingsToPrefs()
            tvActiveSubtitle.text = GeminiBrain.getAutoModelStatusReport()
            Toast.makeText(this, "💾 Cognitive AI Core Settings Saved!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun trainAiFromSelectedScreenshots(uris: List<Uri>) {
        val skillName = etSkillTaskName.text.toString().trim().ifEmpty { "GOOGLE_FLOW" }
        val apiKey = etApiKey.text.toString().trim()
        val model = etModelName.text.toString().trim()

        btnPickScreenshotsToTrain.isEnabled = false
        btnPickScreenshotsToTrain.text = "⏳ Vision OCR Training..."

        lifecycleScope.launch {
            try {
                val base64Images = withContext(Dispatchers.IO) {
                    uris.take(8).mapNotNull { uri ->
                        uriToScaledBase64Jpeg(uri)
                    }
                }

                if (base64Images.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Screenshots read nahi ho paye.", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val blueprintJson = GeminiBrain.trainSkillFromScreenshots(
                    apiKey = apiKey,
                    model = model,
                    skillName = skillName,
                    userInstruction = "Complete Cognitive State-Machine workflow for $skillName with 540x960 Vision coordinates",
                    screenshotsBase64 = base64Images
                )

                etTrainedSkillJson.setText(blueprintJson)
                VideoQueueManager.saveTrainedSkillBlueprint(
                    context = this@MainActivity,
                    skillName = skillName,
                    jsonBlueprint = blueprintJson,
                    isVerified = false
                )

                VideoQueueManager.appendLog(
                    this@MainActivity,
                    "🎓 Vision OCR Trained '$skillName' from ${base64Images.size} screenshots into RAG Mind!"
                )
                Toast.makeText(
                    this@MainActivity,
                    "✅ '$skillName' State Blueprint Synced!",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "⚠️ Train Error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                btnPickScreenshotsToTrain.isEnabled = true
                btnPickScreenshotsToTrain.text = "📸 Vision OCR Train"
            }
        }
    }

    private fun uriToScaledBase64Jpeg(uri: Uri): String? {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                val rawBmp = BitmapFactory.decodeStream(input) ?: return null
                val scaled = Bitmap.createScaledBitmap(rawBmp, 540, 960, true)
                if (scaled != rawBmp) rawBmp.recycle()
                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 70, baos)
                scaled.recycle()
                Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun startPhoneScreenAgentSafely(
        apiKey: String,
        model: String,
        goal: String,
        folderPath: String
    ) {
        val service = AutoTouchService.instance ?: return
        val lower = goal.lowercase()
        if (lower.contains("youtube") && (lower.contains("upload") || lower.contains("short"))) {
            service.startYouTubeUploadJob(this, apiKey, model, folderPath, goal)
            return
        }

        try {
            val method = service.javaClass.methods.firstOrNull { m ->
                m.name in listOf(
                    "startAutonomousPhoneJob",
                    "startPhoneAgentJob",
                    "startGeneralAgentJob",
                    "startAutonomousTask"
                )
            }
            if (method != null) {
                when (method.parameterTypes.size) {
                    4 -> method.invoke(service, this, apiKey, model, goal)
                    3 -> method.invoke(service, apiKey, model, goal)
                    else -> service.startYouTubeUploadJob(this, apiKey, model, folderPath, goal)
                }
                Toast.makeText(this, "📱 Nax Bot Live Phone Vision Agent started!", Toast.LENGTH_SHORT).show()
            } else {
                service.startYouTubeUploadJob(this, apiKey, model, folderPath, goal)
            }
        } catch (_: Exception) {
            service.startYouTubeUploadJob(this, apiKey, model, folderPath, goal)
        }
    }

    private fun scheduleDailyPipelineAlarm(hour: Int, minute: Int) {
        try {
            val receiverClass = Class.forName("com.auradroid.agent.DailyUploadReceiver")
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, receiverClass)
            val pendingIntent = PendingIntent.getBroadcast(
                this,
                2001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pendingIntent
                )
            }

            val timeFormatted = String.format(Locale.US, "%02d:%02d", hour, minute)
            val msg = "⏰ Smart Timer Locked: Daily State-Machine Pipeline at $timeFormatted"
            tvAutoTimerSummary.text = msg
            VideoQueueManager.saveParsedScheduleSummary(this, msg)
            VideoQueueManager.appendLog(this, msg)
            Toast.makeText(this, "⏰ Daily Auto-Schedule set for $timeFormatted!", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "⏰ Schedule time saved ($hour:$minute)!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveAllSettingsToPrefs() {
        val prefs = getSharedPreferences(VideoQueueManager.PREFS_NAME, Context.MODE_PRIVATE)
        val durSec = etCustomDurationSec.text.toString().toIntOrNull()?.coerceIn(5, 60) ?: 10
        val nextEp = etNextEpisodeNumber.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1
        val dailyLimit = etDailyVideoLimit.text.toString().toIntOrNull()?.coerceIn(1, 5) ?: 1
        val hour = etHour.text.toString().toIntOrNull()?.coerceIn(0, 23) ?: 19
        val minute = etMinute.text.toString().toIntOrNull()?.coerceIn(0, 59) ?: 0

        val selectedStoryMode = when {
            rbStorySideMode.isChecked -> "SIDE_STORY"
            rbStoryStandaloneMode.isChecked -> "STANDALONE"
            else -> "SERIAL"
        }

        prefs.edit()
            .putString("api_key", etApiKey.text.toString().trim())
            .putString("model_name", etModelName.text.toString().trim().ifEmpty { "gemini-3.8-flash" })
            .putString("high_model_name", etHighModelName.text.toString().trim().ifEmpty { "gemini-3.8-pro" })
            .putBoolean("auto_high_model", cbAutoHighModel.isChecked)
            .putBoolean("google_search_grounding", cbGoogleSearchGrounding.isChecked)
            .putBoolean("real_web_touch", cbRealWebTouch.isChecked)
            .putBoolean("use_screen_vision", cbUseScreenVision.isChecked)
            .putBoolean("unstoppable_24h", cb24HourUnstoppable.isChecked)
            .putString("flow_topic", etFlowTopic.text.toString().trim())
            .putInt("daily_video_limit", dailyLimit)
            .putBoolean("auto_flow_first", cbAutoGenerateFlowFirst.isChecked)
            .putInt("schedule_hour", hour)
            .putInt("schedule_minute", minute)
            .putString("folder_path", etFolderPath.text.toString().trim())
            .apply()

        VideoQueueManager.setStoryMode(this, selectedStoryMode)
        VideoQueueManager.setCharacterVisualLock(this, etStoryCharacterLock.text.toString().trim())
        VideoQueueManager.setStoryCustomTwist(this, etStoryCustomTwist.text.toString().trim())
        VideoQueueManager.setTargetVideoDurationSec(this, durSec)
        VideoQueueManager.setNextEpisodeNumber(this, nextEp)
        VideoQueueManager.setOneSecAiConfirmEnabled(this, cbOneSecAiConfirm.isChecked)
        VideoQueueManager.setFirstRunLiveLearnEnabled(this, cbFirstRunLiveLearn.isChecked)
        VideoQueueManager.setVideoFrameVisionSeoEnabled(this, cbVideoFrameVisionSeo.isChecked)
        VideoQueueManager.setUploadNewestFirst(this, rbUploadNewestFirst.isChecked)
        VideoQueueManager.saveMemoryVault(this, etMemoryVault.text.toString().trim())
    }

    private fun refreshQueueCount() {
        val folderPath = etFolderPath.text.toString().trim()
        val preferNewest = VideoQueueManager.isUploadNewestFirst(this)
        val allVideos = VideoQueueManager.getVideoFilesInFolder(folderPath, newestFirst = preferNewest)
        val uploaded = VideoQueueManager.getUploadedSet(this)
        val pendingList = allVideos.filter { it.absolutePath !in uploaded && it.name !in uploaded }
        val nextFile = pendingList.firstOrNull()?.name ?: "None"
        val orderLabel = if (preferNewest) "Newest First" else "Oldest First"

        tvQueueStatus.text =
            "Order: $orderLabel\nTotal: ${allVideos.size} • Pending: ${pendingList.size}\nNext: $nextFile"
    }

    private fun refreshServiceStatusBadge() {
        val isRunning = AutoTouchService.instance != null
        if (isRunning) {
            tvServiceBadge.text = "TOUCH: ACTIVE"
            tvServiceBadge.setTextColor(Color.parseColor("#2DD4BF"))
        } else {
            tvServiceBadge.text = "TOUCH: OFF"
            tvServiceBadge.setTextColor(Color.parseColor("#F87171"))
        }
    }
}
