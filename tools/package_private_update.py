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

ROOT = Path(__file__).resolve().parents[1]
CERTIFICATE = '3e6705b6dc4a6bf3e7109f5322ec51d5ff094a4d912560cd9c1bb826f88754a6'
HELPERS = {'lib/arm64-v8a/libfta_process.so', 'lib/arm64-v8a/libfta_game_io.so'}
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


def overlay(assembly, donor, output, stage, root, proof):
    with zipfile.ZipFile(assembly) as base, zipfile.ZipFile(donor) as old:
        inventory(base); old_names = inventory(old)
        require({n: sha(old.read(n)) for n in old_names if n.startswith('lib/')} == proof['nativePayloadSha256'],
                'Donor native payload inventory differs')
        for member, source in METADATA.items():
            require(base.read(member) == old.read(member) == (root / source).read_bytes(), 'Canonical metadata differs')
        manifest_bytes = old.read('assets/fta-runtime-manifest.json')
        require(sha(manifest_bytes) == proof['runtimeManifestSha256'], 'Runtime manifest identity differs')
        manifest = json.loads(manifest_bytes)
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
                if retained(name):
                    data = old.read(entry); final.writestr(entry, data)
                    if name not in HELPERS:
                        relative = name.replace('lib/arm64-v8a/', 'jniLibs/arm64-v8a/', 1) if name.startswith('lib/') else name
                        target = stage / relative
                        target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(data)
        # This is a recovery attestation, not a newly compiled provider provenance.
        (stage / 'BUILD_PROVENANCE.json').write_text(json.dumps({
            'noticeProvided': True, 'redistributionNoticePending': False,
            'recoverySourceApkSha256': proof['apk_sha256'],
            'nativeSourceCompanionSha256': proof['nativeSourceCompanionSha256'],
            'recoveryMethod': 'Unchanged runtime and notices from verified persistent-key donor APK'}))
        return len(manifest['files'])


def compare_output(output, assembly, donor):
    with zipfile.ZipFile(output) as final, zipfile.ZipFile(assembly) as base, zipfile.ZipFile(donor) as old:
        inventory(final)
        expected = {n for n in base.namelist() if not retained(n) and not signature(n)} | {n for n in old.namelist() if retained(n)}
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
    apksigner = args.build_tools.resolve() / 'apksigner'
    zipalign = args.build_tools.resolve() / 'zipalign'
    verify_signature(apksigner, args.donor.resolve())
    badging = subprocess.check_output([str(args.build_tools.resolve() / 'aapt2'), 'dump', 'badging', str(args.assembly.resolve())], text=True)
    require(f"package: name='com.russianranger.fta' versionCode='{args.version_code}' versionName='{args.version_name}'" in badging,
            'Assembly application/version identity differs')
    with tempfile.TemporaryDirectory(prefix='fta-private-update-') as temporary:
        work = Path(temporary); stage = work / 'provider'
        members = overlay(args.assembly, args.donor, work / 'unsigned.apk', stage, ROOT, proof)
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
        compare_output(work / 'signed.apk', args.assembly, args.donor)
        result = verify(work / 'signed.apk', stage)
        require(result['provider_and_notice_files_verified'] == proof['provider_and_notice_files_verified'], 'Provider/notice count differs')
        for key in ('nativePayloadSha256', 'processSupervisorSourceSha256', 'cacheHelperSourceSha256',
                    'nativeSourceCompanionSha256', 'runtimeManifestSha256', 'gameDataPolicySha256', 'compiledUpdaterLockSha256'):
            result[key] = proof[key]
        result.update(appVersionCode=args.version_code, appVersionName=args.version_name,
            apkBytes=(work / 'signed.apk').stat().st_size, assemblyCommit=args.assembly_commit,
            assemblyCiRunId=args.ci_run_id, originalAssembledApkSha256=args.assembly_sha256,
            donorApkSha256=proof['apk_sha256'], donorVersionCode=proof['appVersionCode'],
            signerCertificateSha256=CERTIFICATE, signatureVerification='passed', apkSignatureScheme='v2',
            zipAlignment='passed', zipAlignmentPageSizeBytes=16384, allNativePayloadsByteIdenticalToDonor=True,
            packagedNativePayloadCount=len(proof['nativePayloadSha256']), privateRuntimeManifestEntriesVerified=members,
            allNonPrivateAssemblyEntriesPreserved=True, nativeRecompiledForDelivery=False,
            finalPackaging='Qualified CI assembly with verified unchanged private native/runtime/notice inputs, aligned and persistently signed',
            physicalVersionExecuted=False, nativeDatabaseFallbackValidated=False, worldReady=False, gameLoginQualified=False)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open('xb') as out, (work / 'signed.apk').open('rb') as source:
            while data := source.read(1024 * 1024):
                out.write(data)
        args.evidence.write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps({k: result[k] for k in ('apk_sha256', 'apkBytes', 'appVersionCode', 'provider_and_notice_files_verified')}))


if __name__ == '__main__':
    main()
