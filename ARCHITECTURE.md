# SpeakAlert architecture

This guide explains how SpeakAlert is put together, so you can find your way around before
changing it. For build steps see the [README](README.md); for how to submit changes see
[CONTRIBUTING.md](CONTRIBUTING.md).

## Overview

SpeakAlert is a single-module Android app (`:app`) written in Kotlin.

| Concern | Technology |
| --- | --- |
| UI | Jetpack Compose, Material 3, Navigation Compose |
| State | MVVM: one `ViewModel` per screen, exposing Kotlin `Flow`s |
| Storage | Room (reminders, missed inbox), DataStore Preferences (settings) |
| Scheduling | `AlarmManager` exact alarms, WorkManager for boot rescheduling |
| Playback | Media3 ExoPlayer for recordings, Android `TextToSpeech` for text |
| Widgets | `AppWidgetProvider` with `RemoteViews` |

There is no network layer. Reminders, recordings and settings never leave the device, except
through a backup file the user exports themselves.

Dependencies are wired by hand, without a DI framework. `VoiceReminderApp` creates an
`AppContainerImpl` ([data/AppContainer.kt](app/src/main/java/com/ghostgramlabs/speakalert/data/AppContainer.kt)),
which lazily builds the repositories and the `AlarmScheduler`. ViewModels get them through
`AppViewModelProvider`; receivers and services reach them via
`(context.applicationContext as VoiceReminderApp).container`.

## Packages

All paths are under `app/src/main/java/com/ghostgramlabs/speakalert/`.

| Package | Responsibility |
| --- | --- |
| `alarm/` | Scheduling and firing reminders: `AlarmScheduler`, `ReminderAlarmReceiver`, follow-up alarms, notification actions, boot handling, notification channels, tone-only player |
| `audio/` | Recording (`AudioRecorder`), clean-up and enhancement of recordings (`VoiceProcessor`, `AudioEnhancer`), in-app preview playback |
| `data/` | Room database and DAOs, entities, repositories, `SettingsRepository` (DataStore), backup/restore |
| `domain/` | Recurrence model and maths (`RecurrenceUtils`): next occurrence, end rules, summaries |
| `service/` | `ReminderPlaybackService`, the foreground service that speaks a reminder, plus playback timeouts and TTS guards |
| `ui/` | Compose screens: `home`, `addedit`, `details`, `alert` (full-screen lock-screen alert), `settings`, shared `components`, `theme`, `navigation` |
| `widget/` | Quick Reminder and Upcoming Reminders home-screen widgets |
| `util/` | Helpers: dates and time formats, locale, battery-optimisation support, private audio routing, Wear OS detection, logging |

## Data model

The database (`AppDatabase`) has two tables.

**`reminders`** ([ReminderEntity](app/src/main/java/com/ghostgramlabs/speakalert/data/model/ReminderEntity.kt)) holds one row per reminder:

- Content: `title`, `reminderText` (spoken by TTS), `audioPath` (a recording or a chosen audio file)
- Timing: `nextTriggerAt` is the next scheduled occurrence. `snoozeUntil`, when set, takes
  priority and is cleared when the snoozed alarm fires, so the recurrence resumes from
  `nextTriggerAt`.
- Recurrence: `recurrenceType` (`NONE`, `DAILY`, `WEEKLY`, `MONTHLY`, `YEARLY`, `CUSTOM`) plus
  `recurrenceJson`, a serialised `RecurrenceModel` with the interval, weekdays, end rule and
  missed policy
- State: `isCompleted`, `completedAt`, `lastFiredAt`
- Follow-up check: `followUpCheckMinutes`, `pendingFollowUpAt`, `followUpFireCount`

**`missed_reminders`** ([MissedReminderEntity](app/src/main/java/com/ghostgramlabs/speakalert/data/model/MissedReminderEntity.kt))
is the Missed inbox: one row each time an occurrence was not delivered on time.

Schema changes need a new `Migration` in `AppDatabase` and a version bump. The database falls
back to a destructive rebuild when no migration path exists, which would wipe users' reminders,
so always add the migration.

Settings (playback, quiet hours, snooze length, language, theme and so on) live in DataStore
and are read through `SettingsRepository`.

## Life of a reminder

