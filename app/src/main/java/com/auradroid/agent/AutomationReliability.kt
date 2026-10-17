package com.auradroid.agent

import android.content.Context
import kotlinx.coroutines.delay
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
            aliases = listOf("google drive", "drive app", "drive folder"),
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

    private fun containsAlias(value: String, alias: String): Boolean {
        val clean = alias.trim().lowercase()
        if (clean.isBlank()) return false
        // Domains and path fragments are intentional literal matches; app names are whole phrases.
        if (clean.contains('.') || clean.contains('/')) return value.contains(clean)
        val escaped = Regex.escape(clean)
        return Regex("""(?<![A-Za-z0-9])$escaped(?![A-Za-z0-9])""").containsMatchIn(value)
    }

    fun profileForGoal(goal: String): AppAutomationProfile? {
        val value = goal.lowercase()
        // Upload/publish tasks target Studio, not the consumer YouTube app.
        if (containsAlias(value, "youtube") &&
            listOf("upload", "publish", "post", "short", "studio", "creator").any { containsAlias(value, it) }) {
            return profiles.firstOrNull { it.id == "YOUTUBE_STUDIO" }
        }

        val matches = profiles.flatMap { profile ->
            profile.aliases.filter { alias -> containsAlias(value, alias) }
                .map { alias -> profile to alias }
        }
        if (matches.isEmpty()) return null

        // When Chrome/browser is just the transport ("open Gmail in Chrome"), route to
        // the requested service, not to the browser home page.
        val explicitlyTargetsBrowser = listOf("open chrome", "launch chrome", "start chrome", "google chrome app")
            .any { value.contains(it) } &&
            !webSignals.any { value.contains(it) } &&
            !urlRegex.containsMatchIn(value)
        if (!explicitlyTargetsBrowser) {
            val targetMatches = matches.filterNot { (profile, _) -> profile.id == "CHROME" }
            if (targetMatches.isNotEmpty() && (isWebRequest(value) || value.contains("website"))) {
                return targetMatches.maxByOrNull { it.second.length }?.first
            }
        }

        // Choose the most specific alias across every adapter, not the first profile in a list.
        return matches.maxWithOrNull(
            compareBy<Pair<AppAutomationProfile, String>> { it.second.length }
                .thenBy { if (it.first.id == "CHROME") 0 else 1 }
        )?.first
    }

    fun profileForPackage(packageName: String?): AppAutomationProfile? {
        if (packageName.isNullOrBlank()) return null
        return profiles.firstOrNull { profile ->
            profile.packageNames.any { it.equals(packageName, ignoreCase = true) }
        }
    }

    fun profileForUrl(rawUrl: String?): AppAutomationProfile? {
        if (rawUrl.isNullOrBlank()) return null
        val host = runCatching { URI(rawUrl).host?.trim('[', ']')?.lowercase().orEmpty() }.getOrDefault("")
        return when {
            host == "studio.youtube.com" || host.endsWith(".studio.youtube.com") ->
                profiles.firstOrNull { it.id == "YOUTUBE_STUDIO" }
            host == "labs.google" || host.endsWith(".labs.google") ||
                host == "flow.google" || host.endsWith(".flow.google") ->
                profiles.firstOrNull { it.id == "GOOGLE_FLOW" }
            host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be" ->
                profiles.firstOrNull { it.id == "YOUTUBE" }
            host == "web.whatsapp.com" || host.endsWith(".whatsapp.com") ->
                profiles.firstOrNull { it.id == "WHATSAPP" }
            host == "instagram.com" || host.endsWith(".instagram.com") ->
                profiles.firstOrNull { it.id == "INSTAGRAM" }
            host == "mail.google.com" ->
                profiles.firstOrNull { it.id == "GMAIL" }
            host == "drive.google.com" ->
                profiles.firstOrNull { it.id == "GOOGLE_DRIVE" }
            host == "photos.google.com" ->
                profiles.firstOrNull { it.id == "GOOGLE_PHOTOS" }
            host == "maps.google.com" ->
                profiles.firstOrNull { it.id == "GOOGLE_MAPS" }
            host == "web.telegram.org" || host.endsWith(".telegram.org") ->
                profiles.firstOrNull { it.id == "TELEGRAM" }
            host == "facebook.com" || host.endsWith(".facebook.com") ->
                profiles.firstOrNull { it.id == "FACEBOOK" }
            host == "x.com" || host.endsWith(".x.com") || host == "twitter.com" || host.endsWith(".twitter.com") ->
                profiles.firstOrNull { it.id == "X" }
            host == "open.spotify.com" || host.endsWith(".spotify.com") ->
                profiles.firstOrNull { it.id == "SPOTIFY" }
            host == "linkedin.com" || host.endsWith(".linkedin.com") ->
                profiles.firstOrNull { it.id == "LINKEDIN" }
            else -> null
        }
    }

    fun memoryDomainForGoal(goal: String, foregroundPackage: String? = null): String {
        return profileForGoal(goal)?.id
            ?: profileForPackage(foregroundPackage)?.id
            ?: "ANDROID_UI"
    }

    fun memoryDomainForWebGoal(goal: String, currentUrl: String): String {
        return profileForGoal(goal)?.id
            ?: profileForUrl(currentUrl)?.id
            ?: "CUSTOM_WEB"
    }

    fun adapterGuidance(
        goal: String,
        packageName: String? = null,
        currentUrl: String? = null
    ): String {
        val profile = profileForGoal(goal) ?: profileForUrl(currentUrl) ?: profileForPackage(packageName)
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
        val youtubeUpload = containsAlias(value, "youtube") &&
            listOf("upload", "publish", "post", "short").any { containsAlias(value, it) }
        val flowTask = value.contains("google flow") || value.contains("flow.google") || value.contains("labs.google/fx")
        val explicitPhone = forcePhoneUi || isExplicitPhoneRequest(goal) ||
            (!youtubeUpload && !flowTask && profile != null &&
                isAppLaunchIntent(value) && !isWebRequest(value) && !isWebsiteWorkflow(value))
        val routeToWeb = !explicitPhone && (
            youtubeUpload || flowTask || isWebRequest(value) ||
                urlInGoal.isNotBlank() ||
                webWorkflowVerbs.any { value.contains(it) } ||
                suggestedMode.equals("WEB", ignoreCase = true)
            )

        val mode = if (routeToWeb) AutomationMode.BACKGROUND_WEB else AutomationMode.PHONE_UI
        val resolvedUrl = resolveStartUrl(goal, suggestedUrl, pinnedFlowUrl)
        val startUrl = if (
            routeToWeb && urlInGoal.isBlank() && profile != null && !youtubeUpload && !flowTask
        ) {
            val normalizedSuggestion = normalizeHttpUrl(suggestedUrl)
            val suggestionProfile = profileForUrl(normalizedSuggestion)
            val searchIntent = listOf(
                "search online", "search the web", "google search", "search for",
                "look up", "find information about", "research"
            ).any { value.contains(it) }
            when {
                normalizedSuggestion != null && suggestionProfile?.id == profile.id -> normalizedSuggestion
                searchIntent && normalizedSuggestion != null -> normalizedSuggestion
                else -> profile.webHome
            }
        } else {
            resolvedUrl
        }
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
        if (explicit != null) {
            // Never silently substitute an unrelated suggested URL when the user's explicit
            // destination is unsafe or malformed.
            return normalizeHttpUrl(explicit) ?: "https://www.google.com/"
        }

        val profile = profileForGoal(goal)
        if (profile != null && (isWebRequest(value) || isWebsiteWorkflow(value) ||
                webWorkflowVerbs.any { value.contains(it) } || value.contains("website"))) {
            return profile.webHome
        }

        normalizeHttpUrl(suggestedUrl)?.let { normalized ->
            val host = runCatching { URI(normalized).host?.trim('[', ']')?.lowercase().orEmpty() }.getOrDefault("")
            val isFlowDestination = host == "labs.google" || host.endsWith(".labs.google") ||
                host == "flow.google" || host.endsWith(".flow.google") ||
                host == "labs.google.com" || host.endsWith(".labs.google.com")
            // A saved Flow URL is only valid for an explicitly Flow-related task.
            if (!isUnsafeHost(host) && !isFlowDestination) return normalized
        }
        return "https://www.google.com/"
    }

    private fun isUnsafeHost(rawHost: String): Boolean {
        val host = rawHost.trim('[', ']').lowercase()
        if (host.isBlank() || host == "localhost" || host.endsWith(".localhost") ||
            host.endsWith(".local") || host.endsWith(".internal")) return true
        // A single-label hostname is commonly a device/local-network name, not a public host.
        if (!host.contains('.') && !host.contains(':')) return true

        val parts = host.split(".")
        val isIpv4 = parts.size == 4 && parts.all { part ->
            val number = part.toIntOrNull()
            number != null && number in 0..255
        }
        if (isIpv4) {
            val a = parts[0].toInt()
            val b = parts[1].toInt()
            if (a == 0 || a == 10 || a == 127 ||
                (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                a >= 224) return true
        }

        if (host.contains(':')) {
            if (host == "::" || host == "::1" || host.startsWith("::ffff:") ||
                host.startsWith("fc", true) || host.startsWith("fd", true) ||
                host.startsWith("ff", true) || Regex("""(?i)^fe[89ab].*""").matches(host)) {
                return true
            }
        }
        return false
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
            if (scheme != "https" || host.isNullOrBlank() || isUnsafeHost(host)) {
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
enum class TaskGateResult { READY, REFRESH, STOP }

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
/**
 * Local task-mix and outcome telemetry. This records task counts by adapter only; it is not
 * Android usage-access tracking and must not be described as users' total app-usage percentage.
 */
object TaskAnalytics {
    private const val PREFS = "nax_task_metrics_v1"
    private const val KEY_COUNTS = "adapter_counts"

    @Synchronized
    fun recordStart(context: Context, adapterId: String) {
        mutate(context, adapterId) { row ->
            row.put("started", row.optInt("started") + 1)
            row.put("lastStartedAt", System.currentTimeMillis())
        }
    }

    @Synchronized
    fun recordTerminal(context: Context, adapterId: String, status: TaskStatus) {
        val key = when (status) {
            TaskStatus.COMPLETED -> "completed"
            TaskStatus.FAILED -> "failed"
            TaskStatus.CANCELLED -> "cancelled"
            else -> return
        }
        mutate(context, adapterId) { row ->
            row.put(key, row.optInt(key) + 1)
            row.put("lastOutcomeAt", System.currentTimeMillis())
        }
    }

    @Synchronized
    fun taskMixSummary(context: Context, limit: Int = 5): String {
        val all = read(context)
        val adapters = all.keys().asSequence().mapNotNull { key ->
            val name = key ?: return@mapNotNull null
            val row = all.optJSONObject(name) ?: return@mapNotNull null
            Triple(name, row, row.optInt("started"))
        }.filter { it.third > 0 }.sortedByDescending { it.third }.take(limit).toList()
        val total = all.keys().asSequence().mapNotNull { key -> key?.let { all.optJSONObject(it) } }
            .sumOf { it.optInt("started") }
        if (total == 0) return "No task history measured yet."
        return adapters.joinToString(" • ") { (name, row, started) ->
            val share = (started * 100.0 / total).toInt()
            val completed = row.optInt("completed")
            val failed = row.optInt("failed")
            val decided = completed + failed
            val success = if (decided == 0) "n/a" else "${(completed * 100.0 / decided).toInt()}% success"
            "$name $share% of recorded tasks ($success)"
        }
    }

    private fun read(context: Context): JSONObject {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_COUNTS, "{}") ?: "{}"
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    private fun mutate(context: Context, adapterId: String, change: (JSONObject) -> Unit) {
        val all = read(context)
        val key = adapterId.ifBlank { "UNKNOWN" }
        val row = all.optJSONObject(key) ?: JSONObject()
        change(row)
        all.put(key, row)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_COUNTS, all.toString()).apply()
    }
}

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
                TaskAnalytics.recordTerminal(context, previous.adapterId, TaskStatus.CANCELLED)
                previous.copy(
                    status = TaskStatus.CANCELLED,
                    message = "Superseded by a newer task; previous work was not marked completed.",
                    updatedAt = now
                )
            } else previous
        }
        save(context, listOf(record) + prior.take(MAX_HISTORY - 1))
        TaskAnalytics.recordStart(context, adapterId)
        MainActivity.onTaskStatusUpdated?.invoke()
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
        if (old.status != status && status in setOf(TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.CANCELLED)) {
            TaskAnalytics.recordTerminal(context, old.adapterId, status)
        }
        save(context, records.take(MAX_HISTORY))
        MainActivity.onTaskStatusUpdated?.invoke()
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
        MainActivity.onTaskStatusUpdated?.invoke()
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


