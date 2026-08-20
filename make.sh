#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MODE="${1:-test}"

print_usage() {
  cat <<'EOF'
Usage:
  ./make.sh [command]

Commands:
  test      Run the Kotlin JVM unit tests
  help      Show this help

Examples:
  ./make.sh
  ./make.sh test
EOF
}

run_unit_tests() {
  "${SCRIPT_DIR}/gradlew" --no-daemon :app:testDebugUnitTest
}

case "${MODE}" in
  test|"")
    run_unit_tests
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
