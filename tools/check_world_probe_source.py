#!/usr/bin/env python3
"""Qualify the receive-only world probe against pinned source and a host socket.

Original ServerPktHeader is compiled unchanged; payload is synthetic. No world,
accounts, login session, Android process or actual game data is executed here.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time
import zipfile

from build_native import check_jobs
from fta_source import SOURCE_ROOT, SOURCE_SHA256, sha256_file

ROOT = Path(__file__).resolve().parents[1]
FILES = (
    'src/server/game/Server/WorldSocket.cpp',
    'src/server/game/Server/WorldSocket.h',
    'src/server/game/Server/Protocol/Opcodes.h',
    'src/server/game/Server/Protocol/ServerPktHeader.h',
    'src/server/apps/worldserver/Main.cpp',
    'src/server/game/DataStores/DBCStores.cpp',
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-zip', type=Path, required=True)
    parser.add_argument('--kotlin-classpath', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if sha256_file(args.source_zip) != SOURCE_SHA256:
        raise ValueError('Original source identity mismatch')
    check_jobs(1)
    with tempfile.TemporaryDirectory(prefix='fta-world-probe-') as temp:
        work = Path(temp)
        with zipfile.ZipFile(args.source_zip) as archive:
            sources = {name: archive.read(SOURCE_ROOT + '/' + name) for name in FILES}
        world = sources[FILES[0]].decode()
        main_source = sources[FILES[4]].decode()
        dbc_source = sources[FILES[5]].decode()
        if len(re.findall(r'^\s*LOAD_DBC\(', dbc_source, re.M)) != 113:
            raise ValueError('Original required store count differs')
        for phrase in ('>> Initialized {} Data Stores in {} ms', 'storage.LoadFromDB(dbTable, storage.GetFormat())',
                       'bad_dbc_files.empty()'):
            if phrase not in dbc_source:
                raise ValueError('Original DBC completion/fallback boundary differs')
        for phrase in ('sConfigMgr->GetOption<int32>("MaxCoreStuckTime", 60)',
                       'Starting up anti-freeze thread ({} seconds max stuck time)...',
                       'World::m_worldLoopCounter', 'freezeDetector->_maxCoreStuckTimeInMs',
                       'Halting process...', 'sScriptMgr->OnShutdown();'):
            if phrase not in main_source:
                raise ValueError('Original world lifecycle evidence differs')
        if not (main_source.index('(worldserver-daemon) ready...') < main_source.index('sScriptMgr->OnStartup();') <
                main_source.index('    WorldUpdateLoop();') < main_source.index('sScriptMgr->OnShutdown();') <
                main_source.index('Halting process...')):
            raise ValueError('Original startup and shutdown ordering differs')
        body = world.split('void WorldSocket::HandleSendAuthSession()', 1)[1].split('\n}', 1)[0]
        for phrase in ('WorldPacket packet(SMSG_AUTH_CHALLENGE, 40);', 'packet << uint32(1);',
                       'packet.append(_authSeed);', 'packet.append(Acore::Crypto::GetRandomBytes<32>());'):
            if phrase not in body:
                raise ValueError('Original world challenge changed')
        if ('std::array<uint8, 4> _authSeed;' not in sources[FILES[1]].decode() or
                'SMSG_AUTH_CHALLENGE                             = 0x1EC,' not in sources[FILES[2]].decode() or
                'ServerPktHeader header(queued->size() + 2, queued->GetOpcode());' not in world or
                'EncryptableAndCompressiblePacket(packet, _authCrypt.IsInitialized())' not in world):
            raise ValueError('Original world framing changed')
        (work / 'ServerPktHeader.h').write_bytes(sources[FILES[3]])
        (work / 'Log.h').write_text('#include <cstdint>\nusing uint8=uint8_t; using uint16=uint16_t; using uint32=uint32_t;\n#define LOG_DEBUG(...) ((void)0)\n')
        (work / 'header.cpp').write_text('#include "ServerPktHeader.h"\n#include <iostream>\n'
            'int main(){ServerPktHeader h(42,0x1ec);if(h.getHeaderLength()!=4)return 1;'
            'std::cout.write(reinterpret_cast<char*>(h.header),4);}\n')
        subprocess.run(['g++', '-std=c++20', '-Wall', '-Wextra', '-Werror', str(work / 'header.cpp'),
                        '-o', str(work / 'header')], check=True)
        header = subprocess.check_output([str(work / 'header')])
        if header != bytes.fromhex('002aec01'):
            raise ValueError('Original header differs from probe')
        packet = header + bytes.fromhex('01000000') + bytes(range(36))
        (work / 'challenge.bin').write_bytes(packet)
        cp = str(work) + ':' + args.kotlin_classpath
        subprocess.run(['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-cp', cp, '-d', str(work),
                        str(ROOT / 'tests/fixtures/LocalWorldProbeHost.java')], check=True)
        command = ['java', '-Djava.net.preferIPv4Stack=true', '-cp', cp, 'LocalWorldProbeHost']
        subprocess.run(command + ['packet', str(work / 'challenge.bin')], check=True, capture_output=True)
        def exchange(payload, delay=0):
            received = []; errors = []
            with socket.socket() as server:
                server.bind(('127.0.0.1', 0)); server.listen(1); server.settimeout(10)
                def serve():
                    try:
                        client, _ = server.accept()
                        with client:
                            client.settimeout(5)
                            for byte in payload:
                                client.sendall(bytes([byte]))
                                if delay: time.sleep(delay)
                            client.shutdown(socket.SHUT_WR)
                            received.append(client.recv(1))
                    except (BrokenPipeError, ConnectionResetError):
                        received.append(b'')
                    except Exception as error: errors.append(type(error).__name__)
                thread = threading.Thread(target=serve, daemon=True); thread.start()
                start = time.monotonic()
                result = subprocess.run(command + ['socket', str(server.getsockname()[1])],
                                        capture_output=True, timeout=10)
                elapsed = time.monotonic() - start
                thread.join(timeout=7)
                if thread.is_alive() or errors or received != [b'']:
                    raise ValueError('Host socket failed or probe sent unexpected bytes')
            return result.returncode, elapsed
        if exchange(packet, .002)[0] != 0:
            raise ValueError('Fragmented source-header challenge rejected')
        if exchange(packet[:-1])[0] == 0:
            raise ValueError('Truncated challenge accepted')
        code, duration = exchange(packet, .1)
        if code == 0 or duration > 3.5:
            raise ValueError('Slow fragments extended the total read deadline')
    proof = {
        'formatVersion': 1, 'scope': 'source-derived-receive-only-world-challenge-host-probe',
        'sourceArchiveSha256': SOURCE_SHA256,
        'sourceSha256': {name: hashlib.sha256(data).hexdigest() for name, data in sources.items()},
        'productionProbeSha256': sha256_file(ROOT / 'android/app/src/main/java/com/russianranger/fta/runtime/LocalWorldProbe.kt'),
        'originalServerHeaderCompiledUnchanged': True, 'syntheticPacketBytes': 44,
        'fragmentedLoopbackChallengePassed': True, 'truncatedChallengeRejected': True,
        'slowFragmentsHitTotalDeadline': True, 'clientBytesSent': 0,
        'originalRequiredDbcStores': 113, 'originalFallbackAndCompletionBoundaryChecked': True,
        'readyMarkerPrecedesModuleStartup': True, 'haltMarkerFollowsModuleShutdown': True,
        'originalFreezeGuardObservesWorldLoopCounter': True,
        'androidExecuted': False, 'originalWorldserverExecuted': False,
        'worldReady': False, 'gameLoginQualified': False,
    }
    args.output.write_text(json.dumps(proof, indent=2) + '\n')
    print('Source framing and receive-only loopback probe passed; world startup remains unqualified')


if __name__ == '__main__':
    main()
