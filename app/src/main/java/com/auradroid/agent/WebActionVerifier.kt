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
    val consecutiveFailures: Int
)

object WebActionVerifier {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastSnapshot: DomSnapshot? = null
    private var consecutiveStuckCount = 0

    fun resetTracker() {
        lastSnapshot = null
        consecutiveStuckCount = 0
    }

    /**
     * Dispatches a hardware-accurate Android MotionEvent (DOWN -> MOVE -> UP) directly onto the WebView,
     * automatically scaling CSS coordinates (e.g. 360px wide) to actual WebView pixel coordinates (e.g. 1080px wide = 3x)!
     */
    suspend fun dispatchRealWebTouch(
        webView: WebView,
        hud: AiOverlayHud?,
        cssX: Float,
        cssY: Float,
        viewportCssWidth: Float = 540f,
        viewportCssHeight: Float = 960f,
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
                val eventDown = MotionEvent.obtain(
                    downTime,
                    downTime,
                    MotionEvent.ACTION_DOWN,
                    localX,
                    localY,
                    0
                )
                val eventMove = MotionEvent.obtain(
                    downTime,
                    downTime + 45L,
                    MotionEvent.ACTION_MOVE,
                    localX + 1f,
                    localY + 1f,
                    0
                )
                val eventUp = MotionEvent.obtain(
                    downTime,
                    downTime + 95L,
                    MotionEvent.ACTION_UP,
                    localX,
                    localY,
                    0
                )

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
     * Ensures Google Flow is set to Video mode (9:16 • 8s/10s • x1) and NOT Nano Banana / Image / x2 mode.
     */
    suspend fun ensureFlowVideoMode8s(
        webView: WebView,
        hud: AiOverlayHud?,
        targetDurationSec: Int = 10
    ): String = suspendCancellableCoroutine { cont ->
        val js = """
            (function() {
                var vw = window.innerWidth || 540;
                var vh = window.innerHeight || 960;
                var bodyTxt = (document.body ? document.body.innerText : '') || '';
                var desiredDur = ${targetDurationSec} + 's';
                
                var pills = Array.from(document.querySelectorAll('button, [role="button"], div, span'));
                for (var i = 0; i < pills.length; i++) {
                    var el = pills[i];
                    var t = (el.innerText || el.getAttribute('aria-label') || '').trim();
                    if (t.length > 0 && t.length < 48 &&
                        (t.indexOf('Nano Banana') !== -1 || t.indexOf('Image') !== -1 || t.indexOf('x2') !== -1 || t.indexOf('x4') !== -1 || (t.indexOf('Video') !== -1 && t.indexOf(desiredDur) === -1))) {
                        
                        var menuButtons = Array.from(document.querySelectorAll('button, [role="option"], [role="menuitem"], [role="tab"], span'));
                        var switched = [];
                        for (var j = 0; j < menuButtons.length; j++) {
                            var mb = menuButtons[j];
                            var mt = (mb.innerText || mb.getAttribute('aria-label') || '').trim();
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
     * Captures a rich snapshot of the current DOM, badges interactive elements,
     * and detects Google Flow rendering percentages (0%..100%) and completed video cards.
     */
    suspend fun captureSnapshot(webView: WebView): DomSnapshot = suspendCancellableCoroutine { cont ->
        val js = """
            (function() {
                try {
                    var oldBadges = document.querySelectorAll('.__nax_badge');
                    oldBadges.forEach(function(b) { b.remove(); });
                } catch(e) {}

                var vw = window.innerWidth || 540;
                var vh = window.innerHeight || 960;
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
                    var txt = (el.innerText || el.value || el.placeholder || el.getAttribute('aria-label') || el.getAttribute('title') || '').replace(/\s+/g, ' ').trim();
                    if (txt.length > 75) txt = txt.substring(0, 75) + '...';

                    var isMediaCard = (tag === 'video') ||
                        (el.querySelector && el.querySelector('video, img, canvas') !== null && r.width >= 90 && r.height >= 90);

                    if (!txt && !isMediaCard && tag !== 'input' && tag !== 'textarea') continue;

                    window.__naxElements.push(el);
                    var cx = Math.round(r.left + r.width / 2);
                    var cy = Math.round(r.top + r.height / 2);
                    var label = txt || (isMediaCard ? 'MEDIA_CARD_THUMBNAIL' : tag.toUpperCase());
                    lines.push('[' + idx + '] ' + tag.toUpperCase() + ' "' + label + '" @(' + cx + ',' + cy + ') size=' + Math.round(r.width) + 'x' + Math.round(r.height) + ' top=' + Math.round(r.top));
                    idx++;
                    if (idx >= 48) break;
                }

                var bodyText = (document.body ? document.body.innerText : '') || '';
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
                    title: document.title || '',
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
                            cont.resume(
                                DomSnapshot(
                                    url = webView.url ?: "",
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
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                if (cont.isActive) {
                    cont.resume(
                        DomSnapshot(
                            url = "",
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
                    )
                }
            }
        }
    }

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
                verificationReport = "Initial / Wait state active.",
                consecutiveFailures = consecutiveStuckCount
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
                verificationReport = "VERIFIED SUCCESS: Screen updated after $actionTaken (Expected: $expectedOutcome).",
                consecutiveFailures = 0
            )
        } else {
            consecutiveStuckCount++
            StepVerificationResult(
                passed = false,
                verificationReport = "VERIFICATION FAILED (#$consecutiveStuckCount): Screen did NOT change after $actionTaken! Escalating to 3x Real Touch / High Model.",
                consecutiveFailures = consecutiveStuckCount
            )
        }
    }

