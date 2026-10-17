package com.auradroid.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

/**
 * Deterministic routing and task-state primitives shared by phone and web workflows.
 * AI may suggest a route, but explicit user intent and safety rules take precedence.
 */
enum class AutomationMode { BACKGROUND_WEB, PHONE_UI }

data class AppAutomationProfile(
    val id: String,
    val displayName: String,
    val aliases: List<String>,
    val packageNames: List<String>,
    val webHome: String,
    val websiteKeywords: List<String> = emptyList()
)

data class AutomationRoute(
    val mode: AutomationMode,
    val profile: AppAutomationProfile?,
    val startUrl: String,
    val reason: String
)

object AppAutomationRouter {
    private val profiles = listOf(
        AppAutomationProfile(
            id = "YOUTUBE_STUDIO", displayName = "YouTube Studio",
            aliases = listOf("youtube studio", "studio.youtube.com"),
            packageNames = listOf("com.google.android.apps.youtube.creator"),
            webHome = "https://studio.youtube.com/",
            websiteKeywords = listOf("upload", "publish", "video", "shorts", "creator studio")
        ),
        AppAutomationProfile(
            id = "GOOGLE_FLOW", displayName = "Google Flow",
            aliases = listOf("google flow", "flow.google", "labs.google/fx"),
            packageNames = emptyList(),
            webHome = "https://labs.google/fx/tools/flow",
            websiteKeywords = listOf("generate video", "render video", "video prompt")
        ),
        AppAutomationProfile(
            id = "CHROME", displayName = "Chrome",
            aliases = listOf("chrome", "google chrome"),
            packageNames = listOf("com.android.chrome"),
            webHome = "https://www.google.com/"
        ),
        AppAutomationProfile(
            id = "YOUTUBE", displayName = "YouTube",
            aliases = listOf("youtube", "yt"),
            packageNames = listOf("com.google.android.youtube"),
            webHome = "https://www.youtube.com/"
        ),
        AppAutomationProfile(
            id = "WHATSAPP", displayName = "WhatsApp",
            aliases = listOf("whatsapp", "whats app"),
            packageNames = listOf("com.whatsapp", "com.whatsapp.w4b"),
            webHome = "https://web.whatsapp.com/"
        ),
        AppAutomationProfile(
            id = "INSTAGRAM", displayName = "Instagram",
            aliases = listOf("instagram", "insta"),
            packageNames = listOf("com.instagram.android"),
            webHome = "https://www.instagram.com/"
        ),
        AppAutomationProfile(
            id = "GMAIL", displayName = "Gmail",
            aliases = listOf("gmail", "google mail", "email"),
            packageNames = listOf("com.google.android.gm"),
            webHome = "https://mail.google.com/"
        ),
        AppAutomationProfile(
            id = "GOOGLE_DRIVE", displayName = "Google Drive",
            aliases = listOf("google drive", "drive"),
            packageNames = listOf("com.google.android.apps.docs"),
            webHome = "https://drive.google.com/"
        ),
        AppAutomationProfile(
            id = "GOOGLE_PHOTOS", displayName = "Google Photos",
            aliases = listOf("google photos", "photos app", "google photo"),
            packageNames = listOf("com.google.android.apps.photos"),
            webHome = "https://photos.google.com/"
        ),
        AppAutomationProfile(
            id = "GOOGLE_MAPS", displayName = "Google Maps",
            aliases = listOf("google maps", "maps app", "maps"),
            packageNames = listOf("com.google.android.apps.maps"),
            webHome = "https://maps.google.com/"
        ),
        AppAutomationProfile(
            id = "SPOTIFY", displayName = "Spotify",
            aliases = listOf("spotify"),
            packageNames = listOf("com.spotify.music"),
            webHome = "https://open.spotify.com/"
        ),
        AppAutomationProfile(
            id = "LINKEDIN", displayName = "LinkedIn",
            aliases = listOf("linkedin"),
            packageNames = listOf("com.linkedin.android"),
            webHome = "https://www.linkedin.com/"
        ),
        AppAutomationProfile(
            id = "ANDROID_SETTINGS", displayName = "Android Settings",
            aliases = listOf("android settings", "phone settings", "system settings"),
            packageNames = listOf("com.android.settings"),
            webHome = "https://www.google.com/"
        ),
        AppAutomationProfile(
            id = "TELEGRAM", displayName = "Telegram",
            aliases = listOf("telegram"),
            packageNames = listOf("org.telegram.messenger", "org.thunderdog.challegram"),
            webHome = "https://web.telegram.org/"
        ),
        AppAutomationProfile(
            id = "FACEBOOK", displayName = "Facebook",
            aliases = listOf("facebook", "fb"),
            packageNames = listOf("com.facebook.katana"),
            webHome = "https://www.facebook.com/"
        ),
        AppAutomationProfile(
            id = "X", displayName = "X",
            aliases = listOf("twitter", "x.com", "x app"),
            packageNames = listOf("com.twitter.android"),
            webHome = "https://x.com/"
        )
    )

