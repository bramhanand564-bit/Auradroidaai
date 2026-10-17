package com.auradroid.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

data class VideoMetadata(
    val title: String,
    val description: String
)

data class StoryEpisodePlan(
    val episodeNumber: Int,
    val storyMode: String,
    val episodeTitle: String,
    val plotSummary: String,
    val videoPrompt: String,
    val durationSec: Int
)

data class ChatPlanResponse(
    val replyText: String,
    val planGoal: String,
    val planSteps: String,
    val suggestedMode: String, // "WEB" or "PHONE"
    val targetUrl: String,
    val parsedTimerSummary: String
)

data class AgentDecision(
    val action: String,      // CLICK_NODE, CLICK_XY, TYPE, SCROLL, BACK, OPEN_APP, WAIT, DONE
    val nodeIndex: Int = -1,
    val x: Float = 0f,
    val y: Float = 0f,
    val textToType: String = "",
    val appPackageOrSetting: String = "",
    val reason: String = "",
    val expectedOutcome: String = "",
    val thoughtProcess: String = "",
    val learnedRule: String = ""
)

data class WebAgentDecision(
    val action: String,      // CLICK_EL, CLICK_XY, DISMISS_POPUP, TYPE_EL, SCROLL, LOAD_URL, DOWNLOAD_VIDEO, WAIT, DONE
    val currentState: String = "STATE_ANALYZING",
    val elementIndex: Int = -1,
    val tapX: Float = -1f,
    val tapY: Float = -1f,
    val textToType: String = "",
    val url: String = "",
    val reason: String = "",
    val expectedOutcome: String = "",
    val thoughtProcess: String = "",
    val learnedRule: String = ""
)

data class PhoneTaskCompletionVerification(val verified: Boolean, val reason: String)

object GeminiBrain {

    @Volatile
    private var activeFastModel: String? = null

    @Volatile
    private var activeHighModel: String? = null

    @Volatile
    private var discoveredLiveModels: List<String> = emptyList()
    private var lastDiscoveryTimeMs: Long = 0L

    private val permanentlyDeadModels = ConcurrentHashMap.newKeySet<String>()
    private val cooldownModelsUntilMs = ConcurrentHashMap<String, Long>()

    private val fallbackSeedModels = listOf(
        "gemini-3.8-flash",
        "gemini-3.7-flash",
        "gemini-3.5-flash",
        "gemini-3.1-flash-lite-preview",
        "gemini-3.1-flash",
        "gemini-3.0-flash",
        "gemini-3.8-pro",
        "gemini-3.7-pro",
        "gemini-3.5-pro",
        "gemini-3.1-pro",
        "gemini-flash-latest",
        "gemini-2.5-flash"
    )

    private fun extractVersionScore(modelName: String): Double {
        val m = Regex("gemini-(\\d+(?:\\.\\d+)?)").find(modelName.lowercase())
        return m?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 3.0
    }

    private fun refreshLiveModelsIfNeeded(apiKey: String, forceRefresh: Boolean = false): List<String> {
        val now = System.currentTimeMillis()
        if (!forceRefresh && discoveredLiveModels.isNotEmpty() && (now - lastDiscoveryTimeMs) < 15 * 60 * 1000L) {
            return discoveredLiveModels
        }
        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models?key=${apiKey.trim()}")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12000
                readTimeout = 12000
            }
            if (conn.responseCode !in 200..299) return discoveredLiveModels
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val modelsArr = JSONObject(body).optJSONArray("models") ?: return discoveredLiveModels

            val validModels = mutableListOf<String>()
            for (i in 0 until modelsArr.length()) {
                val item = modelsArr.getJSONObject(i)
                val rawName = item.optString("name", "").removePrefix("models/").trim()
                val methods = item.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
                val lower = rawName.lowercase()

                val isSupported = methods.contains("generateContent") &&
                        lower.startsWith("gemini") &&
                        !lower.contains("embedding") &&
                        !lower.contains("imagen") &&
                        !lower.contains("veo") &&
                        !lower.contains("tts") &&
                        !lower.contains("audio")

                if (isSupported && rawName !in permanentlyDeadModels) {
                    validModels.add(rawName)
                }
            }

