import copy
import json
import unittest

from client_report import InvalidEvidence
from client_runtime_report import assess, STAGES, FLAGS


class ClientRuntimeReportTests(unittest.TestCase):
    def setUp(self):
        self.apk = dict(apk_sha256='a' * 64, appVersionCode=41, appVersionName='synthetic')
        self.runtime = dict(archiveSha256='b' * 64, catalogSha256='c' * 64, entries=5, bytes=100)
        self.receipt = dict(available=True, state='complete', operation='probe', workerObservation='inactive',
                            interrupted=False, retainedRehashValidated=False, originalClientExecuted=False,
                            androidWineQualified=False, warcraftXLCompatible=False, gameLoginQualified=False,
                            attemptId='00000000-0000-0000-0000-000000000001', **self.runtime,
                            totalBytes=100, probeStages={key: 0 for key in STAGES}, ownershipNormalExit=0,
                            ownershipStopExit=255, ownershipKilledExit=137, stoppedTracees=3, killedTracees=3,
                            probeCleanupComplete=1, displayEvent='ready', socketProbeErrno=0,
                            startedAtEpochMs=100, updatedAtEpochMs=1000, elapsedMs=900,
                            exitHistorySupported=True, exitHistoryAvailable=True, recentClientRuntimeExits=[])
        self.receipt.update({key: True for key in FLAGS})
        self.report = dict(packageName='com.russianranger.fta', apkSha256='a' * 64, appVersionCode=41,
                           appVersionName='synthetic', createdAtEpochMs=1100, clientRuntime=self.receipt)

    def rejects(self):
        with self.assertRaises(InvalidEvidence):
            assess(self.report, self.apk, self.runtime)

    def test_complete_probe_qualifies_only_synthetic_runtime(self):
        result = assess(self.report, self.apk, self.runtime)
        self.assertTrue(result['syntheticAndroidRuntimeQualified'])
        self.assertTrue(result['fullPrePostRehashQualified'])
        for key in ('originalClientExecuted', 'originalWineClientQualified', 'warcraftXLCompatible',
                    'gameLoginQualified', 'actualCacheEvictionMeasured', 'reportAuthenticityVerified'):
            self.assertFalse(result[key])

    def test_every_stage_is_required_with_exact_integer_zero(self):
        for key in STAGES:
            for bad in (False, '0', 1, -1, None):
                self.receipt['probeStages'][key] = bad; self.rejects()
            del self.receipt['probeStages'][key]; self.rejects()
            self.receipt['probeStages'][key] = 0
        self.receipt['probeStages']['extra'] = 0; self.rejects()

    def test_success_flags_cannot_replace_evidence_or_be_truthy_integers(self):
        for key in FLAGS:
            for bad in (False, 1, 'true', None):
                self.receipt[key] = bad; self.rejects()
            self.receipt[key] = True
        self.receipt['probeCleanupComplete'] = True; self.rejects()

    def test_failed_running_verify_and_inconsistent_terminal_reports_rejected(self):
        for key, value in (('state', 'failed'), ('operation', 'verify'), ('workerObservation', 'active'),
                           ('interrupted', True), ('reason', 'memory-pressure'), ('retainedRehashValidated', True)):
            old = copy.deepcopy(self.receipt)
            self.receipt[key] = value; self.rejects()
            self.receipt.clear(); self.receipt.update(old)

    def test_identity_version_package_and_runtime_changes_rejected(self):
        for key, value in (('apkSha256', 'd' * 64), ('appVersionCode', True),
                           ('appVersionName', 'other'), ('packageName', 'other')):
            old = self.report[key]; self.report[key] = value; self.rejects(); self.report[key] = old
        for key in ('archiveSha256', 'catalogSha256'):
            old = self.receipt[key]; self.receipt[key] = 'd' * 64; self.rejects(); self.receipt[key] = old

    def test_counts_ownership_display_and_timestamps_checked(self):
        for key, value in (('entries', True), ('bytes', 99), ('totalBytes', 101), ('ownershipNormalExit', 1),
                           ('ownershipStopExit', 256), ('ownershipKilledExit', 143), ('stoppedTracees', 2),
                           ('killedTracees', 2), ('probeCleanupComplete', 0), ('socketProbeErrno', 13),
                           ('displayEvent', 'timeout'), ('elapsedMs', -1), ('updatedAtEpochMs', 50)):
            old = self.receipt[key]; self.receipt[key] = value; self.rejects(); self.receipt[key] = old

    def test_historical_kill_is_not_an_attempt_matched_kill(self):
        self.receipt['recentClientRuntimeExits'] = [dict(timestampEpochMs=50, reasonCode=3, attemptMatched=False)]
        self.assertTrue(assess(self.report, self.apk, self.runtime)['noAttemptMatchedExitObserved'])
        self.receipt['recentClientRuntimeExits'][0]['attemptMatched'] = True; self.rejects()

    def test_missing_history_never_invents_os_survival_proof(self):
        self.receipt['exitHistoryAvailable'] = False
        self.assertFalse(assess(self.report, self.apk, self.runtime)['noAttemptMatchedExitObserved'])
        self.receipt['recentClientRuntimeExits'] = [dict(timestampEpochMs=50, reasonCode=3, attemptMatched=False)]
        self.rejects()

    def test_malformed_or_unbounded_history_rejected(self):
        for exits in ('bad', [dict(timestampEpochMs=50, reasonCode=3)],
                      [dict(timestampEpochMs=50, reasonCode=3, attemptMatched=False)] * 4):
            self.receipt['recentClientRuntimeExits'] = exits; self.rejects()

    def test_observations_are_typed_and_secrets_never_copied(self):
        self.receipt.update(rawLog='PRIVATE', config='PRIVATE', path='PRIVATE', probeDiagnostics=['PRIVATE'])
        self.report['privateData'] = 'PRIVATE'
        self.assertNotIn('PRIVATE', json.dumps(assess(self.report, self.apk, self.runtime)))
        self.receipt['pressureWaitMs'] = True; self.rejects()

    def test_no_original_execution_claim_can_be_smuggled_into_a_pass(self):
        for key in ('originalClientExecuted', 'androidWineQualified', 'warcraftXLCompatible', 'gameLoginQualified'):
            self.receipt[key] = True; self.rejects(); self.receipt[key] = False


if __name__ == '__main__':
    unittest.main()
