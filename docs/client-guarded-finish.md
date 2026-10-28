# Complete a retained guarded Wine attempt

The code-43 Thor report matches the delivered APK. All 56 Windows checks and
six stages returned zero, ownership cleanup completed, and the protected
fixture was unchanged. The operation nevertheless ended `failed / io-failed`.
It is **not a qualified guarded Wine pass**. See the bounded
[assessment](evidence/android-2026-10-28-thor-code43-incomplete.json).

The report records all file bytes read and 14,089 progress entries, against a
14,092-entry catalog. Its I/O counter equals the successful code-41 run plus
the working-view builder's 1,048,602 charged bytes. This is consistent with a
late post-verification or completion failure, but the report has no exception
category or phase. Neither a particular cause nor successful post-verification
is established. There was no matching process exit or memory-pressure pause.

## Code 44 recovery

`Finish protected Wine verification` requires the failed receipt's exact
catalog, archive, native guard, 56 integer-zero checks, six zero stages,
ownership/cleanup and source/copy observations. It takes the exclusive runtime
lease and performs one fresh full runtime verification. It starts no native
process, Wine instance, prefix initialization or import.

The retained attempt must have its cleanup marker and the exact five code-43
fixture assets. Recovery reads and checks the source canary, original config,
PE32/PE64 files, complete source inventory, original view plan, three protected
guest links, independent changed config, six writable directories and the full
saved Wine transcript. Unknown entries, changed content, hard links, missing
files, partial/duplicate/failed transcript records and contradictory deletion
observations are refused. The protected source is compared before and after.

Only after validation and lease release may the new finish report qualify the
disposable fixture. It binds the original attempt ID, original report digest
and retained transcript digest. The original failed report remains unchanged;
diagnostic export includes both `clientGuardedWine` and
`clientGuardedWineFinish`. Recovery does not establish original-game execution,
rendering, login, imported-tree protection or hostile-code confinement.

Completion now reports bounded phases and exception categories, with numeric
errno when available. Paths, messages, stack traces and raw logs stay private.
Full-file hashing alone is distinguished from successful lease release and
result persistence. A failed final report or notification update cannot silently
become a successful completion.

## Physical test after delivery

Install the signed code-44 update over the existing app. Preserve app data and
the retained attempt. Keep the launcher visible, select **Finish protected Wine
verification** once, and export the diagnostic JSON after it finishes or fails.
This checks the retained 2.64 GB runtime; no separate Prepare, Verify, protected
Wine rerun, client import or server test is needed. If the finish action is
unavailable, export the report instead of replacing the retained attempt.

Code 44 remains unqualified on Thor until its completion report is returned.
