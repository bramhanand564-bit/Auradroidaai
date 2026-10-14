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

data class AgentDecision(
    val action: String,
    val nodeIndex: Int = -1,
    val x: Float = 0f,
    val y: Float = 0f,
    val textToType: String = "",
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
            // Flash मॉडल्स को प्राथमिकता दें
            discovered.sortedByDescending { it.contains("flash", true) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun callGeminiApi(apiKey: String, userModel: String, prompt: String): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw Exception("Gemini API Key missing!")
        val requested = userModel.trim().removePrefix("models/").ifEmpty { "gemini-flash-latest" }

        val candidateSet = linkedSetOf<String>()
        cachedWorkingModel?.let { candidateSet.add(it) }
        candidateSet.add(requested)
        candidateSet.add("gemini-flash-latest")
        candidateSet.add("gemini-3.1-flash-lite-preview")
        candidateSet.add("gemini-3-flash-preview")
        candidateSet.add("gemini-2.5-flash-lite")
        candidateSet.add("gemini-2.0-flash")

        var lastError = ""

        suspend fun tryModelList(models: Collection<String>): String? {
            for (modelName in models) {
                for (attempt in 1..2) {
                    try {
                        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=${apiKey.trim()}"
                        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                            requestMethod = "POST"
                            setRequestProperty("Content-Type", "application/json")
                            connectTimeout = 20000
                            readTimeout = 20000
                            doOutput = true
                        }

                        val payload = JSONObject().apply {
                            put("contents", JSONArray().put(
                                JSONObject().put("parts", JSONArray().put(
                                    JSONObject().put("text", prompt)
                                ))
                            ))
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
                                // 404 या अन्य मॉडल एरर पर तुरंत अगला मॉडल आज़माएं
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

        // 1. पहले डिफ़ॉल्ट और यूज़र के मॉडल आज़माएं
        tryModelList(candidateSet)?.let { return@withContext it }

        // 2. यदि 404/503 आए तो Google के ListModels से चालू मॉडल खोजें
        val liveModels = fetchAvailableFlashModels(apiKey)
        if (liveModels.isNotEmpty()) {
            tryModelList(liveModels)?.let { return@withContext it }
        }

        throw Exception("All models busy or unavailable ($lastError)")
    }

    suspend fun generateYouTubeMetadata(
        apiKey: String,
        model: String,
        fileName: String,
        userInstruction: String
    ): VideoMetadata {
        val prompt = """
            You are an expert YouTube Shorts & Video SEO Manager.
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

    suspend fun decideNextUiAction(
        apiKey: String,
        model: String,
        goal: String,
        screenSummary: String,
        history: List<String>
    ): AgentDecision {
        val prompt = """
            You are an Autonomous Android UI Accessibility Agent controlling an Android phone screen.
            OVERALL GOAL: $goal
            
            RECENT ACTIONS HISTORY:
            ${history.takeLast(6).joinToString("\n")}
            
            CURRENT SCREEN UI ELEMENTS (Index | Text | ContentDesc | ViewId | Editable | Clickable | BoundsCenterXY):
            $screenSummary
            
            Decide the single next action to complete the GOAL.
            Available actions:
            - "CLICK_NODE": Click a specific node by its index.
            - "CLICK_XY": Tap exact screen coordinates (x, y).
            - "TYPE": Type text into an editable field (specify nodeIndex and textToType).
            - "SCROLL": Scroll down to see more UI elements.
            - "WAIT": Wait 3 seconds if screen is loading.
            - "DONE": Goal is completely finished (e.g. Upload/Publish button was clicked).
            
            Return strictly valid JSON:
            {
              "action": "CLICK_NODE",
              "nodeIndex": 3,
              "x": 540,
              "y": 1200,
              "textToType": "",
              "reason": "Brief explanation"
            }
        """.trimIndent()

        return try {
            val rawJson = callGeminiApi(apiKey, model, prompt)
            val clean = rawJson.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val obj = JSONObject(clean)
            AgentDecision(
                action = obj.optString("action", "WAIT"),
                nodeIndex = obj.optInt("nodeIndex", -1),
                x = obj.optDouble("x", 540.0).toFloat(),
                y = obj.optDouble("y", 1000.0).toFloat(),
                textToType = obj.optString("textToType", ""),
                reason = obj.optString("reason", "Proceeding")
            )
        } catch (e: Exception) {
            AgentDecision(action = "WAIT", reason = "API retry wait: ${e.message}")
        }
    }
}
