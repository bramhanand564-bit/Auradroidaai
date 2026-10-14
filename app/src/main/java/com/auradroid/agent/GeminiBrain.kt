package com.auradroid.agent

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
    val reason: String = ""
)

data class WebAgentDecision(
    val action: String,      // CLICK_EL, TYPE_EL, SCROLL, LOAD_URL, DOWNLOAD_VIDEO, CHAIN_YOUTUBE, WAIT, DONE
    val elementIndex: Int = -1,
    val textToType: String = "",
    val url: String = "",
    val reason: String = ""
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

    private suspend fun callGeminiApi(
        apiKey: String,
        userModel: String,
        prompt: String,
        base64ImageJpeg: String? = null
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
                            if (!base64ImageJpeg.isNullOrBlank()) {
                                put(JSONObject().put("inline_data", JSONObject().apply {
                                    put("mime_type", "image/jpeg")
                                    put("data", base64ImageJpeg)
                                }))
                            }
                        }

                        val payload = JSONObject().apply {
                            put("contents", JSONArray().put(JSONObject().put("parts", partsArray)))
                            put("generationConfig", JSONObject().apply {
                                put("temperature", 0.25)
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
     * Generates a fresh, unique 8-second cinematic video prompt for Google Flow every day
     */
    suspend fun generateDailyFlowPrompt(
        apiKey: String,
        model: String,
        userTopic: String
    ): String {
        val prompt = """
            You are an expert AI Video Prompt Director for Google Flow (Veo 8-second video generator).
            User's core topic/theme: "$userTopic"
            
            Create ONE fresh, vivid, cinematic 8-second video generation prompt in English with clear camera movement, lighting, and action.
            Respond strictly in valid JSON:
            {
              "videoPrompt": "Detailed 8-second cinematic prompt..."
            }
        """.trimIndent()

        return try {
            val raw = callGeminiApi(apiKey, model, prompt)
            val clean = raw.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            JSONObject(clean).optString("videoPrompt", userTopic).ifBlank { userTopic }
        } catch (e: Exception) {
            userTopic
        }
    }

    /**
     * TAB 1: Interactive AI Chat & Universal Task Planner
     */
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

    /**
     * Viral Title & Description Generator for YouTube Shorts Uploads
     */
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
        } catch (e: Exception) {
            val baseName = fileName.substringBeforeLast(".").replace("_", " ").replace("-", " ")
            VideoMetadata(
                title = "$baseName #shorts #viral",
                description = "$baseName\n\n#shorts #trending #viral"
            )
        }
    }

    /**
     * Background Web Studio Agent Brain (Google Flow 8s Video Generator, Credit Saver, Web Forms, Any Site)
     */
    suspend fun decideNextWebAction(
        apiKey: String,
        model: String,
        goal: String,
        pageUrl: String,
        pageTextAndElements: String,
        history: List<String>,
        memoryVault: String,
        screenshotBase64: String? = null
    ): WebAgentDecision {
        val prompt = """
            You are an Autonomous Background Web Browser Agent running inside AuraDroid OS.
            OVERALL GOAL: $goal
            CURRENT URL: $pageUrl
            MEMORY VAULT: $memoryVault
            
            RECENT ACTIONS TAKEN:
            ${history.takeLast(10).joinToString("\n")}
            
            CURRENT WEBPAGE INTERACTIVE ELEMENTS & VISIBLE TEXT:
            $pageTextAndElements
            
            CRITICAL RULES FOR GOOGLE FLOW & VIDEO GENERATION WEBSITES:
            1. Do NOT click Generate more than once per video! Respect the user's 1000 credits.
            2. First, find the prompt textarea/input box, use "TYPE_EL" to enter the 8-second video prompt.
            3. Next, click the Generate / Create / Arrow Send button ONCE using "CLICK_EL".
            4. While the video is generating (15s to 120s), the page will show loading/progress or generating text. You MUST return action="WAIT" until the video element (<video>) or Download button appears!
            5. As soon as a <video> element or Download icon/button appears on the page, choose action="DOWNLOAD_VIDEO" (or click the Download button) to save the 8-second video!
            6. Once downloaded, if the goal includes uploading to YouTube, return "CHAIN_YOUTUBE", otherwise return "DONE".
            
            Available actions:
            - "CLICK_EL": Click interactive webpage element by its elementIndex.
            - "TYPE_EL": Type/fill text into input/textarea at elementIndex (provide textToType).
            - "SCROLL": Scroll down the webpage.
            - "LOAD_URL": Navigate directly to a specific URL (provide url).
            - "DOWNLOAD_VIDEO": Automatically extract and download the generated <video> or MP4 file from the page.
            - "WAIT": Wait 6 seconds (use while AI video is generating or page is loading).
            - "CHAIN_YOUTUBE": Video is downloaded, now launch YouTube Auto-Upload pipeline!
            - "DONE": Goal is 100% completed.
            
            Respond strictly in valid JSON:
            {
              "action": "CLICK_EL",
              "elementIndex": 2,
              "textToType": "",
              "url": "",
              "reason": "Short clear status message"
            }
        """.trimIndent()

        return try {
            val rawJson = callGeminiApi(apiKey, model, prompt, screenshotBase64)
            val clean = rawJson.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val obj = JSONObject(clean)
            WebAgentDecision(
                action = obj.optString("action", "WAIT").uppercase(),
                elementIndex = obj.optInt("elementIndex", -1),
                textToType = obj.optString("textToType", ""),
                url = obj.optString("url", ""),
                reason = obj.optString("reason", "Analyzing webpage...")
            )
        } catch (e: Exception) {
            WebAgentDecision(action = "WAIT", reason = "Waiting for webpage...")
        }
    }

    /**
     * Universal Phone Screen Agent Brain (With Continuous Chat Monitoring Support!)
     */
    suspend fun decideNextUiAction(
        apiKey: String,
        model: String,
        goal: String,
        screenSummary: String,
        history: List<String>,
        memoryVault: String = "",
        screenshotBase64: String? = null,
        screenWidth: Int = 1080,
        screenHeight: Int = 2408,
        isContinuousMonitorMode: Boolean = false
    ): AgentDecision {
        val monitorRules = if (isContinuousMonitorMode) {
            """
            CONTINUOUS CHAT MONITORING MODE IS ACTIVE:
            - NEVER return "DONE"! You must keep monitoring the chat continuously until the user stops you.
            - Look at the chat messages on screen (and in the screenshot):
              * Messages on the RIGHT side (centerX > ${screenWidth / 2}, green bubbles) were sent by YOU.
              * Messages on the LEFT side (centerX < ${screenWidth / 2}, dark/white bubbles) are INCOMING messages from the other person.
            - If the lowest/latest message in the chat is on the RIGHT side (your own message), DO NOT send another message! Return action="WAIT" with reason="Waiting for new incoming reply...".
            - As soon as a NEW incoming message appears at the bottom on the LEFT side (like 'Hello bro kaise ho'), use action="TYPE" to type a friendly contextual reply in the 'Message' input box, and then click the Send button!
            - After clicking Send, go back to action="WAIT" to wait for their next reply!
            """.trimIndent()
        } else ""

        val prompt = """
            You are a Universal Autonomous Android UI Agent controlling a phone with EXACT screen resolution ${screenWidth}x${screenHeight} pixels.
            OVERALL GOAL: $goal$monitorRules
            
            USER'S SAVED MEMORY VAULT:
            $memoryVault
            
            RECENT ACTIONS HISTORY:
            ${history.takeLast(10).joinToString("\n")}
            
            CURRENT SCREEN UI ELEMENTS (Index | Text | ContentDesc | ViewId | Editable | Clickable | ExactPixelCenterXY):
            $screenSummary
            
            CRITICAL RULES FOR ACCURATE TAPPING:
            1. ALWAYS PREFER "CLICK_NODE" over "CLICK_XY" whenever the target button/icon exists in CURRENT SCREEN UI ELEMENTS!
               - Top-right 3-dot menu has desc='More options' or desc='अधिक विकल्प'. Use "CLICK_NODE" with its nodeIndex.
            2. NEVER tap inside the top Android Status Bar (y < 120 pixels).
            3. If you use "CLICK_XY", output exact pixel coordinates for ${screenWidth}x${screenHeight}.
            
            Available actions:
            - "CLICK_NODE": Click a specific UI node by its nodeIndex.
            - "CLICK_XY": Tap exact pixel coordinates (x, y).
            - "TYPE": Type text into an editable field (specify nodeIndex and textToType).
            - "SCROLL": Scroll down.
            - "BACK": Press Android Back button.
            - "OPEN_APP": Open an Android Setting or App directly ("SETTINGS", "BATTERY", "WIFI", "DISPLAY", "STORAGE", "WHATSAPP", or package name).
            - "WAIT": Wait 4 seconds (use when waiting for a new chat reply or loading).
            - "DONE": Goal is completely finished (NEVER use if Continuous Monitoring Mode is active).
            
            Return strictly valid JSON:
            {
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
            val rawJson = callGeminiApi(apiKey, model, prompt, screenshotBase64)
            val clean = rawJson.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val obj = JSONObject(clean)
            AgentDecision(
                action = obj.optString("action", "WAIT").uppercase(),
                nodeIndex = obj.optInt("nodeIndex", -1),
                x = obj.optDouble("x", 540.0).toFloat(),
                y = obj.optDouble("y", 1000.0).toFloat(),
                textToType = obj.optString("textToType", ""),
                appPackageOrSetting = obj.optString("appPackageOrSetting", ""),
                reason = obj.optString("reason", "Proceeding")
            )
        } catch (e: Exception) {
            AgentDecision(action = "WAIT", reason = "Waiting for response...")
        }
    }
}
