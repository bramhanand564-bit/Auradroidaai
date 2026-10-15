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

    // Stores last known working Fast & High models so subsequent steps run with zero delay
    @Volatile
    private var activeFastModel: String? = null

    @Volatile
    private var activeHighModel: String? = null

    // Live discovered models from Google API
    @Volatile
    private var discoveredLiveModels: List<String> = emptyList()
    private var lastDiscoveryTimeMs: Long = 0L

    // Permanently dead/expired models (404 / 400) -> Never retry in this session
    private val permanentlyDeadModels = ConcurrentHashMap.newKeySet<String>()

    // Quota-exhausted / busy models (429 / 503) -> Cooldown timestamp until which we skip them
    private val cooldownModelsUntilMs = ConcurrentHashMap<String, Long>()

    // Built-in fallback list covering 2.0 -> 2.5 -> 3.x -> 4.x generations
    private val fallbackSeedModels = listOf(
        "gemini-3.8-flash",
        "gemini-3.7-flash",
        "gemini-3.5-flash",
        "gemini-3.1-flash-lite-preview",
        "gemini-3.1-flash",
        "gemini-3.0-flash",
        "gemini-2.5-flash",
        "gemini-flash-latest",
        "gemini-2.5-flash-lite",
        "gemini-3.8-pro",
        "gemini-3.7-pro",
        "gemini-3.5-pro",
        "gemini-3.1-pro",
        "gemini-2.5-pro",
        "gemini-pro-latest",
        "gemini-2.0-flash",
        "gemini-2.0-flash-lite"
    )

    /**
     * Extracts numerical version (e.g. "gemini-3.8-flash" -> 3.8, "gemini-2.5-pro" -> 2.5)
     * so newer generation models are automatically tested and prioritized!
     */
    private fun extractVersionScore(modelName: String): Double {
        val m = Regex("gemini-(\\d+(?:\\.\\d+)?)").find(modelName.lowercase())
        return m?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 2.5
    }

    /**
     * Queries Google's live `/v1beta/models` endpoint to discover every active Gemini model
     * available to the user's API key right now.
     */
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

                // Keep only text/multimodal generation Gemini models; skip TTS, embedding, imagen, veo, audio-only
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
        } catch (e: Exception) {
            discoveredLiveModels
        }
    }

    /**
     * Builds an ordered, self-healing queue of models to try right now:
     * - Skips any 404/expired models (`permanentlyDeadModels`)
     * - Skips any 429 quota-exhausted models currently on cooldown (`cooldownModelsUntilMs`)
     * - Automatically orders by Fast Free-Tier vs High-Reasoning Escalation mode
     */
    private fun buildSmartCandidateQueue(
        apiKey: String,
        escalateToHighModel: Boolean
    ): List<String> {
        val now = System.currentTimeMillis()
        val liveList = refreshLiveModelsIfNeeded(apiKey)
        val combinedPool = (liveList + fallbackSeedModels).distinct()

        // Filter out dead (404) models and currently rate-limited (429) models
        val healthyPool = combinedPool.filter { model ->
            if (model in permanentlyDeadModels) return@filter false
            val cooldownUntil = cooldownModelsUntilMs[model] ?: 0L
            now >= cooldownUntil
        }.ifEmpty {
            // If every single model was on cooldown, clear cooldowns and try fresh
            cooldownModelsUntilMs.clear()
            combinedPool.filter { it !in permanentlyDeadModels }
        }

        val sortedPool = if (escalateToHighModel) {
            // HIGH-BRAIN MODE (Stuck / Verification Failed):
            // Prioritize Pro, Thinking, and Highest-Version (4.x -> 3.8 -> 3.5 -> 2.5) Flash models
            healthyPool.sortedWith(
                compareByDescending<String> { name ->
                    val l = name.lowercase()
                    when {
                        l.contains("pro") && !l.contains("vision") -> 3
                        l.contains("thinking") -> 3
                        l.contains("flash") && !l.contains("lite") -> 2
                        else -> 1
                    }
                }.thenByDescending { extractVersionScore(it) }
            )
        } else {
            // NORMAL FAST FREE-TIER MODE:
            // Prioritize Flash & Flash-Lite models (Highest Free-Tier Quota + Fastest Speed), newest version first!
            healthyPool.sortedWith(
                compareByDescending<String> { name ->
                    val l = name.lowercase()
                    when {
                        l.contains("flash") && !l.contains("lite") && !l.contains("8b") -> 4
                        l.contains("flash-lite") || l.contains("lite") -> 3
                        l.contains("flash") -> 2
                        else -> 1
                    }
                }.thenByDescending { extractVersionScore(it) }
            )
        }

        val finalQueue = linkedSetOf<String>()
        // Put the currently proven working model at index 0 for instant response
        if (escalateToHighModel) {
            activeHighModel?.takeIf { it in healthyPool }?.let { finalQueue.add(it) }
            activeFastModel?.takeIf { it in healthyPool }?.let { finalQueue.add(it) }
        } else {
            activeFastModel?.takeIf { it in healthyPool }?.let { finalQueue.add(it) }
        }
        finalQueue.addAll(sortedPool)
        return finalQueue.toList()
    }

    /**
     * 100% Automatic Self-Healing Gemini API Caller:
     * Automatically rotates through all discovered & seed models (2.5 -> 3.x -> 4.x),
     * blacklists 404/expired models, cools down 429 quota models, and supports Multi-Frame Vision + Google Search!
     */
    private suspend fun callGeminiApi(
        apiKey: String,
        userModel: String,
        prompt: String,
        currentImageJpeg: String? = null,
        previousImageJpeg: String? = null,
        escalateToHighModel: Boolean = false,
        highModelName: String = "AUTO",
        enableGoogleSearch: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw Exception("Gemini API Key missing! Settings tab mein jaakar API Key daalein.")

        var candidateQueue = buildSmartCandidateQueue(apiKey, escalateToHighModel)
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
                    connectTimeout = 25000
                    readTimeout = 25000
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
                }

                val payload = JSONObject().apply {
                    put("contents", JSONArray().put(JSONObject().put("parts", partsArray)))
                    if (withSearchTool) {
                        put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
                        put("generationConfig", JSONObject().apply {
                            put("temperature", 0.2)
                        })
                    } else {
                        put("generationConfig", JSONObject().apply {
                            put("temperature", 0.2)
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
                        // Lock in this working model so future calls are super fast!
                        if (escalateToHighModel) {
                            activeHighModel = modelName
                        } else {
                            activeFastModel = modelName
                        }
                        return finalOut
                    }
                } else {
                    lastError = "HTTP $code ($modelName)"
                    when (code) {
                        404, 400 -> {
                            // Model expired, retired, or unsupported -> Blacklist permanently!
                            if (!withSearchTool) {
                                permanentlyDeadModels.add(modelName)
                                if (activeFastModel == modelName) activeFastModel = null
                                if (activeHighModel == modelName) activeHighModel = null
                            }
                        }
                        429, 403 -> {
                            // Free-tier quota exhausted or model not on free tier -> Cooldown for 2 minutes!
                            cooldownModelsUntilMs[modelName] = System.currentTimeMillis() + 120_000L
                            if (activeFastModel == modelName) activeFastModel = null
                            if (activeHighModel == modelName) activeHighModel = null
                        }
                        500, 502, 503, 504 -> {
                            // Server overloaded -> Short 25s cooldown and jump to next model immediately
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

        // Automatically test models one by one until a working Free-Tier model responds!
        for (modelName in candidateQueue) {
            if (enableGoogleSearch) {
                trySingleModel(modelName, withSearchTool = true)?.let { return@withContext it }
            }
            trySingleModel(modelName, withSearchTool = false)?.let { return@withContext it }
            delay(150) // Tiny pause before seamlessly switching to the next model
        }

        // Final emergency refresh from Google Server if all cached models failed
        val freshLive = refreshLiveModelsIfNeeded(apiKey, forceRefresh = true)
        for (modelName in freshLive) {
            trySingleModel(modelName, withSearchTool = false)?.let { return@withContext it }
        }

        throw Exception("All Free-Tier Gemini models are currently busy ($lastError). Retrying shortly...")
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
     * Generates a 100% Policy-Safe 8-second cinematic video prompt for Google Flow.
     * Automatically includes Hindi Voice & Dialogue when requested!
     */
    suspend fun generateDailyFlowPrompt(
        apiKey: String,
        model: String,
        userTopic: String
    ): String {
        val wantsHindiVoice = userTopic.contains("hindi", true) ||
                userTopic.contains("voice", true) ||
                userTopic.contains("dialog", true) ||
                userTopic.contains("bol", true)

        val hindiVoiceRule = if (wantsHindiVoice) {
            """
            5. HINDI VOICE & DIALOGUE REQUIREMENT:
               The user explicitly requested Hindi voice and dialogue!
               You MUST include a clear spoken Hindi line in quotes inside the prompt so Google Flow (Veo) generates audio and lip-sync, for example:
               `...The character looks at the camera and speaks clearly in Hindi with lip-sync: "भविष्य की इस दुनिया में आपका स्वागत है, अब समय बदल चुका है!" with cinematic ambient sound effects.`
            """.trimIndent()
        } else ""

        val prompt = """
            You are an expert AI Video Prompt Director for Google Flow (Veo 8-second video generator with audio).
            User's core topic/theme: "$userTopic"
            
            STRICT GOOGLE FLOW SAFETY RULES (CRITICAL):
            1. Google Flow strictly blocks any prompt containing words related to minors or children!
            2. NEVER use words like: "boy", "girl", "child", "kid", "young", "teen", "minor", "baby", "skin", "flesh", "blood", "weapon", "fight", or "violence".
            3. If the user's topic mentions "boy" or "girl", automatically replace it with safe sci-fi/cinematic terms like "futuristic cybernetic android explorer", "robotic sci-fi traveler in a glowing neon suit", or "adult futuristic astronaut".
            4. Create ONE fresh, vivid, 100% family-safe cinematic 8-second video prompt (35 to 60 words) with smooth camera movement and glowing lighting.
            $hindiVoiceRule
            
            Respond strictly in valid JSON:
            {
              "videoPrompt": "Detailed 100% policy-safe 8-second cinematic prompt..."
            }
        """.trimIndent()

        val rawPrompt = try {
            val raw = callGeminiApi(apiKey, model, prompt)
            extractCleanJsonObject(raw).optString("videoPrompt", userTopic).ifBlank { userTopic }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            userTopic
        }

        return rawPrompt
            .replace(Regex("\\b(young\\s+boy|little\\s+boy|boy|child|kid|minor|teenager|teen|girl)\\b", RegexOption.IGNORE_CASE), "futuristic android explorer")
            .replace(Regex("\\b(skin|bare)\\b", RegexOption.IGNORE_CASE), "armor")
    }

    suspend fun chatAndBuildPlan(
        apiKey: String,
        model: String,
        userMessage: String,
        chatHistory: String,
        memoryVault: String
    ): ChatPlanResponse {
        val prompt = """
            You are Aura Companion, a friendly, smart Hindi/Hinglish speaking Universal Autonomous AI Assistant.
            
            USER'S SAVED MEMORY VAULT:
            $memoryVault
            
            RECENT CHAT CONVERSATION:
            ${chatHistory.takeLast(2500)}
            
            NEW USER MESSAGE:
            "$userMessage"
            
            Your job:
            1. Talk naturally to the user in clear Hindi/Hinglish.
            2. Create a numbered Step-by-Step Execution Plan.
            3. Choose suggestedMode:
               - "WEB": If the task involves Google Flow, websites, filling web forms, checking daily free video credit limits, generating/downloading AI videos, or GitHub web tasks.
               - "PHONE": If the task involves controlling native Android apps (like WhatsApp live chat monitoring, Gallery, Settings, Instagram, YouTube App).
            4. If a website is mentioned (like Google Flow), provide its URL in targetUrl (e.g. "https://labs.google/fx/tools/flow").
            
            Respond strictly in valid JSON:
            {
              "replyText": "Friendly Hindi/Hinglish reply explaining how you understood the task",
              "planGoal": "Complete concise instruction of the overall goal for the execution engine",
              "planSteps": "1. Step one...\n2. Step two...\n3. Step three...",
              "suggestedMode": "PHONE",
              "targetUrl": ""
            }
        """.trimIndent()

        return try {
            val rawJson = callGeminiApi(apiKey, model, prompt)
            val obj = extractCleanJsonObject(rawJson)
            ChatPlanResponse(
                replyText = obj.optString("replyText", "Maine aapka kaam samajh liya hai aur plan taiyar kar diya hai."),
                planGoal = obj.optString("planGoal", userMessage),
                planSteps = obj.optString("planSteps", "1. Task analyze karein\n2. Action execute karein"),
                suggestedMode = obj.optString("suggestedMode", "PHONE").uppercase(),
                targetUrl = obj.optString("targetUrl", "")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ChatPlanResponse(
                replyText = "⚠️ AI Plan banane mein dikkat aayi: ${e.message}",
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
        userInstruction: String
    ): VideoMetadata {
        val prompt = """
            You are an expert Social Media & YouTube Shorts SEO Manager.
            Video file name: "$fileName"
            Topic / Instruction: "$userInstruction"
            
            Generate a catchy viral Title/Caption (max 85 chars, include 2 hashtags like #shorts #viral) and a full SEO Description with hashtags.
            Respond strictly in valid JSON:
            {
              "title": "Viral Title #shorts #viral",
              "description": "Full SEO description with #hashtags"
            }
        """.trimIndent()

        return try {
            val rawJson = callGeminiApi(apiKey, model, prompt)
            val obj = extractCleanJsonObject(rawJson)
            VideoMetadata(
                title = obj.optString("title", fileName.substringBeforeLast(".")).take(95),
                description = obj.optString("description", "#shorts #viral #trending")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val baseName = fileName.substringBeforeLast(".").replace("_", " ").replace("-", " ")
            VideoMetadata(
                title = "$baseName #shorts #viral",
                description = "$baseName\n\n#shorts #trending #viral"
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
        screenChangedStatus: String = "",
        screenshotBase64: String? = null,
        previousScreenshotBase64: String? = null,
        useHighModel: Boolean = false,
        highModelName: String = "AUTO",
        useGoogleSearch: Boolean = false
    ): WebAgentDecision {
        val escalationBanner = if (useHighModel) {
            """
            🚨 HIGH-REASONING ESCALATION MODE ACTIVE:
            The previous action FAILED verification (the expected next page/player did NOT open)!
            Carefully inspect FRAME 2 (Current Screenshot with 540x960 resolution).
            If standard `CLICK_EL` on a badge number did not work, you can either pick the exact badge number OR return `action="CLICK_XY"` with `tapX` (0..540) and `tapY` (0..960) to physically tap the element on the WebView!
            """.trimIndent()
        } else ""

        val prompt = """
            You are a Self-Correcting, Vision-Powered Autonomous Web Browser Agent with Step Verification & Chain-of-Thought Reasoning.
            OVERALL GOAL: $goal
            CURRENT URL: $pageUrl$escalationBanner
            
            LONG-TERM SQLITE BRAIN LESSONS (NEVER REPEAT PAST MISTAKES!):
            $learnedLessons
            
            USER'S PERSONAL MEMORY VAULT:
            $memoryVault
            
            STEP VERIFICATION STATUS SINCE LAST ACTION:
            $screenChangedStatus
            
            RECENT ACTIONS TAKEN:
            ${history.takeLast(10).joinToString("\n")}
            
            CURRENT WEBPAGE INTERACTIVE ELEMENTS (WITH YELLOW/RED BADGES `[0]`, `[1]`, `[2]`):
            $pageTextAndElements
            
            THINK-BEFORE-YOU-ACT, VERIFICATION & CREDIT-SAVING RULES:
            1. First check `STEP VERIFICATION STATUS` and compare FRAME 1 and FRAME 2 in `last_action_observation`. Did the previous click open the next page?
            2. NEVER generate more than 1 video! If RECENT ACTIONS TAKEN shows you already typed the prompt or clicked `arrow_forward`, DO NOT type or click `arrow_forward` again!
            3. If you are on the Google Flow landing page and see "+ New project", click its badge number using "CLICK_EL".
            4. Check the mode pill at the bottom: if it says "Nano Banana", "Image", "x2", or "x4", click it first to switch to "Video • 720p • 8s • x1".
            5. If the "What do you want to create?" box is empty and no video is rendering, use "TYPE_EL" ONCE on its badge number.
            6. If you see a percentage like "1%", "18%", "65%" on screen, the video is actively rendering! Return action="WAIT".
            7. As soon as rendering finishes and a completed video thumbnail card / player appears on screen, return action="DOWNLOAD_VIDEO".
            8. Always specify `expected_outcome` (what should visibly change on screen after this action, so the verifier can check it on the next step!).
            
            Available actions:
            - "CLICK_EL": Click element by badge index (`elementIndex`).
            - "CLICK_XY": Physically tap exact WebView coordinates (`tapX` in 0..540, `tapY` in 0..960) when JS click fails.
            - "TYPE_EL": Type prompt into input box (`elementIndex`, `textToType`).
            - "SCROLL", "LOAD_URL", "DOWNLOAD_VIDEO", "WAIT", "CHAIN_YOUTUBE", "DONE".
            
            Respond strictly in valid JSON:
            {
              "last_action_observation": "Did the previous action succeed and open the expected page?",
              "thought_process": "Step-by-step reasoning for what to do right now",
              "expected_outcome": "What should open or change on screen after this action",
              "learned_rule": "",
              "action": "TYPE_EL",
              "elementIndex": 4,
              "tapX": 270,
              "tapY": 480,
              "textToType": "Your prompt text here",
              "url": "",
              "reason": "Short clear status message for the user"
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
                reason = obj.optString("reason", thought.take(60).ifBlank { "Analyzing webpage..." }),
                expectedOutcome = obj.optString("expected_outcome", "Page should update"),
                thoughtProcess = thought,
                learnedRule = obj.optString("learned_rule", "")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
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
        screenChangedStatus: String = "",
        screenshotBase64: String? = null,
        previousScreenshotBase64: String? = null,
        screenWidth: Int = 1080,
        screenHeight: Int = 2408,
        isContinuousMonitorMode: Boolean = false,
        useHighModel: Boolean = false,
        highModelName: String = "AUTO",
        useGoogleSearch: Boolean = false
    ): AgentDecision {
        val monitorRules = if (isContinuousMonitorMode) {
            """
            CONTINUOUS CHAT MONITORING MODE IS ACTIVE:
            - NEVER return "DONE"! Keep monitoring the chat continuously until the user stops you.
            - CRITICAL CHECK: Look at the bottom message input box (`edit=true`).
              * If there is already typed reply text sitting inside the bottom input box AND the green 'Send' button (`desc='Send'`) is visible on screen, you MUST immediately return action="CLICK_NODE" on the 'Send' button's nodeIndex!
            - Look at the chat bubbles above the input bar:
              * Bubbles on the RIGHT side (`centerX > ${screenWidth / 2}`) are messages already sent by YOU.
              * Bubbles on the LEFT side (`centerX < ${screenWidth / 2}`) are INCOMING messages from the other person.
            - If the lowest chat bubble is on the LEFT side (incoming message), use action="TYPE" to type a friendly reply in the language they requested!
            - Only return action="WAIT" when the input box is empty (`text='Message'`) AND the lowest chat bubble is on the RIGHT side (your sent message).
            """.trimIndent()
        } else ""

        val escalationNote = if (useHighModel) {
            "🚨 HIGH-REASONING ESCALATION ACTIVE: The previous tap did NOT open the expected screen! Analyze the screenshot carefully and pick the exact nodeIndex or coordinates."
        } else ""

        val prompt = """
            You are a Self-Correcting, Vision-First Universal Autonomous Android UI Agent with Step Verification & Chain-of-Thought Reasoning.
            Phone screen resolution: EXACTLY ${screenWidth}x${screenHeight} pixels.
            OVERALL GOAL: $goal
            
            $escalationNote$monitorRules
            
            LONG-TERM SQLITE BRAIN LESSONS (NEVER REPEAT PAST MISTAKES!):
            $learnedLessons
            
            USER'S SAVED MEMORY VAULT:
            $memoryVault
            
            STEP VERIFICATION STATUS SINCE LAST ACTION:
            $screenChangedStatus
            
            RECENT ACTIONS HISTORY:
            ${history.takeLast(10).joinToString("\n")}
            
            CURRENT SCREEN UI ELEMENTS (Index | Text | ContentDesc | ViewId | Editable | Clickable | ExactPixelCenterXY):
            $screenSummary
            
            CRITICAL RULES FOR ACCURATE TAPPING:
            1. First compare FRAME 1 (Previous) and FRAME 2 (Current) in `last_action_observation`, and reason carefully in `thought_process`.
            2. ALWAYS PREFER "CLICK_NODE" over "CLICK_XY" whenever the target button/icon exists in CURRENT SCREEN UI ELEMENTS!
            3. NEVER tap inside the top Android Status Bar (`y < 120` pixels).
            4. Always write what screen/change you expect next in `expected_outcome`.
            
            Available actions:
            - "CLICK_NODE", "CLICK_XY", "TYPE", "SCROLL", "BACK", "OPEN_APP", "WAIT", "DONE".
            
            Return strictly valid JSON:
            {
              "last_action_observation": "Did the previous action work or did the screen stay the same?",
              "thought_process": "Step-by-step reasoning for the best next action",
              "expected_outcome": "What should happen on screen after this action",
              "learned_rule": "",
              "action": "CLICK_NODE",
              "nodeIndex": 3,
              "x": 540,
              "y": 1200,
              "textToType": "",
              "appPackageOrSetting": "",
              "reason": "Brief explanation of this step"
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
                reason = obj.optString("reason", thought.take(60).ifBlank { "Proceeding" }),
                expectedOutcome = obj.optString("expected_outcome", "Screen should update"),
                thoughtProcess = thought,
                learnedRule = obj.optString("learned_rule", "")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AgentDecision(action = "WAIT", reason = "Waiting for response...")
        }
    }

    /**
     * Returns a short status of which Free-Tier models were auto-discovered & active
     */
    fun getAutoModelStatusReport(): String {
        val fast = activeFastModel ?: "Auto-Detecting..."
        val high = activeHighModel ?: "Auto-Detecting..."
        val deadCount = permanentlyDeadModels.size
        val liveCount = discoveredLiveModels.size
        return "Active Fast: $fast | High:$high | Live Pool: $liveCount | Skipped(404):$deadCount"
    }
}
