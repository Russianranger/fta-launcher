#!/bin/sh
# Runs inside a new disposable ARM64 chroot; no original game or private inputs.
set -eu
export PATH=/opt/wine/bin:/usr/bin:/bin HOME=/root LANG=C.UTF-8
export WINEPREFIX=/fta-ci-prefix WINEARCH=win64 DISPLAY=:8
export WINEDEBUG=-all,err+all WINEDLLOVERRIDES='winemenubuilder,mshtml,mscoree='
export WINEESYNC=0 WINEFSYNC=0 FEX_DISABLETELEMETRY=1
test ! -e "$WINEPREFIX"
mkdir -p /probe/logs /tmp
chmod 1777 /tmp
FTA_DISPLAY_PID=
cleanup() {
    result=$?
    trap - EXIT HUP INT TERM
    timeout 30 wineserver -k > /probe/logs/wineserver-stop.log 2>&1 || true
    timeout 30 wineserver -w >> /probe/logs/wineserver-stop.log 2>&1 || true
    if [ -n "$FTA_DISPLAY_PID" ]; then
        kill "$FTA_DISPLAY_PID" 2>/dev/null || true
        wait "$FTA_DISPLAY_PID" 2>/dev/null || true
    fi
    exit "$result"
}
trap cleanup EXIT HUP INT TERM
Xtigervnc :8 -geometry 640x480 -depth 24 -rfbport -1 -SecurityTypes None -nolisten tcp -ac > /probe/logs/display.log 2>&1 &
FTA_DISPLAY_PID=$!
for attempt in 1 2 3 4 5 6 7 8 9 10; do
    [ ! -S /tmp/.X11-unix/X8 ] || break
    kill -0 "$FTA_DISPLAY_PID"
    sleep 1
done
test -S /tmp/.X11-unix/X8
timeout 180 wine wineboot.exe -u > /probe/logs/prefix.log 2>&1
timeout 90 wine /probe/probe32.exe 2 > /probe/logs/two-channels.log 2>&1
timeout 90 wine /probe/probe32.exe 16 > /probe/logs/sixteen-channels.log 2>&1
timeout 90 wine /probe/probe64.exe --observe-owner-exit > /probe/logs/owner-exit.log 2>&1
tr -d '\r' < /probe/logs/two-channels.log | grep -Fx 'FTA_SYNTHETIC_PARENT_OK channels=2 roundTrips=64 ownerExit=0'
tr -d '\r' < /probe/logs/sixteen-channels.log | grep -Fx 'FTA_SYNTHETIC_PARENT_OK channels=16 roundTrips=512 ownerExit=0'
tr -d '\r' < /probe/logs/owner-exit.log | grep -Fx 'FTA_SYNTHETIC_OWNER_EXIT_OK parentExit=0 childExit=38'
printf '%s\n' 'FTA_SYNTHETIC_MIXED_RUNTIME_PASS'
