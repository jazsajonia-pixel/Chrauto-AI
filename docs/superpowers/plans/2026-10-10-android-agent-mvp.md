# Android Computer-Use AI Agent MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a native Android MVP that sends the user task plus real AccessibilityService observations to Gemini, validates one bounded action at a time, executes real Android UI actions, re-observes, and reports only verified outcomes.

**Architecture:** A single Android app module uses Jetpack Compose for the control UI, a process-safe `AgentRuntime` state holder, a `GeminiProvider` adapter using HTTPS REST, a typed action protocol with local validation, and an `AccessibilityService` bridge exposing current tree snapshots and serialized actions. The first vertical slice supports observe, open app, tap, type text, scroll, back, wait, ask_user, finish, and report_error; the model never receives shell or unrestricted system authority.

**Tech Stack:** Kotlin 2.4.10, Android Gradle Plugin 9.4.0, compile/target SDK 36 (API 37 was not available from the GitHub runner's SDK repository), min SDK 26, Jetpack Compose BOM 2026.09.00, Material 3, Kotlin coroutines, AndroidX lifecycle/activity, Gemini Developer API `gemini-3.8-flash` with JSON-schema structured output.

**Spec:** `MANUS_BUILD_PROMPT.md`, `DEVELOPMENT_PHASES.md`

## Global Constraints

- Use Kotlin and Jetpack Compose for the native Android application.
- The MVP must use a real AI model, real accessibility observation, real device interactions, verification, cancellation, and honest reporting.
- Require explicit Accessibility Service activation; do not bypass authentication, CAPTCHA, permission dialogs, or protected fields.
- Store provider configuration locally without logging API keys or sensitive screen contents.
- Validate every model action locally and execute at most one action per model response.
- Build a debug APK in GitHub Actions and upload it as an artifact.

## Review Focus

- Missing/disabled AccessibilityService: Run must fail with setup guidance and no actions dispatched; unit test the guard.
- Malformed/unknown model output: parser/validator must reject it with an actionable error; unit test unknown action and invalid target.
- Stale or inaccessible targets: action engine must refuse unsafe dispatch and re-observe; unit test target mismatch.
- Cancellation and limits: cancellation must stop new model calls/actions and bounded limits must terminate; unit test both.
- Consequential actions: send/publish/purchase/delete/security actions must produce `ask_user`, not execute; unit test authorization.

---

### Task 1: Phase 0 Gradle and cloud-build foundation

**Files:** Create Gradle settings, root build files, `gradle.properties`, Gradle wrapper, `app/` module, manifest/resources, and `.github/workflows/android.yml`; modify README with build/install setup and source citations.

**Produces:** A reproducible Android project that compiles an empty Compose activity and runs unit tests in GitHub Actions; debug APK artifact upload.

- [ ] Create the Gradle project with version-pinned plugins and Compose dependencies from verified official docs.
- [ ] Add the accessibility service declaration with `canRetrieveWindowContent=true` and `canPerformGestures=true`.
- [ ] Add workflow steps for JDK 21, Android SDK, unit tests, `assembleDebug`, and artifact upload without secrets.
- [ ] Run `./gradlew testDebugUnitTest assembleDebug` locally if tooling is available; otherwise verify through GitHub Actions.

### Task 2: Typed action protocol and deterministic validation

**Files:** `app/src/main/java/.../agent/ActionProtocol.kt`, `ActionValidator.kt`, and unit tests.

**Produces:** Serializable observation/action models, strict action enum, target references, limits, and safety authorization.

- [ ] Write tests for valid actions, unknown actions, malformed JSON, invalid coordinates, stale target IDs, and consequential confirmation.
- [ ] Implement `AgentAction`, `ActionPlan`, `UiObservation`, `UiNodeSnapshot`, `ActionResult`, and `ActionValidator` with explicit bounds and one-action execution.
- [ ] Run protocol tests and confirm failures are represented as errors rather than success.

### Task 3: Real AccessibilityService observation and interaction bridge

**Files:** `AccessibilityAgentService.kt`, `AccessibilityBridge.kt`, `AccessibilityObservation.kt`, service XML/resources, and tests for pure node matching/serialization.

**Produces:** Actual current-window tree snapshots and real node actions for click/type/scroll/back/wait plus app-launch intent handling.

- [ ] Traverse `rootInActiveWindow`, capture text/content descriptions/class, clickable/editable/scrollable flags, actions, bounds, package, and stable per-observation IDs while redacting password fields.
- [ ] Dispatch semantic node actions via `performAction`; use `performGlobalAction` for back and `dispatchGesture` only for justified coordinate gestures.
- [ ] Return dispatch and post-action observation results to the runtime; expose service readiness and settings intent to the UI.
- [ ] Test pure observation redaction, candidate matching, and stale-target rejection.

### Task 4: Gemini provider adapter and structured action parsing

**Files:** `GeminiProvider.kt`, `ProviderModels.kt`, `GeminiJsonParser.kt`, networking utilities, and unit tests.

**Produces:** Real HTTPS calls to Gemini `generateContent` with task + current observation and JSON schema for one action; timeout, HTTP, quota, and malformed-output errors.

- [ ] Write parser tests for valid plans, schema violations, unknown actions, empty response, and provider errors.
- [ ] Implement API-key header handling from runtime config, bounded request timeout, compact observation prompt, JSON-schema response configuration, and semantic validation.
- [ ] Keep model ID configurable with default `gemini-3.8-flash`; document free-tier variability and rate-limit handling.

### Task 5: Bounded observe-plan-act-observe orchestrator

**Files:** `AgentOrchestrator.kt`, `AgentRuntime.kt`, `AgentState.kt`, and unit tests.

**Produces:** End-to-end cancellable loop with max actions/model requests/duration, repeated-failure detection, confirmation pause, verification, and honest statuses.

- [ ] Write fake-provider/fake-bridge tests for multi-step continuation, completion only after matching observation, cancellation, limits, repeated failures, and ask_user.
- [ ] Implement the loop so every action is validated, dispatched through the bridge, followed by observation, and logged with outcome.
- [ ] Reject success messages that have no verified state evidence; surface blocked/failed/needs-assistance states.

### Task 6: Functional Compose UI and secure configuration

**Files:** `MainActivity.kt`, Compose screens/components, `AppPreferences.kt`, and UI tests where practical.

**Produces:** Task input, provider/model/key configuration, Run/Stop, live status/log, accessibility setup guidance, confirmation dialog, and error recovery.

- [ ] Add local preferences storage for provider/model/key with no log output of the key; explain device-local secret limitations.
- [ ] Wire UI to the runtime and launch Android Accessibility Settings; disable Run while active or unconfigured.
- [ ] Add persistent running indicator and immediate Stop control.

### Task 7: Verification, documentation, and cloud build

**Files:** README, `docs/TEST_RESULTS.md`, workflow as needed.

**Produces:** Phone-first installation/setup/test guide and truthful acceptance report.

- [ ] Run all automated tests and debug build; record exact results.
- [ ] Trigger GitHub Actions, inspect workflow logs, and record the APK artifact URL/run.
- [ ] Test missing permission, invalid key, malformed model output, cancellation, and confirmation flow in automated tests; document that real-device Chrome acceptance requires the user’s Android device and configured Gemini key.
- [ ] Do not mark the Chrome scenario complete unless an actual device/test environment executes and verifies it.
