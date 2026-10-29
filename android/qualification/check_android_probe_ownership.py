#!/usr/bin/env python3
"""Run the exact guest ownership program through owned native Linux PRoot.

Host-only prerequisite for the Android controller; never signals an observed PID.
"""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import time


def sample(pid):
    try:
        text = Path(f'/proc/{pid}/stat').read_text()
    except FileNotFoundError:
        return None
    if len(text) > 4096 or int(text.split(' ')[0]) != pid:
        raise ValueError('invalid process observation')
    fields = text[text.rfind(')') + 1:].split()
    return dict(pid=pid, parent=int(fields[1]), session=int(fields[3]), ticks=int(fields[19]), state=fields[0])


def ended(before):
    after = sample(before['pid'])
    return after is None or after['ticks'] != before['ticks'] or after['state'] in ('Z', 'X', 'x')


def run_case(proot, loader, root, work, mode, *, bindings=(), protected=None, no_pollution=False, program="/probe/android_probe.py"):
    control = work / 'ownership-control'
    control.unlink(missing_ok=True)
    log = work / (mode + '.log')
    with log.open('wb') as output:
        tracer = subprocess.Popen([str(proot), '--kill-on-exit'] +
            (['--fta-readonly=' + str(protected)] if protected is not None else []) +
            [part for source, target in bindings for part in ('-b', f'{source}:{target}')] +
            ['--sysvipc', '-0', '-r', str(root),
            '-b', '/dev', '-b', '/proc', '-b', '/sys', '-b', f'{work}:/session', '-w', '/session',
            '/usr/bin/env', '-i', 'PYTHONDONTWRITEBYTECODE=1', '/usr/bin/python3', '-B', program,
            '--ownership-normal' if mode == 'normal' else '--ownership-hold'],
            stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT, start_new_session=True,
            env=dict(os.environ, PROOT_LOADER=str(loader), PROOT_NO_SECCOMP='1', PROOT_TMP_DIR=str(work),
                     **({'PROOT_DONT_POLLUTE_ROOTFS': '1'} if no_pollution else {})))
        try:
            if mode == 'normal':
                if tracer.wait(10) != 0:
                    raise ValueError('normal ownership exit failed')
                return dict(exit=0)
            deadline = time.monotonic() + 5; pids = []
            while len(pids) < 3:
                raw = log.read_bytes()
                if len(raw) > 8192:
                    raise ValueError('oversized ownership output')
                pids = [int(line.split()[-1]) for line in raw.decode().splitlines() if line.startswith('FTA_ANDROID_OWNED ')]
                if tracer.poll() is not None or time.monotonic() >= deadline:
                    raise ValueError('missing live ownership tree')
                time.sleep(0.02)
            identities = [sample(pid) for pid in pids]
            if len(pids) != 3 or len(set(pids)) != 3 or any(x is None for x in identities) or \
                sum(x['parent'] == tracer.pid for x in identities) != 1 or \
                sum(x['session'] == x['pid'] for x in identities) != 2 or \
                any(x['parent'] not in pids + [tracer.pid] for x in identities):
                raise ValueError('unowned process tree')
            if mode == 'kill':
                control.write_text(f'kill {tracer.pid}\n')
            else:
                os.killpg(tracer.pid, signal.SIGTERM)
            status = tracer.wait(5)
            if mode == 'kill' and status != -signal.SIGKILL:
                raise ValueError('tracer was not killed by SIGKILL')
            deadline = time.monotonic() + 3
            while not all(ended(x) for x in identities):
                if time.monotonic() >= deadline:
                    raise ValueError('tracee survived owned tracer termination')
                time.sleep(0.02)
            return dict(exit=128 - status if status < 0 else status, endedTracees=3, survivingTracees=0, detachedLeaders=2)
        finally:
            if tracer.poll() is None:
                os.killpg(tracer.pid, signal.SIGKILL); tracer.wait(5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('proot', 'loader', 'root', 'work'):
        parser.add_argument('--' + name, required=True, type=Path)
    args = parser.parse_args(); args.work.mkdir()
    result = {mode: run_case(args.proot.resolve(), args.loader.resolve(), args.root.resolve(), args.work.resolve(), mode)
              for mode in ('normal', 'stop', 'kill')}
    result.update(scope='android-guest-ownership-program-on-linux', androidExecutionQualified=False)
    print(json.dumps(result, sort_keys=True))


if __name__ == '__main__':
    main()
