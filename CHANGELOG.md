# Changelog

User-facing changes to SpeakAlert, newest first. Version numbers match the Google Play release
(`versionName` in `app/build.gradle.kts`).

## Unreleased

- Speed up optional voice-enhancement processing with an optimized Java FFT and reusable
  output buffers. A synthetic phone benchmark used about 14% less processing time.
- Move reminder-expiry list filtering off the UI thread and reuse cleanup jobs when the
  expiry date is unchanged, avoiding extra WorkManager writes on snooze and Done.
- Recorded reminders expire 7 days after creation, including active, repeating and completed
  reminders. Cleanup removes their stored audio, missed history, pending alarms and alerts;
  editing a reminder keeps its original creation date. Shared recordings remain while used
  by a newer reminder.
- Add quick-action recording that recognizes vivo shortcut launches after unlocking, with
  notification Stop controls and voice reminders due 10 minutes after recording stops.
- Add a Record reminder home-screen widget: tap to start recording immediately, stop, then
  choose 5, 10, 15, or 30 minutes, or 1 hour to save the reminder.
- Shake listening now updates one explicitly silent status notification with gesture recognition,
  the current mode/action, recording progress, saving status and the scheduled due time.
- Customizable single/double/triple shake actions for recording and time editing, with separate
  Minutes/Hours increments, adjustable sensitivity and timing windows, feedback switches and
  a recent action log. By default, shake after stopping to edit, add 5 minutes per gesture,
  double-shake to switch to 30-minute steps, and triple-shake or pause to finish scheduling.
- Optional shake-to-record listening works while locked, with vibration feedback, notification
  controls, and an inbox for turning saved recordings into reminders.
- Shake recording now plays distinct start/stop sounds and automatically creates a reminder
  due five minutes after recording stops. Failed saves keep the recording available for recovery.
- SpeakAlert is now open source under the Apache License 2.0.

## 2.0.40 - 2026-10-06

- Eight new languages: Portuguese (Brazil), Russian, Vietnamese, Bengali, Telugu, Tamil, Greek
  and Malayalam, for 12 in total. Buttons now grow to fit longer translations.
- Reschedule a missed reminder from the Missed tab.
- Fixed: sound kept playing after the notification was swiped away.
- Fixed: a reminder played by itself after its time was changed.
- Fixed: a voice note kept playing while it was being edited.
- Fixed: backing up with no reminders produced an empty file.
- Fixed: wrapped chips in the Custom repeat sheet.

## 2.0.39 - 2026-09-30

- Refreshed app icon.
- Edit a reminder's date and time directly from its details screen.
- Voice reminders save immediately instead of waiting for voice enhancement.
- A reminder's notification clears once it is handled in the app.
- Back closes bottom sheets on Android 16.
- Sheets and dialogs are opaque, so the screen behind no longer shows through.
- Fixed: the notification permission prompt replaced the battery-optimisation request on first
  run.
- Fixed: a crash on devices with no file or ringtone picker; the app now explains instead.
- Fixed: freezes caused by the speech engine running on the main thread.
- Fixed: a crash in the time picker.

## 2.0.37 - 2026-09-17

- Home explains why reminders are not firing, and lets you pause reminders for a while.
- The Missed list no longer grows forever.
- Finished translations that still contained English text.

## 2.0.36 - 2026-09-16

- The full-screen alert reliably reaches the lock screen, with a rebuilt design.
- You can save while still recording, and voice normalisation no longer amplifies background
  noise.
- Configurable title for reminders saved without a name.
- 24-hour time setting that applies across the app and widgets.
- Dismissing a missed entry also resolves its reminder.
- Fixed several freezes in widgets and recording previews.

## 2.0.35 - 2026-09-10

- Reminder reliability and first-run onboarding improvements.
- Fixed silent voice recordings.

## 2.0.34 - 2026-08-13

- Android 16 support.

## Earlier versions

Releases before 2.0.34 are recorded in the git history.
