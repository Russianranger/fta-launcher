# Repository instructions

Read `docs/SHORT-HANDOFF.md` before development. Work only in this repository;
Pocket Realm and Ashen Order are read-only references unless separately authorized.

- Keep database seeds, player/account records, client/game assets, private runtime
  configs and generated binaries out of Git. Run `tools/check_repository.py`
  after staging a commit. Keep original archive hashes and source attribution.
- Preserve all 52 source modules and C++20. Do not claim an Android native build
  from a host build, or Thor/world readiness from a foundation APK.
- Never replay the populated fresh-install database seed over live saves.
  SQL compatibility failures and receipt mismatches are blockers to diagnose.
- Native builds default to one job, permit at most two, and must use memory
  checks. Avoid concurrent heavyweight native builds. Keep logs on disk and
  agent handoffs short rather than pasting source or compiler dumps into chat.
- Use `python3 -m unittest discover -s tests -v` for tooling. Android foundation
  qualification is JDK17/SDK35 `testDebugUnitTest lintDebug assembleDebug` via
  its pinned Gradle wrapper; inspect exact-commit CI results.
- Document missing evidence and the next concrete gate in the short handoff.
