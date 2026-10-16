package com.auradroid.agent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private class AuraBrainDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "auradroid_brain.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        createAllTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        createAllTables(db)
    }

    private fun createAllTables(db: SQLiteDatabase) {
        // Table 1: Self-Learned Rules & Error Recovery Memory
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

        // Table 2: Episodic Story Series Continuation (Part 1, Part 2, Part 3 + Side Stories)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS story_episodes (
                episode_num INTEGER PRIMARY KEY,
                story_mode TEXT,
                series_theme TEXT,
                episode_title TEXT,
                plot_summary TEXT,
                video_prompt TEXT,
                duration_sec INTEGER,
                timestamp INTEGER
            )
            """.trimIndent()
        )

        // Table 3: WM3-Style Trained Skills & First-Run Live Verified JSON Blueprints
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS trained_skills (
                skill_name TEXT PRIMARY KEY,
                json_blueprint TEXT,
                is_live_verified INTEGER DEFAULT 0,
                timestamp INTEGER
            )
            """.trimIndent()
        )

        val coreLessons = listOf(
            "GOOGLE_FLOW" to "Never use words like boy, girl, child, kid, minor, or skin in video prompts; always use safe terms like adult futuristic cybernetic tech explorer.",
            "GOOGLE_FLOW" to "Before typing a prompt in Google Flow, switch Nano Banana / Image / x2 mode to Video • 9:16 • 8s/10s • x1 so credits are never wasted.",
            "GOOGLE_FLOW" to "Never type into the top search bar (top < 175px). Always type ONCE into the bottom 'What do you want to create?' box and click arrow_forward.",
            "GOOGLE_FLOW" to "When video rendering reaches 100% and the thumbnail card appears, intercept the MP4 stream directly or tap the card with 3x CSS-to-WebView scaled coordinates.",
            "WHATSAPP" to "If reply text is already typed inside the bottom Message input box and the green Send button (desc='Send') is visible, immediately click the Send node.",
            "ANDROID_UI" to "On the first run of any new task, use Live Test calibration with real screenshots to verify every step, then save the verified JSON blueprint for background runs."
        )
        for ((dom, rule) in coreLessons) {
            val cv = ContentValues().apply {
                put("domain", dom)
                put("rule_learned", rule)
                put("timestamp", System.currentTimeMillis())
            }
            db.insertWithOnConflict("episodic_memory", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }

        // Seed default GOOGLE_FLOW Trained Skill JSON Blueprint
        val defaultFlowBlueprint = JSONObject().apply {
            put("skill", "GOOGLE_FLOW")
            put("description", "Start-to-End Google Flow Video Creation & MP4 Download Blueprint")
            put("steps", JSONArray().apply {
                put(JSONObject().put("step", 1).put("screen", "Landing Page").put("action", "CLICK_EL").put("target", "+ New project").put("verify", "Project workspace opens with 'Start creating or drop media'"))
                put(JSONObject().put("step", 2).put("screen", "Project Workspace").put("action", "ENSURE_MODE").put("target", "Video • 720p • 8s/10s • x1").put("verify", "Bottom pill shows Video mode and x1"))
                put(JSONObject().put("step", 3).put("screen", "Prompt Composer").put("action", "TYPE_AND_SUBMIT").put("target", "What do you want to create? (bottom textarea top >= 175)").put("verify", "Card appears with 0% -> 100% progress"))
                put(JSONObject().put("step", 4).put("screen", "Rendering Grid").put("action", "WAIT_FOR_100_PERCENT").put("target", "Wait while % badge is visible").put("verify", "Completed video card thumbnail with Play icon appears"))
                put(JSONObject().put("step", 5).put("screen", "Completed Card").put("action", "EXTRACT_OR_TAP_CARD").put("target", "Sniff background MP4 network URL or 3x-scaled tap on card -> Play -> Download").put("verify", "MP4 file saved in AutoVideos folder"))
            })
        }.toString(2)

        val skillCv = ContentValues().apply {
            put("skill_name", "GOOGLE_FLOW")
            put("json_blueprint", defaultFlowBlueprint)
            put("is_live_verified", 0)
            put("timestamp", System.currentTimeMillis())
        }
        db.insertWithOnConflict("trained_skills", null, skillCv, SQLiteDatabase.CONFLICT_IGNORE)
    }
}

object VideoQueueManager {
    const val PREFS_NAME = "auradroid_prefs"
    private const val KEY_UPLOADED_SET = "uploaded_videos"

