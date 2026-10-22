# From the Ashes — private compiled world runtime

This private diagnostic package adds the original From the Ashes worldserver,
all 52 supplied module directories, original static scripts and Lua 5.2.4 to the
previously staged database/auth runtime. The database provider and the historical
resolver-v3 authserver retain their original bytes and separate provenance.
Building a new worldserver does not rebuild or requalify that authserver.

The original core's worldserver source notice permits GNU GPL version 2 or, at
the recipient's option, any later version. For this combined worldserver and
GNU Readline 8.3.3 runtime, the later-version option is exercised under GNU GPL
version 3. GNU Readline is GPL-3.0-or-later. Full original core/module/dependency
license files, the original AUTHORS file, Readline copyright/readme and GPL-3.0
terms accompany this package. The original license texts are preserved. Each
component retains its copyrights, notices and applicable terms; this election
does not relicense an independently licensed component.

Readline 8.3.3 is Readline 8.3 with upstream patches 001, 002 and 003. Boost
1.83.0 is used statically under the Boost Software License 1.0. Lua 5.2.4 is
used statically under the MIT license; its copyright is held by Lua.org,
PUC-Rio. MariaDB Connector/C 3.4.9 is a separate shared library under
LGPL-2.1-or-later. The existing provider's OpenSSL, ncurses and other shared
library notices remain unchanged. Android NDK r28c runtime notices accompany
the newly linked static runtime; the provider's distinct NDK r29 notice is
also retained.

WORLD_SOURCE_EVIDENCE.json records exact source/archive/patch/notice identities.
WORLD_BUILD_PROVENANCE.json separately records the completed world build,
the reviewed source profile, actual linked static target evidence and exact ELF
identity. The corresponding-source companion contains the original supplied
source archive, reviewed port patches/build recipes and pinned dependency source
archives/patches. The original core source is supplied privately to its original
source owner. This package and its evidence do not grant permission to publish
that private source or any game/client assets.

These materials are for the original source owner's private APK diagnostic.
Public redistribution and a complete playable realm have not been qualified.
Staging and linking evidence does not establish Android execution, successful
database updates, map availability, world listener operation or gameplay. The
compiled updater diagnostic uses a fresh disposable seed and original normal
world startup, with missing maps as the barrier before a world listener.
