#!/usr/bin/env python3
"""Inspect the exact private WarcraftXL DLL, root proxy and host without loading them."""
import argparse
import hashlib
import json
from pathlib import Path
import sys

from pe_linkage import PE, InvalidPE, require

DLL_SHA256 = '9c935e27679a8161dbbdc3390bbb908d0ad444a5fe114ed0624c3db52656eedd'
PROXY_SHA256 = '4c3522dd17e2ef09cbe13ac82c1e4360c7beb98cbe7af7675d751d1f5bc330e8'
HOST_SHA256 = '6349752018efba461a2ac4bf19a98d1293aa7c21f98cb90f50615f33a7701955'
HOST_CHANNEL_RVA, HOST_CHANNEL_BYTES = 0xc107, 88
HOST_CHANNEL_SHA256 = '76b2733bfa073d78db5e80835e4b52758540f22dba1f59d9e265dfd9ac74b720'
HOST_INIT_RVA, HOST_INIT_BYTES = 0xc20d, 101
HOST_INIT_SHA256 = '12562a59a54d5d7bfa899e2eb033035a0128c3d4d9ea8376a0f1faeef4d961b5'
HOST_LIBRARIES = frozenset(('kernel32.dll', 'user32.dll', 'wininet.dll'))
HOST_KERNEL_IMPORTS = frozenset(('CreateFileMappingA', 'MapViewOfFile', 'CreateEventA',
    'CreateMutexA', 'GetCurrentProcessId', 'OpenProcess', 'WaitForSingleObject',
    'WaitForMultipleObjects', 'SetEvent', 'CloseHandle', 'ExitProcess'))
IPC_NAMES = ((0x1c3a4c, 0xccf98, r'Local\WarcraftXLShm_%u'),
             (0x1c3a64, 0xccfb0, r'Local\WarcraftXLReq_%u_%u'),
             (0x1c3a80, 0xccfd0, r'Local\WarcraftXLResp_%u_%u'),
             (0x1c3a9c, 0xccff0, r'Local\WarcraftXLBlob_%u_%u'),
             (0x1c3ab8, 0xcd010, r'Local\WarcraftXLHostSingleton_%u'))
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


def host_imports_satisfy(host):
    require(host.architecture == 'x64' and not host.dll, 'host-architecture-mismatch')
    require(host.directory(13, 32) is None and host.directory(14, 72) is None,
            'unexpected-host-delay-or-clr-directory')
    imports = host.imports()
    require(set(imports) == HOST_LIBRARIES and
            HOST_KERNEL_IMPORTS <= set(imports['kernel32.dll']), 'host-static-import-mismatch')
    return imports


def inspect_host(host_data, dll):
    require(hashlib.sha256(host_data).hexdigest() == HOST_SHA256, 'host-identity-mismatch')
    host = PE(host_data)
    imports = host_imports_satisfy(host)
    require(all(dll.string(dll_rva) == host.string(host_rva) == name
                for dll_rva, host_rva, name in IPC_NAMES), 'host-ipc-name-mismatch')
    require(host.string(0xcd548) == '--client-pid', 'host-argument-mismatch')
    for rva, size, sha in ((HOST_CHANNEL_RVA, HOST_CHANNEL_BYTES, HOST_CHANNEL_SHA256),
                          (HOST_INIT_RVA, HOST_INIT_BYTES, HOST_INIT_SHA256)):
        require(hashlib.sha256(host.rva(rva, size)).hexdigest() == sha, 'host-header-code-mismatch')
    require(host.rva(0xccf68, 8) == bytes.fromhex('0200000010000000'), 'host-channel-bound-mismatch')
    return dict(hostSha256=HOST_SHA256, hostBytes=len(host_data), hostArchitecture='x64',
        matchesThorImportedHost=True, assetHostBinaryInspected=True,
        hostStaticImportLibraries=sorted(imports), hostStaticImportCount=sum(map(len, imports.values())),
        hostDelayImportDirectoryPresent=False, hostClrDirectoryPresent=False,
        matchingIpcNameTemplates=len(IPC_NAMES), hostHeaderProducerPinned=True,
        hostChannelSelectionRva=HOST_CHANNEL_RVA, hostChannelSelectionBytes=HOST_CHANNEL_BYTES,
        hostChannelSelectionSha256=HOST_CHANNEL_SHA256,
        hostHeaderInitializationRva=HOST_INIT_RVA, hostHeaderInitializationBytes=HOST_INIT_BYTES,
        hostHeaderInitializationSha256=HOST_INIT_SHA256,
        hostChannelStrideBytes=0xc0040, hostInlineCapacityBytes=0xc0000,
        mixedX86X64WindowsRuntimeRequired=True, sharedWinePrefixRequired=True)


def assess(data, proxy_data=None, host_data=None):
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
        hostSupplied=host_data is not None, assetHostBinaryInspected=False,
        entireHostExecuted=False, hostPairingQualified=False, liveIpcQualified=False,
        wineRuntimeQualified=False, warcraftXLCompatible=False, gameLoginQualified=False,
        entireDllExecuted=False, clientOrWineExecuted=False, originalFilesModified=False,
        privateConfigurationExported=False)
    if proxy_data is not None:
        require(hashlib.sha256(proxy_data).hexdigest() == PROXY_SHA256, 'proxy-identity-mismatch')
        require(proxy_exports_satisfy(REQUIRED_EXPORTS, PE(proxy_data)), 'proxy-exports-mismatch')
        result.update(proxySha256=PROXY_SHA256, proxyBytes=len(proxy_data), proxyExportContractValidated=True)
    if host_data is not None:
        result.update(inspect_host(host_data, dll))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dll', type=Path, required=True)
    parser.add_argument('--proxy', type=Path)
    parser.add_argument('--host', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        evidence = assess(load(args.dll), load(args.proxy) if args.proxy else None,
                          load(args.host) if args.host else None)
        with args.output.open('x') as output:
            json.dump(evidence, output, indent=2); output.write('\n')
        print(json.dumps({key: evidence[key] for key in
            ('matchesThorImportedDll', 'proxyExportContractValidated', 'assetHostBinaryInspected')}))
    except InvalidPE as error:
        print(str(error), file=sys.stderr); return 1
    except (OSError, ValueError, TypeError):
        print('private-component-inspection-failed', file=sys.stderr); return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
