package com.auradroid.agent

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object VideoQueueManager {
    const val PREFS_NAME = "auradroid_prefs"
    private const val KEY_UPLOADED_SET = "uploaded_videos"

    fun getVideoFilesInFolder(folderPath: String): List<File> {
        val dir = File(folderPath)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles { file ->
            file.isFile && file.extension.lowercase() in listOf("mp4", "mov", "mkv", "webm")
        }?.sortedBy { it.lastModified() } ?: emptyList()
    }

    fun getUploadedSet(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_UPLOADED_SET, emptySet()) ?: emptySet()
    }

    fun getNextPendingVideo(context: Context, folderPath: String): File? {
        val allVideos = getVideoFilesInFolder(folderPath)
        val uploaded = getUploadedSet(context)
        return allVideos.firstOrNull { it.absolutePath !in uploaded && it.name !in uploaded }
    }

    fun findNewlyDownloadedVideo(sinceTimestampMs: Long, targetFolder: String): File? {
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val targetDir = File(targetFolder)
        if (!targetDir.exists()) targetDir.mkdirs()

        val candidates = mutableListOf<File>()
        listOf(downloadDir, targetDir).forEach { dir ->
            dir?.listFiles { f ->
                f.isFile &&
                f.extension.lowercase() in listOf("mp4", "webm", "mov") &&
                f.lastModified() >= sinceTimestampMs &&
                f.length() > 40_000L
            }?.let { candidates.addAll(it) }
        }

        val newest = candidates.maxByOrNull { it.lastModified() } ?: return null
        if (newest.parentFile?.absolutePath != targetDir.absolutePath) {
            val destFile = File(targetDir, newest.name)
            return try {
                newest.copyTo(destFile, overwrite = true)
                destFile
            } catch (e: Exception) {
                newest
            }
        }
        return newest
    }

    /**
     * Shows all downloaded videos & files in Download/AutoVideos and Downloads folder for the in-app Browser Storage dialog.
     */
    fun getDownloadedFilesReport(targetFolder: String): String {
        val targetDir = File(targetFolder)
        if (!targetDir.exists()) targetDir.mkdirs()
        val files = getVideoFilesInFolder(targetFolder).sortedByDescending { it.lastModified() }
        if (files.isEmpty()) {
            return "📂 फोल्डर ($targetFolder) अभी खाली है।\n\nजब Google Flow या ब्राउज़र से कोई वीडियो डाउनलोड होगी, तो वह यहाँ दिखेगी।"
        }
        val uploaded = mutableSetOf<String>()
        val sdf = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
        return files.mapIndexed { idx, file ->
            val sizeMb = String.format(Locale.US, "%.2f MB", file.length() / (1024.0 * 1024.0))
            val dateStr = sdf.format(Date(file.lastModified()))
            "${idx + 1}. 🎬 ${file.name}\n    Size: $sizeMb • $dateStr"
        }.joinToString("\n\n")
    }

    fun markVideoUploaded(context: Context, videoFile: File) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = getUploadedSet(context).toMutableSet()
        current.add(videoFile.absolutePath)
        current.add(videoFile.name)
        prefs.edit().putStringSet(KEY_UPLOADED_SET, current).apply()
    }

    fun resetUploadedHistory(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_UPLOADED_SET).apply()
    }

    fun savePinnedFlowUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("pinned_flow_url", url.trim()).apply()
    }

    fun getPinnedFlowUrl(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("pinned_flow_url", "https://labs.google/fx/tools/flow") ?: "https://labs.google/fx/tools/flow"
    }

    fun getFlowTopic(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(
                "flow_topic",
                "Cinematic 8-second hyper-realistic story of a futuristic AI boy exploring a glowing sci-fi world"
            ) ?: ""
    }

    fun getDailyVideoLimit(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt("daily_video_limit", 1).coerceIn(1, 5)
    }

    fun saveMemoryVault(context: Context, memoryText: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("memory_vault", memoryText.trim()).apply()
    }

    fun getMemoryVault(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(
                "memory_vault",
                "Favorite Video Topic: Futuristic AI & Sci-Fi Stories\nPreferred Language: Hindi + English"
            ) ?: ""
    }

    fun isScreenVisionEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("use_screen_vision", true)
    }

    fun is24HourUnstoppableEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("unstoppable_24h", true)
    }

    fun getStepDelayMs(context: Context): Long {
        val sec = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt("step_delay_sec", 3).coerceIn(1, 30)
        return sec * 1000L
    }

    fun appendLog(context: Context, message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$time] $message"
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val oldLogs = prefs.getString("live_logs", "") ?: ""
        val updated = (oldLogs.lines().takeLast(60) + line).joinToString("\n").trim()
        prefs.edit().putString("live_logs", updated).apply()
        MainActivity.onLogUpdated?.invoke(updated)
    }

    fun clearLogs(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val msg = "[System] Logs cleared."
        prefs.edit().putString("live_logs", msg).apply()
        MainActivity.onLogUpdated?.invoke(msg)
    }
}
