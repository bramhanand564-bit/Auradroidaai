package com.auradroid.agent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private class AuraBrainDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "auradroid_brain.db", null, 5) {

    override fun onCreate(db: SQLiteDatabase) {
        createAllTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 5) {
            // Remove the legacy unsafe lesson that could send a message without explicit intent.
            db.delete(
                "episodic_memory",
                "rule_learned = ?",
                arrayOf("If reply text is already typed inside the bottom Message input box and the green Send button (desc='Send') is visible, immediately click the Send node.")
            )
        }
        createAllTables(db)
    }

    private fun createAllTables(db: SQLiteDatabase) {
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
            "GOOGLE_FLOW" to "Every trained JSON step is a guide only: wait 1 second after every action to confirm the screen actually updated before moving to the next step.",
            "GOOGLE_FLOW" to "When video rendering reaches 100% and the thumbnail card appears, try Sense-1 (Background MP4 Stream Sniffer), Sense-2 (DOM/React Fiber URL), Sense-3 (3x Finger Tap on Play icon), and Sense-4 (Card Center Tap -> Download).",
            "YOUTUBE" to "Never use the raw file name or 'Google Flow' in YouTube titles; always inspect actual video frames with Vision AI to craft a curiosity-driven viral title.",
            "WHATSAPP" to "Never auto-send because text is present. Click Send only when the user's complete task explicitly requests sending a reply and the current Send control has been verified."
        )
        for ((dom, rule) in coreLessons) {
            val cv = ContentValues().apply {
                put("domain", dom)
                put("rule_learned", rule)
                put("timestamp", System.currentTimeMillis())
            }
            db.insertWithOnConflict("episodic_memory", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }

        // Pre-Trained Built-in GOOGLE_FLOW Master JSON Blueprint with 1-Second AI Confirmation Rules
        val masterFlowBlueprint = JSONObject().apply {
            put("skill", "GOOGLE_FLOW")
            put("execution_policy", "AI_1S_STEP_CONFIRMED_GUIDE_NEVER_SKIP_BLINDLY")
            put("description", "Pre-trained start-to-end Google Flow video creation & 4-Sense MP4 download blueprint")
            put("steps", JSONArray().apply {
                put(
                    JSONObject()
                        .put("step", 1)
                        .put("screen_identify", "Google Flow Landing Page with '+ New project' button")
                        .put("action", "CLICK_EL")
                        .put("target", "+ New project")
                        .put("css_hint", "x=270, y=700")
                        .put("confirm_after_1s", "Workspace must open showing 'What do you want to create?' at bottom")
                        .put("fallback_sense", "If DOM click does not open workspace in 1s, use 3x hardware finger tap at (270, 700)")
                )
                put(
                    JSONObject()
                        .put("step", 2)
                        .put("screen_identify", "Mode Selector Pill near bottom composer")
                        .put("action", "ENSURE_VIDEO_MODE")
                        .put("target", "Video • 720p • 8s/10s • x1")
                        .put("forbid", "Nano Banana, Image, x2, x4")
                        .put("css_hint", "x=425, y=892")
                        .put("confirm_after_1s", "Bottom mode pill must show Video and x1")
                )
                put(
                    JSONObject()
                        .put("step", 3)
                        .put("screen_identify", "Bottom Prompt Composer (top >= 175px, never top search bar)")
                        .put("action", "TYPE_AND_SUBMIT_ONCE")
                        .put("target", "What do you want to create?")
                        .put("submit_button", "arrow_forward @(496, 892)")
                        .put("confirm_after_1s", "Project card appears with 0%..99% rendering badge; lock prompt so it never types twice")
                )
                put(
                    JSONObject()
                        .put("step", 4)
                        .put("screen_identify", "Rendering Progress Card (0% to 99%)")
                        .put("action", "WAIT_WITH_1S_CONFIRM")
                        .put("target", "Wait until percentage reaches 100% and thumbnail image appears")
                        .put("confirm_after_1s", "100% completed video card with Play icon is visible")
                )
                put(
                    JSONObject()
                        .put("step", 5)
                        .put("screen_identify", "Completed Video Card on Project Grid")
                        .put("action", "MULTI_SENSE_DOWNLOAD_MP4")
                        .put("target", "Sense-1: Sniff network MP4 • Sense-2: DOM/Fiber src • Sense-3: Tap Play @(92,286) • Sense-4: Tap Card @(148,360) -> Download")
                        .put("confirm_after_1s", "MP4 file (>40KB) verified inside user's selected storage folder")
                )
            })
        }.toString(2)

        val skillCv = ContentValues().apply {
            put("skill_name", "GOOGLE_FLOW")
            put("json_blueprint", masterFlowBlueprint)
            put("is_live_verified", 0)
            put("timestamp", System.currentTimeMillis())
        }
        db.insertWithOnConflict("trained_skills", null, skillCv, SQLiteDatabase.CONFLICT_REPLACE)
    }
}

object VideoQueueManager {
    const val PREFS_NAME = "auradroid_prefs"
    private const val KEY_UPLOADED_SET = "uploaded_videos"

