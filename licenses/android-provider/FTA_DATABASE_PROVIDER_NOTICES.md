# From the Ashes — database provider subset

This package includes the unmodified MariaDB 12.3.2 ARM64/Bionic database
provider received in Pocket Realm v0.100.2-alpha: the database server and client,
nine private dynamic libraries, and original MariaDB system/helper data. Exact
container, package and ELF identities are recorded in the accompanying provider
lock and build provenance.

The complete received `POCKET_REALM_THIRD_PARTY_NOTICES.md` is preserved
unchanged, including its upstream three-year corresponding-source offer. That
document describes the complete upstream application. Its references to Pocket
Realm application code, CMaNGOS, Playerbots, game content, Wine, Box64 and graphics
runtimes do not mean those components are included in this FTA database subset.
Only the provider's declared eleven ELF files and system data are included.

| Included component | Pinned package | License evidence |
| --- | --- | --- |
| MariaDB server/client and system data | 2:12.3.2 | GPL-2.0; matching source `COPYING` supplied |
| OpenSSL libcrypto/libssl | 1:3.6.3 | Apache-2.0; matching source license supplied |
| libc++ shared runtime | 29 / Android NDK r29 | Received upstream notice: Apache-2.0 with LLVM exception; historical Termux recipe labels it NCSA |
| libandroid-support | 29-1 | Matching source and historical recipe: Apache-2.0/MIT, including embedded notices |
| wcwidth routines embedded by libandroid-support | v4 | Matching source license supplied |
| libcrypt | 0.2-6 | BSD-2-Clause; actual FreeBSD copyright and permission notice supplied |
| libedit | 20260512-3.1-0 | BSD-3-Clause; matching source `COPYING` supplied |
| ncurses | 6.6.20260307+really6.5.20250830 | MIT-style; matching source snapshot `COPYING` supplied |
| PCRE2 | 10.47 | BSD-3-Clause with PCRE2 exception and embedded JIT license; matching complete license supplied |
| zlib | 1.3.2 | zlib license; matching source license supplied |

The preserved upstream notice identifies libandroid-support as GPL-3.0.
The version-matching Termux recipe and the original v29/v4 source notices
instead declare Apache-2.0/MIT and retain their embedded copyright notices.
This correction accompanies the unchanged received document; it does not edit
or silently replace it. Full GPL-2.0, GPL-3.0 and Apache-2.0 terms are also
included for the licenses referenced by the received notice.

`SOURCE_EVIDENCE.json` records version-matching historical recipe commits,
URLs, hashes and the scope of independently retrieved license files. The
received upstream source offer is distinguished from an independently verified
complete corresponding-source archive or a reproduced binary build. This FTA
notice does not claim that every source archive has been independently verified
or that these binaries have been rebuilt from those archives.

The FTA staging tools and Android host code are separate independently written
components. MariaDB is an isolated native process. No MariaDB executable bytes
are changed, relinked into the JVM, or copied from writable app storage for
execution. Each original component retains its own license and copyrights.
