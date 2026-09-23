#!/usr/bin/env bash
# sub-00 Stage D: per-cell headless dev-server boot smoke.
# Boots the cell's dev server, requires the driver marker AND the vanilla
# "Done (...)" line within the timeout, then stops the server. Exit 0 only if
# both were observed. Used by .github/workflows/ci.yml and runnable locally.
#
# Usage: ci/boot-smoke.sh <cell>     e.g. ci/boot-smoke.sh 1.21.1-neoforge
# Env:   BOOT_SMOKE_TIMEOUT (seconds, default 600)
set -uo pipefail

CELL="${1:?usage: boot-smoke.sh <cell>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

RUN_DIR="drivers/driver-${CELL}/run"
LOG="build/boot-smoke-${CELL}.log"
MARKER="VINE driver ${CELL} alive"
TIMEOUT="${BOOT_SMOKE_TIMEOUT:-600}"

mkdir -p "$RUN_DIR" build
printf 'eula=true\n' > "$RUN_DIR/eula.txt"
: > "$LOG"

# Windows covers Git Bash (WINDIR set) and WSL (/proc/version mentions
# microsoft) — both reach gradlew through cmd.exe; CI runs on Linux.
WINDOWS=""
if [[ -n "${WINDIR:-}" ]] || grep -qiE 'microsoft|wsl' /proc/version 2>/dev/null; then
    WINDOWS=1
fi
if [[ -n "$WINDOWS" ]]; then
    GRADLE=(cmd.exe /c gradlew.bat)
else
    GRADLE=(./gradlew)
fi

echo "boot-smoke[${CELL}]: starting :drivers:driver-${CELL}:runServer (timeout ${TIMEOUT}s)"
"${GRADLE[@]}" ":drivers:driver-${CELL}:runServer" --console=plain > "$LOG" 2>&1 &
GPID=$!

stop_server() {
    kill "$GPID" 2>/dev/null
    if command -v powershell.exe >/dev/null 2>&1; then
        powershell.exe -NoProfile -Command \
            "Get-CimInstance Win32_Process | Where-Object { \$_.CommandLine -match 'driver-${CELL//./\\.}' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }" \
            >/dev/null 2>&1
    else
        pkill -f "driver-${CELL}" 2>/dev/null
    fi
    wait "$GPID" 2>/dev/null
}

result=1
deadline=$((SECONDS + TIMEOUT))
while (( SECONDS < deadline )); do
    if grep -qF "$MARKER" "$LOG" && grep -q "Done (" "$LOG"; then
        result=0
        break
    fi
    # Done without the marker is an immediate failure — no point waiting.
    if grep -q "Done (" "$LOG" && ! grep -qF "$MARKER" "$LOG"; then
        echo "boot-smoke[${CELL}]: FAIL — server reached Done without the driver marker"
        break
    fi
    if ! kill -0 "$GPID" 2>/dev/null; then
        echo "boot-smoke[${CELL}]: FAIL — gradle process exited before Done"
        break
    fi
    sleep 5
done

if (( SECONDS >= deadline )) && (( result != 0 )); then
    echo "boot-smoke[${CELL}]: FAIL — timed out after ${TIMEOUT}s waiting for marker + Done"
fi

stop_server

if (( result == 0 )); then
    echo "boot-smoke[${CELL}]: PASS"
    grep -F "$MARKER" "$LOG" | head -1
    grep "Done (" "$LOG" | head -1
fi
exit $result
