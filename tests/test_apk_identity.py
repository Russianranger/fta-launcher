"""A checked APK must contain current seed metadata, even when native bytes match."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from tools.verify_android_apk import verify


class ApkMetadataIdentityTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.stage = self.root / "provider"
        (self.stage / "assets").mkdir(parents=True)
        (self.stage / "jniLibs/arm64-v8a").mkdir(parents=True)
        (self.stage / "assets/notice.txt").write_text("Synthetic attribution fixture\n")
        (self.stage / "BUILD_PROVENANCE.json").write_text(json.dumps({"noticeProvided": True}))
        self.lock = self.root / "full-seed-lock.json"
        self.lock.write_text('{"fixture":"current metadata"}\n')
        self.apk = self.root / "fixture.apk"

    def package(self, metadata):
        header = bytearray(64)
        header[:6] = b"\x7fELF\x02\x01"
        header[18:20] = (183).to_bytes(2, "little")
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.write(self.stage / "assets/notice.txt", "assets/notice.txt")
            archive.writestr("lib/arm64-v8a/libfta_process.so", header)
            if metadata is not None:
                archive.writestr("assets/metadata/full-seed-lock.json", metadata)

    def test_packaged_metadata_matches_the_canonical_lock(self):
        self.package(self.lock.read_bytes())
        result = verify(self.apk, self.stage, self.lock)
        self.assertEqual(result["full_seed_metadata_sha256"], hashlib.sha256(self.lock.read_bytes()).hexdigest())
        self.assertFalse(result["android_execution_qualified"])

    def test_stale_same_size_metadata_is_rejected_despite_matching_provider(self):
        self.package(self.lock.read_bytes().replace(b"current", b"expired"))
        with self.assertRaisesRegex(ValueError, "metadata identity"):
            verify(self.apk, self.stage, self.lock)

    def test_missing_generated_asset_is_rejected(self):
        self.package(None)
        with self.assertRaises(KeyError):
            verify(self.apk, self.stage, self.lock)

    def test_duplicate_asset_is_rejected(self):
        self.package(self.lock.read_bytes())
        import warnings
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.apk, "a") as archive:
                archive.writestr("assets/metadata/full-seed-lock.json", self.lock.read_bytes())
        with self.assertRaisesRegex(ValueError, "duplicate"):
            verify(self.apk, self.stage, self.lock)
