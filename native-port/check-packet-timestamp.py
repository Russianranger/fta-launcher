#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Check the exact packet timestamp expression with production ByteBuffer code."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from fta_source import verify_source_tree, sha256_file


def function(source: str, signature: str) -> str:
    start = source.index(signature)
    opening = source.index('{', start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--profile', default='android-arm64-world-v9')
    parser.add_argument('--ndk-root', type=Path, required=True)
    parser.add_argument('--cxx', default='g++')
    args = parser.parse_args()
    source = args.source.resolve()
    inventory = verify_source_tree(source, port_profile=args.profile)
    unit = (source / 'src/server/game/Entities/Unit/Unit.cpp').read_text()
    movement = function(unit, 'void Unit::SendMonsterMove(')
    original = 'data << GameTime::GetGameTimeMS().count();'
    fixed = 'data << static_cast<int64>(GameTime::GetGameTimeMS().count());'
    if movement.count(fixed) != 1 or original in movement:
        raise ValueError('Source does not contain the reviewed packet timestamp conversion')
    packets = source / 'src/server/shared/Packets'
    append = function((packets / 'ByteBuffer.cpp').read_text(),
                      'void ByteBuffer::append(uint8 const* src, std::size_t cnt)')
    harness = r'''
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <limits>
#include <type_traits>
#include "ByteBuffer.h"
#include "GameTime.h"
#define ASSERT(condition, ...) do { if (!(condition)) std::abort(); } while (0)
APPEND_BODY
namespace GameTime {
    using Milliseconds = ::Milliseconds;
    Milliseconds::rep current;
    Milliseconds GetGameTimeMS() { return Milliseconds(current); }
}
static_assert(sizeof(int64) == 8 && std::is_signed_v<int64>);
static_assert(sizeof(GameTime::Milliseconds::rep) == 8 && std::is_signed_v<GameTime::Milliseconds::rep>);
#ifdef __ANDROID__
static_assert(std::is_same_v<GameTime::Milliseconds::rep, long long>);
static_assert(std::is_same_v<int64, long>);
#else
static_assert(std::is_same_v<GameTime::Milliseconds::rep, int64>);
#endif
void emit(ByteBuffer& data) { TIMESTAMP_STATEMENT }
int main() {
    int64 const values[] = {0, 1, 2147483647, 4294967295LL, 4294967296LL,
                           std::numeric_limits<int64>::max(), -1, std::numeric_limits<int64>::min()};
    for (int64 value : values) {
        GameTime::current = value;
        ByteBuffer data;
        emit(data);
        if (data.size() != 8) return 1;
        uint64 const bits = static_cast<uint64>(value);
        for (unsigned byte = 0; byte != 8; ++byte) {
            if (data.contents()[byte] != static_cast<uint8>(bits >> (byte * 8))) return 2;
            std::printf("%02x", static_cast<unsigned>(data.contents()[byte]));
        }
        std::puts("");
    }
}
'''.replace('APPEND_BODY', append)
    includes = [str(packets), str(source / 'src/common'), str(source / 'src/common/Utilities'),
                str(source / 'src/server/game/Time')]
    flags = ['-std=c++20'] + ['-I' + path for path in includes]
    clang = args.ndk_root.resolve() / 'toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android33-clang++'
    with tempfile.TemporaryDirectory(prefix='fta-packet-timestamp-') as directory:
        root = Path(directory)
        outputs = []
        for name, statement in [('original', original), ('fixed', fixed)]:
            test = root / (name + '.cpp')
            test.write_text(harness.replace('TIMESTAMP_STATEMENT', statement))
            executable = root / (name + '-host')
            subprocess.run([args.cxx, *flags, str(test), '-o', str(executable)],
                           check=True, capture_output=True)
            outputs.append(subprocess.run([str(executable)], check=True, capture_output=True).stdout)
            target = subprocess.run([str(clang), *flags, '-c', str(test), '-o', str(root / (name + '.o'))],
                                    capture_output=True, text=True)
            if name == 'original':
                if target.returncode == 0 or 'ambiguous' not in target.stderr:
                    raise ValueError('Original Android packet overload ambiguity was not reproduced')
            elif target.returncode:
                raise ValueError('Reviewed Android packet timestamp failed: ' + target.stderr[-2000:])
        if outputs[0] != outputs[1] or len(outputs[0].splitlines()) != 8:
            raise ValueError('Packet conversion changes original host serialization')
    verify_source_tree(source, port_profile=args.profile)
    print(json.dumps({'status': 'passed', 'profile': args.profile,
                      'source_tree_sha256': inventory['tree_sha256'], 'host_cases': 8,
                      'serialized_bytes_per_case': 8, 'host_original_and_fixed_bytes_equal': True,
                      'original_android_ambiguity_reproduced': True, 'fixed_android_compile': 'passed',
                      'android_binaries_executed': False,
                      'byte_buffer_header_sha256': sha256_file(packets / 'ByteBuffer.h'),
                      'game_time_header_sha256': sha256_file(source / 'src/server/game/Time/GameTime.h'),
                      'duration_header_sha256': sha256_file(source / 'src/common/Utilities/Duration.h'),
                      'byte_buffer_append_sha256': hashlib.sha256(append.encode()).hexdigest(),
                      'statement_sha256': hashlib.sha256(fixed.encode()).hexdigest(),
                      'scope': 'actual ByteBuffer header/append and exact timestamp expression; host byte execution and NDK compile only'}))


if __name__ == '__main__':
    main()
