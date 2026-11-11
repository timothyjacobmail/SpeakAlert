<p align="center">
  <img src="store/play_store_icon.png" width="120" alt="SpeakAlert icon">
</p>

<h1 align="center">SpeakAlert</h1>

<p align="center">
  Talking reminders for Android, in your own voice or text-to-speech.<br>
  Offline, no account, no ads.
</p>

<p align="center">
 
  <a href="https://ghostgramlabs.com/SpeakAlert/privacy.html">Privacy policy</a> ·
  <a href="LICENSE">Apache 2.0</a>
</p>

<p align="center">
  <a href="https://github.com/ghostgramlabs/SpeakAlert/actions/workflows/ci.yml"><img src="https://github.com/ghostgramlabs/SpeakAlert/actions/workflows/ci.yml/badge.svg" alt="CI status"></a>
</p>

<p align="center">
  <img src="store/screenshots/01_hero.png" width="200" alt="Home screen">
  <img src="store/screenshots/02_voice.png" width="200" alt="Recording a voice reminder">
  <img src="store/screenshots/03_alert.png" width="200" alt="Full-screen alert">
  <img src="store/screenshots/06_repeat.png" width="200" alt="Repeat options">
</p>

Most reminder apps post a silent notification. SpeakAlert plays the reminder out loud, so you
hear it even when the phone is in your pocket or across the room.

## Features

- **Reminders that talk.** Record your voice, type text for text-to-speech, or pick any audio
  file. Speech uses the reminder's own language (a Hindi reminder is spoken in Hindi).
- **Flexible repeats.** Daily, weekly, monthly, yearly, or custom intervals (every 30 minutes,
  every 3 days). Repeats can end never, on a date, or after a number of times.
- **Reminds until done.** An optional follow-up check re-alerts until you mark the reminder Done.
  Full-screen lock-screen alerts have Done, Silence and Snooze buttons.
- **Reliable delivery.** Exact alarms, rescheduling after reboot, a missed-reminder inbox,
  tone-only mode, optional Do Not Disturb bypass, and battery-optimisation guidance for
  restrictive phones.
- **Private playback.** Play reminders through the earpiece or Bluetooth headphones.
- **Shake to record.** Enable listening in Settings, then shake firmly twice to record even
  while locked. Distinct sounds confirm starting and stopping. Configure single, double and
  triple gesture actions, edit the reminder time by shaking, and check the recent action log.
- **Widgets and Wear OS.** A quick-add widget, an upcoming-reminders widget, and a Record
  reminder widget: tap to record, stop, then choose 5, 10, 15, or 30 minutes, or 1 hour to save.
  Also supports alerts on Wear OS watches.
- **Backup and restore.** Exports every reminder, voice recordings included.
- **12 languages.** English, Spanish, Hindi, Arabic, Portuguese (Brazil), Russian, Vietnamese,
  Bengali, Telugu, Tamil, Greek and Malayalam, with right-to-left layout support.
- **Fully offline.** No account, no analytics, no network calls for reminders. Data stays on
  the device.

## Shake recording

Open **Settings > Shake to record > Open recordings and controls**, grant microphone and
notification access, then tap **Enable listening** before locking the phone. One gesture is
two firm movements within 1.2 seconds. Repeat the complete gesture to make a double or triple;
an action runs after the grouping pause (1.4 seconds by default), so a double does not also
run the single action. Four or more gestures in one group are ignored.

The default assignments are:

| When | Single gesture | Double gesture | Triple gesture |
| --- | --- | --- | --- |
| Ready | Start recording | Nothing | Nothing |
| Recording | Stop recording | Nothing | Nothing |
| Within 10 seconds after stopping | Enter time editing | Nothing | Finish and schedule |
| Minutes mode | Add 5 minutes | Switch to Hours mode | Finish and schedule |
| Hours mode | Add 30 minutes | Switch to Minutes mode | Finish and schedule |

The initial time is five minutes after recording stops. Switching modes does not add time.
Pause for 10 seconds to finish automatically, or use **Finish and schedule reminder**.
The completed audio is saved immediately; the reminder is inserted and scheduled once the
entry/edit window ends. **Stop listening** also finishes the pending reminder immediately.
The screen and ongoing notification show the mode, step and due time during editing.
The notification updates silently as gestures are recognized, recording starts/stops, modes
change and the reminder is saved. It shows the last action and final due time without making
a notification sound or vibration. Recording/edit feedback remains separately configurable.

Open **Customize shake actions** to change all assignments, the default delay, both increments,
entry/idle windows, gesture grouping, cooldown, force threshold, sounds and vibration. Changes
apply when listening is enabled again. Add, subtract, reset, switch and finish can be assigned
independently in each editing mode. Subtraction keeps at least one minute in the future;
additions are capped at seven days. If editing outlasts the due time, finishing schedules it
at least five seconds ahead. **Recent shake actions** keeps the last 30 recognized groups and
saves in memory for checking your settings; it contains no audio and can be cleared.

A short beep and vibration confirm starting; a different sound and longer vibration confirm
stopping. While editing, a short cue indicates Minutes mode and a double cue indicates Hours
mode. Sounds follow notification volume and silent-mode settings. Each take is limited to
five minutes. Tap **View reminder** or the updated notification after scheduling to preview
it or change its time. Your default follow-up setting also applies.

