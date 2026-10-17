# AuraDroid AI — Reliability Roadmap

## Goal

Nax Bot must choose the execution mode and application that match the user's real intent, use fresh screen evidence, avoid accidental side effects, persist task status and never claim success without completion evidence.

## Implemented in reliability-v1 branch

1. **Routing foundation** — deterministic app profiles, URL normalization and explicit phone-intent priority; YouTube upload routes to Studio, and saved Flow URLs are not reused for unrelated tasks.
2. **Popular app guidance profiles** — Chrome, YouTube/Studio, Google Flow, WhatsApp, Instagram, Gmail, Drive, Photos, Maps, Telegram, Facebook, X, Spotify, LinkedIn and Android Settings have dedicated instruction and memory domains. These are policy/profile foundations, not complete end-to-end adapters for every app.
3. **Persistent task ledger** — records bounded task history and statuses; old active tasks are not silently dropped, and an interrupted task is marked PAUSED rather than automatically replayed.
4. **Independent phone DONE verification** — before native phone tasks report success, the agent requests a second screen-evidence check. Sensitive actions need visible outcome evidence or remain unverified.
5. **Web completion evidence** — the WebView agent's existing goal verifier controls its completion state; unsupported DONE decisions are rejected.
6. **Per-app memory domain** — native phone tasks derive the lesson domain from actual foreground package/app profile; web tasks use the selected profile instead of always pulling Google Flow lessons.
7. **Cooperative pause/resume/cancel** — UI controls and step gates stop advancing between actions; resuming performs a new observation on the next step.
8. **Scheduling correction** — the UI calls the registered TaskSchedulerReceiver directly rather than looking for the missing DailyUploadReceiver class. Schedule preferences are saved consistently and an enabled daily alarm is restored after reboot.
9. **Manifest hardening** — cleartext networking and Android backup are disabled. Folder/media permissions still require a broader production and compatibility review.
10. **Regression coverage** — route/URL safety tests were added and CI is configured to run unit tests before building the debug APK on branch pushes and PRs.
11. **Build fix** — the latest main-branch nullable AccessibilityNodeInfo smart-cast compile error had already been fixed and the corresponding main build succeeded before this branch was created.

## P0 — prove the core works (next)

| Item | Test / completion criteria |
|---|---|
| Branch CI | testDebugUnitTest and assembleDebug both pass on the final branch head. |
| Routing regression matrix | Test open WhatsApp, open Chrome, a website URL, upload to YouTube, a Flow request and ambiguous app names. Explicit phone requests must not be redirected into the WebView. |
| Phone-device tests | On real devices, verify foreground-package detection, keyboard/overlay handling, stale-node rejection and coordinates on several screen densities and rotations. |
| Completion verifier | Verify that opening a page, typing a draft, sending a message and uploading a video have different completion evidence. Confirm no success without adequate evidence. |
| Pause and cancel | Pause between steps, ensure no next step is issued, resume using a fresh snapshot, cancel and verify state is persisted. |
| Permissions | Test storage/media selection and Accessibility revocation, and report actionable blockers instead of silent failure. |

## P1 — Chrome/WebView and YouTube workflows

1. Build scenario tests for the exact websites/tasks most frequently used. The first priority is Chrome/WebView and YouTube Studio.
2. Record per step: goal, app profile, URL/package, target label/role, action, before/after state, verification result and recovery outcome.
3. Improve DOM context: current URL/title, loading state, enabled state, dialogs, frames and tab/context identity.
4. Confirm exact video identity before upload, validate title/description/privacy/audience, prevent duplicate submission and require visible final status.
5. Keep the WebView-vs-real-Chrome distinction explicit. The embedded WebView doesn't automatically share Chrome's cookies, active tabs or UI session.
6. Create per-app adapters only where a tested scenario and visible success evidence are defined. App prompt guidance alone is not proof of a working integration.

## P1 — task lifecycle, scheduling and recovery

- Persist task states and bounded diagnostics; never automatically replay an uncertain irreversible action after process death.
- Test daily schedule creation, missing alarm permission, reboot restoration, disabled Accessibility, absent network and a locked phone.
- Consider WorkManager for deferrable persistent work. Device UI interaction must still obey Android foreground and Accessibility constraints.
- Pause/resume must happen at safe step boundaries; a dispatched tap/upload cannot necessarily be recalled.

## P2 — memory and personalized skills

- Store learning per application and screen signature.
- Learn only from verified outcomes; track rule version, confidence, evidence, success/failure and last tested time.
- Require repeated successful regression scenarios before labelling a skill as "live verified".
- Add user-controlled app/site allowlists and confirmation requirements for Send, Publish, Delete, Purchase and other irreversible actions.

## P2 — user experience

- Show task state, current application/site, current step, verification evidence and recovery reason.
- Give a clear safe pause when a target is ambiguous or disappears.
- Add task history and an exportable diagnostic report before adding decorative animations.
- Expand UI polish only after the core scenarios pass repeatedly.

## Known limitations — do not overclaim

- A successful CI build does not validate execution on a real phone.
- Profiles give app-specific routing, memory and model guidance, but most third-party workflows still need live end-to-end testing and likely richer adapters.
- A WebView workflow is not the same as hidden/headless Chrome automation.
- Android cannot run phone-side code while the phone is powered off; exact alarms and accessibility may be restricted by Android/OEM behaviour.
- Zero errors cannot be guaranteed. The objective is measurable reliability, prevention of silent/wrong success, bounded recovery and safe stopping when evidence is ambiguous.

## Required regression examples

- Explicit "open WhatsApp" launches the installed native app and remains in that app.
- "Open the WhatsApp website in a browser" chooses the web path.
- "Upload my newest video to YouTube" starts at YouTube Studio and does not say success until visible upload evidence exists.
- An unrelated web task never reuses the pinned Google Flow URL.
- Unsafe URL schemes and local/private-network destinations are rejected by the route URL normalizer.
- A stale native or DOM target causes a fresh lookup instead of tapping an old index.
- A screenshot capture failure blocks coordinate-only phone taps.
- If an upload/message outcome is uncertain, it is not repeated blindly.
- A process interruption marks a task paused; the task is not automatically rerun.
- Pause prevents the next step from dispatching; resume refreshes screen state first.

## Definition of verified completion

An action was **attempted** when it was dispatched. A step is **verified** only when fresh evidence supports its expected result. A task is **completed** only when task-specific completion evidence exists. If that evidence is missing or contradictory, the correct state is PAUSED/FAILED and the UI must show why rather than claim success.
