#!/bin/bash
# Test all Minecraft server versions and capture logs
# Usage: ./test_all_versions.sh

set -e

cd "$(dirname "$0")/.."

VERSIONS=("forge-1.18" "forge-1.19" "forge-1.20" "forge-1.21" "forge-26" "neoforge-1.21" "neoforge-26")
LOG_DIR="test-runs/logs"
TIMEOUT=180  # 3 minutes max per server

mkdir -p "$LOG_DIR"

# Clean up any existing server processes
cleanup() {
    echo "Cleaning up..."
    pkill -f "runServer" 2>/dev/null || true
    pkill -f "minecraft" 2>/dev/null || true
}

trap cleanup EXIT

for VERSION in "${VERSIONS[@]}"; do
    echo ""
    echo "=========================================="
    echo "Testing $VERSION"
    echo "=========================================="
    
    LOG_FILE="$LOG_DIR/${VERSION}.log"
    
    # Accept EULA
    if [[ "$VERSION" == "forge-26" || "$VERSION" == "neoforge-26" ]]; then
        RUN_DIR="${VERSION}/run-systemtest"
        GRADLEW="./gradlew-26"
        GRADLE_ARGS=("-Pomx_modern_26=true")
    else
        RUN_DIR="${VERSION}/run"
        GRADLEW="./gradlew"
        GRADLE_ARGS=()
    fi
    mkdir -p "$RUN_DIR"
    echo "eula=true" > "$RUN_DIR/eula.txt"
    
    # Create minimal server.properties to speed up startup
    cat > "$RUN_DIR/server.properties" << EOF
online-mode=false
level-type=flat
generate-structures=false
spawn-npcs=false
spawn-animals=false
spawn-monsters=false
max-tick-time=-1
EOF
    
    # Create config directory with minimal config
    mkdir -p "$RUN_DIR/config"
    cat > "$RUN_DIR/config/minecraftmatrixbridge.toml" << EOF
[matrix]
homeserver = "http://localhost:9999"
roomId = "!test:localhost"
accessToken = "test_token"

[bridge]
enableMcToMatrix = true
enableMatrixToMc = true
enableEventTaps = true
EOF
    
    echo "Starting $VERSION server..."
    
    # Run server with timeout, capturing output
    timeout $TIMEOUT "$GRADLEW" "${GRADLE_ARGS[@]}" :${VERSION}:runServer --no-daemon 2>&1 | tee "$LOG_FILE" &
    SERVER_PID=$!
    
    # Wait for server to fully start or fail
    STARTED=false
    for i in $(seq 1 60); do
        sleep 3
        if grep -q "Event index built in" "$LOG_FILE" 2>/dev/null; then
            STARTED=true
            echo "Event index built - checking results..."
            break
        fi
        if grep -q "Done\|For help, type" "$LOG_FILE" 2>/dev/null; then
            STARTED=true
            echo "Server started successfully"
            break
        fi
        if ! kill -0 $SERVER_PID 2>/dev/null; then
            echo "Server process exited"
            break
        fi
        echo "  Waiting... ($i)"
    done
    
    # Give it a moment to finish logging
    sleep 2
    
    # Kill the server
    echo "Stopping server..."
    kill $SERVER_PID 2>/dev/null || true
    wait $SERVER_PID 2>/dev/null || true
    
    # Extract event indexing info
    echo ""
    echo "--- Event Index Results for $VERSION ---"
    grep -i "event.*index\|indexed\|scanning\|MatrixBridge" "$LOG_FILE" 2>/dev/null | head -20 || echo "No event index logs found"
    echo ""
    
done

echo ""
echo "=========================================="
echo "All tests complete. Logs saved in $LOG_DIR"
echo "=========================================="
