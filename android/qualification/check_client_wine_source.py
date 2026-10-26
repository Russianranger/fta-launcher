#!/usr/bin/env python3
"""Compare the Kotlin DLL override policy with the unchanged original Linux function.

Only synthetic override strings are processed. No Wine/client is started; no
private source is copied to Git. Compile the Kotlin host bridge separately.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import random
import subprocess
import zipfile

LAUNCHER_SHA256 = 'b1ab843535307a5a384b30ed29ef80733abf5e45b7967250408a80f79a6380f0'
MEMBER = 'Launcher/src/main_linux.cpp'


def corpus():
    cases = ['', 'd3d9=b', 'D3D9.DLL=', 'dxgi,d3d9,d3d11=b',
             'd3d9=n;d3d9.dll=b;d3d9=', 'opaque;d3d9', '*d3d9=b;d3d9helper=n',
             ';;;', ',,=b', 'dxgi==b', ' \t\r\n\v\f ', '\u00a0d3d9=b',
             'winemenubuilder.exe=;dinput8=n,b', 'x' * 16384]
    rng = random.Random(12340)
    names = ['d3d9', 'D3D9', 'd3d9.dll', 'D3D9.DLL', 'dxgi', 'd3d11', 'dinput8',
             'winemenubuilder.exe', '*d3d9', 'd3d9helper', 'opaque', '', '\u00a0d3d9']
    spaces = ['', ' ', '\t', '\r\n', '\v\f']
    for _ in range(1024):
        clauses = []
        for _ in range(rng.randrange(1, 12)):
            modules = ','.join(rng.choice(spaces) + rng.choice(names) + rng.choice(spaces)
                               for _ in range(rng.randrange(1, 6)))
            clauses.append(modules + rng.choice(['=b', '=n,b', '=', '= b,n ', '==n', '']))
        cases.append(';'.join(clauses))
    return cases


CPP_BRIDGE = r'''
int main() {
    std::setlocale(LC_ALL, "C");
    std::string line;
    auto digit = [](char c) -> unsigned { return c <= '9' ? c - '0' : c - 'a' + 10; };
    while (std::getline(std::cin, line)) {
        if (line.size() % 2 || line.size() > 32768) return 2;
        std::string input;
        for (std::size_t i = 0; i < line.size(); i += 2)
            input.push_back(static_cast<char>(digit(line[i]) * 16 + digit(line[i + 1])));
        auto result = NativeD3d9Overrides(input);
        for (unsigned char c : result) std::cout << "0123456789abcdef"[c >> 4] << "0123456789abcdef"[c & 15];
        std::cout << '\n';
    }
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--launcher-zip', type=Path, required=True)
    parser.add_argument('--kotlin-classpath', required=True)
    parser.add_argument('--work-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    archive = args.launcher_zip.read_bytes()
    if hashlib.sha256(archive).hexdigest() != LAUNCHER_SHA256:
        raise SystemExit('launcher-identity-mismatch')
    with zipfile.ZipFile(args.launcher_zip) as source:
        original = source.read(MEMBER)
    begin = original.index(b'std::string TrimAscii(')
    end = original.index(b'bool IsManagedWineVariable(', begin)
    unchanged = original[begin:end]
    args.work_dir.mkdir(parents=True, exist_ok=False)
    harness = args.work_dir / 'original.cpp'
    harness.write_bytes(b'#include <algorithm>\n#include <cctype>\n#include <clocale>\n#include <iostream>\n'
                       b'#include <string>\n#include <utility>\n#include <vector>\n' + unchanged + CPP_BRIDGE.encode())
    binary = args.work_dir / 'original'
    with (args.work_dir / 'compiler.log').open('wb') as log:
        subprocess.run([os.environ.get('CXX', 'g++'), '-std=c++20', '-Wall', '-Wextra', '-Werror',
                        str(harness), '-o', str(binary)], stdout=log, stderr=subprocess.STDOUT, check=True, timeout=60)
    cases = corpus()
    data = ('\n'.join(value.encode().hex() for value in cases) + '\n').encode()
    expected = subprocess.run([str(binary)], input=data, capture_output=True, check=True, timeout=30).stdout
    actual = subprocess.run(['java', '-Xmx64m', '-cp', args.kotlin_classpath,
                             'com.russianranger.fta.client.ClientWineOverridesHost'],
                            input=data, capture_output=True, check=True, timeout=60).stdout
    if actual != expected or len(actual.splitlines()) != len(cases):
        raise SystemExit('source-comparison-mismatch')
    root = Path(__file__).resolve().parents[2]
    implementation = root / 'android/app/src/main/java/com/russianranger/fta/client/ClientWineOverrides.kt'
    evidence = dict(formatVersion=1, scope='original-launcher-wine-override-comparison',
        launcherZipSha256=LAUNCHER_SHA256, sourceMember=MEMBER,
        sourceMemberSha256=hashlib.sha256(original).hexdigest(),
        unchangedFunctionSliceSha256=hashlib.sha256(unchanged).hexdigest(),
        kotlinImplementationSha256=hashlib.sha256(implementation.read_bytes()).hexdigest(),
        cases=len(cases), corpusSha256=hashlib.sha256(data).hexdigest(),
        outputSha256=hashlib.sha256(actual).hexdigest(), exactSourceComparisonPassed=True,
        originalFunctionModified=False, clientOrWineExecuted=False, originalClientFilesModified=False,
        assetHostIdentityQualified=False, wineRuntimeQualified=False, warcraftXLCompatible=False,
        gameLoginQualified=False)
    with args.output.open('x') as output:
        json.dump(evidence, output, indent=2); output.write('\n')
    print(json.dumps(dict(cases=len(cases), exactSourceComparisonPassed=True)))


if __name__ == '__main__':
    main()
