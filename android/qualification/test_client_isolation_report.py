import copy
import json
from pathlib import Path
import unittest

from client_report import InvalidEvidence
from client_isolation_report import assess, FLAGS, REJECTIONS


class ClientIsolationReportTests(unittest.TestCase):
    def setUp(self):
        self.apk = json.loads((Path(__file__).resolve().parents[2] /
            'docs/evidence/android-2026-10-28-private-storage-isolation-apk.json').read_text())
        manifest = self.apk['clientIsolationManifest']
        self.receipt = dict(available=True, state='complete', workerObservation='inactive', interrupted=False,
            importedClientAccessed=False, wineExecuted=False, originalClientExecuted=False,
            attemptId='00000000-0000-0000-0000-000000000001', fixtureExit=0, fixtureBytes=65545,
            fixtureChecksPassed=50, startedAtEpochMs=100, updatedAtEpochMs=1000, elapsedMs=900,
            checks={n: 0 for n in manifest['checks']}, rootRejections={n: True for n in REJECTIONS},
            payloadSha256=dict(manifest['payloadSha256'], **{'libproot-loader.so': manifest['loaderSha256']}),
            exitHistoryAvailable=True, recentExits=[], **{k: True for k in FLAGS})
        self.report = dict(packageName='com.russianranger.fta', apkSha256=self.apk['apk_sha256'],
            appVersionCode=self.apk['appVersionCode'], appVersionName=self.apk['appVersionName'],
            createdAtEpochMs=1100, clientStorageIsolation=self.receipt,
            platform=dict(sdkInt=33, supportedAbis=['arm64-v8a'], manufacturer='AYN', model='AYN Thor'))

    def rejects(self):
        with self.assertRaises(InvalidEvidence): assess(self.report, self.apk)

    def test_pass_qualifies_only_the_disposable_fixture(self):
        result = assess(self.report, self.apk)
        self.assertTrue(result['androidStorageFixtureQualified'])
        self.assertTrue(result['reportedThor'])
        for key in ('importedTreeProtectionQualified', 'guardedWineQualified', 'originalClientExecuted',
                    'warcraftXLCompatible', 'gameLoginQualified', 'hostileCodeConfinementQualified', 'reportAuthenticityVerified'):
            self.assertFalse(result[key])

    def test_every_check_must_be_present_and_integer_zero(self):
        for key in list(self.receipt['checks']):
            for bad in (False, '0', 0.0, 1, None):
                self.receipt['checks'][key] = bad; self.rejects()
            del self.receipt['checks'][key]; self.rejects(); self.receipt['checks'][key] = 0
        self.receipt['checks']['extra'] = 0; self.rejects()

    def test_root_refusals_cannot_be_missing_or_truthy(self):
        for key in REJECTIONS:
            for bad in (False, 1, 'true', None):
                self.receipt['rootRejections'][key] = bad; self.rejects()
            del self.receipt['rootRejections'][key]; self.rejects(); self.receipt['rootRejections'][key] = True

    def test_flags_and_unmeasured_execution_claims_fail_closed(self):
        for key in FLAGS:
            self.receipt[key] = 1; self.rejects(); self.receipt[key] = True
        for key in ('importedClientAccessed', 'wineExecuted', 'originalClientExecuted'):
            self.receipt[key] = True; self.rejects(); self.receipt[key] = False

    def test_terminal_counts_cleanup_and_timing_are_checked(self):
        for key, bad in [('state', 'failed'), ('reason', 'probe-failed'), ('workerObservation', 'active'),
                         ('interrupted', True), ('fixtureExit', True), ('fixtureChecksPassed', 49),
                         ('fixtureBytes', 65544), ('ownedHandlesReaped', False), ('elapsedMs', 120001),
                         ('updatedAtEpochMs', 50), ('attemptId', 'invalid')]:
            old = copy.deepcopy(self.receipt); self.receipt[key] = bad; self.rejects()
            self.receipt.clear(); self.receipt.update(old)

    def test_app_payload_and_packaging_contract_must_match(self):
        for key, bad in [('apkSha256', '0' * 64), ('packageName', 'other'), ('appVersionCode', True), ('appVersionName', 'other')]:
            old = self.report[key]; self.report[key] = bad; self.rejects(); self.report[key] = old
        for key in list(self.receipt['payloadSha256']):
            old = self.receipt['payloadSha256'][key]; self.receipt['payloadSha256'][key] = '0' * 64
            self.rejects(); self.receipt['payloadSha256'][key] = old
        self.apk['nativePayloadSha256']['lib/arm64-v8a/libproot_ro.so'] = '0' * 64; self.rejects()

    def test_historical_exits_do_not_mask_attempt_exits(self):
        exit = dict(reason=3, status=0, timestamp=50, attemptMatched=False)
        self.receipt['recentExits'] = [exit]
        self.assertTrue(assess(self.report, self.apk)['noAttemptMatchedExitObserved'])
        exit['attemptMatched'] = True; self.rejects(); exit['attemptMatched'] = False
        exit['timestamp'] = 500; self.rejects()

    def test_absent_or_malformed_history_cannot_invent_survival_evidence(self):
        self.receipt['exitHistoryAvailable'] = False
        self.assertFalse(assess(self.report, self.apk)['noAttemptMatchedExitObserved'])
        for value in ('bad', [dict(attemptMatched=False)], [dict(timestamp=50, reason=3, status=0, attemptMatched=False)]):
            self.receipt['recentExits'] = value; self.rejects()
        self.receipt['exitHistoryAvailable'] = True
        self.receipt['recentExits'] = [dict(timestamp=50, reason=3, status=0, attemptMatched=False)] * 4; self.rejects()

    def test_contract_duplicates_and_private_fields_never_pass(self):
        self.receipt.update(path='PRIVATE', rawLog='PRIVATE', config='PRIVATE')
        self.report['privateData'] = 'PRIVATE'
        self.assertNotIn('PRIVATE', json.dumps(assess(self.report, self.apk)))
        self.apk['clientIsolationManifest']['checks'][-1] = self.apk['clientIsolationManifest']['checks'][0]
        self.rejects()


if __name__ == '__main__': unittest.main()
