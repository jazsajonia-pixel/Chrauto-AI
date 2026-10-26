# Scheduling, background execution, and Viber

## How a scheduled task runs

1. Tasks live in `files/tasks.json` (atomic write: temp file + rename). Each one stores id, title, kind, instruction or Viber batch, scheduled time, time zone, status, created/updated/started/finished times, attempts, last result, last error, a "waiting for" reason, and up to 40 history events.
2. One `AlarmManager` alarm is armed for the earliest scheduled task (`TaskScheduler.sync`). `setExactAndAllowWhileIdle` is used when exact alarms are allowed (always below Android 12, or when the user grants "Alarms & reminders"); otherwise `setAndAllowWhileIdle`, which Android may defer. The UI states which one applies.
3. The alarm receiver calls `TaskRunner.kick`. `TaskRunner` is single-flight: one engine job at a time, and a task is claimed (SCHEDULED/WAITING to RUNNING) under the store lock, so two triggers can never run the same task.
4. Before running, `DeviceConditions` checks: accessibility service connected, screen interactive, keyguard unlocked, and (for AI tasks) network. If something is missing, the task becomes **Waiting for device** with the reason. It resumes on unlock (`ACTION_USER_PRESENT`, received by the running accessibility service), when the service reconnects, when the app is opened, or on a 15-minute fallback alarm while waiting for network. After 24 hours it fails with the reason.
5. No extra foreground service is used. While the accessibility service is enabled, Android keeps the app's process bound, and the task runs inside it.

## Recovery

- App start (`ChrautoApp`), reboot, app update, clock change, time-zone change, and exact-alarm permission change re-arm the alarm (`TaskBootReceiver`).
- A task found as **Running** in a fresh process was interrupted. It becomes **Needs attention**, and is not re-run automatically. A Viber message found as **Sending** becomes **Unconfirmed**.
- Automatic retries happen only for temporary AI errors (network, timeout, rate limit) that occurred before any action was taken, at most 3 attempts, 5 minutes apart. Everything else needs a manual Retry.

## Viber group messages

Viber has no public API for sending into a regular group from another app, and Chrauto does not claim one. The feature drives the normal Viber app:

1. `ACTION_SEND` / `ACTION_SEND_MULTIPLE` to `com.viber.voip` with the text and images (shared read-only through a `FileProvider`).
2. Wait for Viber, find the group by exact, case-insensitive name. Zero or several matches stops with "Not sent". If needed, the search box is used.
3. The message is persisted as **Sending** before the chat is tapped (in some Viber versions picking a chat in the share sheet sends immediately).
4. Assisted mode (default): the user taps Send; Chrauto watches for up to 2 minutes for the text to appear in the chat.
5. Auto mode: Chrauto taps the single, clearly identified Send button once, then looks for the text in a chat bubble for 10 seconds.
6. Outcomes: **Sent** (text seen in a bubble, not only in the composer), **Unconfirmed** (may have been sent; never retried automatically), **Failed** (definitely not sent; for example the text is still in the composer).

Failure policy: the batch stops at the first Failed or Unconfirmed message. Messages after it stay Pending. Retry re-queues only Failed and Cancelled messages. Unconfirmed messages must be checked in Viber and marked Sent or Not sent.

### Still requires the user

- Viber installed, signed in, and already a member of the group.
- The phone unlocked with the screen on at send time (Android does not allow accessibility automation behind the lock screen).
- Assisted mode: tapping Send.
- Resolving Unconfirmed messages.
- Image-only messages cannot be confirmed automatically and always end as Unconfirmed.

### Not yet verified

All Viber screen-recognition logic is covered by JVM tests with scripted screens only. It has not been run against the real Viber app. View ids, labels, and whether the share sheet sends on tap may differ between Viber versions.

## TECNO phone test checklist (4 GB RAM)

1. Install `chrauto-ai-v0.6.0.apk`, open the app, allow notifications.
2. Settings: Accessibility, Installed apps, Chrauto AI, turn on. On TECNO/HiOS also allow Auto-start and set Battery to "No restriction" for Chrauto (Phone Master can stop background apps).
3. Allow "Alarms & reminders" when the app offers it; the status chip changes to "Exact scheduling".
4. Run "Open Settings" now. Expect: Running, then Completed, no UI freeze while it reads the screen.
5. Schedule "Open Settings" 3 minutes ahead, lock the phone. Expect: Waiting for device (Phone is locked). Unlock: it runs and completes.
6. Schedule a task 3 minutes ahead, then swipe Chrauto away from recents. Expect it still runs on time (the accessibility service keeps the process).
7. Reboot with a task scheduled for later. Expect it still listed as Scheduled and running at its time after unlock.
8. Press Stop during a run. Expect Cancelled within about a second.
9. Viber, assisted mode: create a test group with one other person, schedule 2 text messages. Expect Viber to open on that group with the text ready; send it yourself; the message becomes Sent.
10. Viber, auto mode: same with "Tap Send for me". Expect exactly one copy of each message in the group.
11. Rename the group so the name no longer matches. Expect Failed with "No chat is named exactly".
12. Report each result (and the Viber version) back so the handoff can be updated.
