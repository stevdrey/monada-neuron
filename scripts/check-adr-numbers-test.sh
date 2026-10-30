#!/usr/bin/env bash
# ABOUTME: Self-test for check-adr-numbers.sh against a throwaway repository and a fake `gh` on PATH.
# ABOUTME: Covers a clean tree, a duplicate in the tree, a clash with an open pull request and --next.
set -euo pipefail

script=$(cd "$(dirname "$0")" && pwd)/check-adr-numbers.sh
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# A fake gh that answers `gh pr list --json number,files --jq <filter>` from $FAKE_PRS with the real jq.
mkdir -p "$work/bin"
cat >"$work/bin/gh" <<'EOF'
#!/usr/bin/env bash
filter=""
while [ $# -gt 0 ]; do
  case "$1" in --jq) filter="$2"; shift ;; esac
  shift
done
[ -n "${FAKE_PRS:-}" ] || exit 1
jq -r "$filter" <<<"$FAKE_PRS"
EOF
chmod +x "$work/bin/gh"
export PATH="$work/bin:$PATH"

# origin/main with two ADRs; a clone with one more ADR on a branch.
git init -q -b main "$work/origin"
mkdir -p "$work/origin/docs/adr"
printf '# ADR 0001\n' >"$work/origin/docs/adr/0001-first.md"
printf '# ADR 0002\n' >"$work/origin/docs/adr/0002-second.md"
git -C "$work/origin" add . && git -C "$work/origin" -c user.name=t -c user.email=t@t commit -qm main
git clone -q "$work/origin" "$work/clone"
cd "$work/clone"
git checkout -q -b topic
printf '# ADR 0003\n' >docs/adr/0003-mine.md

failures=0
expect() {
  local name=$1 want_status=$2 want_text=$3
  shift 3
  local out status=0
  out=$("$@" 2>&1) || status=$?
  if [ "$status" != "$want_status" ] || ! grep -q -- "$want_text" <<<"$out"; then
    echo "FAIL $name: status $status (want $want_status), output:"
    sed 's/^/  /' <<<"$out"
    failures=$((failures + 1))
  else
    echo "ok   $name"
  fi
}

no_prs='[]'
other_pr_same_number='[{"number":7,"files":[{"path":"docs/adr/0003-theirs.md"},{"path":"src/Foo.java"}]}]'
other_pr_editing_existing='[{"number":8,"files":[{"path":"docs/adr/0002-second.md"}]}]'
self_pr='[{"number":9,"files":[{"path":"docs/adr/0003-mine.md"}]}]'

FAKE_PRS=$no_prs expect "clean tree, no open PRs" 0 "ADR numbers are unique (tree, main, open pull requests)" "$script"
FAKE_PRS=$other_pr_editing_existing expect "other PR editing an ADR already on main" 0 "unique" "$script"
FAKE_PRS=$self_pr expect "own PR is skipped with --pr" 0 "unique" "$script" --pr 9
FAKE_PRS=$self_pr expect "own PR is a clash without --pr" 1 "ADR 0003 (0003-mine.md) is also added by open pull request(s): #9" "$script"
FAKE_PRS=$other_pr_same_number expect "clash with another open PR" 1 "also added by open pull request(s): #7 (0003-theirs.md)" "$script"
FAKE_PRS=$no_prs expect "--next after tree" 0 "^0004$" "$script" --next
FAKE_PRS=$other_pr_same_number expect "--next counts open PRs" 0 "^0004$" "$script" --next
FAKE_PRS='[{"number":7,"files":[{"path":"docs/adr/0010-theirs.md"}]}]' expect "--next skips past an open PR" 0 "^0011$" "$script" --next
FAKE_PRS='' expect "gh unavailable warns" 0 "open pull requests not checked" "$script"
FAKE_PRS='' expect "gh unavailable fails with --require-prs" 2 "Cannot list open pull requests" "$script" --require-prs

printf '# ADR 0002\n' >docs/adr/0002-dup.md
FAKE_PRS=$no_prs expect "duplicate in tree" 1 "Duplicate ADR number 0002" "$script"
rm docs/adr/0002-dup.md

# Renumbering: the tree renames main's 0002 to 0005 and nothing else claims 0005.
git mv docs/adr/0002-second.md docs/adr/0005-second.md
FAKE_PRS=$no_prs expect "renamed ADR is unique" 0 "unique" "$script"
FAKE_PRS=$no_prs expect "--next after a rename" 0 "^0006$" "$script" --next

if [ "$failures" = 0 ]; then
  echo "all checks passed"
else
  echo "$failures check(s) failed"
  exit 1
fi
