#!/usr/bin/env bash
# Real-device regression check for the Android Auto Backup / Restore flow -- specifically the
# SnapshotProtection race fixed in commit 12181cf ("Guard against SnapshotWorker overwriting an
# unread inbound backup"). See that commit and git/backup/SnapshotProtection.kt's doc comment
# for the full story: a returning user's `restoreOfferShown` flag is itself carried over by
# Android's backup mechanism, so the automatic "we found a backup" offer silently declines to
# fire for exactly the user who has a real snapshot to recover, leaving the periodic
# SnapshotWorker free to clobber it before the manual "Restore from backup" button ever gets
# used -- unless the guard holds.
#
# This is NOT a test of Android's own backup/restore mechanism (bmgr, the local transport,
# package install) -- that's AOSP's problem, covered by their own CTS backup suite. It's a test
# of our own code and config using that real mechanism as the only way to produce the exact
# real-world state a JVM unit test structurally can't reach:
#   - backup_rules.xml/data_extraction_rules.xml actually exclude/include the right paths
#     against the real enforcement mechanism, not just read correctly as XML.
#   - MainActivity's cold-start ordering (capturing dataDirExistedBeforeInit before the first
#     HolderNative.initialize() call) survives future refactors.
#   - The empirical timing assumption the whole fix leans on -- WorkManager's first periodic
#     tick firing within seconds, no enforced initial delay -- still holds against whatever
#     WorkManager/OS version is actually running.
#
# Slow (several minutes) and mildly at the mercy of real scheduler timing, so this is meant to
# be run by hand before a release, not on every commit -- see ./make.sh for the fast suite that
# runs on every push. Needs exactly one connected device/emulator (or pass -s SERIAL) with a
# debuggable build, since it uses `adb shell run-as` to peek at app-private files.
#
# Usage: scripts/backup_restore_regression.sh [-s SERIAL] [--apk PATH] [--build]

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
PACKAGE="team.holder.android.debug"
APK_PATH="${REPO_ROOT}/app/build/outputs/apk/debug/app-debug.apk"
DO_BUILD=0
ADB_SERIAL=""

# How long to wait for a real SnapshotWorker tick to show up in logcat, and how long to keep
# polling the snapshot file's own content afterward for an unwanted change. WorkManager's first
# periodic tick fired within 1-150 seconds across this fix's own manual verification -- these
# ceilings give real headroom above that without making a failing run hang indefinitely.
WORKER_TICK_TIMEOUT_SECONDS="${WORKER_TICK_TIMEOUT_SECONDS:-90}"
SUSTAINED_WATCH_SECONDS="${SUSTAINED_WATCH_SECONDS:-180}"
POLL_INTERVAL_SECONDS="${POLL_INTERVAL_SECONDS:-5}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    -s) ADB_SERIAL="$2"; shift 2 ;;
    --apk) APK_PATH="$2"; shift 2 ;;
    --build) DO_BUILD=1; shift ;;
    -h|--help)
      cat <<'EOF'
Usage: scripts/backup_restore_regression.sh [-s SERIAL] [--apk PATH] [--build]

  -s SERIAL   Target this device/emulator (adb -s). Default: the only connected one.
  --apk PATH  Use this APK instead of app/build/outputs/apk/debug/app-debug.apk.
  --build     Run ./gradlew :app:assembleDebug first.
EOF
      exit 0
      ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

adb_() { if [[ -n "${ADB_SERIAL}" ]]; then adb -s "${ADB_SERIAL}" "$@"; else adb "$@"; fi; }
run_as() { adb_ shell run-as "${PACKAGE}" "$@"; }
log() { echo "[backup-restore-regression] $*"; }
fail() { echo "[backup-restore-regression] FAIL: $*" >&2; exit 1; }

# --- Preflight -----------------------------------------------------------------------------

if [[ -z "${ADB_SERIAL}" ]]; then
  device_count="$(adb devices | tail -n +2 | grep -c $'\tdevice$' || true)"
  if [[ "${device_count}" -ne 1 ]]; then
    fail "expected exactly one connected device/emulator, found ${device_count} -- pass -s SERIAL"
  fi
