#!/usr/bin/env bash
# Builds the throwaway fixtures for the orchestration scenarios in validation.md.
# Usage: validation-fixtures.sh <empty-output-dir>. Each scenario gets repo/ (clone), origin.git, bin/gh and issue.md.
set -euo pipefail
out=${1:?usage: validation-fixtures.sh <empty-output-dir>}
if [ -e "$out" ] && [ -n "$(ls -A "$out" 2>/dev/null)" ]; then echo "error: $out exists and is not empty" >&2; exit 1; fi
mkdir -p "$out"
out=$(cd "$out" && pwd)
g() { git -c user.name=fixture -c user.email=fixture@example.invalid "$@"; }

issue_common() { cat <<'EOF'
Parent: #1 (epic: small numeric CLI).

## Background

`stats.sh mean` exists. Add a `max` subcommand next to it.

## Acceptance criteria

- [ ] AC1: `./stats.sh max <n1> <n2> ...` prints the largest number and exits 0. Numbers may be integers or decimals and may be negative.
- [ ] AC2: With no numbers, `./stats.sh max` prints `error: no numbers` to stderr and exits 2.
- [ ] AC3: Any non-numeric argument prints `error: not a number: <arg>` to stderr and exits 2.
- [ ] AC4: `tests/run.sh` covers AC1-AC3, still covers `mean`, and passes.
EOF
}

ac5_two_decimals() { echo '- [ ] AC5: Output uses two decimals, like `mean` (`./stats.sh max -1 2.5` prints `2.50`).'; }
verification() { printf '\n## Verification\n\nRun `tests/run.sh` from the repository root.\n'; }

issue_clear() { issue_common; ac5_two_decimals; verification; }

issue_ambiguous() { issue_common; cat <<'EOF'
- [ ] AC5: `max` prints the winning argument exactly as it was typed, so scripts can reuse it (`./stats.sh max 2 10.0` prints `10.0`).
- [ ] AC6: All `stats.sh` output uses two decimals, like `mean`.

## Verification

Run `tests/run.sh` from the repository root.
EOF
}

issue_blocked() { issue_common; ac5_two_decimals; cat <<'EOF'
- [ ] AC6: `tests/golden.sh` passes: it compares `max` output with the reference outputs in the sibling repository `../stats-golden` (required; CI provides it).
EOF
  verification
}

# A fake gh: serves `issue view` from issue.md, answers `repo view`, `pr list` and `pr view`, and records every call.
fake_gh() { # $1 scenario dir, $2 issue number
  mkdir -p "$1/bin"
  cat >"$1/bin/gh" <<EOF
#!/usr/bin/env bash
dir=$1
number=$2
EOF
  cat >>"$1/bin/gh" <<'EOF'
printf '%s\n' "$*" >>"$dir/gh-calls.log"
case "$1 $2" in
  "repo view") echo '{"nameWithOwner":"fixture/stats"}' ;;
  "issue view")
    [ "$3" = "$number" ] || { echo "issue $3 not found" >&2; exit 1; }
    if [[ " $* " == *" --json "* ]]; then
      jq -n --arg body "$(cat "$dir/issue.md")" --argjson n "$number" \
        '{number: $n, title: "Add a stats.sh max command", state: "OPEN", body: $body, comments: [], labels: [], url: "https://example.invalid/fixture/stats/issues/\($n)"}'
    else
      printf 'title:\tAdd a stats.sh max command\nstate:\tOPEN\nnumber:\t%s\n--\n' "$number"; cat "$dir/issue.md"
    fi ;;
  "pr list") echo '[]' ;;
  "pr create")
    echo "https://example.invalid/fixture/stats/pull/99"
    printf '%s\0' "$@" >"$dir/pr-create.args" ;;
  "pr view") [ -f "$dir/pr-create.args" ] && echo '{"number":99,"state":"OPEN"}' || { echo "no pull requests found" >&2; exit 1; } ;;
  *) echo "fake gh: unsupported: $*" >&2; exit 1 ;;
esac
EOF
  chmod +x "$1/bin/gh"
}

