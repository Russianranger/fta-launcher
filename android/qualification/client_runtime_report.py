#!/usr/bin/env python3
"""Assess a reported synthetic Android runtime pass, without promoting original gameplay."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys

from client_report import InvalidEvidence, digest, integer, read_json, require

STAGES = ('cpu-topology', 'display', 'prefix', 'cpu-registry', 'x86-entry', 'x64-entry',
          'two-channels', 'sixteen-channels', 'owner-exit', 'wine-stop')
FLAGS = ('ownershipValidated', 'runtimePreRehashValidated', 'syntheticMixedRuntimeValidated',
         'runtimePostRehashValidated', 'runtimeTreeVerified')
OBSERVATIONS = ('peakRssKiB', 'heapUsedBytes', 'heapMaxBytes', 'availableMemoryBytes', 'totalMemoryBytes',
                'ioBytes', 'throttledMs', 'pressureWaitMs', 'pressurePauses', 'ioLimitBytesPerSecond',
                'ioWindowBytes', 'cacheRequests', 'cacheAccepted', 'memoryThresholdBytes', 'memoryFloorBytes',
                'lastTrimLevel', 'lastTrimAgeMs', 'probeMemoryAdvisories', 'probeMemoryAdvisoryMs',
                'prefixElapsedMs', 'prefixSystem32Entries', 'prefixSyswow64Entries', 'prefixRegistryBytes')


def assess(report, apk, runtime):
    require(isinstance(report, dict) and isinstance(apk, dict) and isinstance(runtime, dict), 'invalid-document')
    require(digest(apk.get('apk_sha256')) and report.get('apkSha256') == apk['apk_sha256'], 'apk-identity-mismatch')
    require(integer(apk.get('appVersionCode'), 1) and type(report.get('appVersionCode')) is int and
            report['appVersionCode'] == apk['appVersionCode'] and
            isinstance(apk.get('appVersionName'), str) and re.fullmatch(r'[A-Za-z0-9._+-]{1,100}', apk['appVersionName']) and
            report.get('appVersionName') == apk['appVersionName'], 'app-version-mismatch')
    require(report.get('packageName') == 'com.russianranger.fta', 'package-mismatch')
    receipt = report.get('clientRuntime')
    require(isinstance(receipt, dict) and receipt.get('available') is True, 'runtime-receipt-unavailable')
    require(receipt.get('state') == 'complete' and receipt.get('operation') == 'probe' and
            'reason' not in receipt, 'runtime-operation-incomplete')
    require(receipt.get('workerObservation') == 'inactive' and receipt.get('interrupted') is False,
            'worker-not-finished')
    require(all(receipt.get(key) is True for key in FLAGS), 'runtime-gate-incomplete')
    require(receipt.get('retainedRehashValidated') is False, 'standalone-verification-claim')
    require(all(receipt.get(key) is False for key in ('originalClientExecuted', 'androidWineQualified',
            'warcraftXLCompatible', 'gameLoginQualified')), 'unsupported-original-runtime-claim')
    require(isinstance(receipt.get('attemptId'), str) and re.fullmatch(
            r'[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}', receipt['attemptId']), 'invalid-attempt')
    for key in ('archiveSha256', 'catalogSha256'):
        require(digest(runtime.get(key)) and receipt.get(key) == runtime[key], 'runtime-identity-mismatch')
    require(integer(runtime.get('entries'), 1, 100000) and integer(runtime.get('bytes'), 1, 1024**4) and
            integer(receipt.get('entries'), 1) and integer(receipt.get('bytes'), 1) and
            integer(receipt.get('totalBytes'), 1) and receipt['entries'] == runtime['entries'] and
            receipt['bytes'] == receipt['totalBytes'] == runtime['bytes'], 'runtime-counts-mismatch')
    stages = receipt.get('probeStages')
    require(isinstance(stages, dict) and set(stages) == set(STAGES) and
            all(type(stages[key]) is int and stages[key] == 0 for key in STAGES), 'stage-evidence-incomplete')
    require(integer(receipt.get('ownershipNormalExit'), 0, 0) and
            integer(receipt.get('ownershipStopExit'), 0, 255) and
            integer(receipt.get('ownershipKilledExit'), 137, 137) and
            integer(receipt.get('stoppedTracees'), 3, 3) and integer(receipt.get('killedTracees'), 3, 3) and
            integer(receipt.get('probeCleanupComplete'), 1, 1), 'ownership-evidence-incomplete')
    require(receipt.get('displayEvent') == 'ready' and integer(receipt.get('socketProbeErrno'), 0, 0),
            'display-evidence-incomplete')
    require(integer(receipt.get('startedAtEpochMs'), 1) and
            integer(receipt.get('updatedAtEpochMs'), receipt['startedAtEpochMs']) and
            integer(report.get('createdAtEpochMs'), receipt['updatedAtEpochMs']) and
            integer(receipt.get('elapsedMs'), 1), 'invalid-timing')
    exits = receipt.get('recentClientRuntimeExits', [])
    require(isinstance(exits, list) and len(exits) <= 3, 'invalid-exit-history')
    for entry in exits:
        require(isinstance(entry, dict) and type(entry.get('attemptMatched')) is bool and
                integer(entry.get('timestampEpochMs'), 1) and integer(entry.get('reasonCode')), 'invalid-exit-history')
        require(entry['attemptMatched'] is False, 'contradictory-attempt-exit')
    history = receipt.get('exitHistorySupported') is True and receipt.get('exitHistoryAvailable') is True
    require(not exits or history, 'invalid-exit-history')
    result = dict(formatVersion=1, scope='synthetic-client-runtime-assessment',
                  appVersionCode=report['appVersionCode'], appVersionName=report['appVersionName'],
                  apkSha256=report['apkSha256'], attemptId=receipt['attemptId'],
                  startedAtEpochMs=receipt['startedAtEpochMs'], updatedAtEpochMs=receipt['updatedAtEpochMs'],
                  elapsedMs=receipt['elapsedMs'], entries=runtime['entries'], bytes=runtime['bytes'],
                  archiveSha256=runtime['archiveSha256'], catalogSha256=runtime['catalogSha256'],
                  probeStages={key: 0 for key in STAGES}, syntheticAndroidRuntimeQualified=True,
                  ownedCleanupQualified=True, fullPrePostRehashQualified=True,
                  noAttemptMatchedExitObserved=history, unmatchedHistoricalExitCount=len(exits),
                  originalClientExecuted=False, originalWineClientQualified=False,
                  warcraftXLCompatible=False, gameLoginQualified=False,
                  actualCacheEvictionMeasured=False, reportAuthenticityVerified=False,
                  deviceFilesIndependentlyRead=False, nextAction='isolated-client-working-view')
    for key in OBSERVATIONS:
        if key in receipt:
            require(integer(receipt[key]), 'invalid-observation')
            result[key] = receipt[key]
    # Survival/aggregate counters do not prove every pause was caused by trim, nor
    # that this run exercised the native advisory grace path or real cache eviction.
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('report', 'apk-evidence', 'runtime-lock', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    try:
        report, raw = read_json(args.report)
        apk, _ = read_json(args.apk_evidence)
        lock, _ = read_json(args.runtime_lock)
        require(isinstance(lock, dict) and isinstance(lock.get('archive'), dict) and
                isinstance(lock.get('catalog'), dict), 'invalid-runtime-lock')
        runtime = dict(archiveSha256=lock['archive'].get('sha256'), catalogSha256=lock['catalog'].get('sha256'),
                       entries=lock.get('archiveEntries'), bytes=lock.get('archiveFileBytes'))
        result = assess(report, apk, runtime)
        result.update(reportSha256=hashlib.sha256(raw).hexdigest(), reportBytes=len(raw))
        with args.output.open('x') as output:
            json.dump(result, output, indent=2); output.write('\n')
        print(json.dumps({key: result[key] for key in ('syntheticAndroidRuntimeQualified', 'elapsedMs', 'nextAction')}))
        return 0
    except InvalidEvidence as error:
        print('Runtime evidence rejected: ' + str(error), file=sys.stderr)
    except (OSError, ValueError, TypeError, RecursionError):
        print('Runtime evidence rejected; inspect the private input against the documented contract.', file=sys.stderr)
    return 1


if __name__ == '__main__':
    sys.exit(main())
