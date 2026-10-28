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
from package_private_update import CERTIFICATE, METADATA, LEGACY, FAULT, WORLD, CLIENT_LIBS, ISOLATION_LIBS, check_helper_sources, compare_output, inventory, overlay, sha, verify_signature, verify_legacy, verify_fault


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

    def test_verified_client_bridge_and_notices_survive_donor_overlay(self):
        base = dict(self.base, **{name: name.encode() for name in CLIENT_LIBS})
        base['assets/client-probe/NOTICE.txt'] = b'synthetic component notices'
        with patch('stage_client_probe.verify_apk') as verify:
            output, assembly, donor = self.build(base=base)
            verify.assert_called_once()
        compare_output(output, assembly, donor)
        with zipfile.ZipFile(output) as archive:
            for name in CLIENT_LIBS: self.assertEqual(base[name], archive.read(name))
        self.assertEqual(b'synthetic component notices', (self.root / 'stage/assets/client-probe/NOTICE.txt').read_bytes())

    def test_partial_or_unqualified_client_bridge_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Incomplete client bridge'):
            self.build(base=dict(self.base, **{next(iter(CLIENT_LIBS)): b'partial'}))
        with patch('stage_client_probe.verify_apk', side_effect=ValueError('unqualified')):
            with self.assertRaisesRegex(ValueError, 'unqualified'):
                self.build(base=dict(self.base, **{name: b'unqualified' for name in CLIENT_LIBS}))

    def test_new_guard_is_verified_separately_and_preserves_original_bridge(self):
        base = dict(self.base, **{name: name.encode() for name in CLIENT_LIBS | ISOLATION_LIBS})
        base['assets/client-isolation/NOTICE.txt'] = b'synthetic isolation notice'
        with patch('stage_client_probe.verify_apk'), patch('stage_client_readonly.verify_apk') as verify:
            output, assembly, donor = self.build(base=base)
            verify.assert_called_once()
        compare_output(output, assembly, donor)
        with zipfile.ZipFile(output) as archive:
            for name in CLIENT_LIBS | ISOLATION_LIBS: self.assertEqual(base[name], archive.read(name))
        self.assertEqual(b'synthetic isolation notice', (self.root / 'stage/assets/client-isolation/NOTICE.txt').read_bytes())

    def test_guard_without_loader_and_notices_is_rejected(self):
        for additions in (ISOLATION_LIBS, {next(iter(ISOLATION_LIBS))}):
            with self.assertRaisesRegex(ValueError, 'Incomplete isolation bridge'):
                self.build(base=dict(self.base, **{n: b'partial' for n in additions}))

    def test_changed_guard_cannot_silently_replace_donor_payload(self):
        old = dict(self.old, **{n: b'old guard' for n in ISOLATION_LIBS})
        base = dict(self.base, **{n: b'changed guard' for n in CLIENT_LIBS | ISOLATION_LIBS})
        with patch('stage_client_probe.verify_apk'), patch('stage_client_readonly.verify_apk'):
            with self.assertRaisesRegex(ValueError, 'Existing isolation bridge must not change silently'):
                self.build(base=base, old=old)

    def test_client_bridge_cannot_outlive_its_current_assembly_notices(self):
        old = dict(self.old, **{name: b'previous bridge' for name in CLIENT_LIBS})
        with self.assertRaisesRegex(ValueError, 'Retained client bridge requires'):
            self.build(old=old)

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

    def module_fixture(self):
        module = self.root / 'legacy.so'; module.write_bytes(b'synthetic module')
        elf = {'needed': ['libc.so', 'libcrypto.so.3', 'libdl.so'], 'sonames': [],
               'interpreter': None, 'minimumLoadAlignment': 16384}
        crypto_name = 'lib/arm64-v8a/libcrypto.so'
        self.old[crypto_name] = b'synthetic crypto'
        manifest = json.loads(self.old['assets/fta-runtime-manifest.json'])
        manifest['files'].append({'path': 'native/libcrypto.so', 'size': len(self.old[crypto_name]),
            'sha256': sha(self.old[crypto_name]), 'logicalName': 'libcrypto.so.3'})
        self.old['assets/fta-runtime-manifest.json'] = json.dumps(manifest).encode()
        self.proof['runtimeManifestSha256'] = sha(self.old['assets/fta-runtime-manifest.json'])
        self.proof['nativePayloadSha256'][crypto_name] = sha(self.old[crypto_name])
        (self.root / 'manifests/openssl-legacy.json').write_text(json.dumps({
            'moduleBytes': module.stat().st_size, 'moduleSha256': sha(module.read_bytes()),
            'retainedCryptoSha256': sha(self.old[crypto_name]), 'elf': elf}))
        return module, elf

    def test_separate_provider_preserves_entire_retained_manifest_and_donor_members(self):
        module, elf = self.module_fixture()
        assembly = self.archive('base.apk', self.base); donor = self.archive('old.apk', self.old)
        output = self.root / 'final.apk'
        with patch('package_private_update.inspect_elf', return_value=elf):
            overlay(assembly, donor, output, self.root / 'stage', self.root, self.proof, module)
        compare_output(output, assembly, donor, module)
        with zipfile.ZipFile(output) as z:
            self.assertEqual(self.old['assets/fta-runtime-manifest.json'], z.read('assets/fta-runtime-manifest.json'))
            self.assertEqual(module.read_bytes(), z.read(LEGACY))
            self.assertEqual(len(self.proof['nativePayloadSha256']) + 1, len([n for n in z.namelist() if n.startswith('lib/')]))

    def test_module_missing_changed_linked_or_wrong_elf_is_rejected(self):
        module, elf = self.module_fixture()
        with patch('package_private_update.inspect_elf', return_value=elf):
            verify_legacy(module, self.root)
            link = self.root / 'linked.so'; link.symlink_to(module)
            with self.assertRaises(ValueError): verify_legacy(link, self.root)
            with self.assertRaises(ValueError): verify_legacy(self.root / 'absent.so', self.root)
            module.write_bytes(b'changed module')
            with self.assertRaises(ValueError): verify_legacy(module, self.root)
        module.write_bytes(b'synthetic module')
        for key, value in (('needed', ['unexpected.so']), ('minimumLoadAlignment', 4096), ('interpreter', '/untrusted')):
            changed = dict(elf); changed[key] = value
            with patch('package_private_update.inspect_elf', return_value=changed):
                with self.assertRaises(ValueError): verify_legacy(module, self.root)

    def test_module_cannot_be_added_to_unmatched_retained_crypto(self):
        module, elf = self.module_fixture()
        pin = self.root / 'manifests/openssl-legacy.json'; values = json.loads(pin.read_text())
        values['retainedCryptoSha256'] = '0' * 64; pin.write_text(json.dumps(values))
        assembly = self.archive('base.apk', self.base); donor = self.archive('old.apk', self.old)
        with patch('package_private_update.inspect_elf', return_value=elf):
            with self.assertRaisesRegex(ValueError, 'Retained crypto'):
                overlay(assembly, donor, self.root / 'final.apk', self.root / 'stage', self.root, self.proof, module)

    def test_fault_addition_preserves_existing_legacy_and_entire_donor(self):
        module, elf = self.module_fixture()
        self.old[LEGACY] = module.read_bytes(); self.proof['nativePayloadSha256'][LEGACY] = sha(module.read_bytes())
        fault = self.root / 'fault.so'; fault.write_bytes(b'synthetic fault')
        source = self.root / 'native/world-fault/world_fault.c'; source.parent.mkdir(parents=True); source.write_bytes(b'synthetic source')
        (self.root / 'manifests/world-fault.json').write_text(json.dumps({'moduleBytes': fault.stat().st_size,
            'moduleSha256': sha(fault.read_bytes()), 'sourceSha256': sha(source.read_bytes()), 'elf': elf}))
        (self.root / 'LICENSE').write_bytes(b'synthetic notice')
        self.base['assets/metadata/launcher-LICENSE.txt'] = b'synthetic notice'
        assembly = self.archive('base.apk', self.base); donor = self.archive('old.apk', self.old); output = self.root / 'final.apk'
        with patch('package_private_update.inspect_elf', return_value=elf):
            overlay(assembly, donor, output, self.root / 'stage', self.root, self.proof, module, fault)
            compare_output(output, assembly, donor, module, fault)
            source.write_bytes(b'changed')
            with self.assertRaises(ValueError): verify_fault(fault, self.root)
        with zipfile.ZipFile(output) as archive:
            self.assertEqual(fault.read_bytes(), archive.read(FAULT))
            self.assertEqual(self.old['assets/fta-runtime-manifest.json'], archive.read('assets/fta-runtime-manifest.json'))

    def test_world_addition_preserves_baseline_world_and_receipt_identity(self):
        world = self.root / 'world.so'; world.write_bytes(b'synthetic repaired world')
        pin = {'synthetic': 'module validation is separately tested'}
        (self.root / 'manifests/world-session.json').write_text(json.dumps(pin))
        self.base['assets/metadata/world-session.json'] = json.dumps(pin).encode()
        assembly = self.archive('base.apk', self.base); donor = self.archive('old.apk', self.old); output = self.root / 'final.apk'
        with patch('package_private_update.verify_world_module') as verify:
            overlay(assembly, donor, output, self.root / 'stage', self.root, self.proof, world=world)
            verify.assert_called_once_with(world, pin, self.root)
        compare_output(output, assembly, donor, world=world)
        with zipfile.ZipFile(output) as archive:
            self.assertEqual(world.read_bytes(), archive.read(WORLD))
            for name in self.old:
                if name.startswith(('lib/', 'assets/runtime/')) or name == 'assets/fta-runtime-manifest.json':
                    self.assertEqual(self.old[name], archive.read(name))
        self.base['assets/metadata/world-session.json'] = b'changed'
        assembly = self.archive('changed.apk', self.base)
        with patch('package_private_update.verify_world_module'):
            with self.assertRaisesRegex(ValueError, 'Assembled world session metadata'):
                overlay(assembly, donor, self.root / 'refused.apk', self.root / 'refused-stage', self.root, self.proof, world=world)

    def test_existing_world_addon_cannot_be_silently_overwritten(self):
        world = self.root / 'world.so'; world.write_bytes(b'repaired world')
        (self.root / 'manifests/world-session.json').write_text('{}')
        self.base['assets/metadata/world-session.json'] = b'{}'
        self.old[WORLD] = b'different existing world'; self.proof['nativePayloadSha256'][WORLD] = sha(self.old[WORLD])
        with patch('package_private_update.verify_world_module'):
            with self.assertRaisesRegex(ValueError, 'must not be silently replaced'):
                overlay(self.archive('base.apk', self.base), self.archive('old.apk', self.old), self.root / 'refused.apk',
                        self.root / 'stage', self.root, self.proof, world=world)


if __name__ == '__main__':
    unittest.main()
