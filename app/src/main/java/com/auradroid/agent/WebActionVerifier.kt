package com.auradroid.agent

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebView
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

data class WebStepVerification(
    val pageChanged: Boolean,
    val urlChanged: Boolean,
    val domChanged: Boolean,
    val hasActiveVideoStream: Boolean,
    val summary: String
)

object WebActionVerifier {

    /**
     * Dispatches a REAL Android Physical Finger Tap (MotionEvent ACTION_DOWN -> ACTION_UP)
     * directly onto the WebView at CSS coordinates (cssX, cssY).
     * This solves stubborn web elements (like Google Flow video cards & Play buttons) that ignore JS .click()!
     */
    @Suppress("DEPRECATION")
    suspend fun dispatchRealFingerTapOnWebView(
        webView: WebView?,
        cssX: Float,
        cssY: Float
    ): Boolean {
        if (webView == null || cssX <= 0f || cssY <= 0f) return false
        val scale = webView.scale.takeIf { it > 0.1f } ?: 1f
        val viewX = (cssX * scale).coerceIn(5f, (webView.width.takeIf { it > 10 } ?: 540) - 5f)
        val viewY = (cssY * scale).coerceIn(5f, (webView.height.takeIf { it > 10 } ?: 960) - 5f)

        val downTime = SystemClock.uptimeMillis()
        val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, viewX, viewY, 0)
        webView.dispatchTouchEvent(downEvent)
        downEvent.recycle()

        delay(90)

