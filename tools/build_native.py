#!/usr/bin/env python3
"""Bounded, auth-first native build driver; source and database are never started."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import platform
import re
import shlex
import shutil
import subprocess
import sys

from fta_source import SourceError, verify_source_tree, sha256_file


class BuildError(ValueError):
    pass


def cgroup_available(limit: int, usage: int, statistics: dict[str, int]) -> int:
    # Both file-cache LRUs can be reclaimed. Count clean inactive pages plus
    # only one quarter of clean active pages, preserving most hot file cache.
    # Dirty/writeback pages and reclaimable slab are never counted here.
    inactive = statistics.get('inactive_file', statistics.get('total_inactive_file', 0))
    active = statistics.get('active_file', statistics.get('total_active_file', 0))
    dirty = statistics.get('file_dirty', statistics.get('dirty', 0))
    writeback = statistics.get('file_writeback', statistics.get('writeback', 0))
    reclaimable = max(0, inactive - dirty - writeback) + max(0, active - dirty - writeback) // 4
    return max(0, limit - usage + min(usage, reclaimable))


def memory_limit_bytes() -> int | None:
    """Use the smaller cgroup/host availability; do not trust CPU count for jobs."""
    limits = []
    try:
        for line in Path('/proc/meminfo').read_text().splitlines():
            if line.startswith('MemAvailable:'):
                limits.append(int(line.split()[1]) * 1024)
    except OSError:
        pass
    for limit_name, usage_name in (
            ('/sys/fs/cgroup/memory.max', '/sys/fs/cgroup/memory.current'),
            ('/sys/fs/cgroup/memory/memory.limit_in_bytes', '/sys/fs/cgroup/memory/memory.usage_in_bytes')):
        try:
            value = Path(limit_name).read_text().strip()
            if value.isdigit():
                usage = int(Path(usage_name).read_text().strip())
                try:
                    statistics = {parts[0]: int(parts[1]) for line in
                                  (Path(limit_name).parent / 'memory.stat').read_text().splitlines()
                                  if len(parts := line.split()) == 2}
                except (OSError, ValueError):
                    statistics = {}
                limits.append(cgroup_available(int(value), usage, statistics))
        except OSError:
            pass
    return min(limits) if limits else None


def check_jobs(jobs: int, available: int | None = None) -> None:
    if jobs not in (1, 2):
        raise BuildError('Build parallelism must be one or two jobs')
    available = memory_limit_bytes() if available is None else available
    if available is not None and available < (jobs * 1536 + 512) * 1024 * 1024:
        raise BuildError('Insufficient memory for requested compiler parallelism; reduce jobs or use a larger runner')


def read_cache(path: Path) -> dict[str, str]:
    values = {}
    if path.exists():
        for line in path.read_text(errors='replace').splitlines():
            if line and not line.startswith(('#', '//')) and ':' in line and '=' in line:
                key, value = line.split('=', 1)
                values[key.split(':', 1)[0]] = value
    return values


def check_cache(cache: dict[str, str], target_platform: str, *, configured: bool) -> None:
    if target_platform == 'android' and cache.get('MYSQL_CONFIG'):
        raise BuildError('Android cache enables a host mysql_config probe; use a clean build directory')
    for key, value in cache.items():
        if key == 'DISABLED_AC_MODULES' and value:
            raise BuildError('CMake cache disables required modules')
        if (key.startswith(('MODULE_', 'SCRIPTS_')) and not key.endswith(('-STRINGS', '-ADVANCED'))
                and value not in ('static', 'default')):
            raise BuildError('CMake cache changes required content linkage or disables it')
    if cache.get('LUA_VERSION', 'lua52') != 'lua52' or cache.get('LUA_STATIC', 'ON') != 'ON':
        raise BuildError('CMake cache changes the required static Lua 5.2 module profile')
    if configured:
        for key, expected in {'SCRIPTS':'static', 'MODULES':'static', 'CMAKE_BUILD_TYPE':'Release',
                              'CMAKE_CXX_STANDARD':'20'}.items():
            if cache.get(key) != expected:
                raise BuildError(f'Configured {key} does not preserve the required build profile')
        if target_platform == 'android' and cache.get('CMAKE_ANDROID_ARCH_ABI', cache.get('ANDROID_ABI')) != 'arm64-v8a':
            raise BuildError('Android build did not configure ARM64 ABI')
        if target_platform == 'android' and cache.get('CONF_DIR') != 'configs':
            raise BuildError('Android build does not use supervised relative module configuration path')
        if (target_platform == 'android' and cache.get('APPS_BUILD') == 'all'
                and cache.get('CMAKE_EXE_LINKER_FLAGS') != '-Wl,--strip-debug'):
            raise BuildError('Android world build does not use debug-section-only linker stripping')


def world_build_evidence(build: Path, source: Path, module_names: list[str]) -> dict:
    """Require CMake's actual world link graph, rather than infer it from options."""
    cache = read_cache(build / 'CMakeCache.txt')
    if cache.get('LUA_VERSION') != 'lua52' or cache.get('LUA_STATIC') != 'ON':
        raise BuildError('World evidence does not preserve static Lua 5.2')
    reply = build / '.cmake/api/v1/reply'
    indexes = sorted(reply.glob('index-*.json'), key=lambda path: path.stat().st_mtime_ns)
    if not indexes:
        raise BuildError('World build lacks CMake file API evidence')
    index = json.loads(indexes[-1].read_text())
    model_ref = index.get('reply', {}).get('codemodel-v2', {})
    if 'jsonFile' not in model_ref:
        raise BuildError('World build lacks a CMake codemodel reply')
    model = json.loads((reply / model_ref['jsonFile']).read_text())
    configurations = model.get('configurations', [])
    if len(configurations) != 1:
        raise BuildError('World evidence requires one configured build graph')
    targets = {}
    for entry in configurations[0]['targets']:
        target = json.loads((reply / entry['jsonFile']).read_text())
        targets[target['name']] = target
    required = {'worldserver': 'EXECUTABLE', 'modules': 'STATIC_LIBRARY',
                'scripts': 'STATIC_LIBRARY', 'lualib': 'STATIC_LIBRARY'}
    artifacts = {}
    for name, kind in required.items():
        target = targets.get(name, {})
        paths = target.get('artifacts', [])
        if target.get('type') != kind or len(paths) != 1:
            raise BuildError(f'World evidence lacks required {kind} target {name}')
        path = Path(paths[0]['path'])
        path = path if path.is_absolute() else build / path
        if not path.is_file():
            raise BuildError(f'Completed world build lacks target artifact {name}')
        artifacts[name] = {'path': str(path.resolve()), 'type': kind,
                           'size': path.stat().st_size, 'sha256': sha256_file(path)}
    world = targets['worldserver']
    fragments = world.get('link', {}).get('commandFragments', [])
    link_tokens = [token for part in fragments for token in shlex.split(part['fragment'])]
    linked_paths = {(Path(token) if Path(token).is_absolute() else build / token).resolve()
                    for token in link_tokens if not token.startswith('-')}
    for name in ('modules', 'scripts', 'lualib'):
        if Path(artifacts[name]['path']) not in linked_paths:
            raise BuildError(f'World link does not include required static target {name}')
    compile_text = ' '.join(part['fragment'] for group in world.get('compileGroups', [])
                            for part in group.get('compileCommandFragments', []))
    compile_text += ' ' + ' '.join(entry['define'] for group in world.get('compileGroups', [])
                                   for entry in group.get('defines', []))
    def comma_definition(name: str) -> list[str]:
        match = re.search(r'(?:^|\s)(?:-D)?' + re.escape(name) + r'=([^\s]+)', compile_text)
        return sorted(filter(None, match.group(1).replace('\\', '').replace('"', '').split(','))) if match else []

    configured_modules = comma_definition('AC_MODULES_LIST')
    if configured_modules != sorted(module_names):
        raise BuildError('Actual world compilation module list differs from the verified source')
    configuration_files = comma_definition('CONFIG_FILE_LIST')
    source_configurations = sorted(path.name.removesuffix('.dist')
                                   for name in module_names
                                   for path in (source / 'modules' / name / 'conf').glob('*.conf.dist'))
    if configuration_files != source_configurations:
        raise BuildError('Actual world module configuration list differs from the verified source')
    if cache.get('CMAKE_SYSTEM_NAME') == 'Android' and cache.get('CONF_DIR') != 'configs':
        raise BuildError('World evidence does not use supervised relative module configuration path')
    module_counts = dict.fromkeys(module_names, 0)
    for entry in targets['modules'].get('sources', []):
        if 'compileGroupIndex' not in entry:
            continue
        path = Path(entry['path'])
        path = path if path.is_absolute() else source / path
        try:
            relative = path.resolve().relative_to((source / 'modules').resolve())
        except ValueError:
            continue
        if relative.parts[0] in module_counts:
            module_counts[relative.parts[0]] += 1
    if not all(module_counts.values()):
        raise BuildError('World module archive omits compiled sources from a required module')
    for name in ('worldserver', 'modules', 'scripts'):
        groups = [group for group in targets[name].get('compileGroups', []) if group['language'] == 'CXX']
        if not groups or any(group.get('languageStandard', {}).get('standard') != '20' for group in groups):
            raise BuildError(f'World target {name} does not compile as C++20')
    dependencies = {}
    debug_sections = 'unchanged'
    if cache.get('CMAKE_SYSTEM_NAME') == 'Android':
        linker_options = []
        for token in link_tokens:
            if token.startswith('-Wl,'):
                linker_options.extend(token[4:].split(','))
            else:
                linker_options.append(token.removeprefix('-Xlinker='))
        if ('--strip-debug' not in linker_options
                or any(token in ('-s', '--strip-all') for token in linker_options)):
            raise BuildError('Android world link does not preserve the debug-section-only stripping policy')
        debug_sections = 'linker-strip-debug'
        path = Path(cache.get('ANDROID_NCURSES_LIBRARY', ''))
        if not path.is_file() or path.resolve() not in linked_paths:
            raise BuildError('World link lacks its resolved Android ncurses library')
        dependencies['ncurses'] = {'path': str(path.resolve()), 'size': path.stat().st_size,
                                   'sha256': sha256_file(path)}
    return {'schema_version': 1, 'world_target_completed': True,
            'module_names': configured_modules, 'module_compilation_counts': module_counts,
            'module_configuration_files': configuration_files, 'configuration_directory': cache.get('CONF_DIR'),
            'static_modules': True, 'static_scripts': True, 'lua_version': cache['LUA_VERSION'],
            'lua_static': cache['LUA_STATIC'] == 'ON', 'cxx_standard': 20,
            'linked_static_targets': ['modules', 'scripts', 'lualib'],
            'debug_section_policy': debug_sections,
            'target_artifacts': artifacts, 'target_dependency_artifacts': dependencies,
            'execution_qualified': False}


