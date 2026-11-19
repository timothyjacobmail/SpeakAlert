# Record reminder widget

Add **SpeakAlert > Record reminder** from the launcher's widget picker.

Both **Create reminder** and **Record reminder** can be resized down to one home-screen
cell. Long-press a placed widget and drag its resize handles inward: it should show only
the app icon, without a card, caption, or button. The recording icon has a **⚡** badge
at its bottom-right, including in the expanded card. Enlarge the widget until the full
card fits to restore its caption and button. Check resizing in both directions and
after rotating the launcher; tapping anywhere in the small widget should still work.
If a launcher caches the old minimum size after an app update, remove and re-add the widget.

1. Tap the widget. With microphone permission granted, recording should start immediately.
   On first use, grant microphone access and verify recording starts after approval.
2. Speak, tap **Stop recording**, and verify the five choices: **5 min**, **10 min**,
   **15 min**, **30 min**, **1 hr**. Tap a choice; the reminder saves and returns to the launcher.
3. Open SpeakAlert and check the recording plays and the reminder time is the chosen delay
   after the time you tapped the choice. Wait for a 5-minute reminder to verify delivery.
4. Repeat for each preset. Tap a preset repeatedly and verify only one reminder is created.
5. Tap **Record again** before saving. Verify the saved reminder contains the replacement take.
6. Cancel during recording and after stopping. Neither should create a reminder; the microphone
   indicator should turn off. An empty/too-short take should offer recording again.
7. Rotate during recording and while choosing a delay. Keep the take without restarting capture.
   Press Home during capture: recording should stop; tapping the widget again should show delays.
8. Tap the widget again while its recording screen is open: it should reuse that screen and take.
9. Deny microphone access; verify no recording starts and the permission/settings actions work.
   With another recording already active, verify a recording error rather than a microphone takeover.
10. Check light/dark mode, large text, and a small display: all choices should remain accessible
    by scrolling. Existing quick-add and upcoming-reminder widgets should still work.

Automated coverage in `AddEditViewModelTest` checks all five exact delays and durable audio,
duplicate-tap protection, and rejecting empty recordings.

## Widgets Anywhere lock-screen drawer

With both apps updated, place **Record reminder** in the drawer. Grant SpeakAlert microphone
access once while unlocked. Lock the phone, open the drawer, and tap the widget. The drawer
should close, the elapsed timer should start, and **Stop recording** should be available
without entering a PIN. Stop, choose a delay, and check that the phone returns to the lock
screen. After unlocking, verify the new reminder and play the take. Repeat with **Cancel**
and verify no reminder is added. Check another widget that opens a private app still follows
the drawer's configured unlock behavior.
