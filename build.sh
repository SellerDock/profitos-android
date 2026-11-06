#!/usr/bin/env bash
# Użycie: build.sh [debug|release]
#   debug   (domyślny) -> assembleDebug, bez dodatkowych mountów
#   release            -> assembleRelease, montuje keystore z /opt/profitos/apk-release (ro)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

MODE="${1:-debug}"

case "${MODE}" in
  debug)
    GRADLE_TASK="assembleDebug"
    APK_PATH="${SCRIPT_DIR}/app/build/outputs/apk/debug/app-debug.apk"
    ;;
  release)
    GRADLE_TASK="assembleRelease"
    APK_PATH="${SCRIPT_DIR}/app/build/outputs/apk/release/app-release.apk"
    ;;
  *)
    echo "Nieznany tryb budowania: '${MODE}'" >&2
    echo "Użycie: build.sh [debug|release]" >&2
    exit 1
    ;;
esac

docker build -t profitos-apk-builder "${SCRIPT_DIR}"

DOCKER_ARGS=(-v "${SCRIPT_DIR}:/workspace")
if [[ "${MODE}" == "release" ]]; then
  DOCKER_ARGS+=(-v /opt/profitos/apk-release:/opt/profitos/apk-release:ro)
fi

docker run --rm \
  "${DOCKER_ARGS[@]}" \
  -w /workspace \
  profitos-apk-builder \
  ./gradlew --no-daemon "${GRADLE_TASK}"

echo
echo "APK: ${APK_PATH}"
ls -la "${APK_PATH}"
