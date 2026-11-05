#!/usr/bin/env bash
# Start every module's dev server against a local mock Matrix homeserver and keep
# one log per module. Delegates to scripts/system_test_all.py, which owns the server
# process (no pattern-based pkill), uses each module's real run directory, and fails
# when the bridge, event taps, or Matrix -> Minecraft broadcast do not work.
#
# Usage: ./test-runs/test_all_versions.sh [module ...]
# Exit status is non-zero if any module failed; all modules are still attempted.

set -uo pipefail

cd "$(dirname "$0")/.."

if [[ $# -gt 0 ]]; then
    VERSIONS=("$@")
else
    VERSIONS=("forge-1.18" "forge-1.19" "forge-1.20" "forge-1.21" "forge-26.1" "forge-26.2" "neoforge-1.21" "neoforge-26.1" "neoforge-26.2")
fi
LOG_DIR="test-runs/logs"
TIMEOUT_S=600

mkdir -p "$LOG_DIR"

failed=()
for VERSION in "${VERSIONS[@]}"; do
    echo ""
    echo "=========================================="
    echo "Testing $VERSION"
    echo "=========================================="
    LOG_FILE="$LOG_DIR/${VERSION}.log"
    if python3 scripts/system_test_all.py --modules "$VERSION" --timeout-s "$TIMEOUT_S" > "$LOG_FILE" 2>&1; then
        echo "PASS $VERSION"
    else
        echo "FAIL $VERSION (see $LOG_FILE)"
        failed+=("$VERSION")
    fi
    grep -E "Event index built in|MatrixBridge started|\[Matrix\] <|OK: " "$LOG_FILE" | head -5 || true
done

echo ""
echo "=========================================="
if [[ ${#failed[@]} -gt 0 ]]; then
    echo "Failed modules: ${failed[*]}. Logs saved in $LOG_DIR"
    echo "=========================================="
    exit 1
fi
echo "All ${#VERSIONS[@]} modules passed. Logs saved in $LOG_DIR"
echo "=========================================="
