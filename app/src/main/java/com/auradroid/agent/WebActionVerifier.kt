package com.auradroid.agent

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
    val isInvalidModelModeSelected: Boolean
)

data class StepVerificationResult(
    val passed: Boolean,
    val verificationReport: String,
    val consecutiveFailures: Int,
    val recommendedSense: Int // 1 = DOM Click, 2 = 3x Finger Tap, 3 = Inner Icon Tap, 4 = Offset Tap + High Pro Model
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
            1 -> "Sense-1: DOM Click"
            2 -> "Sense-2: 3x Finger Tap"
            3 -> "Sense-3: Inner Icon Tap"
            else -> "Sense-4: Offset Tap + Pro AI"
        }
    }

    /**
     * 1-Second Live AI Step Confirmation:
     * Waits 1 second after any action, captures a fresh live snapshot, and confirms whether the screen
     * genuinely progressed before allowing the workflow to move to the next step!
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
     * Dispatches a hardware-accurate Android MotionEvent (DOWN -> MOVE -> UP) directly onto the WebView,
     * scaling CSS coordinates (540x960) to actual WebView pixel coordinates (3x screen scale).
     */
    suspend fun dispatchRealWebTouch(
        webView: WebView,
        hud: AiOverlayHud?,
        cssX: Float,
        cssY: Float,
        viewportCssWidth: Float = 540f,
        viewportCssHeight: Float = 960f,
        holdDurationMs: Long = 95L,
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

                val localX = (cssX * scaleX).coerceIn(8f, (actualW - 8f).coerceAtLeast(10f))
                val localY = (cssY * scaleY).coerceIn(8f, (actualH - 8f).coerceAtLeast(10f))

                val downTime = SystemClock.uptimeMillis()
                val moveTime = downTime + (holdDurationMs / 2L).coerceAtLeast(35L)
                val upTime = downTime + holdDurationMs.coerceAtLeast(80L)

                val eventDown = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, localX, localY, 0)
                val eventMove = MotionEvent.obtain(downTime, moveTime, MotionEvent.ACTION_MOVE, localX + 1f, localY + 1f, 0)
                val eventUp = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, localX, localY, 0)

                webView.requestFocus()
                webView.dispatchTouchEvent(eventDown)
                webView.dispatchTouchEvent(eventMove)
                webView.dispatchTouchEvent(eventUp)

                eventDown.recycle()
                eventMove.recycle()
                eventUp.recycle()

                hud?.getWebViewBoundsOnScreen()?.let { bounds ->
                    val screenX = bounds.left + localX
                    val screenY = bounds.top + localY
                    hud.showTapEffect(screenX, screenY, label)
                }

                if (cont.isActive) cont.resume(true)
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(false)
            }
        }
    }

    /**
     * 4-Sense Smart Clicking Engine:
     * Adapts its clicking technique automatically based on `senseLevel` (1..4) when a previous click didn't open the next state.
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
            val baseX = if (obj.optBoolean("ok", false)) obj.optDouble("x", fallbackCssX.toDouble()).toFloat() else fallbackCssX
            val baseY = if (obj.optBoolean("ok", false)) obj.optDouble("y", fallbackCssY.toDouble()).toFloat() else fallbackCssY

            if (baseX > 0f && baseY > 0f && VideoQueueManager.isRealWebTouchEnabled(webView.context)) {
                val (offsetX, offsetY, holdMs) = when (activeSense) {
                    1 -> Triple(0f, 0f, 85L)
                    2 -> Triple(0f, 0f, 120L)
                    3 -> Triple(-18f, -18f, 140L)
                    else -> Triple(16f, -24f, 260L)
                }
                dispatchRealWebTouch(
                    webView = webView,
                    hud = hud,
                    cssX = baseX + offsetX,
                    cssY = baseY + offsetY,
                    viewportCssWidth = vw,
                    viewportCssHeight = vh,
                    holdDurationMs = holdMs,
                    label = "${getSenseName(activeSense)} •${label.take(12)}"
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
     * Captures a live DOM snapshot, tags interactive elements with indices `[0]..[47]`,
     * and checks Google Flow rendering progress (0%..100%) and completed video cards.
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
                        val snap = DomSnapshot(
                            url = obj.optString("url", webView.url ?: ""),
                            title = obj.optString("title", ""),
                            interactiveElementsText = obj.optString("elementsText", ""),
                            elementCount = obj.optInt("count", 0),
                            domFingerprint = obj.optString("textHash", "").hashCode(),
                            hasActiveVideoStream = obj.optBoolean("hasStream", false),
                            hasCompletedProjectCard = obj.optBoolean("hasCard", false),
                            isFlowRenderingInProgress = obj.optBoolean("isRendering", false),
                            renderingProgressLabel = obj.optString("pctLabel", ""),
                            isInvalidModelModeSelected = obj.optBoolean("invalidMode", false)
                        )
                        if (cont.isActive) cont.resume(snap)
                    } catch (_: Exception) {
                        if (cont.isActive) {
                            cont.resume(emptySnapshot(webView.url ?: ""))
                        }
                    }
                }
            } catch (_: Exception) {
                if (cont.isActive) {
                    cont.resume(emptySnapshot(""))
                }
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
        isInvalidModelModeSelected = false
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
                verificationReport = "1s AI Confirm: Initial / Wait state verified.",
                consecutiveFailures = consecutiveStuckCount,
                recommendedSense = 1
            )
        }

        val urlChanged = before.url != after.url
        val domChanged = before.domFingerprint != after.domFingerprint ||
                abs(before.elementCount - after.elementCount) >= 1
        val streamAppeared = !before.hasActiveVideoStream && after.hasActiveVideoStream
        val renderStarted = !before.isFlowRenderingInProgress && after.isFlowRenderingInProgress

        return if (urlChanged || domChanged || streamAppeared || renderStarted) {
            consecutiveStuckCount = 0
            StepVerificationResult(
                passed = true,
                verificationReport = "1s AI CONFIRMED SUCCESS: Screen progressed after $actionTaken ($expectedOutcome).",
                consecutiveFailures = 0,
                recommendedSense = 1
            )
        } else {
            consecutiveStuckCount++
            val nextSense = (consecutiveStuckCount + 1).coerceIn(2, 4)
            StepVerificationResult(
                passed = false,
                verificationReport = "1s AI CONFIRM FAILED (#$consecutiveStuckCount): Screen did NOT change after$actionTaken! Switching to ${getSenseName(nextSense)}.",
                consecutiveFailures = consecutiveStuckCount,
                recommendedSense = nextSense
            )
        }
    }

    /**
     * 4-Sense Completed Video Card Opener & MP4 Extractor:
     * Sense 1: Zero-Click Background Network Stream Sniffer (`AiOverlayHud.lastSniffedMp4Url`)
     * Sense 2: DOM `<video>`, Performance Timing & React Fiber State Extractor
     * Sense 3: 3x Finger Touch on Inner Play Icon `@(92, 286)`
     * Sense 4: 3x Finger Touch on Card Center `@(148, 360)` -> Download Button
     */
    suspend fun attemptOpenPlayAndDownloadVideo(
        webView: WebView,
        hud: AiOverlayHud?,
        attemptIndex: Int
    ): Pair<Boolean, String> {
        // Sense 1: Zero-Click Background Network Sniffer Check
        val sniffedUrl = AiOverlayHud.lastSniffedMp4Url
        val sniffedAgeMs = System.currentTimeMillis() - AiOverlayHud.lastSniffedTimestampMs
        if (!sniffedUrl.isNullOrBlank() && sniffedAgeMs < 180_000L) {
            AiOverlayHud.lastSniffedMp4Url = null
            val ok = hud?.enqueueDirectVideoDownload(sniffedUrl) ?: false
            if (ok) {
                return true to "[Sense-1: Network Stream Sniffer] Captured MP4 directly in background!"
            }
        }

        // Sense 2: Inspect DOM <video>, Performance Timing, React Fiber, or find Play/Card coordinates
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

                    // Sense 3 & Sense 4: Rotate between Play Icon @(92,286), Card Center, and Upper-Right Download area
                    val (tapX, tapY, senseTag) = when (attemptIndex % 3) {
                        1 -> Triple(92f, 286f, "Sense-3: Play Icon Tap")
                        2 -> Triple(cssX + 18f, cssY + 22f, "Sense-4: Offset Card Tap")
                        else -> Triple(cssX, cssY, "Sense-2: 3x Card Tap")
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
                        if (ok) return true to "[$senseTag] Tapped$targetName & captured MP4 stream!"
                    }
                    false to "[$senseTag] Tapped$targetName @(${tapX.toInt()},${tapY.toInt()}); 1s AI confirming player..."
                }
                else -> false to "Scanning for completed video card..."
            }
        } catch (e: Exception) {
            false to "Card open check error: ${e.message}"
        }
    }
}
