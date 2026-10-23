# World-column diagnostic follow-up

Physical version 8 completed the original three-database updater and prepared
statements, reached the deliberate missing-map boundary and shut MariaDB down
cleanly. Its overall gate failed only the world column fingerprint (23/24 final
checks passed). The exported boolean cannot identify which field differs, and
the passing 15-check seed baseline did not fingerprint columns. Preserve the
failure; changing the expected hash would not be justified.

Version 9 leaves the strict validation SQL, expected-result lock, original SQL
and all native bytes unchanged. It records read-only world catalog fingerprints
immediately before world startup and after the accepted missing-map boundary,
before strict postconditions can throw. Separate native stages keep the existing
three `WORLD_VALIDATION` invocations authoritative. Observational data never
contributes to `compiledUpdaterQualified`.

Each snapshot contains the existing whole-column count/byte/hash aggregate and
complete per-table count/byte/hash entries. Numeric table indices refer to the
signed post-lock table list; the seed subset has 371 tables and the post state
has 372. Four fixed probes correspond to `creature_template.speed_run`,
`mod_fake_player_ambience_go_target.stand_distance`,
`mod_fake_player_ambience_npc_target.stand_distance`, and
`mod_fake_player_ambience_profile.interact_distance`. They export hashes of the
canonical row and type/null/default/collation/extra components. No native output,
SQL, defaults, table/column text from query results or player data is exported.
Floating-default formatting is an unproven candidate, not a diagnosis.

The parser rejects missing, duplicate, unknown, reordered, malformed or truncated
observations. Snapshot counts/byte sums must match the global aggregate. The
atomic receipt is bound to the current attempt, world ELF, updater lock and source
tree; export reconstructs only typed fields. SQL parsing is bounded at 128 KiB,
while the existing client cap remains 64 KiB; receipt and pretty export bounds
are 160/200 KiB. The diagnostic adds no global privileges or SQL writes beyond
the existing session concatenation limit.

The actual compiled Kotlin `schemaSql` emitted 17,945 bytes, SHA256
`ba8a9f4b9be545eab80228eee6b5a6f96472878183dd4a7fa03a561fd000247e`.
It ran on a fresh exact MariaDB 12.3.2 host after all 563 seed tables/one trigger,
before and after the original source-policy SQL model. Both utf8 and utf8mb4
clients produced identical snapshots; outputs were 38,412/38,510 bytes. The real
Kotlin parser and receipt round-trip validated both full snapshots. All original
postconditions still matched, metadata remained unchanged, and the disposable
sandbox shut down. The pretty export measured 142,271 bytes. This does not
execute or qualify the Bionic worldserver.

Hash-only expected host snapshots are in
`evidence/database-2026-10-22-world-column-snapshots.json`; exact query/class
identity and measured results are in
`evidence/database-2026-10-22-world-column-production-sql.json`. The device report
is recorded separately in
`evidence/android-2026-10-22-thor-compiled-updater-attempt.json`.

Install private version 9 as an update and rerun **Test compiled three-database
updater**. Export the report even if the unchanged strict check still fails.
Compare its before/after hashes with the host fixture before altering any schema
expectation. Existing native corresponding source remains applicable because
all 16 native files are unchanged; launcher changes are in the public repository.
