#!/usr/bin/env bash
# Self-test for publication-gate.sh against a throwaway repository and hand-written result blocks.
# Covers PASS on the current snapshot, other statuses, stale and moved snapshots, commits on top and malformed blocks.
set -euo pipefail

gate=$(cd "$(dirname "$0")" && pwd)/publication-gate.sh
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
g() { git -c user.name=t -c user.email=t@example.invalid "$@"; }

git init -q -b main "$work/repo"
cd "$work/repo"
printf 'one\n' >a.txt
g add a.txt && g commit -qm base
git branch base
printf 'two\n' >>a.txt
g commit -qam topic
printf 'uncommitted\n' >b.txt

base=$(git rev-parse base)
head=$(git rev-parse HEAD)
tmp=$(mktemp -d)
git ls-files -z | GIT_INDEX_FILE=$tmp/index git update-index --add --remove -z --stdin
GIT_INDEX_FILE=$tmp/index git add -A
tree=$(GIT_INDEX_FILE=$tmp/index git write-tree)
rm -rf "$tmp"

result() { # file status identical ac2-status [base] [head] [tree]
  cat >"$1" <<EOF
## Independent verification result

**Status:** $2
**Issue:** #7 (parent #1)
**Snapshot:** base=${5:-$base} head=${6:-$head} tree=${7:-$tree} (identical at start and end: $3)
**Recheck of:** first run

### Criteria
| Id | Criterion (short) | Status | Evidence |
| --- | --- | --- | --- |
| AC1 | first | SATISFIED | C1 |
| AC2 | second, \`a \\| b\` | $4 | C2, \`x \\|\\| y\` |

### Findings
- none

### Checks run
- \`tests/run.sh\` (cwd repo) — PASS — 2/2.
EOF
}

failures=0
expect() { # name want-status want-text command...
  local name=$1 want_status=$2 want_text=$3
  shift 3
  local out status=0
  out=$("$@" 2>&1) || status=$?
  if [ "$status" != "$want_status" ] || ! grep -qF -- "$want_text" <<<"$out"; then
    echo "FAIL $name: status $status (want $want_status), output: $out"
    failures=$((failures + 1))
  else
    echo "ok   $name"
  fi
}

r=$work/results
mkdir "$r"
result "$r/pass" PASS yes SATISFIED
result "$r/needs" "NEEDS CHANGES" yes PARTIAL
result "$r/blocked" BLOCKED yes "NOT VERIFIABLE"
result "$r/changed" PASS no SATISFIED
result "$r/partial" PASS yes PARTIAL
result "$r/other-base" PASS yes SATISFIED "$head"
sed '/^\*\*Snapshot:/d' "$r/pass" >"$r/no-snapshot"
cat "$r/pass" "$r/pass" >"$r/two-blocks"
awk '/^\| AC/ { next } 1' "$r/pass" >"$r/no-rows"
awk '/^\| (Id|---) / { next } 1' "$r/partial" >"$r/no-header"
# Each half of the header check alone: with no separator the first data row would be skipped, so make it PARTIAL.
sed '/^| --- /d; s/^| AC1 | first | SATISFIED/| AC1 | first | PARTIAL/' "$r/pass" >"$r/no-separator"
sed 's/^| Id |/| ID |/' "$r/pass" >"$r/other-header"
sed 's/^- none$/- **F1 [blocking] [demonstrated]** `a.sh:1` — wrong output./' "$r/pass" >"$r/open-blocking"
sed 's/^- none$/- **F1 [blocking] [demonstrated] — resolved.** `a.sh:1` fixed./' "$r/pass" >"$r/resolved-blocking"
sed 's/end: yes)$/end: yes; matches the request)/; s/^\*\*Status:\*\* PASS$/**Status:** PASS (round 2)/' "$r/pass" >"$r/extra-text"
sed 's/^- none$/- **F1 [blocking] [demonstrated]** `a.sh:1` — unresolved variable reference./' "$r/pass" >"$r/unresolved-word"
sed 's/^\*\*Status:\*\* PASS$/**Status:** PASS | NEEDS CHANGES | BLOCKED/' "$r/pass" >"$r/template-status"
sed 's/^\*\*Status:\*\* PASS$/**Status:** PASS2/' "$r/pass" >"$r/glued-status"
sed 's/end: yes)$/end: yes\/no)/' "$r/pass" >"$r/template-identical"
sed 's/end: yes)$/end: yes-no)/' "$r/pass" >"$r/glued-identical"

expect "PASS on the uncommitted verified snapshot, before commit" 0 "ALLOW" "$gate" --before-commit "$r/pass" base
expect "publish refused until the verified tree is committed" 1 "commit the verified snapshot first" "$gate" "$r/pass" base
expect "NEEDS CHANGES is refused" 1 "status is 'NEEDS CHANGES'" "$gate" --before-commit "$r/needs" base
expect "BLOCKED is refused" 1 "status is 'BLOCKED'" "$gate" --before-commit "$r/blocked" base
expect "snapshot changed during review is refused" 1 "identical at start and end: no" "$gate" --before-commit "$r/changed" base
expect "PASS with a PARTIAL row is refused" 1 "criterion AC2 is 'PARTIAL'" "$gate" --before-commit "$r/partial" base
expect "verified against another base is refused" 1 "base moved" "$gate" --before-commit "$r/other-base" base
expect "missing Snapshot line is refused" 1 "Snapshot line is malformed" "$gate" --before-commit "$r/no-snapshot" base
expect "two result blocks are refused" 1 "exactly one result block, found 2" "$gate" --before-commit "$r/two-blocks" base
expect "empty criteria table is refused" 1 "criteria table is empty" "$gate" --before-commit "$r/no-rows" base
expect "criteria table without a header is refused" 1 "no header and separator row" "$gate" --before-commit "$r/no-header" base
expect "criteria table without a separator is refused" 1 "no header and separator row" "$gate" --before-commit "$r/no-separator" base
expect "criteria table with an unexpected header is refused" 1 "no header and separator row" "$gate" --before-commit "$r/other-header" base
expect "PASS with an open blocking finding is refused" 1 "open blocking finding: - **F1 [blocking]" "$gate" --before-commit "$r/open-blocking" base
expect "PASS with a resolved blocking finding is allowed" 0 "ALLOW" "$gate" --before-commit "$r/resolved-blocking" base
expect "extra text after the identical value and the status is allowed" 0 "ALLOW" "$gate" --before-commit "$r/extra-text" base
expect "'unresolved' in a blocking finding's text is not a resolution" 1 "open blocking finding" "$gate" --before-commit "$r/unresolved-word" base
expect "unfilled template status line is refused" 1 "not PASS" "$gate" --before-commit "$r/template-status" base
expect "status word with glued text is refused" 1 "status is 'PASS2'" "$gate" --before-commit "$r/glued-status" base
expect "unfilled template identical value is refused" 1 "Snapshot line is malformed" "$gate" --before-commit "$r/template-identical" base
expect "identical value with glued text is refused" 1 "Snapshot line is malformed" "$gate" --before-commit "$r/glued-identical" base
expect "missing result file is refused" 1 "result file not found" "$gate" "$r/absent" base
expect "unknown base ref is refused" 1 "does not resolve" "$gate" --before-commit "$r/pass" no-such-ref

printf 'edited after review\n' >>b.txt
expect "edit after PASS makes the result stale" 1 "stale result" "$gate" --before-commit "$r/pass" base
printf 'uncommitted\n' >b.txt
chmod +x b.txt
expect "mode change after PASS makes the result stale" 1 "stale result" "$gate" --before-commit "$r/pass" base
chmod -x b.txt

# Creating the issue branch in place keeps HEAD and the uncommitted verified changes.
git switch -q -c issue-in-place
expect "issue branch created in place before the commit keeps the snapshot" 0 "ALLOW" "$gate" --before-commit "$r/pass" base

g add b.txt && g commit -qm "commit verified snapshot"
expect "commit of exactly the verified tree may be published" 0 "ALLOW" "$gate" "$r/pass" base

# The sequence from the PR #194 review: commit the verified tree, then create the push branch from the base.
git switch -q -c issue-from-base base
expect "branch created from the base after the commit is refused" 1 "(stale result)" "$gate" "$r/pass" base
git switch -q issue-in-place
expect "before-commit mode refuses a HEAD that moved" 1 "is not the verified head" "$gate" --before-commit "$r/pass" base

verified_commit=$(git rev-parse HEAD)
# A merge whose first parent is the verified head and whose tree is the verified tree is still not one commit.
side=$(g commit-tree "$(git rev-parse 'base^{tree}')" -p base -m side)
g reset -q --hard "$(g commit-tree "$tree" -p "$head" -p "$side" -m merge)"
expect "a merge commit on top of the verified head is refused" 1 "neither the verified head nor a single commit" "$gate" "$r/pass" base
g reset -q --hard "$verified_commit"

g commit -q --allow-empty -m "second commit, same tree"
expect "two commits on top of the verified head are refused" 1 "neither the verified head nor a single commit" "$gate" "$r/pass" base
g reset -q --hard HEAD^

git checkout -q base
printf 'main moved\n' >c.txt
g add c.txt && g commit -qm "base moves"
git checkout -q -
expect "base that moved after the review is refused" 1 "base moved" "$gate" "$r/pass" base

[ "$failures" = 0 ] || { echo "$failures check(s) failed"; exit 1; }
echo "all checks passed"
