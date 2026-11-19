# Volume-button quick recording

## Device check: vivo V2436, Android 16

The phone's built-in **Quick action > Open app** route still requires unlocking. In the
observed event log, keyguard dismissal completed at 11:24:59.923 and the new SpeakAlert launcher
was created at 11:24:59.986. No recording service was started while locked. This confirms that
this particular system shortcut delays app launch until after unlock, even though the launcher
declares lock-screen support.

The updated route recognizes the observed vivo shortcut intent flags (`0x30204000`, including
`FLAG_ACTIVITY_TASK_ON_HOME`) after unlock. A normal app-icon launch (`0x10200000`) does not
match this shortcut marker. The recording launcher uses its own short-lived task so a previous
reminder screen cannot swallow a subsequent shortcut activation. Verify the revised
hold → unlock → record flow physically before calling the device test passed.

## Setup and manual checks

In the phone's own screen-off quick-action settings, assign **SpeakAlert Debug** to the
Volume Down long press.
Reselect the app after updating so the phone uses the new lock-screen launcher entry.
SpeakAlert's **Settings > Quick-action recording** switch is enabled by default.
Grant microphone and notification permissions once while unlocked.

The app does not listen globally for volume keys. The phone launches the app from its own
quick action. A locked launcher activation is routed to a recording-only activity that can
appear over the keyguard, or open after vivo dismisses it, then starts a microphone foreground service after the window gains
focus. Normal unlocked launches, reminder notifications and the quick-add widget keep their
existing destinations. The recording screen contains no reminder-library data.

1. Lock the phone and turn off its screen. Hold Volume Down and unlock when vivo asks.
   Verify recording starts automatically after unlocking, and the microphone indicator and
   **Recording your reminder** notification appear. Do not expect capture before vivo launches
   the app.
2. Speak, turn the screen off if needed, then activate the phone shortcut again and unlock if
   asked. Verify recording stops and a single voice reminder is scheduled exactly 10 minutes
   after stopping.
3. With the recording screen visible, press and release Volume Down. Verify it stops and saves.
   The key release belonging to the initial long press must not immediately stop the new take.
4. Start another take and use **Stop and remind in 10 min** on the lock-screen notification.
   Verify no unlock is required to stop. Tapping the notification body should show controls
   without toggling the recording.
5. Close the recording screen or turn the screen off. Verify recording continues in the
   foreground service. Rotate the screen and verify capture does not restart or stop.
6. Rapidly repeat an activation, or tap Stop while another Stop is saving. Verify exactly one
   reminder is saved. A too-short/empty take must not create an empty reminder.
7. Deny microphone/notification permission. Verify no recording begins; initial permission
   setup requires unlocking. Disable the quick-recording switch and verify the phone shortcut
   opens the normal reminder screen after unlocking instead of recording.
8. While locked, open a reminder notification or the quick-add widget. Verify the normal
   reminder screens remain behind the keyguard and do not start recording.
9. Verify capture auto-stops at five minutes and releases the microphone. Force a save failure:
   the completed recording should remain in the recordings inbox for recovery. A scheduler
   failure must keep the stored reminder and durable audio without duplicating it.

`QuickActionRecordingSessionTest` covers the 10-minute deadline, durable audio, duplicate
launcher delivery, concurrent Stop actions, microphone contention, empty takes, failed saves,
and caller cancellation during saving. `LockedLaunchRoutingTest` covers the keyguard/navigation
boundary, vivo's after-unlock marker, ordinary app-icon launches, other manufacturers, and the
off switch. OEM hardware routing still requires the physical-device checks.
