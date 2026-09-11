#!/usr/bin/env bash

# Stress-test Android/native concurrency.
#
# Repeatedly hammers the card pager, triggers rapid card writes, and re-enters
# the card view to provoke overlapping HolderNative calls on Dispatchers.IO.
# Intended to catch native crashes, races, and Git repository corruption,
# particularly around concurrent holder-core access.
#
# The script records the app PID and Android native-crash state before and
# after the run. A disappearing process, changed PID after a crash/restart,
# or new native crash tombstone is considered a failure.
#
# Usage:
#   ./stress-android-native-concurrency.sh <adb-device-id> [rounds]
#
# Default: 20 rounds.
# Requires adb and a debuggable team.holder.android.debug installation.
#
# What does it do?
# * 2,800 rapid alternating pager gestures designed to provoke overlapping card loads;
# * repeated writes mixed into that workload;
# * backing out and cold re-entering, which causes fresh list/content work;
# * PID checking every round, so a native crash/restart can't quietly pass;
# * crash-buffer and DropBox checking at the end.

set -u
D="$1"; ROUNDS="${2:-20}"; PKG=team.holder.android.debug
T() { adb -s "$D" shell input tap "$1" "$2"; }
K() { adb -s "$D" shell input keyevent "$1"; }
alive() { adb -s "$D" shell pidof "$PKG" | tr -d '\r'; }

echo "### $D  rounds=$ROUNDS  $(date +%H:%M:%S)"
adb -s "$D" logcat -b crash -c
before=$(adb -s "$D" shell dumpsys dropbox --print 2>/dev/null | grep -cE "^20.* data_app_native_crash")
adb -s "$D" shell am force-stop "$PKG"
adb -s "$D" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 3
T 240 572; sleep 2          # Home
T 240 605; sleep 2          # first card -> pager
start_pid=$(alive); echo "pager pid $start_pid"

gone=0
for r in $(seq 1 "$ROUNDS"); do
  p=$(alive); [ -z "$p" ] && { echo "!!! round $r: PROCESS GONE"; gone=1; break; }
  printf 'r%s ' "$r"
  # burst of page flips -> overlapping getCardContent on Dispatchers.IO
  adb -s "$D" shell 'for i in $(seq 1 70); do input swipe 990 1200 100 1200 28; input swipe 100 1200 990 1200 28; done'
  # rapid child-card creates (writes) racing the v- the reported wikilink-create crash shape
  for c in 1 2 3; do adb -s "$D" shell input tap 632 2277; done
  adb -s "$D" shell 'for i in $(seq 1 15); do inpu done'
  K 4; K 4; sleep 1
  # cold re-entry: listCards + getCardContent + op
  T 240 572; sleep 1; T 240 605; sleep 1
done
echo

p=$(alive); after=$(adb -s "$D" shell dumpsys dropbox --print 2>/dev/null | grep -cE "^20.* data_app_native_crash")
echo "### $D RESULT $(date +%H:%M:%S)"
echo "start_pid=$start_pid  end_pid=${p:-GONE}  (same process = no crash restart)"
echo "native-crash tombstones: before=$before afte))"
echo "--- crash buffer ---"
adb -s "$D" logcat -b crash -d 2>/dev/null | grep do|>>> $PKG|git_repository_free|CardStore|open_or_in
it" | head -30
[ "$gone" = 1 ] && exit 1 || exit 0
