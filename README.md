# Chrauto AI

Chrauto AI is a native Android computer-use agent MVP. It accepts a natural-language task, sends the task and the current Android AccessibilityService tree to a configured Gemini model, validates one structured action locally, executes supported actions on the real device, re-observes the screen, and reports only verified completion.

## Current implementation

The app contains a Jetpack Compose control screen, a real Gemini Developer API adapter, strict action parsing/validation, an AccessibilityService that serializes visible nodes and dispatches semantic click/text/scroll/back actions, a bounded cancellable observe-plan-act-observe loop, and safety pauses for consequential tasks. The model cannot run shell commands or arbitrary code. AGP 9's built-in Kotlin support is used; only the Compose compiler plugin is applied separately.

The provider default is `gemini-3.8-flash`, selected because the current official Gemini documentation lists it as a stable multimodal model with structured-output support. Gemini limits are project/model dependent and the free tier is not guaranteed; check AI Studio before testing. The API key is entered on the device and never committed or printed in logs.

## Build from a phone with GitHub Actions

Push the repository to GitHub and open **Actions → Android Debug APK → Run workflow**. The workflow installs JDK 21 and Android API 37, runs `testDebugUnitTest`, builds `assembleDebug`, and uploads `app-debug.apk` as the `chrauto-ai-debug-apk` artifact. Download the artifact from the successful run, then install the APK on the Android phone. The local sandbox did not have an Android SDK or Gradle installation at implementation time, so the cloud workflow is the authoritative build path.

## Phone setup

1. Install and launch the APK.
2. Create a Gemini API key in Google AI Studio and enter it in the app. Do not paste it into GitHub, issues, or chat logs.
3. Tap **Open Accessibility Settings**, enable **Chrauto AI Accessibility**, and acknowledge Android's disclosure. This permission allows the service to inspect visible accessibility nodes and perform supported UI actions on the user's behalf.
4. Return to the app, use the sample Chrome task, and tap **Run**. Chrome must be installed and the device may need to be unlocked. The service does not bypass permissions, authentication, CAPTCHA, or protected fields.
5. Tap **Stop** at any time to cancel the coroutine and prevent new actions.

## Tests and limitations

Automated JVM tests cover action validation, stale targets, malformed/unknown model output, safety confirmation, and provider parsing. A real-device Chrome acceptance test has not been honestly claimed from this sandbox because no Android emulator/device is attached and no Gemini key was supplied. The GitHub Actions artifact proves compilation/tests only; it does not prove device behavior. Accessibility trees vary by app, custom-rendered controls may be inaccessible, and model quotas/network failures are surfaced as failures rather than success.

Official references used for the implementation: [Android AccessibilityService](https://developer.android.com/guide/topics/ui/accessibility/service), [AccessibilityNodeInfo](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo), [Compose setup](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler), [Gemini structured output](https://ai.google.dev/gemini-api/docs/generate-content/structured-output), [Gemini models](https://ai.google.dev/gemini-api/docs/models), and [Gemini rate limits](https://ai.google.dev/gemini-api/docs/rate-limits).
