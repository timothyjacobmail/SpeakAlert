# Shake recording device checks

Run `gradlew.bat testDebugUnitTest assembleDebug` first. Automated tests cover gesture
filtering/cooldown, finalized draft persistence, path validation, microphone ownership,
and importing/canceling/saving recordings in the reminder editor. Automatic reminder tests
cover the five-minute deadline, follow-up defaults, distinct audio files, database/alarm
failures, and completing a save when its caller closes. Gesture-action tests cover grouping,
context mappings, mode changes, deadline bounds, idle expiry, custom settings persistence
and scheduling the selected time without duplicate reminders.

Use a physical phone for sensor and OEM power behavior. Check Android 8–13 and Android 14+
(especially the target Android 16). Emulator acceleration alone does not verify screen-off
sensor delivery or microphone audio quality.

1. Open Settings > Shake to record > Open recordings and controls. Deny microphone access:
   listening must stay off and explain how to grant access. Repeat with notifications denied.
2. Grant both permissions and enable listening. Verify the ongoing notification and idle
   status. A single bump or ordinary walking should not start recording.
3. Make one gesture (two firm movements), then pause for 1.4 seconds with default settings:
   verify the short beep and vibration, recording status and notification timer. Two or three
   gestures in one group must not first run the single action. Check the recent action log.
4. Speak, then shake twice again. Verify the different stop sound and longer vibration. Verify
   after the 10-second entry window a new reminder is created automatically, due exactly five minutes after stop, with the
   default follow-up setting. Tap View reminder or the notification to preview/change its time.
   Confirm it fires and contains speech, and that no duplicate remains in the recordings inbox.
5. Enable listening, lock the phone and turn its screen off. Repeat step 4 without unlocking;
   verify capture starts and stops, then unlock to preview. Repeat after several idle minutes
   and with the app removed from Recents. Document OEM battery restrictions if they stop it.
6. Stop from the notification while locked. Verify the stop sound plays, the entry window
   appears and the microphone indicator disappears. Stop listening during another take: it
   must finish saving/scheduling before removing the service notification. Check sensor
   registration and wake locks are released. Repeat with notification volume muted: vibration
   should still work. Neither tone may change the system volume.
7. Let a take run for five minutes with the screen off: it must stop with a sound, enter the
   editing window, then create a reminder due five minutes after that automatic stop.
8. Open an older/recovered inbox take, edit its title, rotate, then cancel: the original must remain in the
   inbox. Save successfully: the reminder owns its copy and the inbox original disappears.
   Replace an imported take with new audio: the original stays in the inbox.
9. Start a normal editor recording and shake: the original must keep recording. Try to start
   an editor recording during shake capture: it must fail gracefully without stealing audio.
10. Test microphone disabled in Android privacy controls, an active phone call, full storage,
    missing accelerometer, and a blocked notification channel. Failures must not report a
    successful take. Very quiet or silent finalized audio is retained with a preview warning.
11. Stop the app from Android's active-apps panel, force-stop it, and reboot. Listening must
    remain off until explicitly enabled again. Previously completed inbox takes must remain.
    Abrupt termination during capture may leave an incomplete take, excluded from the inbox.
12. Stop a take, then make one gesture within 10 seconds. Verify Minutes mode and unchanged
    initial due time. One more gesture adds 5 minutes. A double switches to Hours without
    adding time; a single then adds 30 minutes. A triple schedules exactly one reminder.
    Repeat with the screen off and by using the visible time-edit buttons.
13. Let the edit inactivity timer expire, use Finish from the notification, and use Stop
    listening during editing. Each must schedule once with the selected time. Start a gesture
    near the window boundary: it must be classified before timeout. Repeated finish commands
    must not duplicate reminders or interrupt the save. No pending take can be deleted/imported
    through the inbox while the service owns it.
14. Customize actions: swap single/double actions in Minutes mode; assign subtract and reset
    in Hours mode; change the base delay and increments; disable sounds/vibration. Save, reopen
    the form and restart listening. Confirm all assignments and values persist and only the
    matching context runs each action. Invalid numeric values must block saving. Restore
    defaults requires Save, and editing an active session requires stopping it first.
15. Adjust force, grouping pause and cooldown; check the action log against physical gestures.
    Four or more gestures in one group must be ignored. Clear the log. Verify subtraction
    cannot select the past and large additions stop at seven days. Wait beyond a short selected
    due time while editing; finishing must schedule at least five seconds in the future.
16. Kill the process during editing: a completed audio take must remain in the recovery inbox
    on reopening. The unfinished time selection is intentionally not restored. Rotate the
    settings screen while editing fields: unsaved field values and assignments must survive.
17. Watch the notification while making single/double/triple gestures: recognition, the last
    action, mode, recording timer, saving and final due time must update in one notification.
    Disable shake sounds/vibration to isolate notification alerts, then verify updates are
    silent on both a fresh install and an existing notification channel. Reminder alarms must
    continue to use their own configured playback and notification behavior.

Record the phone model, Android version, battery mode and actual results. A successful build
or unit test is not evidence that physical shake capture works on the lock screen.

## Initial implementation verification, 2026-10-24

- `:app:testDebugUnitTest :app:assembleDebug` completed successfully: 251 tests, no failures.
- Used the installed JetBrains JDK 21 with process-local `JAVA_TOOL_OPTIONS` values
  `-Dnet.bytebuddy.experimental=true` (the existing Mockito/Byte Buddy version otherwise
  rejects Java 21) and `-Djdk.net.unixdomain.tmpdir=D:\My_Android_Apps\SpeakAlert\build\socket-tmp`
  (the sandbox temporary socket path otherwise fails). The project continues to recommend
  JDK 17; no global Java or Gradle settings were changed.
- The connected V2436 phone reports Android API 36. Its installed app was not replaced,
  and physical shaking, screen-off capture and audio quality have not yet been verified.
- The new controls currently use English resource fallback for other app languages.

## Sound cues and automatic reminders verification, 2026-10-24

- The user confirmed the original shake recording feature works on their phone.
- `:app:testDebugUnitTest :app:assembleDebug` completed successfully after adding sound
  cues and automatic reminders: 257 tests, no failures. The same process-local JDK
  workarounds listed above were used.
- Physical playback of the new cues and delivery of the automatic reminders still need
  device verification using the checklist above.

## Customizable gesture actions verification, 2026-10-24

- `:app:testDebugUnitTest :app:assembleDebug` completed successfully: 270 tests, no failures
  or errors. The same process-local JDK workarounds listed above were used.
- New tests verify single/double/triple grouping, overflow rejection, context-specific
  assignments, custom settings round trips, fine/coarse increments, safe time bounds,
  monotonic idle windows and scheduling the final selected deadline once.
- The updated APK was built, but the new physical gesture sequences and screen-off time
  editing have not been verified on a phone in this change. Follow steps 12–16 above.

## Silent status notification verification, 2026-10-24

- `:app:testDebugUnitTest :app:assembleDebug` completed successfully: 270 tests, no failures
  or errors, after adding explicit silent channel/builder settings and live status text.
- Device verification of notification silence and state changes remains covered by step 17.
