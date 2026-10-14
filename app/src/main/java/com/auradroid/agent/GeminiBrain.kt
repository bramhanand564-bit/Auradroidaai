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
    val action: String,      // CLICK_EL, TYPE_EL, SCROLL, LOAD_URL, DOWNLOAD_URL, CHAIN_YOUTUBE, WAIT, DONE
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
            You can perform ANY task on the user's phone or in the background web browser.
            
            USER'S SAVED MEMORY VAULT:
            $memoryVault
            
            RECENT CHAT CONVERSATION:
            ${chatHistory.takeLast(2500)}
            
            NEW USER MESSAGE:
            "$userMessage"
            
            Your job:
            1. Talk naturally to the user in clear Hindi/Hinglish. Clarify any doubts if needed, or confirm you are ready.
            2. Create a clear, numbered Step-by-Step Execution Plan so the user sees exactly how you will do the task.
            3. Choose suggestedMode:
               - "WEB": If the task involves websites, filling web forms, checking daily free video credit limits, generating/downloading AI videos, or GitHub web tasks.
               - "PHONE": If the task involves controlling native Android apps, fixing phone settings, or uploading local folder videos to the YouTube app.
            4. If a website is mentioned or needed, provide its full URL in targetUrl (or "https://www.google.com" if searching).
            
            Respond strictly in valid JSON:
            {
              "replyText": "Friendly Hindi/Hinglish reply explaining how you understood the task",
              "planGoal": "Complete concise instruction of the overall goal for the execution engine",
              "planSteps": "1. Step one...\n2. Step two...\n3. Step three...",
              "suggestedMode": "WEB",
              "targetUrl": "https://..."
            }
        """.trimIndent()

        return try {
            val rawJson = callGeminiApi(apiKey, model, prompt)
            val clean = rawJson.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val obj = JSONObject(clean)
            ChatPlanResponse(
                replyText = obj.optString("replyText", "Maine aapka kaam samajh liya hai aur plan taiyar kar diya hai."),
                planGoal = obj.optString("planGoal", userMessage),
                planSteps = obj.optString("planSteps", "1. Task analyze karein\n2. Action execute karein\n3. Report dein"),
                suggestedMode = obj.optString("suggestedMode", "WEB").uppercase(),
                targetUrl = obj.optString("targetUrl", "")
            )
        } catch (e: Exception) {
            ChatPlanResponse(
                replyText = "⚠️ AI Plan banane mein dikkat aayi: ${e.message}",
                planGoal = userMessage,
                planSteps = "1. Execute: $userMessage",
                suggestedMode = "WEB",
                targetUrl = ""
            )
        }
    }

    /**
     * Viral Title & Description Generator for Video Uploads
     */
    suspend fun generateYouTubeMetadata(
        apiKey: String,
        model: String,
        fileName: String,
        userInstruction: String
    ): VideoMetadata {
        val prompt = """
            You are an expert Social Media & YouTube SEO Manager.
            Video file name: "$fileName"
            User instruction: "$userInstruction"
            
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
     * Background Web Studio Agent Brain (Forms, Free Credits Check, Video Generation, GitHub, Any Website)
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
            
            USER'S PERSONAL MEMORY VAULT (Use this data for filling forms, emails, topics, GitHub urls):
            $memoryVault
            
            RECENT ACTIONS TAKEN:
            ${history.takeLast(8).joinToString("\n")}
            
            CURRENT WEBPAGE INTERACTIVE ELEMENTS & VISIBLE TEXT:
            $pageTextAndElements
            
            Rules:
            - If checking daily free video credit/limit, inspect the page text and proceed if credits exist.
            - If generating a story/film video or filling a form, type into the appropriate input/textarea and click the submit/generate button.
            - If the webpage says "Generating...", "Processing...", or "Loading...", choose action "WAIT" so we wait patiently until the task/video is ready!
            - If a video or file is ready to download, click the Download button or return "DOWNLOAD_URL".
            - If the goal also asks to upload the downloaded video to YouTube after downloading, return "CHAIN_YOUTUBE" once download is triggered.
            
            Available actions:
            - "CLICK_EL": Click interactive webpage element by its elementIndex.
            - "TYPE_EL": Type/fill text into input/textarea at elementIndex (provide textToType).
            - "SCROLL": Scroll down the webpage to see more fields/buttons.
            - "LOAD_URL": Navigate directly to a specific URL (provide url).
            - "DOWNLOAD_URL": Direct download of a video/file URL found on page (provide url).
            - "WAIT": Wait 5 seconds (use when AI video/code is generating or page is loading).
            - "CHAIN_YOUTUBE": Video is downloaded, now launch YouTube Auto-Upload pipeline!
            - "DONE": Goal is 100% completed.
            
            Respond strictly in valid JSON:
            {
              "action": "CLICK_EL",
              "elementIndex": 2,
              "textToType": "",
              "url": "",
              "reason": "Short clear status message for the user"
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
            WebAgentDecision(action = "WAIT", reason = "Retrying after network pause...")
        }
    }

    /**
     * Universal Phone Screen & Settings Agent Brain (Supports Real Screenshot Vision!)
     */
    suspend fun decideNextUiAction(
        apiKey: String,
        model: String,
        goal: String,
        screenSummary: String,
        history: List<String>,
        memoryVault: String = "",
        screenshotBase64: String? = null
    ): AgentDecision {
        val prompt = """
            You are a Universal Autonomous Android UI Agent controlling an Android phone.
            OVERALL GOAL: $goal
            
            USER'S SAVED MEMORY VAULT:
            $memoryVault
            
            RECENT ACTIONS HISTORY:
            ${history.takeLast(8).joinToString("\n")}
            
            CURRENT SCREEN UI ELEMENTS (Index | Text | ContentDesc | ViewId | Editable | Clickable | CenterXY):
            $screenSummary
            
            Available actions:
            - "CLICK_NODE": Click a specific UI node by its nodeIndex.
            - "CLICK_XY": Tap exact screen coordinates (x, y) (useful if you see an unlabeled icon in the screenshot).
            - "TYPE": Type text into an editable field (specify nodeIndex and textToType).
            - "SCROLL": Scroll down to see more options.
            - "BACK": Press Android Back button.
            - "OPEN_APP": Open an Android System Setting or App directly. Set appPackageOrSetting to one of:
                "SETTINGS", "BATTERY", "WIFI", "DISPLAY", "STORAGE", "SOUND", "BLUETOOTH", "APPS", or a package name like "com.whatsapp", "com.android.chrome", "com.google.android.youtube".
            - "WAIT": Wait 3 seconds if screen is loading.
            - "DONE": Goal is completely finished.
            
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
