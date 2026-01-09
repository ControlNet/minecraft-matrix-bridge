#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

SKIP_TESTS="false"
if [[ "${1:-}" == "--skip-tests" ]]; then
  SKIP_TESTS="true"
fi

MOD_ID="$(grep -E '^mod_id=' gradle.properties | head -n1 | cut -d= -f2- | tr -d '\r' | xargs || true)"
MOD_VERSION="$(grep -E '^mod_version=' gradle.properties | head -n1 | cut -d= -f2- | tr -d '\r' | xargs || true)"

if [[ -z "${MOD_ID}" ]]; then
  echo "ERROR: mod_id not found in gradle.properties" >&2
  exit 1
fi
if [[ -z "${MOD_VERSION}" ]]; then
  echo "ERROR: mod_version not found in gradle.properties" >&2
  exit 1
fi

echo "Building ${MOD_ID} version ${MOD_VERSION}..."

TASKS=()
if [[ "${SKIP_TESTS}" != "true" ]]; then
  TASKS+=(":core:test")
fi
TASKS+=(":forge-1.18:build" ":forge-1.19:build" ":forge-1.20:build" ":forge-1.21:build")

./gradlew --no-daemon --stacktrace -Pmod_version="${MOD_VERSION}" "${TASKS[@]}"

rm -rf dist
mkdir -p dist

for p in forge-1.18/build/libs/*.jar forge-1.19/build/libs/*.jar forge-1.20/build/libs/*.jar forge-1.21/build/libs/*.jar; do
  [[ -e "${p}" ]] || continue
  b="$(basename "${p}")"
  # Skip common non-release jars if present.
  if [[ "${b}" == *-sources.jar || "${b}" == *-javadoc.jar ]]; then
    continue
  fi
  cp -f "${p}" dist/
done

EXPECTED=(
  "${MOD_ID}-forge-1.18.x-${MOD_VERSION}.jar"
  "${MOD_ID}-forge-1.19.x-${MOD_VERSION}.jar"
  "${MOD_ID}-forge-1.20.x-${MOD_VERSION}.jar"
  "${MOD_ID}-forge-1.21.x-${MOD_VERSION}.jar"
)

for f in "${EXPECTED[@]}"; do
  if [[ ! -f "dist/${f}" ]]; then
    echo "ERROR: expected jar not found: dist/${f}" >&2
    echo "dist contains:" >&2
    ls -la dist >&2 || true
    exit 1
  fi
done

(cd dist && sha256sum *.jar > SHA256SUMS.txt)
echo "Done. Artifacts:"
ls -la dist