```
 Add/Edit screen ──save──▶ ReminderRepository (Room)
                                │
                                ▼
                    AlarmScheduler.schedule()
              AlarmManager.setExactAndAllowWhileIdle
                                │  at trigger time
                                ▼
                     ReminderAlarmReceiver
     ┌──────────────┬───────────┴────────────┬───────────────────┐
     ▼              ▼                        ▼                   ▼
 quiet hours /   late recurring         on time              follow-up
 paused          (missed policy)                             check due
 → Missed inbox  → Missed inbox or   → notification and     → re-alert,
                   "missed" notice     ReminderPlaybackService   schedule next
                                       (voice or TTS) or         check
                                       ToneAlertPlayer
                                │
                                ▼
          advance recurrence (RecurrenceUtils) and schedule the next alarm
```

### 1. Scheduling

`AndroidAlarmScheduler` sets an exact `RTC_WAKEUP` alarm whose `PendingIntent` targets
`ReminderAlarmReceiver`, using the reminder ID as the request code. If the user has denied the
exact-alarm permission (Android 12+), it falls back to an inexact alarm. Follow-up checks use a
separate request-code range (`FollowUpAlarmScheduler`), so they never replace the main alarm.

### 2. Firing

[ReminderAlarmReceiver](app/src/main/java/com/ghostgramlabs/speakalert/alarm/ReminderAlarmReceiver.kt)
holds most of the delivery rules. In order, it:

1. Loads the reminder and ignores duplicate fires for an occurrence that already fired.
2. Checks quiet hours and the user's temporary pause. A suppressed reminder goes to the Missed
   inbox, and its recurrence still advances.
3. Decides whether the alarm is late. The grace window is 60 seconds for minute or hour
   intervals and 5 minutes for calendar repeats; one-time reminders are always shown. A late
   recurring reminder follows its missed policy: `SKIP_TO_NEXT` logs it to the inbox silently,
   `FIRE_ON_RESUME` shows a "missed" notification.
4. On time: posts the notification (full-screen when the system allows it) and either starts
   `ReminderPlaybackService` for voice or TTS autoplay, or starts `ToneAlertPlayer` in tone-only
   mode.
5. Advances the recurrence with `RecurrenceUtils`, or ends it when its end rule is met, and
   schedules the next alarm. One-time reminders stay active until the user taps Done or
   Dismiss.
6. Schedules the next follow-up check if one is configured.

Notification buttons (Done, Snooze, Play, Stop) are handled by `ReminderActionReceiver`.

### 3. Playback

[ReminderPlaybackService](app/src/main/java/com/ghostgramlabs/speakalert/service/ReminderPlaybackService.kt)
is a `mediaPlayback` foreground service. It plays the recording with ExoPlayer or speaks the
text with TTS, optionally looping until a timeout, and requests transient audio focus. By default
it uses the alarm stream. With private playback on, it uses the media stream, or the earpiece
(voice-call stream) when the proximity sensor says the phone is at the user's ear.

All `TextToSpeech` calls run on a dedicated single thread. The engine can block for seconds,
and calling it on the main thread caused ANRs.

### 4. Reboot and clock changes

Alarms do not survive a reboot. `BootReceiver` handles `BOOT_COMPLETED`, `TIME_CHANGED` and
`TIMEZONE_CHANGED` by enqueuing `BootRescheduleWorker`. Android 15 forbids starting a
`mediaPlayback` foreground service from a boot broadcast, so the worker:

- reschedules future reminders normally, and
- handles reminders that came due while the phone was off inline: it adds them to the Missed
  inbox and schedules their next occurrence instead of firing them immediately.

On Android 15+, the receiver also skips autoplay for the first two minutes after boot.

## Shake recording

`shake/ShakeRecordingService` is a non-exported `microphone` foreground service, explicitly
started from `ShakeRecordingsActivity` after microphone and notification permission checks.
The manifest declares `FOREGROUND_SERVICE_MICROPHONE`. It is not started at boot or restarted
after process death: the microphone's while-in-use grant must come from the visible activity.
The service uses a wake-up accelerometer if available, otherwise a partial wake lock while
listening, and always a wake lock during recording, gesture grouping and time editing so
screen-off deadlines run. Sensor
listeners, recorder and wake lock are released on shutdown. The ongoing notification offers
save and stop actions; it opens the ordinary unlocked inbox, without a full-screen intent.
The status channel disables sound, vibration and badges by default; every notification uses
`setSilent(true)` and low priority as well as `setOnlyAlertOnce(true)`. A single notification ID
is updated with recognition progress, mode, last action and the saved due time. Optional
recording/edit cues are independent of notification alerts.

