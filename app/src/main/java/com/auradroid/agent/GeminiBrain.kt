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
    val targetUrl: String
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
    val action: String,      // CLICK_EL, CLICK_XY, TYPE_EL, SCROLL, LOAD_URL, DOWNLOAD_VIDEO, CHAIN_YOUTUBE, WAIT, DONE
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

    // Prioritizes 4.x / 3.8 / 3.7 / 3.5 / 3.1 Free-Tier models first, then falls back if needed
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
        "gemini-pro-latest",
        "gemini-2.5-flash",
        "gemini-2.5-flash-lite",
        "gemini-2.5-pro",
        "gemini-2.0-flash"
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
                        !lower.contains("audio") &&
                        !lower.contains("vision-only") &&
                        !lower.contains("aqa")

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
                            l.contains("flash") && !l.contains("lite") && !l.contains("8b") -> 4
                            l.contains("flash-lite") || l.contains("lite") -> 3
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
                        put(JSONObject().put("text", "FRAME 1 (PREVIOUS SCREEN BEFORE LAST ACTION):"))
                        put(JSONObject().put("inline_data", JSONObject().apply {
                            put("mime_type", "image/jpeg")
                            put("data", previousImageJpeg)
                        }))
                    }
                    if (!currentImageJpeg.isNullOrBlank()) {
                        put(JSONObject().put("text", "FRAME 2 (CURRENT LIVE SCREEN RIGHT NOW):"))
                        put(JSONObject().put("inline_data", JSONObject().apply {
                            put("mime_type", "image/jpeg")
                            put("data", currentImageJpeg)
                        }))
                    }
                    extraImagesBase64.forEachIndexed { idx, b64 ->
                        if (b64.isNotBlank()) {
                            put(JSONObject().put("text", "TRAINING SCREENSHOT STEP #${idx + 1}:"))
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
                            put("temperature", 0.25)
                        })
                    } else {
                        put("generationConfig", JSONObject().apply {
                            put("temperature", 0.25)
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
                        404, 400 -> {
                            if (!withSearchTool) {
                                permanentlyDeadModels.add(modelName)
                                if (activeFastModel == modelName) activeFastModel = null
                                if (activeHighModel == modelName) activeHighModel = null
                            }
                        }
                        429, 403 -> {
                            cooldownModelsUntilMs[modelName] = System.currentTimeMillis() + 120_000L
                            if (activeFastModel == modelName) activeFastModel = null
                            if (activeHighModel == modelName) activeHighModel = null
                        }
                        500, 502, 503, 504 -> {
                            cooldownModelsUntilMs[modelName] = System.currentTimeMillis() + 25_000L
                        }
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
            delay(140)
        }

        val freshLive = refreshLiveModelsIfNeeded(apiKey, forceRefresh = true)
        for (modelName in freshLive) {
            trySingleModel(modelName, withSearchTool = false)?.let { return@withContext it }
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
     * Customizable Episodic Story Engine (Part 1 -> Part 2 -> Part 3 + Side Stories + 8s/10s/12s/15s Duration!)
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
            6. HINDI VOICE & DIALOGUE TIMING ($targetDurationSec SECONDS):
               Include a natural spoken Hindi dialogue in quotes paced for a $targetDurationSec-second scene so Google Flow (Veo) generates clear Hindi audio & lip-sync!
               Every episode MUST have a completely NEW Hindi dialogue matching this episode's plot!
            """.trimIndent()
        } else ""

        val modeInstruction = when (storyMode.uppercase()) {
            "SERIAL" -> """
                STORY CONTINUITY MODE: SERIAL EPISODE (PART $episodeNumber)
                - Read PREVIOUS EPISODES HISTORY carefully.
                - Advance the technical story forward to Part $episodeNumber! NEVER repeat a previous scene or dialogue.
                - If Part 1 introduced the problem/discovery, Part 2 must show the next invention/challenge, Part 3 escalates the mission, etc.
            """.trimIndent()
            "SIDE_STORY" -> """
                STORY MODE: SIDE-STORY / SPIN-OFF REEL IN THE SAME UNIVERSE
                - Create a fun, engaging technical side-story set in the same world and featuring the same tech aesthetic, but showing a unique side gadget, AI drone, or mini-incident!
            """.trimIndent()
            else -> """
                STORY MODE: FRESH STANDALONE REEL
                - Create a completely fresh, standalone viral technical story scene based on the theme.
            """.trimIndent()
        }

        val twistInstruction = if (customUserTwist.isNotBlank()) {
            "USER'S CUSTOM IDEA / TWIST FOR THIS EPISODE (MUST INCLUDE!): \"$customUserTwist\""
        } else {
            "No custom twist specified—use your creative genius to make Part $episodeNumber exciting!"
        }

        val prompt = """
            You are an elite AI Showrunner & Video Prompt Director for Google Flow ($targetDurationSec-second cinematic video generator).
            
            CORE SERIES THEME: "$seriesTheme"
            MAIN CHARACTER VISUAL LOCK (Keep consistent across episodes): "$characterLock"
            TARGET VIDEO DURATION: $targetDurationSec seconds$modeInstruction
            
            PREVIOUS EPISODES HISTORY (NEVER REPEAT THESE!):
            $previousEpisodesContext$twistInstruction
            
            STRICT GOOGLE FLOW POLICY SAFETY RULES:
            1. Google Flow blocks any prompt containing words related to minors or children!
            2. NEVER use words like: "boy", "girl", "child", "kid", "young", "teen", "minor", "baby", "skin", "blood", "weapon", or "violence".
            3. Always describe the protagonist using safe adult/sci-fi terms like "adult futuristic cybernetic tech explorer" or "adult holographic engineer".
            4. Design the scene and camera movement specifically for a $targetDurationSec-second continuous cinematic shot.$hindiVoiceRule
            
            Respond strictly in valid JSON:
            {
              "episodeTitle": "Short catchy title for Part $episodeNumber (e.g. The Quantum Core Awakening)",
              "plotSummary": "1-2 sentence summary of what happens in Part $episodeNumber so future episodes remember it",
              "videoPrompt": "Full 100% policy-safe $targetDurationSec-second cinematic video prompt including character look, camera motion, and Hindi dialogue..."
            }
        """.trimIndent()

        return try {
            val raw = callGeminiApi(apiKey, model, prompt)
            val obj = extractCleanJsonObject(raw)
            val epTitle = obj.optString("episodeTitle", "Part $episodeNumber: Cybernetic Evolution")
            val summary = obj.optString("plotSummary", "Part $episodeNumber advances the technical journey.")
            val rawVideoPrompt = obj.optString("videoPrompt", seriesTheme).ifBlank { seriesTheme }

            val safeVideoPrompt = rawVideoPrompt
                .replace(Regex("\\b(young\\s+boy|little\\s+boy|boy|child|kid|minor|teenager|teen|girl)\\b", RegexOption.IGNORE_CASE), "adult futuristic cybernetic tech explorer")
                .replace(Regex("\\b(skin|bare)\\b", RegexOption.IGNORE_CASE), "metallic suit")

            StoryEpisodePlan(
                episodeNumber = episodeNumber,
                storyMode = storyMode.uppercase(),
                episodeTitle = epTitle,
                plotSummary = summary,
                videoPrompt = safeVideoPrompt,
                durationSec = targetDurationSec
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            val safeFallback = seriesTheme
                .replace(Regex("\\b(boy|girl|child|kid|young|teen)\\b", RegexOption.IGNORE_CASE), "adult cybernetic tech explorer")
            StoryEpisodePlan(
                episodeNumber = episodeNumber,
                storyMode = storyMode.uppercase(),
                episodeTitle = "Part $episodeNumber",
                plotSummary = "Episode $episodeNumber generated.",
                videoPrompt = safeFallback,
                durationSec = targetDurationSec
            )
        }
    }

    /**
     * Backwards-compatible wrapper for quick prompt generation
     */
    suspend fun generateDailyFlowPrompt(
        apiKey: String,
        model: String,
        userTopic: String
    ): String {
        val plan = generateEpisodicStoryPrompt(
            apiKey = apiKey,
            model = model,
            seriesTheme = userTopic,
            characterLock = "Adult futuristic cybernetic tech explorer",
            storyMode = "SERIAL",
            episodeNumber = 1,
            previousEpisodesContext = "Part 1",
            customUserTwist = "",
            targetDurationSec = 10
        )
        return plan.videoPrompt
    }

    /**
     * WM3-Style AI Skill Trainer:
     * Analyzes a sequence of step-by-step screenshots (from start to finish of any task)
     * and builds a permanent, self-healing JSON Skill Blueprint!
     */
    suspend fun trainSkillFromScreenshots(
        apiKey: String,
        model: String,
        skillName: String,
        userInstruction: String,
        screenshotsBase64: List<String>
    ): String {
        val prompt = """
            You are the AuraDroid OS 'Train Model' Visual Skill Architect (powered by Gemini 3.8/3.1).
            The user has provided ${screenshotsBase64.size} sequential screenshots demonstrating a complete task from start to finish.
            
            SKILL NAME: "$skillName"
            USER NOTES: "$userInstruction"
            
            Analyze each screenshot in order (Step 1, Step 2, Step 3...) and create a complete, self-healing JSON Execution Blueprint that our autonomous agent can follow every time without getting stuck!
            For each step, specify:
            - "step": step number
            - "screen_identify": how to recognize this screen visually or via DOM text
            - "action": one of ["CLICK_EL", "CLICK_XY", "TYPE_AND_SUBMIT", "WAIT_FOR_100_PERCENT", "EXTRACT_OR_TAP_CARD", "CLICK_NODE", "TYPE"]
            - "target": exact button text, placeholder, or visual position (e.g. "+ New project", "What do you want to create?", "Completed video card")
            - "verify_next": what must change or appear on the next screen to confirm the tap worked
            - "recovery_hint": what the AI should do if the click doesn't open the next page (e.g. "Use 3x scaled real finger tap or scroll down")
            
            Respond strictly in valid JSON:
            {
              "skill": "$skillName",
              "description": "Trained visual workflow for $skillName",
              "steps": [
                {
                  "step": 1,
                  "screen_identify": "...",
                  "action": "...",
                  "target": "...",
                  "verify_next": "...",
                  "recovery_hint": "..."
                }
              ]
            }
        """.trimIndent()

        return try {
            val raw = callGeminiApi(
                apiKey = apiKey,
                userModel = model,
                prompt = prompt,
                extraImagesBase64 = screenshotsBase64.take(8),
                escalateToHighModel = true
            )
            extractCleanJsonObject(raw).toString(2)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw Exception("Skill Training Error: ${e.message}")
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
            You are Nax Bot, a friendly, smart Hindi/Hinglish speaking Universal Autonomous AI Assistant inside AuraDroid OS.
            
            USER'S SAVED MEMORY VAULT:
            $memoryVault
            
            RECENT CHAT CONVERSATION:
            ${chatHistory.takeLast(2500)}
            
            NEW USER MESSAGE:
            "$userMessage"
            
            Your job:
            1. Talk naturally to the user in clear Hindi/Hinglish as Nax Bot.
            2. Create a numbered Step-by-Step Execution Plan.
            3. Choose suggestedMode:
               - "WEB": If the task involves Google Flow, websites, episodic story video creation, or browser tasks.
               - "PHONE": If the task involves controlling native Android apps (WhatsApp, YouTube App, Settings, Instagram).
            4. If a website is mentioned, provide its URL in targetUrl (e.g. "https://labs.google/fx/tools/flow").
            
            Respond strictly in valid JSON:
            {
              "replyText": "Friendly Hindi/Hinglish reply from Nax Bot",
              "planGoal": "Complete concise instruction of the overall goal",
              "planSteps": "1. Step one...\n2. Step two...",
              "suggestedMode": "WEB",
              "targetUrl": "https://labs.google/fx/tools/flow"
            }
        """.trimIndent()

        return try {
            val rawJson = callGeminiApi(apiKey, model, prompt)
            val obj = extractCleanJsonObject(rawJson)
            ChatPlanResponse(
                replyText = obj.optString("replyText", "Nax Bot ne aapka plan taiyar kar liya hai!"),
                planGoal = obj.optString("planGoal", userMessage),
                planSteps = obj.optString("planSteps", "1. Task analyze karein\n2. Action execute karein"),
                suggestedMode = obj.optString("suggestedMode", "WEB").uppercase(),
                targetUrl = obj.optString("targetUrl", "")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ChatPlanResponse(
                replyText = "⚠️ Plan banane mein dikkat aayi: ${e.message}",
                planGoal = userMessage,
                planSteps = "1. Execute: $userMessage",
                suggestedMode = "PHONE",
                targetUrl = ""
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
        val episodeContext = if (episodeNum > 0) {
            "IMPORTANT: This video is 'Part $episodeNum' of an ongoing story series! Episode Title: '$episodeTitle'. Story Summary: '$storySummary'. You MUST include 'Part $episodeNum' in the YouTube Short title!"
        } else ""

        val prompt = """
            You are an expert YouTube Shorts Viral SEO Manager.
            Video file name: "$fileName"
            Topic / Instruction: "$userInstruction"
            $episodeContext
            
            Generate a catchy viral Title/Caption (max 85 chars, include Part number if applicable and #shorts #viral) and a full SEO Description with hashtags.
            Respond strictly in valid JSON:
            {
              "title": "Viral Title | Part $episodeNum #shorts #viral",
              "description": "Full story description with #hashtags"
            }
        """.trimIndent()

        return try {
            val rawJson = callGeminiApi(apiKey, model, prompt)
            val obj = extractCleanJsonObject(rawJson)
            VideoMetadata(
                title = obj.optString("title", "Tech Story Part ${episodeNum.coerceAtLeast(1)} #shorts #viral").take(95),
                description = obj.optString("description", "$storySummary\n\n#shorts #viral #trending")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            val partTag = if (episodeNum > 0) "Part $episodeNum " else ""
            VideoMetadata(
                title = "${episodeTitle.ifBlank { "Cybernetic Tech Story" }} \vert{} $partTag#shorts #viral".take(95),
                description = "$storySummary\n\n#shorts #trending #viral"
            )
        }
    }

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
        val escalationBanner = if (useHighModel) {
            """
            🚨 HIGH-REASONING ESCALATION MODE ACTIVE:
            The previous action FAILED verification (the expected next page/player did NOT open)!
            Inspect FRAME 2 (Current Screenshot, 540x960 coordinate space) and TRAINED SKILL BLUEPRINT carefully.
            If `CLICK_EL` on a badge number did not work, pick the exact inner button badge OR return `action="CLICK_XY"` with `tapX` (0..540) and `tapY` (0..960) to physically touch the element!
            """.trimIndent()
        } else ""

        val prompt = """
            You are Nax Bot's Self-Healing Vision Web Agent with Trained Blueprint Memory & Step Verification.
            OVERALL GOAL: $goal
            CURRENT URL: $pageUrl$escalationBanner
            
            TRAINED SKILL JSON BLUEPRINT (FOLLOW THIS STEP-BY-STEP MAP):
            ${trainedBlueprint.ifBlank { "Standard web navigation" }}
            
            LONG-TERM SQLITE BRAIN LESSONS (NEVER REPEAT PAST MISTAKES!):
            $learnedLessons
            
            USER'S PERSONAL MEMORY VAULT:
            $memoryVault
            
            STEP VERIFICATION STATUS SINCE LAST ACTION:
            $screenChangedStatus
            
            RECENT ACTIONS TAKEN:
            ${history.takeLast(10).joinToString("\n")}
            
            CURRENT WEBPAGE INTERACTIVE ELEMENTS (WITH BADGES `[0]`, `[1]`, `[2]`):
            $pageTextAndElements
            
            CRITICAL RULES:
            1. Check `STEP VERIFICATION STATUS` and compare FRAME 1 and FRAME 2.
            2. NEVER generate more than 1 video per run! Once prompt is typed and `arrow_forward` is clicked, NEVER type again!
            3. On Google Flow landing page, click "+ New project" badge using "CLICK_EL".
            4. Ensure mode pill is set to Video mode (8s/10s • x1), NOT Nano Banana Image mode.
            5. While rendering percentage ("1%", "25%", "80%") is visible, return action="WAIT".
            6. As soon as rendering reaches 100% and the completed video card / Play icon appears, return action="DOWNLOAD_VIDEO".
            
            Respond strictly in valid JSON:
            {
              "last_action_observation": "Did the previous action open the expected screen?",
              "thought_process": "Step-by-step reasoning",
              "expected_outcome": "What should visibly change after this action",
              "learned_rule": "",
              "action": "CLICK_EL",
              "elementIndex": 4,
              "tapX": 270,
              "tapY": 480,
              "textToType": "",
              "url": "",
              "reason": "Short status message for Nax Bot notch"
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
            val thought = obj.optString("thought_process", "")
            WebAgentDecision(
                action = obj.optString("action", "WAIT").uppercase(),
                elementIndex = obj.optInt("elementIndex", -1),
                tapX = obj.optDouble("tapX", -1.0).toFloat(),
                tapY = obj.optDouble("tapY", -1.0).toFloat(),
                textToType = obj.optString("textToType", ""),
                url = obj.optString("url", ""),
                reason = obj.optString("reason", thought.take(50).ifBlank { "Analyzing..." }),
                expectedOutcome = obj.optString("expected_outcome", "Page should update"),
                thoughtProcess = thought,
                learnedRule = obj.optString("learned_rule", "")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            WebAgentDecision(action = "WAIT", reason = "Waiting for webpage...")
        }
    }

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
        val monitorRules = if (isContinuousMonitorMode) {
            """
            CONTINUOUS CHAT MONITORING MODE IS ACTIVE:
            - NEVER return "DONE"! Keep monitoring the chat continuously.
            - If reply text is already typed inside the bottom input box AND the Send button (`desc='Send'`) is visible, immediately return action="CLICK_NODE" on Send!
            """.trimIndent()
        } else ""

        val prompt = """
            You are Nax Bot's Self-Correcting Android Phone UI Agent.
            Phone screen resolution: EXACTLY ${screenWidth}x${screenHeight} pixels.
            OVERALL GOAL: $goal$monitorRules
            
            TRAINED SKILL BLUEPRINT:
            ${trainedBlueprint.ifBlank { "Standard phone navigation" }}
            
            LONG-TERM SQLITE BRAIN LESSONS:
            $learnedLessons
            
            USER'S SAVED MEMORY VAULT:
            $memoryVault
            
            STEP VERIFICATION STATUS SINCE LAST ACTION:
            $screenChangedStatus
            
            RECENT ACTIONS HISTORY:
            ${history.takeLast(10).joinToString("\n")}
            
            CURRENT SCREEN UI ELEMENTS:
            $screenSummary
            
            Return strictly valid JSON:
            {
              "last_action_observation": "Did the previous action work?",
              "thought_process": "Step-by-step reasoning",
              "expected_outcome": "What should happen next",
              "learned_rule": "",
              "action": "CLICK_NODE",
              "nodeIndex": 3,
              "x": 540,
              "y": 1200,
              "textToType": "",
              "appPackageOrSetting": "",
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
            val thought = obj.optString("thought_process", "")
            AgentDecision(
                action = obj.optString("action", "WAIT").uppercase(),
                nodeIndex = obj.optInt("nodeIndex", -1),
                x = obj.optDouble("x", 540.0).toFloat(),
                y = obj.optDouble("y", 1000.0).toFloat(),
                textToType = obj.optString("textToType", ""),
                appPackageOrSetting = obj.optString("appPackageOrSetting", ""),
                reason = obj.optString("reason", thought.take(50).ifBlank { "Proceeding" }),
                expectedOutcome = obj.optString("expected_outcome", "Screen should update"),
                thoughtProcess = thought,
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
        return "Fast: $fast | High: $high \vert{} Live Pool:${discoveredLiveModels.size}"
    }
}
