#!/usr/bin/env python3
"""Reproduce the physical stderr hash using unchanged original host crypto code.

Private source and the measured ARM64 executable supply provenance only. No
Android code, realm, SQL or game data is executed by this isolated host fixture.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import tempfile
import zipfile

from build_native import check_jobs
from fta_source import SOURCE_ROOT, SOURCE_SHA256, sha256_file

ROOT = Path(__file__).resolve().parents[1]
WORLD_SHA = '97e578f7e3013715c2d9254dc29dd87d4a33598583093110eaad24157ee67766'
STDERR_SHA = 'c874def29443f077496d51e01c69be82a8a677f7fa7528880dad71ee4ed8f997'
LEAF = 'src/common/Cryptography/ARC4.cpp'
FILES = (LEAF, 'src/common/Cryptography/ARC4.h', 'src/common/Cryptography/OpenSSLCrypto.cpp',
         'src/common/Cryptography/OpenSSLCrypto.h', 'src/common/Debugging/Errors.cpp',
         'src/common/Debugging/Errors.h', 'src/common/Utilities/StringFormat.h',
         'src/common/Utilities/Duration.h', 'src/common/Define.h', 'src/common/CompilerDefs.h')
CALL_CHAIN = ('src/common/Cryptography/Authentication/AuthCrypt.h',
              'src/server/game/Server/WorldSocket.h', 'src/server/apps/worldserver/Main.cpp')


def embedded_source_root(binary):
    paths = set(re.findall(rb'/[^\x00\r\n ]*/src/common/Cryptography/ARC4\.cpp\x00', binary))
    if len(paths) != 1:
        raise ValueError('Original executable source path is ambiguous or unavailable')
    return paths.pop()[:-1].decode('ascii')[:-len(LEAF)].rstrip('/')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-zip', type=Path, required=True)
    parser.add_argument('--world-elf', type=Path, required=True)
    parser.add_argument('--host-provider', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--build-log', type=Path, required=True)
    args = parser.parse_args()
    if sha256_file(args.source_zip) != SOURCE_SHA256 or sha256_file(args.world_elf) != WORLD_SHA:
        raise ValueError('Original source or executable identity differs')
    check_jobs(1)
    embedded = embedded_source_root(args.world_elf.read_bytes())
    with tempfile.TemporaryDirectory(prefix='fta-world-crypto-') as temporary:
        work = Path(temporary); source = work / 'source'
        identities = {}
        with zipfile.ZipFile(args.source_zip) as archive:
            names = list(FILES + CALL_CHAIN) + [n.removeprefix(SOURCE_ROOT + '/') for n in archive.namelist()
                                  if n.startswith(SOURCE_ROOT + '/deps/fmt/include/') and not n.endswith('/')]
            for name in names:
                data = archive.read(SOURCE_ROOT + '/' + name)
                destination = source / name; destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(data); identities[name] = hashlib.sha256(data).hexdigest()
        auth = (source / CALL_CHAIN[0]).read_text()
        world = (source / CALL_CHAIN[1]).read_text()
        main_source = (source / CALL_CHAIN[2]).read_text()
        if not ('AuthCrypt() = default;' in auth and 'Acore::Crypto::ARC4 _clientDecrypt;' in auth and
                'Acore::Crypto::ARC4 _serverEncrypt;' in auth and 'AuthCrypt _authCrypt;' in world and
                main_source.index('OpenSSLCrypto::threadsSetup();') < main_source.index('StartWorldNetwork(')):
            raise ValueError('Original socket/provider construction chain differs')
        includes = ['src/common', 'src/common/Cryptography', 'src/common/Debugging',
                    'src/common/Utilities', 'deps/fmt/include']
        command = ['g++', '-std=gnu++20', '-DFMT_HEADER_ONLY', '-O0', '-pthread',
                   f'-ffile-prefix-map={source}={embedded}']
        command += [f'-I{source / name}' for name in includes]
        command += [str(source / name) for name in FILES if name.endswith('.cpp')]
        command += [str(ROOT / 'tests/fixtures/world_crypto_host.cpp'), '-lcrypto', '-o', str(work / 'crypto')]
        with args.build_log.open('w') as log:
            subprocess.run(command, check=True, stdout=log, stderr=log)
        missing = work / 'missing'; missing.mkdir()
        present = work / 'present'; present.mkdir()
        (present / 'legacy.so').symlink_to(args.host_provider.resolve(strict=True))
        def run(directory):
            environment = dict(os.environ, OPENSSL_MODULES=str(directory), OPENSSL_CONF='/dev/null')
            return subprocess.run([str(work / 'crypto')], env=environment, capture_output=True, timeout=10)
        negative = run(missing); positive = run(present)
        digest = hashlib.sha256(negative.stderr).hexdigest()
        if (negative.returncode != -signal.SIGSEGV or negative.stdout or len(negative.stderr) != 326 or
                negative.stderr.count(b'\n') != 9 or digest != STDERR_SHA):
            raise ValueError('Missing provider did not reproduce the measured physical assertion')
        if positive.returncode != 0 or positive.stderr or positive.stdout != b'bbf316e8d940af0ad3\n':
            raise ValueError('Original ARC4 did not pass the known plaintext/key vector with the provider')
        result = dict(status='host_original_crypto_passed', sourceArchiveSha256=SOURCE_SHA256,
            originalWorldElfSha256=WORLD_SHA, sourceFilesSha256=identities,
            sourcePathSha256=hashlib.sha256((embedded + '/' + LEAF).encode()).hexdigest(),
            assertion=dict(file=LEAF, line=27, function='ARC4', condition='result == 1'),
            missingProvider=dict(signal='SIGSEGV', shellExitCode=139, stderrBytes=326, stderrLines=9,
                                 stderrSha256=digest, exactPhysicalStderrHashMatched=True),
            presentProvider=dict(exitCode=0, originalKnownVectorPassed=True,
                hostProviderSha256=sha256_file(args.host_provider)),
            hostOpenSslVersion=subprocess.check_output(['openssl', 'version'], text=True).strip(),
            compilerJobs=1, originalSourcesModified=False, androidExecutionQualified=False,
            originalSocketConstructsTwoArc4Contexts=True,
            nativeDatabaseFallbackValidated=False, worldReady=False, gameLoginQualified=False)
        args.output.write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps({key: result[key] for key in ('status', 'missingProvider', 'presentProvider')}))


if __name__ == '__main__':
    main()
