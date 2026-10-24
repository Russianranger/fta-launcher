#!/usr/bin/env python3
"""Check synthetic assets with the original C++ loader and production JVM store.

No actual game assets, world startup, Android SAF or foreground qualification.
The original source is used privately from its pinned archive, never vendored.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import zipfile

from build_native import check_jobs
from create_game_data_policy import FILES, derive, cpp_text
from fta_source import SOURCE_ROOT, SOURCE_SHA256, sha256_file

REPO = Path(__file__).resolve().parents[1]


def synthetic_assets(policy):
    p = dict(line.split('=', 1) for line in policy.splitlines())
    assets = {}
    for key, value in p.items():
        if not key.startswith('dbc.'):
            continue
        fields, record, offset, sentinel = map(int, value.split(','))
        body = bytearray(record)
        if sentinel >= 0:
            struct.pack_into('<I', body, offset, sentinel)
        assets['dbc/' + key[4:]] = struct.pack('<4sIIII', b'WDBC', 1, fields, record, 1) + body + b'\0'
    for tile in p['startupTiles'].split(','):
        assets[f'maps/{tile}.map'] = struct.pack('<4sII8I', b'MAPS', 9, 12340, *([0] * 8))
        assets[f'vmaps/{tile[:3]}.vmtree'] = b'VMAP_4.8\1'
        assets[f'vmaps/{tile[:3]}_{tile[5:7]}_{tile[3:5]}.vmtile'] = b'VMAP_4.8'
    assets['mmaps/000.mmap'] = struct.pack('<5f2i', 0, 0, 0, 533, 533, 4096, 32768)
    assets['mmaps/0002828.mmtile'] = struct.pack('<4I', 0x4d4d4150, 7, 20, 8) + bytes(40) + struct.pack('<2I', 0x444e4156, 7)
    return assets


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source-zip', type=Path, required=True)
    p.add_argument('--kotlin-classpath', required=True)
    p.add_argument('--java', default='java')
    p.add_argument('--cache-advice-library', type=Path, help='Run the production cache helper from this host library directory')
    p.add_argument('--cxx', default='g++')
    p.add_argument('--with-stock-backup', action='store_true', help='Exercise the screenshot-observed auxiliary DBC folder')
    p.add_argument('--with-db2-companions', action='store_true', help='Preserve opaque DB2 companions without qualifying them as runtime DBCs')
    p.add_argument('--with-editor-companion', action='store_true', help='Preserve the report-identified editor ZIP without expanding it')
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    if sha256_file(args.source_zip) != SOURCE_SHA256:
        raise ValueError('Original source archive hash mismatch')
    check_jobs(1)
    policy = (REPO / 'manifests/game-data-policy.properties').read_text()
    proof = {'cacheAdviceEnabled': bool(args.cache_advice_library), 'formatVersion': 1, 'scope': 'synthetic-game-data-source-and-store-host-check',
             'sourceArchiveSha256': SOURCE_SHA256,
             'policySha256': hashlib.sha256(policy.encode()).hexdigest(),
             'worldReady': False, 'actualGameAssetsTested': False, 'androidSafQualified': False,
             'nativeFullAssetSemanticsQualified': False, 'compilerJobs': 1, 'cppStandard': 20}
    with tempfile.TemporaryDirectory(prefix='fta-game-data-host-') as temporary:
        root = Path(temporary)
        with zipfile.ZipFile(args.source_zip) as z:
            source = {path: z.read(SOURCE_ROOT + '/' + path) for path in FILES}
            if derive(source) != policy:
                raise ValueError('Committed policy differs from exact original source')
            if args.with_db2_companions:
                scanned = 0
                for info in z.infolist():
                    name = info.filename.removeprefix(SOURCE_ROOT + '/')
                    if name.startswith(('src/server/', 'src/common/', 'modules/')) and name.endswith(('.cpp', '.h', '.hpp', '.c')):
                        scanned += 1
                        if re.search(r'\.db2\b|\bDB2(?:Storage|FileLoader)\b', cpp_text(z.read(info)), re.IGNORECASE):
                            raise ValueError('Pinned server DB2 references require review before companion handling')
                if scanned < 100:
                    raise ValueError('Incomplete original server source inspection')
                proof.update(serverCommonAndModuleSourceFilesScanned=scanned, noDb2LoaderReferencesInPinnedServerSources=True)
            prefixes = ('src/common/', 'deps/fmt/include/', 'deps/g3dlite/include/', 'deps/recastnavigation/Detour/Include/')
            for info in z.infolist():
                name = info.filename.removeprefix(SOURCE_ROOT + '/')
                if info.is_dir() or name == info.filename or not (name.startswith(prefixes) or name in FILES):
                    continue
                relative = Path(name)
                if relative.is_absolute() or '..' in relative.parts:
                    raise ValueError('Unsafe source path')
                target = root / 'source' / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(z.read(info))
        assets = synthetic_assets(policy)
        if args.with_stock_backup:
            # Deliberately differs from the active Spell schema: this is not a runtime fallback.
            assets['dbc/stock_dbc/Spell.dbc'] = struct.pack('<4sIIIII', b'WDBC', 1, 1, 4, 1, 123) + b'\0'
        companions = {'dbc/lightskybox.db2': b'opaque synthetic companion',
                      'dbc/Spell.db2': b'not a runtime DBC', 'dbc/stock_dbc/lightskybox.db2': bytes(range(16))} if args.with_db2_companions else {}
        assets.update(companions)
        editor_path = 'dbc/WDBX - DBC EDITOR.zip'
        editor = None
        if args.with_editor_companion:
            nested = io.BytesIO()
            with zipfile.ZipFile(nested, 'w') as z:
                z.writestr('../outside.exe', b'synthetic; must never be extracted')
                z.writestr('Spell.dbc', b'not a runtime replacement')
            editor = nested.getvalue()
            assets[editor_path] = editor
        for name, data in assets.items():
            path = root / 'assets' / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        # Different widths are exercised with nonzero field values, not only header equality.
        outfit = root / 'assets/dbc/CharStartOutfit.dbc'
        data = bytearray(outfit.read_bytes())
        data[24] = 17
        struct.pack_into('<I', data, 28, 0x12345678)
        outfit.write_bytes(data)
        bad = root / 'bad.dbc'
        bad.write_bytes(b'NOPE' + data[4:])
        stores = dict(re.findall(r'DBCStorage\s*<[^>]+>\s+(\w+)\((\w+)\)', cpp_text(source[FILES[0]])))
        loads = re.findall(r'LOAD_DBC\((\w+),\s*"([^"]+)"', cpp_text(source[FILES[0]]))
        if len(loads) != 113 or not all(name.endswith('.dbc') for _, name in loads):
            raise ValueError('Original runtime DBC inventory changed')
        properties = dict(line.split('=', 1) for line in policy.splitlines())
        cases = []
        for store, name in loads:
            fields, record, offset, sentinel = properties['dbc.' + name].split(',')
            cases.append(f'{{"{name}", {stores[store]}, {fields}, {record}, {sentinel}}}')
        harness = r'''
#include "DBCFileLoader.h"
#include "DBCfmt.h"
#include "Optional.h"
#include "GridTerrainData.h"
#include "MapDefines.h"
#include "VMapDefinitions.h"
#include <cstring>
#include <iostream>
#include <stdexcept>
// Keep original assertions enabled. Only their fatal logging sink is adapted.
std::string GetDebugInfo() { return {}; }
namespace Acore {
[[noreturn]] void Assert(std::string_view, uint32, std::string_view, std::string_view, std::string_view, std::string_view) { std::abort(); }
}
struct Case { const char* name; const char* fmt; unsigned fields, bytes; long sentinel; };
int main(int argc, char** argv) {
    if (argc != 3) return 2;
    auto require = [](bool ok) { if (!ok) throw std::runtime_error("original source check failed"); };
    require(MapVersionMagic == 9 && sizeof(map_fileheader) == 44);
    require(std::string(VMAP::VMAP_MAGIC) == "VMAP_4.8");
    require(MMAP_VERSION == 20 && MMAP_MAGIC == 0x4d4d4150 && sizeof(MmapTileHeader) == 56);
    require(DT_NAVMESH_VERSION == 7 && DT_NAVMESH_MAGIC == 0x444e4156 && sizeof(dtNavMeshParams) == 28);
    Case cases[] = {CASES};
    unsigned sentinels = 0;
    for (auto c : cases) {
        DBCFileLoader loader;
        require(loader.Load((std::string(argv[1]) + "/dbc/" + c.name).c_str(), c.fmt));
        require(loader.GetNumRows() == 1 && loader.GetCols() == c.fields && loader.GetRowSize() == c.bytes);
        unsigned offset = 0;
        for (unsigned i = 0; i < c.fields; ++i) {
            require(loader.GetOffset(i) == offset);
            if (c.sentinel >= 0 && (c.fmt[i] == 'n' || c.fmt[i] == 'd')) {
                require(loader.getRecord(0).getUInt(i) == static_cast<unsigned>(c.sentinel)); ++sentinels;
            }
            offset += (c.fmt[i] == 'b' || c.fmt[i] == 'X') ? 1 : 4;
        }
        require(offset == c.bytes);
        if (std::string(c.name) == "CharStartOutfit.dbc") {
            require(loader.getRecord(0).getUInt8(1) == 17);
            require(loader.getRecord(0).getUInt(5) == 0x12345678);
        }
    }
    require(sentinels == 7);
    DBCFileLoader invalid;
    require(!invalid.Load(argv[2], CharStartOutfitEntryfmt));
    std::cout << "Original loader: 113 synthetic DBCs, 7 sentinels, byte offsets and source header sizes passed\n";
}
'''.replace('CASES', ',\n'.join(cases))
        cpp = root / 'source-check.cpp'
        cpp.write_text(harness)
        includes = ['src/common', 'src/common/Compiler', 'src/common/Debugging', 'src/common/Utilities',
                    'src/common/DataStores', 'src/common/Collision', 'src/common/Collision/Maps',
                    'src/server/shared/DataStores', 'src/server/game/Grids', 'deps/fmt/include',
                    'deps/g3dlite/include', 'deps/recastnavigation/Detour/Include']
        command = [args.cxx, '-std=c++20', '-O1', '-DFMT_HEADER_ONLY']
        command += ['-I' + str(root / 'source' / path) for path in includes]
        command += [str(cpp), str(root / 'source/src/common/DataStores/DBCFileLoader.cpp'), '-o', str(root / 'source-check')]
        subprocess.run(command, check=True)
        subprocess.run([str(root / 'source-check'), str(root / 'assets'), str(bad)], check=True)
        proof.update(originalDbcLoaderUnmodified=True, originalAssertionsEnabled=True,
                     syntheticDbcsLoadedByOriginalCpp=113, sourceVersionSentinelsChecked=7,
                     byteFieldOffsetsPassed=True, originalFormatHeaderSizesPassed=True)
        archive = root / 'synthetic.zip'
        with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as z:
            for name in assets:
                with z.open(name, 'w', force_zip64=True) as entry:
                    entry.write((root / 'assets' / name).read_bytes())
        classes = root / 'classes'
        classes.mkdir()
        subprocess.run([args.java, '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-cp', args.kotlin_classpath,
                        '-d', str(classes), str(REPO / 'tests/fixtures/GameDataHost.java')], check=True)
        store = root / 'store'
        command = [args.java] + (['-Dfta.cacheAdvice=true', '-Djava.library.path=' + str(args.cache_advice_library.resolve())] if args.cache_advice_library else []) + ['-cp', str(classes) + ':' + args.kotlin_classpath, 'GameDataHost']
        def run(mode, code=0):
            result = subprocess.run(command + [mode, str(store), str(REPO / 'manifests/game-data-policy.properties'), str(archive), str(len(companions)), str(int(editor is not None))])
            if result.returncode != code:
                raise ValueError(f'Host {mode} exited {result.returncode}, expected {code}')
        run('import')
        before = (store / 'active').read_bytes()
        run('crash', 73)
        if (store / 'active').read_bytes() != before:
            raise ValueError('Abrupt interrupted replacement changed the active candidate')
        run('verify')
        run('import')
        run('verify')
        if any(path.name.startswith('.partial-') for path in store.iterdir()):
            raise ValueError('Subsequent import failed to clean interrupted staging')
        proof.update(productionJvmImportPassed=True, separateJvmReverifyPassed=True,
                     abruptProcessDeathPreservedActiveCandidate=True, orphanStagingRecovered=True,
                     separateJvmProcesses=5, syntheticAssets=len(assets))
        if args.with_stock_backup:
            current = store / (store / 'active').read_text()
            if (current / 'dbc/stock_dbc/Spell.dbc').read_bytes() != assets['dbc/stock_dbc/Spell.dbc']:
                raise ValueError('Auxiliary stock DBC bytes were not preserved')
            if (current / 'dbc/Spell.dbc').read_bytes() != assets['dbc/Spell.dbc']:
                raise ValueError('Auxiliary stock DBC replaced the active runtime file')
            proof.update(stockBackupBytesPreserved=True, stockBackupNeverSubstitutedForRuntime=True)
        if companions:
            current = store / (store / 'active').read_text()
            if any((current / name).read_bytes() != data for name, data in companions.items()):
                raise ValueError('Opaque DB2 companion bytes were not preserved')
            if (current / 'dbc/Spell.dbc').read_bytes() != assets['dbc/Spell.dbc']:
                raise ValueError('DB2 companion replaced a required runtime DBC')
            proof.update(db2CompanionBytesPreserved=True, db2CompanionCount=len(companions),
                         db2CompanionsNeverSubstitutedForRuntime=True, db2FormatsInterpreted=False)
        if editor is not None:
            current = store / (store / 'active').read_text()
            saved = current / editor_path
            if saved.read_bytes() != editor or saved.stat().st_mode & 0o111:
                raise ValueError('Editor archive bytes or non-executable mode were not preserved')
            if (current / 'dbc/Spell.dbc').read_bytes() != assets['dbc/Spell.dbc'] or list(root.rglob('outside.exe')):
                raise ValueError('Nested editor archive contents escaped or replaced runtime files')
            proof.update(editorArchiveBytesPreserved=True, editorArchiveNonExecutable=True,
                         editorArchiveNeverExpanded=True, editorArchiveNeverSubstitutedForRuntime=True,
                         originalRequiredDbcNamesUnchanged=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(proof, indent=2) + '\n')
    print('Original-source and production-store host checks passed; no real game data qualified')


if __name__ == '__main__':
    main()
