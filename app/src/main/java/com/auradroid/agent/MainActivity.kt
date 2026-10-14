package com.auradroid.agent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        var onLogUpdated: ((String) -> Unit)? = null
    }

    private lateinit var tvServiceBadge: TextView
    private lateinit var tvQueueStatus: TextView
    private lateinit var tvConsoleLog: TextView
    private lateinit var etApiKey: EditText
    private lateinit var etModelName: EditText
    private lateinit var etFolderPath: EditText
    private lateinit var etHour: EditText
    private lateinit var etMinute: EditText
    private lateinit var etAiCommand: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvServiceBadge = findViewById(R.id.tvServiceBadge)
        tvQueueStatus = findViewById(R.id.tvQueueStatus)
        tvConsoleLog = findViewById(R.id.tvConsoleLog)
        etApiKey = findViewById(R.id.etApiKey)
        etModelName = findViewById(R.id.etModelName)
        etFolderPath = findViewById(R.id.etFolderPath)
        etHour = findViewById(R.id.etHour)
        etMinute = findViewById(R.id.etMinute)
        etAiCommand = findViewById(R.id.etAiCommand)

        loadSavedSettings()

        onLogUpdated = { logs ->
            runOnUiThread {
                tvConsoleLog.text = logs
            }
        }

        findViewById<Button>(R.id.btnEnableAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Find 'AuraDroid AI' and turn it ON", Toast.LENGTH_LONG).show()
        }

        findViewById<Button>(R.id.btnEnableStorage).setOnClickListener {
            requestStorageAccess()
        }

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
                "⏰ Daily Auto-Upload Scheduled for ${String.format("%02d:%02d", h, m)} every day!"
            )
            Toast.makeText(this, "Scheduled Daily at $h:$m!", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnRunYouTubeNow).setOnClickListener {
            saveSettings()
            val service = AutoTouchService.instance
            if (service == null) {
                Toast.makeText(this, "Please Enable Auto-Touch Service first!", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }
            service.startYouTubeAutoUpload(
                apiKey = etApiKey.text.toString(),
                model = etModelName.text.toString(),
                folderPath = etFolderPath.text.toString(),
                userPrompt = etAiCommand.text.toString()
            )
        }

        findViewById<Button>(R.id.btnRunUniversalTask).setOnClickListener {
            saveSettings()
            val service = AutoTouchService.instance
            if (service == null) {
                Toast.makeText(this, "Please Enable Auto-Touch Service first!", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }
            service.startUniversalAiTask(
                apiKey = etApiKey.text.toString(),
                model = etModelName.text.toString(),
                userGoal = etAiCommand.text.toString()
            )
        }

        findViewById<Button>(R.id.btnStopAgent).setOnClickListener {
            AutoTouchService.instance?.stopCurrentJob()
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
            tvServiceBadge.setTextColor(0xFFFF5252.toInt())
        }
    }

    private fun refreshFolderQueueStatus() {
        val path = etFolderPath.text.toString().trim()
        val dir = File(path)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val allVideos = VideoQueueManager.getVideoFilesInFolder(path)
        val uploaded = VideoQueueManager.getUploadedSet(this)
        val pendingCount = allVideos.count { it.absolutePath !in uploaded && it.name !in uploaded }
        val uploadedCount = allVideos.size - pendingCount
        val nextName = VideoQueueManager.getNextPendingVideo(this, path)?.name ?: "None"

        tvQueueStatus.text = "Total: ${allVideos.size} | Pending: $pendingCount | Uploaded: $uploadedCount | Next: $nextName"
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "All Files Access Already Granted!", Toast.LENGTH_SHORT).show()
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
        val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("api_key", etApiKey.text.toString().trim())
            .putString("model_name", etModelName.text.toString().trim())
            .putString("folder_path", etFolderPath.text.toString().trim())
            .putString("ai_command", etAiCommand.text.toString().trim())
            .putInt("sched_hour", etHour.text.toString().toIntOrNull() ?: 18)
            .putInt("sched_min", etMinute.text.toString().toIntOrNull() ?: 0)
            .apply()
    }

    private fun loadSavedSettings() {
        val prefs = getSharedPreferences("auradroid_prefs", Context.MODE_PRIVATE)
        etApiKey.setText(prefs.getString("api_key", ""))
        etModelName.setText(prefs.getString("model_name", "gemini-2.5-flash"))
        etFolderPath.setText(prefs.getString("folder_path", "/storage/emulated/0/Download/AutoVideos"))
        etAiCommand.setText(
            prefs.getString(
                "ai_command",
                "Upload next pending video from folder to YouTube as Public with viral Hindi/English title, description and tags."
            )
        )
        etHour.setText(prefs.getInt("sched_hour", 18).toString())
        etMinute.setText(String.format("%02d", prefs.getInt("sched_min", 0)))
        val savedLogs = prefs.getString("live_logs", null)
        if (!savedLogs.isNullOrBlank()) {
            tvConsoleLog.text = savedLogs
        }
    }
}
