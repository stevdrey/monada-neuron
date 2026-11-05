#!/usr/bin/env bash
# Self-test for publication-gate.sh against a throwaway repository and hand-written result blocks.
# Every DENY in the gate has a case here. With --mutants, it also runs this self-test against copies of the gate
# that each disable or weaken one check, and fails if any copy still passes (a check no case depends on).
# GATE overrides the gate under test; the mutant run uses it.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
gate=${GATE:-$here/publication-gate.sh}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
g() { git -c user.name=t -c user.email=t@example.invalid "$@"; }
# A locale in which a bash range such as [0-9] also matches non-ASCII digits (en_US.UTF-8 with glibc does). The
# non-ASCII cases run the gate under it, so a gate that relied on ranges would fail them; empty if none is installed.
wide=
for l in en_US.UTF-8 de_DE.UTF-8 C.UTF-8; do
  if LC_ALL=$l bash -c '[[ ² =~ ^[0-9]$ ]]' 2>/dev/null; then wide=$l && break; fi
done

if [ "${1:-}" = --mutants ]; then
  # name@@exact text in the gate@@replacement. Each text must occur in the gate.
  mutants=(
    'no result-file check@@[ -f "$result" ] || deny@@[ -f "$result" ] || true'
    'no verifier-file check@@[ -f "$verifier" ] || deny@@[ -f "$verifier" ] || true'
    'no block count@@[ "$blocks" = 1 ] || deny@@[ "$blocks" = 1 ] || true'
    'no line count@@[ "$lines" = 1 ] || deny@@[ "$lines" = 1 ] || true'
    'case-sensitive line count@@grep -ci@@grep -c'
    'line anywhere in the file@@line=$(awk@@line=$(grep -i "<!-- verification" "$result" | head -n 1) # $(awk'
    'no line format check@@[[ $line =~ $re ]] || deny@@[[ $line =~ $re ]] || true'
    'unanchored line start@@re="^<!--@@re="<!--'
    'unanchored line end@@-->\$"@@-->"'
    'locale: hex as a range@@[0123456789abcdef]{40}|[0123456789abcdef]{64}@@[0-9a-f]{40}|[0-9a-f]{64}'
    "locale: numbers as ranges@@num='(0|[123456789][0123456789]*)'@@num='(0|[1-9][0-9]*)'"
    'any SHA length@@{40}|@@{40,64}|'
    "leading zeros allowed@@num='(0|[123456789][0123456789]*)'@@num='([0123456789]+)'"
    'no status check@@[ "$status" = PASS ] || deny@@[ "$status" = PASS ] || true'
    'no identical check@@[ "$identical" = yes ] || deny@@[ "$identical" = yes ] || true'
    'no empty-criteria check@@[ "$criteria" != 0 ] || deny@@[ "$criteria" != 0 ] || true'
    'no satisfied check@@[ "$satisfied" = "$criteria" ] || deny@@[ "$satisfied" = "$criteria" ] || true'
    'no open-blocking check@@[ "$blocking_open" = 0 ] || deny@@[ "$blocking_open" = 0 ] || true'
    'no snippet check@@grep -q write-tree <<<"$snippet" || deny@@grep -q write-tree <<<"$snippet" || true'
    'no base-ref check@@>/dev/null || deny@@>/dev/null || true'
    'no fingerprint check@@tree=$hex$ ]] || deny@@tree=$hex$ ]] || true'
    'no base check@@[ "$c_base" = "$v_base" ] || deny@@[ "$c_base" = "$v_base" ] || true'
    'no tree check@@[ "$c_tree" = "$v_tree" ] || deny@@[ "$c_tree" = "$v_tree" ] || true'
    'no before-commit head check@@[ "$c_head" = "$v_head" ] || deny@@[ "$c_head" = "$v_head" ] || true'
    'no HEAD tree check@@= "$v_tree" ] || deny "HEAD^@@= "$v_tree" ] || true "HEAD^'
    'merges on top allowed@@[ "$parents" = 2 ]@@[ "$parents" -ge 2 ]'
    'any parent allowed@@[ "$(git rev-parse HEAD^)" = "$v_head" ]@@true'
  )
  src=$(<"$here/publication-gate.sh")
  survivors=0 killed=0
  for m in "${mutants[@]}"; do
    name=${m%%@@*} rest=${m#*@@}
    old=${rest%%@@*} new=${rest#*@@}
    if [[ $name == locale:* && -z $wide ]]; then echo "skipped  $name (no installed locale widens [0-9])"; continue; fi
    [[ $src == *"$old"* ]] || { echo "MUTANT $name: text not found in the gate"; survivors=$((survivors + 1)); continue; }
    dir=$work/$RANDOM$RANDOM
    mkdir -p "$dir/monada-neuron-implement-issue" "$dir/monada-neuron-independent-verification"
    cp "$here/../monada-neuron-independent-verification/SKILL.md" "$dir/monada-neuron-independent-verification/"
    printf '%s\n' "${src/"$old"/"$new"}" >"$dir/monada-neuron-implement-issue/publication-gate.sh"
    chmod +x "$dir/monada-neuron-implement-issue/publication-gate.sh"
    if out=$(GATE=$dir/monada-neuron-implement-issue/publication-gate.sh "$here/publication-gate-check.sh" 2>&1); then
      echo "SURVIVED $name"
      survivors=$((survivors + 1))
    else
      killers=$(grep '^FAIL ' <<<"$out" | sed 's/^FAIL \([^:]*\):.*/\1/') || killers='(no case failed)'
      echo "killed   $name — $(wc -l <<<"$killers") case(s), first: $(head -n 1 <<<"$killers")"
      killed=$((killed + 1))
    fi
  done
  [ "$survivors" = 0 ] || { echo "$survivors mutant(s) survived"; exit 1; }
  echo "$killed of ${#mutants[@]} mutants killed, none survived"
  exit 0
fi

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

# result file [status] [identical] [criteria] [satisfied] [blocking_open] [base] [head] [tree]
# The Markdown always says PASS with every criterion SATISFIED: the gate must decide from the verification line alone.
result() {
  cat >"$1" <<EOF
## Independent verification result

<!-- verification v1 status=${2:-PASS} base=${7:-$base} head=${8:-$head} tree=${9:-$tree} identical=${3:-yes} criteria=${4:-2} satisfied=${5:-2} blocking_open=${6:-0} -->
**Status:** PASS
**Issue:** #7 (parent #1)
**Snapshot:** base=$base head=$head tree=$tree (identical at start and end: yes)
**Recheck of:** first run

### Criteria
| Id | Criterion (short) | Status | Evidence |
| --- | --- | --- | --- |
| AC1 | first | SATISFIED | C1 |
| AC2 | second, \`a \\| b\` | SATISFIED | C2 |

### Findings
- **F1 [non-blocking] [suspected] [open]** \`a.sh:1\` — slow on large input.

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
result "$r/pass"
result "$r/needs" NEEDS_CHANGES yes 2 1 1
result "$r/blocked" BLOCKED
result "$r/changed" PASS no
result "$r/no-criteria" PASS yes 0 0
result "$r/partial" PASS yes 2 1
result "$r/open-blocking" PASS yes 2 2 1
result "$r/other-base" PASS yes 2 2 0 "$head"
result "$r/long-sha" PASS yes 2 2 0 "${base}0"
result "$r/sha256" PASS yes 2 2 0 "$base$(printf '%024d' 0)"
result "$r/upper-sha" PASS yes 2 2 0 "$(tr a-f A-F <<<"$base")"
result "$r/leading-zero" PASS yes 02 02
result "$r/arabic-digit" PASS yes 2 2 "٠"
result "$r/superscript-sha" PASS yes 2 2 0 "${base%?}²"
result "$r/superscript" PASS yes "²" "²"
result "$r/plus" PASS yes 2 2 +0
line=$(grep '^<!-- verification ' "$r/pass")

# Markdown that disagrees with the line: the line decides in both directions.
sed 's/^\*\*Status:\*\* PASS$/**Status:** NEEDS CHANGES/; s/| SATISFIED |/| UNMET |/; s/\[non-blocking\]/[blocking]/' \
  "$r/pass" >"$r/markdown-says-fail"
# Placement, count and form of the line.
grep -v '^<!-- verification ' "$r/pass" >"$r/no-line"
cat "$r/pass" "$r/pass" >"$r/two-blocks"
grep -v '^## Independent verification result$' "$r/pass" >"$r/no-block"
awk -v l="$line" '{ print } /^### Findings$/ { print l }' "$r/pass" >"$r/duplicated-line"
awk -v l="$line" '{ print } /^### Findings$/ { print "  " l }' "$r/pass" >"$r/duplicated-indented"
sed '/^\*\*Status:/a <!--VERIFICATION v1 status=NEEDS_CHANGES -->' "$r/pass" >"$r/duplicated-other-case"
awk '/^<!-- verification / { held = $0; next } { print } /^\*\*Status:/ { print held }' "$r/pass" >"$r/line-after-status"
sed '/^## Independent verification result$/a not the line' "$r/pass" >"$r/text-before-line"
sed 's/^<!-- verification v1 /<!-- verification v2 /' "$r/pass" >"$r/other-version"
sed 's/ -->$/ --> trailing/' "$r/pass" >"$r/trailing-text"
sed 's/ -->$/ --> /' "$r/pass" >"$r/trailing-space"
sed 's/ -->$/ -->\r/' "$r/pass" >"$r/crlf"
sed 's/^<!-- verification /x <!-- verification /' "$r/pass" >"$r/leading-text"
sed 's/^<!-- verification /<!--  verification /' "$r/pass" >"$r/double-space"
sed 's/ status=PASS / status=NEEDS CHANGES /' "$r/pass" >"$r/spaced-status"
sed 's/ status=PASS / status=PASS|NEEDS_CHANGES|BLOCKED /' "$r/pass" >"$r/template-status"
sed 's/ status=PASS / status=pass /' "$r/pass" >"$r/lower-status"
sed 's/ identical=yes / identical=yes|no /' "$r/pass" >"$r/template-identical"
sed 's/ criteria=2 satisfied=2 / satisfied=2 criteria=2 /' "$r/pass" >"$r/reordered"
sed 's/ blocking_open=0 -->$/ -->/' "$r/pass" >"$r/missing-field"
sed -n '/^```markdown$/,/^```$/p' "$here/../monada-neuron-independent-verification/SKILL.md" | sed '1d;$d' >"$r/template"

ok() { expect "$1" 0 "ALLOW" "$gate" --before-commit "$r/$2" base; }
no() { expect "$1" 1 "$3" "$gate" --before-commit "$r/$2" base; }

ok "PASS on the uncommitted verified snapshot, before commit" pass
expect "publish refused until the verified tree is committed" 1 "commit the verified snapshot first" "$gate" "$r/pass" base
expect "missing result file is refused" 1 "result file not found" "$gate" "$r/absent" base
no "NEEDS_CHANGES is refused although the Markdown says PASS" needs "status is 'NEEDS_CHANGES', not PASS"
no "BLOCKED is refused" blocked "status is 'BLOCKED', not PASS"
no "snapshot changed during the review is refused" changed "identical=no"
no "zero criteria is refused" no-criteria "no criteria were verified"
no "PASS with an unsatisfied criterion is refused" partial "only 1 of 2 criteria are SATISFIED"
no "PASS with an open blocking finding is refused" open-blocking "1 blocking finding(s) still open"
no "verified against another base is refused" other-base "base moved"
ok "Markdown that reports failures does not override a PASS line" markdown-says-fail
no "missing verification line is refused" no-line "exactly one verification line, found 0"
no "two result blocks are refused" two-blocks "exactly one result block, found 2"
no "missing result heading is refused" no-block "exactly one result block, found 0"
no "duplicated verification line is refused" duplicated-line "exactly one verification line, found 2"
no "indented duplicate line is refused" duplicated-indented "exactly one verification line, found 2"
no "duplicate line in another case is refused" duplicated-other-case "exactly one verification line, found 2"
no "line after the Status line is refused" line-after-status "missing after the result heading or malformed"
no "text between heading and line is refused" text-before-line "missing after the result heading or malformed"
no "unknown contract version is refused" other-version "malformed"
no "text after the line is refused" trailing-text "malformed"
no "trailing space is refused" trailing-space "malformed"
no "CRLF line ending is refused" crlf "malformed"
no "text before the comment is refused" leading-text "malformed"
no "doubled space is refused" double-space "malformed"
no "status with a space is refused" spaced-status "malformed"
no "unfilled template status is refused" template-status "malformed"
no "lower-case status is refused" lower-status "malformed"
no "unfilled template identical value is refused" template-identical "malformed"
no "reordered fields are refused" reordered "malformed"
no "missing field is refused" missing-field "malformed"
no "verifier's template block is refused" template "malformed"
no "41-character SHA is refused" long-sha "malformed"
no "upper-case SHA is refused" upper-sha "malformed"
no "64-character SHA is accepted as a SHA and compared" sha256 "base moved"
no "number with a leading zero is refused" leading-zero "malformed"
[ -n "$wide" ] || echo "note: no installed locale widens [0-9]; the non-ASCII cases run in the current locale"
for c in arabic-digit superscript superscript-sha; do
  expect "non-ASCII digit is refused ($c, ${wide:-current locale})" 1 "malformed" \
    env ${wide:+"LC_ALL=$wide"} "$gate" --before-commit "$r/$c" base
done
no "signed number is refused" plus "malformed"
expect "unknown base ref is refused" 1 "does not resolve" "$gate" --before-commit "$r/pass" no-such-ref

# The gate needs the verifier's SKILL.md next to its own directory and a usable fingerprint snippet in it.
lone=$work/lone/monada-neuron-implement-issue
mkdir -p "$lone" "$work/broken/monada-neuron-implement-issue" "$work/broken/monada-neuron-independent-verification" \
  "$work/garbled/monada-neuron-implement-issue" "$work/garbled/monada-neuron-independent-verification"
cp "$gate" "$lone/"
cp "$gate" "$work/broken/monada-neuron-implement-issue/"
cp "$gate" "$work/garbled/monada-neuron-implement-issue/"
printf '# no snippet\n' >"$work/broken/monada-neuron-independent-verification/SKILL.md"
printf '```bash\necho write-tree\n```\n' >"$work/garbled/monada-neuron-independent-verification/SKILL.md"
expect "missing verifier skill is refused" 1 "verifier skill not found" "$lone/publication-gate.sh" --before-commit "$r/pass" base
expect "verifier without a fingerprint snippet is refused" 1 "could not extract the fingerprint snippet" \
  "$work/broken/monada-neuron-implement-issue/publication-gate.sh" --before-commit "$r/pass" base
expect "fingerprint output in the wrong form is refused" 1 "fingerprint failed" \
  "$work/garbled/monada-neuron-implement-issue/publication-gate.sh" --before-commit "$r/pass" base

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
# A commit with the verified tree whose parent is not the verified head.
g reset -q --hard "$(g commit-tree "$tree" -p base -m elsewhere)"
expect "a commit on another parent is refused" 1 "neither the verified head nor a single commit" "$gate" "$r/pass" base
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
