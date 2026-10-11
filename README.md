# Chrauto AI

Chrauto AI is a native Android computer-use agent MVP. It accepts a natural-language task, sends the task and the current Android AccessibilityService tree to a configured Gemini model, validates one structured action locally, executes supported actions on the real device, re-observes the screen, and reports only verified completion.

## Current implementation

The app contains a Jetpack Compose control screen, a real Gemini Developer API adapter, strict action parsing/validation, an AccessibilityService that serializes visible nodes and dispatches semantic click/text/scroll/back actions, a bounded cancellable observe-plan-act-observe loop, and safety pauses for consequential tasks. The model cannot run shell commands or arbitrary code. AGP 9's built-in Kotlin support is used; only the Compose compiler plugin is applied separately.

The test suite includes JVM protocol/parser tests, Compose UI instrumentation tests for validation, injected run/completion state, cancellation, and accessibility setup, plus Espresso instrumentation tests that dispatch tap, text, scroll, and back actions through real `AccessibilityNodeInfo` objects from an Android view fixture. GitHub Actions enables KVM and runs the instrumentation suite on an API 35 Google APIs `pixel_7_pro` emulator. Instrumentation XML/HTML reports are uploaded as the `android-test-reports` artifact even when tests fail.

The provider default is `gemini-3.8-flash`, selected because the current official Gemini documentation lists it as a stable multimodal model with structured-output support. Gemini limits are project/model dependent and the free tier is not guaranteed; check AI Studio before testing. The API key is entered on the device and never committed or printed in logs.

## What's new in 0.2.0

- **Fixed Gemini HTTP 400** (`Invalid value at generation_config.response_schema.properties[..].value.type`). The request schema was putting Kotlin arrays (`arrayOf("string","null")`) into `org.json`, which serialised them as `[Ljava.lang.String;@...`. Gemini's `responseSchema` uses the OpenAPI subset, so nullable fields now use `"nullable": true`. JSON `null` values in replies are treated as missing, and regression tests cover both.
- **Smarter agent loop**: rejected or failed steps are fed back to the model so it can retry (up to 3 times) instead of failing the whole run. Taps on text labels go to the nearest clickable parent, the model sees the list of installed apps and recent steps, and Android 11+ package visibility is declared so `open_app` can find Chrome.
- **New UI**: a setup checklist (API key saved encrypted on the device, model chips, accessibility, microphone), quick task chips, a live status card with a step-by-step activity log, and a launcher icon.
- **Floating assistant** (drawn as an accessibility overlay, so it needs no extra permission): a draggable mascot bubble. Tap it and a small bottom panel pops open with **Voice command**, **Stop**, **Repeat last task**, **Open app**, and **Hide**. The bubble's face changes while it listens, thinks, works, finishes, or gets stuck, and it can talk back (you can turn this off).
- **Cursor buddy**: while a task runs, a little mascot flies to each element before tapping, typing, or scrolling, shows a target ring and a tap ripple, and shows a "…" loader while it's thinking. The agent acts through accessibility node actions, not screen-coordinate taps, and the cursor window can't be touched, so neither overlay can be clicked by accident.

## Download

Every push to `main` that passes the tests publishes `chrauto-ai-debug.apk` to the **latest-debug** release on the Releases page.

## Build from a phone with GitHub Actions

Push the repository to GitHub and open **Actions → Android Debug APK → Run workflow**. The workflow installs JDK 21 and Android API 36, runs `testDebugUnitTest`, builds `assembleDebug`, and uploads `app-debug.apk` as the `chrauto-ai-debug-apk` artifact. The app uses Compose BOM 2026.06.00 because the newer 2026.09 BOM requires compile SDK 37, which was not available from the GitHub runner SDK repository during this build. Download the artifact from the successful run, then install the APK on the Android phone. The local sandbox did not have an Android SDK or Gradle installation at implementation time, so the cloud workflow is the authoritative build path.

## Phone setup

1. Install and launch the APK.
2. Create a Gemini API key in Google AI Studio and enter it in the app. Do not paste it into GitHub, issues, or chat logs.
3. Tap **Open Accessibility Settings**, enable **Chrauto AI Accessibility**, and acknowledge Android's disclosure. This permission allows the service to inspect visible accessibility nodes and perform supported UI actions on the user's behalf.
4. Return to the app, use the sample Chrome task, and tap **Run**. Chrome must be installed and the device may need to be unlocked. The service does not bypass permissions, authentication, CAPTCHA, or protected fields.
5. Tap **Stop** at any time to cancel the coroutine and prevent new actions.

## Tests and limitations

Automated JVM tests cover action validation, stale targets, malformed/unknown model output, safety confirmation, and provider parsing. A real-device Chrome acceptance test has not been honestly claimed from this sandbox because no Android emulator/device is attached and no Gemini key was supplied. The GitHub Actions artifact proves compilation/tests only; it does not prove device behavior. Accessibility trees vary by app, custom-rendered controls may be inaccessible, and model quotas/network failures are surfaced as failures rather than success.

Official references used for the implementation: [Android AccessibilityService](https://developer.android.com/guide/topics/ui/accessibility/service), [AccessibilityNodeInfo](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo), [Compose setup](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler), [Gemini structured output](https://ai.google.dev/gemini-api/docs/generate-content/structured-output), [Gemini models](https://ai.google.dev/gemini-api/docs/models), and [Gemini rate limits](https://ai.google.dev/gemini-api/docs/rate-limits).
