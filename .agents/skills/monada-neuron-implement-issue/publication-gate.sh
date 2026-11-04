#!/usr/bin/env bash
# Publication gate for SKILL.md: allows a commit or a push only on a current independent PASS.
# Usage: publication-gate.sh [--before-commit] <result-file> <base-ref>; run from the checkout to publish.
set -euo pipefail

mode=publish
if [ "${1:-}" = --before-commit ]; then mode=before-commit; shift; fi
result=${1:?usage: publication-gate.sh [--before-commit] <result-file> <base-ref>}
base_ref=${2:?usage: publication-gate.sh [--before-commit] <result-file> <base-ref>}

deny() { echo "DENY: $*"; exit 1; }

[ -f "$result" ] || deny "result file not found: $result"
verifier=$(cd "$(dirname "$0")/.." && pwd)/monada-neuron-independent-verification/SKILL.md
[ -f "$verifier" ] || deny "verifier skill not found: $verifier"

blocks=$(grep -c '^## Independent verification result$' "$result" || true)
[ "$blocks" = 1 ] || deny "expected exactly one result block, found $blocks"

status=$(sed -n 's/^\*\*Status:\*\* *//p' "$result")
[ "$(grep -c '^\*\*Status:\*\*' "$result")" = 1 ] || deny "expected exactly one Status line"
[ "$status" = PASS ] || deny "status is '$status', not PASS"

snapshot=$(sed -n 's/^\*\*Snapshot:\*\* *//p' "$result")
[[ $snapshot =~ ^base=([0-9a-f]{40,64})\ head=([0-9a-f]{40,64})\ tree=([0-9a-f]{40,64})\ \(identical\ at\ start\ and\ end:\ ([a-z]+)\)$ ]] ||
  deny "Snapshot line is malformed: '$snapshot'"
v_base=${BASH_REMATCH[1]} v_head=${BASH_REMATCH[2]} v_tree=${BASH_REMATCH[3]} identical=${BASH_REMATCH[4]}
[ "$identical" = yes ] || deny "snapshot changed during the review (identical at start and end: $identical)"

# Criteria table: data rows between '### Criteria' and the next heading; the status is the third column.
# Escaped pipes (`\|`) inside a cell are removed first so they do not shift the columns.
table=$(awk '/^### Criteria$/ { f = 1; next } /^#/ { f = 0 } f && /^\|/' "$result")
[[ $(sed -n 1p <<<"$table") =~ ^\|\ *Id\ *\| ]] && [[ $(sed -n 2p <<<"$table") =~ ^\|\ *-+\ *\| ]] ||
  deny "criteria table has no header and separator row"
rows=$(sed '1,2d; s/\\|//g' <<<"$table")
[ -n "$rows" ] || deny "criteria table is empty"
while IFS= read -r row; do
  id=$(awk -F'|' '{ gsub(/^ +| +$/, "", $2); print $2 }' <<<"$row")
  st=$(awk -F'|' '{ gsub(/^ +| +$/, "", $4); print $4 }' <<<"$row")
  [ "$st" = SATISFIED ] || deny "criterion $id is '$st', not SATISFIED"
done <<<"$rows"

# Recompute the snapshot identity with the verifier's own snippet, so both skills share one definition.
snippet=$(awk '/^```bash$/ { f = 1; next } /^```$/ { if (f) exit } f' "$verifier")
grep -q write-tree <<<"$snippet" || deny "could not extract the fingerprint snippet from $verifier"
git rev-parse --verify -q "$base_ref^{commit}" >/dev/null || deny "base ref '$base_ref' does not resolve"
current=$(BASE_REF=$base_ref bash -c "${snippet//<base-ref>/\"\$BASE_REF\"}")
[[ $current =~ ^base=([0-9a-f]{40,64})\ head=([0-9a-f]{40,64})\ tree=([0-9a-f]{40,64})$ ]] || deny "fingerprint failed: '$current'"
c_base=${BASH_REMATCH[1]} c_head=${BASH_REMATCH[2]} c_tree=${BASH_REMATCH[3]}

[ "$c_base" = "$v_base" ] || deny "base moved: $base_ref is $c_base, verified base was $v_base"
[ "$c_tree" = "$v_tree" ] || deny "checkout tree $c_tree differs from verified tree $v_tree (stale result)"

if [ "$mode" = before-commit ]; then
  [ "$c_head" = "$v_head" ] || deny "HEAD $c_head is not the verified head $v_head"
  echo "ALLOW: checkout is the verified snapshot; commit exactly this tree"
  exit 0
fi

[ "$(git rev-parse 'HEAD^{tree}')" = "$v_tree" ] || deny "HEAD^{tree} differs from the verified tree; commit the verified snapshot first"
if [ "$c_head" != "$v_head" ]; then
  parents=$(git rev-list --parents -n 1 HEAD | wc -w)
  [ "$parents" = 2 ] && [ "$(git rev-parse HEAD^)" = "$v_head" ] ||
    deny "HEAD $c_head is neither the verified head nor a single commit on top of it"
fi
echo "ALLOW: HEAD $c_head carries the verified tree $v_tree on base $v_base"
