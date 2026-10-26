# Chrauto AI: notes for AI agents

- Chrauto is the phone-automation app (AccessibilityService agent, scheduled tasks, Viber assisted sending, Chron voice assistant). It uses cloud AI providers only. Do not add local-model inference here; that belongs to Chron-Launcher. Chrono (mobile dev environment) is a separate app too.
- Build: `./gradlew testDebugUnitTest assembleDebug`. CI ("Android Debug APK") also runs instrumentation tests on an API 35 emulator and `scripts/ci-screenshots.sh`.
- Releases: bump `versionCode`/`versionName` in `app/build.gradle.kts`, add `.github/release-notes/vX.Y.Z.md`, merge to main, then push tag `vX.Y.Z`.
- Safety rules: never tap Send/Pay/Delete-like targets twice automatically; never auto-retry a message whose status is Unconfirmed; never report success without evidence on screen.
- Task scheduling and Viber design: `docs/SCHEDULING_AND_VIBER.md`.
