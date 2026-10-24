#!/usr/bin/env python3
"""Qualify an additive world ELF without changing retained realm/updater identities.

Compilation and source evidence only. Never execute a server or qualify Thor.
"""
import argparse
import json
from pathlib import Path

from fta_source import verify_source_tree
from stage_android_provider import digest, inspect_elf
from stage_world_runtime import verify_build_report, verified_file

ROOT = Path(__file__).resolve().parents[1]
NAME = 'libfta_world_session.so'
PROFILE = 'android-arm64-world-v14'
BASE_PROFILE = 'android-arm64-world-v13'
BASE_MANIFEST = 'dda46e9555ad3b58acdbb0125fccdc89850de78a56159ee2bfcbfbde8f529c8b'
BASE_WORLD = '97e578f7e3013715c2d9254dc29dd87d4a33598583093110eaad24157ee67766'
UPDATER = '466c1c6673d7f939b14c4b6f4dc787efa58ac6c8fa91e982c76151a3f1f8c02f'
MANAGER = 'src/server/game/Scripting/ScriptMgr.cpp'


def require(ok, message):
    if not ok:
        raise ValueError(message)


def profile_delta(profiles):
    base, current = (profiles['profiles'][name] for name in (BASE_PROFILE, PROFILE))
    require(current['patches'][:-1] == base['patches'], 'Baseline patches differ')
    patch = current['patches'][-1]
    require(list(patch['files']) == [MANAGER], 'World session repair changes another source file')
    require(current['result_inventory']['modules'] == base['result_inventory']['modules'] and
            len(current['result_inventory']['modules']) == 52, 'Original module inventory differs')
    return patch


def verify_module(module, pin, root=ROOT):
    require(pin['moduleName'] == NAME and pin['sourceProfile'] == PROFILE and
            pin['baselineManifestSha256'] == BASE_MANIFEST and pin['baselineWorldSha256'] == BASE_WORLD and
            pin['compiledUpdaterLockSha256'] == UPDATER, 'World session baseline identity differs')
    profiles = json.loads((root / 'native-port/profiles.json').read_text())
    patch = profile_delta(profiles)
    require(pin['sourceTreeSha256'] == profiles['profiles'][PROFILE]['result_inventory']['tree_sha256'] and
            pin['patchSha256'] == patch['sha256'], 'World session source identity differs')
    verified_file(module, {'size': pin['moduleBytes'], 'sha256': pin['moduleSha256']})
    require(inspect_elf(module, reject_search_paths=True) == pin['elf'], 'World session ELF metadata differs')
    require(pin['elf']['interpreter'] == '/system/bin/linker64' and
            pin['elf']['minimumLoadAlignment'] >= 16384 and not pin['elf']['sonames'], 'World session executable ABI differs')
    return pin


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('source', 'baseline-source', 'build-directory', 'provider-stage', 'output', 'evidence'):
        p.add_argument('--' + name, type=Path, required=True)
    args = p.parse_args()
    require(not args.output.exists() and not args.evidence.exists(), 'Do not overwrite measured evidence')
    profiles = json.loads((ROOT / 'native-port/profiles.json').read_text())
    patch = profile_delta(profiles)
    verify_source_tree(args.baseline_source, port_profile=BASE_PROFILE)
    verify_source_tree(args.source, port_profile=PROFILE)
    # Both trees were verified against their complete reviewed inventories. Prove
    # every actual file, including SQL and configs, unchanged except the one patch.
    def files(root):
        return {f.relative_to(root).as_posix(): digest(f) for f in root.rglob('*') if f.is_file()}
    before, after = files(args.baseline_source), files(args.source)
    require(before.keys() == after.keys(), 'World source file inventory changed')
    require([key for key in before if before[key] != after[key]] == [MANAGER], 'Unexpected source changes')
    build = args.build_directory.resolve()
    report = json.loads((build / 'fta-build-report.json').read_text())
    require(report['port_profile'] == PROFILE, 'World build has another source profile')
    jobs = int(report['commands'][-1][-1])
    require(jobs in (1, 2), 'Native build concurrency exceeds the reviewed limit')
    world = build / 'src/server/apps/worldserver'
    proof, _ = verify_build_report(report, world, build / 'src/server/apps/authserver', args.source.resolve(), build)
    manifest_path = args.provider_stage / 'assets/fta-runtime-manifest.json'
    require(digest(manifest_path) == BASE_MANIFEST, 'Retained runtime manifest differs')
    manifest = json.loads(manifest_path.read_text())
    baseline = next(e for e in manifest['files'] if e['role'] == 'world-server')
    require(baseline['sha256'] == BASE_WORLD and digest(ROOT / 'manifests/world-updater-lock.json') == UPDATER,
            'Retained world/updater differs')
    # The dynamic dependency closure must still be exactly the pinned baseline.
    elf = inspect_elf(world, reject_search_paths=True)
    old = args.provider_stage / 'jniLibs/arm64-v8a' / Path(baseline['path']).name
    verified_file(old, baseline)
    require(elf == inspect_elf(old, reject_search_paths=True), 'World dynamic dependency closure changed')
    for entry in manifest['files']:
        path = (args.provider_stage / 'jniLibs/arm64-v8a' / Path(entry['path']).name if entry['path'].startswith('native/')
                else args.provider_stage / 'assets/runtime' / entry['path'])
        verified_file(path, entry)
    pin = dict(moduleName=NAME, moduleBytes=world.stat().st_size, moduleSha256=digest(world), elf=elf,
               sourceProfile=PROFILE, sourceTreeSha256=report['source_tree_sha256'], patchSha256=patch['sha256'],
               baselineManifestSha256=BASE_MANIFEST, baselineWorldSha256=BASE_WORLD, compiledUpdaterLockSha256=UPDATER)
    verify_module(world, pin)
    args.output.write_text(json.dumps(pin, indent=2) + '\n')
    args.evidence.write_text(json.dumps(dict(status='compiled-world-session-qualified', **pin,
        requiredModuleCount=52, cxxStandard=20, staticModules=True, staticScripts=True, luaVersion='lua52', luaStatic=True,
        compilerJobs=jobs, compilerSha256=digest(Path(report['configuration']['CMAKE_CXX_COMPILER']).resolve()),
        moduleCompilationCounts=proof['compiledWorldEvidence']['module_compilation_counts'],
        onlyChangedSourceFile=MANAGER, sqlAndConfigurationsByteIdentical=True, baselineRuntimeFilesVerified=len(manifest['files']),
        baselineRuntimeManifestPreserved=True, runtimeQualified=False, databaseFallbackValidated=False, worldReady=False), indent=2) + '\n')
    print(json.dumps({'worldserverSha256': pin['moduleSha256'], 'bytes': pin['moduleBytes'], 'modules': 52}))


if __name__ == '__main__':
    main()
