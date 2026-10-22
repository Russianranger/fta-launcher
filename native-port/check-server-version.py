#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Compile and exercise the exact reviewed core's provider-version function."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from fta_source import verify_source_tree


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--profile', default='mariadb-c-api-v1')
    parser.add_argument('--cxx', default='g++')
    args = parser.parse_args()
    inventory = verify_source_tree(args.source, port_profile=args.profile)
    source = (args.source / 'src/server/database/Database/DatabaseWorkerPool.cpp').read_text()
    start = source.index('bool DatabaseIncompatibleVersion(std::string const mysqlVersion)')
    end = source.index('\ntemplate <class T>\nuint32 DatabaseWorkerPool<T>::OpenConnections', start)
    function = source[start:end]
    cases = [('12.3.2-MariaDB', False), ('5.5.5-12.3.2-MariaDB', False),
             ('12.3.2-MariaDB-android', False), ('12.3.1-MariaDB', True),
             ('12.3.20-MariaDB', True), ('12.4.0-MariaDB', True),
             ('13.0.0-MariaDB', True), ('8.4.2-MySQL', True),
             ('12.3.2-Unknown', True), ('', True)]
    harness = '#include <string>\n#define MARIADB_BASE_VERSION "actual-provider-branch"\n' + function
    harness += '\nint main() {\n'
    for version, incompatible in cases:
        harness += f' if (DatabaseIncompatibleVersion({json.dumps(version)}) != {str(incompatible).lower()}) return 1;\n'
    harness += ' return 0;\n}\n'
    with tempfile.TemporaryDirectory(prefix='fta-server-version-') as directory:
        root = Path(directory)
        test = root / 'version.cpp'
        test.write_text(harness)
        subprocess.run([args.cxx, '-std=c++20', str(test), '-o', str(root / 'version-test')], check=True)
        subprocess.run([str(root / 'version-test')], check=True)
    print(json.dumps({'status':'passed', 'cases':len(cases),
                      'actual_source_tree_sha256':inventory['tree_sha256'],
                      'scope':'compiled exact core provider-version function; no DB connection'}))


if __name__ == '__main__':
    main()
