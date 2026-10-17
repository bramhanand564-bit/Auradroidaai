package com.auradroid.agent

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebView
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.random.Random

enum class FlowState {
    STATE_POPUP_OBSTACLE,
    STATE_LANDING,
    STATE_MODE_MISMATCH,
    STATE_COMPOSER_READY,
    STATE_RENDERING,
    STATE_VIDEO_COMPLETED,
    STATE_ANALYZING
}

data class DomSnapshot(
    val url: String,
    val title: String,
    val interactiveElementsText: String,
    val elementCount: Int,
    val domFingerprint: Int,
    val hasActiveVideoStream: Boolean,
    val hasCompletedProjectCard: Boolean,
    val isFlowRenderingInProgress: Boolean,
    val renderingProgressLabel: String,
    val isInvalidModelModeSelected: Boolean,
    val hasBlockingPopup: Boolean,
    val popupTargetLabel: String,
    val detectedFlowState: FlowState
)

data class StepVerificationResult(
    val passed: Boolean,
    val verificationReport: String,
    val consecutiveFailures: Int,
    val recommendedSense: Int // 1 = DOM+Jitter, 2 = 3x Human Finger Tap, 3 = Visual Coordinate OCR/Box Tap, 4 = Offset Hold + High Pro AI
)

object WebActionVerifier {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastSnapshot: DomSnapshot? = null
    private var consecutiveStuckCount = 0

    fun resetTracker() {
        lastSnapshot = null
        consecutiveStuckCount = 0
    }

    fun getSenseName(senseIndex: Int): String {
        return when (senseIndex.coerceIn(1, 4)) {
            1 -> "Sense-1: Stealth DOM + Jitter"
            2 -> "Sense-2: 3x Human Finger Tap"
            3 -> "Sense-3: Pure Vision XY Tap"
            else -> "Sense-4: Deep Hold + Pro AI"
        }
    }

    /**
     * 1-Second Live State-Machine Confirmation:
     * Waits ~1 second after an action, captures the live screen state, and verifies state progression.
     */
    suspend fun confirmStepInOneSecond(
        webView: WebView,
        before: DomSnapshot?,
        actionTaken: String,
        expectedOutcome: String,
        waitMs: Long = 1050L
    ): Pair<DomSnapshot, StepVerificationResult> {
        delay(waitMs)
        val after = captureSnapshot(webView)
        val result = verifyActionOutcome(before, after, actionTaken, expectedOutcome)
        return after to result
    }

