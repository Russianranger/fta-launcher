"""Fail-closed checks for the completed world's CMake build evidence."""
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from build_native import BuildError, world_build_evidence


class WorldBuildEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / 'source'
        self.build = self.root / 'build'
        self.reply = self.build / '.cmake/api/v1/reply'
        self.reply.mkdir(parents=True)
        (self.build / 'CMakeCache.txt').write_text('LUA_VERSION:STRING=lua52\nLUA_STATIC:BOOL=ON\n')
        self.modules = ['first-module', 'second-module']
        self.targets = {}
        for name in ('worldserver', 'modules', 'scripts', 'lualib'):
            artifact = name if name == 'worldserver' else f'lib{name}.a'
            (self.build / artifact).write_bytes(name.encode())
            self.targets[name] = {
                'name': name,
                'type': 'EXECUTABLE' if name == 'worldserver' else 'STATIC_LIBRARY',
                'artifacts': [{'path': artifact}],
                'compileGroups': [{'language': 'CXX', 'languageStandard': {'standard': '20'}}],
            }
        self.targets['worldserver']['link'] = {'commandFragments': [
            {'fragment': 'libmodules.a libscripts.a liblualib.a', 'role': 'libraries'}]}
        self.targets['worldserver']['compileGroups'][0]['compileCommandFragments'] = [
            {'fragment': '-DAC_MODULES_LIST="first-module,second-module,"'}]
        self.targets['modules']['sources'] = [
            {'path': f'modules/{name}/entry.cpp', 'compileGroupIndex': 0} for name in self.modules]

    def evidence(self):
        for name, target in self.targets.items():
            (self.reply / f'{name}.json').write_text(json.dumps(target))
        (self.reply / 'codemodel.json').write_text(json.dumps({'configurations': [{
            'targets': [{'jsonFile': f'{name}.json'} for name in self.targets]}]}))
        (self.reply / 'index-build.json').write_text(json.dumps({'reply': {
            'codemodel-v2': {'jsonFile': 'codemodel.json'}}}))
        return world_build_evidence(self.build, self.source, self.modules)

    def test_records_actual_linked_artifacts_and_module_compilations(self):
        result = self.evidence()
        self.assertTrue(result['world_target_completed'])
        self.assertEqual(result['module_names'], self.modules)
        self.assertEqual(result['module_compilation_counts'], dict.fromkeys(self.modules, 1))
        self.assertEqual(len(result['target_artifacts']['worldserver']['sha256']), 64)
        self.assertFalse(result['execution_qualified'])

    def test_configured_module_without_compilation_is_rejected(self):
        self.targets['modules']['sources'].pop()
        with self.assertRaisesRegex(BuildError, 'omits compiled sources'):
            self.evidence()

    def test_compiled_module_omitted_from_world_configuration_is_rejected(self):
        self.targets['worldserver']['compileGroups'][0]['compileCommandFragments'][0]['fragment'] = (
            '-DAC_MODULES_LIST="first-module,"')
        with self.assertRaisesRegex(BuildError, 'module list differs'):
            self.evidence()

    def test_module_list_in_cmake_defines_is_supported(self):
        group = self.targets['worldserver']['compileGroups'][0]
        group['defines'] = [{'define': 'AC_MODULES_LIST="first-module,second-module,"'}]
        group['compileCommandFragments'] = []
        self.assertEqual(self.evidence()['module_names'], self.modules)

    def test_changed_lua_configuration_is_rejected(self):
        (self.build / 'CMakeCache.txt').write_text('LUA_VERSION:STRING=luajit\nLUA_STATIC:BOOL=ON\n')
        with self.assertRaisesRegex(BuildError, 'does not preserve static Lua 5.2'):
            self.evidence()

    def test_module_configurations_must_match_compiled_list(self):
        conf = self.source / 'modules' / 'first-module' / 'conf'
        conf.mkdir(parents=True)
        (conf / 'original.conf.dist').write_text('Option = 1\n')
        with self.assertRaisesRegex(BuildError, 'configuration list differs'):
            self.evidence()
        self.targets['worldserver']['compileGroups'][0]['compileCommandFragments'].append(
            {'fragment': '-DCONFIG_FILE_LIST="original.conf,"'})
        self.assertEqual(self.evidence()['module_configuration_files'], ['original.conf'])

    def test_completed_archive_outside_actual_world_link_is_rejected(self):
        self.targets['worldserver']['link']['commandFragments'][0]['fragment'] = 'libmodules.a libscripts.a'
        with self.assertRaisesRegex(BuildError, 'does not include required static target lualib'):
            self.evidence()

    def test_changed_cpp_standard_is_rejected(self):
        self.targets['modules']['compileGroups'][0]['languageStandard']['standard'] = '17'
        with self.assertRaisesRegex(BuildError, r'does not compile as C\+\+20'):
            self.evidence()

    def test_stale_model_without_completed_artifact_is_rejected(self):
        (self.build / 'worldserver').unlink()
        with self.assertRaisesRegex(BuildError, 'lacks target artifact worldserver'):
            self.evidence()


if __name__ == '__main__':
    unittest.main()