fi

if [[ "${DO_BUILD}" -eq 1 ]]; then
  log "Building debug APK..."
  "${REPO_ROOT}/gradlew" -p "${REPO_ROOT}" :app:assembleDebug -q
fi
[[ -f "${APK_PATH}" ]] || fail "APK not found at ${APK_PATH} (pass --build or --apk)"

# --- UI helpers ------------------------------------------------------------------------------
# uiautomator dump + exact bounds, not eyeballed/scaled screenshot coordinates -- see the
# project memory on tap mis-navigation for why. text_center/tap_text retry a few times to ride
# out Compose recomposition/animation delays.

DUMP_FILE="$(mktemp)"
FAKE_SNAPSHOT_LOCAL="$(mktemp)"
trap 'rm -f "${DUMP_FILE}" "${FAKE_SNAPSHOT_LOCAL}"' EXIT

dump_ui() {
  adb_ shell uiautomator dump /sdcard/backup_regression_dump.xml >/dev/null 2>&1
  adb_ pull /sdcard/backup_regression_dump.xml "${DUMP_FILE}" >/dev/null 2>&1
}

# Prints "cx cy" for the first node whose text or content-desc *contains* $1 as a substring
# (not an exact match -- e.g. "restored 1 cards" needs to match inside a longer sentence like
# "Backup Regression Project: restored 1 cards."), or nothing.
#
# Checks the two attributes independently within each whole node tag, not via a single
# alternation over the whole file -- a node commonly has an empty text="" *and* a real
# content-desc (or vice versa), e.g. the Settings icon is text="" content-desc="Settings".
# Alternating `(?:text|content-desc)="(...)"` against the raw file greedily grabs whichever
# attribute happens to appear first in that node's tag, silently discarding the other one --
# found the hard way when "Settings" (a content-desc, with an earlier empty text="") never
# matched.
text_center() {
  local needle="$1"
  python3 - "${DUMP_FILE}" "${needle}" <<'PY'
import re, sys
dump, needle = open(sys.argv[1], encoding="utf-8").read(), sys.argv[2]
for tag in re.finditer(r'<node[^>]*>', dump):
    node = tag.group(0)
    bounds_m = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
    if not bounds_m:
        continue
    text_m = re.search(r'text="([^"]*)"', node)
    desc_m = re.search(r'content-desc="([^"]*)"', node)
    values = [m.group(1) for m in (text_m, desc_m) if m]
    if any(needle in value for value in values):
        x1, y1, x2, y2 = map(int, bounds_m.groups())
        print(f"{(x1 + x2) // 2} {(y1 + y2) // 2}")
        break
PY
}

tap_text() {
  local needle="$1" attempts="${2:-10}"
  for _ in $(seq 1 "${attempts}"); do
    dump_ui
    local center
    center="$(text_center "${needle}")"
    if [[ -n "${center}" ]]; then
      # shellcheck disable=SC2086
      adb_ shell input tap ${center}
      return 0
    fi
    sleep 1
  done
  fail "never found tappable text \"${needle}\" on screen"
}

# Polls up to $2 one-second attempts (default 5) for $1 to appear anywhere on screen.
screen_has_text() {
  local needle="$1" attempts="${2:-5}"
  for _ in $(seq 1 "${attempts}"); do
    dump_ui
    [[ -n "$(text_center "${needle}")" ]] && return 0
    sleep 1
  done
  return 1
}

# --- Snapshot content helpers ------------------------------------------------------------

printf '%s\n' \
  '{"card_id":"regression-c1","project_id":"regression-p1","project_name":"Backup Regression Project","privacy_mode":"plain","title":"Regression Card","body":"Injected by backup_restore_regression.sh.","created_at":1000,"updated_at":1000}' \
  | gzip -c > "${FAKE_SNAPSHOT_LOCAL}"

