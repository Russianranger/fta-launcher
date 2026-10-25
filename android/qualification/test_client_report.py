import json
from pathlib import Path
import tempfile
import unittest

from client_report import assess, read_json, InvalidEvidence


class ClientReportTests(unittest.TestCase):
    def setUp(self):
        self.apk = dict(apk_sha256='a' * 64, appVersionCode=37, appVersionName='synthetic-client')
        self.report = dict(packageName='com.russianranger.fta', apkSha256='a' * 64,
                           appVersionCode=37, appVersionName='synthetic-client', createdAtEpochMs=2000,
                           clientInspection=dict(state='complete', files=12, knownBytes=5000, unknownSizes=0),
                           clientImport=dict(available=True, state='complete', operation='import',
                               attemptId='00000000-0000-0000-0000-000000000001', sourceWritePerformed=False,
                               clientImported=True, copyReadbackValidated=True, retainedRehashValidated=False,
                               warcraftXLCompatible=False, gameLoginQualified=False, interrupted=False,
                               workerObservation='inactive', catalogSha256='b' * 64, files=12, totalFiles=12,
                               bytes=5000, totalBytes=5000, resumedBytes=1000, startedAtEpochMs=100,
                               updatedAtEpochMs=1000, elapsedMs=900, exitHistorySupported=True,
                               exitHistoryAvailable=True, recentClientExits=[]))

    @property
    def receipt(self):
        return self.report['clientImport']

    def verification(self):
        baseline = assess(self.report, self.apk)
        self.receipt.update(operation='verify', retainedRehashValidated=True, resumedBytes=0,
                            attemptId='00000000-0000-0000-0000-000000000002',
                            startedAtEpochMs=1100, updatedAtEpochMs=1900, elapsedMs=800)
        return baseline

    def rejects(self, reason, baseline=None):
        with self.assertRaisesRegex(InvalidEvidence, '^' + reason + '$'):
            assess(self.report, self.apk, baseline)

    def test_import_success_never_promotes_retained_or_runtime_gates(self):
        result = assess(self.report, self.apk)
        self.assertTrue(result['importQualified'])
        for key in ('retainedRehashQualified', 'clientIntegrityGateQualified', 'warcraftXLCompatible',
                    'gameLoginQualified', 'actualCacheEvictionMeasured', 'pressureBackoffQualified'):
            self.assertFalse(result[key])
        self.assertEqual('verify-imported-client', result['nextAction'])

    def test_verification_requires_same_catalog_counts_and_later_attempt(self):
        baseline = self.verification(); result = assess(self.report, self.apk, baseline)
        self.assertTrue(result['retainedRehashQualified']); self.assertTrue(result['matchesPriorImport'])
        self.assertTrue(result['clientIntegrityGateQualified']); self.assertFalse(result['gameLoginQualified'])

    def test_verification_without_import_baseline_is_explicitly_incomplete(self):
        self.verification(); result = assess(self.report, self.apk)
        self.assertTrue(result['retainedRehashQualified']); self.assertFalse(result['clientIntegrityGateQualified'])
        self.assertEqual('match-import-baseline', result['nextAction'])

    def test_wrong_apk_cannot_be_qualified(self):
        self.report['apkSha256'] = 'c' * 64; self.rejects('apk-identity-mismatch')

    def test_wrong_version_or_package_cannot_be_qualified(self):
        self.report['appVersionCode'] = True; self.rejects('app-version-mismatch')
        self.report['appVersionCode'] = 37; self.report['packageName'] = 'other'; self.rejects('package-mismatch')

    def test_running_failed_or_stopped_receipt_cannot_pass(self):
        for state in ('copy', 'verify', 'failed', 'stopped'):
            self.receipt['state'] = state; self.rejects('client-operation-incomplete')

    def test_false_success_flags_cannot_pass(self):
        for key in ('clientImported', 'copyReadbackValidated'):
            self.receipt[key] = False; self.rejects('copy-evidence-inconsistent'); self.receipt[key] = True
        self.receipt['sourceWritePerformed'] = True; self.rejects('copy-evidence-inconsistent')

    def test_import_cannot_claim_full_rehash(self):
        self.receipt['retainedRehashValidated'] = True; self.rejects('retained-rehash-evidence-inconsistent')

    def test_verification_must_have_full_rehash_flag(self):
        self.verification(); self.receipt['retainedRehashValidated'] = False
        self.rejects('retained-rehash-evidence-inconsistent')

    def test_partial_counts_and_bool_numbers_rejected(self):
        for value in (11, True, '12', -1):
            self.receipt['files'] = value; self.rejects('incomplete-counts')

    def test_older_unmatched_low_memory_exit_does_not_fail_complete_import(self):
        self.receipt['recentClientExits'] = [dict(timestampEpochMs=50, reasonCode=3, attemptMatched=False)]
        result = assess(self.report, self.apk)
        self.assertTrue(result['importQualified']); self.assertEqual(1, result['unmatchedHistoricalExitCount'])

    def test_matching_exit_contradicts_complete_receipt(self):
        self.receipt['recentClientExits'] = [dict(timestampEpochMs=500, reasonCode=3, attemptMatched=True)]
        self.rejects('contradictory-attempt-exit')

    def test_unavailable_exit_history_does_not_invent_survival_observations(self):
        self.receipt['exitHistoryAvailable'] = False
        result = assess(self.report, self.apk)
        self.assertTrue(result['importQualified']); self.assertFalse(result['noAttemptMatchedExitObserved'])

    def test_unbounded_or_malformed_exit_history_is_rejected(self):
        self.receipt['recentClientExits'] = [dict(timestampEpochMs=50, reasonCode=3, attemptMatched=False)] * 4
        self.rejects('invalid-exit-history')
        self.receipt['recentClientExits'] = [dict(timestampEpochMs=50, reasonCode=3)]
        self.rejects('invalid-exit-history')

    def test_catalog_mismatch_prevents_combined_gate(self):
        baseline = self.verification(); self.receipt['catalogSha256'] = 'c' * 64
        self.rejects('import-baseline-mismatch', baseline)

    def test_reused_attempt_and_old_verification_prevent_combined_gate(self):
        baseline = self.verification(); self.receipt['startedAtEpochMs'] = 900
        self.rejects('verification-not-after-import', baseline)
        self.receipt['startedAtEpochMs'] = 1100; self.receipt['attemptId'] = baseline['attemptId']
        self.rejects('verification-not-after-import', baseline)

    def test_inspection_totals_must_match(self):
        self.report['clientInspection']['knownBytes'] = 4999; self.rejects('inspection-totals-mismatch')

    def test_secret_fields_are_never_copied_to_assessment(self):
        secret = 'private-source-uri-and-settings'
        self.receipt.update(sourceUri=secret, filesByName={secret: secret}, configuration=secret)
        self.report['private'] = secret
        self.assertNotIn(secret, json.dumps(assess(self.report, self.apk)))

    def test_accepting_cache_advice_does_not_prove_eviction_or_pause_recovery(self):
        self.receipt.update(sourceCacheRequests=100, sourceCacheAccepted=100, pressurePauses=2)
        result = assess(self.report, self.apk)
        self.assertFalse(result['actualCacheEvictionMeasured']); self.assertFalse(result['pressureBackoffQualified'])
        self.assertEqual(2, result['pressurePauses'])

    def test_runtime_claims_cannot_be_derived_from_this_gate(self):
        self.receipt['warcraftXLCompatible'] = True; self.rejects('unsupported-client-runtime-claim')

    def test_malformed_numeric_telemetry_is_not_exported(self):
        self.receipt['peakRssKiB'] = 'private error'; self.rejects('invalid-observation')

    def test_completed_receipt_with_failure_or_running_worker_is_rejected(self):
        self.receipt['reason'] = 'memory-pressure'; self.rejects('completed-receipt-has-failure')
        del self.receipt['reason']; self.receipt['workerObservation'] = 'active'; self.rejects('worker-not-finished')

    def test_duplicate_json_keys_and_oversized_input_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'report.json'; path.write_text('{"state":"failed","state":"complete"}')
            with self.assertRaisesRegex(InvalidEvidence, 'duplicate-json-key'): read_json(path)
            path.write_bytes(b' ' * (2 * 1024 * 1024 + 1))
            with self.assertRaisesRegex(InvalidEvidence, 'document-too-large'): read_json(path)

    def test_corrupt_baseline_does_not_bind_verification(self):
        baseline = self.verification(); baseline['formatVersion'] = True
        self.rejects('invalid-import-baseline', baseline)


if __name__ == '__main__':
    unittest.main()
