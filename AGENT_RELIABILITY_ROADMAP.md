# AuraDroid AI — Agent Reliability Roadmap

## Goal

Nax Bot must act on the user's intended application and only continue when the current screen and target control have been checked. The agent must never confuse a website/domain with permission to launch Chrome, and must not silently continue in a different app.

## Implemented in the current routing/safety changes

1. **Intent-first native app routing** — for ambiguous social-app tasks such as “open twitter.com”, infer the intended installed app from the full user goal. Explicit wording such as “in Chrome”, “in a browser”, or “open the website” keeps the web route.
2. **Custom Twitter-client preference** — search launchable apps by their visible label and package, prioritizing Flox/Floks/Flocs-style Twitter clients when installed, instead of picking Chrome or blindly selecting a generic X label.
3. **No unrelated fallback** — if a native app cannot be resolved, do not quietly open Settings or Chrome and pretend the target succeeded.
4. **Launch verification and package pinning** — check the actual foreground package after launch and keep a native-app task tied to the verified app.
5. **Recovery from foreground drift** — before collecting targets at each step, recheck the foreground package. If an unrelated app took focus, attempt to restore the verified target and re-check before any tap.
6. **Cross-window target guard** — when a task is pinned to an app, do not click/type into a node from a different app. The on-screen keyboard and short-lived Android permission/chooser surfaces remain eligible.
7. **Fresh target validation** — AI-selected node IDs are revalidated against a newer accessibility snapshot, matched by package plus stable label/view ID, rather than trusted by index alone.
8. **Prompt-level routing rule** — the model is told that OPEN_APP accepts an installed app label/package or supported setting, never a web URL, and that it must preserve a verified app task.
9. **No visual target dots** — accessibility node indices remain internal; the phone UI stays clear.
10. **Blocked actions are recorded** — route mismatches and refused app launches are logged, and repeated unsafe target choices pause the task rather than dispatching a blind action.

## Next reliability ideas, in priority order

| Priority | Idea | Expected benefit | Status |
|---|---|---|---|
| P0 | **App-intent resolver test matrix** for “open Twitter”, “open twitter.com”, “open Floks Twitter”, “open twitter.com in Chrome”, and apps missing from the phone | Prevent routing regressions | Next |
| P0 | **One structured event per step**: goal, intended package, observed package, action, target label, pre/post snapshot hash, verification outcome | Makes “why did it do that?” diagnosable from logs | Next |
| P0 | **Foreground change gate for every action** — verify the target package immediately before and after a click/type, not only at loop start | Closes remaining focus-change race windows | Partial: loop-start and fresh-node checks added |
| P0 | **Side-effect confirmation barrier** before Send, Publish, Delete, Follow/Unfollow, or purchase; require fresh evidence that the intended control matches the task | Prevent accidental irreversible actions | Next |
| P1 | **Three-signal verification**: accessibility tree + screenshot availability/hash + expected semantic result | Distinguishes “tap executed” from “goal achieved” | Partial: snapshot and screenshot change checks exist |
| P1 | **Recovery ladder**: reacquire semantic target → refresh tree → wait only if loading evidence exists → scroll/reveal → safe back/reopen; stop after bounded attempts | Reduces loops and random tapping | Partial |
| P1 | **Task-specific adapters** for Twitter/X, Instagram, YouTube Studio, Google Flow, and WhatsApp, each with known page states and success evidence | Fewer model guesses on common workflows | Next |
| P1 | **App-specific memory** keyed by package + screen signature + action outcome, not a single generic lesson bucket | Avoid applying a Chrome lesson to a native app | Next |
| P1 | **Screenshot health telemetry**: capture requested, callback succeeded, bitmap encoded, image size, model received image; distinguish a null screenshot from a bad analysis | Makes screenshot failure visible instead of silent | Next |
| P1 | **Freshness budgets** for node snapshots and web DOM snapshots, with automatic reacquisition before executing stale targets | Prevents old indices/DOM nodes being reused | Partial for phone node actions |
| P2 | **Coordinate calibration checks** using actual screenshot dimensions, rotation/insets, and the known image-to-device transform; disable coordinate taps if calibration is uncertain | Addresses wrong-coordinate taps | Next |
| P2 | **Browser context awareness**: active tab, URL, iframe/dialog context, loading state, popup state, and current page title must be part of web decisions | Reduces wrong-tab and modal confusion | Next |
| P2 | **Task plan with explicit done evidence**: each goal decomposed into steps with a verifiable postcondition and an overall completion test | Stops early/false “Done” claims | Partial |
| P2 | **Confidence threshold**: when two installed apps have similar labels, don't guess; log the candidates and ask or pause safely | Avoids wrong clones/duplicates | Next |
| P2 | **Replayable regression scenarios** based on sanitized screen trees and mocked action results | Lets CI test routing/verification without needing a physical phone | Next |
| P3 | **User-controlled allow/deny rules** for apps, sites, and sensitive actions | Makes autonomy bounded and predictable | Next |

## Required regression cases

- “Open twitter.com” with a Flox/Floks Twitter client installed: open that native client; do not open Chrome.
- “Open Twitter in Chrome” or “visit the Twitter website”: use the web route.
- “Open Floks Twitter”: exact matching installed label wins.
- No matching native app installed: report a blocker; never launch an unrelated app.
- Target app launches, but Chrome unexpectedly takes focus: restore and verify the pinned package before resuming; stop safely if it cannot be restored.
- AI returns a node index from Chrome while the task is pinned to Twitter: reject it and refresh the live tree.
- Target node disappears between AI response and execution: refresh/remap by package and stable label/view ID; do not click stale coordinates.
- Screenshot capture returns null: log capture failure distinctly and do not claim visual analysis occurred.
- Upload/publish result is uncertain: verify status before retrying to avoid duplicate submissions.

## Definition of “verified”

A click or intent launch is only an attempted action. The agent may say the target was opened only after checking the observed foreground package; it may say an upload/message/post succeeded only after checking a relevant visible result state. If evidence is missing or contradictory, the correct result is “not verified” and a bounded recovery or safe pause.
