# Assessing client import and retained-verification reports

`android/qualification/client_report.py` is a host-only evidence checker. It reads
the owner's exported JSON and a measured private APK proof, and writes a new,
strictly allowlisted assessment. It does not open device files, start a client,
authenticate an exported report, change an app receipt or update a runtime gate.
The Android application and all native payloads are unchanged by this increment.

The physical code-37 import passed. Its assessment is
`docs/evidence/android-2026-10-25-thor-code37-client-import-pass.json`:
10,476 files / 60,243,212,332 bytes, catalog
`fe49bee4d96be084cfda4740112f1a73a10b87ab222e51ccb8730be38c300f13`.
`importQualified` refers to the operation in that report; it does not assert a
later retained rehash. Neither copy nor retained rehash proves host pairing,
WarcraftXL/Wine execution or gameplay.

## Next report

The owner should run **Verify imported client** on the already-installed code 37,
then export. Do not request a repeated import or server gate. Assess the new private
JSON from the repository root, choosing a new output filename:

```sh
python3 android/qualification/client_report.py \
  --report /private/path/to/new-report.json \
  --apk-evidence docs/evidence/android-2026-10-25-private-client-memory-apk.json \
  --prior-import docs/evidence/android-2026-10-25-thor-code37-client-import-pass.json \
  --output /private/path/to/new-assessment.json
```

For the initial import assessment, omit `--prior-import`. Use the APK proof that
matches the actual reported private version; never substitute the public CI APK.
The checker refuses to overwrite any existing output. Only promote a sanitized,
reviewed assessment into public evidence; owner raw reports remain private.

## Qualification boundaries

- Exact reported package, version and SHA256 must match the measured APK proof.
- Require a completed, inactive, non-interrupted operation, exact completed totals,
  valid attempt/catalog/timestamps, and consistent copy/rehash flags.
- Counts must match the completed source inspection with zero unknown sizes.
- A matching process exit contradicts a complete receipt. Older unmatched exits
  remain historical observations. Unavailable exit history is never treated as a
  successful exit observation; receipt integrity can still be assessed separately.
- A verification receipt alone qualifies only its reported retained rehash. The
  combined client-integrity gate additionally requires the same catalog/counts as
  a qualified import assessment and a distinct later verification attempt.
- No copied flag can qualify WarcraftXL, gameplay, actual cache eviction or
  controlled pressure backoff. Zero pressure pauses establish that this code-37
  workload did not exercise the pause/recovery branch.
- Input size is bounded to 2 MiB; duplicate JSON keys, malformed values and arbitrary
  private fields cannot leak through the allowlisted assessment or CLI errors.

Run `python3 -m unittest discover -s android/qualification -p 'test_*.py' -v`.
The 24 synthetic regressions also run in the normal foundation CI tooling job.
Existing `python3 -m unittest discover -s tests -v` and Android qualifications
remain required. The current native source companion is unchanged: no native,
port, manifest, licensing or native reproduction-tool input changed.
