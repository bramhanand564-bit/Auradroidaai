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
    val thoughtProcess: String = "",
    val learnedRule: String = ""
)

data class WebAgentDecision(
    val action: String,      // CLICK_EL, TYPE_EL, SCROLL, LOAD_URL, DOWNLOAD_VIDEO, CHAIN_YOUTUBE, WAIT, DONE
    val elementIndex: Int = -1,
    val textToType: String = "",
    val url: String = "",
    val reason: String = "",
    val thoughtProcess: String = "",
    val learnedRule: String = ""
)

object GeminiBrain {

    private var cachedWorkingModel: String? = null

    private fun fetchAvailableFlashModels(apiKey: String): List<String> {
        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models?key=${apiKey.trim()}")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
            }
            if (conn.responseCode !in 200..299) return emptyList()
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val modelsArr = JSONObject(body).optJSONArray("models") ?: return emptyList()

            val discovered = mutableListOf<String>()
            for (i in 0 until modelsArr.length()) {
                val item = modelsArr.getJSONObject(i)
                val rawName = item.optString("name", "").removePrefix("models/")
                val methods = item.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
                if (methods.contains("generateContent") && rawName.contains("gemini", true)) {
                    discovered.add(rawName)
                }
            }
            discovered.sortedByDescending { it.contains("flash", true) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Supports Multi-Frame Vision: Can send BOTH the Previous Frame (Before Action) and Current Frame (Now)
     * so Gemini sees screen transitions just like watching a video!
     */
    private suspend fun callGeminiApi(
        apiKey: String,
        userModel: String,
        prompt: String,
        currentImageJpeg: String? = null,
        previousImageJpeg: String? = null
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw Exception("Gemini API Key missing! Settings tab mein jaakar API Key daalein.")
        val requested = userModel.trim().removePrefix("models/").ifEmpty { "gemini-2.5-flash" }

        val candidateSet = linkedSetOf<String>()
        cachedWorkingModel?.let { candidateSet.add(it) }
        candidateSet.add(requested)
        candidateSet.add("gemini-2.5-flash")
        candidateSet.add("gemini-flash-latest")
        candidateSet.add("gemini-3.1-flash-lite-preview")
        candidateSet.add("gemini-2.5-flash-lite")
        candidateSet.add("gemini-2.0-flash")

        var lastError = ""

        suspend fun tryModels(models: Collection<String>): String? {
            for (modelName in models) {
                for (attempt in 1..2) {
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
                            put("generationConfig", JSONObject().apply {
                                put("temperature", 0.2)
                                put("responseMimeType", "application/json")
                            })
                        }

                        OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }

                        val code = conn.responseCode
                        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                        val responseText = stream?.bufferedReader()?.use { it.readText() } ?: ""

                        if (code in 200..299) {
                            val root = JSONObject(responseText)
                            val text = root.getJSONArray("candidates")
                                .getJSONObject(0)
                                .getJSONObject("content")
                                .getJSONArray("parts")
                                .getJSONObject(0)
                                .getString("text")
                            cachedWorkingModel = modelName
                            return text.trim()
                        } else {
                            lastError = "HTTP $code ($modelName)"
                            if (code == 503 || code == 429) {
                                delay(2000L * attempt)
                                continue
                            } else {
                                break
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lastError = e.message ?: "Network error"
                        delay(1000L)
                    }
                }
            }
            return null
        }

        tryModels(candidateSet)?.let { return@withContext it }

        val liveModels = fetchAvailableFlashModels(apiKey)
        if (liveModels.isNotEmpty()) {
            tryModels(liveModels)?.let { return@withContext it }
        }

        throw Exception("Gemini API Busy/Error ($lastError)")
    }

