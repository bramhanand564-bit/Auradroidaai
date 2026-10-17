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

data class AgentDecision(
    val action: String, // OPEN_APP, CLICK_NODE, CLICK_XY, TYPE, SCROLL, BACK, WAIT, DONE
    val nodeIndex: Int = -1,
    val x: Float = -1f,
    val y: Float = -1f,
    val textToType: String = "",
    val appPackageOrSetting: String = "",
    val reason: String = "",
    val learnedRule: String = ""
)

data class TaskCompletionVerification(
    val verified: Boolean,
    val evidence: String,
    val reason: String
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

    private val fallbackSeedModels = listOf(
        "gemini-3.8-flash",
        "gemini-3.7-flash",
        "gemini-3.5-flash",
        "gemini-3.1-flash",
        "gemini-3.0-flash",
        "gemini-3.8-pro",
        "gemini-3.7-pro",
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
                        put(JSONObject().put("text", "FRAME 1 (PREVIOUS SCREEN BEFORE LAST ACTION):"))
                        put(JSONObject().put("inline_data", JSONObject().apply {
                            put("mime_type", "image/jpeg")
                            put("data", previousImageJpeg)
                        }))
                    }
                    if (!currentImageJpeg.isNullOrBlank()) {
                        put(JSONObject().put("text", "FRAME 2 (CURRENT LIVE SCREEN RIGHT NOW - 1s AI STEP CONFIRMATION):"))
                        put(JSONObject().put("inline_data", JSONObject().apply {
                            put("mime_type", "image/jpeg")
                            put("data", currentImageJpeg)
                        }))
                    }
                    extraImagesBase64.forEachIndexed { idx, b64 ->
                        if (b64.isNotBlank()) {
                            put(JSONObject().put("text", "VIDEO FRAME VISION SEO SNAPSHOT #${idx + 1}:"))
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
                            permanentlyDeadModels.add(modelName)
                        }
                        429, 403 -> {
                            cooldownModelsUntilMs[modelName] = System.currentTimeMillis() + 120_000L
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
     * Episodic Story Prompt Generator (Part 1, 2, 3 + Side-Stories + Duration)
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
            "SERIAL" -> "SERIAL EPISODE (PART $episodeNumber): Advance the story forward. Never repeat previous scenes."
            "SIDE_STORY" -> "SIDE-STORY / SPIN-OFF REEL: Create a fun tech side-story in the same universe."
            else -> "FRESH STANDALONE REEL: Create a fresh standalone technical scene."
        }

        val twistInstruction = if (customUserTwist.isNotBlank()) "USER TWIST: \"$customUserTwist\"" else ""

        val prompt = """
            You are an elite AI Showrunner for Google Flow ($targetDurationSec-second cinematic video generator).
            SERIES THEME: "$seriesTheme"
            CHARACTER VISUAL LOCK: "$characterLock"
            TARGET DURATION: $targetDurationSec seconds
            $modeInstruction
            
            PREVIOUS EPISODES:
            $previousEpisodesContext
            
            $twistInstruction
            
            SAFETY RULES: Never use words like boy, girl, child, kid, minor. Always use adult futuristic cybernetic tech explorer.
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
            StoryEpisodePlan(
                episodeNumber = episodeNumber,
                storyMode = storyMode.uppercase(),
                episodeTitle = obj.optString("episodeTitle", "Part $episodeNumber"),
                plotSummary = obj.optString("plotSummary", "Episode $episodeNumber story."),
                videoPrompt = obj.optString("videoPrompt", seriesTheme),
                durationSec = targetDurationSec
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            StoryEpisodePlan(episodeNumber, storyMode, "Part $episodeNumber", "Summary", seriesTheme, targetDurationSec)
        }
    }

    /**
     * WM3-Style Visual Skill Trainer
     */
    suspend fun trainSkillFromScreenshots(
        apiKey: String,
        model: String,
        skillName: String,
        userInstruction: String,
        screenshotsBase64: List<String>
    ): String {
        val prompt = """
            You are AuraDroid OS Visual Skill Architect.
            Analyze ${screenshotsBase64.size} sequential screenshots for "$skillName" and create a JSON Blueprint with 1-second AI confirmation steps.
            Respond strictly in valid JSON:
            {
              "skill": "$skillName",
              "steps": [
                { "step": 1, "screen_identify": "...", "action": "CLICK_EL", "target": "...", "confirm_after_1s": "..." }
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

    /**
     * Smart Chat & Timer Parser (Extracts alarms, task counts and creates step-by-step plans)
     */
    suspend fun chatAndBuildPlan(
        apiKey: String,
        model: String,
        userMessage: String,
        chatHistory: String,
        memoryVault: String
    ): ChatPlanResponse {
        val prompt = """
            You are Nax Bot, a friendly Hindi/Hinglish speaking Universal Autonomous AI Assistant inside AuraDroid OS.
            USER MEMORY: $memoryVault
            CHAT HISTORY: ${chatHistory.takeLast(2500)}
            NEW MESSAGE: "$userMessage"
            
            Your job:
            1. Parse any timer instructions or schedule requests (e.g. "रात 11 बजे से सुबह 5 बजे तक हर 2 घंटे में...") and summarize it in parsedTimerSummary.
            2. Reply naturally in Hinglish as Nax Bot.
            3. Create a numbered Step-by-Step Plan.
            4. Choose suggestedMode: "WEB" for Google Flow/websites, "PHONE" for native apps.
            
            Respond strictly in valid JSON:
            {
              "replyText": "Hinglish reply from Nax Bot",
              "planGoal": "Core goal",
              "planSteps": "1. Step one...\n2. Step two...",
              "suggestedMode": "WEB",
              "targetUrl": "https://labs.google/fx/tools/flow",
              "parsedTimerSummary": "⏰ Timer Locked: Daily tasks scheduled as requested."
            }
        """.trimIndent()

        return try {
            val rawJson = callGeminiApi(apiKey, model, prompt)
            val obj = extractCleanJsonObject(rawJson)
            ChatPlanResponse(
                replyText = obj.optString("replyText", "Nax Bot ne plan taiyar kar liya hai!"),
                planGoal = obj.optString("planGoal", userMessage),
                planSteps = obj.optString("planSteps", "1. Execute task"),
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

    /**
     * Viral Video Frame Vision SEO Metadata Generator:
     * Inspects actual video frames extracted from the MP4 file using Gemini Vision
     * to craft a curiosity-driven viral YouTube title and description!
     */
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
            
            Craft a high-CTR, curiosity-inducing viral YouTube Shorts title (max 85 chars, include #shorts #viral, never mention 'Google Flow' or file names) and a rich SEO description.
            Respond strictly in valid JSON:
            {
              "title": "Viral Title | Part $episodeNum #shorts #viral",
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
                title = obj.optString("title", "Cybernetic Future #shorts #viral").take(95),
                description = obj.optString("description", "$storySummary\n\n#shorts #viral #trending")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            VideoMetadata(
                title = "${episodeTitle.ifBlank { "Cybernetic Secret" }} #shorts #viral".take(95),
                description = "$storySummary\n\n#shorts #viral"
            )
        }
    }

    /**
     * Second-pass completion check for native-phone tasks. A model's DONE decision is not
     * accepted until a fresh screen is independently checked for evidence. Irreversible
     * actions also require concrete visible outcome text, not the model's explanation alone.
     */
    suspend fun verifyPhoneTaskCompletion(
        apiKey: String,
        model: String,
        highModelName: String,
        goal: String,
        screenSummary: String,
        recentHistory: List<String>,
        screenshotBase64: String?
    ): TaskCompletionVerification {
        val prompt = """
            You are an independent task-completion verifier for Nax Bot. The phone agent proposed DONE.
            Decide ONLY whether the current real Android screen proves the user's complete goal is finished.
            USER GOAL: $goal
            LIVE ACCESSIBILITY SCREEN SUMMARY:
            ${screenSummary.take(9000)}
            RECENT ACTION HISTORY:
            ${recentHistory.takeLast(8).joinToString("\n").take(2500)}
            RULES:
            - Do not trust the previous agent's DONE decision or its explanation.
            - The attached screenshot, if present, and live screen summary are the only evidence.
            - A click being dispatched, a field containing text, or a changed screen alone does not prove the goal is done.
            - For upload/publish/post/send/email/delete/payment/purchase/order/book/follow/subscribe actions, require visible evidence of the actual outcome, not merely an enabled button or populated form.
            - If the outcome is ambiguous, still loading, hidden, or not visible, verified must be false.
            - Do not infer success from the goal itself. Quote a short, concrete piece of current-screen evidence.
            Return JSON only: {"verified":false,"evidence":"","reason":"why evidence is insufficient"}
        """.trimIndent()

        return try {
            val raw = callGeminiApi(
                apiKey = apiKey,
                userModel = model,
                prompt = prompt,
                currentImageJpeg = screenshotBase64,
                escalateToHighModel = true,
                highModelName = highModelName,
                enableGoogleSearch = false
            )
            val obj = extractCleanJsonObject(raw)
            val modelVerified = obj.optBoolean("verified", false)
            val evidence = obj.optString("evidence", "").trim()
            val reason = obj.optString("reason", "Completion evidence was not provided.").trim()
            val screen = screenSummary.lowercase().replace(Regex("""\s+"""), " ").trim()
            val normalizedEvidence = evidence.lowercase().replace(Regex("""\s+"""), " ").trim()
            val evidenceAppearsOnScreen = normalizedEvidence.length >= 8 &&
                screen.contains(normalizedEvidence)
            val goalText = goal.lowercase()
            val asksUpload = Regex("""\\b(upload|uploading)\\b""").containsMatchIn(goalText)
            val asksPublish = goalText.contains("publish") || Regex(
                """\\b(post (this|it|the|to|on)|share (this|it|the)|make public)\\b"""
            ).containsMatchIn(goalText)
            val asksSend = Regex("""\\b(send|sending)\\b""").containsMatchIn(goalText)
            val asksDelete = Regex("""\\b(delete|deleting|remove|removing|erase|erasing)\\b""").containsMatchIn(goalText)
            val asksTransaction = Regex(
                """\\b(pay|payment|purchase|buy this|place an order|order this|book this|reserve|subscribe|renew subscription)\\b"""
            ).containsMatchIn(goalText)
            val asksFollow = Regex("""\\b(unfollow|follow)\\s+(@[a-z0-9_.-]+|this account|the account|this user|the user)\\b""")
                .containsMatchIn(goalText) && !Regex("""\\bfollow (the )?(steps|instructions|guide|process)\\b""").containsMatchIn(goalText)
            val asksLike = Regex("""\\b(like|unlike) (this|the) (post|video|photo|comment|reel)\\b""")
                .containsMatchIn(goalText)
            val asksSubmit = Regex("""\\b(submit|submit this|submit the|apply for)\\b""").containsMatchIn(goalText)
            val asksSave = Regex("""\\b(save changes|save this file|save the file|save draft|save this|save the changes)\\b""")
                .containsMatchIn(goalText)
            val requiredOutcomeProofs = buildList {
                if (asksUpload) add(listOf("upload complete", "uploaded successfully", "video uploaded", "video published", "upload successful", "saved as draft"))
                if (asksPublish) add(listOf("published", "post published", "your post is live", "successfully posted", "post has been shared", "your post has been shared", "video is public", "video published", "story shared"))
                if (asksSend) add(listOf("message sent", "sent successfully", "email sent", "delivered", "message delivered", "message was sent", "your message has been sent"))
                if (asksDelete) add(listOf("deleted successfully", "item deleted", "removed successfully", "moved to trash", "moved to bin", "deleted", "removed"))
                if (asksTransaction) add(listOf("payment successful", "payment complete", "transaction successful", "order confirmed", "your order is confirmed", "booking confirmed", "reservation confirmed", "purchased successfully", "subscription confirmed"))
                if (asksFollow) add(listOf("followed successfully", "following", "request sent", "following you"))
                if (asksLike) add(listOf("liked", "added to likes", "you liked this"))
                if (asksSubmit) add(listOf("submitted successfully", "form submitted", "application submitted", "response recorded", "thank you for submitting"))
                if (asksSave) add(listOf("saved successfully", "changes saved", "draft saved", "file saved", "saved"))
            }
            val sensitiveEvidence = requiredOutcomeProofs.all { alternatives ->
                alternatives.any { phrase -> screen.contains(phrase) }
            }
            if (modelVerified && evidenceAppearsOnScreen && sensitiveEvidence) {
                TaskCompletionVerification(true, evidence.take(350), reason.take(500))
            } else {
                val explanation = when {
                    !sensitiveEvidence -> "Sensitive action has no concrete visible outcome confirmation."
                    !evidenceAppearsOnScreen -> "The verifier's quoted evidence is not present in the current accessibility screen text."
                    !modelVerified -> reason.ifBlank { "The independent verifier did not find enough evidence." }
                    else -> reason
                }
                TaskCompletionVerification(false, evidence.take(350), explanation.take(500))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TaskCompletionVerification(false, "", "Completion verification failed: ${e.message ?: "unknown error"}")
        }
    }

    /**
     * Phone accessibility agent decision maker. Kept separate from WebAgentDecision
     * because native Android nodes use nodeIndex/x/y and app/setting actions.
     */
    suspend fun decideNextUiAction(
        apiKey: String,
        model: String,
        goal: String,
        screenSummary: String,
        history: List<String>,
        memoryVault: String,
        learnedLessons: String = "",
        screenChangedStatus: String = "",
        screenshotBase64: String? = null,
        previousScreenshotBase64: String? = null,
        screenWidth: Int,
        screenHeight: Int,
        isContinuousMonitorMode: Boolean = false,
        useHighModel: Boolean = false,
        highModelName: String = "gemini-3.8-pro",
        useGoogleSearch: Boolean = false
    ): AgentDecision {
        val foregroundPackage = Regex("""Foreground app package:\s*(\S+)""")
            .find(screenSummary)?.groupValues?.getOrNull(1)
        val adapterGuidance = AppAutomationRouter.adapterGuidance(goal, foregroundPackage)
        val prompt = """
            You are Nax Bot, an Android PHONE UI navigation agent. You control the REAL foreground Android app through AccessibilityService, not an embedded browser.
            USER'S COMPLETE TASK (never lose or replace this goal): $goal
            REAL DEVICE SIZE: $screenWidth x $screenHeight pixels.
            IMPORTANT: The attached screenshot is resized to exactly 540x1200 for vision.
            For CLICK_XY, return x/y in that screenshot's 540x1200 image coordinate space (x=0..540, y=0..1200), NOT real-device pixels. The app converts those image coordinates to real device coordinates. Prefer CLICK_NODE whenever a matching visible accessibility node exists.
            CONTINUOUS MONITOR MODE: $isContinuousMonitorMode

            CURRENT REAL DEVICE SCREEN AND APP IDENTITY:
            $screenSummary

            APP-SPECIFIC ADAPTER RULES:
            $adapterGuidance

            REQUIRED NAVIGATION REASONING:
            1. Identify the foreground package and current page from the node list and screenshot.
            2. Compare the visible screen with the full goal and last 8 actions; choose the next small, evidence-based action.
            3. The floating Nax Bot overlay is not part of the target app. Use app nodes and screenshot labels to identify the real control.
            4. Use WAIT only when there is clear evidence of loading/buffering, a transient dialog, or the user explicitly asked for ongoing monitoring. A keyboard being open or an editable search field containing text is NOT a reason to WAIT.
            5. If a search query is already typed and the keyboard is open, submit it with the visible Search/Go key or matching accessibility node. Then inspect results before opening a result.
            6. Accessibility node indexes are internal selection IDs only; they are NOT drawn as numbered dots on the phone screen. Prefer CLICK_NODE and choose only a listed node whose label/context matches the current step.
            7. Never guess an index. If no matching target is in the live node list, refresh/re-read the current nodes. If a target was rejected as stale/missing in recent history, do NOT repeat that same index or target; choose a different current target or stop safely.
            8. For TYPE, select a currently listed node marked edit=true or otherwise supporting text input. Do not use a generic bottom-of-screen coordinate or a random editable field. If recent history contains TYPE_VISUAL_VERIFY_PENDING, inspect the current screenshot for the exact requested text first; do not type/paste it again unless the screenshot clearly proves it is absent and the same input is still selected.
            9. After every action, wait briefly, capture a fresh screenshot and accessibility-node snapshot, compare with the previous UI state, and verify the expected change before continuing.
            10. Never automatically press Send, Publish, Submit, Delete, Purchase or a similar side-effect button just because text is present. Only do so when the user's complete goal explicitly requests that side effect and the exact current control is confirmed.
            11. If the same action fails to change the screen, do not repeat it blindly. Use one evidence-based recovery; after repeated failure, report a blocker and stop.
            12. Never switch to the in-app WebView for a real-phone task or invent controls not visible on screen.
            11. APP VS WEBSITE ROUTING IS STRICT:
                - When the user asks to open/launch an app (especially Twitter/X or "Floks/Flox/Flocs Twitter") and does NOT explicitly ask for Chrome, a browser, a website/site, or a URL, use the native installed app. Do NOT type twitter.com into Chrome, tap a Chrome suggestion, or treat a domain-looking name as a command to launch Chrome.
                - If the task history says the native-app router opened and verified a target package, keep working in that app. Do not switch to Chrome or another similarly named app unless the user explicitly requested a browser/site.
                - For OPEN_APP, appPackageOrSetting must be an installed app label/package or supported setting name, NEVER a web URL. If the requested native app cannot be found, report the blocker rather than silently navigating elsewhere.
                - Choose actions from the current foreground app's package/window and live node labels. A click dispatch is not proof the intended app opened.

            RECENT ACTIONS (latest 8):
            ${history.takeLast(8).joinToString("\n")}

            LONG-TERM MEMORY:
            $memoryVault

            LEARNED LESSONS:
            $learnedLessons

            SCREEN CHANGE VERIFICATION:
            $screenChangedStatus

            Choose exactly one next action. Use CLICK_NODE when the intended node index is known;
            use CLICK_XY only when a coordinate tap is necessary. For CLICK_XY, use screenshot image coordinates
            in the 540x1200 image space. Do not guess coordinates when the target is not clearly visible.
            Use OPEN_APP for an app package or supported setting name.
            Use TYPE only when text should be entered into a listed editable node. Never append an automatic Send tap to TYPE.
            Use WAIT while monitoring for a reply or while an app is demonstrably loading. Use DONE only when the user's goal is actually complete.
            Leave learnedRule empty when the previous action failed or the screen change does not verify the expected outcome.
            Never claim text entry, a sent message, a generated video, or an upload completed without visible evidence.
            Return valid JSON only:
            {
              "action": "CLICK_NODE",
              "nodeIndex": 2,
              "x": -1,
              "y": -1,
              "textToType": "",
              "appPackageOrSetting": "",
              "reason": "Short explanation",
              "learnedRule": ""
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
            val allowedActions = setOf("OPEN_APP", "CLICK_NODE", "CLICK_XY", "TYPE", "SCROLL", "BACK", "WAIT", "DONE")
            val action = obj.optString("action", "WAIT").uppercase().let {
                if (it in allowedActions) it else "WAIT"
            }
            AgentDecision(
                action = action,
                nodeIndex = obj.optInt("nodeIndex", -1),
                x = obj.optDouble("x", -1.0).toFloat(),
                y = obj.optDouble("y", -1.0).toFloat(),
                textToType = obj.optString("textToType", ""),
                appPackageOrSetting = obj.optString("appPackageOrSetting", ""),
                reason = obj.optString("reason", "Waiting for the next safe action"),
                learnedRule = obj.optString("learnedRule", "")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep failures distinguishable from a legitimate WAIT so the phone agent can
            // retry/escalate briefly and recover safely instead of waiting forever.
            val safeDetail = (e.message ?: "")
                .replace(Regex("(?i)(key=)[^&\\s]+"), "$1[REDACTED]")
                .take(120)
            val detail = if (safeDetail.isBlank()) "" else ": $safeDetail"
            AgentDecision(
                action = "WAIT",
                reason = "AI_ERROR: ${e.javaClass.simpleName}$detail"
            )
        }
    }

    /**
     * 1-Second AI Step Confirmation Web Agent Decision Maker
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
        val adapterGuidance = AppAutomationRouter.adapterGuidance(goal, currentUrl = pageUrl)
        val prompt = """
            You are Nax Bot's 1-Second AI Step-Confirmed Web Agent.
            OVERALL GOAL: $goal
            CURRENT URL: $pageUrl

            APP-SPECIFIC ADAPTER RULES:
            $adapterGuidance
            
            TRAINED SKILL BLUEPRINT (GUIDE ONLY - VERIFY EVERY 1s):
            ${trainedBlueprint.ifBlank { "Standard navigation" }}
            
            SQLITE BRAIN LESSONS:
            $learnedLessons
            
            1s STEP VERIFICATION STATUS:
            $screenChangedStatus
            
            RECENT ACTIONS:
            ${history.takeLast(8).joinToString("\n")}
            
            CURRENT WEBPAGE ELEMENTS:
            $pageTextAndElements
            
            BACKGROUND WEB MODE (IMPORTANT):
            - There is no reliable phone screenshot in this mode. Use the live DOM target list, role, label, disabled/selected state, URL and page state only. Do not guess visual coordinates when a matching DOM target exists.
            - Choose the exact visible, enabled target whose label and role match the current goal. Never select a disabled target. If multiple targets have similar labels, inspect surrounding labels and choose the best contextual match.
            - If the previous verification says VERIFY FAILED, DONE REJECTED, or NOT CONFIRMED, the prior step is still pending. Do NOT advance to the next logical task step. Pick one recovery strategy for the SAME pending step, or WAIT to re-observe / report a blocker.
            - Prefer different recovery mechanisms, not repeated identical clicks. Do not repeat a possibly irreversible submit/upload/publish/send/delete/purchase/create action when its outcome is uncertain; inspect the new DOM for confirmation first.
            - Treat disabled state, selected tabs, focus, field contents, URL, visible result/alert text and loading/progress indicators as evidence. A click being dispatched is not proof that the action worked.
            RULES:
            1. Every action must be checked against fresh DOM state. If not verified, use the supplied failure report to recover the same step.
            2. Never type/submit a prompt twice.
            3. When rendering reaches 100% or a completed result is visibly confirmed, return action="DOWNLOAD_VIDEO".
            4. Return DONE only when the page contains direct, task-specific evidence that the requested goal is complete. Never infer success only because the expected button was clicked.
            
            Respond strictly in valid JSON:
            {
              "thought_process": "Reasoning",
              "action": "CLICK_EL",
              "elementIndex": 2,
              "tapX": 270,
              "tapY": 700,
              "textToType": "",
              "url": "",
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
            WebAgentDecision(
                action = obj.optString("action", "WAIT").uppercase(),
                elementIndex = obj.optInt("elementIndex", -1),
                tapX = obj.optDouble("tapX", -1.0).toFloat(),
                tapY = obj.optDouble("tapY", -1.0).toFloat(),
                textToType = obj.optString("textToType", ""),
                url = obj.optString("url", ""),
                reason = obj.optString("reason", "Working...")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            WebAgentDecision(action = "WAIT", reason = "Waiting...")
        }
    }

    fun getAutoModelStatusReport(): String {
        val fast = activeFastModel ?: "Auto (3.8/3.7/3.1)"
        val high = activeHighModel ?: "Auto-Pro (3.8/3.1)"
        return "Fast: $fast | High: $high | Live: ${discoveredLiveModels.size}"
    }
}