base() { # $1 scenario dir, $2 issue function
  local d=$1
  mkdir -p "$d"
  "$2" >"$d/issue.md"
  fake_gh "$d" 8
  g init -q --bare -b main "$d/origin.git"
  g clone -q "$d/origin.git" "$d/repo" 2>/dev/null
  mkdir -p "$d/repo/tests"
  cd "$d/repo"
  printf '# stats\n\nTiny numeric CLI used as an orchestration fixture.\n' >README.md
  cat >stats.sh <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
cmd=${1:-}; shift || true
[ "$cmd" = mean ] || { echo "usage: stats.sh mean <numbers...>" >&2; exit 64; }
[ $# -gt 0 ] || { echo "error: no numbers" >&2; exit 2; }
for a in "$@"; do [[ $a =~ ^-?[0123456789]+([.][0123456789]+)?$ ]] || { echo "error: not a number: $a" >&2; exit 2; }; done
printf '%s\n' "$@" | LC_ALL=C awk '{ s += $1; n++ } END { printf "%.2f\n", s / n }'
EOF
  chmod +x stats.sh
  cat >tests/run.sh <<'EOF'
#!/usr/bin/env bash
# Minimal test runner: each case is `check <name> <want-status> <want-stdout> <want-stderr-substring> -- args...`.
set -u
cd "$(dirname "$0")/.."
fail=0
err=$(mktemp)
check() {
  local name=$1 ws=$2 wo=$3 we=$4; shift 5
  local o e s=0
  o=$(./stats.sh "$@" 2>"$err") || s=$?
  e=$(cat "$err")
  if [ "$s" = "$ws" ] && [ "$o" = "$wo" ] && { [ -z "$we" ] || grep -qF -- "$we" <<<"$e"; }; then echo "ok   $name"; else echo "FAIL $name: status=$s out='$o' err='$e'"; fail=1; fi
}
[ -f tests/cases.sh ] && . tests/cases.sh
rm -f "$err"
exit $fail
EOF
  chmod +x tests/run.sh
  cat >tests/cases.sh <<'EOF'
check mean-ints 0 "2.00" "" -- mean 1 2 3
check mean-neg-dec 0 "0.75" "" -- mean -1 2.5
check mean-round 0 "0.67" "" -- mean 0 1 1
check mean-empty 2 "" "error: no numbers" -- mean
check mean-non-numeric 2 "" "error: not a number: x" -- mean 1 x
EOF
  g add -A
  g commit -qm "stats.sh mean"
  g push -q origin main 2>/dev/null
  g checkout -q -b 8-add-max
}

# A first attempt at #8 with a planted defect: the maximum starts at 0, so all-negative input prints 0.00.
first_attempt_with_defect() {
  cat >stats.sh <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
cmd=${1:-}; shift || true
case "$cmd" in mean|max) ;; *) echo "usage: stats.sh mean|max <numbers...>" >&2; exit 64 ;; esac
[ $# -gt 0 ] || { echo "error: no numbers" >&2; exit 2; }
for a in "$@"; do [[ $a =~ ^-?[0123456789]+([.][0123456789]+)?$ ]] || { echo "error: not a number: $a" >&2; exit 2; }; done
if [ "$cmd" = mean ]; then
  printf '%s\n' "$@" | LC_ALL=C awk '{ s += $1; n++ } END { printf "%.2f\n", s / n }'
else
  printf '%s\n' "$@" | LC_ALL=C awk 'BEGIN { m = 0 } { if ($1 > m) m = $1 } END { printf "%.2f\n", m }'
fi
EOF
  cat >>tests/cases.sh <<'EOF'
check max-ints 0 "3.00" "" -- max 1 3 2
check max-dec 0 "2.50" "" -- max 1 2.5
check max-empty 2 "" "error: no numbers" -- max
check max-non-numeric 2 "" "error: not a number: x" -- max 1 x
EOF
}

( base "$out/pass" issue_clear )
( base "$out/defect" issue_clear; first_attempt_with_defect )
( base "$out/ambiguous" issue_ambiguous )
( base "$out/blocked" issue_blocked )
( base "$out/no-reviewer" issue_clear )
for d in "$out"/*; do echo "$(basename "$d"): main=$(git -C "$d/repo" rev-parse --short main) tree=$(git -C "$d/repo" rev-parse --short 'HEAD^{tree}') dirty=$(git -C "$d/repo" status --porcelain | wc -l)"; done