/**
 * Single foreground task controller shared by phone and web executors.
 * Pause takes effect between action steps; cancellation remains an immediate coroutine cancel.
 */
object TaskRunController {
    @Volatile private var activeTaskId: String? = null
    @Volatile private var pauseRequested: Boolean = false
    @Volatile private var cancelRequested: Boolean = false

    @Synchronized
    fun begin(taskId: String) {
        activeTaskId = taskId
        pauseRequested = false
        cancelRequested = false
    }

    @Synchronized
    fun requestPause(): Boolean {
        if (activeTaskId.isNullOrBlank() || cancelRequested) return false
        pauseRequested = true
        return true
    }

    @Synchronized
    fun requestResume(): Boolean {
        if (activeTaskId.isNullOrBlank() || cancelRequested) return false
        pauseRequested = false
        return true
    }

    @Synchronized
    fun requestCancel(): Boolean {
        if (activeTaskId.isNullOrBlank()) return false
        cancelRequested = true
        pauseRequested = false
        return true
    }

    fun isPaused(): Boolean = pauseRequested
    fun hasActiveTask(): Boolean = !activeTaskId.isNullOrBlank() && !cancelRequested

    /**
     * Returns REFRESH after pause/resume so callers discard the old snapshot/AI decision.
     * STOP means this run no longer owns the controller or the user cancelled it.
     */
    suspend fun awaitBeforeStep(context: Context, taskId: String, step: Int): TaskGateResult {
        if (activeTaskId != taskId) return TaskGateResult.STOP
        var announcedPause = false
        while (activeTaskId == taskId && pauseRequested && !cancelRequested) {
            if (!announcedPause) {
                TaskExecutionStore.update(
                    context, taskId, TaskStatus.PAUSED, step,
                    "Paused by user between actions. Resume to continue safely."
                )
                VideoQueueManager.appendLog(context, "⏸️ Task paused at step $step; no new action will run until resumed.")
                announcedPause = true
            }
            delay(250)
        }
        if (activeTaskId != taskId || cancelRequested) return TaskGateResult.STOP
        if (announcedPause) {
            TaskExecutionStore.update(
                context, taskId, TaskStatus.RUNNING, step,
                "Resumed by user; discard the old decision and refresh the screen before continuing."
            )
            VideoQueueManager.appendLog(context, "▶️ Task resumed; refreshing screen state before the next action.")
            return TaskGateResult.REFRESH
        }
        return TaskGateResult.READY
    }

    @Synchronized
    fun finish(taskId: String?) {
        if (taskId.isNullOrBlank() || activeTaskId != taskId) return
        activeTaskId = null
        pauseRequested = false
        cancelRequested = false
    }
}
