#!/usr/bin/env bash
# Builds Holder's debug APK once, then installs and launches it on every connected Android
# phone or running emulator `adb` can see -- for quick manual compatibility testing across
# multiple real devices without repeating the build+install+launch cycle by hand for each one.
# See README.md's Local Development section for the one-line summary.
#
# Usage: scripts/deploy-all.sh

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
PACKAGE="team.holder.android.debug"
APK_PATH="${REPO_ROOT}/app/build/outputs/apk/debug/app-debug.apk"

log() { echo "[deploy-all] $*"; }
warn() { echo "[deploy-all] $*" >&2; }

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  cat <<'EOF'
Usage: scripts/deploy-all.sh

Builds Holder's debug APK once, then installs and launches it on every
connected Android phone or emulator `adb devices` reports as ready (state
"device"). Targets that are offline, unauthorized, or otherwise not ready
are skipped and reported, not treated as failures on their own.
EOF
  exit 0
fi

command -v adb >/dev/null 2>&1 || {
  warn "adb not found on PATH. Install Android platform-tools (or open a shell where Android Studio's SDK platform-tools directory is on PATH) and try again."
  exit 1
}

log "Building debug APK (./gradlew :app:assembleDebug)..."
"${REPO_ROOT}/gradlew" -p "${REPO_ROOT}" :app:assembleDebug -q

if [[ ! -f "${APK_PATH}" ]]; then
  warn "Build succeeded but the APK is missing at ${APK_PATH} -- check the assembleDebug output path."
  exit 1
fi

# adb devices' plain (non -l) output is one "<serial>\t<state>" line per connected target, e.g.:
#   List of devices attached
#   emulator-5554   device
#   ZY322PVNGV      offline
# Physical phones and emulators are indistinguishable here beyond their serial's shape, and
# nothing below treats them differently. Filtering on a literal tab (rather than e.g. `tail -n
# +2`) skips the header line correctly even when adb also prints "* daemon started successfully
# *"-style banner lines first, which happens the first time it starts its background server.
serials=()
states=()
while IFS=$'\t' read -r serial state; do
  [[ -n "${serial}" ]] || continue
  serials+=("${serial}")
  states+=("${state}")
done < <(adb devices | grep $'\t' || true)

if [[ "${#serials[@]}" -eq 0 ]]; then
  warn "No Android devices or emulators are connected (adb devices found nothing)."
  warn "Connect a phone with USB debugging enabled, or start an emulator, then try again."
  exit 1
fi

results=()
usable_count=0
failure_count=0

for i in "${!serials[@]}"; do
  serial="${serials[$i]}"
  state="${states[$i]}"

  if [[ "${state}" != "device" ]]; then
    log "Skipping ${serial}: adb reports \"${state}\", not ready."
    results+=("${serial}: skipped (${state})")
    continue
  fi
  usable_count=$((usable_count + 1))

  log "Installing on ${serial}..."
  install_output="$(adb -s "${serial}" install -r -g "${APK_PATH}" 2>&1)" && install_ok=1 || install_ok=0
  if [[ "${install_ok}" -eq 0 ]]; then
    # -r keeps app data across a matching-signature reinstall (the common case: Holder's debug
    # builds all use the same checked-in debug keystore). If it still fails -- e.g. this device
    # previously got a debug build signed some other way -- fall back to a clean install so the
    # device still gets tested, just without preserved data. That matches "preserve app data
    # during replacement where practical": practical here, not guaranteed.
    warn "install -r failed on ${serial}, retrying as a clean install (app data will be reset): ${install_output}"
    adb -s "${serial}" uninstall "${PACKAGE}" >/dev/null 2>&1 || true
    install_output="$(adb -s "${serial}" install -g "${APK_PATH}" 2>&1)" && install_ok=1 || install_ok=0
  fi

  if [[ "${install_ok}" -eq 0 ]]; then
    warn "Install failed on ${serial}: ${install_output}"
    results+=("${serial}: install failed")
    failure_count=$((failure_count + 1))
    continue
  fi

  log "Launching Holder on ${serial}..."
  if adb -s "${serial}" shell monkey -p "${PACKAGE}" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; then
    results+=("${serial}: installed + launched")
  else
    warn "Installed on ${serial} but launch failed."
    results+=("${serial}: installed, launch failed")
    failure_count=$((failure_count + 1))
  fi
done

echo
log "Summary:"
for r in "${results[@]}"; do
  echo "  ${r}"
done

if [[ "${usable_count}" -eq 0 ]]; then
  warn "No usable devices (adb state \"device\") among the connected targets -- nothing was deployed."
  exit 1
fi

if [[ "${failure_count}" -gt 0 ]]; then
  exit 1
fi
