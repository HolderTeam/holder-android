#!/usr/bin/env bash
# Starts every Android Virtual Device already configured on this machine that isn't already
# running. Companion to scripts/deploy-all.sh -- see README.md's Local Development section for
# the two-script workflow: run this once to bring your emulator set up, then
# scripts/deploy-all.sh as often as you like while developing.
#
# Usage: scripts/start-emulators.sh

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"

log() { echo "[start-emulators] $*"; }
warn() { echo "[start-emulators] $*" >&2; }

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  cat <<'EOF'
Usage: scripts/start-emulators.sh

Starts every configured Android Virtual Device that isn't already running
(discovered via `emulator -list-avds`). Never starts a second instance of
an AVD that's already running, and never creates, modifies, or deletes AVD
definitions -- it only starts them.
EOF
  exit 0
fi

# Same PATH check deploy-all uses for adb -- needed here to see which AVDs are already
# running, not to start anything itself.
command -v adb >/dev/null 2>&1 || {
  warn "adb not found on PATH. Install Android platform-tools (or open a shell where Android Studio's SDK platform-tools directory is on PATH) and try again."
  exit 1
}

# `emulator` isn't always on PATH the way `adb` commonly is -- it ships in its own SDK
# "Android Emulator" package, not platform-tools. Fall back to the same SDK location the
# project's own Gradle build already knows about (local.properties' sdk.dir, written by
# Android Studio -- see README.md) rather than inventing a separate setup mechanism.
find_emulator_bin() {
  if command -v emulator >/dev/null 2>&1; then
    command -v emulator
    return 0
  fi
  local sdk_dir=""
  if [[ -n "${ANDROID_HOME:-}" ]]; then
    sdk_dir="${ANDROID_HOME}"
  elif [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
    sdk_dir="${ANDROID_SDK_ROOT}"
  elif [[ -f "${REPO_ROOT}/local.properties" ]]; then
    sdk_dir="$(sed -n 's/^sdk\.dir=//p' "${REPO_ROOT}/local.properties" | tail -1)"
  fi
  if [[ -n "${sdk_dir}" && -x "${sdk_dir}/emulator/emulator" ]]; then
    echo "${sdk_dir}/emulator/emulator"
    return 0
  fi
  return 1
}

EMULATOR_BIN="$(find_emulator_bin)" || {
  warn "Could not find the 'emulator' tool on PATH, via \$ANDROID_HOME/\$ANDROID_SDK_ROOT, or via local.properties' sdk.dir."
  warn "Install the \"Android Emulator\" SDK package (Android Studio > SDK Manager > SDK Tools), or add it to PATH, then try again."
  exit 1
}

# Every AVD configured on this machine, one name per line -- purely local config discovery,
# same as `adb devices` is for deploy-all's target discovery. Does not start, stop, or touch
# any AVD definition.
configured_avds=()
while IFS= read -r name; do
  [[ -n "${name}" ]] || continue
  configured_avds+=("${name}")
done < <("${EMULATOR_BIN}" -list-avds)

if [[ "${#configured_avds[@]}" -eq 0 ]]; then
  warn "No AVDs are configured on this machine."
  warn "Create one in Android Studio's Device Manager (or via avdmanager create avd), then try again."
  exit 1
fi

# Which AVDs are already running: every attached emulator-* serial adb currently knows about
# (any state counts -- a booting/offline one is still a live process for that AVD, and
# starting a second instance of it would be exactly the duplicate this script must avoid),
# mapped back to its AVD name via `adb emu avd name`.
running_avds=()
while IFS=$'\t' read -r serial _state; do
  case "${serial}" in
    emulator-*) ;;
    *) continue ;;
  esac
  avd_name="$(adb -s "${serial}" emu avd name 2>/dev/null | head -n 1 | tr -d '\r')"
  [[ -n "${avd_name}" ]] && running_avds+=("${avd_name}")
done < <(adb devices | grep $'\t' || true)

is_running() {
  local needle="$1" candidate
  for candidate in "${running_avds[@]}"; do
    [[ "${candidate}" == "${needle}" ]] && return 0
  done
  return 1
}

started_count=0
for avd in "${configured_avds[@]}"; do
  if is_running "${avd}"; then
    log "Skipping ${avd}: already running."
    continue
  fi

  log_file="${TMPDIR:-/tmp}/holder-emulator-${avd}.log"
  log "Starting ${avd} in the background (log: ${log_file})..."
  nohup "${EMULATOR_BIN}" -avd "${avd}" >"${log_file}" 2>&1 < /dev/null &
  disown
  started_count=$((started_count + 1))
done

if [[ "${started_count}" -eq 0 ]]; then
  log "All configured AVDs are already running."
else
  log "Started ${started_count} emulator(s). They can take a minute or more to finish booting"
  log "before 'adb devices' reports them as ready -- check the log file(s) above if one doesn't"
  log "come up, then run scripts/deploy-all.sh once they do."
fi