on_device_snapshot_matches_fake() {
  local tmp
  tmp="$(mktemp)"
  if ! run_as cat files/backup/snapshot.jsonl.gz > "${tmp}" 2>/dev/null; then
    rm -f "${tmp}"
    return 1
  fi
  cmp -s "${tmp}" "${FAKE_SNAPSHOT_LOCAL}"
  local result=$?
  rm -f "${tmp}"
  return "${result}"
}

file_exists_on_device() { run_as ls "$1" >/dev/null 2>&1; }

# --- Phase 1: produce a realistic "already used the app once" backup -----------------------
# The scenario that actually matters: a RETURNING user, whose restoreOfferShown flag is already
# true from their very first-ever launch, long before "losing the phone" -- see
# SnapshotProtection's doc comment for why that's what makes the automatic offer unreliable and
# the periodic worker the real threat. Simulated here by genuinely going through a first launch
# (which sets that flag on its own, honestly) before ever introducing a snapshot to protect.

log "Phase 1: clean install, first launch (sets restoreOfferShown), inject a fake prior backup..."
adb_ uninstall "${PACKAGE}" >/dev/null 2>&1 || true
adb_ install -g "${APK_PATH}" >/dev/null
adb_ shell monkey -p "${PACKAGE}" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 3

# Deliberately NOT force-stopping here: Android excludes a force-stopped app from backup
# eligibility entirely (by design -- force-stop means "leave it alone"), which turns
# `bmgr backupnow` into a silent-looking no-op ("Backup is not allowed" on the one line that
# actually says so, buried under an overall "Success" for the job as a whole). Confirmed the
# hard way while writing this script. Leaving the app merely backgrounded is enough -- nothing
# else touches files/backup/snapshot.jsonl.gz again before backupnow runs, a few seconds later.
run_as mkdir -p files/backup files/local_state
# run-as only reliably preserves the app's data-dir cwd for a command it execs directly --
# piping through a nested `sh -c "... > path"` loses that (confirmed the hard way: it fails
# with a plain "No such file or directory" despite the directory existing). adb push to a
# world-readable staging path, then a direct run-as cp, sidesteps that entirely.
adb_ push "${FAKE_SNAPSHOT_LOCAL}" /data/local/tmp/backup_regression_fake_snapshot.jsonl.gz >/dev/null
run_as cp /data/local/tmp/backup_regression_fake_snapshot.jsonl.gz files/backup/snapshot.jsonl.gz
# Also seed a marker where SnapshotProtection's guard would live, so the upcoming backup
# actually contains something under files/local_state/ -- this is what makes the exclusion
# check below meaningful, rather than trivially true because there was never anything there.
run_as touch files/local_state/pending_restore_snapshot

log "Phase 1: bmgr backupnow..."
adb_ shell bmgr enable true >/dev/null
adb_ shell bmgr transport com.android.localtransport/.LocalTransport >/dev/null
backupnow_output="$(adb_ shell bmgr backupnow "${PACKAGE}")"
# Checking only the overall "Backup finished with result: Success" line is a trap -- it prints
# that even when the one line that actually matters says "Package ... with result: Backup is
# not allowed" for our specific package (confirmed the hard way: happens if the app was
# force-stopped since its last launch, among other reasons). Check the per-package line itself.
echo "${backupnow_output}" | grep -q "Package ${PACKAGE} with result: Success" \
  || fail "bmgr backupnow did not report success for ${PACKAGE}:"$'\n'"${backupnow_output}"

# --- Phase 2: the actual reinstall, and what Android's own restore delivered ----------------

log "Phase 2: uninstall + reinstall (real install-time restore, not a simulation)..."
adb_ uninstall "${PACKAGE}" >/dev/null
adb_ install -g "${APK_PATH}" >/dev/null

file_exists_on_device files/holder \
  && fail "files/holder/ should not exist before first launch -- exclusion did not hold, or something else populated it"
file_exists_on_device files/local_state/pending_restore_snapshot \
  && fail "files/local_state/ marker survived a restore -- its backup exclusion is broken"
on_device_snapshot_matches_fake \
  || fail "restored files/backup/snapshot.jsonl.gz does not match what was backed up -- inclusion/round-trip is broken"