`ShakeDetector` requires two distinct 2.7g peaks within 1.2 seconds, separated by at least
180ms. Force and cooldown are configurable (2.7g and 500ms by default). `ShakeSequence` groups
complete gestures until a configurable quiet gap (1400ms by default), dispatching only one
single/double/triple action; larger groups are ignored. `ShakeActionEngine` implements the
Ready, Recording, After recording, Minutes and Hours contexts with monotonic idle deadlines
and a wall-clock alarm time. `ShakeSettingsStore` persists validated values and one action per
context/count in the existing settings DataStore; settings are loaded before sensor registration
and remain fixed for the listening session. `ShakeSettingsScreen` exposes mappings, increments,
timeouts, detection and feedback controls. An in-memory StateFlow holds the last 30 recognized
groups and saves for inspection; it contains no audio. `RecordingOwner` prevents the service and editor from
capturing simultaneously. `ShakeRecordingSound` uses short notification-stream tones after
capture starts and after it stops; each tone generator releases independently of service
shutdown. Editing cues distinguish Minutes and Hours mode. The service stays foreground until
a user-requested stop has saved the active take or pending time edit.

`ShakeDraftStore` writes to private `files/shake_recordings/`, renaming finalized takes from
`.recording` to `.m4a`. A completed file is held through the entry/edit window, protected from
inbox import/deletion. Finishing, timeout or service shutdown consumes that pending slot once.
`ShakeReminderCreator` copies it to `files/reminders/`, inserts a one-time reminder at the selected
time (initially five minutes after stop, without rounding), applies
the default follow-up setting, and schedules its alarm. The database/audio handoff completes
off the main thread even if the controls close. A short wake lock covers the save. Only then
is the inbox original removed. Database failures keep the inbox original; scheduling failures
keep the saved reminder and audio for editing. The notification opens the new reminder.

Older or failed takes can still be imported through the inbox. The editor works on a temporary
copy; only a successful reminder save consumes the original. A process killed during time
editing leaves the completed take in the recovery inbox, without restoring the time selection.
Incomplete takes after abrupt
process termination are excluded. The manual reminder backup includes takes after they become
reminders, not unassigned inbox recordings. New shake UI strings currently use the English
fallback in `values/shake.xml`.

## Backup format

`ReminderBackupManager` exports active reminders to a ZIP file containing `backup.json` (enums
stored by name so the format survives refactors) and an `audio/` entry per recording. On import,
past one-time reminders are skipped, past recurring ones are moved to their next occurrence, and
exact duplicates of existing reminders are skipped. Keep the format backwards compatible: older
backups must still import.

## Localisation

User-facing text belongs in `res/values/strings.xml`, with translations in `res/values-<code>/`
(currently ar, bn, el, es, hi, ml, pt-rBR, ru, ta, te, vi). Arabic is right-to-left; use
`RtlMirror` and start/end rather than left/right. The in-app language picker uses
`AppCompatDelegate.setApplicationLocales`.

## Testing

Unit tests in `app/src/test` run on the JVM with JUnit 4, Mockito and coroutines-test, and
mirror the main package layout. Recurrence maths, missed-reminder rules, playback timeouts and
backup import are the most heavily tested areas; add tests there when you change them.

```bash
./gradlew testDebugUnitTest
```

Alarm timing, notifications and audio routing depend on the device and Android version, and
JVM tests cannot cover them. Run the manual checks in
[docs/REMINDER_RELIABILITY_TEST_PLAN.md](docs/REMINDER_RELIABILITY_TEST_PLAN.md) before a release.

## Debugging

`FileLogger` traces the alarm path (scheduling, firing, missed decisions, playback) and can
write the trace to a log file in Downloads. Debug builds also expose extra options in Settings
(`BuildConfig.SHOW_DEBUG_OPTIONS`).

## Other directories

| Path | Contents |
| --- | --- |
| `docs/` | The product website, published by GitHub Pages at ghostgramlabs.com/SpeakAlert |
| `store/` | Play Store listing text, screenshots, icons and promo material |
| `tools/icon/` | `make_icon.py`, which generates every launcher, store and website icon |
| `tools/store/` | Scripts that compose store screenshots and the promo video from raw captures |
