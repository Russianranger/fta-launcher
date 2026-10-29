import copy
import json
from pathlib import Path
import unittest

from client_report import InvalidEvidence
from client_guarded_finish_report import assess, FLAGS, UNQUALIFIED, KEYS


ROOT = Path(__file__).resolve().parents[2]


class GuardedFinishReportTests(unittest.TestCase):
    def setUp(self):
        def evidence(name):
            return json.loads((ROOT / 'docs/evidence' / name).read_text())
        self.apk = evidence('android-2026-10-28-private-bind-cleanup-apk.json')
        self.baseline = evidence('android-2026-10-28-thor-code43-incomplete.json')
        self.prior = evidence('android-2026-10-28-thor-code44-access-denied.json')
        lock = json.loads((ROOT / 'android/qualification/client-runtime-lock.json').read_text())
        self.runtime = dict(archiveSha256=lock['archive']['sha256'], catalogSha256=lock['catalog']['sha256'],
                            entries=lock['archiveEntries'], bytes=lock['archiveFileBytes'])
        source = copy.deepcopy(self.baseline)
        source.update(available=True, operation='probe', interrupted=False, windowsErrors={},
                      guardedWineStorageValidated=False, ownershipValidated=True, displayEvent='ready', socketProbeErrno=0,
                      exitHistoryAvailable=True, exitHistorySupported=True, recentClientRuntimeExits=[])
        source.update({k: False for k in UNQUALIFIED})
        finish = copy.deepcopy(source)
        start = self.prior['updatedAtEpochMs'] + 1000
        finish.update(state='complete', operation='finish', completionPhase='result-commit',
            attemptId='00000000-0000-0000-0000-000000000045', startedAtEpochMs=start, updatedAtEpochMs=start + 1000,
            elapsedMs=1000, sourceProbeAttemptId=source['attemptId'], sourceReportSha256=self.prior['sourceReportSha256'],
            retainedTranscriptSha256='1' * 64, nativeProbeReexecuted=False, runtimeBindPlaceholdersRemoved=2,
            entries=self.runtime['entries'])
        finish.pop('reason')
        finish.update({k: True for k in FLAGS})
        for key in ('startedAtEpochMs', 'updatedAtEpochMs', 'elapsedMs'):
            finish['sourceProbe' + key[0].upper() + key[1:]] = source[key]
        self.report = dict(packageName='com.russianranger.fta', appVersionCode=self.apk['appVersionCode'],
            appVersionName=self.apk['appVersionName'], apkSha256=self.apk['apk_sha256'], createdAtEpochMs=start + 2000,
            clientGuardedWine=source, clientGuardedWineFinish=finish,
            platform=dict(sdkInt=33, supportedAbis=['arm64-v8a'], manufacturer='AYN', model='AYN Thor'))

    def check(self, report=None):
        return assess(report or self.report, self.apk, self.runtime, self.baseline, self.prior)

    def rejects(self, receipt, key, bad):
        report = copy.deepcopy(self.report)
        target = report[receipt] if receipt else report
        target[key] = bad
        with self.assertRaises(InvalidEvidence): self.check(report)

    def test_success_preserves_failed_source_and_limits_qualification(self):
        result = self.check()
        self.assertTrue(result['guardedWineFixtureQualified'])
        self.assertTrue(result['sourceAttemptPreserved'])
        self.assertEqual('failed', result['originalSourceState'])
        self.assertEqual(2, result['runtimeBindPlaceholdersRemoved'])
        self.assertFalse(result['nativeProbeReexecuted'])
        for key in ('importedClientAccessed', 'originalWorkingViewQualified', 'originalClientExecuted',
                    'warcraftXLCompatible', 'gameLoginQualified', 'hostileCodeConfinementQualified',
                    'actualCacheEvictionMeasured', 'reportAuthenticityVerified', 'deviceFilesIndependentlyRead'):
            self.assertFalse(result[key])

    def test_exact_apk_and_version_are_required(self):
        for key, bad in (('appVersionCode', 44), ('appVersionCode', 45.0), ('appVersionName', 'old'),
                         ('apkSha256', '0' * 64), ('packageName', 'other')):
            self.rejects(None, key, bad)
        self.apk['appVersionCode'] = 44; self.report['appVersionCode'] = 44
        with self.assertRaises(InvalidEvidence): self.check()

    def test_all_completion_flags_and_final_phase_are_mandatory(self):
        for key in FLAGS:
            for value in (False, 1, 'true', None): self.rejects('clientGuardedWineFinish', key, value)
        for key, bad in (('nativeProbeReexecuted', True), ('state', 'failed'), ('completionPhase', 'runtime-pre-verify'),
                         ('reason', 'io-failed'), ('failureCategory', 'access-denied'), ('workerObservation', 'active')):
            self.rejects('clientGuardedWineFinish', key, bad)

    def test_every_windows_check_requires_integer_zero_in_both_receipts(self):
        for section in ('clientGuardedWine', 'clientGuardedWineFinish'):
            for key in KEYS:
                for bad in (False, 0.0, '0', 1, None):
                    checks = dict(self.report[section]['windowsChecks'], **{key: bad})
                    self.rejects(section, 'windowsChecks', checks)
                checks = dict(self.report[section]['windowsChecks']); del checks[key]
                self.rejects(section, 'windowsChecks', checks)
            self.rejects(section, 'windowsChecks', dict(self.report[section]['windowsChecks'], unexpected=0))
            self.rejects(section, 'windowsErrors', {'error': 0})
            stages = dict(self.report[section]['probeStages']); stages.pop('wine-stop')
            self.rejects(section, 'probeStages', stages)

    def test_source_attempt_digest_timing_and_original_failure_must_remain(self):
        for key, bad in (('sourceProbeAttemptId', '00000000-0000-0000-0000-000000000000'),
                         ('sourceReportSha256', '0' * 64), ('retainedTranscriptSha256', 'bad'),
                         ('sourceProbeElapsedMs', self.baseline['elapsedMs'] + 1)):
            self.rejects('clientGuardedWineFinish', key, bad)
        for key, bad in (('state', 'complete'), ('reason', 'other'), ('entries', 14092),
                         ('runtimePostRehashValidated', True), ('guardedWineStorageValidated', True)):
            self.rejects('clientGuardedWine', key, bad)

    def test_runtime_full_counts_and_cleanup_bounds_cannot_be_inferred_from_bytes(self):
        for key, bad in (('entries', 14089), ('bytes', self.runtime['bytes'] - 1), ('totalBytes', True),
                         ('runtimeBindPlaceholdersRemoved', 3), ('runtimeBindPlaceholdersRemoved', True),
                         ('catalogSha256', '0' * 64), ('probeCleanupComplete', 0)):
            self.rejects('clientGuardedWineFinish', key, bad)
        # Idempotent completion can observe no remaining placeholders, but must
        # still complete its cleanup checks and full catalog verification.
        self.report['clientGuardedWineFinish']['runtimeBindPlaceholdersRemoved'] = 0
        self.assertTrue(self.check()['guardedWineFixtureQualified'])

    def test_payload_and_retained_observations_are_bound_to_original(self):
        self.rejects('clientGuardedWineFinish', 'ownershipStopExit', 0)
        self.rejects('clientGuardedWineFinish', 'viewPlanSha256', '0' * 64)
        self.rejects('clientGuardedWineFinish', 'guardedPayloadSha256', {})
        self.apk['nativePayloadSha256']['lib/arm64-v8a/libproot_ro.so'] = '0' * 64
        with self.assertRaises(InvalidEvidence): self.check()

    def test_windows_delete_success_does_not_replace_file_survival_checks(self):
        deletion = dict(self.report['clientGuardedWineFinish']['windowsDeletion'])
        deletion['x86Error'] = 5
        self.rejects('clientGuardedWineFinish', 'windowsDeletion', deletion)
        deletion['x86ReturnedSuccess'] = False
        self.rejects('clientGuardedWineFinish', 'windowsDeletion', deletion)
        self.rejects('clientGuardedWineFinish', 'retainedFilesUnchanged', False)

    def test_no_original_execution_or_integrity_claim_can_be_promoted(self):
        for section in ('clientGuardedWine', 'clientGuardedWineFinish'):
            for key in UNQUALIFIED:
                for bad in (True, 0, None): self.rejects(section, key, bad)

    def test_exit_history_rejects_both_token_and_time_contradictions(self):
        finish = self.report['clientGuardedWineFinish']; source = self.report['clientGuardedWine']
        entry = dict(timestampEpochMs=source['startedAtEpochMs'] - 1000, reasonCode=10, status=0, attemptMatched=False)
        finish['recentClientRuntimeExits'] = [entry]
        self.assertTrue(self.check()['noAttemptMatchedExitObserved'])
        self.rejects('clientGuardedWineFinish', 'recentClientRuntimeExits', [dict(entry, attemptMatched=True)])
        self.rejects('clientGuardedWineFinish', 'recentClientRuntimeExits', [dict(entry, timestampEpochMs=finish['startedAtEpochMs'] + 10)])
        self.rejects('clientGuardedWineFinish', 'recentClientRuntimeExits', [entry] * 4)
        self.rejects('clientGuardedWineFinish', 'exitHistoryAvailable', False)
        finish['recentClientRuntimeExits'] = []; finish['exitHistoryAvailable'] = False
        self.assertFalse(self.check()['noAttemptMatchedExitObserved'])

    def test_finish_must_follow_source_and_prior_recovery(self):
        self.rejects('clientGuardedWineFinish', 'startedAtEpochMs', self.prior['updatedAtEpochMs'])
        self.rejects('clientGuardedWineFinish', 'attemptId', self.prior['attemptId'])
        self.rejects('clientGuardedWineFinish', 'updatedAtEpochMs', 1)
        self.rejects(None, 'createdAtEpochMs', 1)

    def test_private_extras_are_not_exported_and_observations_are_typed(self):
        self.report['privatePath'] = 'PRIVATE'
        self.report['clientGuardedWineFinish'].update(privateLog='PRIVATE', configuration='PRIVATE')
        self.assertNotIn('PRIVATE', json.dumps(self.check()))
        self.rejects('clientGuardedWineFinish', 'pressureWaitMs', '28000')


if __name__ == '__main__': unittest.main()