    private val explicitPhoneSignals = listOf(
        "real phone", "phone screen", "on my phone", "actual device", "android app",
        "control my phone", "control the phone", "control phone",
        "open app", "launch app", "phone ko control", "mere phone", "mere mobile",
        "फोन की स्क्रीन", "असली फोन", "मोबाइल ऐप"
    )
    private val webSignals = listOf(
        "website", "web site", "webpage", "web page", "in browser", "using browser",
        "in chrome", "on the website", "website par", "web par", "browser me",
        "search online", "visit the site", "open url", "link ko kholo"
    )
    private val webWorkflowVerbs = listOf(
        "upload", "publish", "post", "website", "webpage", "web page", "download from",
        "fill this form", "submit form", "browse", "search online", "url", "http://", "https://",
        "www."
    )
    private val urlRegex = Regex("""(?i)(https?://[^\s<>"']+|www\.[^\s<>"']+|(?:[a-z0-9-]+\.)+(?:com|org|net|io|app|dev|in|co|ai|edu|gov)(?:/[^\s<>"']*)?)""")

    fun allProfiles(): List<AppAutomationProfile> = profiles.toList()

    fun profileForGoal(goal: String): AppAutomationProfile? {
        val value = goal.lowercase()
        // Upload/publish tasks target Studio, not the consumer YouTube app.
        if (value.contains("youtube") &&
            listOf("upload", "publish", "post", "short", "studio", "creator").any { value.contains(it) }) {
            return profiles.firstOrNull { it.id == "YOUTUBE_STUDIO" }
        }
        // More specific profiles must win over their parent product.
        return profiles.firstOrNull { profile ->
            profile.aliases.sortedByDescending { it.length }.any { alias -> value.contains(alias) }
        }
    }

    fun profileForPackage(packageName: String?): AppAutomationProfile? {
        if (packageName.isNullOrBlank()) return null
        return profiles.firstOrNull { profile ->
            profile.packageNames.any { it.equals(packageName, ignoreCase = true) }
        }
    }

    fun memoryDomainForGoal(goal: String, foregroundPackage: String? = null): String {
        return profileForPackage(foregroundPackage)?.id
            ?: profileForGoal(goal)?.id
            ?: "ANDROID_UI"
    }