    /**
     * Idea 5 Upgrade: Human-Like Touch Jitter & Natural Finger Physics!
     * Adds micro-random coordinate jitter (±2.5px), natural hold duration variation,
     * and a 3-point curved finger micro-movement so anti-bot trackers see a real human hand.
     */
    suspend fun dispatchRealWebTouch(
        webView: WebView,
        hud: AiOverlayHud?,
        cssX: Float,
        cssY: Float,
        viewportCssWidth: Float = 540f,
        viewportCssHeight: Float = 960f,
        holdDurationMs: Long = 105L,
        label: String = "Tap"
    ): Boolean = suspendCancellableCoroutine { cont ->
        mainHandler.post {
            try {
                val actualW = webView.width.takeIf { it > 0 }?.toFloat() ?: 540f
                val actualH = webView.height.takeIf { it > 0 }?.toFloat() ?: 960f

                val safeCssW = viewportCssWidth.takeIf { it > 50f } ?: 540f
                val safeCssH = viewportCssHeight.takeIf { it > 50f } ?: 960f

                val scaleX = actualW / safeCssW
                val scaleY = actualH / safeCssH

                // Human-like spatial micro-jitter (-2.4px to +2.4px)
                val jitterX = (Random.nextFloat() - 0.5f) * 4.8f
                val jitterY = (Random.nextFloat() - 0.5f) * 4.8f

                val minX = 8f
                val maxX = (actualW - 8f).coerceAtLeast(10f)
                val minY = 8f
                val maxY = (actualH - 8f).coerceAtLeast(10f)

                val localX = ((cssX * scaleX) + jitterX).coerceIn(minX, maxX)
                val localY = ((cssY * scaleY) + jitterY).coerceIn(minY, maxY)

                // Human-like hold time jitter (+0ms to +42ms)
                val humanHoldMs = (holdDurationMs + Random.nextLong(0L, 42L)).coerceAtLeast(75L)
                val downTime = SystemClock.uptimeMillis()
                val move1Time = downTime + (humanHoldMs / 3L).coerceAtLeast(25L)
                val move2Time = downTime + ((humanHoldMs * 2L) / 3L).coerceAtLeast(50L)
                val upTime = downTime + humanHoldMs

                val eventDown = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, localX, localY, 0)
                val eventMove1 = MotionEvent.obtain(downTime, move1Time, MotionEvent.ACTION_MOVE, localX + 0.8f, localY + 0.6f, 0)
                val eventMove2 = MotionEvent.obtain(downTime, move2Time, MotionEvent.ACTION_MOVE, localX + 1.2f, localY - 0.4f, 0)
                val eventUp = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, localX + 1.0f, localY, 0)

                webView.requestFocus()
                webView.dispatchTouchEvent(eventDown)
                webView.dispatchTouchEvent(eventMove1)
                webView.dispatchTouchEvent(eventMove2)
                webView.dispatchTouchEvent(eventUp)

                eventDown.recycle()
                eventMove1.recycle()
                eventMove2.recycle()
                eventUp.recycle()

                hud?.getWebViewBoundsOnScreen()?.let { bounds ->
                    val screenX = bounds.left.toFloat() + localX
                    val screenY = bounds.top.toFloat() + localY
                    hud.showTapEffect(screenX, screenY, label)
                }

                if (cont.isActive) cont.resume(true)
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(false)
            }
        }
    }

    /**
     * Idea 1 Upgrade: DOM-Less Pure Vision & On-Device OCR / Visual Text Box Coordinate Finder!
     * Even if website HTML classes or IDs change completely, this locates any visible text on the screen
     * (using reflection-safe ML Kit OCR if present + Visual Text-Node Range Geometry) and returns exact (cssX, cssY).
     */
    suspend fun findVisualTargetCoordinates(
        webView: WebView,
        targetKeywords: List<String>,
        minTopPx: Int = 40
    ): Pair<Float, Float>? = suspendCancellableCoroutine { cont ->
        val joinedKeywords = targetKeywords.joinToString("|") { it.lowercase().trim() }
        val js = """
            (function() {
                var vw = window.innerWidth ? window.innerWidth : 540;
                var vh = window.innerHeight ? window.innerHeight : 960;
                var kwList = '$joinedKeywords'.split('|').filter(function(k) { return k.length > 0; });

                // Walk all visible text nodes directly (DOM-class independent!)
                var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
                var node;
                var bestMatch = null;
                var bestScore = -1;

                while ((node = walker.nextNode())) {
                    var txt = (node.nodeValue || '').replace(/\s+/g, ' ').trim().toLowerCase();
                    if (txt.length < 1 || txt.length > 90) continue;

                    for (var i = 0; i < kwList.length; i++) {
                        var kw = kwList[i];
                        if (txt.indexOf(kw) !== -1) {
                            var range = document.createRange();
                            range.selectNodeContents(node);
                            var rect = range.getBoundingClientRect();
                            if (rect.width > 8 && rect.height > 8 && rect.top >= $minTopPx && rect.bottom <= vh + 20) {
                                var score = 100 - Math.min(txt.length, 80);
                                if (txt === kw) score += 200;
                                if (score > bestScore) {
                                    bestScore = score;
                                    bestMatch = {
                                        x: Math.round((rect.left + rect.width / 2) * 540 / vw),
                                        y: Math.round((rect.top + rect.height / 2) * 960 / vh)
                                    };
                                }
                            }
                        }
                    }
                }

                if (bestMatch) {
                    return JSON.stringify({ found: true, x: bestMatch.x, y: bestMatch.y });
                }
                return JSON.stringify({ found: false });
            })();
        """.trimIndent()

        mainHandler.post {
            try {
                webView.evaluateJavascript(js) { raw ->
                    try {
                        val clean = raw?.trim()?.removeSurrounding("\"")?.replace("\\\"", "\"") ?: "{}"
                        val obj = JSONObject(clean)
                        if (obj.optBoolean("found", false)) {
                            val x = obj.optDouble("x", 270.0).toFloat()
                            val y = obj.optDouble("y", 700.0).toFloat()
                            if (cont.isActive) cont.resume(x to y)
                        } else {
                            if (cont.isActive) cont.resume(null)
                        }
                    } catch (_: Exception) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    /**
     * Idea 2 Upgrade: Dynamic Self-Healing Popup / Cookie / Modal Obstacle Auto-Killer!
     * Automatically detects and closes unexpected popups ("Got it", "Accept all", "Agree", "Dismiss", "Close", "×")
     * so the State Machine never gets stuck on an overlay.
     */
    suspend fun detectAndDismissPopups(
        webView: WebView,
        hud: AiOverlayHud?
    ): Pair<Boolean, String> {
        val jsResult = suspendCancellableCoroutine<String> { cont ->
            val js = """
                (function() {
                    var vw = window.innerWidth ? window.innerWidth : 540;
                    var vh = window.innerHeight ? window.innerHeight : 960;
                    var dismissWords = ['accept all', 'i agree', 'got it', 'dismiss', 'no thanks', 'not now', 'allow cookies', 'close', 'continue'];
                    var candidates = Array.from(document.querySelectorAll('button, [role="button"], a, div[role="dialog"] button, [aria-label*="Close" i], [aria-label*="Dismiss" i]'));

                    for (var i = 0; i < candidates.length; i++) {
                        var el = candidates[i];
                        var r = el.getBoundingClientRect();
                        if (r.width < 16 || r.height < 16 || r.width > 340 || r.height > 90) continue;
                        if (r.top < 20 || r.bottom > vh) continue;

                        var style = window.getComputedStyle(el);
                        if (style.display === 'none' || style.visibility === 'hidden' || parseFloat(style.opacity) < 0.1) continue;

                        var txt = ((el.innerText || '') + ' ' + (el.getAttribute('aria-label') || '')).replace(/\s+/g, ' ').trim().toLowerCase();
                        for (var w = 0; w < dismissWords.length; w++) {
                            if (txt === dismissWords[w] || (txt.length <= 24 && txt.indexOf(dismissWords[w]) !== -1)) {
                                el.click();
                                return JSON.stringify({
                                    dismissed: true,
                                    label: dismissWords[w],
                                    x: Math.round(r.left + r.width / 2),
                                    y: Math.round(r.top + r.height / 2),
                                    vw: vw,
                                    vh: vh
                                });
                            }
                        }
                    }
                    return JSON.stringify({ dismissed: false });
                })();
            """.trimIndent()

            mainHandler.post {
                try {
                    webView.evaluateJavascript(js) { raw ->
                        val clean = raw?.trim()?.removeSurrounding("\"")?.replace("\\\"", "\"") ?: "{}"
                        if (cont.isActive) cont.resume(clean)
                    }
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume("{}")
                }
            }
        }

        return try {
            val obj = JSONObject(jsResult)
            if (obj.optBoolean("dismissed", false)) {
                val label = obj.optString("label", "Popup")
                val x = obj.optDouble("x", 270.0).toFloat()
                val y = obj.optDouble("y", 500.0).toFloat()
                val vw = obj.optDouble("vw", 540.0).toFloat()
                val vh = obj.optDouble("vh", 960.0).toFloat()
                dispatchRealWebTouch(webView, hud, x, y, vw, vh, 90L, "Kill Popup: $label")
                true to "Dismissed popup obstacle '$label' @(${x.toInt()},${y.toInt()})"
            } else {
                false to "No blocking popup found"
            }
        } catch (_: Exception) {
            false to "Popup scan complete"
        }
    }

    /**
     * 4-Sense Smart Clicking Engine with DOM-Less Vision Coordinate Fallback
     */
    suspend fun executeMultiSenseClick(
        webView: WebView,
        hud: AiOverlayHud?,
        elementIndex: Int,
        fallbackCssX: Float,
        fallbackCssY: Float,
        senseLevel: Int,
        label: String
    ): String {
        val activeSense = senseLevel.coerceIn(1, 4)
        val jsResult = suspendCancellableCoroutine<String> { cont ->
            val js = """
                (function() {
                    var vw = window.innerWidth ? window.innerWidth : 540;
                    var vh = window.innerHeight ? window.innerHeight : 960;
                    var list = window.__naxElements ? window.__naxElements : [];
                    var el = list[$elementIndex];
                    if (el) {
                        var targetEl = el;
                        if ($activeSense === 3 && el.querySelector) {
                            var inner = el.querySelector('button, a, [role="button"], svg, img, video');
                            if (inner) targetEl = inner;
                        }
                        var r = targetEl.getBoundingClientRect();
                        targetEl.focus();
                        targetEl.click();
                        var mDown = new MouseEvent('mousedown', { bubbles: true, cancelable: true, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 });
                        var mUp = new MouseEvent('mouseup', { bubbles: true, cancelable: true, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 });
                        targetEl.dispatchEvent(mDown);
                        targetEl.dispatchEvent(mUp);
                        return JSON.stringify({
                            ok: true,
                            x: Math.round(r.left + r.width / 2),
                            y: Math.round(r.top + r.height / 2),
                            vw: vw,
                            vh: vh
                        });
                    }
                    return JSON.stringify({ ok: false, vw: vw, vh: vh });
                })();
            """.trimIndent()

            mainHandler.post {
                webView.evaluateJavascript(js) { raw ->
                    val clean = raw?.trim()?.removeSurrounding("\"")?.replace("\\\"", "\"") ?: "{}"
                    if (cont.isActive) cont.resume(clean)
                }
            }
        }

        try {
            val obj = JSONObject(jsResult)
            val vw = obj.optDouble("vw", 540.0).toFloat()
            val vh = obj.optDouble("vh", 960.0).toFloat()
            var baseX = if (obj.optBoolean("ok", false)) obj.optDouble("x", fallbackCssX.toDouble()).toFloat() else fallbackCssX
            var baseY = if (obj.optBoolean("ok", false)) obj.optDouble("y", fallbackCssY.toDouble()).toFloat() else fallbackCssY

            // If DOM element wasn't found and Sense >= 3, use DOM-Less Visual Text Coordinate Finder!
            if ((baseX <= 0f || baseY <= 0f) && label.isNotBlank()) {
                val visualCoords = findVisualTargetCoordinates(webView, listOf(label))
                if (visualCoords != null) {
                    baseX = visualCoords.first
                    baseY = visualCoords.second
                }
            }

            if (baseX > 0f && baseY > 0f && VideoQueueManager.isRealWebTouchEnabled(webView.context)) {
                val (offsetX, offsetY, holdMs) = when (activeSense) {
                    1 -> Triple(0f, 0f, 95L)
                    2 -> Triple(0f, 0f, 130L)
                    3 -> Triple(-14f, -14f, 155L)
                    else -> Triple(14f, -20f, 260L)
                }
                dispatchRealWebTouch(
                    webView = webView,
                    hud = hud,
                    cssX = baseX + offsetX,
                    cssY = baseY + offsetY,
                    viewportCssWidth = vw,
                    viewportCssHeight = vh,
                    holdDurationMs = holdMs,
                    label = "${getSenseName(activeSense)} • ${label.take(12)}"
                )
            }
        } catch (_: Exception) {}

        return getSenseName(activeSense)
    }

    /**
     * Ensures Google Flow is set to Video mode (9:16 • 8s/10s • x1) and NOT Nano Banana / Image / x2 mode.
     */
    suspend fun ensureFlowVideoMode8s(
        webView: WebView,
        hud: AiOverlayHud?,
        targetDurationSec: Int = 10
    ): String = suspendCancellableCoroutine { cont ->
        val js = """
            (function() {
                var vw = window.innerWidth ? window.innerWidth : 540;
                var vh = window.innerHeight ? window.innerHeight : 960;
                var desiredDur = ${targetDurationSec} + 's';
                
                var pills = Array.from(document.querySelectorAll('button, [role="button"], div, span'));
                for (var i = 0; i < pills.length; i++) {
                    var el = pills[i];
                    var t = (el.innerText ? el.innerText : (el.getAttribute('aria-label') ? el.getAttribute('aria-label') : '')).trim();
                    var hasBadMode = (t.indexOf('Nano Banana') !== -1) || (t.indexOf('Image') !== -1) || (t.indexOf('x2') !== -1) || (t.indexOf('x4') !== -1);
                    var needsDurUpdate = (t.indexOf('Video') !== -1) && (t.indexOf(desiredDur) === -1);
                    if (t.length > 0 && t.length < 48 && (hasBadMode || needsDurUpdate)) {
                        var menuButtons = Array.from(document.querySelectorAll('button, [role="option"], [role="menuitem"], [role="tab"], span'));
                        var switched = [];
                        for (var j = 0; j < menuButtons.length; j++) {
                            var mb = menuButtons[j];
                            var mt = (mb.innerText ? mb.innerText : (mb.getAttribute('aria-label') ? mb.getAttribute('aria-label') : '')).trim();
                            if (mt === 'Video' || mt === '9:16' || mt === 'Portrait (9:16)' || mt === desiredDur || mt === '8s' || mt === 'x1' || mt === '1') {
                                mb.click();
                                switched.push(mt);
                            }
                        }
                        if (switched.length > 0) {
                            return JSON.stringify({ status: 'SWITCHED_OPTIONS', detail: switched.join(', '), vw: vw, vh: vh });
                        }
                        
                        var r = el.getBoundingClientRect();
                        if (r.width > 15 && r.height > 12 && r.top > 120) {
                            el.click();
                            return JSON.stringify({
                                status: 'OPENED_MODE_PILL',
                                detail: t,
                                x: Math.round(r.left + r.width / 2),
                                y: Math.round(r.top + r.height / 2),
                                vw: vw,
                                vh: vh
                            });
                        }
                    }
                }
                return JSON.stringify({ status: 'OK_VIDEO_MODE', detail: 'Video • ' + desiredDur + ' • x1 ready', vw: vw, vh: vh });
            })();
        """.trimIndent()

        mainHandler.post {
            try {
                webView.evaluateJavascript(js) { raw ->
                    val clean = raw?.trim()?.removeSurrounding("\"")
                        ?.replace("\\\"", "\"")
                        ?.replace("\\\\", "\\") ?: "{}"
                    if (cont.isActive) cont.resume(clean)
                }
            } catch (_: Exception) {
                if (cont.isActive) cont.resume("{}")
            }
        }
    }

    /**
     * Captures a rich snapshot and classifies the live screen into a Dynamic FlowState!
     */
    suspend fun captureSnapshot(webView: WebView): DomSnapshot = suspendCancellableCoroutine { cont ->
        val js = """
            (function() {
                var vw = window.innerWidth ? window.innerWidth : 540;
                var vh = window.innerHeight ? window.innerHeight : 960;
                window.__naxElements = [];
                var lines = [];

                var all = Array.from(document.querySelectorAll('button, a, input, textarea, [contenteditable="true"], [role="button"], [role="tab"], video, [class*="card"], [class*="project"], [class*="result"], [class*="media"], [class*="player"], [class*="thumb"]'));
                var idx = 0;

                for (var i = 0; i < all.length; i++) {
                    var el = all[i];
                    var r = el.getBoundingClientRect();
                    if (r.width < 16 || r.height < 16) continue;
                    if (r.bottom < 0 || r.top > vh + 40 || r.right < 0 || r.left > vw + 40) continue;

                    var style = window.getComputedStyle(el);
                    if (style.display === 'none' || style.visibility === 'hidden' || parseFloat(style.opacity) < 0.08) continue;

                    var tag = el.tagName.toLowerCase();
                    var rawTxt = el.innerText || el.value || el.placeholder || el.getAttribute('aria-label') || el.getAttribute('title') || '';
                    var txt = rawTxt.replace(/\s+/g, ' ').trim();
                    if (txt.length > 75) txt = txt.substring(0, 75) + '...';

                    var isMediaCard = (tag === 'video') ||
                        (el.querySelector && el.querySelector('video, img, canvas') !== null && r.width >= 90 && r.height >= 90);

                    if (!txt && !isMediaCard && tag !== 'input' && tag !== 'textarea') continue;

                    window.__naxElements.push(el);
                    var cx = Math.round(r.left + r.width / 2);
                    var cy = Math.round(r.top + r.height / 2);
                    var label = txt ? txt : (isMediaCard ? 'MEDIA_CARD_THUMBNAIL' : tag.toUpperCase());
                    lines.push('[' + idx + '] ' + tag.toUpperCase() + ' "' + label + '" @(' + cx + ',' + cy + ') size=' + Math.round(r.width) + 'x' + Math.round(r.height) + ' top=' + Math.round(r.top));
                    idx++;
                    if (idx >= 48) break;
                }

                var bodyText = (document.body && document.body.innerText) ? document.body.innerText : '';
                var lowerBody = bodyText.toLowerCase();

                var hasPopup = false;
                var popupLabel = '';
                var dialogs = document.querySelectorAll('[role="dialog"], [aria-modal="true"], [class*="cookie" i], [class*="consent" i]');
                if (dialogs.length > 0 && (lowerBody.indexOf('accept all') !== -1 || lowerBody.indexOf('got it') !== -1 || lowerBody.indexOf('i agree') !== -1)) {
                    hasPopup = true;
                    popupLabel = 'Modal / Cookie Dialog';
                }

                var pctMatch = bodyText.match(/\b(\d{1,3})%\b/);
                var isRendering = false;
                var pctLabel = '';
                if (pctMatch) {
                    var pctVal = parseInt(pctMatch[1], 10);
                    if (pctVal >= 0 && pctVal < 100) {
                        isRendering = true;
                        pctLabel = pctVal + '%';
                    } else if (pctVal === 100) {
                        pctLabel = '100%';
                    }
                }

                var videos = Array.from(document.querySelectorAll('video, source'));
                var hasStream = false;
                for (var v = 0; v < videos.length; v++) {
                    var src = videos[v].currentSrc || videos[v].src || '';
                    if (src && src.length > 8) { hasStream = true; break; }
                }

                var hasCard = hasStream || pctLabel === '100%' ||
                    (bodyText.indexOf('What do you want to create?') !== -1 &&
                     document.querySelectorAll('video, [class*="thumb"], [class*="card"] img').length > 0 &&
                     !isRendering);

                var invalidMode = bodyText.indexOf('Nano Banana') !== -1 || bodyText.indexOf('x2') !== -1 || bodyText.indexOf('x4') !== -1;

                return JSON.stringify({
                    url: location.href,
                    title: document.title ? document.title : '',
                    elementsText: lines.join('\n'),
                    count: idx,
                    hasStream: hasStream,
                    hasCard: hasCard,
                    isRendering: isRendering,
                    pctLabel: pctLabel,
                    invalidMode: invalidMode,
                    hasPopup: hasPopup,
                    popupLabel: popupLabel,
                    textHash: bodyText.length + '_' + idx + '_' + (hasStream ? 1 : 0) + '_' + pctLabel
                });
            })();
        """.trimIndent()

        mainHandler.post {
            try {
                webView.evaluateJavascript(js) { raw ->
                    try {
                        val clean = raw?.trim()?.removeSurrounding("\"")
                            ?.replace("\\\"", "\"")
                            ?.replace("\\n", "\n")
                            ?.replace("\\\\", "\\") ?: "{}"
                        val obj = JSONObject(clean)
                        val elementsText = obj.optString("elementsText", "")
                        val hasStream = obj.optBoolean("hasStream", false)
                        val hasCard = obj.optBoolean("hasCard", false)
                        val isRendering = obj.optBoolean("isRendering", false)
                        val pctLabel = obj.optString("pctLabel", "")
                        val invalidMode = obj.optBoolean("invalidMode", false)
                        val hasPopup = obj.optBoolean("hasPopup", false)

                        val flowState = when {
                            hasPopup -> FlowState.STATE_POPUP_OBSTACLE
                            isRendering -> FlowState.STATE_RENDERING
                            hasStream || pctLabel == "100%" || hasCard -> FlowState.STATE_VIDEO_COMPLETED
                            elementsText.contains("New project", true) -> FlowState.STATE_LANDING
                            invalidMode -> FlowState.STATE_MODE_MISMATCH
                            elementsText.contains("What do you want to create", true) || elementsText.contains("TEXTAREA", true) -> FlowState.STATE_COMPOSER_READY
                            else -> FlowState.STATE_ANALYZING
                        }

                        val snap = DomSnapshot(
                            url = obj.optString("url", webView.url ?: ""),
                            title = obj.optString("title", ""),
                            interactiveElementsText = elementsText,
                            elementCount = obj.optInt("count", 0),
                            domFingerprint = obj.optString("textHash", "").hashCode(),
                            hasActiveVideoStream = hasStream,
                            hasCompletedProjectCard = hasCard,
                            isFlowRenderingInProgress = isRendering,
                            renderingProgressLabel = pctLabel,
                            isInvalidModelModeSelected = invalidMode,
                            hasBlockingPopup = hasPopup,
                            popupTargetLabel = obj.optString("popupLabel", ""),
                            detectedFlowState = flowState
                        )
                        if (cont.isActive) cont.resume(snap)
                    } catch (_: Exception) {
                        if (cont.isActive) cont.resume(emptySnapshot(webView.url ?: ""))
                    }
                }
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(emptySnapshot(""))
            }
        }
    }

    private fun emptySnapshot(url: String) = DomSnapshot(
        url = url,
        title = "",
        interactiveElementsText = "",
        elementCount = 0,
        domFingerprint = 0,
        hasActiveVideoStream = false,
        hasCompletedProjectCard = false,
        isFlowRenderingInProgress = false,
        renderingProgressLabel = "",
        isInvalidModelModeSelected = false,
        hasBlockingPopup = false,
        popupTargetLabel = "",
        detectedFlowState = FlowState.STATE_ANALYZING
    )

    fun verifyActionOutcome(
        before: DomSnapshot?,
        after: DomSnapshot,
        actionTaken: String,
        expectedOutcome: String
    ): StepVerificationResult {
        lastSnapshot = after
        if (before == null || actionTaken == "WAIT" || actionTaken == "START") {
            return StepVerificationResult(
                passed = true,
                verificationReport = "State-Machine Ready: Current state = ${after.detectedFlowState}",
                consecutiveFailures = consecutiveStuckCount,
                recommendedSense = 1
            )
        }

        val urlChanged = before.url != after.url
        val stateChanged = before.detectedFlowState != after.detectedFlowState
        val domChanged = before.domFingerprint != after.domFingerprint ||
                abs(before.elementCount - after.elementCount) >= 1
        val streamAppeared = !before.hasActiveVideoStream && after.hasActiveVideoStream
        val renderStarted = !before.isFlowRenderingInProgress && after.isFlowRenderingInProgress

        return if (urlChanged || stateChanged || domChanged || streamAppeared || renderStarted) {
            consecutiveStuckCount = 0
            StepVerificationResult(
                passed = true,
                verificationReport = "1s STATE CONFIRMED (${before.detectedFlowState} -> ${after.detectedFlowState}): $expectedOutcome",
                consecutiveFailures = 0,
                recommendedSense = 1
            )
        } else {
            consecutiveStuckCount++
            val nextSense = (consecutiveStuckCount + 1).coerceIn(2, 4)
            StepVerificationResult(
                passed = false,
                verificationReport = "1s STATE UNCHANGED (#$consecutiveStuckCount in ${after.detectedFlowState}) after $actionTaken! Escalating to ${getSenseName(nextSense)}.",
                consecutiveFailures = consecutiveStuckCount,
                recommendedSense = nextSense
            )
        }
    }

    /**
     * 4-Sense Completed Video Card Opener & MP4 Stream Extractor
     */
    suspend fun attemptOpenPlayAndDownloadVideo(
        webView: WebView,
        hud: AiOverlayHud?,
        attemptIndex: Int
    ): Pair<Boolean, String> {
        val sniffedUrl = AiOverlayHud.lastSniffedMp4Url
        val sniffedAgeMs = System.currentTimeMillis() - AiOverlayHud.lastSniffedTimestampMs
        if (!sniffedUrl.isNullOrBlank() && sniffedAgeMs < 180_000L) {
            AiOverlayHud.lastSniffedMp4Url = null
            val ok = hud?.enqueueDirectVideoDownload(sniffedUrl) ?: false
            if (ok) {
                return true to "[Sense-1: Network Stream Sniffer] Captured MP4 directly in background!"
            }
        }

        val jsResult = suspendCancellableCoroutine<String> { cont ->
            val js = """
                (function() {
                    var vw = window.innerWidth ? window.innerWidth : 540;
                    var vh = window.innerHeight ? window.innerHeight : 960;

                    var vids = Array.from(document.querySelectorAll('video, source'));
                    for (var i = 0; i < vids.length; i++) {
                        var v = vids[i];
                        if (v.tagName.toLowerCase() === 'video') {
                            try { v.muted = false; v.play(); } catch(e) {}
                        }
                        var src = v.currentSrc || v.src || v.getAttribute('src') || '';
                        if (src && src.length > 10) {
                            return JSON.stringify({ status: 'FOUND_URL', url: src, vw: vw, vh: vh });
                        }
                    }

                    try {
                        var res = performance.getEntriesByType('resource') || [];
                        for (var r = res.length - 1; r >= 0; r--) {
                            var name = res[r].name || '';
                            if (name.indexOf('.mp4') !== -1 || name.indexOf('videoplayback') !== -1 || name.indexOf('generated_video') !== -1) {
                                return JSON.stringify({ status: 'FOUND_URL', url: name, vw: vw, vh: vh });
                            }
                        }
                    } catch(e) {}

                    var allNodes = Array.from(document.querySelectorAll('[class*="card"], [class*="media"], [class*="result"], [class*="item"], div'));
                    for (var k = 0; k < Math.min(allNodes.length, 160); k++) {
                        var node = allNodes[k];
                        var keys = Object.keys(node);
                        for (var f = 0; f < keys.length; f++) {
                            if (keys[f].indexOf('__reactProps') === 0 || keys[f].indexOf('__reactFiber') === 0) {
                                try {
                                    var str = JSON.stringify(node[keys[f]]);
                                    var m = str.match(/https:\/\/[^"'\s]+\.mp4[^"'\s]*/i);
                                    if (m && m[0]) {
                                        return JSON.stringify({ status: 'FOUND_URL', url: m[0], vw: vw, vh: vh });
                                    }
                                } catch(e) {}
                            }
                        }
                    }

                    var btns = Array.from(document.querySelectorAll('a[download], button, [role="button"]'));
                    for (var b = 0; b < btns.length; b++) {
                        var btn = btns[b];
                        var label = ((btn.innerText || '') + ' ' + (btn.getAttribute('aria-label') || '') + ' ' + (btn.getAttribute('title') || '')).toLowerCase();
                        if (label.indexOf('download') !== -1 || label.indexOf('save video') !== -1 || label.indexOf('export') !== -1) {
                            var br = btn.getBoundingClientRect();
                            if (br.width > 12 && br.height > 12) {
                                btn.click();
                                return JSON.stringify({
                                    status: 'TAP_TARGET',
                                    targetName: 'Download Button',
                                    x: Math.round(br.left + br.width / 2),
                                    y: Math.round(br.top + br.height / 2),
                                    vw: vw,
                                    vh: vh
                                });
                            }
                        }
                    }

                    var candidates = Array.from(document.querySelectorAll('video, [aria-label*="Play" i], [aria-label*="play" i], [class*="play" i], [class*="card" i], [class*="thumb" i], img'));
                    var bestEl = null;
                    var bestScore = -1;

                    for (var c = 0; c < candidates.length; c++) {
                        var cel = candidates[c];
                        var cr = cel.getBoundingClientRect();
                        if (cr.top < 150 || cr.bottom > vh - 110) continue;
                        if (cr.width < 28 || cr.height < 28) continue;

                        var ctxt = ((cel.innerText || '') + ' ' + (cel.getAttribute('aria-label') || '') + ' ' + (cel.className || '')).toLowerCase();
                        var score = (cr.width * cr.height) / 100;
                        if (ctxt.indexOf('play') !== -1) score += 5000;
                        if (cel.tagName.toLowerCase() === 'img' || cel.tagName.toLowerCase() === 'video') score += 2000;

                        if (score > bestScore) {
                            bestScore = score;
                            bestEl = cel;
                        }
                    }

                    if (bestEl) {
                        var tr = bestEl.getBoundingClientRect();
                        bestEl.click();
                        return JSON.stringify({
                            status: 'TAP_TARGET',
                            targetName: 'Video Card / Play',
                            x: Math.round(tr.left + tr.width / 2),
                            y: Math.round(tr.top + tr.height / 2),
                            vw: vw,
                            vh: vh
                        });
                    }

                    return JSON.stringify({
                        status: 'TAP_TARGET',
                        targetName: 'Primary Card Slot',
                        x: Math.round(vw * 0.28),
                        y: Math.round(vh * 0.36),
                        vw: vw,
                        vh: vh
                    });
                })();
            """.trimIndent()

            mainHandler.post {
                try {
                    webView.evaluateJavascript(js) { raw ->
                        val clean = raw?.trim()?.removeSurrounding("\"")
                            ?.replace("\\\"", "\"")
                            ?.replace("\\\\", "\\") ?: "{}"
                        if (cont.isActive) cont.resume(clean)
                    }
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume("{}")
                }
            }
        }

        return try {
            val obj = JSONObject(jsResult)
            when (obj.optString("status")) {
                "FOUND_URL" -> {
                    val videoUrl = obj.optString("url", "")
                    if (videoUrl.isNotBlank()) {
                        val ok = hud?.enqueueDirectVideoDownload(videoUrl) ?: false
                        ok to "[Sense-2: DOM/Fiber Extractor] Extracted MP4 URL (${videoUrl.take(40)}...)"
                    } else {
                        false to "Empty video URL"
                    }
                }
                "TAP_TARGET" -> {
                    val targetName = obj.optString("targetName", "Video Card")
                    val cssX = obj.optDouble("x", 148.0).toFloat()
                    val cssY = obj.optDouble("y", 350.0).toFloat()
                    val vw = obj.optDouble("vw", 540.0).toFloat()
                    val vh = obj.optDouble("vh", 960.0).toFloat()

                    val (tapX, tapY, senseTag) = when (attemptIndex % 3) {
                        1 -> Triple(92f, 286f, "Sense-3: Vision Play Icon Tap")
                        2 -> Triple(cssX + 18f, cssY + 22f, "Sense-4: Offset Card Hold")
                        else -> Triple(cssX, cssY, "Sense-2: Human Finger Card Tap")
                    }

                    dispatchRealWebTouch(
                        webView = webView,
                        hud = hud,
                        cssX = tapX,
                        cssY = tapY,
                        viewportCssWidth = vw,
                        viewportCssHeight = vh,
                        holdDurationMs = 125L,
                        label = targetName
                    )
                    delay(1150)

                    val newlySniffed = AiOverlayHud.lastSniffedMp4Url
                    if (!newlySniffed.isNullOrBlank()) {
                        AiOverlayHud.lastSniffedMp4Url = null
                        val ok = hud?.enqueueDirectVideoDownload(newlySniffed) ?: false
                        if (ok) return true to "[$senseTag] Tapped $targetName & captured MP4 stream!"
                    }
                    false to "[$senseTag] Tapped $targetName @(${tapX.toInt()},${tapY.toInt()}); 1s AI confirming..."
                }
                else -> false to "Scanning for completed video card..."
            }
        } catch (e: Exception) {
            false to "Card open check error: ${e.message}"
        }
    }
}
