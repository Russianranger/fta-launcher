#!/usr/bin/env python3
"""Assess owner-supplied client receipts against a measured private APK identity.

This checks reported evidence, not the device files or report authenticity. It
never infers Wine/WarcraftXL/gameplay or cache eviction from a copy receipt.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys


class InvalidEvidence(ValueError):
    pass


def require(condition, reason):
    if not condition:
        raise InvalidEvidence(reason)


def integer(value, minimum=0, maximum=2**63 - 1):
    return type(value) is int and minimum <= value <= maximum


def digest(value):
    return isinstance(value, str) and re.fullmatch(r'[a-f0-9]{64}', value) is not None


def assess(report, apk, prior_import=None):
    require(isinstance(report, dict) and isinstance(apk, dict), 'invalid-document')
    require(digest(apk.get('apk_sha256')) and report.get('apkSha256') == apk['apk_sha256'], 'apk-identity-mismatch')
    require(integer(apk.get('appVersionCode'), 1, 2_100_000_000) and type(report.get('appVersionCode')) is int and
            report['appVersionCode'] == apk['appVersionCode'] and
            isinstance(apk.get('appVersionName'), str) and re.fullmatch(r'[A-Za-z0-9._+-]{1,100}', apk['appVersionName']) and
            report.get('appVersionName') == apk['appVersionName'],
            'app-version-mismatch')
    require(report.get('packageName') == 'com.russianranger.fta', 'package-mismatch')
    receipt = report.get('clientImport')
    require(isinstance(receipt, dict) and receipt.get('available') is True, 'client-receipt-unavailable')
    require(receipt.get('state') == 'complete' and receipt.get('operation') in ('import', 'verify'), 'client-operation-incomplete')
    require(isinstance(receipt.get('attemptId'), str) and re.fullmatch(
        r'[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}', receipt['attemptId']), 'invalid-attempt')
    require(receipt.get('sourceWritePerformed') is False and receipt.get('clientImported') is True and
            receipt.get('copyReadbackValidated') is True, 'copy-evidence-inconsistent')
    verify = receipt['operation'] == 'verify'
    require(receipt.get('retainedRehashValidated') is verify, 'retained-rehash-evidence-inconsistent')
    require(receipt.get('warcraftXLCompatible') is False and receipt.get('gameLoginQualified') is False,
            'unsupported-client-runtime-claim')
    require(receipt.get('interrupted') is False and receipt.get('workerObservation') == 'inactive', 'worker-not-finished')
    require('reason' not in receipt, 'completed-receipt-has-failure')
    require(digest(receipt.get('catalogSha256')), 'invalid-catalog')
    require(integer(receipt.get('files'), 1, 100000) and integer(receipt.get('totalFiles'), 1, 100000) and
            receipt['files'] == receipt['totalFiles'] and integer(receipt.get('bytes'), 1, 1024**4) and
            integer(receipt.get('totalBytes'), 1, 1024**4) and receipt['bytes'] == receipt['totalBytes'], 'incomplete-counts')
    require(integer(receipt.get('resumedBytes'), 0, receipt['bytes']), 'invalid-resumed-count')
    require(not verify or receipt['resumedBytes'] == 0, 'verify-has-resumed-copy-bytes')
    require(integer(receipt.get('startedAtEpochMs'), 1) and
            integer(receipt.get('updatedAtEpochMs'), receipt['startedAtEpochMs']) and
            integer(report.get('createdAtEpochMs'), receipt['updatedAtEpochMs']) and
            integer(receipt.get('elapsedMs'), 1), 'invalid-timing')

    # Optional OS history never promotes or erases file-integrity evidence. An exact
    # contradictory attempt match prevents qualification; an older kill does not.
    exits = receipt.get('recentClientExits', [])
    require(isinstance(exits, list) and len(exits) <= 3, 'invalid-exit-history')
    for entry in exits:
        require(isinstance(entry, dict) and type(entry.get('attemptMatched')) is bool and
                integer(entry.get('timestampEpochMs'), 1) and integer(entry.get('reasonCode')),
                'invalid-exit-history')
        require(entry['attemptMatched'] is False, 'contradictory-attempt-exit')
    exit_available = receipt.get('exitHistorySupported') is True and receipt.get('exitHistoryAvailable') is True
    require(not exits or exit_available, 'invalid-exit-history')

    inspection = report.get('clientInspection')
    require(isinstance(inspection, dict) and inspection.get('state') == 'complete' and
            integer(inspection.get('files'), 1) and integer(inspection.get('knownBytes'), 1) and
            inspection['files'] == receipt['files'] and inspection['knownBytes'] == receipt['bytes'] and
            type(inspection.get('unknownSizes')) is int and inspection['unknownSizes'] == 0,
            'inspection-totals-mismatch')

    prior_matched = False
    if prior_import is not None:
        require(isinstance(prior_import, dict) and type(prior_import.get('formatVersion')) is int and
                prior_import['formatVersion'] == 1 and
                prior_import.get('scope') == 'client-receipt-assessment' and
                prior_import.get('operation') == 'import' and prior_import.get('importQualified') is True and
                prior_import.get('retainedRehashQualified') is False and digest(prior_import.get('catalogSha256')) and
                integer(prior_import.get('files'), 1, 100000) and integer(prior_import.get('bytes'), 1, 1024**4),
                'invalid-import-baseline')
        require(verify, 'baseline-requires-verification')
        require(prior_import.get('catalogSha256') == receipt['catalogSha256'] and
                prior_import.get('files') == receipt['files'] and prior_import.get('bytes') == receipt['bytes'],
                'import-baseline-mismatch')
        require(integer(prior_import.get('updatedAtEpochMs'), 1) and
                prior_import['updatedAtEpochMs'] <= receipt['startedAtEpochMs'] and
                prior_import.get('attemptId') != receipt['attemptId'], 'verification-not-after-import')
        prior_matched = True

    result = dict(formatVersion=1, scope='client-receipt-assessment',
                  appVersionCode=report['appVersionCode'], appVersionName=report['appVersionName'],
                  apkSha256=report['apkSha256'], operation=receipt['operation'], attemptId=receipt['attemptId'],
                  startedAtEpochMs=receipt['startedAtEpochMs'], updatedAtEpochMs=receipt['updatedAtEpochMs'],
                  elapsedMs=receipt['elapsedMs'], files=receipt['files'], bytes=receipt['bytes'],
                  resumedBytes=receipt['resumedBytes'], catalogSha256=receipt['catalogSha256'],
                  importQualified=not verify, retainedRehashQualified=verify,
                  matchesPriorImport=prior_matched, clientIntegrityGateQualified=verify and prior_matched,
                  noAttemptMatchedExitObserved=exit_available, unmatchedHistoricalExitCount=len(exits),
                  warcraftXLCompatible=False, gameLoginQualified=False, actualCacheEvictionMeasured=False,
                  pressureBackoffQualified=False,
                  reportAuthenticityVerified=False, deviceFilesIndependentlyRead=False,
                  nextAction='runtime-preparation' if verify and prior_matched else
                  'match-import-baseline' if verify else 'verify-imported-client')
    # Fixed, typed observations only: no source URI/path, configuration, exception
    # message, arbitrary keys or raw receipt objects can pass through this output.
    for key in ('peakRssKiB', 'heapUsedBytes', 'heapMaxBytes', 'availableMemoryBytes', 'totalMemoryBytes',
                'lowMemoryThresholdBytes', 'ioBytes', 'ioLimitBytesPerSecond', 'ioWindowBytes',
                'throttledMs', 'pressureWaitMs', 'pressurePauses', 'sourceCacheRequests',
                'sourceCacheAccepted', 'destinationCacheRequests', 'destinationCacheAccepted',
                'psiSomeMilliPercent', 'psiFullMilliPercent'):
        if key in receipt:
            require(integer(receipt[key]), 'invalid-observation')
            result[key] = receipt[key]
    for key in ('memoryObservationAvailable', 'psiObservationAvailable', 'systemLowMemory', 'activityBound'):
        if key in receipt:
            require(type(receipt[key]) is bool, 'invalid-observation')
            result[key] = receipt[key]
    # No claim that pause/recovery was tested merely because the workload survived.
    return result


def read_json(path):
    with path.open('rb') as stream:
        data = stream.read(2 * 1024 * 1024 + 1)
    require(len(data) <= 2 * 1024 * 1024, 'document-too-large')
    def unique(pairs):
        values = {}
        for key, value in pairs:
            require(key not in values, 'duplicate-json-key')
            values[key] = value
        return values
    return json.loads(data, object_pairs_hook=unique), data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--apk-evidence', type=Path, required=True)
    parser.add_argument('--prior-import', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        report, data = read_json(args.report)
        apk, _ = read_json(args.apk_evidence)
        baseline = read_json(args.prior_import)[0] if args.prior_import else None
        result = assess(report, apk, baseline)
        result.update(reportSha256=hashlib.sha256(data).hexdigest(), reportBytes=len(data))
        # New evidence only; never overwrite a previous baseline or owner report.
        with args.output.open('x') as output:
            json.dump(result, output, indent=2); output.write('\n')
        print(json.dumps({key: result[key] for key in ('operation', 'files', 'bytes', 'nextAction')}))
        return 0
    except InvalidEvidence as error:
        print('Client evidence rejected: ' + str(error), file=sys.stderr)
        return 1
    except (OSError, ValueError, TypeError, RecursionError):
        # Do not echo paths, JSON fragments or raw exception messages to CI/export.
        print('Client evidence rejected; inspect the private input against the documented contract.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
