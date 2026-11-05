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

# Read only the verification line the verifier's output contract defines; the Markdown is for people.
# Character classes are spelled out, so a locale cannot widen them to non-ASCII digits or letters.
! grep -q $'\r' "$result" || deny "result file has CRLF line endings; save it with LF line endings"
blocks=$(grep -c '^## Independent verification result$' "$result" || true)
[ "$blocks" = 1 ] || deny "expected exactly one result block, found $blocks"
# Only lines that start with the comment count, so a result may quote the line inside a cell or code span.
lines=$(grep -ci '^[[:space:]]*<!--[[:space:]]*verification' "$result" || true)
[ "$lines" = 1 ] || deny "expected exactly one verification line, found $lines"
line=$(awk '/^## Independent verification result$/ { f = 1; next } f && NF { print; exit }' "$result")
hex='([0123456789abcdef]{40}|[0123456789abcdef]{64})'
num='(0|[123456789][0123456789]*)'
re="^<!-- verification v1 status=(PASS|NEEDS_CHANGES|BLOCKED) base=$hex head=$hex tree=$hex identical=(yes|no)"
re+=" criteria=$num satisfied=$num blocking_open=$num -->\$"
[[ $line =~ $re ]] || deny "verification line is missing after the result heading or malformed: '$line'"
status=${BASH_REMATCH[1]} v_base=${BASH_REMATCH[2]} v_head=${BASH_REMATCH[3]} v_tree=${BASH_REMATCH[4]}
identical=${BASH_REMATCH[5]} criteria=${BASH_REMATCH[6]} satisfied=${BASH_REMATCH[7]} blocking_open=${BASH_REMATCH[8]}

[ "$status" = PASS ] || deny "status is '$status', not PASS"
[ "$identical" = yes ] || deny "snapshot changed during the review (identical=$identical)"
[ "$criteria" != 0 ] || deny "no criteria were verified"
[ "$satisfied" = "$criteria" ] || deny "only $satisfied of $criteria criteria are SATISFIED"
# Required checks that did not run are not marked as required in the result, so they stay with the verifier's PASS rule.
[ "$blocking_open" = 0 ] || deny "$blocking_open blocking finding(s) still open"

# Recompute the snapshot identity with the verifier's own snippet, so both skills share one definition.
snippet=$(awk '/^```bash$/ { f = 1; next } /^```$/ { if (f) exit } f' "$verifier")
grep -q write-tree <<<"$snippet" || deny "could not extract the fingerprint snippet from $verifier"
git rev-parse --verify -q "$base_ref^{commit}" >/dev/null || deny "base ref '$base_ref' does not resolve"
current=$(BASE_REF=$base_ref bash -c "${snippet//<base-ref>/\"\$BASE_REF\"}")
[[ $current =~ ^base=$hex\ head=$hex\ tree=$hex$ ]] || deny "fingerprint failed: '$current'"
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
