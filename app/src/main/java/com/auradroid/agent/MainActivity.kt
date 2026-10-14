package com.auradroid.agent

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        var onLogUpdated: ((String) -> Unit)? = null
        private const val REQ_VOICE_SPEECH = 202
    }

    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Header Views
    private lateinit var tvActiveSubtitle: TextView
    private lateinit var tvServiceBadge: TextView
    private lateinit var btnToggleFloatingPod: Button

    // 5 Tab Layouts & Nav Buttons
    private lateinit var tabChatLayout: View
    private lateinit var tabWebLayout: View
    private lateinit var tabPipelinesLayout: View
    private lateinit var tabDoctorLayout: View
    private lateinit var tabSettingsLayout: View

    private lateinit var navTabChat: Button
    private lateinit var navTabWeb: Button
    private lateinit var navTabPipelines: Button
    private lateinit var navTabDoctor: Button
    private lateinit var navTabSettings: Button

    // Tab 1: Chat & Planner
    private lateinit var tvChatHistory: TextView
    private lateinit var etChatInput: EditText
    private lateinit var tvPlanSteps: TextView
    private lateinit var rbModeBackgroundWeb: RadioButton
    private lateinit var rbModePhoneScreen: RadioButton
    private var currentLockedGoal: String = ""
    private var currentSuggestedUrl: String = ""

    // Tab 2: Background Web Studio
    private lateinit var etWebUrl: EditText
    private lateinit var etWebTaskGoal: EditText
    private lateinit var webPreviewContainer: FrameLayout

    // Tab 3: YouTube & 24/7 Pipelines
    private lateinit var etFolderPath: EditText
    private lateinit var tvQueueStatus: TextView
    private lateinit var etAiCommand: EditText
    private lateinit var cbPipelineAiVideoFirst: CheckBox
    private lateinit var etHour: EditText
    private lateinit var etMinute: EditText

    // Tab 4: Phone Doctor & Memory Vault
    private lateinit var etDoctorProblem: EditText
    private lateinit var etMemoryVault: EditText

    // Tab 5: Settings & Logs
    private lateinit var etApiKey: EditText
    private lateinit var etModelName: EditText
    private lateinit var cbUseScreenVision: CheckBox
    private lateinit var cb24HourUnstoppable: CheckBox
    private lateinit var etStepDelaySec: EditText
    private lateinit var tvConsoleLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        loadSavedSettings()
        setupBottomNavigation()
        setupTab1ChatAndPlanner()
        setupTab2WebStudio()
        setupTab3YouTubePipelines()
        setupTab4DoctorAndVault()
        setupTab5SettingsAndLogs()

        onLogUpdated = { logs ->
            runOnUiThread {
                tvConsoleLog.text = logs
            }
        }
    }

    private fun bindViews() {
        tvActiveSubtitle = findViewById(R.id.tvActiveSubtitle)
        tvServiceBadge = findViewById(R.id.tvServiceBadge)
        btnToggleFloatingPod = findViewById(R.id.btnToggleFloatingPod)

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

        tvChatHistory = findViewById(R.id.tvChatHistory)
        etChatInput = findViewById(R.id.etChatInput)
        tvPlanSteps = findViewById(R.id.tvPlanSteps)
        rbModeBackgroundWeb = findViewById(R.id.rbModeBackgroundWeb)
        rbModePhoneScreen = findViewById(R.id.rbModePhoneScreen)

        etWebUrl = findViewById(R.id.etWebUrl)
        etWebTaskGoal = findViewById(R.id.etWebTaskGoal)
        webPreviewContainer = findViewById(R.id.webPreviewContainer)

        etFolderPath = findViewById(R.id.etFolderPath)
        tvQueueStatus = findViewById(R.id.tvQueueStatus)
        etAiCommand = findViewById(R.id.etAiCommand)
        cbPipelineAiVideoFirst = findViewById(R.id.cbPipelineAiVideoFirst)
        etHour = findViewById(R.id.etHour)
        etMinute = findViewById(R.id.etMinute)

        etDoctorProblem = findViewById(R.id.etDoctorProblem)
        etMemoryVault = findViewById(R.id.etMemoryVault)

        etApiKey = findViewById(R.id.etApiKey)
        etModelName = findViewById(R.id.etModelName)
        cbUseScreenVision = findViewById(R.id.cbUseScreenVision)
        cb24HourUnstoppable = findViewById(R.id.cb24HourUnstoppable)
        etStepDelaySec = findViewById(R.id.etStepDelaySec)
        tvConsoleLog = findViewById(R.id.tvConsoleLog)

        btnToggleFloatingPod.setOnClickListener {
            val service = AutoTouchService.instance
            if (service == null) {
                Toast.makeText(this, "Enable Auto-Touch in Settings first!", Toast.LENGTH_SHORT).show()
                switchTab(5)
                return@setOnClickListener
            }
            val visible = service.overlayHud?.togglePodVisibility() ?: false
            btnToggleFloatingPod.text = if (visible) "Pod: ON" else "Pod: OFF"
        }
    }

    private fun setupBottomNavigation() {
        navTabChat.setOnClickListener { switchTab(1) }
        navTabWeb.setOnClickListener { switchTab(2) }
        navTabPipelines.setOnClickListener { switchTab(3) }
        navTabDoctor.setOnClickListener { switchTab(4) }
        navTabSettings.setOnClickListener { switchTab(5) }
    }

    private fun switchTab(tabIndex: Int) {
        tabChatLayout.visibility = if (tabIndex == 1) View.VISIBLE else View.GONE
        tabWebLayout.visibility = if (tabIndex == 2) View.VISIBLE else View.GONE
        tabPipelinesLayout.visibility = if (tabIndex == 3) View.VISIBLE else View.GONE
        tabDoctorLayout.visibility = if (tabIndex == 4) View.VISIBLE else View.GONE
        tabSettingsLayout.visibility = if (tabIndex == 5) View.VISIBLE else View.GONE

        val activeColor = 0xFF3B82F6.toInt()
        val inactiveColor = 0xFF94A3B8.toInt()

        navTabChat.setTextColor(if (tabIndex == 1) activeColor else inactiveColor)
        navTabWeb.setTextColor(if (tabIndex == 2) activeColor else inactiveColor)
        navTabPipelines.setTextColor(if (tabIndex == 3) activeColor else inactiveColor)
        navTabDoctor.setTextColor(if (tabIndex == 4) activeColor else inactiveColor)
        navTabSettings.setTextColor(if (tabIndex == 5) activeColor else inactiveColor)

        tvActiveSubtitle.text = when (tabIndex) {
            1 -> "Interactive AI Chat & Task Planner"
            2 -> "Background Web Studio (No Screen Disturbance)"
            3 -> "YouTube Auto-Uploader & 24/7 Scheduler"
            4 -> "Phone Settings Doctor & Personal Memory Vault"
            else -> "System Permissions, Customization & Live Logs"
        }

        if (tabIndex == 2) {
            AutoTouchService.instance?.overlayHud?.attachWebViewToContainer(webPreviewContainer)
        } else if (tabIndex == 3) {
            refreshFolderQueueStatus()
        }
    }

    // ====================================================================
    // TAB 1: INTERACTIVE CHAT & PLANNER
    // ====================================================================
    private fun setupTab1ChatAndPlanner() {
        findViewById<Button>(R.id.btnClearChat).setOnClickListener {
            tvChatHistory.text = "🤖 Companion Bot: Chat saaf kar di gayi hai. Naya kaam batayein!"
        }

        findViewById<Button>(R.id.btnVoiceMic).setOnClickListener {
            try {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "AuraDroid AI ko apna kaam bolein...")
                }
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQ_VOICE_SPEECH)
            } catch (e: Exception) {
                Toast.makeText(this, "Voice input not supported on this device", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnSendChat).setOnClickListener {
            saveSettings()
            val userMsg = etChatInput.text.toString().trim()
            if (userMsg.isEmpty()) {
                Toast.makeText(this, "Pehle apna message ya kaam likhein!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val apiKey = etApiKey.text.toString().trim()
            val model = etModelName.text.toString().trim()
            val vault = etMemoryVault.text.toString().trim()

            val updatedChat = "${tvChatHistory.text}\n\n👤 You: $userMsg\n\n🤖 Companion Bot: Soch raha hoon aur plan bana raha hoon..."
            tvChatHistory.text = updatedChat
            etChatInput.setText("")

            uiScope.launch {
                val planRes = GeminiBrain.chatAndBuildPlan(
                    apiKey = apiKey,
                    model = model,
                    userMessage = userMsg,
                    chatHistory = updatedChat,
                    memoryVault = vault
                )

                tvChatHistory.text = "${updatedChat.substringBeforeLast("🤖 Companion Bot:")}🤖 Companion Bot: ${planRes.replyText}"
                tvPlanSteps.text = "🎯 Goal: ${planRes.planGoal}\n\n${planRes.planSteps}"

                currentLockedGoal = planRes.planGoal
                currentSuggestedUrl = planRes.targetUrl
                if (planRes.targetUrl.isNotBlank()) {
                    etWebUrl.setText(planRes.targetUrl)
                }
                etWebTaskGoal.setText(planRes.planGoal)

                if (planRes.suggestedMode == "PHONE") {
                    rbModePhoneScreen.isChecked = true
                } else {
                    rbModeBackgroundWeb.isChecked = true
                }

                VideoQueueManager.saveApprovedPlan(this@MainActivity, planRes.planGoal, planRes.planSteps)
            }
        }

        findViewById<Button>(R.id.btnExecutePlan).setOnClickListener {
            saveSettings()
            val service = AutoTouchService.instance
            if (service == null) {
                Toast.makeText(this, "Pehle Settings tab se 'Enable Auto-Touch' ON karein!", Toast.LENGTH_LONG).show()
                switchTab(5)
                return@setOnClickListener
            }

            val goalToRun = currentLockedGoal.ifBlank { etChatInput.text.toString().trim() }
            if (goalToRun.isBlank()) {
                Toast.makeText(this, "Pehle AI se baat karke plan banayein ya kaam likhein!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val apiKey = etApiKey.text.toString().trim()
            val model = etModelName.text.toString().trim()
            val folder = etFolderPath.text.toString().trim()

            if (goalToRun.contains("youtube", true) && !rbModeBackgroundWeb.isChecked) {
                service.startYouTubeAutoUpload(apiKey, model, folder, goalToRun, cbPipelineAiVideoFirst.isChecked)
            } else if (rbModeBackgroundWeb.isChecked) {
                val url = currentSuggestedUrl.ifBlank { etWebUrl.text.toString().trim() }
                service.startBackgroundWebTask(
                    apiKey = apiKey,
                    model = model,
                    initialUrl = url,
                    userGoal = goalToRun,
                    folderPath = folder,
                    chainYouTubeAfterDownload = goalToRun.contains("youtube", true)
                )
                Toast.makeText(this, "Background Web Pod mein kaam shuru ho gaya!", Toast.LENGTH_SHORT).show()
            } else {
                service.startUniversalAiTask(apiKey, model, goalToRun)
            }
        }

        findViewById<Button>(R.id.btnSavePlanSkill).setOnClickListener {
            if (currentLockedGoal.isNotBlank()) {
                val oldVault = etMemoryVault.text.toString()
                val updatedVault = "$oldVault\nSaved Routine: $currentLockedGoal".trim()
                etMemoryVault.setText(updatedVault)
                VideoQueueManager.saveMemoryVault(this, updatedVault)
                Toast.makeText(this, "Plan aapke Memory Vault mein save ho gaya!", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnStopAgent).setOnClickListener {
            AutoTouchService.instance?.stopCurrentJob()
        }
    }

    // ====================================================================
    // TAB 2: BACKGROUND WEB STUDIO
    // ====================================================================
    private fun setupTab2WebStudio() {
        findViewById<Button>(R.id.btnWebGemini).setOnClickListener {
            loadUrlInWebStudio("https://gemini.google.com")
        }
        findViewById<Button>(R.id.btnWebVideoAi).setOnClickListener {
            loadUrlInWebStudio("https://wan.video")
        }
        findViewById<Button>(R.id.btnWebYtStudio).setOnClickListener {
            loadUrlInWebStudio("https://studio.youtube.com")
        }
        findViewById<Button>(R.id.btnWebGitHub).setOnClickListener {
            loadUrlInWebStudio("https://github.com")
        }

        findViewById<Button>(R.id.btnWebGo).setOnClickListener {
            var url = etWebUrl.text.toString().trim()
            if (!url.startsWith("http")) url = "https://$url"
            loadUrlInWebStudio(url)
        }

        findViewById<Button>(R.id.btnRunWebAgent).setOnClickListener {
            saveSettings()
            val service = AutoTouchService.instance
            if (service == null) {
                Toast.makeText(this, "Pehle Settings tab mein 'Enable Auto-Touch' ON karein!", Toast.LENGTH_LONG).show()
                switchTab(5)
                return@setOnClickListener
            }
            val goal = etWebTaskGoal.text.toString().trim().ifEmpty {
                "Inspect this webpage, fill required fields or generate/download video."
            }
            service.startBackgroundWebTask(
                apiKey = etApiKey.text.toString().trim(),
                model = etModelName.text.toString().trim(),
                initialUrl = etWebUrl.text.toString().trim(),
                userGoal = goal,
                folderPath = etFolderPath.text.toString().trim(),
                chainYouTubeAfterDownload = goal.contains("youtube", true)
            )
        }
    }

    private fun loadUrlInWebStudio(url: String) {
        etWebUrl.setText(url)
        val service = AutoTouchService.instance
        if (service != null) {
            service.overlayHud?.attachWebViewToContainer(webPreviewContainer)
            service.overlayHud?.getOrCreateSharedWebView()?.loadUrl(url)
        } else {
            Toast.makeText(this, "Enable Auto-Touch in Settings tab to activate Web Pod", Toast.LENGTH_SHORT).show()
        }
    }

    // ====================================================================
    // TAB 3: YOUTUBE AUTO-UPLOAD & 24/7 SCHEDULER
    // ====================================================================
    private fun setupTab3YouTubePipelines() {
        findViewById<Button>(R.id.btnScanFolder).setOnClickListener {
            saveSettings()
            refreshFolderQueueStatus()
        }

        findViewById<Button>(R.id.btnResetHistory).setOnClickListener {
            VideoQueueManager.resetUploadedHistory(this)
            refreshFolderQueueStatus()
            VideoQueueManager.appendLog(this, "🔄 Uploaded videos history cleared.")
        }

        findViewById<Button>(R.id.btnSaveSchedule).setOnClickListener {
            saveSettings()
            val h = etHour.text.toString().toIntOrNull()?.coerceIn(0, 23) ?: 18
            val m = etMinute.text.toString().toIntOrNull()?.coerceIn(0, 59) ?: 0
            TaskSchedulerReceiver.scheduleDailyUpload(this, h, m)
            VideoQueueManager.appendLog(
                this,
                "⏰ 24/7 Daily Auto-Upload Scheduled for ${String.format("%02d:%02d", h, m)}!"
            )
            Toast.makeText(this, "Daily Scheduled at ${String.format("%02d:%02d", h, m)}!", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnRunYouTubeNow).setOnClickListener {
            saveSettings()
            val service = AutoTouchService.instance
            if (service == null) {
                Toast.makeText(this, "Please Enable Auto-Touch in Settings tab first!", Toast.LENGTH_LONG).show()
                switchTab(5)
                return@setOnClickListener
            }
            service.startYouTubeAutoUpload(
                apiKey = etApiKey.text.toString().trim(),
                model = etModelName.text.toString().trim(),
                folderPath = etFolderPath.text.toString().trim(),
                userPrompt = etAiCommand.text.toString().trim(),
                createAiVideoIfEmpty = cbPipelineAiVideoFirst.isChecked
            )
        }
    }

    // ====================================================================
    // TAB 4: PHONE DOCTOR & MEMORY VAULT
    // ====================================================================
    private fun setupTab4DoctorAndVault() {
        findViewById<Button>(R.id.btnDocBattery).setOnClickListener {
            etDoctorProblem.setText("Open Battery settings, inspect battery usage and enable Battery Saver / optimization.")
            runPhoneDoctorWithAction("BATTERY")
        }

        findViewById<Button>(R.id.btnDocWifi).setOnClickListener {
            etDoctorProblem.setText("Open Wi-Fi / Network settings and check connection status.")
            runPhoneDoctorWithAction("WIFI")
        }

        findViewById<Button>(R.id.btnDocStorage).setOnClickListener {
            etDoctorProblem.setText("Open Storage settings and check available free space.")
            runPhoneDoctorWithAction("STORAGE")
        }

        findViewById<Button>(R.id.btnRunPhoneDoctor).setOnClickListener {
            runPhoneDoctorWithAction("SETTINGS")
        }

        findViewById<Button>(R.id.btnSaveMemory).setOnClickListener {
            val text = etMemoryVault.text.toString()
            VideoQueueManager.saveMemoryVault(this, text)
            Toast.makeText(this, "Personal Memory Vault Saved!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runPhoneDoctorWithAction(settingTarget: String) {
        saveSettings()
        val service = AutoTouchService.instance
        if (service == null) {
            Toast.makeText(this, "Enable Auto-Touch in Settings tab first!", Toast.LENGTH_LONG).show()
            switchTab(5)
            return
        }
        val problem = etDoctorProblem.text.toString().trim().ifEmpty {
            "Open Android Settings and check $settingTarget configuration."
        }
        service.startUniversalAiTask(
            apiKey = etApiKey.text.toString().trim(),
            model = etModelName.text.toString().trim(),
            userGoal = problem,
            initialSettingAction = settingTarget
        )
    }

    // ====================================================================
    // TAB 5: SETTINGS, PERMISSIONS & LIVE LOGS
    // ====================================================================
    private fun setupTab5SettingsAndLogs() {
        findViewById<Button>(R.id.btnEnableAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Find 'AuraDroid AI Autonomous Companion' and turn it ON", Toast.LENGTH_LONG).show()
        }

        findViewById<Button>(R.id.btnEnableStorage).setOnClickListener {
            requestStorageAccess()
        }

        findViewById<Button>(R.id.btnSaveSettings).setOnClickListener {
            saveSettings()
            Toast.makeText(this, "All Custom Settings Saved!", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnClearLogs).setOnClickListener {
            VideoQueueManager.clearLogs(this)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VOICE_SPEECH && resultCode == Activity.RESULT_OK && data != null) {
            val spoken = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                etChatInput.setText(spoken)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceBadge()
        refreshFolderQueueStatus()
    }

    private fun updateServiceBadge() {
        val active = AutoTouchService.instance != null
        if (active) {
            tvServiceBadge.text = "TOUCH: ACTIVE"
            tvServiceBadge.setTextColor(0xFF10B981.toInt())
        } else {
            tvServiceBadge.text = "TOUCH: OFF"
            tvServiceBadge.setTextColor(0xFFF87171.toInt())
        }
    }

    private fun refreshFolderQueueStatus() {
        val path = etFolderPath.text.toString().trim()
        val dir = File(path)
        if (!dir.exists()) dir.mkdirs()

        val allVideos = VideoQueueManager.getVideoFilesInFolder(path)
        val uploaded = VideoQueueManager.getUploadedSet(this)
        val pendingCount = allVideos.count { it.absolutePath !in uploaded && it.name !in uploaded }
        val uploadedCount = allVideos.size - pendingCount
        val nextName = VideoQueueManager.getNextPendingVideo(this, path)?.name ?: "None"

        tvQueueStatus.text = "Total: ${allVideos.size} | Pending: $pendingCount | Uploaded: $uploadedCount\nNext: $nextName"
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "Folder Access Already Granted!", Toast.LENGTH_SHORT).show()
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                101
            )
        }
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences(VideoQueueManager.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString("api_key", etApiKey.text.toString().trim())
            .putString("model_name", etModelName.text.toString().trim())
            .putString("folder_path", etFolderPath.text.toString().trim())
            .putString("ai_command", etAiCommand.text.toString().trim())
            .putBoolean("pipeline_ai_video_first", cbPipelineAiVideoFirst.isChecked)
            .putBoolean("use_screen_vision", cbUseScreenVision.isChecked)
            .putBoolean("unstoppable_24h", cb24HourUnstoppable.isChecked)
            .putInt("step_delay_sec", etStepDelaySec.text.toString().toIntOrNull()?.coerceIn(1, 30) ?: 3)
            .putInt("sched_hour", etHour.text.toString().toIntOrNull() ?: 18)
            .putInt("sched_min", etMinute.text.toString().toIntOrNull() ?: 0)
            .apply()
        VideoQueueManager.saveMemoryVault(this, etMemoryVault.text.toString())
    }

    private fun loadSavedSettings() {
        val prefs = getSharedPreferences(VideoQueueManager.PREFS_NAME, Context.MODE_PRIVATE)
        etApiKey.setText(prefs.getString("api_key", ""))
        etModelName.setText(prefs.getString("model_name", "gemini-2.5-flash"))
        etFolderPath.setText(prefs.getString("folder_path", "/storage/emulated/0/Download/AutoVideos"))
        etAiCommand.setText(
            prefs.getString(
                "ai_command",
                "Upload next pending video from folder to YouTube as Public with viral Hindi/English title, description and tags."
            )
        )
        cbPipelineAiVideoFirst.isChecked = prefs.getBoolean("pipeline_ai_video_first", false)
        cbUseScreenVision.isChecked = prefs.getBoolean("use_screen_vision", true)
        cb24HourUnstoppable.isChecked = prefs.getBoolean("unstoppable_24h", true)
        etStepDelaySec.setText(prefs.getInt("step_delay_sec", 3).toString())
        etHour.setText(prefs.getInt("sched_hour", 18).toString())
        etMinute.setText(String.format("%02d", prefs.getInt("sched_min", 0)))
        etMemoryVault.setText(VideoQueueManager.getMemoryVault(this))

        val (savedGoal, savedSteps) = VideoQueueManager.getApprovedPlan(this)
        if (savedGoal.isNotBlank()) {
            currentLockedGoal = savedGoal
            tvPlanSteps.text = "🎯 Goal: $savedGoal\n\n$savedSteps"
        }

        val savedLogs = prefs.getString("live_logs", null)
        if (!savedLogs.isNullOrBlank()) {
            tvConsoleLog.text = savedLogs
        }
    }
}
