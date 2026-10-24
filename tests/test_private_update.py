import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from package_private_update import CERTIFICATE, METADATA, check_helper_sources, compare_output, inventory, overlay, sha, verify_signature


class PrivateUpdateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.base = {'classes.dex': b'qualified application', 'AndroidManifest.xml': b'qualified version',
                     'resources.arsc': b'qualified resources', 'META-INF/services/provider': b'keep service metadata',
                     'META-INF/CERT.RSA': b'old disposable signature', 'assets/runtime/data/provider': b'public',
                     'lib/arm64-v8a/libfta_worldserver.so': b'not the private server'}
        self.old = {'classes.dex': b'old app must never replace the new app',
                    'lib/arm64-v8a/libfta_worldserver.so': b'synthetic original native payload',
                    'assets/runtime/data/provider': b'synthetic provider data',
                    'assets/runtime/notices/source.txt': b'synthetic attribution'}
        manifest = {'files': [{'path': 'data/provider', 'size': len(self.old['assets/runtime/data/provider']),
                              'sha256': sha(self.old['assets/runtime/data/provider'])}]}
        self.old['assets/fta-runtime-manifest.json'] = json.dumps(manifest).encode()
        for member, path in METADATA.items():
            target = self.root / path; target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(path.encode())
            self.base[member] = self.old[member] = target.read_bytes()
        self.proof = {'nativePayloadSha256': {n: sha(b) for n, b in self.old.items() if n.startswith('lib/')},
                      'runtimeManifestSha256': sha(self.old['assets/fta-runtime-manifest.json']),
                      'apk_sha256': 'a' * 64, 'nativeSourceCompanionSha256': 'b' * 64}

    def archive(self, name, data):
        path = self.root / name
        with zipfile.ZipFile(path, 'w') as z:
            for key, value in data.items(): z.writestr(key, value)
        return path

    def build(self, base=None, old=None, proof=None):
        assembly = self.archive('base.apk', self.base if base is None else base)
        donor = self.archive('old.apk', self.old if old is None else old)
        output = self.root / 'final.apk'
        overlay(assembly, donor, output, self.root / 'stage', self.root, self.proof if proof is None else proof)
        return output, assembly, donor

    def test_application_and_service_metadata_preserved_only_private_inputs_replaced(self):
        output, base, old = self.build(); compare_output(output, base, old)
        with zipfile.ZipFile(output) as z:
            self.assertEqual(self.base['classes.dex'], z.read('classes.dex'))
            self.assertEqual(self.old['lib/arm64-v8a/libfta_worldserver.so'], z.read('lib/arm64-v8a/libfta_worldserver.so'))
            self.assertEqual(b'keep service metadata', z.read('META-INF/services/provider'))
            self.assertNotIn('META-INF/CERT.RSA', z.namelist())

    def test_changed_or_extra_native_payloads_fail_closed(self):
        for name in ('lib/arm64-v8a/libfta_worldserver.so', 'lib/arm64-v8a/unreviewed.so'):
            old = dict(self.old); old[name] = b'changed'
            with self.assertRaises(ValueError): self.build(old=old)

    def test_altered_assembly_or_donor_metadata_cannot_be_overlaid(self):
        for member in METADATA:
            for side in ('base', 'old'):
                data = dict(self.base if side == 'base' else self.old); data[member] = b'altered'
                with self.assertRaises(ValueError): self.build(**{side: data})

    def test_runtime_manifest_and_member_identity_are_both_checked(self):
        for member in ('assets/fta-runtime-manifest.json', 'assets/runtime/data/provider'):
            old = dict(self.old); old[member] = b'altered'
            with self.assertRaises(ValueError): self.build(old=old)

    def test_extra_final_member_or_mutated_dex_cannot_pass_comparison(self):
        output, base, old = self.build()
        with zipfile.ZipFile(output) as z: good = {n: z.read(n) for n in z.namelist()}
        for name in ('classes.dex', 'assets/unexpected'):
            changed = dict(good); changed[name] = b'changed'
            path = self.archive('altered.apk', changed)
            with self.assertRaises(ValueError): compare_output(path, base, old)

    def test_duplicate_traversal_and_symlink_members_rejected(self):
        for names in (['x', 'x'], ['../x'], ['/absolute'], ['a\\b']):
            path = self.root / 'bad.apk'
            with warnings.catch_warnings(), zipfile.ZipFile(path, 'w') as z:
                warnings.simplefilter('ignore')
                for name in names: z.writestr(name, b'x')
            with zipfile.ZipFile(path) as z:
                with self.assertRaises(ValueError): inventory(z)
        path = self.root / 'link.apk'
        with zipfile.ZipFile(path, 'w') as z:
            entry = zipfile.ZipInfo('link'); entry.external_attr = 0o120777 << 16; z.writestr(entry, b'target')
        with zipfile.ZipFile(path) as z:
            with self.assertRaises(ValueError): inventory(z)

    def test_changed_native_helper_sources_cannot_reuse_old_binary(self):
        directory = self.root / 'native/process-supervisor'; directory.mkdir(parents=True)
        (directory / 'process_owner.cpp').write_bytes(b'supervisor')
        (directory / 'game_data_cache.cpp').write_bytes(b'cache')
        proof = {'processSupervisorSourceSha256': {'native/process-supervisor/process_owner.cpp': sha(b'supervisor')},
                 'cacheHelperSourceSha256': sha(b'cache')}
        check_helper_sources(self.root, proof)
        for field in ('processSupervisorSourceSha256', 'cacheHelperSourceSha256'):
            bad = copy.deepcopy(proof)
            if field == 'cacheHelperSourceSha256': bad[field] = sha(b'changed')
            else: bad[field]['native/process-supervisor/process_owner.cpp'] = sha(b'changed')
            with self.assertRaises(ValueError): check_helper_sources(self.root, bad)

    def test_only_one_persistent_v2_signer_is_accepted(self):
        good = 'Verified using v2 scheme (APK Signature Scheme v2): true\nSigner #1 certificate SHA-256 digest: ' + CERTIFICATE
        with patch('package_private_update.subprocess.check_output', return_value=good):
            verify_signature(Path('apksigner'), Path('synthetic.apk'))
        for bad in (good.replace('true', 'false'), good.replace(CERTIFICATE, '0' * 64),
                    good + '\nSigner #2 certificate SHA-256 digest: ' + CERTIFICATE):
            with patch('package_private_update.subprocess.check_output', return_value=bad):
                with self.assertRaises(ValueError): verify_signature(Path('apksigner'), Path('synthetic.apk'))


if __name__ == '__main__':
    unittest.main()