    /**
     * Generates a 100% Policy-Safe 8-second cinematic video prompt for Google Flow.
     */
    suspend fun generateDailyFlowPrompt(
        apiKey: String,
        model: String,
        userTopic: String
    ): String {
        val prompt = """
            You are an expert AI Video Prompt Director for Google Flow (Veo 8-second video generator).
            User's core topic/theme: "$userTopic"
            
            STRICT GOOGLE FLOW SAFETY RULES (CRITICAL):
            1. Google Flow strictly blocks any prompt containing words related to minors or children!
            2. NEVER use words like: "boy", "girl", "child", "kid", "young", "teen", "minor", "baby", "skin", "flesh", "blood", "weapon", "fight", or "violence".
            3. If the user's topic mentions "boy" or "girl", automatically replace it with safe sci-fi/cinematic terms like "futuristic cybernetic android explorer", "robotic sci-fi traveler in a glowing neon suit", or "sleek humanoid astronaut".
            4. Create ONE fresh, vivid, 100% family-safe cinematic 8-second video prompt in English (30 to 45 words) with smooth camera movement and glowing lighting.
            
            Respond strictly in valid JSON:
            {
              "videoPrompt": "Detailed 100% policy-safe 8-second cinematic prompt..."
            }
        """.trimIndent()

        val rawPrompt = try {
            val raw = callGeminiApi(apiKey, model, prompt)
            val clean = raw.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            JSONObject(clean).optString("videoPrompt", userTopic).ifBlank { userTopic }
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
            4. If a website is mentioned (like Google Flow), provide its URL in targetUrl (e.g. "[https://labs.google/fx/tools/flow](https://labs.google/fx/tools/flow)").
            
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
            val clean = rawJson.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val obj = JSONObject(clean)
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
            val clean = rawJson.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val obj = JSONObject(clean)
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

    /**
     * Background Web Studio Agent Brain:
     * Uses Multi-Frame Vision (Previous + Current Screenshot), SQLite Learned Memory, and Chain-of-Thought Reasoning!
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
        screenChangedStatus: String = "",
        screenshotBase64: String? = null,
        previousScreenshotBase64: String? = null
    ): WebAgentDecision {
        val prompt = """
            You are a Self-Correcting, Vision-Powered Autonomous Web Browser Agent with Chain-of-Thought Reasoning.
            OVERALL GOAL: $goal
            CURRENT URL: $pageUrl
            
            LONG-TERM SQLITE BRAIN LESSONS (NEVER REPEAT PAST MISTAKES!):
            $learnedLessons
            
            USER'S PERSONAL MEMORY VAULT:
            $memoryVault
            
            SCREEN TRANSITION CHECK SINCE LAST STEP:
            $screenChangedStatus
            
            RECENT ACTIONS TAKEN:
            ${history.takeLast(10).joinToString("\n")}
            
            CURRENT WEBPAGE INTERACTIVE ELEMENTS (WITH YELLOW/RED BADGES `[0]`, `[1]`, `[2]`):
            $pageTextAndElements
            
            THINK-BEFORE-YOU-ACT & CREDIT-SAVING RULES:
            1. First compare FRAME 1 (Previous) and FRAME 2 (Current) in `last_action_observation`, and reason carefully in `thought_process`.
            2. NEVER generate more than 1 video! If RECENT ACTIONS TAKEN shows you already typed the prompt or clicked `arrow_forward`, DO NOT type or click `arrow_forward` again!
            3. If you are on the Google Flow landing page and see "+ New project", click its badge number using "CLICK_EL".
            4. Check the mode pill at the bottom: if it says "Nano Banana", "Image", "x2", or "x4", click it first to switch to "Video • 720p • 8s • x1".
            5. If the "What do you want to create?" box is empty and no video is rendering, use "TYPE_EL" ONCE on its badge number.
            6. If you see a percentage like "1%", "18%", "65%" on screen, the video is actively rendering! Return action="WAIT".
            7. As soon as rendering reaches 100% or a completed video thumbnail card / player appears on screen, return action="DOWNLOAD_VIDEO".
            8. If you discover a useful new rule from an error or screen change, write a 1-line rule in `learned_rule` (otherwise leave `learned_rule` empty "").
            
            Available actions:
            - "CLICK_EL", "TYPE_EL", "SCROLL", "LOAD_URL", "DOWNLOAD_VIDEO", "WAIT", "CHAIN_YOUTUBE", "DONE".
            
            Respond strictly in valid JSON:
            {
              "last_action_observation": "What changed on screen after the previous step",
              "thought_process": "Step-by-step reasoning for what to do right now",
              "learned_rule": "",
              "action": "TYPE_EL",
              "elementIndex": 4,
              "textToType": "Your prompt text here",
              "url": "",
              "reason": "Short clear status message for the user"
            }
        """.trimIndent()

        return try {
            val rawJson = callGeminiApi(apiKey, model, prompt, screenshotBase64, previousScreenshotBase64)
            val clean = rawJson.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val obj = JSONObject(clean)
            val thought = obj.optString("thought_process", "")
            WebAgentDecision(
                action = obj.optString("action", "WAIT").uppercase(),
                elementIndex = obj.optInt("elementIndex", -1),
                textToType = obj.optString("textToType", ""),
                url = obj.optString("url", ""),
                reason = obj.optString("reason", thought.take(60).ifBlank { "Analyzing webpage..." }),
                thoughtProcess = thought,
                learnedRule = obj.optString("learned_rule", "")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            WebAgentDecision(action = "WAIT", reason = "Waiting for webpage...")
        }
    }

    /**
     * Universal Phone Screen Agent Brain:
     * Uses Multi-Frame Vision (Previous + Current Screenshot), SQLite Learned Memory, and Chain-of-Thought Reasoning!
     */
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
        isContinuousMonitorMode: Boolean = false
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

        val prompt = """
            You are a Self-Correcting, Vision-First Universal Autonomous Android UI Agent with Chain-of-Thought Reasoning.
            Phone screen resolution: EXACTLY ${screenWidth}x${screenHeight} pixels.
            OVERALL GOAL: $goal$monitorRules
            
            LONG-TERM SQLITE BRAIN LESSONS (NEVER REPEAT PAST MISTAKES!):
            $learnedLessons
            
            USER'S SAVED MEMORY VAULT:
            $memoryVault
            
            SCREEN TRANSITION CHECK SINCE LAST STEP:
            $screenChangedStatus
            
            RECENT ACTIONS HISTORY:
            ${history.takeLast(10).joinToString("\n")}
            
            CURRENT SCREEN UI ELEMENTS (Index | Text | ContentDesc | ViewId | Editable | Clickable | ExactPixelCenterXY):
            $screenSummary
            
            CRITICAL RULES FOR ACCURATE TAPPING:
            1. First compare FRAME 1 (Previous) and FRAME 2 (Current) in `last_action_observation`, and reason carefully in `thought_process`.
            2. ALWAYS PREFER "CLICK_NODE" over "CLICK_XY" whenever the target button/icon exists in CURRENT SCREEN UI ELEMENTS!
               - In WhatsApp, the green Send button has `desc='Send'` or `id='send'`. ALWAYS use `action="CLICK_NODE"` with its exact `nodeIndex`!
               - Top-right 3-dot menu has `desc='More options'` or `desc='अधिक विकल्प'`. Use `action="CLICK_NODE"`.
            3. NEVER tap inside the top Android Status Bar (`y < 120` pixels).
            4. If your previous tap did not change the screen, do NOT repeat the exact same failing coordinate—pick the matching nodeIndex or try a different element, and record what you learned in `learned_rule`.
            
            Available actions:
            - "CLICK_NODE", "CLICK_XY", "TYPE", "SCROLL", "BACK", "OPEN_APP", "WAIT", "DONE".
            
            Return strictly valid JSON:
            {
              "last_action_observation": "Did the previous action work or did the screen stay the same?",
              "thought_process": "Step-by-step reasoning for the best next action",
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
            val rawJson = callGeminiApi(apiKey, model, prompt, screenshotBase64, previousScreenshotBase64)
            val clean = rawJson.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val obj = JSONObject(clean)
            val thought = obj.optString("thought_process", "")
            AgentDecision(
                action = obj.optString("action", "WAIT").uppercase(),
                nodeIndex = obj.optInt("nodeIndex", -1),
                x = obj.optDouble("x", 540.0).toFloat(),
                y = obj.optDouble("y", 1000.0).toFloat(),
                textToType = obj.optString("textToType", ""),
                appPackageOrSetting = obj.optString("appPackageOrSetting", ""),
                reason = obj.optString("reason", thought.take(60).ifBlank { "Proceeding" }),
                thoughtProcess = thought,
                learnedRule = obj.optString("learned_rule", "")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AgentDecision(action = "WAIT", reason = "Waiting for response...")
        }
    }
}
