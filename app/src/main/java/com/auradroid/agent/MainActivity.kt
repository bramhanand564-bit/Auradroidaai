package com.auradroid.agent

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
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
        var onBrowserUrlChanged: ((String) -> Unit)? = null
        private const val REQ_VOICE_SPEECH = 202
    }

    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var tvActiveSubtitle: TextView
    private lateinit var tvServiceBadge: TextView
    private lateinit var btnToggleFloatingPod: Button

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

    // Tab 2: Full-Control Browser
    private lateinit var etWebUrl: EditText
    private lateinit var etWebTaskGoal: EditText
    private lateinit var btnBrowserDesktop: Button
    private lateinit var webPreviewContainer: FrameLayout

    // Tab 3: Google Flow 8s -> Daily 7/8 PM YouTube Pipeline
    private lateinit var etFlowTopic: EditText
    private lateinit var etDailyVideoLimit: EditText
    private lateinit var cbAutoGenerateFlowFirst: CheckBox
    private lateinit var etHour: EditText
    private lateinit var etMinute: EditText
    private lateinit var etFolderPath: EditText
    private lateinit var tvQueueStatus: TextView

    // Tab 4: Vault
    private lateinit var etMemoryVault: EditText

    // Tab 5: Settings & Logs
    private lateinit var etApiKey: EditText
    private lateinit var etModelName: EditText
    private lateinit var cbUseScreenVision: CheckBox
    private lateinit var cb24HourUnstoppable: CheckBox
    private lateinit var tvConsoleLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        loadSavedSettings()
        setupBottomNavigation()
        setupTab1ChatAndPlanner()
        setupTab2FullBrowser()
        setupTab3FlowAndYouTubePipelines()
        setupTab4Vault()
        setupTab5Settings()

        onLogUpdated = { logs ->
            runOnUiThread { tvConsoleLog.text = logs }
        }
        onBrowserUrlChanged = { currentUrl ->
            runOnUiThread {
                if (!etWebUrl.hasFocus()) {
                    etWebUrl.setText(currentUrl)
                }
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
        btnBrowserDesktop = findViewById(R.id.btnBrowserDesktop)
        webPreviewContainer = findViewById(R.id.webPreviewContainer)

        etFlowTopic = findViewById(R.id.etFlowTopic)
        etDailyVideoLimit = findViewById(R.id.etDailyVideoLimit)
        cbAutoGenerateFlowFirst = findViewById(R.id.cbAutoGenerateFlowFirst)
        etHour = findViewById(R.id.etHour)
        etMinute = findViewById(R.id.etMinute)
        etFolderPath = findViewById(R.id.etFolderPath)
        tvQueueStatus = findViewById(R.id.tvQueueStatus)

        etMemoryVault = findViewById(R.id.etMemoryVault)

        etApiKey = findViewById(R.id.etApiKey)
        etModelName = findViewById(R.id.etModelName)
        cbUseScreenVision = findViewById(R.id.cbUseScreenVision)
        cb24HourUnstoppable = findViewById(R.id.cb24HourUnstoppable)
        tvConsoleLog = findViewById(R.id.tvConsoleLog)

        btnToggleFloatingPod.setOnClickListener {
            val service = AutoTouchService.instance
            if (service == null) {
                Toast.makeText(this, "Enable Auto-Touch in Settings first!", Toast.LENGTH_SHORT).show()
                switchTab(5)
                return@setOnClickListener
            }
            val visible = service.overlayHud?.togglePodVisibility() ?: false
            btnToggleFloatingPod.text = if (visible) "Bot: ON" else "Bot: OFF"
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
            1 -> "Interactive AI Chat & Universal Planner"
            2 -> "Full-Control Browser (Login Gmail & Google Flow)"
            3 -> "Google Flow (8s Video) ➔ Daily 7/8 PM YouTube"
            4 -> "Personal Memory Vault & Custom Rules"
            else -> "Engine Settings, Keep-Alive Lock & Live Logs"
        }

        if (tabIndex == 2) {
            val service = AutoTouchService.instance
            if (service != null) {
                service.overlayHud?.attachWebViewToContainer(webPreviewContainer)
            } else {
                Toast.makeText(this, "Enable Auto-Touch in Settings tab first to activate Browser!", Toast.LENGTH_LONG).show()
            }
        } else if (tabIndex == 3) {
            refreshFolderQueueStatus()
        }
    }

    // ====================================================================
    // TAB 1: UNIVERSAL CHAT & PLANNER
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
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Apna kaam bolein...")
                }
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQ_VOICE_SPEECH)
            } catch (_: Exception) {
                Toast.makeText(this, "Voice input not available", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnSendChat).setOnClickListener {
            saveSettings()
            val userMsg = etChatInput.text.toString().trim()
            if (userMsg.isEmpty()) return@setOnClickListener

            val updatedChat = "${tvChatHistory.text}\n\n👤 You: $userMsg\n\n🤖 Companion Bot: Soch raha hoon aur plan bana raha hoon..."
            tvChatHistory.text = updatedChat
            etChatInput.setText("")

            uiScope.launch {
                val planRes = GeminiBrain.chatAndBuildPlan(
                    etApiKey.text.toString().trim(),
                    etModelName.text.toString().trim(),
                    userMsg,
                    updatedChat,
                    etMemoryVault.text.toString().trim()
                )
                tvChatHistory.text = "${updatedChat.substringBeforeLast("🤖 Companion Bot:")}🤖 Companion Bot: ${planRes.replyText}"
                tvPlanSteps.text = "🎯 Goal: ${planRes.planGoal}\n\n${planRes.planSteps}"
                currentLockedGoal = planRes.planGoal
                etWebTaskGoal.setText(planRes.planGoal)
                if (planRes.targetUrl.isNotBlank()) etWebUrl.setText(planRes.targetUrl)

                if (planRes.suggestedMode == "PHONE") {
                    rbModePhoneScreen.isChecked = true
                } else {
                    rbModeBackgroundWeb.isChecked = true
                }
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
            val goal = currentLockedGoal.ifBlank { etChatInput.text.toString().trim() }
            if (goal.isBlank()) {
                Toast.makeText(this, "Pehle chat mein apna kaam likhkar plan banayein!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (rbModeBackgroundWeb.isChecked) {
                service.startBackgroundWebTask(
                    etApiKey.text.toString().trim(),
                    etModelName.text.toString().trim(),
                    etWebUrl.text.toString().trim(),
                    goal,
                    etFolderPath.text.toString().trim()
                )
                Toast.makeText(this, "Background Browser mein kaam shuru ho gaya!", Toast.LENGTH_SHORT).show()
            } else {
                service.startUniversalAiTask(
                    etApiKey.text.toString().trim(),
                    etModelName.text.toString().trim(),
                    goal
                )
            }
        }

        findViewById<Button>(R.id.btnStopAgent).setOnClickListener {
            AutoTouchService.instance?.stopCurrentJob()
        }
    }

    // ====================================================================
    // TAB 2: FULL-CONTROL IN-APP BROWSER (MANUAL GMAIL & GOOGLE FLOW)
    // ====================================================================
    private fun setupTab2FullBrowser() {
        findViewById<Button>(R.id.btnBrowserBack).setOnClickListener {
            val wv = AutoTouchService.instance?.overlayHud?.getOrCreateSharedWebView()
            if (wv?.canGoBack() == true) wv.goBack()
        }

        findViewById<Button>(R.id.btnBrowserForward).setOnClickListener {
            val wv = AutoTouchService.instance?.overlayHud?.getOrCreateSharedWebView()
            if (wv?.canGoForward() == true) wv.goForward()
        }

        findViewById<Button>(R.id.btnBrowserRefresh).setOnClickListener {
            AutoTouchService.instance?.overlayHud?.getOrCreateSharedWebView()?.reload()
        }

        findViewById<Button>(R.id.btnBrowserFlow).setOnClickListener {
            val url = VideoQueueManager.getPinnedFlowUrl(this)
            openManualUrl(url)
        }

        findViewById<Button>(R.id.btnBrowserGmail).setOnClickListener {
            openManualUrl("[https://accounts.google.com](https://accounts.google.com)")
        }

        btnBrowserDesktop.setOnClickListener {
            val hud = AutoTouchService.instance?.overlayHud ?: return@setOnClickListener
            val isDesk = hud.toggleDesktopMode()
            btnBrowserDesktop.text = if (isDesk) "🖥️ Desktop: ON" else "🖥️ Desktop: OFF"
        }

        findViewById<Button>(R.id.btnBrowserDownloads).setOnClickListener {
            val folder = etFolderPath.text.toString().trim()
            val report = VideoQueueManager.getDownloadedFilesReport(folder)
            AlertDialog.Builder(this)
                .setTitle("📥 Browser Downloads & Storage")
                .setMessage(report)
                .setPositiveButton("OK", null)
                .show()
        }

        findViewById<Button>(R.id.btnWebGo).setOnClickListener {
            var url = etWebUrl.text.toString().trim()
            if (!url.startsWith("http")) url = "https://$url"
            openManualUrl(url)
        }

        findViewById<Button>(R.id.btnPinFlowUrl).setOnClickListener {
            val currentUrl = AutoTouchService.instance?.overlayHud?.getOrCreateSharedWebView()?.url
                ?: etWebUrl.text.toString().trim()
            VideoQueueManager.savePinnedFlowUrl(this, currentUrl)
            etWebUrl.setText(currentUrl)
            Toast.makeText(this, "📌 Google Flow URL Pinned for Daily Pipeline!", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnRunWebAgent).setOnClickListener {
            saveSettings()
            val service = AutoTouchService.instance
            if (service == null) {
                Toast.makeText(this, "Enable Auto-Touch in Settings first!", Toast.LENGTH_LONG).show()
                switchTab(5)
                return@setOnClickListener
            }
            val goal = etWebTaskGoal.text.toString().trim().ifEmpty {
                "Create an 8-second video on this page based on: ${etFlowTopic.text}"
            }
            service.startBackgroundWebTask(
                etApiKey.text.toString().trim(),
                etModelName.text.toString().trim(),
                etWebUrl.text.toString().trim(),
                goal,
                etFolderPath.text.toString().trim()
            )
        }
    }

    private fun openManualUrl(url: String) {
        etWebUrl.setText(url)
        val service = AutoTouchService.instance
        if (service != null) {
            service.overlayHud?.attachWebViewToContainer(webPreviewContainer)
            service.overlayHud?.getOrCreateSharedWebView()?.loadUrl(url)
        } else {
            Toast.makeText(this, "Pehle Settings tab se Auto-Touch ON karein!", Toast.LENGTH_SHORT).show()
        }
    }

    // ====================================================================
    // TAB 3: GOOGLE FLOW (8s VIDEO) ➔ DAILY 7/8 PM YOUTUBE PIPELINE
    // ====================================================================
    private fun setupTab3FlowAndYouTubePipelines() {
        findViewById<Button>(R.id.btnSaveSchedule).setOnClickListener {
            saveSettings()
            val h = etHour.text.toString().toIntOrNull()?.coerceIn(0, 23) ?: 19
            val m = etMinute.text.toString().toIntOrNull()?.coerceIn(0, 59) ?: 0
            TaskSchedulerReceiver.scheduleDailyUpload(this, h, m)
            val timeStr = String.format(Locale.US, "%02d:%02d", h, m)
            VideoQueueManager.appendLog(this, "⏰ Daily Flow ➔ YouTube Pipeline Scheduled for $timeStr!")
            Toast.makeText(this, "⏰ Daily Scheduled for $timeStr!", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnRunFlowToYouTubeNow).setOnClickListener {
            saveSettings()
            val service = AutoTouchService.instance
            if (service == null) {
                Toast.makeText(this, "Enable Auto-Touch in Settings first!", Toast.LENGTH_LONG).show()
                switchTab(5)
                return@setOnClickListener
            }
            service.startGoogleFlowToYouTubePipeline(
                apiKey = etApiKey.text.toString().trim(),
                model = etModelName.text.toString().trim(),
                flowUrl = VideoQueueManager.getPinnedFlowUrl(this),
                topicIdea = etFlowTopic.text.toString().trim(),
                dailyVideoCount = etDailyVideoLimit.text.toString().toIntOrNull() ?: 1,
                folderPath = etFolderPath.text.toString().trim()
            )
        }

        findViewById<Button>(R.id.btnScanFolder).setOnClickListener {
            saveSettings()
            refreshFolderQueueStatus()
        }

        findViewById<Button>(R.id.btnResetHistory).setOnClickListener {
            VideoQueueManager.resetUploadedHistory(this)
            refreshFolderQueueStatus()
            VideoQueueManager.appendLog(this, "🔄 Uploaded history reset.")
        }

        findViewById<Button>(R.id.btnRunYouTubeNow).setOnClickListener {
            saveSettings()
            val service = AutoTouchService.instance
            if (service == null) {
                Toast.makeText(this, "Enable Auto-Touch in Settings first!", Toast.LENGTH_LONG).show()
                switchTab(5)
                return@setOnClickListener
            }
            service.startYouTubeAutoUpload(
                etApiKey.text.toString().trim(),
                etModelName.text.toString().trim(),
                etFolderPath.text.toString().trim(),
                etFlowTopic.text.toString().trim()
            )
        }
    }

    // ====================================================================
    // TAB 4: MEMORY VAULT
    // ====================================================================
    private fun setupTab4Vault() {
        findViewById<Button>(R.id.btnSaveMemory).setOnClickListener {
            VideoQueueManager.saveMemoryVault(this, etMemoryVault.text.toString())
            Toast.makeText(this, "Memory Vault Saved!", Toast.LENGTH_SHORT).show()
        }
    }

    // ====================================================================
    // TAB 5: SETTINGS & BATTERY KILL PROTECTION
    // ====================================================================
    private fun setupTab5Settings() {
        findViewById<Button>(R.id.btnEnableAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Turn ON 'AuraDroid AI Universal Companion'", Toast.LENGTH_LONG).show()
        }

        findViewById<Button>(R.id.btnEnableStorage).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
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

        findViewById<Button>(R.id.btnDisableBatteryOptimization).setOnClickListener {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "✅ Battery Kill Protection is ALREADY Active!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        findViewById<Button>(R.id.btnSaveSettings).setOnClickListener {
            saveSettings()
            Toast.makeText(this, "Settings Saved!", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VOICE_SPEECH && resultCode == Activity.RESULT_OK && data != null) {
            val spoken = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) etChatInput.setText(spoken)
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceBadge()
        refreshFolderQueueStatus()
    }

    private fun updateServiceBadge() {
        val active = AutoTouchService.instance != null
        tvServiceBadge.text = if (active) "TOUCH: ACTIVE" else "TOUCH: OFF"
        tvServiceBadge.setTextColor(if (active) 0xFF10B981.toInt() else 0xFFF87171.toInt())
    }

    private fun refreshFolderQueueStatus() {
        val path = etFolderPath.text.toString().trim()
        val dir = File(path)
        if (!dir.exists()) dir.mkdirs()
        val allVideos = VideoQueueManager.getVideoFilesInFolder(path)
        val uploaded = VideoQueueManager.getUploadedSet(this)
        val pending = allVideos.count { it.absolutePath !in uploaded && it.name !in uploaded }
        val nextName = VideoQueueManager.getNextPendingVideo(this, path)?.name ?: "None"
        tvQueueStatus.text = "Total: ${allVideos.size} | Pending: $pending | Uploaded: ${allVideos.size - pending}\nNext: $nextName"
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences(VideoQueueManager.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString("api_key", etApiKey.text.toString().trim())
            .putString("model_name", etModelName.text.toString().trim())
            .putString("folder_path", etFolderPath.text.toString().trim())
            .putString("flow_topic", etFlowTopic.text.toString().trim())
            .putInt("daily_video_limit", etDailyVideoLimit.text.toString().toIntOrNull()?.coerceIn(1, 5) ?: 1)
            .putBoolean("auto_flow_first", cbAutoGenerateFlowFirst.isChecked)
            .putInt("sched_hour", etHour.text.toString().toIntOrNull()?.coerceIn(0, 23) ?: 19)
            .putInt("sched_min", etMinute.text.toString().toIntOrNull()?.coerceIn(0, 59) ?: 0)
            .putBoolean("use_screen_vision", cbUseScreenVision.isChecked)
            .putBoolean("unstoppable_24h", cb24HourUnstoppable.isChecked)
            .apply()
        VideoQueueManager.saveMemoryVault(this, etMemoryVault.text.toString())
    }

    private fun loadSavedSettings() {
        val prefs = getSharedPreferences(VideoQueueManager.PREFS_NAME, Context.MODE_PRIVATE)
        etApiKey.setText(prefs.getString("api_key", ""))
        etModelName.setText(prefs.getString("model_name", "gemini-2.5-flash"))
        etFolderPath.setText(prefs.getString("folder_path", "/storage/emulated/0/Download/AutoVideos"))
        etWebUrl.setText(VideoQueueManager.getPinnedFlowUrl(this))
        etFlowTopic.setText(VideoQueueManager.getFlowTopic(this))
        etDailyVideoLimit.setText(VideoQueueManager.getDailyVideoLimit(this).toString())
        cbAutoGenerateFlowFirst.isChecked = prefs.getBoolean("auto_flow_first", true)
        etHour.setText(prefs.getInt("sched_hour", 19).toString())
        etMinute.setText(String.format(Locale.US, "%02d", prefs.getInt("sched_min", 0)))
        cbUseScreenVision.isChecked = prefs.getBoolean("use_screen_vision", true)
        cb24HourUnstoppable.isChecked = prefs.getBoolean("unstoppable_24h", true)
        etMemoryVault.setText(VideoQueueManager.getMemoryVault(this))
        val logs = prefs.getString("live_logs", null)
        if (!logs.isNullOrBlank()) tvConsoleLog.text = logs
    }
}
