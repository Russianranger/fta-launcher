# Synthetic Android runtime report assessment

The owner-supplied code-41 report passed the synthetic runtime gate on Thor.
[Measured assessment](evidence/android-2026-10-27-thor-code41-runtime-pass.json).
The report is evidence supplied by the owner; this checker cannot independently
authenticate it or read device storage. It does not promote original WarcraftXL,
graphics, gameplay, total guest RSS or actual cache eviction.

`android/qualification/client_runtime_report.py` checks exact private APK digest,
package/version, pinned runtime archive/catalog/counts, all ten integer-zero stages,
normal/Stop/forced-death ownership, cleanup completion, inactive terminal worker,
and full pre/post runtime rehash flags. Contradictory attempt-matched exits reject
the receipt; unavailable OS history cannot invent survival proof. Older unmatched
exits remain historical. Standalone retained verification cannot substitute for
this combined process-and-integrity gate.

The inherited JSON reader rejects duplicate keys and inputs over 2 MiB. Output
is created exclusively and contains only typed allowlisted values; raw reports,
configurations, paths, logs and arbitrary diagnostic strings cannot pass through.
The original report and private APK evidence are never modified.

```sh
python3 android/qualification/client_runtime_report.py \
  --report /private/report.json \
  --apk-evidence docs/evidence/android-2026-10-27-private-client-memory-apk.json \
  --runtime-lock android/qualification/client-runtime-lock.json \
  --output /private/new-runtime-assessment.json
```

Eleven regressions cover every stage, false/truthy flags, incomplete terminal
states, changed identity/counts, cleanup/display/timing failures, historical versus
matching exits, unavailable/malformed history, private-field exclusion and
unsupported original-execution claims. No APK code changed in this increment.

Local validation passed: all 11 new assessor regressions, 207 tooling tests,
and 91 of 93 qualification tests (two existing host-capability skips). No Android
application, native code, runtime payload or delivered APK changed; the installed
APK retains its exact application-commit CI and new physical qualification.
