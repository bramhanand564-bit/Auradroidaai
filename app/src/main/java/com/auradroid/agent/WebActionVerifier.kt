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
    val isInvalidModelModeSelected: Boolean,
    // Private verification evidence. These values are used locally and are not automatically sent to the model.
    val pageText: String = "",
    val scrollY: Float = 0f,
    val formStateFingerprint: Int = 0,
    val activeElementSummary: String = "",
    val activeElementValue: String = "",
    val readyState: String = "unknown",
    val hasVisibleErrorMessage: Boolean = false,
    val hasVisibleSuccessMessage: Boolean = false,
    val viewportCssWidth: Float = 540f,
    val viewportCssHeight: Float = 960f
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
            2 -> "Sense-2: DOM Pointer/Mouse Events"
            3 -> "Sense-3: Inner Control Touch"
            else -> "Sense-4: Offset Touch / Keyboard Fallback"
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
        val usePhysicalTouch = VideoQueueManager.isRealWebTouchEnabled(webView.context)
        val jsResult = suspendCancellableCoroutine<String> { cont ->
            val js = """
                (function() {
                    var vw = window.innerWidth || 540;
                    var vh = window.innerHeight || 960;
                    var list = window.__naxElements || [];
                    var el = list[$elementIndex];
                    if (!el) return JSON.stringify({ ok: false, error: 'STALE_TARGET', vw: vw, vh: vh });

                    var disabled = !!el.disabled || el.getAttribute('aria-disabled') === 'true';
                    if (disabled) return JSON.stringify({ ok: false, error: 'DISABLED_TARGET', vw: vw, vh: vh });

                    var targetEl = el;
                    if ($activeSense === 3 && el.querySelector) {
                        var inner = el.querySelector('button:not([disabled]), a, [role="button"]:not([aria-disabled="true"]), input:not([disabled]), svg, [aria-label]');
                        if (inner) targetEl = inner;
                    }

                    var r = targetEl.getBoundingClientRect();
                    if (r.width < 1 || r.height < 1 || r.bottom < 0 || r.top > vh || r.right < 0 || r.left > vw) {
                        return JSON.stringify({ ok: false, error: 'TARGET_NOT_VISIBLE', vw: vw, vh: vh });
                    }

                    var x = r.left + r.width / 2;
                    var y = r.top + r.height / 2;
                    var method = '';
                    if ($activeSense === 1) {
                        targetEl.focus();
                        targetEl.click();
                        method = 'DOM_CLICK';
                    } else if ($activeSense === 2) {
                        targetEl.focus();
                        targetEl.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, clientX: x, clientY: y }));
                        targetEl.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, cancelable: true, clientX: x, clientY: y }));
                        targetEl.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, clientX: x, clientY: y }));
                        method = 'POINTER_MOUSE_EVENTS';
                    } else if (!$usePhysicalTouch) {
                        targetEl.focus();
                        var key = ($activeSense === 3) ? 'Enter' : ' ';
                        targetEl.dispatchEvent(new KeyboardEvent('keydown', { key: key, bubbles: true, cancelable: true }));
                        targetEl.dispatchEvent(new KeyboardEvent('keyup', { key: key, bubbles: true, cancelable: true }));
                        method = 'KEYBOARD_' + ($activeSense === 3 ? 'ENTER' : 'SPACE');
                    } else {
                        method = ($activeSense === 3) ? 'INNER_TOUCH' : 'OFFSET_TOUCH';
                        if ($activeSense === 4) {
                            x = Math.max(r.left + 4, Math.min(r.right - 4, x + Math.min(10, r.width / 5)));
                            y = Math.max(r.top + 4, Math.min(r.bottom - 4, y - Math.min(8, r.height / 5)));
                        }
                    }
                    return JSON.stringify({
                        ok: true, method: method,
                        x: Math.round(x), y: Math.round(y), vw: vw, vh: vh
                    });
                })();
            """.trimIndent()
                .replace("$" + "usePhysicalTouch", usePhysicalTouch.toString())

            mainHandler.post {
                webView.evaluateJavascript(js) { raw ->
                    val clean = raw?.trim()?.removeSurrounding("\"")?.replace("\\\"", "\"") ?: "{}"
                    if (cont.isActive) cont.resume(clean)
                }
            }
        }

        return try {
            val obj = JSONObject(jsResult)
            if (!obj.optBoolean("ok", false)) {
                "FAILED: ${obj.optString("error", "target unavailable")}; no click was sent"
            } else {
                val method = obj.optString("method", "UNKNOWN")
                if (method == "INNER_TOUCH" || method == "OFFSET_TOUCH") {
                    val ok = dispatchRealWebTouch(
                        webView = webView,
                        hud = hud,
                        cssX = obj.optDouble("x", fallbackCssX.toDouble()).toFloat(),
                        cssY = obj.optDouble("y", fallbackCssY.toDouble()).toFloat(),
                        viewportCssWidth = obj.optDouble("vw", 540.0).toFloat(),
                        viewportCssHeight = obj.optDouble("vh", 960.0).toFloat(),
                        holdDurationMs = if (activeSense == 3) 120L else 150L,
                        label = "${getSenseName(activeSense)} • ${label.take(18)}"
                    )
                    if (!ok) "FAILED: WebView touch dispatch failed" else "${getSenseName(activeSense)}: ${method}"
                } else "${getSenseName(activeSense)}: ${method}"
            }
        } catch (_: Exception) {
            "FAILED: click strategy threw an exception"
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
                var desiredDur = '${targetDurationSec}s';

                function visible(el) {
                    var r = el.getBoundingClientRect();
                    var cs = getComputedStyle(el);
                    return r.width > 12 && r.height > 10 && r.bottom > 0 && r.top < vh &&
                        r.right > 0 && r.left < vw && cs.display !== 'none' &&
                        cs.visibility !== 'hidden' && parseFloat(cs.opacity || '1') > 0.08;
                }
                function label(el) {
                    return String(el.getAttribute('aria-label') || el.innerText || el.textContent || el.title || '')
                        .replace(/\s+/g, ' ').trim().slice(0, 100);
                }
                function selected(el) {
                    var c = String(el.className && typeof el.className === 'string' ? el.className : '').toLowerCase();
                    var state = String(el.getAttribute('data-state') || '').toLowerCase();
                    return el.getAttribute('aria-selected') === 'true' ||
                        el.getAttribute('aria-pressed') === 'true' ||
                        el.getAttribute('aria-checked') === 'true' ||
                        state === 'on' || state === 'selected' ||
                        /(^|[ -])(active|selected|checked)([ -]|$)/.test(c);
                }
                var controls = Array.from(document.querySelectorAll(
                    'button, [role="button"], [role="option"], [role="menuitem"], [role="tab"], [role="radio"], [role="combobox"]'
                )).filter(visible);
                var popupRoots = Array.from(document.querySelectorAll('[role="menu"], [role="listbox"], [role="dialog"]')).filter(visible);
                var menuOpen = popupRoots.length > 0 ||
                    controls.some(function(el) { return /^(option|menuitem)$/.test(el.getAttribute('role') || ''); });
                var selectedText = controls.filter(selected).map(label).join(' ').toLowerCase();
                var outside = controls.filter(function(el) {
                    return !el.closest('[role="menu"], [role="listbox"], [role="dialog"]');
                });
                var modePill = outside.find(function(el) {
                    var t = label(el).toLowerCase();
                    return t.length < 80 && (t.indexOf('video') !== -1 || t.indexOf('nano banana') !== -1 || t.indexOf('image') !== -1);
                });
                var pillText = modePill ? label(modePill).toLowerCase() : '';
                var stateText = (selectedText + ' ' + pillText).toLowerCase();

                var videoOk = stateText.indexOf('video') !== -1 &&
                    stateText.indexOf('nano banana') === -1 &&
                    stateText.indexOf('image') === -1;
                var ratioOk = stateText.indexOf('9:16') !== -1 || stateText.indexOf('portrait') !== -1;
                var durationOk = stateText.indexOf(desiredDur.toLowerCase()) !== -1;
                var speedOk = stateText.indexOf('x1') !== -1 || stateText.indexOf('1x') !== -1 ||
                    /(^|[ ,•])1([ ,•]|$)/.test(stateText);
                if (videoOk && ratioOk && durationOk && speedOk) {
                    return JSON.stringify({ status: 'OK_VIDEO_MODE', detail: 'Selected Video, portrait 9:16, ' + desiredDur + ', x1', vw: vw, vh: vh });
                }

                var missing = !videoOk ? 'video' : (!ratioOk ? 'ratio' : (!durationOk ? 'duration' : 'speed'));
                var exactMatch = function(t, key) {
                    t = t.toLowerCase().replace(/\s+/g, ' ').trim();
                    if (key === 'video') return t === 'video' || t === 'video mode';
                    if (key === 'ratio') return t === '9:16' || t === 'portrait (9:16)' || t === 'portrait 9:16';
                    if (key === 'duration') return t === desiredDur.toLowerCase();
                    return t === 'x1' || t === '1x' || t === '1';
                };

                if (menuOpen) {
                    var options = controls.filter(function(el) {
                        var role = el.getAttribute('role') || '';
                        return /^(option|menuitem|tab|radio)$/.test(role) ||
                            !!el.closest('[role="menu"], [role="listbox"], [role="dialog"]');
                    });
                    var option = options.find(function(el) {
                        return exactMatch(label(el), missing) && !selected(el) &&
                            !el.disabled && el.getAttribute('aria-disabled') !== 'true';
                    });
                    if (option) {
                        option.click();
                        return JSON.stringify({ status: 'SELECTED_OPTION', detail: 'Selected one option: ' + label(option), vw: vw, vh: vh });
                    }

                    // If this menu does not contain the missing setting, move to that setting's pill with one click.
                    var settingPill = outside.find(function(el) {
                        var t = label(el).toLowerCase();
                        if (missing === 'ratio') return t.indexOf('9:16') !== -1 || t.indexOf('16:9') !== -1 || t.indexOf('portrait') !== -1 || t.indexOf('landscape') !== -1 || t.indexOf('aspect') !== -1;
                        if (missing === 'duration') return /(^|[^0-9])(8s|10s|5s|6s)([^0-9]|$)/.test(t);
                        if (missing === 'speed') return t.indexOf('x1') !== -1 || t.indexOf('x2') !== -1 || t.indexOf('x4') !== -1 || /(^| )1( |$)/.test(t);
                        return t.indexOf('video') !== -1 || t.indexOf('image') !== -1 || t.indexOf('nano banana') !== -1;
                    });
                    if (settingPill) {
                        settingPill.click();
                        return JSON.stringify({ status: 'OPENED_MODE_PILL', detail: 'Opened setting: ' + label(settingPill), vw: vw, vh: vh });
                    }
                    return JSON.stringify({ status: 'MODE_UNCERTAIN', detail: 'Menu is open but the missing setting could not be identified safely.', vw: vw, vh: vh });
                }

                // No popup is open. Click exactly one pill to expose the next needed setting.
                var pill = outside.find(function(el) {
                    var t = label(el).toLowerCase();
                    if (missing === 'ratio') return t.indexOf('9:16') !== -1 || t.indexOf('16:9') !== -1 || t.indexOf('portrait') !== -1 || t.indexOf('landscape') !== -1 || t.indexOf('aspect') !== -1;
                    if (missing === 'duration') return /(^|[^0-9])(8s|10s|5s|6s)([^0-9]|$)/.test(t);
                    if (missing === 'speed') return t.indexOf('x1') !== -1 || t.indexOf('x2') !== -1 || t.indexOf('x4') !== -1 || /(^| )1( |$)/.test(t);
                    return t.indexOf('video') !== -1 || t.indexOf('image') !== -1 || t.indexOf('nano banana') !== -1;
                });
                if (pill) {
                    pill.click();
                    return JSON.stringify({ status: 'OPENED_MODE_PILL', detail: 'Opened setting: ' + label(pill), vw: vw, vh: vh });
                }
                return JSON.stringify({ status: 'MODE_UNCERTAIN', detail: 'No visible pill for the missing setting (' + missing + ').', vw: vw, vh: vh });
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
                if (cont.isActive) cont.resume("{\"status\":\"MODE_UNCERTAIN\"}")
            }
        }
    }

    /**
     * Captures live DOM/semantic state and up to 80 visible interactive target candidates,
     * and checks Google Flow rendering progress (0%..100%) and completed video cards.
     */
    suspend fun captureSnapshot(webView: WebView): DomSnapshot = suspendCancellableCoroutine { cont ->
        val js = """
            (function() {
                var vw = window.innerWidth ? window.innerWidth : 540;
                var vh = window.innerHeight ? window.innerHeight : 960;
                window.__naxElements = [];
                var lines = [];

                var all = Array.from(document.querySelectorAll(
                    'button, a[href], input, textarea, select, [contenteditable="true"], ' +
                    '[role="button"], [role="link"], [role="tab"], [role="menuitem"], [role="option"], ' +
                    '[role="switch"], [role="checkbox"], [role="radio"], [role="combobox"], [aria-haspopup], ' +
                    '[tabindex]:not([tabindex="-1"]), video, [class*="card"], [class*="project"], ' +
                    '[class*="result"], [class*="media"], [class*="player"], [class*="thumb"]'
                ));
                // Reserve list capacity for genuine controls before lower-confidence card/container candidates.
                function targetPriority(el) {
                    var tag = (el.tagName || '').toLowerCase();
                    var role = el.getAttribute('role') || '';
                    if (/^(button|a|input|textarea|select)$/.test(tag)) return 0;
                    if (/^(button|link|tab|menuitem|option|switch|checkbox|radio|combobox)$/.test(role)) return 1;
                    if (el.hasAttribute('aria-haspopup') || el.hasAttribute('tabindex')) return 2;
                    return 3;
                }
                all.sort(function(a, b) { return targetPriority(a) - targetPriority(b); });
                var idx = 0;

                for (var i = 0; i < all.length; i++) {
                    var el = all[i];
                    var r = el.getBoundingClientRect();
                    if (r.width < 16 || r.height < 16) continue;
                    if (r.bottom < 0 || r.top > vh + 40 || r.right < 0 || r.left > vw + 40) continue;

                    var style = window.getComputedStyle(el);
                    if (style.display === 'none' || style.visibility === 'hidden' || parseFloat(style.opacity) < 0.08) continue;

                    var tag = el.tagName.toLowerCase();
                    var rawTxt = '';
                    if (tag === 'input' || tag === 'textarea' || tag === 'select' || el.isContentEditable) {
                        rawTxt = el.getAttribute('aria-label') || el.placeholder ||
                            el.getAttribute('name') || el.getAttribute('title') || '';
                        if (!rawTxt) rawTxt = tag.toUpperCase() + (el.type ? ':' + el.type : '');
                    } else {
                        rawTxt = el.innerText || el.getAttribute('aria-label') || el.getAttribute('title') || '';
                    }
                    var txt = rawTxt.replace(/\s+/g, ' ').trim();
                    if (txt.length > 75) txt = txt.substring(0, 75) + '...';

                    var isMediaCard = (tag === 'video') ||
                        (el.querySelector && el.querySelector('video, img, canvas') !== null && r.width >= 90 && r.height >= 90);

                    if (!txt && !isMediaCard && tag !== 'input' && tag !== 'textarea') continue;

                    window.__naxElements.push(el);
                    var cx = Math.round(r.left + r.width / 2);
                    var cy = Math.round(r.top + r.height / 2);
                    var label = txt ? txt : (isMediaCard ? 'MEDIA_CARD_THUMBNAIL' : tag.toUpperCase());
                    var role = el.getAttribute('role') || tag;
                    var disabled = !!el.disabled || el.getAttribute('aria-disabled') === 'true';
                    var selected = el.getAttribute('aria-selected') || el.getAttribute('aria-pressed') ||
                        el.getAttribute('aria-checked') || (el.checked === true ? 'true' : '');
                    var rawFieldValue = (tag === 'input' || tag === 'textarea' || tag === 'select')
                        ? String(el.value || '') : '';
                    var valueHint = (tag === 'input' || tag === 'textarea' || tag === 'select')
                        ? (' valuePresent=' + (rawFieldValue.length > 0 ? 'yes' : 'no') + ' valueLength=' + rawFieldValue.length)
                        : '';
                    lines.push('[' + idx + '] ' + tag.toUpperCase() + ' role=' + role +
                        ' "' + label.replace(/\s+/g, ' ').slice(0, 100) + '"' +
                        ' disabled=' + (disabled ? 'yes' : 'no') +
                        (selected ? ' selected=' + selected : '') + valueHint +
                        ' @(' + cx + ',' + cy + ') size=' + Math.round(r.width) + 'x' + Math.round(r.height) + ' top=' + Math.round(r.top));
                    idx++;
                    if (idx >= 80) break;
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

                // Local-only state used to verify actions. Do not send entered values to Gemini by default.
                var formParts = [];
                var fields = Array.from(document.querySelectorAll('input, textarea, select, [contenteditable="true"]'));
                for (var q = 0; q < fields.length; q++) {
                    var field = fields[q];
                    var fr = field.getBoundingClientRect();
                    if (fr.width < 1 || fr.height < 1) continue;
                    var fval = ('value' in field) ? String(field.value || '') : String(field.innerText || '');
                    formParts.push((field.tagName || '') + '|' + (field.type || '') + '|' +
                        (field.getAttribute('aria-label') || field.placeholder || '') + '|' +
                        fval + '|' + (field.checked === true) + '|' +
                        (!!field.disabled || field.getAttribute('aria-disabled') === 'true'));
                }
                var formState = formParts.join('||');
                var formHash = 0;
                for (var fh = 0; fh < formState.length; fh++) {
                    formHash = ((formHash << 5) - formHash + formState.charCodeAt(fh)) | 0;
                }

                var active = document.activeElement;
                var activeTag = active && active.tagName ? active.tagName.toLowerCase() : '';
                var activeLabel = active ? (active.getAttribute('aria-label') || active.placeholder ||
                    active.getAttribute('name') || active.innerText || '') : '';
                var activeValue = active ? (('value' in active) ? String(active.value || '') :
                    (active.isContentEditable ? String(active.innerText || '') : '')) : '';
                var activeSummary = (activeTag + ' ' + String(activeLabel).replace(/\s+/g, ' ').slice(0, 100)).trim();

                function visibleMessage(el) {
                    if (!el) return '';
                    var rr = el.getBoundingClientRect();
                    var st = window.getComputedStyle(el);
                    if (rr.width < 1 || rr.height < 1 || st.display === 'none' || st.visibility === 'hidden') return '';
                    return String(el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 240);
                }
                var errorMessages = Array.from(document.querySelectorAll('[role="alert"], [aria-invalid="true"], [data-state="error"], .error, .toast-error'))
                    .map(visibleMessage).filter(Boolean).slice(0, 5);
                var successMessages = Array.from(document.querySelectorAll('[role="status"], [aria-live="polite"], [data-state="success"], .toast-success'))
                    .map(visibleMessage).filter(Boolean).slice(0, 5);
                var pageErrors = /\b(error|failed|failure|could not|cannot|try again)\b/i.test(errorMessages.join(' '));
                var pageSuccess = /\b(success|completed|complete|uploaded|published|saved|sent successfully)\b/i.test(successMessages.join(' '));

                var fpSource = location.href + '|' + (document.title || '') + '|' + bodyText + '|' +
                    lines.join('|') + '|' + formState + '|' + activeSummary + '|' + window.scrollY;
                var fpHash = 0;
                for (var h = 0; h < fpSource.length; h++) {
                    fpHash = ((fpHash << 5) - fpHash + fpSource.charCodeAt(h)) | 0;
                }

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
                    textHash: String(fpHash),
                    pageText: bodyText.slice(0, 8000),
                    scrollY: window.scrollY || 0,
                    formHash: formHash,
                    activeSummary: activeSummary,
                    activeValue: activeValue.slice(0, 1200),
                    readyState: document.readyState || 'unknown',
                    hasError: pageErrors,
                    hasSuccess: pageSuccess,
                    viewportWidth: vw,
                    viewportHeight: vh
                });
            })();
        """.trimIndent()

        mainHandler.post {
            try {
                webView.evaluateJavascript(js) { raw ->
                    try {
                        val clean = raw?.let { encoded ->
                            runCatching { org.json.JSONArray("[$encoded]").getString(0) }
                                .getOrElse { encoded.trim().removeSurrounding("\"") }
                        } ?: "{}"
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
                            isInvalidModelModeSelected = obj.optBoolean("invalidMode", false),
                            pageText = obj.optString("pageText", ""),
                            scrollY = obj.optDouble("scrollY", 0.0).toFloat(),
                            formStateFingerprint = obj.optInt("formHash", 0),
                            activeElementSummary = obj.optString("activeSummary", ""),
                            activeElementValue = obj.optString("activeValue", ""),
                            readyState = obj.optString("readyState", "unknown"),
                            hasVisibleErrorMessage = obj.optBoolean("hasError", false),
                            hasVisibleSuccessMessage = obj.optBoolean("hasSuccess", false),
                            viewportCssWidth = obj.optDouble("viewportWidth", 540.0).toFloat(),
                            viewportCssHeight = obj.optDouble("viewportHeight", 960.0).toFloat()
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
        expectedOutcome: String,
        countFailure: Boolean = true
    ): StepVerificationResult {
        lastSnapshot = after
        val action = actionTaken.uppercase()
        if (action == "WAIT") {
            // WAIT is an observation interval, not a completed user task step.
            return StepVerificationResult(
                passed = true,
                verificationReport = "OBSERVE ONLY: Wait completed; no task-action success inferred.",
                consecutiveFailures = consecutiveStuckCount,
                recommendedSense = 1
            )
        }
        if (action == "START") {
            return StepVerificationResult(
                passed = true,
                verificationReport = "Initial DOM state captured; no user action claimed.",
                consecutiveFailures = consecutiveStuckCount,
                recommendedSense = 1
            )
        }
        if (before == null) {
            if (countFailure) consecutiveStuckCount++
            return StepVerificationResult(
                passed = false,
                verificationReport = "VERIFY FAILED: no pre-action snapshot exists for ${action}.",
                consecutiveFailures = consecutiveStuckCount,
                recommendedSense = 2
            )
        }

        val urlChanged = before.url != after.url
        val targetUrl = expectedOutcome.trim()
        val urlMatchesRequested = action == "LOAD_URL" && targetUrl.isNotBlank() &&
            after.url.startsWith(targetUrl, ignoreCase = true)
        // Geometry (x/y/size/top) changes during scroll/layout are not proof that a click worked.
        val elementStateChanged = semanticElementState(before.interactiveElementsText) !=
            semanticElementState(after.interactiveElementsText)
        val formStateChanged = before.formStateFingerprint != after.formStateFingerprint
        val activeControlChanged = before.activeElementSummary != after.activeElementSummary
        val activeValueChanged = before.activeElementValue != after.activeElementValue
        val scrollChanged = abs(before.scrollY - after.scrollY) >= 24f
        val streamAppeared = !before.hasActiveVideoStream && after.hasActiveVideoStream
        val renderStarted = !before.isFlowRenderingInProgress && after.isFlowRenderingInProgress
        val renderProgressChanged = before.renderingProgressLabel != after.renderingProgressLabel
        val errorAppeared = !before.hasVisibleErrorMessage && after.hasVisibleErrorMessage
        val successAppeared = !before.hasVisibleSuccessMessage && after.hasVisibleSuccessMessage
        val beforeText = before.pageText.replace(Regex("\\s+"), " ").trim()
        val afterText = after.pageText.replace(Regex("\\s+"), " ").trim()
        val prefixLength = commonPrefixLength(beforeText, afterText)
        val pageTextChanged = beforeText != afterText &&
            (kotlin.math.abs(beforeText.length - afterText.length) >= 6 ||
                prefixLength < minOf(beforeText.length, afterText.length) - 5)
        val basicStateChanged = urlChanged || elementStateChanged || formStateChanged ||
            activeControlChanged || activeValueChanged || streamAppeared || renderStarted ||
            renderProgressChanged || errorAppeared || successAppeared || pageTextChanged
        // Merely focusing a button is not a successful click; require an actual semantic/page/control effect.
        val clickEffectChanged = urlChanged || elementStateChanged || formStateChanged ||
            activeValueChanged || streamAppeared || renderStarted || renderProgressChanged ||
            errorAppeared || successAppeared || pageTextChanged

        val success = when (action) {
            "LOAD_URL" -> urlMatchesRequested || (urlChanged && after.readyState != "loading")
            "TYPE_EL" -> {
                val requestedText = expectedOutcome.trim()
                if (requestedText.isBlank()) {
                    formStateChanged
                } else {
                    after.activeElementValue.contains(requestedText, ignoreCase = false) ||
                        after.pageText.contains(requestedText, ignoreCase = false) ||
                        (formStateChanged && after.activeElementValue == requestedText)
                }
            }
            "SCROLL" -> scrollChanged || (elementStateChanged && pageTextChanged)
            "DOWNLOAD_VIDEO" -> streamAppeared || successAppeared ||
                (pageTextChanged && containsDownloadCompletion(after.pageText))
            "DONE" -> false // Completion is checked separately against the user's overall goal.
            "CLICK_EL", "CLICK_XY", "CLICK_NEW_PROJECT", "SUBMIT_FLOW_PROMPT" -> {
                // A route, dialog, result, control/form state or meaningful text change counts.
                // Merely dispatching a click never counts as success.
                clickEffectChanged && !errorAppeared
            }
            else -> basicStateChanged && !errorAppeared
        }

        if (success) {
            if (countFailure) consecutiveStuckCount = 0
            val evidence = when {
                urlChanged -> "URL changed"
                formStateChanged || activeValueChanged -> "form/control state changed"
                scrollChanged -> "scroll position changed"
                streamAppeared -> "video stream appeared"
                renderProgressChanged || renderStarted -> "render progress changed"
                successAppeared -> "visible success status appeared"
                else -> "meaningful DOM/page state changed"
            }
            return StepVerificationResult(
                passed = true,
                verificationReport = "CONFIRMED: ${action} verified by ${evidence}.",
                consecutiveFailures = 0,
                recommendedSense = 1
            )
        }

        if (countFailure) consecutiveStuckCount++
        val failureNumber = consecutiveStuckCount + if (countFailure) 0 else 1
        val nextSense = (failureNumber + 1).coerceIn(2, 4)
        val reason = when {
            errorAppeared -> "visible error state appeared"
            action == "TYPE_EL" -> "requested text was not found in the active field/page state"
            action == "LOAD_URL" -> "requested URL was not reached"
            action == "SCROLL" -> "scroll position/content did not move"
            else -> "no meaningful post-action state change was observed"
        }
        return StepVerificationResult(
            passed = false,
            verificationReport = "VERIFY FAILED (#${consecutiveStuckCount}): ${reason} after ${action}; use ${getSenseName(nextSense)} or reassess target.",
            consecutiveFailures = consecutiveStuckCount,
            recommendedSense = nextSense
        )
    }

    private fun semanticElementState(raw: String): String {
        return raw.lineSequence()
            .map { line ->
                line.replace(Regex(""" @\(-?\d+,-?\d+\) size=\d+x\d+ top=-?\d+"""), "")
            }
            .joinToString("\n")
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val max = minOf(a.length, b.length)
        var i = 0
        while (i < max && a[i] == b[i]) i++
        return i
    }

    private fun containsDownloadCompletion(text: String): Boolean {
        val s = text.lowercase()
        return listOf("download complete", "downloaded successfully", "file saved", "download finished")
            .any { s.contains(it) }
    }

    /**
     * Retrying these targets can duplicate irreversible or expensive side effects.
     * For such actions, observe and reassess instead of blindly tapping again.
     */
    fun isNonRepeatableAction(targetAndReason: String): Boolean {
        val s = targetAndReason.lowercase()
        return Regex("""\b(submit|upload|publish|post|send|delete|remove|pay|purchase|buy|order|confirm|create|generate|save|apply|follow|subscribe|book|comment|share|start)\b""")
            .containsMatchIn(s)
    }

    /**
     * Re-resolve a target after a failed attempt instead of assuming that DOM indexes remain stable.
     * A duplicate label is accepted only when the same index still names that label.
     */
    fun resolveElementIndexByLabel(
        originalElements: String,
        currentElements: String,
        originalIndex: Int
    ): Int {
        val originalLine = originalElements.lineSequence()
            .firstOrNull { it.trimStart().startsWith("[$originalIndex] ") } ?: return -1
        fun tag(line: String): String = line.substringAfter("] ").substringBefore(' ').trim()
        fun label(line: String): String {
            val first = line.indexOf('"')
            if (first < 0) return ""
            val last = line.indexOf('"', first + 1)
            if (last <= first) return ""
            return line.substring(first + 1, last).trim()
        }
        val expectedTag = tag(originalLine)
        val expectedLabel = label(originalLine)
        if (expectedLabel.isBlank()) return -1

        val matches = currentElements.lineSequence().filter {
            tag(it) == expectedTag && label(it).equals(expectedLabel, ignoreCase = true)
        }.toList()
        if (matches.size == 1) {
            return Regex("""^\[(\d+)\]""").find(matches.first().trim())
                ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
        }
        val sameIndex = currentElements.lineSequence()
            .firstOrNull { it.trimStart().startsWith("[$originalIndex] ") }
        return if (sameIndex != null && tag(sameIndex) == expectedTag &&
            label(sameIndex).equals(expectedLabel, ignoreCase = true)) originalIndex else -1
    }

    /**
     * Require task-specific visible evidence before accepting the model's DONE decision.
     * Returns a report only when success is supported; otherwise the caller must reassess.
     */
    fun verifyGoalCompletion(goal: String, snapshot: DomSnapshot): String? {
        val g = goal.lowercase()
        val page = (snapshot.title + " " + snapshot.url + " " + snapshot.pageText + " " +
            snapshot.interactiveElementsText).lowercase()
        if (snapshot.hasVisibleErrorMessage) return null

        fun hasAny(vararg phrases: String): Boolean = phrases.any { page.contains(it) }

        return when {
            listOf("upload", "uploaded", "youtube studio").any { g.contains(it) } ->
                if (hasAny("upload complete", "uploaded successfully", "video uploaded",
                        "video published", "saved as draft", "upload complete!")) {
                    "Goal evidence verified: page shows an upload/processing/publish confirmation."
                } else null
            listOf("publish", "post this", "post the", "publish the").any { g.contains(it) } ->
                if (hasAny("published", "post published", "your post is live", "successfully posted")) {
                    "Goal evidence verified: published/post confirmation is visible."
                } else null
            listOf("send message", "send this message", "send an email", "send email").any { g.contains(it) } ->
                if (hasAny("message sent", "sent successfully", "email sent", "delivered")) {
                    "Goal evidence verified: sent/delivered confirmation is visible."
                } else null
            listOf("delete", "remove this", "remove the").any { g.contains(it) } ->
                if (hasAny("deleted successfully", "item deleted", "removed successfully", "moved to trash")) {
                    "Goal evidence verified: deletion/removal confirmation is visible."
                } else null
            "download" in g ->
                if (containsDownloadCompletion(page)) {
                    "Goal evidence verified: download completion message is visible."
                } else null
            listOf("generate", "create", "render", "make a video", "make an image").any { g.contains(it) } ->
                if ((snapshot.hasActiveVideoStream || snapshot.renderingProgressLabel == "100%") &&
                    !snapshot.isFlowRenderingInProgress) {
                    "Goal evidence verified: completed media/result is present and rendering is not active."
                } else if (hasAny("generation complete", "created successfully", "render complete", "completed successfully")) {
                    "Goal evidence verified: page displays an explicit generation/completion state."
                } else null
            listOf("search", "find").any { g.startsWith(it) } ->
                if (hasAny("search results", "results found", "results for") &&
                    snapshot.readyState != "loading") {
                    "Goal evidence verified: results are present on the page."
                } else null
            listOf("open ", "navigate", "visit ").any { g.contains(it) } ->
                if (snapshot.url.isNotBlank() && snapshot.readyState == "complete" &&
                    snapshot.pageText.length >= 30 && !snapshot.hasVisibleErrorMessage) {
                    "Goal evidence verified: target page loaded with visible content."
                } else null
            else ->
                if (snapshot.hasVisibleSuccessMessage ||
                    hasAny("completed successfully", "task completed", "changes saved successfully")) {
                    "Goal evidence verified by an explicit page success message."
                } else null
        }
    }

    /**
     * Strong signal that Google Flow accepted a prompt: rendering/progress, an exposed video stream,
     * or explicit generation status text. A changed input field by itself is not acceptance.
     */
    fun isFlowPromptAccepted(snapshot: DomSnapshot, before: DomSnapshot? = null): Boolean {
        val beforeRender = before?.isFlowRenderingInProgress ?: false
        val beforeProgress = before?.renderingProgressLabel.orEmpty()
        val newRenderProgress = snapshot.isFlowRenderingInProgress &&
            (!beforeRender || snapshot.renderingProgressLabel != beforeProgress)
        val newHundredPercent = snapshot.renderingProgressLabel == "100%" && beforeProgress != "100%"
        val newStream = snapshot.hasActiveVideoStream && before?.hasActiveVideoStream != true

        val statusPattern = Regex("""\b(generating (your )?video|rendering (your )?video|generation started|video generation started|request queued|queued for generation|your video is ready)\b""")
        val currentStatus = statusPattern.containsMatchIn(snapshot.pageText.lowercase())
        val previousStatus = before?.pageText?.let { statusPattern.containsMatchIn(it.lowercase()) } ?: false
        val newExplicitStatus = currentStatus && !previousStatus

        // Never treat an old video card/100% label as evidence for the new prompt.
        return newRenderProgress || newHundredPercent || newStream || newExplicitStatus
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
                            if (br.width > 12 && br.height > 12 &&
                                !btn.disabled && btn.getAttribute('aria-disabled') !== 'true' &&
                                br.bottom > 0 && br.top < vh && br.right > 0 && br.left < vw) {
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
                        return JSON.stringify({
                            status: 'TAP_TARGET',
                            targetName: 'Video Card / Play',
                            x: Math.round(tr.left + tr.width / 2),
                            y: Math.round(tr.top + tr.height / 2),
                            vw: vw,
                            vh: vh
                        });
                    }

                    // Never guess a blind coordinate when the live DOM has no trustworthy target.
                    return JSON.stringify({ status: 'NO_TARGET', detail: 'No visible video/play/download target was found', vw: vw, vh: vh });
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
                    val cssX = obj.optDouble("x", -1.0).toFloat()
                    val cssY = obj.optDouble("y", -1.0).toFloat()
                    val vw = obj.optDouble("vw", 540.0).toFloat()
                    val vh = obj.optDouble("vh", 960.0).toFloat()
                    if (cssX < 0f || cssY < 0f) return false to "Rejected target with missing coordinates."

                    // One strategy per call: exact center, then a bounded lower-right offset, then upper-left offset.
                    val (tapX, tapY, senseTag) = when ((attemptIndex - 1).mod(3)) {
                        0 -> Triple(cssX, cssY, "Sense-2: Exact DOM Target")
                        1 -> Triple(
                            (cssX + 12f).coerceIn(6f, (vw - 6f).coerceAtLeast(10f)),
                            (cssY + 10f).coerceIn(6f, (vh - 6f).coerceAtLeast(10f)),
                            "Sense-3: Bounded Offset"
                        )
                        else -> Triple(
                            (cssX - 12f).coerceIn(6f, (vw - 6f).coerceAtLeast(10f)),
                            (cssY - 10f).coerceIn(6f, (vh - 6f).coerceAtLeast(10f)),
                            "Sense-4: Alternate Offset"
                        )
                    }

                    val touchOk = dispatchRealWebTouch(
                        webView = webView,
                        hud = hud,
                        cssX = tapX,
                        cssY = tapY,
                        viewportCssWidth = vw,
                        viewportCssHeight = vh,
                        holdDurationMs = 125L,
                        label = targetName
                    )
                    if (!touchOk) return false to "[$senseTag] WebView touch dispatch failed; no success claimed."
                    delay(1150)

                    val newlySniffed = AiOverlayHud.lastSniffedMp4Url
                    if (!newlySniffed.isNullOrBlank()) {
                        AiOverlayHud.lastSniffedMp4Url = null
                        val ok = hud?.enqueueDirectVideoDownload(newlySniffed) ?: false
                        if (ok) return true to "[$senseTag] Captured MP4 stream after targeting $targetName."
                    }
                    false to "[$senseTag] Targeted $targetName; stream capture is not yet confirmed."
                }
                "NO_TARGET" -> false to obj.optString("detail", "No safe target found; no click sent.")
                else -> false to "No safe video/play/download target was found."
            }
        } catch (e: Exception) {
            false to "Card open check error: ${e.message}"
        }
    }
}