            if (validModels.isNotEmpty()) {
                discoveredLiveModels = validModels
                lastDiscoveryTimeMs = now
            }
            discoveredLiveModels
        } catch (_: Exception) {
            discoveredLiveModels
        }
    }

    private fun buildSmartCandidateQueue(
        apiKey: String,
        userPreferredModel: String,
        escalateToHighModel: Boolean,
        highModelName: String
    ): List<String> {
        val now = System.currentTimeMillis()
        val liveList = refreshLiveModelsIfNeeded(apiKey)
        val cleanUserFast = userPreferredModel.trim().removePrefix("models/")
        val cleanUserHigh = highModelName.trim().removePrefix("models/")

        val combinedPool = (listOf(cleanUserHigh, cleanUserFast) + liveList + fallbackSeedModels)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val healthyPool = combinedPool.filter { model ->
            if (model in permanentlyDeadModels) return@filter false
            val cooldownUntil = cooldownModelsUntilMs[model] ?: 0L
            now >= cooldownUntil
        }.ifEmpty {
            cooldownModelsUntilMs.clear()
            combinedPool.filter { it !in permanentlyDeadModels }
        }

        val sortedPool = if (escalateToHighModel) {
            healthyPool.sortedWith(
                compareByDescending<String> { extractVersionScore(it) }
                    .thenByDescending { name ->
                        val l = name.lowercase()
                        when {
                            l.contains("pro") && !l.contains("vision") -> 4
                            l.contains("thinking") -> 4
                            l.contains("flash") && !l.contains("lite") -> 3
                            else -> 1
                        }
                    }
            )
        } else {
            healthyPool.sortedWith(
                compareByDescending<String> { extractVersionScore(it) }
                    .thenByDescending { name ->
                        val l = name.lowercase()
                        when {
                            l.contains("flash") && !l.contains("lite") -> 4
                            l.contains("pro") -> 2
                            else -> 1
                        }
                    }
            )
        }

        val finalQueue = linkedSetOf<String>()
        if (escalateToHighModel) {
            activeHighModel?.takeIf { it in healthyPool }?.let { finalQueue.add(it) }
            activeFastModel?.takeIf { it in healthyPool }?.let { finalQueue.add(it) }
        } else {
            activeFastModel?.takeIf { it in healthyPool }?.let { finalQueue.add(it) }
        }
        finalQueue.addAll(sortedPool)
        return finalQueue.toList()
    }

    private suspend fun callGeminiApi(
        apiKey: String,
        userModel: String,
        prompt: String,
        currentImageJpeg: String? = null,
        previousImageJpeg: String? = null,
        extraImagesBase64: List<String> = emptyList(),
        escalateToHighModel: Boolean = false,
        highModelName: String = "gemini-3.8-pro",
        enableGoogleSearch: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw Exception("Gemini API Key missing! Settings tab mein API Key daalein.")

        var candidateQueue = buildSmartCandidateQueue(apiKey, userModel, escalateToHighModel, highModelName)
        if (candidateQueue.isEmpty()) {
            permanentlyDeadModels.clear()
            cooldownModelsUntilMs.clear()
            candidateQueue = refreshLiveModelsIfNeeded(apiKey, forceRefresh = true) + fallbackSeedModels
        }

        var lastError = ""

        suspend fun trySingleModel(modelName: String, withSearchTool: Boolean): String? {
            try {
                val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=${apiKey.trim()}"
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 28000
                    readTimeout = 28000
                    doOutput = true
                }

                val partsArray = JSONArray().apply {
                    put(JSONObject().put("text", prompt))
                    if (!previousImageJpeg.isNullOrBlank() && previousImageJpeg != currentImageJpeg) {
                        put(JSONObject().put("text", "FRAME 1 (PREVIOUS SCREEN STATE):"))
                        put(JSONObject().put("inline_data", JSONObject().apply {
                            put("mime_type", "image/jpeg")
                            put("data", previousImageJpeg)
                        }))
                    }
                    if (!currentImageJpeg.isNullOrBlank()) {
                        put(JSONObject().put("text", "FRAME 2 (CURRENT LIVE SCREENSHOT - 540x960 PURE VISION COORDINATE SPACE):"))
                        put(JSONObject().put("inline_data", JSONObject().apply {
                            put("mime_type", "image/jpeg")
                            put("data", currentImageJpeg)
                        }))
                    }
                    extraImagesBase64.forEachIndexed { idx, b64 ->
                        if (b64.isNotBlank()) {
                            put(JSONObject().put("text", "VISION SNAPSHOT #${idx + 1}:"))
                            put(JSONObject().put("inline_data", JSONObject().apply {
                                put("mime_type", "image/jpeg")
                                put("data", b64)
                            }))
                        }
                    }
                }

                val payload = JSONObject().apply {
                    put("contents", JSONArray().put(JSONObject().put("parts", partsArray)))
                    if (withSearchTool) {
                        put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
                        put("generationConfig", JSONObject().apply {
                            put("temperature", 0.22)
                        })
                    } else {
                        put("generationConfig", JSONObject().apply {
                            put("temperature", 0.22)
                            put("responseMimeType", "application/json")
                        })
                    }
                }

                OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val responseText = stream?.bufferedReader()?.use { it.readText() } ?: ""

                if (code in 200..299) {
                    val root = JSONObject(responseText)
                    val parts = root.getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                    val sb = StringBuilder()
                    for (i in 0 until parts.length()) {
                        sb.append(parts.getJSONObject(i).optString("text", ""))
                    }
                    val finalOut = sb.toString().trim()
                    if (finalOut.isNotEmpty()) {
                        if (escalateToHighModel) activeHighModel = modelName else activeFastModel = modelName
                        return finalOut
                    }
                } else {
                    lastError = "HTTP $code ($modelName)"
                    when (code) {
                        404, 400 -> if (!withSearchTool) permanentlyDeadModels.add(modelName)
                        429, 403 -> cooldownModelsUntilMs[modelName] = System.currentTimeMillis() + 120_000L
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = "${e.message} ($modelName)"
            }
            return null
        }

        for (modelName in candidateQueue) {
            if (enableGoogleSearch) {
                trySingleModel(modelName, withSearchTool = true)?.let { return@withContext it }
            }
            trySingleModel(modelName, withSearchTool = false)?.let { return@withContext it }
            delay(120)
        }

        throw Exception("All Free-Tier Gemini models busy ($lastError). Retrying...")
    }

    private fun extractCleanJsonObject(raw: String): JSONObject {
        val trimmed = raw.trim()
        val firstBrace = trimmed.indexOf('{')
        val lastBrace = trimmed.lastIndexOf('}')
        if (firstBrace != -1 && lastBrace > firstBrace) {
            return JSONObject(trimmed.substring(firstBrace, lastBrace + 1))
        }
        return JSONObject(trimmed)
    }

    /**
     * Episodic Story Showrunner (Part 1 -> Part 2 -> Part 3 + Side-Story + Custom Duration)
     */
    suspend fun generateEpisodicStoryPrompt(
        apiKey: String,
        model: String,
        seriesTheme: String,
        characterLock: String,
        storyMode: String,
        episodeNumber: Int,
        previousEpisodesContext: String,
        customUserTwist: String,
        targetDurationSec: Int
    ): StoryEpisodePlan {
        val wantsHindiVoice = seriesTheme.contains("hindi", true) ||
                seriesTheme.contains("voice", true) ||
                seriesTheme.contains("dialog", true) ||
                seriesTheme.contains("bol", true)

        val hindiVoiceRule = if (wantsHindiVoice) {
            """
            HINDI VOICE & DIALOGUE TIMING ($targetDurationSec SECONDS):
            Include a natural spoken Hindi dialogue in quotes paced for a $targetDurationSec-second scene so Google Flow generates clear Hindi audio and lip-sync!
            """.trimIndent()
        } else ""

        val modeInstruction = when (storyMode.uppercase()) {
            "SERIAL" -> "SERIAL EPISODE (PART $episodeNumber): Advance the story forward from previous episodes. Never repeat any scene."
            "SIDE_STORY" -> "SIDE-STORY / SPIN-OFF REEL: Create a fun technical side-story in the same universe."
            else -> "FRESH STANDALONE REEL: Create a fresh standalone viral technical scene."
        }

        val twistInstruction = if (customUserTwist.isNotBlank()) "USER TWIST: \"$customUserTwist\"" else ""

        val prompt = """
            You are an elite AI Showrunner for Google Flow ($targetDurationSec-second cinematic video generator).
            SERIES THEME: "$seriesTheme"
            CHARACTER VISUAL LOCK: "$characterLock"
            TARGET DURATION: $targetDurationSec seconds$modeInstruction
            
            PREVIOUS EPISODES:
            $previousEpisodesContext$twistInstruction
            
            SAFETY RULES: Never use words like boy, girl, child, kid, minor, or skin. Always use adult futuristic cybernetic tech explorer.
            $hindiVoiceRule
            
            Respond strictly in valid JSON:
            {
              "episodeTitle": "Catchy title for Part $episodeNumber",
              "plotSummary": "1-2 sentence summary of Part $episodeNumber",
              "videoPrompt": "Full policy-safe $targetDurationSec-second video prompt with character look and Hindi dialog..."
            }
        """.trimIndent()

        return try {
            val raw = callGeminiApi(apiKey, model, prompt)
            val obj = extractCleanJsonObject(raw)
            val rawPrompt = obj.optString("videoPrompt", seriesTheme).ifBlank { seriesTheme }
            val safePrompt = rawPrompt
                .replace(Regex("\\b(young\\s+boy|little\\s+boy|boy|child|kid|minor|teenager|teen|girl)\\b", RegexOption.IGNORE_CASE), "adult futuristic cybernetic tech explorer")
                .replace(Regex("\\b(skin|bare)\\b", RegexOption.IGNORE_CASE), "metallic suit")

            StoryEpisodePlan(
                episodeNumber = episodeNumber,
                storyMode = storyMode.uppercase(),
                episodeTitle = obj.optString("episodeTitle", "Part $episodeNumber"),
                plotSummary = obj.optString("plotSummary", "Episode $episodeNumber advances the technical journey."),
                videoPrompt = safePrompt,
                durationSec = targetDurationSec
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            StoryEpisodePlan(episodeNumber, storyMode, "Part $episodeNumber", "Episode $episodeNumber", seriesTheme, targetDurationSec)
        }
    }

    suspend fun trainSkillFromScreenshots(
        apiKey: String,
        model: String,
        skillName: String,
        userInstruction: String,
        screenshotsBase64: List<String>
    ): String {
        val prompt = """
            You are AuraDroid OS Cognitive State-Machine Architect.
            Analyze ${screenshotsBase64.size} sequential screenshots for "$skillName" and create a State-Machine JSON Blueprint with DOM-less Vision Coordinates (540x960) and 1-second AI confirmation criteria.
            Respond strictly in valid JSON:
            {
              "skill": "$skillName",
              "architecture": "COGNITIVE_STATE_MACHINE_AND_PURE_VISION",
              "states": [
                {
                  "state": "STATE_1",
                  "screen_identify": "...",
                  "action": "CLICK_EL_OR_VISION_XY",
                  "target": "...",
                  "vision_xy_540x960": "270, 700",
                  "next_state_confirm": "..."
                }
              ]
            }
        """.trimIndent()

        return try {
            val raw = callGeminiApi(apiKey, model, prompt, extraImagesBase64 = screenshotsBase64.take(8), escalateToHighModel = true)
            extractCleanJsonObject(raw).toString(2)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw Exception("Training Error: ${e.message}")
        }
    }

    suspend fun chatAndBuildPlan(
        apiKey: String,
        model: String,
        userMessage: String,
        chatHistory: String,
        memoryVault: String
    ): ChatPlanResponse {
        val prompt = """
            You are Nax Bot, a friendly Hindi/Hinglish speaking Cognitive Autonomous AI Assistant inside AuraDroid OS.
            USER MEMORY: $memoryVault
            CHAT HISTORY: ${chatHistory.takeLast(2500)}
            NEW MESSAGE: "$userMessage"
            
            Your job:
            1. Parse any timer or schedule instructions and summarize in parsedTimerSummary.
            2. Reply naturally in Hinglish as Nax Bot.
            3. Create a numbered Step-by-Step Cognitive State Plan.
            4. Choose suggestedMode: "WEB" for Google Flow/websites, "PHONE" for native Android apps.
            
            Respond strictly in valid JSON:
            {
              "replyText": "Hinglish reply from Nax Bot",
              "planGoal": "Core goal",
              "planSteps": "1. State one...\n2. State two...",
              "suggestedMode": "WEB",
              "targetUrl": "https://labs.google/fx/tools/flow",
              "parsedTimerSummary": "⏰ Timer Locked: Scheduled as requested."
            }
        """.trimIndent()

        return try {
            val rawJson = callGeminiApi(apiKey, model, prompt)
            val obj = extractCleanJsonObject(rawJson)
            ChatPlanResponse(
                replyText = obj.optString("replyText", "Nax Bot ne cognitive state plan taiyar kar liya hai!"),
                planGoal = obj.optString("planGoal", userMessage),
                planSteps = obj.optString("planSteps", "1. Execute state machine"),
                suggestedMode = obj.optString("suggestedMode", "WEB").uppercase(),
                targetUrl = obj.optString("targetUrl", ""),
                parsedTimerSummary = obj.optString("parsedTimerSummary", "⏰ Smart Timer: Active")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ChatPlanResponse("Plan taiyar hai!", userMessage, "1. Run task", "WEB", "", "⏰ Timer Active")
        }
    }

    suspend fun generateYouTubeMetadataWithVision(
        apiKey: String,
        model: String,
        videoFramesBase64: List<String>,
        fileName: String,
        userInstruction: String,
        episodeNum: Int = 0,
        episodeTitle: String = "",
        storySummary: String = ""
    ): VideoMetadata {
        val episodeContext = if (episodeNum > 0) {
            "This is Part $episodeNum of an ongoing series! Title: '$episodeTitle'. Summary: '$storySummary'."
        } else ""

        val prompt = """
            You are an expert YouTube Shorts Viral SEO Manager with Vision AI capabilities.
            Inspect the provided video frames extracted directly from the generated MP4 file.
            Topic: "$userInstruction"
            $episodeContext
            
            Craft a high-CTR, curiosity-inducing viral YouTube Shorts title (max 85 chars, include Part $episodeNum if applicable and #shorts #viral, never mention 'Google Flow' or raw file names) and a rich SEO description.
            Respond strictly in valid JSON:
            {
              "title": "Viral Title • Part $episodeNum #shorts #viral",
              "description": "Full description with #hashtags"
            }
        """.trimIndent()

        return try {
            val rawJson = callGeminiApi(
                apiKey = apiKey,
                userModel = model,
                prompt = prompt,
                extraImagesBase64 = videoFramesBase64,
                escalateToHighModel = true
            )
            val obj = extractCleanJsonObject(rawJson)
            VideoMetadata(
                title = obj.optString("title", "Cybernetic Future • Part ${episodeNum.coerceAtLeast(1)} #shorts #viral").take(95),
                description = obj.optString("description", "$storySummary\n\n#shorts #viral #trending")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            VideoMetadata(
                title = "${episodeTitle.ifBlank { "Cybernetic Secret" }} • Part ${episodeNum.coerceAtLeast(1)} #shorts #viral".take(95),
                description = "$storySummary\n\n#shorts #viral"
            )
        }
    }

    suspend fun generateYouTubeMetadata(
        apiKey: String,
        model: String,
        fileName: String,
        userInstruction: String,
        episodeNum: Int = 0,
        episodeTitle: String = "",
        storySummary: String = ""
    ): VideoMetadata {
        return generateYouTubeMetadataWithVision(
            apiKey = apiKey,
            model = model,
            videoFramesBase64 = emptyList(),
            fileName = fileName,
            userInstruction = userInstruction,
            episodeNum = episodeNum,
            episodeTitle = episodeTitle,
            storySummary = storySummary
        )
    }

    /**
     * Idea 1, 2 & 3 Upgrade:
     * Cognitive State-Machine + DOM-Less Pure Vision Coordinate Extractor + RAG Error Memory Web Agent!
     */
    suspend fun decideNextWebAction(
        apiKey: String,
        model: String,
        goal: String,
        pageUrl: String,
        pageTextAndElements: String,
        history: List<String>,
        memoryVault: String,
        learnedLessons: String = "",
        trainedBlueprint: String = "",
        screenChangedStatus: String = "",
        screenshotBase64: String? = null,
        previousScreenshotBase64: String? = null,
        useHighModel: Boolean = false,
        highModelName: String = "gemini-3.8-pro",
        useGoogleSearch: Boolean = false
    ): WebAgentDecision {
        val prompt = """
            You are Nax Bot's Cognitive State-Machine & Pure Vision Web Agent (540x960 coordinate space).
            OVERALL GOAL: $goal
            CURRENT URL: $pageUrl
            
            STATE-MACHINE BLUEPRINT (GUIDE):
            ${trainedBlueprint.ifBlank { "Dynamic State Machine Navigation" }}
            
            VECTOR RAG SELF-HEALING MEMORIES (SIMILAR PAST SITUATIONS):
            $learnedLessons
            
            1s AI CONFIRMATION & OBSTACLE REPORT:
            $screenChangedStatus
            
            RECENT STATE TRANSITIONS:
            ${history.takeLast(8).joinToString("\n")}
            
            DOM ELEMENTS (IF AVAILABLE - MAY BE EMPTY IF SITE CHANGED HTML):
            $pageTextAndElements
            
            COGNITIVE STATE-MACHINE & PURE VISION RULES:
            1. IDENTIFY CURRENT STATE: Look at FRAME 2 (540x960 screenshot) and determine where you are right now (e.g. STATE_POPUP_OBSTACLE, STATE_LANDING, STATE_COMPOSER, STATE_RENDERING, STATE_VIDEO_READY).
            2. POPUP / OBSTACLE AUTO-KILLER: If any cookie banner, 'Got it', 'Accept', 'Close (X)', or unexpected modal dialog is blocking the screen, return action="DISMISS_POPUP" with elementIndex or visual coordinates (tapX, tapY) to close it first!
            3. DOM-LESS PURE VISION FALLBACK: If DOM elements do not match or a previous click failed, look directly at FRAME 2 (width=540, height=960) with your eyes, find the exact button/text position, and return action="CLICK_XY" with exact `tapX` (0..540) and `tapY` (0..960)!
            4. Never type the video prompt more than once. When rendering reaches 100%, return action="DOWNLOAD_VIDEO".
            
            Respond strictly in valid JSON:
            {
              "current_state": "STATE_LANDING",
              "thought_process": "Visual and state-machine reasoning",
              "expected_outcome": "What state should appear in 1 second",
              "learned_rule": "Optional new self-healing rule if an obstacle was solved",
              "action": "CLICK_EL",
              "elementIndex": 2,
              "tapX": 270,
              "tapY": 700,
              "textToType": "",
              "url": "",
              "reason": "Short status for Nax Bot"
            }
        """.trimIndent()

        return try {
            val rawJson = callGeminiApi(
                apiKey = apiKey,
                userModel = model,
                prompt = prompt,
                currentImageJpeg = screenshotBase64,
                previousImageJpeg = previousScreenshotBase64,
                escalateToHighModel = useHighModel,
                highModelName = highModelName,
                enableGoogleSearch = useGoogleSearch
            )
            val obj = extractCleanJsonObject(rawJson)
            WebAgentDecision(
                action = obj.optString("action", "WAIT").uppercase(),
                currentState = obj.optString("current_state", "STATE_ACTIVE"),
                elementIndex = obj.optInt("elementIndex", -1),
                tapX = obj.optDouble("tapX", -1.0).toFloat(),
                tapY = obj.optDouble("tapY", -1.0).toFloat(),
                textToType = obj.optString("textToType", ""),
                url = obj.optString("url", ""),
                reason = obj.optString("reason", "Executing state..."),
                expectedOutcome = obj.optString("expected_outcome", "State transition"),
                thoughtProcess = obj.optString("thought_process", ""),
                learnedRule = obj.optString("learned_rule", "")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            WebAgentDecision(action = "WAIT", reason = "Analyzing state...")
        }
    }

    /**
     * Native Android Phone UI Cognitive Agent (Used by AutoTouchService / PhoneAgentManager)
     */
    suspend fun decideNextUiAction(
        apiKey: String,
        model: String,
        goal: String,
        screenSummary: String,
        history: List<String>,
        memoryVault: String = "",
        learnedLessons: String = "",
        trainedBlueprint: String = "",
        screenChangedStatus: String = "",
        screenshotBase64: String? = null,
        previousScreenshotBase64: String? = null,
        screenWidth: Int = 1080,
        screenHeight: Int = 2408,
        isContinuousMonitorMode: Boolean = false,
        useHighModel: Boolean = false,
        highModelName: String = "gemini-3.8-pro",
        useGoogleSearch: Boolean = false
    ): AgentDecision {
        val prompt = """
            You are Nax Bot's Cognitive State-Machine & Pure Vision Android Phone Agent.
            Phone resolution: ${screenWidth}x${screenHeight} pixels.
            OVERALL GOAL: $goal
            
            VECTOR RAG LESSONS:
            $learnedLessons
            
            1s STEP CONFIRMATION STATUS:
            $screenChangedStatus
            
            RECENT ACTIONS:
            ${history.takeLast(8).joinToString("\n")}
            
            SCREEN UI NODES:
            $screenSummary
            
            If a UI node click fails or is missing from the accessibility tree, inspect the screenshot visually and return action="CLICK_XY" with exact pixel coordinates (x, y).
            Respond strictly in valid JSON:
            {
              "thought_process": "Reasoning",
              "expected_outcome": "Next state",
              "learned_rule": "",
              "action": "CLICK_NODE",
              "nodeIndex": 3,
              "x": 540,
              "y": 1200,
              "textToType": "",
              "appPackageOrSetting": "",
              "reason": "Status for Nax Bot"
            }
        """.trimIndent()

        return try {
            val rawJson = callGeminiApi(
                apiKey = apiKey,
                userModel = model,
                prompt = prompt,
                currentImageJpeg = screenshotBase64,
                previousImageJpeg = previousScreenshotBase64,
                escalateToHighModel = useHighModel,
                highModelName = highModelName,
                enableGoogleSearch = useGoogleSearch
            )
            val obj = extractCleanJsonObject(rawJson)
            AgentDecision(
                action = obj.optString("action", "WAIT").uppercase(),
                nodeIndex = obj.optInt("nodeIndex", -1),
                x = obj.optDouble("x", 540.0).toFloat(),
                y = obj.optDouble("y", 1000.0).toFloat(),
                textToType = obj.optString("textToType", ""),
                appPackageOrSetting = obj.optString("appPackageOrSetting", ""),
                reason = obj.optString("reason", "Proceeding"),
                expectedOutcome = obj.optString("expected_outcome", "Screen update"),
                thoughtProcess = obj.optString("thought_process", ""),
                learnedRule = obj.optString("learned_rule", "")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            AgentDecision(action = "WAIT", reason = "Waiting...")
        }
    }

    fun getAutoModelStatusReport(): String {
        val fast = activeFastModel ?: "Auto (3.8/3.7/3.1)"
        val high = activeHighModel ?: "Auto-Pro (3.8/3.1)"
        return "Fast: $fast • High: $high • Live: ${discoveredLiveModels.size}"
    }
    /** Independently verify a proposed DONE state against current-screen evidence. */
    suspend fun verifyPhoneTaskCompletion(
        apiKey: String, model: String, highModelName: String, goal: String,
        screenSummary: String, recentHistory: List<String>, screenshotBase64: String?
    ): PhoneTaskCompletionVerification {
        val prompt = "Verify whether this Android task is demonstrably complete. Do not trust a proposed DONE state without evidence. If uncertain, return verified=false. Goal: $goal. Current screen: $screenSummary. Recent actions: ${recentHistory.takeLast(8).joinToString(\"; \")}. Return JSON only: {\"verified\":true/false,\"reason\":\"brief evidence\"}."
        return try {
            val raw = callGeminiApi(apiKey=apiKey, userModel=model, prompt=prompt, currentImageJpeg=screenshotBase64, escalateToHighModel=true, highModelName=highModelName)
            val obj = extractCleanJsonObject(raw)
            PhoneTaskCompletionVerification(obj.optBoolean("verified", false), obj.optString("reason", "No clear completion evidence."))
        } catch (e: CancellationException) { throw e }
          catch (e: Exception) { PhoneTaskCompletionVerification(false, "Independent verification unavailable: ${e.message ?: \"unknown error\"}") }
    }

}
