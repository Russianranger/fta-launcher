#!/usr/bin/env python3
"""Verify APK bytes against the exact provider stage, including every notice."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile


def digest(stream):
    value = hashlib.sha256()
    for block in iter(lambda: stream.read(1024 * 1024), b""):
        value.update(block)
    return value.hexdigest()


def verify(apk: Path, stage: Path) -> dict:
    proof = json.loads((stage / "BUILD_PROVENANCE.json").read_text())
    if not proof.get("noticeProvided") or proof.get("redistributionNoticePending"):
        raise ValueError("Provider attribution and source evidence are incomplete")
    expected = []
    for root_name, apk_prefix in (("assets", "assets"), ("jniLibs/arm64-v8a", "lib/arm64-v8a")):
        root = stage / root_name
        if not root.is_dir():
            raise ValueError("Missing provider stage directory")
        for path in sorted(root.rglob("*")):
            if path.is_symlink():
                raise ValueError("Provider stage contains a filesystem link")
            if path.is_file():
                expected.append((path, apk_prefix + "/" + path.relative_to(root).as_posix()))
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("APK contains duplicate ZIP names")
        for path, member in expected:
            info = archive.getinfo(member)
            if info.file_size != path.stat().st_size:
                raise ValueError("Packaged provider size changed: " + member)
            with path.open("rb") as original, archive.open(info) as packaged:
                if digest(original) != digest(packaged):
                    raise ValueError("Packaged provider identity changed: " + member)
        with archive.open("lib/arm64-v8a/libfta_process.so") as native:
            header = native.read(64)
        if (len(header) != 64 or header[:6] != b"\x7fELF\x02\x01" or
                int.from_bytes(header[18:20], "little") != 183):
            raise ValueError("Native process supervisor is not ELF64 ARM64")
    with apk.open("rb") as source:
        apk_digest = digest(source)
    return {"status": "apk_identity_passed", "apk_sha256": apk_digest,
            "provider_and_notice_files_verified": len(expected),
            "android_execution_qualified": False}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--provider-stage", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(verify(args.apk, args.provider_stage), indent=2))