        val upTime = SystemClock.uptimeMillis()
        val upEvent = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, viewX, viewY, 0)
        webView.dispatchTouchEvent(upEvent)
        upEvent.recycle()
        return true
    }

    /**
     * Finds the exact center (x, y) of `[data-aura-idx="index"]` inside the webpage,
     * performs JS click AND (if enabled by user) dispatches a Real Finger Touch on the WebView!
     */
    suspend fun clickElementWithRealTouchFallback(
        context: Context,
        webView: WebView?,
        index: Int
    ): String {
        if (webView == null) return "NO_WEBVIEW"
        val rawJson = getElementCenterAndJsClick(webView, index)
        return try {
            val obj = JSONObject(rawJson)
            val status = obj.optString("status", "NOT_FOUND")
            val cx = obj.optDouble("x", -1.0).toFloat()
            val cy = obj.optDouble("y", -1.0).toFloat()
            val label = obj.optString("label", "")

            if (VideoQueueManager.isRealWebTouchEnabled(context) && cx > 0f && cy > 0f) {
                delay(120)
                dispatchRealFingerTapOnWebView(webView, cx, cy)
            }
            "$status ($label at ${cx.toInt()},${cy.toInt()})"
        } catch (e: Exception) {
            rawJson
        }
    }

    private suspend fun getElementCenterAndJsClick(webView: WebView, index: Int): String =
        suspendCancellableCoroutine { cont ->
            val js = """
                (function() {
                    var el = document.querySelector('[data-aura-idx="$index"]');
                    if (!el) return JSON.stringify({status: 'NOT_FOUND', x: -1, y: -1, label: ''});
                    var clickable = el.closest('button, [role="button"], a') || el;
                    clickable.scrollIntoView({block: 'center'});
                    var rect = clickable.getBoundingClientRect();
                    var cx = rect.left + (rect.width / 2.0);
                    var cy = rect.top + (rect.height / 2.0);
                    var opts = { bubbles: true, cancelable: true, view: window, clientX: cx, clientY: cy };
                    clickable.dispatchEvent(new PointerEvent('pointerdown', opts));
                    clickable.dispatchEvent(new MouseEvent('mousedown', opts));
                    clickable.dispatchEvent(new PointerEvent('pointerup', opts));
                    clickable.dispatchEvent(new MouseEvent('mouseup', opts));
                    clickable.click();
                    var lbl = (clickable.innerText || clickable.getAttribute('aria-label') || clickable.tagName || '').trim().substring(0, 25);
                    return JSON.stringify({status: 'CLICKED', x: cx, y: cy, label: lbl});
                })();
            """.trimIndent()

            webView.evaluateJavascript(js) { res ->
                val clean = res?.trim('"')?.replace("\\\"", "\"") ?: "{}"
                if (cont.isActive) cont.resume(clean)
            }
        }

    /**
     * Verifies whether the previous action actually changed the webpage / opened the expected next screen.
     */
    fun verifyWebStepChanged(
        previousUrl: String,
        currentUrl: String,
        previousDomHash: Int,
        currentDomSnapshot: String,
        expectedOutcome: String
    ): WebStepVerification {
        val currentDomHash = currentDomSnapshot.take(900).hashCode()
        val urlChanged = previousUrl != currentUrl && previousUrl.isNotBlank()
        val domChanged = currentDomHash != previousDomHash && previousDomHash != 0
        val hasVideo = currentDomSnapshot.contains("HAS_VIDEO_ELEMENT: YES")
        val pageChanged = urlChanged || domChanged

        val summary = if (pageChanged) {
            "VERIFIED_OK: Screen updated after last action (urlChanged=$urlChanged, domChanged=$domChanged). Expected: '$expectedOutcome'."
        } else {
            "VERIFICATION_FAILED: Screen did NOT change after last action! The expected outcome ('$expectedOutcome') did NOT happen. Try a different element or use Real Finger Touch / High Model reasoning!"
        }

        return WebStepVerification(
            pageChanged = pageChanged,
            urlChanged = urlChanged,
            domChanged = domChanged,
            hasActiveVideoStream = hasVideo,
            summary = summary
        )
    }

    /**
     * Complete 3-Step Automated Handler for Google Flow when video finishes rendering (100%):
     * 1. Finds the finished video card on the grid (01:18) and taps it with Real Finger Touch to open the Player.
     * 2. Finds the center Play (▶) button inside the Player (01:24) and taps it + calls video.play() so the MP4 stream loads.
     * 3. Extracts the loaded MP4 blob/http stream or clicks the Download button to save it into AutoVideos.
     */
    suspend fun openPlayAndDownloadFlowVideo(
        context: Context,
        webView: WebView?
    ): String {
        if (webView == null) return "NO_WEBVIEW"

        val stepJson = inspectAndTriggerFlowVideoJson(webView)
        return try {
            val obj = JSONObject(stepJson)
            val stage = obj.optString("stage", "UNKNOWN")
            val tapX = obj.optDouble("x", -1.0).toFloat()
            val tapY = obj.optDouble("y", -1.0).toFloat()

            // Dispatch Real Finger Touch at the exact coordinates of the Video Card or Play Button!
            if (VideoQueueManager.isRealWebTouchEnabled(context) && tapX > 0f && tapY > 0f) {
                delay(150)
                dispatchRealFingerTapOnWebView(webView, tapX, tapY)
                delay(600)
                // If we just tapped the Play button, trigger a second check to grab the newly loaded <video> src!
                if (stage == "TAPPED_PLAY_BUTTON") {
                    inspectAndTriggerFlowVideoJson(webView)
                }
            }
            "$stage (${tapX.toInt()},${tapY.toInt()})"
        } catch (e: Exception) {
            stepJson
        }
    }

    private suspend fun inspectAndTriggerFlowVideoJson(webView: WebView): String =
        suspendCancellableCoroutine { cont ->
            val js = """
                (function() {
                    // STAGE 1: Check if a <video> element already has a loaded MP4/Blob stream
                    var vids = document.querySelectorAll('video');
                    for (var i = 0; i < vids.length; i++) {
                        var v = vids[i];
                        try { v.muted = false; v.play(); } catch(e) {}
                        var src = v.currentSrc || v.src;
                        if (!src) {
                            var sEl = v.querySelector('source');
                            if (sEl) src = sEl.src;
                        }
                        if (src && src.startsWith('blob:')) {
                            fetch(src).then(r => r.blob()).then(b => {
                                var reader = new FileReader();
                                reader.onloadend = function() {
                                    AuraBlobBridge.saveBase64Video(reader.result, 'flow_8s_' + Date.now() + '.mp4');
                                };
                                reader.readAsDataURL(b);
                            });
                            return JSON.stringify({stage: 'SAVING_BLOB_STREAM', x: -1, y: -1});
                        } else if (src && src.startsWith('http')) {
                            fetch(src, { credentials: 'include' }).then(r => r.blob()).then(b => {
                                var reader = new FileReader();
                                reader.onloadend = function() {
                                    AuraBlobBridge.saveBase64Video(reader.result, 'flow_8s_' + Date.now() + '.mp4');
                                };
                                reader.readAsDataURL(b);
                            }).catch(function() {
                                var a = document.createElement('a');
                                a.href = src;
                                a.download = 'flow_8s_' + Date.now() + '.mp4';
                                document.body.appendChild(a);
                                a.click();
                            });
                            return JSON.stringify({stage: 'SAVING_HTTP_STREAM', x: -1, y: -1});
                        }
                    }

                    // STAGE 2: Check if we are inside the Video Player view (0:00 / 0:08 visible or big Play button in center)
                    var bodyTxt = document.body ? document.body.innerText : '';
                    var isPlayerOpen = bodyTxt.indexOf('0:00') !== -1 || bodyTxt.indexOf('0:08') !== -1 || vids.length > 0;
                    if (isPlayerOpen) {
                        // Look for Download icon/button first
                        var dlBtns = document.querySelectorAll('a[download], button, [role="menuitem"], [role="button"]');
                        for (var d = 0; d < dlBtns.length; d++) {
                            var lbl = (dlBtns[d].innerText || dlBtns[d].getAttribute('aria-label') || dlBtns[d].getAttribute('title') || '').toLowerCase();
                            if (lbl.indexOf('download') !== -1 || lbl.indexOf('save') !== -1) {
                                var dRect = dlBtns[d].getBoundingClientRect();
                                dlBtns[d].click();
                                return JSON.stringify({stage: 'CLICKED_PLAYER_DOWNLOAD', x: dRect.left + dRect.width/2, y: dRect.top + dRect.height/2});
                            }
                        }

                        // Otherwise tap the big center Play (▶) button so Google Flow loads the video stream!
                        var allBtns = document.querySelectorAll('button, [role="button"], svg, i');
                        for (var p = 0; p < allBtns.length; p++) {
                            var pr = allBtns[p].getBoundingClientRect();
                            var pTxt = (allBtns[p].innerText || allBtns[p].getAttribute('aria-label') || '').toLowerCase();
                            if (pr.top > 180 && pr.top < 620 && pr.left > 100 && pr.left < 440 && pr.width >= 24 && pr.height >= 24) {
                                if (pTxt.indexOf('play') !== -1 || pTxt === 'play_arrow' || pr.width > 40) {
                                    allBtns[p].click();
                                    return JSON.stringify({stage: 'TAPPED_PLAY_BUTTON', x: pr.left + pr.width/2, y: pr.top + pr.height/2});
                                }
                            }
                        }
                        // Fallback center tap of the video player
                        return JSON.stringify({stage: 'TAPPED_PLAY_BUTTON', x: window.innerWidth / 2, y: window.innerHeight * 0.45});
                    }

                    // STAGE 3: We are on the Project Thumbnail Grid (like 01:13 - 01:18 in the video)!
                    // Find the completed video thumbnail card on the grid and return its exact center (x, y) for Real Finger Touch!
                    var candidates = document.querySelectorAll('a[href*="/edit/"], img, video, [role="button"], button, div');
                    for (var c = 0; c < candidates.length; c++) {
                        var el = candidates[c];
                        var r = el.getBoundingClientRect();
                        var t = (el.innerText || el.getAttribute('aria-label') || '').trim();
                        if (r.top > 110 && r.top < 560 && r.left >= 8 && r.width > 80 && r.height > 100 && r.width < 480) {
                            if (t.indexOf('Failed') === -1 && t.indexOf('%') === -1) {
                                var cx = r.left + (r.width / 2.0);
                                var cy = r.top + (r.height / 2.0);
                                el.click();
                                return JSON.stringify({stage: 'TAPPED_VIDEO_CARD_ON_GRID', x: cx, y: cy});
                            }
                        }
                    }

                    return JSON.stringify({stage: 'WAITING_FOR_CARD', x: -1, y: -1});
                })();
            """.trimIndent()

            webView.evaluateJavascript(js) { res ->
                val clean = res?.trim('"')?.replace("\\\"", "\"") ?: "{}"
                if (cont.isActive) cont.resume(clean)
            }
        }
}
