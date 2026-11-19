# AI work log

## 2026-11-19

- The account fork uses remote `fork`. Its `main` tip was `13a1b3b`; the primary working tree has substantial pre-existing edits and a different history. The two linked worktrees share the same Git repository. Compare the trees before integrating local work.
- Phone package `com.ghostgramlabs.speakalert.debug` was installed with version code 67.
- Published `57a84a6` on `codex/local-sync-2026-11-19`. Compared its full tree to the fork's `main` and reconciled the overlapping histories locally as `81b8b41`.
- The remaining delta implements shake gestures and lock-screen widget recording, with related tests and documentation. Manual review should cover microphone permission denied, screen locked, and process recreation on the Vivo phone.
- Published the distinct local widget branch as `codex/record-reminder-widget` on the account fork.
- The first fresh test run exposed unsupported Java 21 instrumentation in Mockito's bundled Byte Buddy. Updated only the JVM test dependencies in the integration worktree.
- The reconciled debug build, all 382 JVM tests, and instrumentation APK compilation passed after the test dependency update. The account fork is the primary source for future syncs.
