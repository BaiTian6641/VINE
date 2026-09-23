#!/usr/bin/env bash
# sub-00 Stage D: Prime Invariant gate (§5.1). Scans the built API jars for any
# reference to loader/Minecraft packages; a single hit fails the build.
# Run after: ./gradlew :vine-api:jar :vine-client-api:jar
set -uo pipefail


# unzip is not on every bash PATH (WSL here lacks it); resolve an executable
# explicitly, and NEVER let a missing tool masquerade as a clean scan.
UNZIP="$(command -v unzip 2>/dev/null || true)"
for c in "/mnt/c/Program Files/Git/usr/bin/unzip.exe" "/c/Program Files/Git/usr/bin/unzip.exe" "C:/Program Files/Git/usr/bin/unzip.exe"; do
    [[ -z "$UNZIP" && -x "$c" ]] && UNZIP="$c"
done
[[ -n "$UNZIP" ]] || { echo "prime-invariant: FAIL — unzip not found"; exit 1; }
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

fail=0
for module in vine-api vine-client-api; do
    jar=$(ls "$module"/build/libs/"$module"-*.jar 2>/dev/null | grep -v -- '-sources' | head -1 || true)
    if [[ -z "${jar:-}" ]]; then
        echo "prime-invariant: FAIL — no jar found for $module (build it first)"
        fail=1
        continue
    fi
    # Byte-level scan of every entry: catches class refs, strings, descriptors.
    bytes=$(mktemp)
    if ! "$UNZIP" -p "$jar" > "$bytes" 2>/dev/null; then
        echo "prime-invariant: FAIL — could not extract $jar"
        rm -f "$bytes"
        fail=1
        continue
    fi
    if grep -aqE 'net/(minecraft|neoforged|fabricmc)' "$bytes"; then
        echo "prime-invariant: FAIL — $jar references net.minecraft/net.neoforged/net.fabricmc:"
        "$UNZIP" -l "$jar" | head -20
        fail=1
    else
        echo "prime-invariant: OK — $jar clean"
    fi
    rm -f "$bytes"
done
exit $fail
