# Original world RC4 provider recovery

Code 28 recovered the same failed code-27 attempt without another native launch.
Its stderr is 326 bytes / 9 lines, SHA256
`c874def29443f077496d51e01c69be82a8a677f7fa7528880dad71ee4ed8f997`.
Compiling unchanged original `ARC4.cpp`, `OpenSSLCrypto.cpp`, `Errors.cpp` and fmt,
with the source path recovered from the pinned world ELF, reproduces that exact
hash and SIGSEGV when the legacy provider is absent. The assertion is at
`src/common/Cryptography/ARC4.cpp:27`, constructor `ARC4`, `result == 1`.
The original assertion handler deliberately faults; exit 139 alone did not prove
an accidental memory fault. No raw log, private build path or dynamic value is
published. [Physical evidence](evidence/android-2026-10-24-thor-code28-recovery.json).

The original world socket owns an `AuthCrypt`, whose two original ARC4 objects
are constructed before the initial challenge. Original provider setup calls
`OSSL_PROVIDER_load` for legacy and default without checking their return values.
The original `EVP_CIPHER_fetch("RC4")` therefore needs the legacy module even for
the launcher's receive-only socket probe. The last Auctionator log prefix is not
the cause. No module, assertion, cipher, validation rule or game data is bypassed.

With a host legacy provider exposed through the same `legacy.so` link convention,
the unchanged code exits zero and maps key `Key` / plaintext `Plaintext` to
`bbf316e8d940af0ad3`. This is host OpenSSL 3.0.13 evidence, not Android execution.
[Host proof](evidence/host-2026-10-24-world-crypto.json).

## Android module and retained identities

The original Termux OpenSSL 3.6.3 package has rotated off the checked repositories.
The private source companion v8 already contains the exact OpenSSL 3.6.3 source
archive. `tools/build_openssl_legacy.py` compiles only its legacy module using the
pinned NDK r28c, Android API 33, one compiler job and 16 KiB LOAD alignment. It links
directly against the unchanged packaged `libcrypto.so.3`; undefined symbols are
rejected by the linker. No OpenSSL source patch is applied. Two separate clean
builds produced the same 162,336-byte module SHA256:
`432951dda01dada3ca6cb2bbe522c35b43a17b37a8f796c0257ba3fd8a9a1cec`.
Only `libcrypto.so.3`, `libdl.so` and `libc.so` are needed; no RPATH/RUNPATH or
interpreter is accepted. [Build proof](evidence/android-2026-10-24-openssl-legacy.json).

The separate [pin](../manifests/openssl-legacy.json) and runtime constants verify
that module and its exact retained crypto dependency before opening the realm.
The additional APK native member is `libfta_openssl_legacy.so`. An install-scoped
private `legacy.so` symlink targets only the hash-verified immutable APK file.
Missing, changed, linked, non-ARM64 or mismatched inputs fail closed. Existing
links are verified and never repointed across APK installs.

Only the actual world session receives `OPENSSL_MODULES` pointing to this
directory and `OPENSSL_CONF=/dev/null`. The original explicit legacy/default
loads still select their algorithms. Database, auth and earlier updater actions
receive no new override. See the upstream
[provider search-path documentation](https://docs.openssl.org/3.6/man3/OSSL_PROVIDER/).

All 17 donor native payloads and all 148 runtime-manifest entries remain byte
identical. In particular, the provider manifest identity used by retained realm
and backup receipts does not change. Existing source/notice files still cover
the same OpenSSL source; the public build recipe documents the new compilation.
The packager verifies the extra module separately and expects 18 native payloads
and 222 provider/notice checks. The original certificate is mandatory.

## Reproduction and remaining gate

```sh
python3 tools/check_world_crypto.py --source-zip /private/From_The_Ashes_Source.zip \
  --world-elf /private/libfta_worldserver.so --host-provider /usr/lib/x86_64-linux-gnu/ossl-modules/legacy.so \
  --output /private/host-crypto.json --build-log /private/host-crypto.log
python3 tools/build_openssl_legacy.py --source /private/openssl-3.6.3.tar.gz \
  --ndk-zip /private/android-ndk-r28c-linux.zip --crypto-elf /private/libcrypto.so.3 \
  --output /private/libfta_openssl_legacy.so --evidence /private/legacy.json \
  --build-log /private/legacy-build.log
```

Private code 29 is the next actual world test, not a gameplay qualification.
Install it in place, retain the current assets/realm/backup, select **Start native world test** once, and keep the launcher visible until healthy. Then turn
the screen off for three minutes, reopen, select **Stop**, and export the report.
On failure export immediately before any retry. Its automatic full asset rehash
binds the new session lease; no manual import or prior database/auth test is needed.
Native fallback, world readiness and login remain false until measured runtime,
shutdown and postchecks pass, including at least 120,000 healthy screen-off ms.
