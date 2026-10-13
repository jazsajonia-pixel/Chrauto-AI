# Screen-Aware Game Agent: Implementation Specification

## Goal
Enable explicit, bounded, user-authorized game tasks using semantic accessibility data plus fresh screenshot observations, while preserving existing general-purpose app automation. Improve recovery and reduce overlay footprint. Implementation status (feature branch): explicit consent UI, a MediaProjection foreground service, ephemeral downscaled JPEG capture, Gemini inline-image input, and coordinate taps are now implemented. Gesture/recovery and the instrumentation/device verification matrix remain partial; this feature branch has not yet been validated on a physical device. This document remains the acceptance checklist and does not imply a release APK.

## Platform and permission boundary
- Keep screenshot automation opt-in per task/session. Request Android `MediaProjection` consent through an Activity result flow; do not silently capture. Show a persistent, clear running indicator and a one-tap stop control. Stop capture on task completion, cancellation, service teardown, or projection revocation.
- Use a foreground service with the applicable media-projection foreground-service type/permission for the app's target SDK. Follow the current Android platform requirements, and test on supported Android/API levels before release.
- Downscale/compress frames on-device, do not persist them, and transmit only to the user's configured vision-capable provider when the user has explicitly enabled screenshot mode. Make provider capability and data transmission clear in UI.
- Accessibility node text and screenshots may contain private data. Minimize collected content and do not log frames, credentials, or full prompt payloads.
- Android `AccessibilityService.dispatchGesture` supports gesture strokes and continuation of a stroke, but it is not a general-purpose API for arbitrary independent simultaneous multi-touch fingers. Advertise only supported single-pointer paths/holds and supported multi-stroke behavior; report unsupported gestures clearly. Do not use hidden APIs or root-only input injection.

## Architecture
1. Add a session owner for MediaProjection. UI obtains consent; a foreground capture component owns `VirtualDisplay`/`ImageReader`, produces a bounded-size JPEG/bitmap frame, closes `Image`/`HardwareBuffer` resources promptly, and exposes cancellable `captureFrame()` to the device bridge.
2. Extend `UiObservation` with optional image payload/metadata that is not included in `compactText()` or logs. Preserve current node-only paths when capture is disabled.
3. Introduce a provider capability interface and image-aware planning request. Only providers explicitly supporting images may accept screenshot mode. Implement Gemini inline image input with the existing text instructions and strict JSON action schema. Other providers remain on node-only mode until properly implemented; reject unsupported mode rather than dropping images invisibly.
4. Add `TapAt(x,y)` and `GesturePath(points,duration)` actions. Validate finite coordinates, screen bounds, point count, duration and maximum path length. Require an opt-in game session for coordinate actions; use confidence thresholds and ask for help on uncertain targets. Re-observe after each action.
5. For game screens without accessibility semantics, pass a reduced screenshot plus available nodes and ask the vision model to describe relevant controls/state and select one action. Do not infer a button from appearance alone at high consequence areas (purchase, ad, external navigation, account/security); pause for user input.
6. Recovery: apply per-provider-call timeout; propagate cancellation; enforce max task duration/action/request count. For transient provider/network failures, use bounded exponential backoff, then at most configured task restarts (default 1, hard cap 3). Re-capture/re-observe at restart, clear stale target IDs, explain recovery in status, and stop cleanly at limit. Detect no-progress from stable observation fingerprints, allow at most a few alternate actions, then fail with a useful reason. Never retry a consequential action blindly because its result may have succeeded.
7. Overlay: change dock mascot from 62dp to about 48dp, reduce padding/status width and button dimensions proportionally, preserve touch target/accessibility labels and expand interaction. Keep cursor buddy visually small and do not block game controls. Add a user option to hide/toggle cursor overlay during gameplay.

## Suggested code touchpoints
- `agent/ActionProtocol.kt`: observation metadata, coordinate/gesture actions and validation.
- `accessibility/AccessibilityAgentService.kt`: capture bridge and supported gestures.
- New capture/session service plus `MainActivity` consent launcher and manifest/service config.
- `provider/GeminiProvider.kt`: image-aware request using supported inline media part; preserve JSON schema and timeout.
- `agent/AgentOrchestrator.kt`: timeout, explicit recovery limits, cancellation and state reporting.
- `data/SettingsStore.kt` / Compose settings: screenshot mode and bounded retry settings with safe defaults.
- `overlay/AssistantOverlay.kt`: smaller mascot dock and gameplay-friendly overlay toggle.

## Acceptance tests
- Unit tests cover retry caps/backoff behavior, cancellation during model call/action/capture, stale observations, no-progress detection, coordinate bounds, gesture limits, and safety pauses.
- Provider tests inspect serialized image-aware request shape without making live network calls.
- Instrumentation tests cover MediaProjection consent/revocation, orientation/resolution changes, stopping capture, screenshot resource release, and app lifecycle.
- Device tests cover button-like game controls, canvas screens, unavailable/unsupported gestures, small screens, overlay pass-through, and explicit stop.
- Verify existing node-only tasks work unchanged with capture disabled.
- Run `./gradlew testDebugUnitTest assembleDebug` and relevant connected instrumentation tests; inspect APK install/run behavior. Do not call the feature complete until these pass and a reviewer checks privacy/permission disclosures.

## Release boundary
A source change or spec on a branch is not an APK. Build/sign/publish only through the repository's intended CI/release process and only after checks pass. No APK is produced by this specification.