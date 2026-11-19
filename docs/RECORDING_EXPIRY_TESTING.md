# Seven-day recorded-reminder expiry

Recordings stored in the app's private `files/reminders` directory expire exactly 7 × 24 hours
after the reminder's original `createdAt`. This covers voice reminders from the editor, widget,
quick action, shake recording, and restored local recordings. Text-only reminders, picked
content URIs and external files are unaffected. Active, completed, recurring, snoozed and
missed recorded reminders all use the same creation-based deadline. Editing does not reset it;
duplicating creates a new reminder with its own creation date.

Repository reads exclude expired recordings and alarm scheduling rejects deadlines at or after
expiry. Late alarm broadcasts and stale playback notifications cannot alert for expired rows.
WorkManager schedules cleanup for each recorded reminder's deadline, runs an hourly fallback,
and sweeps on process startup and when the main screen resumes. Android can defer background
work: physical row/file deletion happens when cleanup runs, rather than at an exact-alarm
deadline. Missed entries are removed in the same database transaction as their source reminder.
Alarm/follow-up cancellation and notification cleanup follow deletion. Audio is deleted only
when no stored reminder references it; an old orphan scan recovers interrupted cleanup.

Automated checks in `RecordedReminderExpiryTest` and `RecordedReminderRepositoryExpiryTest`
cover the exact seven-day boundary, completion/recurrence/snooze, external audio protection,
shared files used by newer duplicates, orphan recovery, failed alarm cancellation, reads before
worker execution, creation-date preservation during edits, and cleanup scheduling on insert.
`ReminderBackupAuditTest` checks that restoring an already-expired voice reminder does not
resurrect it or leave an extracted orphan.

For a debug-device/manual check, use disposable reminders and recordings with creation dates
just before and just after the cutoff in a test database; do not change the phone's clock or
age real user reminders. Run the cleanup worker. Verify the older row, missed history, alerts
and unshared file disappear, while the newer reminder, shared file, text reminder and picked
audio remain. Relaunch the app, repeat cleanup and verify it is idempotent. Background
WorkManager execution should also be checked after a normal app launch and a reboot.