Listening stays active only for the current service session. Enable it again after a restart,
force-stop, or system termination. Wake-up accelerometers are preferred; other devices need
a partial wake lock while listening, which increases battery use. Device battery restrictions
can still interrupt listening. If automatic reminder creation fails, the completed take stays
in the inbox for recovery, alongside older unassigned recordings. Canceling a recovery edit
keeps the original. If the reminder is saved but scheduling fails, open that reminder to set
its time again. An abrupt process termination during capture can leave an incomplete take,
which is not shown in the inbox. If the process is killed during time editing, the completed
recording remains in the recovery inbox; an unfinished time selection is not restored.

The suggested [d3xvn/shake](https://github.com/d3xvn/shake) package targets Flutter. SpeakAlert
uses an independent Kotlin accelerometer detector with threshold, peak spacing and cooldown
instead of introducing a Flutter runtime. See [Android microphone foreground-service
restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
for why listening must first be enabled while the app is visible.

## Building

Requirements:

- Android Studio (Ladybug or newer) or the Android SDK command-line tools
- JDK 17
- Android SDK 36

```bash
git clone https://github.com/ghostgramlabs/SpeakAlert.git
cd SpeakAlert
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
```

Debug builds use the app ID `com.ghostgramlabs.speakalert.debug` and launcher name
**SpeakAlert Debug**, so they install alongside the release app with separate data and
permissions. Existing reminders remain in the release app.

The debug APK lands in `app/build/outputs/apk/debug/`. Install it on a device or emulator
running Android 8.0 (API 26) or later:

```bash
./gradlew installDebug
```

Debug builds need no configuration. Only release builds need a signing key (see
[Release signing](#release-signing)).

### Running tests

```bash
./gradlew testDebugUnitTest
```

Unit tests live in `app/src/test` and run on the JVM. Exact-alarm and notification behaviour
also needs testing on real devices; [docs/REMINDER_RELIABILITY_TEST_PLAN.md](docs/REMINDER_RELIABILITY_TEST_PLAN.md)
lists the manual checks.

### Release signing

Release builds read their signing key from `local.properties`, which git ignores:

```properties
speakalert.storeFile=your-release-key.jks
speakalert.storePassword=...
speakalert.keyAlias=...
speakalert.keyPassword=...
```

`storeFile` is resolved relative to the `app/` directory. Never commit a keystore or its
passwords.

## Project structure

The app is a single Gradle module written in Kotlin with Jetpack Compose, Room, DataStore,
AlarmManager, WorkManager and Media3, following an MVVM layout.

```
app/src/main/java/com/ghostgramlabs/speakalert/
├── alarm/        Exact-alarm scheduling, alarm receivers, boot rescheduling
├── audio/        Voice recording, processing, playback
├── data/         Room database, DAOs, repositories, backup/restore
├── domain/       Domain models and recurrence logic
├── service/      Foreground playback service and text-to-speech
├── ui/           Compose screens: home, add/edit, details, alert, settings
├── widget/       Home-screen widgets
└── util/         Shared helpers

docs/             Website served at ghostgramlabs.com/SpeakAlert (GitHub Pages)
store/            Play Store listing, screenshots and icons
tools/            Python scripts that generate the icon, screenshots and promo video
```

Translations are in `app/src/main/res/values-*/strings.xml`.

## Permissions

| Permission | Why |
| --- | --- |
| `RECORD_AUDIO` | Record voice reminders |
| `POST_NOTIFICATIONS` | Show reminder alerts (Android 13+) |
| `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM` | Fire reminders at the exact minute |
| `USE_FULL_SCREEN_INTENT` | Full-screen alert on the lock screen |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Keep playback running while a reminder speaks |
| `RECEIVE_BOOT_COMPLETED` | Reschedule reminders after a restart |
| `ACCESS_NOTIFICATION_POLICY` | Optional Do Not Disturb bypass |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Ask to be exempted from battery limits that delay alarms |
| `BLUETOOTH_CONNECT` | Route private playback to Bluetooth headphones |
| `WAKE_LOCK`, `VIBRATE` | Wake the device and vibrate on alerts |

## Documentation

| Document | What it covers |
| --- | --- |
| [ARCHITECTURE.md](ARCHITECTURE.md) | How the app is built: packages, data model, the alarm and playback flow |
| [CHANGELOG.md](CHANGELOG.md) | What changed in each release |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Reporting bugs, translating, and submitting pull requests |
| [docs/REMINDER_RELIABILITY_TEST_PLAN.md](docs/REMINDER_RELIABILITY_TEST_PLAN.md) | Manual device checks before a release |

## Contributing

Bug reports, translations and pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).
To report a security problem, follow [SECURITY.md](SECURITY.md) instead of opening a public issue.
Everyone taking part is expected to follow the [code of conduct](CODE_OF_CONDUCT.md).

## License

SpeakAlert is released under the [Apache License 2.0](LICENSE). You may use, modify and
redistribute it, including commercially, as long as you keep the copyright notice and the
[NOTICE](NOTICE) file, which credits GhostGram Labs.

The SpeakAlert name and icon are not covered by the license. If you publish a fork, give it its
own name, icon and application ID.

Made by [GhostGram Labs](https://ghostgramlabs.com).
