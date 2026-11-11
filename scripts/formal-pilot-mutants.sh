#!/usr/bin/env bash
# ABOUTME: Shows that the formal pilot of issue #202 can fail: applies each scratch break of the coordinator to a copy
# ABOUTME: of this checkout and requires `formalPilotCheck` to report the property that break targets.
#
# Usage:
#   scripts/formal-pilot-mutants.sh [--keep] [mutant-name ...]   default: the unmodified baseline, then every mutant
#
# --keep   keep the temporary copy and print its path (for inspecting a mutant's report)
#
# The checkout itself is never edited. Tracked and untracked, not ignored files are copied to a temporary
# directory; each patch from docs/experiments/formal-pilot-aeon-failure-semantics-v1/mutants/ is applied there,
# checked with `formalPilotCheck --tests '*everyScheduleWithinTheBounds*'` at the default bound, and reverted.
# Exit 0 when the baseline passes and every mutant fails on its target property; 1 otherwise. Other
# properties a mutant also violates are listed (a stuck run, for example, also changes the outcome).
set -euo pipefail

keep=0
selected=()
while [ $# -gt 0 ]; do
  case "$1" in
    --keep) keep=1 ;;
    -h|--help) sed -n '4,14p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*) echo "unknown argument: $1" >&2; exit 2 ;;
    *) selected+=("$1") ;;
  esac
  shift
done

root=$(git rev-parse --show-toplevel)
mutant_dir=docs/experiments/formal-pilot-aeon-failure-semantics-v1/mutants

# Mutant name and the property it must violate.
declare -A expected=(
  [m1-first-completed-failure]=MATCHES_SEQUENTIAL_ORACLE
  [m2-no-sibling-cancel]=NO_RUNNABLE_TASK_AFTER_END
  [m3-unbounded-window]=BOUNDED_IN_ORDER_SUBMISSION
  [m4-reversed-suppressed]=SUPPRESSES_COMPLETED_LATER_FAILURES
  [m5-lost-refill]=NO_WAIT_WITHOUT_PENDING_WORK
  [m6-no-suppressed]=SUPPRESSES_COMPLETED_LATER_FAILURES
)
held=(MATCHES_SEQUENTIAL_ORACLE SUPPRESSES_COMPLETED_LATER_FAILURES BOUNDED_IN_ORDER_SUBMISSION
      NO_RUNNABLE_TASK_AFTER_END NO_WAIT_WITHOUT_PENDING_WORK)
if [ ${#selected[@]} -eq 0 ]; then
  selected=(m1-first-completed-failure m2-no-sibling-cancel m3-unbounded-window m4-reversed-suppressed m5-lost-refill
            m6-no-suppressed)
fi

work=$(mktemp -d)
copy="$work/monada-neuron"
cleanup() { if [ "$keep" -eq 0 ]; then rm -rf "$work"; else echo "kept: $copy"; fi; }
trap cleanup EXIT INT TERM

mkdir -p "$copy"
# Tracked files deleted in the working tree are still listed by ls-files; copy only paths that exist.
(cd "$root" && git ls-files -co --exclude-standard -z | grep -zv '^\.claude/' \
  | while IFS= read -r -d '' f; do if [ -e "$f" ]; then printf '%s\0' "$f"; fi; done \
  | xargs -0 cp --parents -t "$copy")
(cd "$copy" && git init -q && git add -A && git -c user.name=pilot -c user.email=pilot@localhost commit -qm base)

report="$copy/build/reports/formal-pilot/exhaustive-max-inputs-4.txt"
# Sets status to pass, fail, or noreport (the build or the test JVM failed before writing a report).
check() {
  rm -f "$report"
  (cd "$copy" && ./gradlew -q formalPilotCheck --tests '*everyScheduleWithinTheBounds*' >"$work/last.log" 2>&1) \
    && status=pass || status=fail
  if [ ! -f "$report" ]; then
    status=noreport
    echo "no report written; last lines of the Gradle output:" >&2
    tail -40 "$work/last.log" >&2
  fi
}
violated_in_report() {
  if [ "$status" = noreport ]; then return 0; fi
  grep -oE '^[A-Z_]+: VIOLATED' "$report" | cut -d: -f1 | tr '\n' ' ' || true
}

failed=0
check
violated=$(violated_in_report)
if [ "$status" = pass ] && [ -z "$violated" ]; then
  echo "baseline: PASS (no property violated)"
else
  echo "baseline: FAIL (status=$status violated=[$violated])"
  failed=1
fi

for name in "${selected[@]}"; do
  patch="$root/$mutant_dir/$name.patch"
  want=${expected[$name]:-}
  if [ -z "$want" ] || [ ! -f "$patch" ]; then
    echo "$name: unknown mutant" >&2
    failed=1
    continue
  fi
  if ! (cd "$copy" && git apply "$patch"); then
    echo "$name: NOT KILLED (patch does not apply)"
    failed=1
    continue
  fi
  check
  if [ "$status" = noreport ]; then
    echo "$name: NOT KILLED (no report: the build or the test JVM failed)"
    failed=1
    (cd "$copy" && git checkout -q -- .)
    continue
  fi
  violated=$(violated_in_report)
  counts=$(for p in "${held[@]}"; do grep -E "^$p: " "$report" | sed -E 's/^([A-Z_]+): .*violating schedules: ([0-9]+).*/\1=\2/'; done | tr '\n' ' ')
  if [ "$status" = fail ] && [[ " $violated " == *" $want "* ]]; then
    echo "$name: KILLED by $want (violated: $(echo "$violated" | xargs); $counts)"
  else
    echo "$name: NOT KILLED as expected (status=$status expected=$want violated=[$violated])"
    failed=1
  fi
  grep -A14 "^smallest counterexample, $want " "$report" | sed 's/^/    /' || true
  cp "$report" "$work/$name.txt"
  (cd "$copy" && git checkout -q -- .)
done
exit "$failed"