    // ========================================================================
    // 1. 1-SECOND AI STEP CONFIRMATION LOCK & FIRST-RUN CALIBRATION
    // ========================================================================
    fun isOneSecAiConfirmEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("one_sec_ai_confirm", true)
    }

    fun setOneSecAiConfirmEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean("one_sec_ai_confirm", enabled).apply()
    }

    fun isFirstRunLiveLearnEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("first_run_live_learn", true)
    }

    fun setFirstRunLiveLearnEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean("first_run_live_learn", enabled).apply()
    }

    fun isVideoFrameVisionSeoEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("video_frame_vision_seo", true)
    }

    fun setVideoFrameVisionSeoEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean("video_frame_vision_seo", enabled).apply()
    }

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
    // 2. VIDEO FRAME VISION EXTRACTOR (EXTRACTS 2 REAL PHOTOS FROM MP4 FOR VIRAL TITLE!)
    // ========================================================================
    /**
     * Extracts 2 actual frames (around 1.5s and 5.5s) from the MP4 video file and converts them
     * to compact Base64 JPEGs so Gemini 3.8 Vision can SEE the actual video before writing the YouTube title!
     */
    fun extractVideoFramesBase64(videoFile: File): List<String> {
        if (!videoFile.exists() || videoFile.length() < 10_000L) return emptyList()
        val frames = mutableListOf<String>()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoFile.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 8000L

            val sampleTimesUs = listOf(
                (durationMs * 0.20 * 1000L).toLong().coerceAtLeast(500_000L),
                (durationMs * 0.70 * 1000L).toLong().coerceAtLeast(2_500_000L)
            )

            for (timeUs in sampleTimesUs) {
                val rawBmp = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime()
                if (rawBmp != null) {
                    val targetW = 480
                    val ratio = rawBmp.height.toFloat() / rawBmp.width.coerceAtLeast(1).toFloat()
                    val targetH = (targetW * ratio).toInt().coerceIn(320, 860)
                    val scaled = Bitmap.createScaledBitmap(rawBmp, targetW, targetH, true)
                    if (scaled != rawBmp) rawBmp.recycle()

                    val baos = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 72, baos)
                    scaled.recycle()
                    frames.add(Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP))
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }
        return frames
    }

    // ========================================================================
    // 3. WM3-STYLE TRAINED SKILLS (SCREENSHOTS / JSON PASTE / LIVE TEACH)
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
                    val verified = if (c.getInt(1) == 1) "✅ 1st-Run Verified (Background Ready)" else "🎯 1st-Run Phone Learn Pending"
                    val bp = c.getString(2) ?: "{}"
                    list.add("🎓 $name [$verified]\n${bp.take(360)}")
                }
            }
            if (list.isEmpty()) "No trained skills yet." else list.joinToString("\n\n────────────────────\n\n")
        } catch (_: Exception) {
            "No trained skills yet."
        }
    }

    // ========================================================================
    // 4. SMART NATURAL LANGUAGE TIMER & SCHEDULE STORAGE
    // ========================================================================
    fun saveParsedScheduleSummary(context: Context, summary: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("parsed_schedule_summary", summary.trim()).apply()
    }

    fun getParsedScheduleSummary(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(
                "parsed_schedule_summary",
                "⏰ Smart Timer: चैट में समय लिखें तो AI खुद अलार्म और टास्क काउंट सेट कर देगा।"
            ) ?: ""
    }

    // ========================================================================
    // 5. CUSTOMIZABLE STORY SERIES (PART 1, 2, 3 + SIDE STORY + 10s DURATION)
    // ========================================================================
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
                    list.add("Part $num [$mode] ($title): $sum • Prompt: ${vp.take(110)}...")
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
    // 6. VIDEO QUEUE & CUSTOMIZABLE STORAGE FOLDER
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
            return "📂 फोल्डर ($targetFolder) अभी खाली है।\n\nजब Google Flow या किसी भी स्टोरेज से वीडियो आएगी, तो वह यहाँ दिखेगी।"
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
                "Cinematic hyper-realistic technical story series of an adult futuristic cybernetic explorer inventing quantum gadgets in a glowing sci-fi world with Hindi voice and dialog"
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
        // When 1-Second AI Step Confirmation is enabled, AI checks and confirms within ~1200ms!
        if (isOneSecAiConfirmEnabled(context)) {
            return 1200L
        }
        val sec = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt("step_delay_sec", 2).coerceIn(1, 30)
        return sec * 1000L
    }

    fun appendDetailedLog(
        context: Context,
        modelUsed: String,
        clickSense: String,
        actionDesc: String,
        whyReason: String,
        verifyStatus: String
    ) {
        val msg = "[$modelUsed • $clickSense] $actionDesc -> Why: $whyReason -> Verify: $verifyStatus"
        appendLog(context, msg)
    }

    fun appendLog(context: Context, message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$time] $message"
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val oldLogs = prefs.getString("live_logs", "") ?: ""
        val updated = (oldLogs.lines().takeLast(75) + line).joinToString("\n").trim()
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