    // ========================================================================
    // 1. FIRST-RUN LIVE TEST & LEARN -> THEN BACKGROUND ENGINE
    // ========================================================================
    fun isFirstRunLiveLearnEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("first_run_live_learn", true)
    }

    fun setFirstRunLiveLearnEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean("first_run_live_learn", enabled).apply()
    }

    /**
     * Checks if a task (like GOOGLE_FLOW) has already completed its First-Run Live Test calibration.
     * If false (and First-Run Live Learn is enabled), the app automatically opens the visible window on Run #1
     * so screenshots + touch coordinates calibrate 100% accurately, then marks it verified for future Background runs!
     */
    fun isSkillLiveVerified(context: Context, skillName: String): Boolean {
        return try {
            val db = AuraBrainDatabase(context).readableDatabase
            val cursor = db.rawQuery(
                "SELECT is_live_verified FROM trained_skills WHERE skill_name = ?",
                arrayOf(skillName.uppercase().trim())
            )
            cursor.use { c ->
                if (c.moveToFirst()) c.getInt(0) == 1 else false
            }
        } catch (_: Exception) {
            false
        }
    }

    fun markSkillLiveVerified(context: Context, skillName: String, verified: Boolean = true) {
        try {
            val db = AuraBrainDatabase(context).writableDatabase
            val cv = ContentValues().apply {
                put("is_live_verified", if (verified) 1 else 0)
                put("timestamp", System.currentTimeMillis())
            }
            val rows = db.update("trained_skills", cv, "skill_name = ?", arrayOf(skillName.uppercase().trim()))
            if (rows == 0) {
                cv.put("skill_name", skillName.uppercase().trim())
                cv.put("json_blueprint", "{}")
                db.insertWithOnConflict("trained_skills", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
        } catch (_: Exception) {}
    }

    // ========================================================================
    // 2. WM3-STYLE TRAINED SKILLS (SCREENSHOTS / JSON PASTE / LIVE TEACH)
    // ========================================================================
    fun saveTrainedSkillBlueprint(
        context: Context,
        skillName: String,
        jsonBlueprint: String,
        isVerified: Boolean = false
    ) {
        val cleanName = skillName.uppercase().trim().ifEmpty { "CUSTOM_TASK" }
        try {
            val db = AuraBrainDatabase(context).writableDatabase
            val cv = ContentValues().apply {
                put("skill_name", cleanName)
                put("json_blueprint", jsonBlueprint.trim())
                put("is_live_verified", if (isVerified) 1 else 0)
                put("timestamp", System.currentTimeMillis())
            }
            db.insertWithOnConflict("trained_skills", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (_: Exception) {}
    }

    fun getTrainedSkillBlueprint(context: Context, skillName: String): String {
        val cleanName = skillName.uppercase().trim().ifEmpty { "GOOGLE_FLOW" }
        return try {
            val db = AuraBrainDatabase(context).readableDatabase
            val cursor = db.rawQuery(
                "SELECT json_blueprint FROM trained_skills WHERE skill_name = ?",
                arrayOf(cleanName)
            )
            cursor.use { c ->
                if (c.moveToFirst()) c.getString(0) ?: "" else ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    fun getAllTrainedSkillsSummary(context: Context): String {
        return try {
            val db = AuraBrainDatabase(context).readableDatabase
            val list = mutableListOf<String>()
            val cursor = db.rawQuery(
                "SELECT skill_name, is_live_verified, json_blueprint FROM trained_skills ORDER BY timestamp DESC",
                null
            )
            cursor.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: "SKILL"
                    val verified = if (c.getInt(1) == 1) "✅ Live Verified (Background Ready)" else "🎯 Needs 1st Live Run"
                    val bp = c.getString(2) ?: "{}"
                    list.add("🎓 $name [$verified]\n${bp.take(320)}")
                }
            }
            if (list.isEmpty()) "No trained skills yet." else list.joinToString("\n\n────────────────────\n\n")
        } catch (_: Exception) {
            "No trained skills yet."
        }
    }

    // ========================================================================
    // 3. CUSTOMIZABLE STORY SERIES (PART 1, 2, 3 + SIDE STORY + 10s DURATION)
    // ========================================================================
    /**
     * Story Modes: "SERIAL" (Part 1 -> Part 2 -> Part 3), "SIDE_STORY" (Spin-off reel in same world), "STANDALONE" (Fresh reel)
     */
    fun getStoryMode(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("story_mode_type", "SERIAL") ?: "SERIAL"
    }

    fun setStoryMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("story_mode_type", mode.uppercase()).apply()
    }

    fun getCharacterVisualLock(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(
                "story_character_lock",
                "Adult futuristic cybernetic tech explorer wearing a sleek glowing neon-blue visor and dark metallic jacket"
            ) ?: ""
    }

    fun setCharacterVisualLock(context: Context, characterDesc: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("story_character_lock", characterDesc.trim()).apply()
    }

    fun getTargetVideoDurationSec(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt("target_video_duration_sec", 10).coerceIn(5, 60)
    }

    fun setTargetVideoDurationSec(context: Context, seconds: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt("target_video_duration_sec", seconds.coerceIn(5, 60)).apply()
    }

    fun getNextEpisodeNumber(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt("next_episode_number", 1).coerceAtLeast(1)
    }

    fun setNextEpisodeNumber(context: Context, epNum: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt("next_episode_number", epNum.coerceAtLeast(1)).apply()
    }

    fun getStoryCustomTwist(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("story_custom_twist", "") ?: ""
    }

    fun setStoryCustomTwist(context: Context, twist: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("story_custom_twist", twist.trim()).apply()
    }

    fun recordStoryEpisode(
        context: Context,
        episodeNum: Int,
        storyMode: String,
        seriesTheme: String,
        episodeTitle: String,
        plotSummary: String,
        videoPrompt: String,
        durationSec: Int
    ) {
        try {
            val db = AuraBrainDatabase(context).writableDatabase
            val cv = ContentValues().apply {
                put("episode_num", episodeNum)
                put("story_mode", storyMode)
                put("series_theme", seriesTheme.take(250))
                put("episode_title", episodeTitle.take(120))
                put("plot_summary", plotSummary.take(450))
                put("video_prompt", videoPrompt.take(600))
                put("duration_sec", durationSec)
                put("timestamp", System.currentTimeMillis())
            }
            db.insertWithOnConflict("story_episodes", null, cv, SQLiteDatabase.CONFLICT_REPLACE)

            if (storyMode == "SERIAL") {
                setNextEpisodeNumber(context, episodeNum + 1)
            }

            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt("last_generated_ep_num", episodeNum)
                .putString("last_generated_ep_mode", storyMode)
                .putString("last_generated_ep_title", episodeTitle)
                .putString("last_generated_ep_summary", plotSummary)
                .apply()
        } catch (_: Exception) {}
    }

    fun getLastGeneratedEpisodeInfo(context: Context): Triple<Int, String, String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ep = prefs.getInt("last_generated_ep_num", 1)
        val title = prefs.getString("last_generated_ep_title", "") ?: ""
        val summary = prefs.getString("last_generated_ep_summary", "") ?: ""
        return Triple(ep, title, summary)
    }

    fun getPreviousEpisodesContext(context: Context, limit: Int = 6): String {
        return try {
            val db = AuraBrainDatabase(context).readableDatabase
            val list = mutableListOf<String>()
            val cursor = db.rawQuery(
                "SELECT episode_num, story_mode, episode_title, plot_summary, video_prompt FROM story_episodes ORDER BY episode_num DESC LIMIT ?",
                arrayOf(limit.toString())
            )
            cursor.use { c ->
                while (c.moveToNext()) {
                    val num = c.getInt(0)
                    val mode = c.getString(1) ?: "SERIAL"
                    val title = c.getString(2) ?: ""
                    val sum = c.getString(3) ?: ""
                    val vp = c.getString(4) ?: ""
                    list.add("Part $num [$mode] ($title): $sum | Prompt: ${vp.take(110)}...")
                }
            }
            if (list.isEmpty()) {
                "No previous episodes yet. This is Part 1 (The Opening Episode of the Series)!"
            } else {
                list.reversed().joinToString("\n")
            }
        } catch (_: Exception) {
            "No previous episodes yet."
        }
    }

    fun getFullStoryArcReport(context: Context): String {
        return try {
            val db = AuraBrainDatabase(context).readableDatabase
            val list = mutableListOf<String>()
            val sdf = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
            val cursor = db.rawQuery(
                "SELECT episode_num, story_mode, episode_title, plot_summary, duration_sec, timestamp FROM story_episodes ORDER BY episode_num ASC",
                null
            )
            cursor.use { c ->
                while (c.moveToNext()) {
                    val num = c.getInt(0)
                    val mode = c.getString(1) ?: "SERIAL"
                    val title = c.getString(2) ?: "Part $num"
                    val sum = c.getString(3) ?: ""
                    val dur = c.getInt(4)
                    val timeStr = sdf.format(Date(c.getLong(5)))
                    list.add("🎬 Part $num [$mode • ${dur}s]: $title ($timeStr)\n   📖 $sum")
                }
            }
            if (list.isEmpty()) {
                "अभी तक कोई स्टोरी पार्ट जनरेट नहीं हुआ है।\n\nजब आप 'अभी अगला स्टोरी पार्ट बनाएं' दबाएंगे, तो Part 1, Part 2, Part 3 की पूरी कहानी यहाँ क्रम से सेव होती जाएगी!"
            } else {
                list.joinToString("\n\n")
            }
        } catch (_: Exception) {
            "Story history empty."
        }
    }

    fun resetStoryArcHistory(context: Context) {
        try {
            val db = AuraBrainDatabase(context).writableDatabase
            db.execSQL("DELETE FROM story_episodes")
        } catch (_: Exception) {}
        setNextEpisodeNumber(context, 1)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove("last_generated_ep_num")
            .remove("last_generated_ep_title")
            .remove("last_generated_ep_summary")
            .apply()
    }

    // ========================================================================
    // 4. VIDEO QUEUE & CUSTOMIZABLE UPLOAD ORDER (NEWEST vs OLDEST)
    // ========================================================================
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
            } catch (_: Exception) {
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
        } catch (_: Exception) {
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
                "Cinematic hyper-realistic technical story of a futuristic cybernetic explorer in a glowing sci-fi world with Hindi voice and dialog"
            ) ?: ""
    }

    fun getDailyVideoLimit(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt("daily_video_limit", 1).coerceIn(1, 5)
    }

    fun getHighModelName(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("high_model_name", "gemini-3.8-pro") ?: "gemini-3.8-pro"
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
                "Favorite Video Topic: Technical Sci-Fi & Cybernetic Explorer Series\nPreferred Language: Hindi + English"
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