    fun adapterGuidance(goal: String, packageName: String? = null): String {
        val profile = profileForPackage(packageName) ?: profileForGoal(goal)
        return when (profile?.id) {
            "CHROME" -> "CHROME ADAPTER: verify the current URL, tab title, loading state and active tab. Prefer current semantic/DOM targets; never use an old target index after navigation."
            "YOUTUBE", "YOUTUBE_STUDIO" -> "YOUTUBE ADAPTER: distinguish the consumer YouTube app from YouTube Studio. For uploads, verify exact filename/preview, metadata and audience/privacy before the final side effect; require visible upload evidence."
            "GOOGLE_FLOW" -> "GOOGLE FLOW ADAPTER: submit a generation prompt once only. Wait for fresh render/progress evidence and verify the actual finished video file before chaining to YouTube."
            "WHATSAPP" -> "WHATSAPP ADAPTER: verify the contact/group header before typing. A typed draft is not a sent message; never send unless the original user goal explicitly requests it and verify the resulting chat state."
            "INSTAGRAM" -> "INSTAGRAM ADAPTER: confirm the active account/profile and the selected media. Distinguish save-draft from publish; do not repeat post/follow actions when the result is uncertain."
            "GMAIL" -> "GMAIL ADAPTER: check recipient, subject and body independently. A populated compose form is not a sent email; send only when explicitly requested and verify visible sent evidence."
            "GOOGLE_DRIVE" -> "DRIVE ADAPTER: verify filename and destination folder before moving, sharing or deleting. Treat permission dialogs and upload progress as separate states."
            "GOOGLE_PHOTOS" -> "PHOTOS ADAPTER: verify the selected photo/video by visible preview and date/name when available; don't confuse Photos with a local Downloads folder."
            "GOOGLE_MAPS" -> "MAPS ADAPTER: verify the destination name and location in the selected result before starting navigation or changing route options."
            "TELEGRAM" -> "TELEGRAM ADAPTER: verify the conversation header and recipient; a composer draft is not a sent message."
            "FACEBOOK" -> "FACEBOOK ADAPTER: verify the active profile/page and target audience; treat publish, follow and delete as sensitive side effects."
            "X" -> "X/TWITTER ADAPTER: verify the exact installed app or website destination and current account; don't switch between similarly named clients without explicit user intent."
            "SPOTIFY" -> "SPOTIFY ADAPTER: verify the selected track/playlist and current playback state before reporting playback success."
            "LINKEDIN" -> "LINKEDIN ADAPTER: verify the active profile and target post/person; never submit a post or connection action unless explicitly requested."
            "ANDROID_SETTINGS" -> "ANDROID SETTINGS ADAPTER: confirm the exact settings page and current toggle state; don't claim a setting changed until its new state is observed."
            else -> "UNIVERSAL ADAPTER: confirm the foreground app and current screen before every action; use fresh targets, verify the result and pause rather than guessing when evidence is ambiguous."
        }
    }

    fun isExplicitPhoneRequest(goal: String): Boolean {
        val value = goal.lowercase()
        return explicitPhoneSignals.any { value.contains(it) }
    }

    fun chooseRoute(
        goal: String,
        suggestedMode: String? = null,
        forcePhoneUi: Boolean = false,
        suggestedUrl: String = "",
        pinnedFlowUrl: String = ""
    ): AutomationRoute {
        val value = goal.lowercase()
        val profile = profileForGoal(goal)
        val urlInGoal = urlRegex.find(goal)?.value.orEmpty()
        val explicitPhone = forcePhoneUi || isExplicitPhoneRequest(goal) ||
            (profile != null && isAppLaunchIntent(value) && !isWebRequest(value) && !isWebsiteWorkflow(value))

        val youtubeUpload = value.contains("youtube") &&
            listOf("upload", "publish", "post", "short").any { value.contains(it) }
        val flowTask = value.contains("google flow") || value.contains("flow.google") || value.contains("labs.google/fx")
        val routeToWeb = !explicitPhone && (
            youtubeUpload || flowTask || isWebRequest(value) ||
                urlInGoal.isNotBlank() ||
                webWorkflowVerbs.any { value.contains(it) } ||
                suggestedMode.equals("WEB", ignoreCase = true)
            )

        val mode = if (routeToWeb) AutomationMode.BACKGROUND_WEB else AutomationMode.PHONE_UI
        val startUrl = resolveStartUrl(goal, suggestedUrl, pinnedFlowUrl)
        val reason = when {
            forcePhoneUi || isExplicitPhoneRequest(goal) -> "Explicit real-phone request takes priority."
            youtubeUpload -> "YouTube publishing/upload tasks use the controlled web workflow by default."
            flowTask -> "Google Flow is a web-first workflow."
            urlInGoal.isNotBlank() || isWebRequest(value) -> "The instruction explicitly identifies a website/browser destination."
            mode == AutomationMode.BACKGROUND_WEB -> "The requested workflow is suitable for the web engine."
            profile != null -> "The requested app is mapped to its dedicated ${profile.displayName} adapter."
            else -> "No website target was detected; use the real-phone agent rather than a random web page."
        }
        return AutomationRoute(mode, profile, startUrl, reason)
    }

