import copy
import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
from verify_world_session import profile_delta, PROFILE, MANAGER


class WorldSessionProfileTests(unittest.TestCase):
    def test_only_one_reviewed_shutdown_source_delta_can_extend_the_baseline(self):
        good = json.loads((ROOT / 'native-port/profiles.json').read_text())
        patch = profile_delta(good)
        self.assertEqual([MANAGER], list(patch['files']))
        for mutation in ('patch', 'file', 'module'):
            bad = copy.deepcopy(good); profile = bad['profiles'][PROFILE]
            if mutation == 'patch': profile['patches'][0]['sha256'] = '0' * 64
            elif mutation == 'file': profile['patches'][-1]['files']['src/server/game/Other.cpp'] = {}
            else: profile['result_inventory']['modules'].pop()
            with self.assertRaises(ValueError): profile_delta(bad)
