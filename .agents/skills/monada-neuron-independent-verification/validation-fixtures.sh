#!/usr/bin/env bash
# Builds the throwaway fixture repositories for the scenarios in validation.md (round 4 versions).
# Usage: validation-fixtures.sh <empty-output-dir>. Each fixture has `main` (base) and `topic` (snapshot).
set -euo pipefail
out=$1
rm -rf "$out"; mkdir -p "$out"
g() { git -c user.name=fixture -c user.email=fixture@example.invalid "$@"; }

issue() { cat <<'EOF'
# Issue #7: Add a `stats.sh mean` command

Parent: #1 (epic: small numeric CLI).

## Acceptance criteria

- [ ] AC1: `./stats.sh mean <n1> <n2> ...` prints the arithmetic mean rounded to two decimals and exits 0. Numbers may be integers or decimals and may be negative (e.g. `./stats.sh mean -1 2.5` prints `0.75`).
- [ ] AC2: With no numbers, it prints `error: no numbers` to stderr and exits 2.
- [ ] AC3: Any non-numeric argument prints `error: not a number: <arg>` to stderr and exits 2.
- [ ] AC4: `tests/run.sh` covers AC1-AC3 and passes.

## Verification

Run `tests/run.sh` from the repository root.
EOF
}

base() { # $1 dir
  mkdir -p "$1/tests"; cd "$1"; g init -q -b main
  issue > ISSUE.md
  printf '# stats\n\nTiny numeric CLI used as a verification fixture.\n' > README.md
  cat > tests/run.sh <<'EOF'
#!/usr/bin/env bash
# Minimal test runner: each case is `check <name> <want-status> <want-stdout> <want-stderr-substring> -- args...`.
set -u
cd "$(dirname "$0")/.."
fail=0
check() {
  local name=$1 ws=$2 wo=$3 we=$4; shift 5
  local o e s=0
  o=$(./stats.sh "$@" 2>/tmp/stats-err.$$) || s=$?
  e=$(cat /tmp/stats-err.$$); rm -f /tmp/stats-err.$$
  if [ "$s" = "$ws" ] && [ "$o" = "$wo" ] && { [ -z "$we" ] || grep -qF -- "$we" <<<"$e"; }; then echo "ok   $name"; else echo "FAIL $name: status=$s out='$o' err='$e'"; fail=1; fi
}
[ -f tests/cases.sh ] && . tests/cases.sh
exit $fail
EOF
  chmod +x tests/run.sh
  g add -A; g commit -qm "base: test runner"
  g checkout -q -b topic
}

validate_full='for a in "$@"; do [[ $a =~ ^-?[0123456789]+([.][0123456789]+)?$ ]] || { echo "error: not a number: $a" >&2; exit 2; }; done'
validate_intonly='for a in "$@"; do [[ $a =~ ^[0-9]+$ ]] || { echo "error: not a number: $a" >&2; exit 2; }; done'

stats() { # $1 validation line (may be empty)
  cat > stats.sh <<EOF
#!/usr/bin/env bash
set -euo pipefail
cmd=\${1:-}; shift || true
[ "\$cmd" = mean ] || { echo "usage: stats.sh mean <numbers...>" >&2; exit 64; }
[ \$# -gt 0 ] || { echo "error: no numbers" >&2; exit 2; }
$1
printf '%s\n' "\$@" | LC_ALL=C awk '{ s += \$1; n++ } END { printf "%.2f\n", s / n }'
EOF
  chmod +x stats.sh
}

cases_full='check mean-ints 0 "2.00" "" -- mean 1 2 3
check mean-neg-dec 0 "0.75" "" -- mean -1 2.5
check mean-round 0 "0.67" "" -- mean 0 1 1
check mean-round-neg 0 "-0.67" "" -- mean 0 -1 -1
check empty 2 "" "error: no numbers" -- mean
check non-numeric 2 "" "error: not a number: x" -- mean 1 x'
cases_posint='check mean-ints 0 "2.00" "" -- mean 1 2 3
check mean-single 0 "5.00" "" -- mean 5
check empty 2 "" "error: no numbers" -- mean
check non-numeric 2 "" "error: not a number: x" -- mean 1 x'
cases_noac3='check mean-ints 0 "2.00" "" -- mean 1 2 3
check mean-neg-dec 0 "0.75" "" -- mean -1 2.5
check empty 2 "" "error: no numbers" -- mean'

# 1 pass
( base "$out/pass"; stats "$validate_full"; printf '%s\n' "$cases_full" > tests/cases.sh; g add -A; g commit -qm "Add stats.sh mean (closes #7)" )
# 2 defect: integer-only validation rejects negatives/decimals; tests only use positive integers
( base "$out/defect"; stats "$validate_intonly"; printf '%s\n' "$cases_posint" > tests/cases.sh; g add -A; g commit -qm "Add stats.sh mean (closes #7); all tests pass" )
# 3 missing requirement: no non-numeric validation, no AC3 test
( base "$out/missing"; stats ""; printf '%s\n' "$cases_noac3" > tests/cases.sh; g add -A; g commit -qm "Add stats.sh mean (closes #7)" )
# 4 blocked: correct code, but the issue's required check depends on a sibling golden repo that is absent
( base "$out/blocked"; stats "$validate_full"; printf '%s\n' "$cases_full" > tests/cases.sh
  cat >> ISSUE.md <<'EOF'
- [ ] AC5: `tests/golden.sh` passes: it compares output with the reference outputs in the sibling repository `../stats-golden` (required; CI provides it).
EOF
  cat > tests/golden.sh <<'EOF'
#!/usr/bin/env bash
set -eu
cd "$(dirname "$0")/.."
golden=../stats-golden
[ -d "$golden" ] || { echo "error: $golden not found; clone stats-golden next to this repository" >&2; exit 3; }
for f in "$golden"/cases/*.args; do diff <(./stats.sh mean $(cat "$f")) "${f%.args}.out"; done
EOF
  chmod +x tests/golden.sh
  g add -A; g commit -qm "Add stats.sh mean and golden check (closes #7)" )
# 5 corrected: defect fixture plus a fix commit with regression tests
( cp -r "$out/defect" "$out/corrected"; cd "$out/corrected"; stats "$validate_full"; printf '%s\n' "$cases_posint" > tests/cases.sh
  printf '%s\n' 'check mean-neg-dec 0 "0.75" "" -- mean -1 2.5' 'check mean-neg-only 0 "-2.00" "" -- mean -3 -1' 'check mean-round 0 "0.67" "" -- mean 0 1 1' 'check non-ascii-digit 2 "" "error: not a number: ١" -- mean 1 ١' >> tests/cases.sh
  g add -A; g commit -qm "Accept negative and decimal numbers (fixes F1)" )
for d in "$out"/*; do echo "$(basename "$d"): main=$(git -C "$d" rev-parse --short main) topic=$(git -C "$d" rev-parse --short HEAD)"; done
