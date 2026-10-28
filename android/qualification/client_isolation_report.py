#!/usr/bin/env python3
"""Assess the disposable Android storage fixture without qualifying imported files or Wine."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys

from client_report import InvalidEvidence, digest, integer, read_json, require

PAYLOADS = {'libproot_ro.so', 'libfta_storage_probe.so'}
REJECTIONS = {'missing', 'noncanonical', 'symlink', 'hardlink'}
FLAGS = ('storageIsolationValidated', 'protectedTreeUnchanged', 'ownedHandlesReaped')


def assess(report, apk):
    require(isinstance(report, dict) and isinstance(apk, dict), 'invalid-document')
    require(report.get('packageName') == 'com.russianranger.fta', 'package-mismatch')
    require(digest(apk.get('apk_sha256')) and report.get('apkSha256') == apk['apk_sha256'], 'apk-identity-mismatch')
    require(integer(apk.get('appVersionCode'), 1) and type(report.get('appVersionCode')) is int and
            report['appVersionCode'] == apk['appVersionCode'] and
            isinstance(apk.get('appVersionName'), str) and re.fullmatch(r'[A-Za-z0-9._+-]{1,100}', apk['appVersionName']) and
            report.get('appVersionName') == apk['appVersionName'], 'app-version-mismatch')
    manifest = apk.get('clientIsolationManifest')
    require(isinstance(manifest, dict) and integer(manifest.get('formatVersion'), 1, 1), 'invalid-isolation-manifest')
    names = manifest.get('checks')
    require(isinstance(names, list) and len(names) == 50 and
            all(isinstance(n, str) and re.fullmatch(r'[a-z-]{1,40}', n) for n in names) and
            len(set(names)) == len(names), 'invalid-check-contract')
    pins = manifest.get('payloadSha256')
    require(isinstance(pins, dict) and set(pins) == PAYLOADS and all(digest(v) for v in pins.values()) and
            digest(manifest.get('loaderSha256')), 'invalid-payload-contract')
    pins = dict(pins, **{'libproot-loader.so': manifest['loaderSha256']})
    inventory = apk.get('nativePayloadSha256')
    require(isinstance(inventory, dict) and all(inventory.get('lib/arm64-v8a/' + n) == h for n, h in pins.items()),
            'packaged-payload-mismatch')
    receipt = report.get('clientStorageIsolation')
    require(isinstance(receipt, dict) and receipt.get('available') is True, 'receipt-unavailable')
    require(receipt.get('state') == 'complete' and 'reason' not in receipt, 'operation-incomplete')
    require(receipt.get('workerObservation') == 'inactive' and receipt.get('interrupted') is False, 'worker-not-finished')
    require(all(receipt.get(k) is True for k in FLAGS), 'isolation-evidence-incomplete')
    require(all(receipt.get(k) is False for k in ('importedClientAccessed', 'wineExecuted', 'originalClientExecuted')),
            'unsupported-client-claim')
    require(isinstance(receipt.get('attemptId'), str) and re.fullmatch(
            r'[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}', receipt['attemptId']), 'invalid-attempt')
    checks = receipt.get('checks')
    require(isinstance(checks, dict) and set(checks) == set(names) and
            all(integer(checks[n], 0, 0) for n in names), 'checks-incomplete')
    refused = receipt.get('rootRejections')
    require(isinstance(refused, dict) and set(refused) == REJECTIONS and
            all(refused[n] is True for n in REJECTIONS), 'root-refusals-incomplete')
    require(receipt.get('payloadSha256') == pins, 'payload-identity-mismatch')
    require(integer(receipt.get('fixtureExit'), 0, 0) and integer(receipt.get('fixtureBytes'), 65545, 65545) and
            integer(receipt.get('fixtureChecksPassed'), 50, 50), 'fixture-counts-mismatch')
    require(integer(receipt.get('startedAtEpochMs'), 1) and
            integer(receipt.get('updatedAtEpochMs'), receipt['startedAtEpochMs']) and
            integer(report.get('createdAtEpochMs'), receipt['updatedAtEpochMs']) and
            integer(receipt.get('elapsedMs'), 1, 120000), 'invalid-timing')
    history = receipt.get('exitHistoryAvailable') is True
    exits = receipt.get('recentExits', [])
    require(isinstance(exits, list) and len(exits) <= 3 and (not exits or history), 'invalid-exit-history')
    for entry in exits:
        require(isinstance(entry, dict) and type(entry.get('attemptMatched')) is bool and
                integer(entry.get('timestamp'), 1) and integer(entry.get('reason')) and
                integer(entry.get('status')), 'invalid-exit-history')
        require(entry['attemptMatched'] is False and
                not receipt['startedAtEpochMs'] <= entry['timestamp'] <= receipt['updatedAtEpochMs'],
                'contradictory-attempt-exit')
    platform = report.get('platform')
    require(isinstance(platform, dict) and integer(platform.get('sdkInt'), 26, 100) and
            isinstance(platform.get('supportedAbis'), list) and 'arm64-v8a' in platform['supportedAbis'], 'invalid-platform')
    return dict(formatVersion=1, scope='disposable-client-storage-assessment',
                appVersionCode=report['appVersionCode'], appVersionName=report['appVersionName'],
                apkSha256=report['apkSha256'], attemptId=receipt['attemptId'],
                startedAtEpochMs=receipt['startedAtEpochMs'], updatedAtEpochMs=receipt['updatedAtEpochMs'],
                elapsedMs=receipt['elapsedMs'], fixtureBytes=65545, fixtureChecksPassed=50, fixtureExit=0,
                checks={n: 0 for n in names}, rootRejections={n: True for n in sorted(REJECTIONS)}, payloadSha256=pins,
                androidStorageFixtureQualified=True, protectedFixtureTreeUnchanged=True, ownedHandlesReaped=True,
                workerObservation='inactive', noAttemptMatchedExitObserved=history, unmatchedHistoricalExitCount=len(exits),
                androidSdkInt=platform['sdkInt'], reportedThor=platform.get('manufacturer') == 'AYN' and platform.get('model') == 'AYN Thor',
                importedClientAccessed=False, importedTreeProtectionQualified=False, guardedWineQualified=False,
                originalClientExecuted=False, warcraftXLCompatible=False, gameLoginQualified=False,
                hostileCodeConfinementQualified=False, reportAuthenticityVerified=False, deviceFilesIndependentlyRead=False,
                nextAction='guarded-wine-file-access-and-bounded-working-view')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('report', 'apk-evidence', 'output'): parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    try:
        report, raw = read_json(args.report); apk, _ = read_json(args.apk_evidence)
        result = assess(report, apk)
        result.update(reportSha256=hashlib.sha256(raw).hexdigest(), reportBytes=len(raw))
        with args.output.open('x') as output: json.dump(result, output, indent=2); output.write('\n')
        print(json.dumps({k: result[k] for k in ('androidStorageFixtureQualified', 'fixtureChecksPassed', 'elapsedMs', 'nextAction')}))
        return 0
    except InvalidEvidence as error:
        print('Storage evidence rejected: ' + str(error), file=sys.stderr)
    except (OSError, ValueError, TypeError, RecursionError):
        print('Storage evidence rejected; inspect the private input against the documented contract.', file=sys.stderr)
    return 1


if __name__ == '__main__': sys.exit(main())
