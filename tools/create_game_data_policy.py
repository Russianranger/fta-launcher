#!/usr/bin/env python3
"""Derive read-only asset preflight metadata from the exact original source ZIP.

This is a format/inventory check, never evidence of world startup or gameplay.
No game assets or database rows are consumed or emitted.
"""
import argparse
import hashlib
from pathlib import Path
import re
import struct
import zipfile

from fta_source import SOURCE_SHA256, SOURCE_ROOT, sha256_file

FILES = (
    'src/server/game/DataStores/DBCStores.cpp', 'src/server/shared/DataStores/DBCfmt.h',
    'src/common/DataStores/DBCFileLoader.cpp', 'src/server/game/Grids/GridTerrainData.h',
    'src/server/game/Grids/GridTerrainLoader.cpp', 'src/server/game/Grids/GridDefines.h',
    'src/server/game/Maps/AreaDefines.h', 'src/server/game/World/World.cpp',
    'src/common/Collision/VMapDefinitions.h', 'src/common/Collision/Maps/MapTree.cpp',
    'src/common/Collision/Maps/MapDefines.h', 'src/common/Collision/Management/MMapMgr.cpp',
    'deps/recastnavigation/Detour/Include/DetourNavMesh.h',
)


def cpp_text(data):
    """Remove C++ comments while retaining string/character literals used by metadata."""
    pattern = r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/'
    return re.sub(pattern, lambda m: ' ' if m[0].startswith(('//', '/*')) else m[0], data.decode())


def derive(source):
    stores_text = cpp_text(source[FILES[0]])
    stores = dict(re.findall(r'DBCStorage\s*<[^>]+>\s+(\w+)\((\w+)\)', stores_text))
    formats = dict(re.findall(r'char constexpr (\w+)\[\]\s*=\s*"([A-Za-z]+)"', cpp_text(source[FILES[1]])))
    loads = re.findall(r'LOAD_DBC\((\w+),\s*"([^"]+)"', stores_text)
    assert len(loads) == len(set(name for _, name in loads)) == 113
    sentinels = dict(re.findall(r'!(\w+)\.LookupEntry\((\d+)\)', stores_text))
    assert len(sentinels) == 7
    result = {'formatVersion': '1', 'sourceArchiveSha256': SOURCE_SHA256, 'dbcCount': str(len(loads))}
    for store, name in loads:
        fmt = formats[stores[store]]
        assert set(fmt) <= set('nidxXbfs')
        sizes = [1 if c in 'bX' else 4 for c in fmt]
        id_column = next((i for i, c in enumerate(fmt) if c in 'nd'), None)
        index_offset = sum(sizes[:id_column]) if id_column is not None else -1
        if store in sentinels: assert index_offset >= 0
        result['dbc.' + name] = ','.join(map(str, (len(fmt), sum(sizes), index_offset, int(sentinels.get(store, '-1')))))
    result['mapVersion'] = re.search(r'MapVersionMagic\s*=\s*(\d+)', cpp_text(source[FILES[3]]))[1]
    result['vmapMagic'] = re.search(r'VMAP_MAGIC\[\]\s*=\s*"([^"]+)"', cpp_text(source[FILES[8]]))[1]
    result['mmapVersion'] = re.search(r'#define MMAP_VERSION (\d+)', cpp_text(source[FILES[10]]))[1]
    result['detourVersion'] = re.search(r'DT_NAVMESH_VERSION\s*=\s*(\d+)', cpp_text(source[FILES[12]]))[1]
    assert (result['mapVersion'], result['vmapMagic'], result['mmapVersion'], result['detourVersion']) == ('9', 'VMAP_4.8', '20', '7')
    map_ids = dict(re.findall(r'(MAP_[A-Z_]+)\s*=\s*(\d+)', cpp_text(source[FILES[6]])))
    coordinates = re.findall(r'MapMgr::ExistMapAndVMap\((MAP_[A-Z_]+),\s*(-?[\d.]+)f,\s*(-?[\d.]+)f\)', cpp_text(source[FILES[7]]))
    assert len(coordinates) == 8
    def f32(v): return struct.unpack('<f', struct.pack('<f', float(v)))[0]
    tiles = []
    for name, x, y in coordinates:
        gx, gy = [int(f32(32 - f32(f32(v) / f32(533.3333)))) for v in (x, y)]
        assert 0 <= gx < 64 and 0 <= gy < 64
        tiles.append(f'{int(map_ids[name]):03}{gx:02}{gy:02}')
    result['startupTiles'] = ','.join(sorted(set(tiles)))
    for path, data in source.items(): result['source.' + path] = hashlib.sha256(data).hexdigest()
    return ''.join(f'{k}={v}\n' for k, v in sorted(result.items()))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source-zip', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    assert sha256_file(args.source_zip) == SOURCE_SHA256
    with zipfile.ZipFile(args.source_zip) as archive:
        source = {path: archive.read(SOURCE_ROOT + '/' + path) for path in FILES}
    data = derive(source)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(data)
    print('Original-source game-data policy: 113 DBC schemas, 7 version sentinels, 8 startup tiles')


if __name__ == '__main__':
    main()
