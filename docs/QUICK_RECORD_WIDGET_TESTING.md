# Record reminder widget

Add **SpeakAlert > Record reminder** from the launcher's widget picker.

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
