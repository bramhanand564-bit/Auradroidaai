package com.auradroid.agent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private class AuraBrainDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "auradroid_brain.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS episodic_memory (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                domain TEXT,
                rule_learned TEXT UNIQUE ON CONFLICT REPLACE,
                timestamp INTEGER
            )
            """.trimIndent()
        )
        val coreLessons = listOf(
            "GOOGLE_FLOW" to "Never use words like boy, girl, child, kid, minor, or skin in video prompts; always use safe terms like futuristic cybernetic android explorer.",
            "GOOGLE_FLOW" to "Before typing a prompt in Google Flow, switch Nano Banana / Image / x2 mode to Video • 9:16 • 8s • x1 so credits are never wasted.",
            "GOOGLE_FLOW" to "Never type or click arrow_forward more than once per video. While % progress is visible, always WAIT.",
            "GOOGLE_FLOW" to "When video rendering finishes on the project grid, tap the completed video thumbnail card physically to open the video player, tap the center Play button so the MP4 stream loads, and then download it.",
            "WHATSAPP" to "If reply text is already typed inside the bottom Message input box and the green Send button (desc='Send') is visible, immediately click the Send node instead of waiting.",
            "ANDROID_UI" to "Always prefer CLICK_NODE over CLICK_XY. If a tap does not open the expected next page, verify using screenshot and escalate to High Model or physical coordinates."
        )
        for ((dom, rule) in coreLessons) {
            val cv = ContentValues().apply {
                put("domain", dom)
                put("rule_learned", rule)
                put("timestamp", System.currentTimeMillis())
            }
            db.insertWithOnConflict("episodic_memory", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
}

object VideoQueueManager {
    const val PREFS_NAME = "auradroid_prefs"
    private const val KEY_UPLOADED_SET = "uploaded_videos"

    /**
     * User Customizable Setting: Should we upload Newest Video First (true) or Oldest Video First (false)?
     */
    fun isUploadNewestFirst(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("upload_newest_first", true)
    }

    fun setUploadNewestFirst(context: Context, newestFirst: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean("upload_newest_first", newestFirst).apply()
    }

    fun getVideoFilesInFolder(folderPath: String, newestFirst: Boolean = false): List<File> {
        val dir = File(folderPath)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        val files = dir.listFiles { file ->
            file.isFile && file.extension.lowercase() in listOf("mp4", "mov", "mkv", "webm")
        } ?: return emptyList()

        return if (newestFirst) {
            files.sortedByDescending { it.lastModified() }
        } else {
            files.sortedBy { it.lastModified() }
        }
    }

    fun getUploadedSet(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_UPLOADED_SET, emptySet()) ?: emptySet()
    }

    /**
     * Respects the user's customization choice (Newest First vs Oldest First)
     */
    fun getNextPendingVideo(context: Context, folderPath: String): File? {
        val preferNewest = isUploadNewestFirst(context)
        val allVideos = getVideoFilesInFolder(folderPath, newestFirst = preferNewest)
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

    fun getDownloadedFilesReport(targetFolder: String): String {
        val targetDir = File(targetFolder)
        if (!targetDir.exists()) targetDir.mkdirs()
        val files = getVideoFilesInFolder(targetFolder, newestFirst = true)
        if (files.isEmpty()) {
            return "📂 फोल्डर ($targetFolder) अभी खाली है।\n\nजब Google Flow या ब्राउज़र से कोई वीडियो डाउनलोड होगी, तो वह यहाँ दिखेगी।"
        }
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

    fun recordLearnedLesson(context: Context, lesson: String, domain: String = "GLOBAL") {
        val clean = lesson.trim()
        if (clean.length < 6) return
        try {
            val db = AuraBrainDatabase(context).writableDatabase
            val cv = ContentValues().apply {
                put("domain", domain.uppercase())
                put("rule_learned", clean)
                put("timestamp", System.currentTimeMillis())
            }
            db.insertWithOnConflict("episodic_memory", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (_: Exception) {}
    }

    fun getLearnedLessonsSummary(context: Context, domainFilter: String? = null): String {
        return try {
            val db = AuraBrainDatabase(context).readableDatabase
            val list = mutableListOf<String>()
            val cursor = if (domainFilter.isNullOrBlank()) {
                db.rawQuery("SELECT domain, rule_learned FROM episodic_memory ORDER BY timestamp DESC LIMIT 25", null)
            } else {
                db.rawQuery(
                    "SELECT domain, rule_learned FROM episodic_memory WHERE domain = ? OR domain = 'GLOBAL' OR domain = 'ANDROID_UI' ORDER BY timestamp DESC LIMIT 25",
                    arrayOf(domainFilter.uppercase())
                )
            }
            cursor.use { c ->
                while (c.moveToNext()) {
                    val dom = c.getString(0) ?: "AI"
                    val rule = c.getString(1) ?: ""
                    list.add("[$dom] $rule")
                }
            }
            if (list.isEmpty()) "No prior lessons recorded." else list.joinToString("\n")
        } catch (e: Exception) {
            "No prior lessons."
        }
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
                "Cinematic 8-second hyper-realistic story of a futuristic cybernetic explorer in a glowing sci-fi world and story and hindi voice and dialog"
            ) ?: ""
    }

    fun getDailyVideoLimit(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt("daily_video_limit", 1).coerceIn(1, 5)
    }

    fun getHighModelName(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("high_model_name", "gemini-2.5-pro") ?: "gemini-2.5-pro"
    }

    fun isAutoHighModelEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("auto_high_model", true)
    }

    fun isGoogleSearchGroundingEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("google_search_grounding", true)
    }

    fun isRealWebTouchEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("real_web_touch", true)
    }

    fun saveMemoryVault(context: Context, memoryText: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("memory_vault", memoryText.trim()).apply()
    }

    fun getMemoryVault(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(
                "memory_vault",
                "Favorite Video Topic: Futuristic Sci-Fi & Cybernetic Worlds\nPreferred Language: Hindi + English"
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
