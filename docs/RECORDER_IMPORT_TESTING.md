# vivo Recorder reminders

In **Settings > Recorder reminders**, turn the switch on and grant audio and
notification access. This integration watches new audio in vivo Recorder's observed
`Recordings/Record/` folder. Disclosed ownership is checked against
`com.vivo.soundrecorder`; unknown ownership is accepted only in that dedicated
folder. The tested vivo build exposes folder files but returns zero rows for an
owner-package query even when Recorder is visible and audio permission is granted.
Both Audio and Files notifications on merged/concrete volumes are observed. It includes
both normal and Volume Down recordings: saved audio does not identify which
launch method started it. Older recordings are ignored when enabling.

An ongoing silent notification indicates that monitoring is enabled. Detection
uses MediaStore events, with a bounded wake lock during finalization/import and
bounded retries for incomplete or temporarily inaccessible files. It does not
capture microphone audio or continuously poll storage. A `specialUse` foreground
service keeps the observer alive for timely screen-off imports; the manifest
documents the use case. A store release needs this service type declared and
reviewed in Play Console.

Completed, stable, decodable recordings are copied to SpeakAlert's private
`files/reminders` directory. A Room transaction inserts the reminder and import
journal together. The deadline is the MediaStore save timestamp plus 600,000 ms.
The normal reminder scheduler, playback and seven-day retention apply to the
owned copy. Recorder originals are never edited or deleted. A per-reminder
notification confirms the ten-minute delay and local due time, and opens that
reminder. Recovery reuses the same reminder/notification instead of importing twice.

The setting defaults off. Turning it off stops the service and monitoring without
cancelling reminders already created. Re-enabling takes a fresh baseline, ignoring
recordings saved while disabled. Monitoring restarts after boot, app updates and
normal app launches while enabled and audio access remains granted. Vivo can still
stop background apps; enable its background/autostart allowances. Force-stopping
the app suspends monitoring until the app is opened again. Preferences are excluded
from Android backup/transfer so monitoring is not silently enabled on another phone.

## Phone checks

1. Note the number of reminders, enable the setting, and verify existing Recorder
   files create no reminders. Check the ongoing notification and permission flow.
2. With the screen off, use Volume Down to record a short take and save it. Verify
   exactly one confirmation and reminder, due ten minutes after save. Check that
   its copied audio plays; the original remains available in Recorder.
3. Record through Recorder normally. Verify the same behavior. Cancel an unfinished
   recording; it should create no reminder. Music and call-recording folders are excluded.
4. Wait for the ten-minute alert with the screen off. Check notifications, exact
   alarm access, autoplay and vivo background settings if playback is suppressed.
5. Rename an imported recording, reopen apps, and restart the monitor. No second
   reminder should appear. Delete/complete an imported reminder; it stays deleted/done.
6. Toggle off (including the notification's **Turn off** action), save another take,
   and toggle on again. That take should stay ignored; a subsequent new take imports.
7. Revoke audio/notification access, check the permission/restart path, and verify
   no false success notification is shown. Reboot/update while enabled and check
   that monitoring resumes. Existing reminders and audio must survive migration.

`RecorderImportEngineTest` covers exact deadlines, copies, deduplication, baseline,
partial files, cancellation, scheduling/notification retry, deleted reminders and
concurrent scans. `RecorderImportDatabaseTest` checks migration and transactional
deduplication against SQLite/Room on the device, using isolated test databases,
and adds a permission-dependent regression for the actual Recorder folder query.

## Verified on vivo V2436, Android 16

- All 320 unit tests passed; all three phone integration tests completed successfully.
- Earlier test takes imported once each, with their original ten-minute deadlines.
- The user confirmed a fresh screen-off Volume Down recording produced the
  ten-minute confirmation notification after the vivo query fix.
- Audio and notification permissions were granted through the app, and monitoring
  remained enabled across update/restart. The temporary diagnostic test app was removed.