log "Phase 2: files/holder/ correctly absent, files/local_state/ correctly excluded, snapshot round-tripped intact."

# --- Phase 3: launch into the real race window ----------------------------------------------

log "Phase 3: launching into the race window (guard should already be armed by MainActivity)..."
# Cleared *before* launch, not right before the poll loop below: WorkManager's periodic work
# fires its first tick promptly with no enforced initial delay (often well under 30s), and the
# uiautomator dump/pull round trips in the checks just below already burn several real seconds
# each. Clearing logcat after those checks risks wiping out the very tick this is trying to
# observe before the poll loop even starts watching for it -- found the hard way.
adb_ logcat -c
adb_ shell monkey -p "${PACKAGE}" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 3

file_exists_on_device files/local_state/pending_restore_snapshot \
  || fail "SnapshotProtection never armed -- MainActivity's cold-start detection is broken"

screen_has_text "Restore from backup" \
  && fail "the automatic restore offer fired, which isn't the scenario this script tests (restoreOfferShown should already be true) -- check RestoreOffer/MainActivity wiring"
log "Phase 3: automatic offer correctly silent (returning-user scenario); guard armed."

log "Phase 3: waiting up to ${WORKER_TICK_TIMEOUT_SECONDS}s for a real SnapshotWorker tick..."
elapsed=0
saw_tick=0
while [[ "${elapsed}" -lt "${WORKER_TICK_TIMEOUT_SECONDS}" ]]; do
  if adb_ logcat -d | grep -q "Worker result SUCCESS.*team.holder.android.git.backup.SnapshotWorker"; then
    saw_tick=1
    break
  fi
  sleep "${POLL_INTERVAL_SECONDS}"
  elapsed=$((elapsed + POLL_INTERVAL_SECONDS))
done
[[ "${saw_tick}" -eq 1 ]] || fail "never observed a SnapshotWorker tick within ${WORKER_TICK_TIMEOUT_SECONDS}s -- can't exercise the race at all"
log "Phase 3: observed a real SnapshotWorker tick."

log "Phase 3: watching the snapshot for up to ${SUSTAINED_WATCH_SECONDS}s more, across further ticks, for an unwanted overwrite..."
elapsed=0
while [[ "${elapsed}" -lt "${SUSTAINED_WATCH_SECONDS}" ]]; do
  on_device_snapshot_matches_fake \
    || fail "the snapshot changed while the guard should still be armed -- SnapshotProtection did not hold"
  sleep "${POLL_INTERVAL_SECONDS}"
  elapsed=$((elapsed + POLL_INTERVAL_SECONDS))
done
log "Phase 3: snapshot untouched through the full watch window. The guard held."

# --- Phase 4: the manual path still works, and disarms correctly ---------------------------

log "Phase 4: driving the manual Restore flow..."
tap_text "Settings"
tap_text "Backup"
tap_text "Load"
screen_has_text "Backup Regression Project" \
  || fail "Restore from backup screen did not show the expected project"
tap_text "Restore"
# "restored 1 cards" is a genuine but fleeting state: a successful restore immediately triggers
# the GitHubBackfillDialog (the just-restored project has no remote yet), which renders in its
# own window and -- confirmed the hard way -- uiautomator dump only captures the topmost
# window, so the results text underneath becomes invisible to it the instant that dialog
# appears. In practice the dialog is already up by the time any dump lands. Accept either as
# proof of success rather than racing a transition uiautomator can't reliably observe.
screen_has_text "restored 1 cards" 5 || screen_has_text "Keep your existing projects safe" 15 \
  || fail "restore did not report success"

file_exists_on_device files/local_state/pending_restore_snapshot \
  && fail "guard still armed after RestoreBackupScreen read the snapshot -- disarm did not fire"
log "Phase 4: restore succeeded and the guard correctly disarmed."

# Dismiss the GitHubBackfillDialog this always triggers on a fresh, remoteless restored project.
if screen_has_text "Not now"; then
  tap_text "Not now"
fi

log "PASS: backup/restore regression check completed successfully."
