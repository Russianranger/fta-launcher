#!/usr/bin/env python3
"""Qualify retained guarded-Wine completion, preserving the original failed receipt.

Inputs are reported evidence, not independent access to device files. Output is
an allowlist and qualifies only the disposable fixture, never the original game.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys

from client_report import InvalidEvidence, digest, integer, read_json, require

STAGES = ('cpu-topology', 'display', 'prefix', 'x86-storage', 'x64-storage', 'wine-stop')
CHECKS = ('shared-read-open', 'read-content', 'seek', 'read-mapping', 'mapped-content',
          'private-mapping', 'private-view', 'direct-read', 'private-write-isolated',
          'directory-list', 'shared-write-denied', 'direct-write-denied', 'truncate-denied',
          'overwrite-denied', 'protected-create-denied', 'delete-preserved', 'rename-denied',
          'attributes-denied', 'hardlink-denied', 'mkdir-denied', 'config-open', 'config-write',
          'log-create', 'log-write', 'log-rename', 'log-delete', 'mixed-child-start',
          'mixed-child-read-protection')
KEYS = tuple(f'{arch}:{name}' for arch in ('x86', 'x64') for name in CHECKS)
FLAGS = ('runtimeBindPlaceholdersChecked', 'retainedProbeValidated', 'retainedFilesValidated',
         'retainedFilesUnchanged', 'completionOnly', 'runtimePostRehashCompleted',
         'runtimePostRehashValidated', 'guardedWineStorageValidated', 'runtimeTreeVerified')
UNQUALIFIED = ('originalClientExecuted', 'androidWineQualified', 'warcraftXLCompatible',
               'gameLoginQualified', 'originalImportAccessed', 'originalWorkingViewQualified',
               'syntheticMixedRuntimeValidated', 'retainedRehashValidated')
COUNTS = dict(ownershipNormalExit=0, ownershipKilledExit=137, stoppedTracees=3, killedTracees=3,
              probeCleanupComplete=1, socketProbeErrno=0, viewCopiedBytes=26,
              viewSharedBytes=573547, viewCopiedFiles=1, viewSharedFiles=3)
OBSERVATIONS = ('ioBytes', 'throttledMs', 'pressureWaitMs', 'pressurePauses', 'peakRssKiB',
                'availableMemoryBytes', 'cacheRequests', 'cacheAccepted')


def uuid(value):
    return isinstance(value, str) and re.fullmatch(r'[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}', value)


def zero_map(value, keys):
    return isinstance(value, dict) and set(value) == set(keys) and all(integer(n, 0, 0) for n in value.values())


def exit_history(receipt):
    history = receipt.get('exitHistorySupported') is True and receipt.get('exitHistoryAvailable') is True
    exits = receipt.get('recentClientRuntimeExits', [])
    require(isinstance(exits, list) and len(exits) <= 3 and (not exits or history), 'invalid-exit-history')
    for entry in exits:
        require(isinstance(entry, dict) and type(entry.get('attemptMatched')) is bool and
                integer(entry.get('timestampEpochMs'), 1) and integer(entry.get('reasonCode')) and
                integer(entry.get('status')), 'invalid-exit-history')
        require(entry['attemptMatched'] is False and not
                receipt['startedAtEpochMs'] <= entry['timestampEpochMs'] <= receipt['updatedAtEpochMs'],
                'contradictory-attempt-exit')
    return history, len(exits)


def assess(report, apk, runtime, source_baseline, prior_finish):
    require(all(isinstance(d, dict) for d in (report, apk, runtime, source_baseline, prior_finish)), 'invalid-document')
    require(report.get('packageName') == 'com.russianranger.fta', 'package-mismatch')
    require(digest(apk.get('apk_sha256')) and report.get('apkSha256') == apk['apk_sha256'], 'apk-identity-mismatch')
    require(integer(apk.get('appVersionCode'), 45) and type(report.get('appVersionCode')) is int and
            report['appVersionCode'] == apk['appVersionCode'] and
            isinstance(apk.get('appVersionName'), str) and re.fullmatch(r'[A-Za-z0-9._+-]{1,100}', apk['appVersionName']) and
            report.get('appVersionName') == apk['appVersionName'], 'app-version-mismatch')
    require(source_baseline.get('scope') == 'incomplete-guarded-wine-attempt-assessment' and
            prior_finish.get('scope') == 'incomplete-guarded-finish-assessment' and
            digest(source_baseline.get('reportSha256')) and digest(prior_finish.get('reportSha256')) and
            digest(prior_finish.get('sourceReportSha256')), 'invalid-source-baseline')
    source = report.get('clientGuardedWine'); finish = report.get('clientGuardedWineFinish')
    require(isinstance(source, dict) and isinstance(finish, dict), 'receipt-unavailable')
    require(finish.get('state') == 'complete' and finish.get('operation') == 'finish' and
            finish.get('completionPhase') == 'result-commit' and
            all(k not in finish for k in ('reason', 'failurePhase', 'failureCategory', 'failureErrno')), 'finish-incomplete')
    require(all(finish.get(k) is True for k in FLAGS) and finish.get('nativeProbeReexecuted') is False,
            'completion-evidence-incomplete')
    require(integer(finish.get('runtimeBindPlaceholdersRemoved'), 0, 2), 'invalid-placeholder-count')
    require(digest(finish.get('retainedTranscriptSha256')), 'invalid-transcript-binding')
    require(finish.get('sourceReportSha256') == prior_finish['sourceReportSha256'] and
            finish.get('sourceProbeAttemptId') == prior_finish.get('sourceProbeAttemptId') ==
            source.get('attemptId') == source_baseline.get('attemptId'), 'source-attempt-mismatch')
    require(source.get('state') == source_baseline.get('state') == 'failed' and
            source.get('reason') == source_baseline.get('reason') == 'io-failed' and
            source.get('operation') == 'probe' and source.get('runtimePostRehashValidated') is False and
            source.get('guardedWineStorageValidated') is False, 'original-failure-not-preserved')
    require(uuid(finish.get('attemptId')) and uuid(source.get('attemptId')) and
            finish['attemptId'] not in (source['attemptId'], prior_finish.get('attemptId')), 'invalid-attempt')
    require(integer(prior_finish.get('updatedAtEpochMs'), 1), 'invalid-prior-finish-timing')
    for suffix in ('StartedAtEpochMs', 'UpdatedAtEpochMs', 'ElapsedMs'):
        key = suffix[0].lower() + suffix[1:]
        require(integer(source_baseline.get(key), 1) and integer(source.get(key), 1) and
                integer(finish.get('sourceProbe' + suffix), 1) and
                source[key] == source_baseline[key] == finish['sourceProbe' + suffix], 'source-timing-mismatch')

    inventory = apk.get('nativePayloadSha256')
    require(isinstance(inventory, dict), 'invalid-packaging-inventory')
    pins = {name: inventory.get('lib/arm64-v8a/' + name) for name in ('libproot_ro.so', 'libproot-loader.so')}
    require(all(digest(h) for h in pins.values()), 'invalid-packaging-inventory')
    for receipt in (source, finish):
        require(receipt.get('available') is True and receipt.get('workerObservation') == 'inactive' and
                receipt.get('interrupted') is False, 'worker-not-finished')
        require(all(receipt.get(k) is False for k in UNQUALIFIED), 'unsupported-original-client-claim')
        require(all(receipt.get(k) is True for k in ('ownershipValidated', 'runtimePreRehashValidated',
                                                  'protectedFixtureUnchanged')), 'retained-evidence-incomplete')
        require(zero_map(receipt.get('windowsChecks'), KEYS) and receipt.get('windowsErrors') == {} and
                zero_map(receipt.get('probeStages'), STAGES), 'check-evidence-incomplete')
        require(receipt.get('guardedPayloadSha256') == pins == source_baseline.get('guardedPayloadSha256'), 'payload-mismatch')
        require(all(integer(receipt.get(k), v, v) for k, v in COUNTS.items()) and
                integer(receipt.get('ownershipStopExit'), 0, 255), 'retained-counts-mismatch')
        require(receipt.get('displayEvent') == 'ready', 'display-not-ready')
        require(digest(receipt.get('viewPlanSha256')) and
                receipt['viewPlanSha256'] == source_baseline.get('viewPlanSha256'), 'view-plan-mismatch')
        deletion = receipt.get('windowsDeletion')
        require(isinstance(deletion, dict) and set(deletion) ==
                {a + k for a in ('x86', 'x64') for k in ('ReturnedSuccess', 'Error')}, 'invalid-deletion-evidence')
        for arch in ('x86', 'x64'):
            success = deletion[arch + 'ReturnedSuccess']; error = deletion[arch + 'Error']
            require(integer(success, 0, 1) and integer(error, 0, 65535) and
                    ((success == 1 and error == 0) or (success == 0 and error in (5, 19))), 'invalid-deletion-evidence')
        require(integer(receipt.get('startedAtEpochMs'), 1) and
                integer(receipt.get('updatedAtEpochMs'), receipt['startedAtEpochMs']) and
                integer(report.get('createdAtEpochMs'), receipt['updatedAtEpochMs']) and
                integer(receipt.get('elapsedMs'), 1), 'invalid-timing')
        for key in ('archiveSha256', 'catalogSha256'):
            require(digest(runtime.get(key)) and receipt.get(key) == runtime[key] == source_baseline.get(key), 'runtime-identity-mismatch')
        exit_history(receipt)
    # The earlier bounded assessment omitted socket errno; both receipts above
    # independently require integer zero rather than inventing a baseline field.
    for key in (*(k for k in COUNTS if k != 'socketProbeErrno'), 'ownershipStopExit', 'windowsDeletion', 'windowsChecks', 'probeStages'):
        require(source.get(key) == finish.get(key) == source_baseline.get(key), 'retained-observation-changed')
    for key in ('bytes', 'totalBytes', 'entries'):
        require(integer(source.get(key)) and source[key] == source_baseline.get(key), 'source-progress-changed')
    require(finish['startedAtEpochMs'] > max(source['updatedAtEpochMs'], prior_finish['updatedAtEpochMs']), 'finish-not-after-source')
    require(integer(runtime.get('entries'), 1, 100000) and integer(runtime.get('bytes'), 1, 1024**4) and
            integer(finish.get('entries'), 1) and finish['entries'] == runtime['entries'] and
            integer(finish.get('bytes'), 1) and integer(finish.get('totalBytes'), 1) and
            finish['bytes'] == finish['totalBytes'] == runtime['bytes'], 'runtime-counts-mismatch')
    platform = report.get('platform')
    require(isinstance(platform, dict) and integer(platform.get('sdkInt'), 26, 100) and
            isinstance(platform.get('supportedAbis'), list) and 'arm64-v8a' in platform['supportedAbis'], 'invalid-platform')
    history, exit_count = exit_history(finish)
    result = dict(formatVersion=1, scope='retained-guarded-wine-finish-assessment',
        appVersionCode=report['appVersionCode'], appVersionName=report['appVersionName'], apkSha256=report['apkSha256'],
        attemptId=finish['attemptId'], sourceProbeAttemptId=source['attemptId'],
        sourceReportSha256=finish['sourceReportSha256'], retainedTranscriptSha256=finish['retainedTranscriptSha256'],
        sourceAssessmentReportSha256=source_baseline['reportSha256'], priorFinishReportSha256=prior_finish['reportSha256'],
        startedAtEpochMs=finish['startedAtEpochMs'], updatedAtEpochMs=finish['updatedAtEpochMs'], elapsedMs=finish['elapsedMs'],
        archiveSha256=runtime['archiveSha256'], catalogSha256=runtime['catalogSha256'], entries=runtime['entries'], bytes=runtime['bytes'],
        windowsChecksPassed=len(KEYS), stagesPassed=len(STAGES), sourceAttemptPreserved=True,
        originalSourceState='failed', sourceReportDigestMatchesPriorFinish=True,
        runtimeBindPlaceholdersRemoved=finish['runtimeBindPlaceholdersRemoved'], runtimeBindPlaceholdersChecked=True,
        retainedFilesValidated=True, retainedFilesUnchanged=True, nativeProbeReexecuted=False,
        guardedWineFixtureQualified=True, fullRuntimeRehashQualified=True, completionPhase='result-commit',
        ownedCleanupQualified=True, workerObservation='inactive', noAttemptMatchedExitObserved=history,
        unmatchedHistoricalExitCount=exit_count, androidSdkInt=platform['sdkInt'],
        reportedThor=platform.get('manufacturer') == 'AYN' and platform.get('model') == 'AYN Thor',
        importedClientAccessed=False, originalWorkingViewQualified=False, originalClientExecuted=False,
        warcraftXLCompatible=False, gameLoginQualified=False, hostileCodeConfinementQualified=False,
        actualCacheEvictionMeasured=False, reportAuthenticityVerified=False, deviceFilesIndependentlyRead=False,
        nextAction='isolated-original-client-working-view')
    for key in OBSERVATIONS:
        if key in finish:
            require(integer(finish[key]), 'invalid-observation'); result[key] = finish[key]
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('report', 'apk-evidence', 'runtime-lock', 'source-assessment', 'prior-finish', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    try:
        report, raw = read_json(args.report); apk, _ = read_json(args.apk_evidence)
        lock, _ = read_json(args.runtime_lock); source, _ = read_json(args.source_assessment)
        prior, _ = read_json(args.prior_finish)
        require(isinstance(lock, dict) and isinstance(lock.get('archive'), dict) and isinstance(lock.get('catalog'), dict), 'invalid-runtime-lock')
        runtime = dict(archiveSha256=lock['archive'].get('sha256'), catalogSha256=lock['catalog'].get('sha256'),
                       entries=lock.get('archiveEntries'), bytes=lock.get('archiveFileBytes'))
        result = assess(report, apk, runtime, source, prior)
        result.update(reportSha256=hashlib.sha256(raw).hexdigest(), reportBytes=len(raw))
        with args.output.open('x') as output: json.dump(result, output, indent=2); output.write('\n')
        print(json.dumps({k: result[k] for k in ('guardedWineFixtureQualified', 'runtimeBindPlaceholdersRemoved', 'elapsedMs', 'nextAction')}))
        return 0
    except InvalidEvidence as error:
        print('Guarded finish evidence rejected: ' + str(error), file=sys.stderr)
    except (OSError, ValueError, TypeError, RecursionError):
        print('Guarded finish evidence rejected; inspect private input against the documented contract.', file=sys.stderr)
    return 1


if __name__ == '__main__': sys.exit(main())
