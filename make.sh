#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MODE="${1:-test}"

print_usage() {
  cat <<'EOF'
Usage:
  ./make.sh [command]

Commands:
  test                     Run JVM unit tests and connected Compose UI tests
  backup-restore-regression
                           Real-device Auto Backup/Restore regression check --
                           slow (several minutes), needs one connected
                           device/emulator. Run by hand before a release, not
                           on every commit. See scripts/backup_restore_regression.sh
                           for what it covers and why.
  help                     Show this help

Examples:
  ./make.sh
  ./make.sh test
  ./make.sh backup-restore-regression
EOF
}

run_unit_tests() {
  "${SCRIPT_DIR}/gradlew" --no-daemon \
    :app:testDebugUnitTest \
    :app:connectedDebugAndroidTest
}

case "${MODE}" in
  test|"")
    run_unit_tests
    ;;
  backup-restore-regression)
    shift || true
    "${SCRIPT_DIR}/scripts/backup_restore_regression.sh" "$@"
    ;;
  help|-h|--help)
    print_usage
    ;;
  *)
    echo "Unknown command: ${MODE}" >&2
    echo >&2
    print_usage >&2
    exit 1
    ;;
esac
