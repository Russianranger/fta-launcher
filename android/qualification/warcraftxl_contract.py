#!/usr/bin/env python3
"""Inspect the exact private WarcraftXL DLL and optional root proxy without loading them."""
import argparse
import hashlib
import json
from pathlib import Path
import sys

from pe_linkage import PE, InvalidPE, require

DLL_SHA256 = '9c935e27679a8161dbbdc3390bbb908d0ad444a5fe114ed0624c3db52656eedd'
PROXY_SHA256 = '4c3522dd17e2ef09cbe13ac82c1e4360c7beb98cbe7af7675d751d1f5bc330e8'
HEADER_RVA = 0x219e0
HEADER_BYTES = 71
HEADER_SHA256 = '7b2a861ccac0eac7be7bbcf7cf17ff5767697e0027208e04a192095d8918e20c'
REQUIRED_EXPORTS = frozenset(('WxlGetSsaaFactor', 'WxlGetControlSettings', 'WxlGetControlOption',
                            'WxlResetControlSettings', 'WxlSaveControlSettings', 'WxlSetControlOption'))


def load(path):
    with path.open('rb') as stream:
        data = stream.read(PE.MAX_BYTES + 1)
    require(len(data) <= PE.MAX_BYTES, 'pe-size-limit')
    return data


def proxy_exports_satisfy(required, proxy):
    require(proxy.architecture == 'x86' and proxy.dll, 'proxy-architecture-mismatch')
    exports = proxy.exports()
    return all(name in exports and exports[name]['executable'] and not exports[name]['forwarded'] for name in required)


def assess(data, proxy_data=None):
    sha = hashlib.sha256(data).hexdigest()
    require(sha == DLL_SHA256, 'warcraftxl-identity-mismatch')
    dll = PE(data)
    require(dll.architecture == 'x86' and dll.dll, 'warcraftxl-architecture-mismatch')
    imports = dll.imports()
    required = imports.get('d3d9.dll', [])
    require(len(required) == len(REQUIRED_EXPORTS) and set(required) == REQUIRED_EXPORTS, 'proxy-contract-mismatch')
    kernel = imports.get('kernel32.dll', [])
    require(all(name in kernel for name in ('CreateProcessA', 'GetCurrentProcessId', 'OpenFileMappingA',
                                           'MapViewOfFile', 'OpenEventA', 'WaitForMultipleObjects')), 'host-import-mismatch')
    require(dll.string(0x1c4058) == '\\Utils' and dll.string(0x1c4060) == '\\WarcraftXLHost.exe' and
            dll.string(0x1c4080) == 'WarcraftXLHost.exe --client-pid %lu%s', 'host-reference-mismatch')
    validator = dll.rva(HEADER_RVA, HEADER_BYTES)
    require(hashlib.sha256(validator).hexdigest() == HEADER_SHA256, 'header-validator-mismatch')
    result = dict(formatVersion=1, scope='exact-warcraftxl-static-contract', dllSha256=sha, dllBytes=len(data),
        dllArchitecture='x86', matchesThorImportedDll=True,
        referencedHostRelativePath='Utils/WarcraftXLHost.exe', hostArgumentTemplate='--client-pid <client-pid>',
        requiredRootProxyExports=sorted(REQUIRED_EXPORTS), headerValidatorRva=HEADER_RVA,
        headerValidatorBytes=HEADER_BYTES, headerValidatorSha256=HEADER_SHA256,
        headerSnapshotBytes=64, headerMagic=0x4c585857, headerProtocol=4, channelCountMinimum=2,
        channelCountMaximum=16, nonzeroHostProcessWordRequired=True,
        proxySupplied=proxy_data is not None, proxyExportContractValidated=False,
        assetHostBinaryInspected=False, hostPairingQualified=False, liveIpcQualified=False,
        wineRuntimeQualified=False, warcraftXLCompatible=False, gameLoginQualified=False,
        entireDllExecuted=False, clientOrWineExecuted=False, originalFilesModified=False,
        privateConfigurationExported=False)
    if proxy_data is not None:
        require(hashlib.sha256(proxy_data).hexdigest() == PROXY_SHA256, 'proxy-identity-mismatch')
        require(proxy_exports_satisfy(REQUIRED_EXPORTS, PE(proxy_data)), 'proxy-exports-mismatch')
        result.update(proxySha256=PROXY_SHA256, proxyBytes=len(proxy_data), proxyExportContractValidated=True)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dll', type=Path, required=True)
    parser.add_argument('--proxy', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        evidence = assess(load(args.dll), load(args.proxy) if args.proxy else None)
        with args.output.open('x') as output:
            json.dump(evidence, output, indent=2); output.write('\n')
        print(json.dumps(dict(matchesThorImportedDll=True, proxyExportContractValidated=evidence['proxyExportContractValidated'])))
    except InvalidPE as error:
        print(str(error), file=sys.stderr); return 1
    except (OSError, ValueError, TypeError):
        print('private-component-inspection-failed', file=sys.stderr); return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
