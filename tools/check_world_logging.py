#!/usr/bin/env python3
"""Qualify runtime-only log filtering using unchanged original logger/appender code."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile

from build_native import check_jobs
from fta_source import SOURCE_ROOT, SOURCE_SHA256, sha256_file

ROOT = Path(__file__).resolve().parents[1]
FILES = ('src/common/Logging/Logger.cpp', 'src/common/Logging/Logger.h', 'src/common/Logging/Appender.cpp',
         'src/common/Logging/Appender.h', 'src/common/Logging/LogMessage.cpp', 'src/common/Logging/LogMessage.h',
         'src/common/Logging/LogCommon.h', 'src/common/Utilities/Timer.cpp', 'src/common/Utilities/Timer.h',
         'src/common/Utilities/Duration.h', 'src/common/Utilities/StringFormat.h', 'src/common/Common.h',
         'src/common/Define.h', 'src/common/CompilerDefs.h')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-zip', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--build-log', type=Path, required=True)
    args = parser.parse_args()
    if sha256_file(args.source_zip) != SOURCE_SHA256:
        raise ValueError('Original source identity differs')
    check_jobs(1)
    with tempfile.TemporaryDirectory(prefix='fta-world-logging-') as temporary:
        work = Path(temporary); identities = {}
        with zipfile.ZipFile(args.source_zip) as archive:
            names = list(FILES) + [n.removeprefix(SOURCE_ROOT + '/') for n in archive.namelist()
                                  if n.startswith(SOURCE_ROOT + '/deps/fmt/include/') and not n.endswith('/')]
            for name in names:
                data = archive.read(SOURCE_ROOT + '/' + name); file = work / name
                file.parent.mkdir(parents=True, exist_ok=True); file.write_bytes(data)
                identities[name] = hashlib.sha256(data).hexdigest()
            for file, phrase in [('src/server/game/DataStores/DBCStores.cpp', '>> Initialized {} Data Stores in {} ms'),
                                 ('src/server/apps/worldserver/Main.cpp', 'Halting process...')]:
                source = archive.read(SOURCE_ROOT + '/' + file).decode()
                category = 'server.loading' if 'DBCStores' in file else 'server.worldserver'
                if f'LOG_INFO("{category}", "{phrase}"' not in source:
                    raise ValueError('Required lifecycle log category differs')
                identities[file] = hashlib.sha256(source.encode()).hexdigest()
        command = ['g++', '-std=gnu++20', '-O1', '-DFMT_HEADER_ONLY', '-pthread',
                   '-ffunction-sections', '-fdata-sections', '-Wl,--gc-sections']
        command += ['-I' + str(work / n) for n in ('src/common', 'src/common/Logging', 'src/common/Utilities', 'deps/fmt/include')]
        command += [str(work / n) for n in FILES if n.endswith('.cpp')]
        command += [str(ROOT / 'tests/fixtures/world_logging_host.cpp'), '-o', str(work / 'logging')]
        with args.build_log.open('w') as log:
            subprocess.run(command, check=True, stdout=log, stderr=log)
            subprocess.run([str(work / 'logging')], check=True, stdout=log, stderr=log)
        proof = dict(status='original_host_logging_passed', sourceArchiveSha256=SOURCE_SHA256,
            sourceFilesSha256=identities, originalSourcesModified=False, rootWarningAndHigherRetained=True,
            lifecycleInfoRetained=True, consoleErrorAndFatalRetained=True, routineInfoPressureLines=50000,
            outputBytesBelow=1024, nativeLogLimitUnchangedBytes=8388608, compilerJobs=1,
            androidExecutionQualified=False, worldReady=False)
        args.output.write_text(json.dumps(proof, indent=2) + '\n'); print(proof['status'])


if __name__ == '__main__':
    main()