    /**
     * Multi-Stage Video Card Opener, Play Trigger & Zero-Click Background Stream Extractor:
     * 1. First checks if AiOverlayHud's network interceptor already sniffed the direct .mp4 URL in the background!
     * 2. Scans DOM <video>, <source>, performance entries, and React Fiber props for any .mp4 / blob URL.
     * 3. If no direct stream yet, returns the exact CSS coordinates of the completed video card & Play button
     *    so dispatchRealWebTouch can tap it with 3x scaled finger accuracy!
     */
    suspend fun attemptOpenPlayAndDownloadVideo(
        webView: WebView,
        hud: AiOverlayHud?,
        attemptIndex: Int
    ): Pair<Boolean, String> {
        // Stage 0: Zero-Click Background Network Sniffer Check!
        val sniffedUrl = AiOverlayHud.lastSniffedMp4Url
        val sniffedAgeMs = System.currentTimeMillis() - AiOverlayHud.lastSniffedTimestampMs
        if (!sniffedUrl.isNullOrBlank() && sniffedAgeMs < 180_000L) {
            AiOverlayHud.lastSniffedMp4Url = null
            val ok = hud?.enqueueDirectVideoDownload(sniffedUrl) ?: false
            if (ok) {
                return true to "Background Network Sniffer captured MP4 directly!"
            }
        }

        // Stage 1: Inspect DOM, React Fiber props, Performance Resource Timing, or locate Card/Play coordinates
        val jsResult = suspendCancellableCoroutine<String> { cont ->
            val js = """
                (function() {
                    var vw = window.innerWidth || 540;
                    var vh = window.innerHeight || 960;

                    // 1. Direct <video> or <source> elements
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

                    // 2. Check Performance Resource Timing for recently loaded mp4/video streams
                    try {
                        var res = performance.getEntriesByType('resource') || [];
                        for (var r = res.length - 1; r >= 0; r--) {
                            var name = res[r].name || '';
                            if (name.indexOf('.mp4') !== -1 || name.indexOf('videoplayback') !== -1 || name.indexOf('generated_video') !== -1) {
                                return JSON.stringify({ status: 'FOUND_URL', url: name, vw: vw, vh: vh });
                            }
                        }
                    } catch(e) {}

                    // 3. Scan React Fiber / state props on cards for hidden video URLs
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

                    // 4. Look for an explicit Download button or icon if player modal is already open
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

                    // 5. Locate the completed video card or Play icon on the project grid (top between 155 and vh - 140)
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

                    // Fallback coordinates right on the first project card slot (seen at ~145, 340 in CSS coords)
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
                        ok to "Extracted stream URL (${videoUrl.take(45)}...)"
                    } else {
                        false to "Empty video URL"
                    }
                }
                "TAP_TARGET" -> {
                    val targetName = obj.optString("targetName", "Video Card")
                    val cssX = obj.optDouble("x", 150.0).toFloat()
                    val cssY = obj.optDouble("y", 340.0).toFloat()
                    val vw = obj.optDouble("vw", 540.0).toFloat()
                    val vh = obj.optDouble("vh", 960.0).toFloat()

                    //Vary tap offset slightly on subsequent attempts to hit Play button vs Card center
                    val offsetX = when (attemptIndex % 3) {
                        1 -> -28f
                        2 -> 20f
                        else -> 0f
                    }
                    val offsetY = when (attemptIndex % 3) {
                        1 -> -55f
                        2 -> 25f
                        else -> 0f
                    }

                    dispatchRealWebTouch(
                        webView = webView,
                        hud = hud,
                        cssX = cssX + offsetX,
                        cssY = cssY + offsetY,
                        viewportCssWidth = vw,
                        viewportCssHeight = vh,
                        label = targetName
                    )
                    delay(1400)

                    // Check if the tap caused the network sniffer to catch the MP4 stream!
                    val newlySniffed = AiOverlayHud.lastSniffedMp4Url
                    if (!newlySniffed.isNullOrBlank()) {
                        AiOverlayHud.lastSniffedMp4Url = null
                        val ok = hud?.enqueueDirectVideoDownload(newlySniffed) ?: false
                        if (ok) return true to "Tapped $targetName & captured MP4 stream!"
                    }
                    false to "Tapped $targetName @(${(cssX + offsetX).toInt()},${(cssY + offsetY).toInt()}); waiting for player/stream..."
                }
                else -> false to "Scanning for completed video card..."
            }
        } catch (e: Exception) {
            false to "Card open check error: ${e.message}"
        }
    }
}
