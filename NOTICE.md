# Attribution and distribution boundaries

The original repository license is preserved in `LICENSE`. New launcher and
tooling code in this foundation is independently written under that MIT license.
Reference projects were inspected for architecture; their implementation is not
vendored in this foundation.

| External component | Reference | License/distribution handling |
| --- | --- | --- |
| Customized From the Ashes / AzerothCore source | 2026-10-20 source archive; base core `7086d18344ae00b39c2809e4f80411b804675cbe` | Root GNU GPL v2; module-specific notices must accompany any later redistribution. Kept external. |
| Pocket Realm Android host | `Russianranger/pocket-realm` at `f397b7a13c844685898b348f5f602dfb6327ce5e` | GPL v3; any future copied implementation must retain notices and compatible licensing. Architecture reference only. |
| Ashen Order Android build reference | `Russianranger/ashen-order-android` at `598dc2abb51e64322a4f2e3c3b31d142836e1109` | AGPL v3; toolchain reference only. |
| Gradle wrapper | Official Gradle 8.9 distribution/tag | Apache License 2.0; wrapper notice and license accompany the wrapper. |
| MariaDB and future runtime dependencies | Not bundled yet | Record exact provider hashes, source and licenses before packaging. |

The matching database seed contains populated account and character tables. It is
a private installation input, not distributable launcher content. Game clients,
MPQs, DBC, maps, vmaps, mmaps and WarcraftXL assets are user-supplied inputs and
are not included in this repository.

No compatibility with the external game or database is implied by an Android
foundation build. No upstream repository was changed.