def commands(args: argparse.Namespace) -> list[list[str]]:
    source = args.source.resolve()
    build = args.build_dir.resolve()
    if source == build or source in build.parents:
        raise BuildError('Build output must be outside the source tree')
    configure = [args.cmake, '-S', str(source), '-B', str(build), '-G', 'Ninja',
                 '-DCMAKE_BUILD_TYPE=Release', '-DCMAKE_CXX_STANDARD=20', '-DCMAKE_CXX_STANDARD_REQUIRED=ON',
                 '-DWITHOUT_GIT=ON', '-DSCRIPTS=static', '-DMODULES=static', '-DTOOLS_BUILD=none',
                 '-DBUILD_TESTING=OFF', '-DNOPCH=ON', '-DDISABLED_AC_MODULES=',
                 '-DAPPS_BUILD=' + ('auth-only' if args.stage == 'auth' else 'all'),
                 '-DCMAKE_INSTALL_PREFIX=' + str((build / 'install').resolve()),
                 f'-DCMAKE_JOB_POOLS=compile_pool={args.jobs};link_pool=1',
                 '-DCMAKE_JOB_POOL_COMPILE=compile_pool', '-DCMAKE_JOB_POOL_LINK=link_pool']
    if args.stage == 'world':
        # Keep the supplied module default explicit; LuaJIT invokes an unqualified
        # host-make path and changing Lua versions changes module semantics.
        configure += ['-DLUA_VERSION=lua52', '-DLUA_STATIC=ON']
    # Ninja still caps the entire graph by --parallel; separate pools keep links serial.
    if args.platform == 'android':
        if not args.toolchain or not args.toolchain.is_file():
            raise BuildError('Android requires an explicit existing CMake toolchain file')
        if platform.machine().lower() not in ('aarch64', 'arm64') and not args.mysql_library:
            raise BuildError('Cross Android build requires target MySQL/MariaDB library and headers')
        configure += ['-DCMAKE_TOOLCHAIN_FILE=' + str(args.toolchain.resolve()),
                      '-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=android-33',
                      '-DANDROID_STL=c++_static', '-DBoost_USE_STATIC_LIBS=ON',
                      '-DBoost_USE_STATIC_RUNTIME=ON',
                      '-DCONF_DIR:STRING=configs',
                      '-DCMAKE_SYSTEM_NAME=Android', '-DCMAKE_SYSTEM_PROCESSOR=aarch64',
                      '-DMYSQL_CONFIG:FILEPATH=']
        if args.dependency_prefix:
            # NDK package searches are rooted; explicitly admit only this target prefix.
            configure.append('-DCMAKE_FIND_ROOT_PATH=' + str(args.dependency_prefix.resolve()))
        if args.stage == 'world':
            # Keep code and the symbol table while bounding the packaged executable size.
            configure.append('-DCMAKE_EXE_LINKER_FLAGS:STRING=-Wl,--strip-debug')
    elif args.toolchain:
        raise BuildError('Use --platform android for a cross toolchain; host results are not Android qualification')
    for field, variable in [('dependency_prefix','CMAKE_PREFIX_PATH'), ('mysql_library','MYSQL_LIBRARY'),
                            ('mysql_include','MYSQL_INCLUDE_DIR'), ('mysql_executable','MYSQL_EXECUTABLE'),
                            ('c_compiler','CMAKE_C_COMPILER'), ('cxx_compiler','CMAKE_CXX_COMPILER')]:
        value = getattr(args, field, None)
        if value:
            configure.append(f'-D{variable}={Path(value).resolve()}')
    if bool(args.mysql_library) != bool(args.mysql_include):
        raise BuildError('Provide both target database headers and library')
    result = [configure]
    if not args.configure_only:
        result.append([args.cmake, '--build', str(build), '--target', 'authserver', '--parallel', str(args.jobs)])
        if args.stage == 'world':
            result.append([args.cmake, '--build', str(build), '--target', 'worldserver', '--parallel', str(args.jobs)])
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--build-dir', type=Path, required=True)
    parser.add_argument('--platform', choices=('host', 'android'), required=True)
    parser.add_argument('--stage', choices=('auth', 'world'), default='auth')
    parser.add_argument('--port-profile', help='Exact reviewed profile from native-port/profiles.json')
    parser.add_argument('--jobs', type=int, default=1)
    parser.add_argument('--cmake', default='cmake')
    parser.add_argument('--toolchain', type=Path)
    parser.add_argument('--dependency-prefix', type=Path)
    parser.add_argument('--mysql-library', type=Path)
    parser.add_argument('--mysql-include', type=Path)
    parser.add_argument('--mysql-executable', type=Path)
    parser.add_argument('--c-compiler', type=Path)
    parser.add_argument('--cxx-compiler', type=Path)
    parser.add_argument('--configure-only', action='store_true')
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()
    report = {'schema_version':1, 'platform':args.platform, 'stage':args.stage,
              'android_server_qualified':False, 'source_preserved':False, 'status':'blocked'}
    try:
        inventory = verify_source_tree(args.source, port_profile=args.port_profile)
        report['source_preserved'] = True
        report['source_tree_sha256'] = inventory['tree_sha256']
        report['required_module_count'] = len(inventory['modules'])
        report['port_profile'] = args.port_profile
        check_jobs(args.jobs)
        plan = commands(args)
        report['commands'] = plan
        check_cache(read_cache(args.build_dir / 'CMakeCache.txt'), args.platform, configured=False)
        if args.dry_run:
            report['status'] = 'plan-only'
            print(json.dumps(report, indent=2))
            return 0
        missing = [name for name in (args.cmake, 'ninja') if not shutil.which(name)]
        if missing:
            raise BuildError('Missing build tools: ' + ', '.join(missing))
        args.build_dir.mkdir(parents=True, exist_ok=True)
        if args.stage == 'world':
            query = args.build_dir / '.cmake/api/v1/query/codemodel-v2'
            query.parent.mkdir(parents=True, exist_ok=True)
            query.touch()
        env = dict(os.environ, CMAKE_BUILD_PARALLEL_LEVEL=str(args.jobs))
        for index, command in enumerate(plan):
            with (args.build_dir / f'fta-step-{index + 1}.log').open('w') as log:
                process = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, env=env, check=False)
            if process.returncode:
                raise BuildError(f'Build step {index + 1} failed with code {process.returncode}; inspect its private log')
            if index == 0:
                check_cache(read_cache(args.build_dir / 'CMakeCache.txt'), args.platform, configured=True)
        verify_source_tree(args.source, port_profile=args.port_profile)
        cache = read_cache(args.build_dir / 'CMakeCache.txt')
        report['configuration'] = {key: cache[key] for key in (
            'CMAKE_BUILD_TYPE', 'CMAKE_CXX_STANDARD', 'SCRIPTS', 'MODULES',
            'CMAKE_CXX_COMPILER', 'ANDROID_ABI', 'CMAKE_ANDROID_ARCH_ABI',
            'ANDROID_PLATFORM', 'ANDROID_STL', 'CMAKE_ANDROID_STL_TYPE',
            'CMAKE_SYSTEM_NAME', 'APPS_BUILD', 'LUA_VERSION', 'LUA_STATIC', 'CONF_DIR',
            'ANDROID_NCURSES_LIBRARY', 'CMAKE_EXE_LINKER_FLAGS') if key in cache}
        if args.platform == 'android':
            report['target_abi'] = cache.get('CMAKE_ANDROID_ARCH_ABI', cache.get('ANDROID_ABI'))
        if not args.configure_only:
            report['artifacts'] = {}
            for name in (['authserver'] if args.stage == 'auth' else ['authserver', 'worldserver']):
                artifact = args.build_dir.resolve() / 'src/server/apps' / name
                report['artifacts'][name] = {'path': str(artifact), 'size': artifact.stat().st_size,
                                            'sha256': sha256_file(artifact)}
            if args.stage == 'world':
                report['compiled_world_evidence'] = world_build_evidence(
                    args.build_dir.resolve(), args.source.resolve(), inventory['modules'])
        # Compilation evidence alone does not qualify Android execution, DB, game data or Thor behavior.
        report['status'] = 'configure-passed' if args.configure_only else 'build-passed'
    except (BuildError, SourceError, OSError, ValueError) as error:
        report['blocker'] = str(error)
        print(json.dumps(report, indent=2))
        return 1
    print(json.dumps(report, indent=2))
    (args.build_dir / 'fta-build-report.json').write_text(json.dumps(report, indent=2) + '\n')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
