# Attribution and distribution boundaries

The original repository license is preserved in `LICENSE`. New launcher and
tooling code in this foundation is independently written under that MIT license.
Reference projects were inspected for architecture; their launcher implementation
is not vendored here. Reviewed patches under `native-port/` are derivative of the
original GPL-2.0-or-later core and retain its license; the independent patch
application/build tools retain MIT licensing.

| External component | Reference | License/distribution handling |
| --- | --- | --- |
| Customized From the Ashes / AzerothCore source | 2026-10-20 source archive; base core `7086d18344ae00b39c2809e4f80411b804675cbe` | Root GNU GPL v2; module-specific notices must accompany any later redistribution. Kept external. |
| Pocket Realm Android host | `Russianranger/pocket-realm` at `f397b7a13c844685898b348f5f602dfb6327ce5e` | GPL v3; any future copied implementation must retain notices and compatible licensing. Architecture reference only. |
| Ashen Order Android build reference | `Russianranger/ashen-order-android` at `598dc2abb51e64322a4f2e3c3b31d142836e1109` | AGPL v3; toolchain reference only. |
| Gradle wrapper | Official Gradle 8.9 distribution/tag | Apache License 2.0; wrapper notice and license accompany the wrapper. |
| MariaDB 12.3.2 Bionic provider | Exact pinned external-process provider in `manifests/android-provider-lock.json` | GPL v2 server; component-specific terms/notices and source references accompany staged artifacts. No provider ELF is committed to Git. |

The matching database seed contains populated account and character tables. It is
a private installation input, not distributable launcher content. Game clients,
MPQs, DBC, maps, vmaps, mmaps and WarcraftXL assets are user-supplied inputs and
are not included in this repository.

The host seed and runtime SQL probe has measured compatibility with MariaDB
12.3.2; that does not establish Android or game-runtime compatibility. The
packaged database diagnostic requires physical-device execution. No upstream
repository was changed.