    fun resolveStartUrl(goal: String, suggestedUrl: String = "", pinnedFlowUrl: String = ""): String {
        val value = goal.lowercase()
        val flowTask = value.contains("google flow") || value.contains("flow.google") || value.contains("labs.google/fx")
        if (flowTask) {
            return validatedPinnedFlowUrl(pinnedFlowUrl) ?: "https://labs.google/fx/tools/flow"
        }

        if (value.contains("youtube") &&
            listOf("upload", "publish", "post", "studio", "creator", "short").any { value.contains(it) }) {
            return "https://studio.youtube.com/"
        }

        val explicit = urlRegex.find(goal)?.value
        if (explicit != null) normalizeHttpUrl(explicit)?.let { return it }

        val profile = profileForGoal(goal)
        if (profile != null && (isWebRequest(value) || isWebsiteWorkflow(value) ||
                webWorkflowVerbs.any { value.contains(it) } || value.contains("website"))) {
            return profile.webHome
        }

        normalizeHttpUrl(suggestedUrl)?.let { normalized ->
            val host = runCatching { URI(normalized).host?.lowercase().orEmpty() }.getOrDefault("")
            val isFlowDestination = host == "labs.google" || host.endsWith(".labs.google") ||
                host == "flow.google" || host.endsWith(".flow.google") ||
                host == "labs.google.com" || host.endsWith(".labs.google.com")
            // A saved Flow URL is only valid for an explicitly Flow-related task.
            if (!isFlowDestination) return normalized
        }
        return "https://www.google.com/"
    }

    fun normalizeHttpUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim().trim('"', '\'', ' ', '\n', '\r', '\t')
        if (trimmed.isBlank()) return null
        val candidate = when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("http://", ignoreCase = true) -> "https://" + trimmed.substringAfter("://")
            trimmed.startsWith("javascript:", ignoreCase = true) ||
                trimmed.startsWith("file:", ignoreCase = true) ||
                trimmed.startsWith("content:", ignoreCase = true) ||
                trimmed.startsWith("data:", ignoreCase = true) -> return null
            trimmed.startsWith("www.", ignoreCase = true) -> "https://$trimmed"
            trimmed.contains('.') && !trimmed.contains(' ') -> "https://$trimmed"
            else -> return null
        }
        return try {
            val uri = URI(candidate)
            val scheme = uri.scheme?.lowercase()
            val host = uri.host
            if (scheme != "https" || host.isNullOrBlank() ||
                host.equals("localhost", true) ||
                host == "127.0.0.1" ||
                host.startsWith("10.") ||
                host.startsWith("192.168.") ||
                host.startsWith("169.254.") ||
                host.endsWith(".local", true)) {
                null
            } else candidate
        } catch (_: Exception) {
            null
        }
    }

    private fun validatedPinnedFlowUrl(rawUrl: String): String? {
        val normalized = normalizeHttpUrl(rawUrl) ?: return null
        val host = runCatching { URI(normalized).host?.lowercase().orEmpty() }.getOrDefault("")
        return normalized.takeIf {
            host == "labs.google" || host.endsWith(".labs.google") ||
                host == "flow.google" || host.endsWith(".flow.google") ||
                host == "labs.google.com" || host.endsWith(".labs.google.com")
        }
    }

    private fun isAppLaunchIntent(value: String): Boolean =
        listOf("open ", "launch ", "start ", " kholo", " kholna", " खोलो", " खोलना", "chalao", "run app")
            .any { value.contains(it) }

    private fun isWebRequest(value: String): Boolean =
        webSignals.any { value.contains(it) }

    private fun isWebsiteWorkflow(value: String): Boolean =
        listOf("on website", "website par", "site par", "web page", "webpage").any { value.contains(it) }
}

enum class TaskStatus { QUEUED, RUNNING, VERIFYING, PAUSED, COMPLETED, FAILED, CANCELLED }

data class TaskRunRecord(
    val id: String,
    val goal: String,
    val mode: String,
    val adapterId: String,
    val status: TaskStatus,
    val step: Int,
    val message: String,
    val startedAt: Long,
    val updatedAt: Long
)

/**
 * Lightweight persistent run ledger. A process-death interrupted UI task is marked PAUSED,
 * never silently reported as completed or automatically replayed.
 */
object TaskExecutionStore {
    private const val PREFS = "nax_task_execution_state"
    private const val KEY_RUNS = "runs_v1"
    private const val MAX_HISTORY = 30

