#!/usr/bin/env bash
# ABOUTME: Checks that ADR numbers under docs/adr/ are unique across the tree, main and every open pull request.
# ABOUTME: With --next it prints the next free number so two open branches never pick the same one (issue #171).
#
# Usage:
#   scripts/check-adr-numbers.sh [--pr N] [--require-prs]   exit 1 on a duplicate or on a clash with an open PR
#   scripts/check-adr-numbers.sh --next                     print the next free four digit number
#
# --pr N          the pull request this tree belongs to; it is skipped when scanning open pull requests
# --require-prs   fail instead of warning when open pull requests cannot be listed (used by CI)
#
# Open pull requests are read with `gh`, which needs network access and a token (GH_TOKEN in CI). Without
# them the check covers the tree and main only and says so.
set -euo pipefail

mode=check
self_pr=0
require_prs=0
while [ $# -gt 0 ]; do
  case "$1" in
    --next) mode=next ;;
    --pr) self_pr="$2"; shift ;;
    --require-prs) require_prs=1 ;;
    -h|--help) sed -n '4,13p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done

root=$(git rev-parse --show-toplevel)
cd "$root"
adr_dir=docs/adr

# Main is read from origin when it can be fetched, so the check sees merges that happened after this branch
# was created. The fetch is skipped silently when offline; the local ref is used instead.
git fetch -q origin "+refs/heads/main:refs/remotes/origin/main" 2>/dev/null || true
main_ref=$(git rev-parse --verify -q origin/main || git rev-parse --verify -q main || true)

on_main=""
if [ -n "$main_ref" ]; then
  on_main=$(git ls-tree --name-only "$main_ref" -- "$adr_dir/" | xargs -n1 basename | grep -E '^[0-9]{4}-' || true)
fi
in_tree=$(find "$adr_dir" -maxdepth 1 -name '[0-9][0-9][0-9][0-9]-*' -exec basename {} \; | sort)

# "<pr> <file>" for every ADR file touched by another open pull request that is not already on main.
in_prs=""
prs_available=0
if command -v gh >/dev/null 2>&1; then
  if listed=$(gh pr list --state open --limit 200 --json number,files --jq \
      ".[] | select(.number != $self_pr) | .number as \$n | .files[].path | select(test(\"^$adr_dir/[0-9]{4}-\")) | \"\(\$n) \(.[$(( ${#adr_dir} + 1 )):])\"" 2>/dev/null); then
    prs_available=1
    while read -r pr file; do
      [ -n "$file" ] || continue
      grep -qxF "$file" <<<"$on_main" || in_prs+="$pr $file"$'\n'
    done <<<"$listed"
  fi
fi
if [ "$prs_available" = 0 ]; then
  if [ "$require_prs" = 1 ]; then
    echo "Cannot list open pull requests (gh missing, unauthenticated or offline); set GH_TOKEN." >&2
    exit 2
  fi
  echo "note: open pull requests not checked (gh missing, unauthenticated or offline)" >&2
fi

if [ "$mode" = next ]; then
  highest=$(
    { printf '%s\n' "$on_main" "$in_tree"; cut -d' ' -f2- <<<"$in_prs"; } \
      | grep -oE '^[0-9]{4}' | sort -n | tail -1
  )
  printf '%04d\n' $((10#${highest:-0} + 1))
  exit 0
fi

fail=0
dups=$(printf '%s\n' "$in_tree" | grep -oE '^[0-9]{4}' | sort | uniq -d || true)
for n in $dups; do
  echo "Duplicate ADR number $n in $adr_dir:"
  printf '%s\n' "$in_tree" | grep "^$n-" | sed 's/^/  /'
  fail=1
done

# ADR files in this tree that main does not have yet, compared against the ones other open pull requests add.
new_here=$(comm -23 <(printf '%s\n' "$in_tree" | sort) <(printf '%s\n' "$on_main" | sort) | grep -E '^[0-9]{4}-' || true)
for file in $new_here; do
  n=${file:0:4}
  hits=$(awk -v n="$n" 'substr($2,1,4)==n {print "#"$1" ("$2")"}' <<<"$in_prs" || true)
  if [ -n "$hits" ]; then
    echo "ADR $n ($file) is also added by open pull request(s): $(tr '\n' ' ' <<<"$hits")"
    fail=1
  fi
done

if [ "$fail" = 0 ]; then
  scope="tree, main"
  [ "$prs_available" = 1 ] && scope="$scope, open pull requests"
  echo "ADR numbers are unique ($scope)."
fi
exit $fail
