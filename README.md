# AuraDroid AI — Nax Bot

AuraDroid AI is an Android/Kotlin prototype for an AI-assisted phone and web task agent. It combines a Gemini-backed planner, Android AccessibilityService actions, an embedded WebView automation workflow, media/YouTube workflows, saved task state and diagnostic logs.

> Reliability note: this is an evolving automation prototype, not a guarantee that every third-party app workflow will succeed. Website layouts, sign-in challenges, permissions, device manufacturers and Android background limits can interrupt tasks. The agent should report "not verified" rather than claim success without evidence.

## Current architecture

- **Phone agent** — selects Android accessibility nodes, checks for stale targets and foreground-app drift, and pauses on repeated no-progress actions.
- **Web agent** — works through AuraDroid's shared embedded WebView using live DOM snapshots. It is not a hidden/headless browser and does not share the signed-in state of the separately installed Chrome app.
- **AI decision layer** — Gemini-backed next-step planning, with app-specific guidance injected for common applications.
- **Routing and app profiles** — deterministic URL handling and profiles for Chrome, YouTube/Studio, Google Flow, WhatsApp, Instagram, Gmail, Drive, Photos, Maps, Telegram, Facebook, X, Spotify, LinkedIn and Android Settings. These profiles guide routing, memory and decision policy; they are not all complete app-specific integrations.
- **Task state** — bounded persistent run history with RUNNING/VERIFYING/PAUSED/COMPLETED/FAILED/CANCELLED states. Interrupted work is marked paused rather than automatically replaying potentially irreversible actions.
- **Verification** — phone completion gets an independent second-pass screen check; web completion uses visible DOM evidence. Uncertain outcomes are not reported as success.
- **Pause/resume/cancel** — cooperative controls act between execution steps. An already-dispatched action may finish before pause takes effect.
- **Scheduling** — the daily task uses the implemented TaskSchedulerReceiver; an enabled schedule is re-armed after reboot. Android must still permit the alarm, service and required app interaction at run time.
- **CI** — GitHub Actions runs routing/URL regression unit tests and builds a debug APK.

## Build

GitHub Actions is the source of truth for the currently committed build.

Locally, with JDK 17 and Gradle 8.6 installed:

    gradle testDebugUnitTest
    gradle assembleDebug

Debug APK output: app/build/outputs/apk/debug/app-debug.apk.

## First-time setup

1. Install the debug APK on a test device.
2. Add a valid Gemini API key in Settings. Use a current model ID actually available to that API key; a UI default is not proof of availability.
3. Enable AuraDroid's Accessibility Service from Android Settings only after reviewing the permissions it grants.
4. Grant only the media/folder access needed for the test workflow.
5. Start with harmless tests: open a known app, read the visible screen, enter text into a test field, and verify the result.
6. Test YouTube upload with a non-sensitive video and explicit privacy/audience settings before scheduling regular uploads.

## Safety and known limits

- A completed build proves compilation, not successful operation on a physical Vivo/Android device. Screen/OEM tests are still required.
- App profiles mostly supply routing/memory/decision guidance. Third-party apps still need live end-to-end tests and may require app-specific adapters for reliable actions.
- The web agent is a WebView workflow. It cannot silently operate the actual Chrome tab/session as though the WebView and Chrome were the same browser.
- Login prompts, CAPTCHA, consent dialogs, changing UI layouts, permission dialogs and unavailable network services may require user action.
- Background tasks cannot execute while the phone is powered off. After reboot, this app only re-arms an enabled alarm; the task is not blindly auto-replayed.
- Pause is cooperative: it takes effect between steps and does not undo an action already dispatched.
- Never store or upload API keys in source control. Before production, review all sensitive permissions, API-key storage, logging, dependency versions, privacy disclosures and distribution requirements.
- Avoid automatically repeating uploads, sends, payments, deletions or publications when their result is uncertain.

## Reliability development order

1. Get a green unit-test + debug-build CI run on every change.
2. Test the Chrome/WebView and YouTube upload flows end to end on the target Android device.
3. Record structured per-step input, selected target, action, verification evidence and failure reason.
4. Add true app-specific workflows incrementally based on real usage data.
5. Expand background execution and recovery only within Android's permitted lifecycle and permission model.
