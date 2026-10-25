#!/usr/bin/env python3
"""Retain verified private native inputs in a separately qualified CI assembly.

Run only after checking exact-commit CI. No native compilation, seed data,
credentials, or private APK bytes are added to the repository by this tool.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import tempfile
import zipfile

from verify_android_apk import verify
from stage_android_provider import inspect_elf
from verify_world_session import verify_module as verify_world_module

ROOT = Path(__file__).resolve().parents[1]
CERTIFICATE = '3e6705b6dc4a6bf3e7109f5322ec51d5ff094a4d912560cd9c1bb826f88754a6'
HELPERS = {'lib/arm64-v8a/libfta_process.so', 'lib/arm64-v8a/libfta_game_io.so'}
LEGACY = 'lib/arm64-v8a/libfta_openssl_legacy.so'
FAULT = 'lib/arm64-v8a/libfta_world_fault_v2.so'
WORLD = 'lib/arm64-v8a/libfta_world_session.so'
METADATA = {'assets/metadata/full-seed-lock.json': 'manifests/full-seed-lock.json',
            'assets/metadata/game-data-policy.properties': 'manifests/game-data-policy.properties'}


def require(ok, message):
    if not ok:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def file_sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def retained(name):
    return name.startswith(('lib/', 'assets/runtime/')) or name == 'assets/fta-runtime-manifest.json'


def signature(name):
    return name.startswith('META-INF/') and (name.endswith(('.SF', '.RSA', '.DSA', '.EC')) or name == 'META-INF/MANIFEST.MF')


def inventory(archive):
    names = archive.namelist()
    require(len(names) == len(set(names)), 'Duplicate APK member')
    for entry in archive.infolist():
        path = PurePosixPath(entry.filename)
        require(not path.is_absolute() and '..' not in path.parts and '\\' not in entry.filename and
                '\0' not in entry.filename and not stat.S_ISLNK(entry.external_attr >> 16), 'Unsafe APK member')
    return names


def check_helper_sources(root, proof):
    for name, expected in proof['processSupervisorSourceSha256'].items():
        require(name.startswith('native/process-supervisor/') and '..' not in PurePosixPath(name).parts, 'Unsafe source path')
        require(file_sha(root / name) == expected, 'Supervisor source identity differs')
    require(file_sha(root / 'native/process-supervisor/game_data_cache.cpp') == proof['cacheHelperSourceSha256'],
            'Cache helper source identity differs')


def verify_legacy(module, root=ROOT):
    pin = json.loads((root / 'manifests/openssl-legacy.json').read_text())
    require(not module.is_symlink() and module.is_file() and module.stat().st_size == pin['moduleBytes'] and
            file_sha(module) == pin['moduleSha256'], 'OpenSSL provider identity differs')
    require(inspect_elf(module, reject_search_paths=True) == pin['elf'], 'OpenSSL provider ELF metadata differs')
    return pin


def verify_fault(module, root=ROOT):
    pin = json.loads((root / 'manifests/world-fault.json').read_text())
    require(not module.is_symlink() and module.is_file() and module.stat().st_size == pin['moduleBytes'] and
            file_sha(module) == pin['moduleSha256'], 'Fault witness identity differs')
    require(file_sha(root / 'native/world-fault/world_fault.c') == pin['sourceSha256'], 'Fault witness source differs')
    require(inspect_elf(module, reject_search_paths=True) == pin['elf'], 'Fault witness ELF metadata differs')
    return pin


def overlay(assembly, donor, output, stage, root, proof, module=None, fault=None, world=None):
    if module is not None:
        verify_legacy(module, root)
    if fault is not None:
        verify_fault(fault, root)
    if world is not None:
        verify_world_module(world, json.loads((root / 'manifests/world-session.json').read_text()), root)
    additions = {name: file for name, file in ((LEGACY, module), (FAULT, fault), (WORLD, world)) if file is not None}
    with zipfile.ZipFile(assembly) as base, zipfile.ZipFile(donor) as old:
        inventory(base); old_names = inventory(old)
        if fault is not None:
            require(base.read('assets/metadata/launcher-LICENSE.txt') == (root / 'LICENSE').read_bytes(),
                    'Native helper license differs')
        if world is not None:
            require(base.read('assets/metadata/world-session.json') == (root / 'manifests/world-session.json').read_bytes(),
                    'Assembled world session metadata differs')
        require({n: sha(old.read(n)) for n in old_names if n.startswith('lib/')} == proof['nativePayloadSha256'],
                'Donor native payload inventory differs')
        for member, source in METADATA.items():
            require(base.read(member) == old.read(member) == (root / source).read_bytes(), 'Canonical metadata differs')
        manifest_bytes = old.read('assets/fta-runtime-manifest.json')
        require(sha(manifest_bytes) == proof['runtimeManifestSha256'], 'Runtime manifest identity differs')
        manifest = json.loads(manifest_bytes)
        if module is not None:
            pin = json.loads((root / 'manifests/openssl-legacy.json').read_text())
            require(any(e.get('logicalName') == 'libcrypto.so.3' and e['sha256'] == pin['retainedCryptoSha256']
                        for e in manifest['files']), 'Retained crypto dependency differs')
        for name, file in additions.items():
            if name in old_names:
                require(old.read(name) == file.read_bytes(), 'Existing provider must not be silently replaced')
        for entry in manifest['files']:
            member = ('lib/arm64-v8a/' + entry['path'][7:]) if entry['path'].startswith('native/') else 'assets/runtime/' + entry['path']
            data = old.read(member)
            require(len(data) == entry['size'] and sha(data) == entry['sha256'], 'Runtime manifest member differs')
        with zipfile.ZipFile(output, 'w') as final:
            for entry in base.infolist():
                if not retained(entry.filename) and not signature(entry.filename):
                    final.writestr(entry, base.read(entry))
            for entry in old.infolist():
                name = entry.filename
                if retained(name) and name not in additions:
                    data = old.read(entry); final.writestr(entry, data)
                    if name not in HELPERS:
                        relative = name.replace('lib/arm64-v8a/', 'jniLibs/arm64-v8a/', 1) if name.startswith('lib/') else name
                        target = stage / relative
                        target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(data)
            for name, file in additions.items():
                data = file.read_bytes()
                final.writestr(name, data, compress_type=zipfile.ZIP_DEFLATED)
                target = stage / name.replace('lib/arm64-v8a/', 'jniLibs/arm64-v8a/', 1)
                target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(data)
        # This is a recovery attestation, not a newly compiled provider provenance.
        (stage / 'BUILD_PROVENANCE.json').write_text(json.dumps({
            'noticeProvided': True, 'redistributionNoticePending': False,
            'recoverySourceApkSha256': proof['apk_sha256'],
            'nativeSourceCompanionSha256': proof['nativeSourceCompanionSha256'],
            'recoveryMethod': 'Unchanged runtime and notices from verified persistent-key donor APK'}))
        return len(manifest['files'])


def compare_output(output, assembly, donor, module=None, fault=None, world=None):
    with zipfile.ZipFile(output) as final, zipfile.ZipFile(assembly) as base, zipfile.ZipFile(donor) as old:
        inventory(final)
        expected = {n for n in base.namelist() if not retained(n) and not signature(n)} | {n for n in old.namelist() if retained(n)}
        for name, file in ((LEGACY, module), (FAULT, fault), (WORLD, world)):
            if file is not None:
                expected.add(name)
                require(final.read(name) == file.read_bytes(), 'Final separate native module changed')
        require(set(final.namelist()) == expected, 'Final APK inventory differs')
        for source, predicate in ((base, lambda n: not retained(n) and not signature(n)), (old, retained)):
            for name in source.namelist():
                if predicate(name):
                    require(final.read(name) == source.read(name), 'Final APK member changed')


def verify_signature(apksigner, apk):
    output = subprocess.check_output([str(apksigner), 'verify', '--verbose', '--print-certs', str(apk)], text=True)
    require('Verified using v2 scheme (APK Signature Scheme v2): true' in output, 'APK v2 signature missing')
    require(re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-f0-9]{64})', output) == [CERTIFICATE],
            'Persistent signing certificate differs')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('assembly', 'donor', 'donor-evidence', 'signing-capsule', 'build-tools', 'output', 'evidence'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--assembly-sha256', required=True)
    parser.add_argument('--assembly-commit', required=True)
    parser.add_argument('--ci-run-id', type=int, required=True)
    parser.add_argument('--version-code', type=int, required=True)
    parser.add_argument('--version-name', required=True)
    parser.add_argument('--legacy-provider', type=Path, required=True)
    parser.add_argument('--fault-witness', type=Path, required=True)
    parser.add_argument('--world-session', type=Path, required=True)
    parser.add_argument('--world-source-companion', type=Path, required=True)
    args = parser.parse_args()
    proof = json.loads(args.donor_evidence.read_text())
    require(not args.output.exists() and not args.evidence.exists(), 'Do not overwrite a delivered artifact')
    require(file_sha(args.donor) == proof['apk_sha256'], 'Donor APK identity differs')
    require(file_sha(args.assembly) == args.assembly_sha256, 'Qualified assembly identity differs')
    require(re.fullmatch(r'[a-f0-9]{40}', args.assembly_commit), 'Invalid assembly commit')
    require(args.version_code > proof['appVersionCode'], 'Private updates must increase version code')
    require(proof['signerCertificateSha256'] == CERTIFICATE, 'Donor certificate evidence differs')
    subprocess.run(['git', 'diff', '--exit-code', args.assembly_commit, '--', 'android', 'native', 'manifests'], cwd=ROOT, check=True)
    check_helper_sources(ROOT, proof)
    from create_world_session_source import verify_companion
    companion_sha = verify_companion(args.world_source_companion, ROOT)
    apksigner = args.build_tools.resolve() / 'apksigner'
    zipalign = args.build_tools.resolve() / 'zipalign'
    verify_signature(apksigner, args.donor.resolve())
    badging = subprocess.check_output([str(args.build_tools.resolve() / 'aapt2'), 'dump', 'badging', str(args.assembly.resolve())], text=True)
    require(f"package: name='com.russianranger.fta' versionCode='{args.version_code}' versionName='{args.version_name}'" in badging,
            'Assembly application/version identity differs')
    with tempfile.TemporaryDirectory(prefix='fta-private-update-') as temporary:
        work = Path(temporary); stage = work / 'provider'
        members = overlay(args.assembly, args.donor, work / 'unsigned.apk', stage, ROOT, proof, args.legacy_provider, args.fault_witness, args.world_session)
        subprocess.run([str(zipalign), '-P', '16', '-f', '4', str(work / 'unsigned.apk'), str(work / 'aligned.apk')], check=True)
        with zipfile.ZipFile(args.signing_capsule) as capsule:
            require(capsule.getinfo('signing.json').file_size <= 4096, 'Oversized signing capsule metadata')
            credentials = json.loads(capsule.read('signing.json'))
            key = credentials['keyFile']
            require(key == PurePosixPath(key).name and key not in ('', '.', '..'), 'Unsafe capsule key name')
            require(capsule.getinfo(key).file_size <= 65536, 'Oversized signing key')
            with (work / 'signing.p12').open('xb') as target:
                os.chmod(target.name, 0o600); target.write(capsule.read(key))
        environment = dict(os.environ, FTA_SIGNING_STORE_PASSWORD=credentials['storePassword'], FTA_SIGNING_KEY_PASSWORD=credentials['keyPassword'])
        subprocess.run([str(apksigner), 'sign', '--ks', str(work / 'signing.p12'), '--ks-key-alias', credentials['keyAlias'],
            '--ks-pass', 'env:FTA_SIGNING_STORE_PASSWORD', '--key-pass', 'env:FTA_SIGNING_KEY_PASSWORD',
            '--v1-signing-enabled', 'false', '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'false',
            '--v4-signing-enabled', 'false', '--out', str(work / 'signed.apk'), str(work / 'aligned.apk')], env=environment, check=True)
        verify_signature(apksigner, work / 'signed.apk')
        subprocess.run([str(zipalign), '-c', '-P', '16', '4', str(work / 'signed.apk')], check=True)
        compare_output(work / 'signed.apk', args.assembly, args.donor, args.legacy_provider, args.fault_witness, args.world_session)
        result = verify(work / 'signed.apk', stage)
        added = sum(name not in proof['nativePayloadSha256'] for name in (LEGACY, FAULT, WORLD))
        require(result['provider_and_notice_files_verified'] == proof['provider_and_notice_files_verified'] + added, 'Provider/notice count differs')
        for key in ('nativePayloadSha256', 'processSupervisorSourceSha256', 'cacheHelperSourceSha256',
                    'nativeSourceCompanionSha256', 'runtimeManifestSha256', 'gameDataPolicySha256', 'compiledUpdaterLockSha256'):
            result[key] = proof[key]
        result.update(appVersionCode=args.version_code, appVersionName=args.version_name,
            apkBytes=(work / 'signed.apk').stat().st_size, assemblyCommit=args.assembly_commit,
            assemblyCiRunId=args.ci_run_id, originalAssembledApkSha256=args.assembly_sha256,
            donorApkSha256=proof['apk_sha256'], donorVersionCode=proof['appVersionCode'],
            signerCertificateSha256=CERTIFICATE, signatureVerification='passed', apkSignatureScheme='v2',
            zipAlignment='passed', zipAlignmentPageSizeBytes=16384, allNativePayloadsByteIdenticalToDonor=not bool(added),
            allDonorNativePayloadsByteIdentical=True,
            packagedNativePayloadCount=len(proof['nativePayloadSha256']) + added, privateRuntimeManifestEntriesVerified=members,
            allNonPrivateAssemblyEntriesPreserved=True, nativeRecompiledForDelivery=False,
            finalPackaging='Qualified CI assembly with unchanged donor inputs and separately pinned native modules, aligned and persistently signed',
            physicalVersionExecuted=False, nativeDatabaseFallbackValidated=False, worldReady=False, gameLoginQualified=False)
        result['nativePayloadSha256'] = dict(proof['nativePayloadSha256'], **{LEGACY: file_sha(args.legacy_provider), FAULT: file_sha(args.fault_witness), WORLD: file_sha(args.world_session)})
        result['opensslLegacyProvider'] = verify_legacy(args.legacy_provider)
        result['worldFaultWitness'] = verify_fault(args.fault_witness)
        result['nativeRecompiledForDelivery'] = bool(added)
        result['nativeWorldRecompiledForDelivery'] = True
        result['worldSessionExecutable'] = verify_world_module(args.world_session, json.loads((ROOT / 'manifests/world-session.json').read_text()))
        result['baselineSourceCompanionSha256'] = proof['nativeSourceCompanionSha256']
        result['nativeSourceCompanionSha256'] = companion_sha
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open('xb') as out, (work / 'signed.apk').open('rb') as source:
            while data := source.read(1024 * 1024):
                out.write(data)
        args.evidence.write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps({k: result[k] for k in ('apk_sha256', 'apkBytes', 'appVersionCode', 'provider_and_notice_files_verified')}))


if __name__ == '__main__':
    main()