    @Synchronized
    fun start(context: Context, goal: String, mode: AutomationMode, adapterId: String): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val record = TaskRunRecord(
            id = id,
            goal = goal.take(500),
            mode = mode.name,
            adapterId = adapterId,
            status = TaskStatus.RUNNING,
            step = 0,
            message = "Task accepted; execution started.",
            startedAt = now,
            updatedAt = now
        )
        val prior = read(context).map { previous ->
            if (previous.status == TaskStatus.RUNNING || previous.status == TaskStatus.VERIFYING) {
                previous.copy(
                    status = TaskStatus.CANCELLED,
                    message = "Superseded by a newer task; previous work was not marked completed.",
                    updatedAt = now
                )
            } else previous
        }
        save(context, listOf(record) + prior.take(MAX_HISTORY - 1))
        return id
    }

    @Synchronized
    fun update(
        context: Context,
        id: String?,
        status: TaskStatus,
        step: Int? = null,
        message: String
    ) {
        if (id.isNullOrBlank()) return
        val records = read(context).toMutableList()
        val index = records.indexOfFirst { it.id == id }
        if (index < 0) return
        val old = records[index]
        if (!isAllowedTransition(old.status, status)) return
        records[index] = old.copy(
            status = status,
            step = step ?: old.step,
            message = message.take(700),
            updatedAt = System.currentTimeMillis()
        )
        save(context, records.take(MAX_HISTORY))
    }

    @Synchronized
    fun latest(context: Context): TaskRunRecord? = read(context).firstOrNull()

    @Synchronized
    fun reconcileInterrupted(context: Context): String? {
        val records = read(context).toMutableList()
        val interrupted = records.filter { it.status == TaskStatus.RUNNING || it.status == TaskStatus.VERIFYING }
        if (interrupted.isEmpty()) return null
        val now = System.currentTimeMillis()
        for (index in records.indices) {
            val old = records[index]
            if (old.status == TaskStatus.RUNNING || old.status == TaskStatus.VERIFYING) {
                records[index] = old.copy(
                    status = TaskStatus.PAUSED,
                    message = "App process ended before task completion was verified. Inspect the screen and resume manually.",
                    updatedAt = now
                )
            }
        }
        save(context, records.take(MAX_HISTORY))
        return "⚠️ ${interrupted.size} interrupted task(s) marked PAUSED; no task was auto-replayed: ${interrupted.first().goal.take(100)}"
    }

    private fun isAllowedTransition(from: TaskStatus, to: TaskStatus): Boolean {
        if (from == to) return true
        return when (from) {
            TaskStatus.QUEUED -> to in setOf(TaskStatus.RUNNING, TaskStatus.CANCELLED, TaskStatus.FAILED, TaskStatus.PAUSED)
            TaskStatus.RUNNING -> to in setOf(TaskStatus.VERIFYING, TaskStatus.PAUSED, TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.CANCELLED)
            TaskStatus.VERIFYING -> to in setOf(TaskStatus.RUNNING, TaskStatus.PAUSED, TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.CANCELLED)
            TaskStatus.PAUSED -> to in setOf(TaskStatus.QUEUED, TaskStatus.RUNNING, TaskStatus.FAILED, TaskStatus.CANCELLED)
            TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.CANCELLED -> false
        }
    }

    private fun read(context: Context): List<TaskRunRecord> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RUNS, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                TaskRunRecord(
                    id = item.optString("id"),
                    goal = item.optString("goal"),
                    mode = item.optString("mode"),
                    adapterId = item.optString("adapterId"),
                    status = runCatching { TaskStatus.valueOf(item.optString("status")) }.getOrDefault(TaskStatus.FAILED),
                    step = item.optInt("step"),
                    message = item.optString("message"),
                    startedAt = item.optLong("startedAt"),
                    updatedAt = item.optLong("updatedAt")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(context: Context, records: List<TaskRunRecord>) {
        val array = JSONArray()
        records.take(MAX_HISTORY).forEach { run ->
            array.put(JSONObject().apply {
                put("id", run.id)
                put("goal", run.goal)
                put("mode", run.mode)
                put("adapterId", run.adapterId)
                put("status", run.status.name)
                put("step", run.step)
                put("message", run.message)
                put("startedAt", run.startedAt)
                put("updatedAt", run.updatedAt)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_RUNS, array.toString()).apply()
    }
}
