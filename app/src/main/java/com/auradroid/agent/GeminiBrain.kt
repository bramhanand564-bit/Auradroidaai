package com.auradroid.agent

import kotlinx.coroutines.Dispatchers
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
    val action: String,      // CLICK_NODE, CLICK_XY, TYPE, SCROLL, BACK, WAIT, DONE
    val nodeIndex: Int = -1,
    val x: Float = 0f,
    val y: Float = 0f,
    val textToType: String = "",
    val reason: String = ""
)

object GeminiBrain {

    private suspend fun callGeminiApi(apiKey: String, model: String, prompt: String): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw Exception("Gemini API Key missing!")
        val cleanModel = model.trim().ifEmpty { "gemini-2.5-flash" }
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent?key=${apiKey.trim()}"

        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 25000
            readTimeout = 25000
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
        val responseStream = if (code in 200..299) conn.inputStream else conn.errorStream
        val responseText = responseStream?.bufferedReader()?.use { it.readText() } ?: ""

        if (code !in 200..299) {
            throw Exception("Gemini HTTP $code: $responseText")
        }

        val root = JSONObject(responseText)
        val text = root.getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")

        return@withContext text.trim()
    }

    suspend fun generateYouTubeMetadata(
        apiKey: String,
        model: String,
        fileName: String,
        userInstruction: String
    ): VideoMetadata {
        val prompt = """
            You are an expert YouTube SEO Growth Manager.
            Video file name: "$fileName"
            User instruction/context: "$userInstruction"
            
            Generate a catchy, high-CTR YouTube Title (max 90 chars) and a complete SEO Description including trending hashtags.
            Respond strictly in valid JSON format:
            {
              "title": "Your viral title here",
              "description": "Full SEO description with #hashtags here"
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
                description = "$baseName\n\nUploaded automatically by AuraDroid AI.\n#shorts #trending #viral"
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
            
            Decide the single next action to move closer to completing the GOAL.
            Available actions:
            - "CLICK_NODE": Click a specific node by its index.
            - "CLICK_XY": Tap exact screen coordinates (x, y).
            - "TYPE": Type text into an editable field (specify nodeIndex and textToType).
            - "SCROLL": Scroll down to see more UI elements.
            - "WAIT": Wait 3 seconds if screen is loading.
            - "DONE": Goal is completely finished (e.g. Upload/Post button was clicked and upload started).
            
            Return strictly valid JSON:
            {
              "action": "CLICK_NODE",
              "nodeIndex": 3,
              "x": 540,
              "y": 1200,
              "textToType": "",
              "reason": "Brief explanation of why this step is chosen"
            }
        """.trimIndent()

        val rawJson = callGeminiApi(apiKey, model, prompt)
        val clean = rawJson.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val obj = JSONObject(clean)

        return AgentDecision(
            action = obj.optString("action", "WAIT"),
            nodeIndex = obj.optInt("nodeIndex", -1),
            x = obj.optDouble("x", 540.0).toFloat(),
            y = obj.optDouble("y", 1000.0).toFloat(),
            textToType = obj.optString("textToType", ""),
            reason = obj.optString("reason", "Proceeding with next step")
        )
    }
}
