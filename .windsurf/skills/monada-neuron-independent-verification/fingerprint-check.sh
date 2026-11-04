#!/usr/bin/env bash
# Regression self-test for the snapshot fingerprint snippet in SKILL.md, run against a throwaway repository.
# Covers tracked ignored files, skip-worktree and assume-unchanged edits, deletions, staged files, modes and symlinks.
set -euo pipefail

skill=$(cd "$(dirname "$0")" && pwd)/SKILL.md
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# The first bash block in SKILL.md is the fingerprint snippet; run it exactly as documented.
awk '/^```bash$/ { f = 1; next } /^```$/ { if (f) exit } f' "$skill" | sed 's/<base-ref>/HEAD/' >"$work/fingerprint.sh"
grep -q 'write-tree' "$work/fingerprint.sh" || { echo "FAIL could not extract the fingerprint snippet from $skill"; exit 1; }
fp() { bash "$work/fingerprint.sh" | sed 's/.*tree=//'; }

cd "$work"
git init -q -b main repo
cd repo
printf '*.local\n' >.gitignore
printf 'a\n' >keep.local
printf 'echo ok\n' >run.sh
chmod 755 run.sh
git add .gitignore run.sh
git add -f keep.local
git -c user.name=t -c user.email=t@example.invalid commit -qm init
clean=$(git rev-parse 'HEAD^{tree}')

failures=0
check() { # name result
  if [ "$2" = ok ]; then echo "ok   $1"; else echo "FAIL $1"; failures=$((failures + 1)); fi
}
same() { [ "$1" = "$2" ] && echo ok || echo no; }
differ() { [ "$1" != "$2" ] && echo ok || echo no; }

check "clean checkout equals HEAD^{tree}" "$(same "$(fp)" "$clean")"
check "tracked ignored file stays in the tree" "$(differ "$(git ls-tree "$(fp)" keep.local)" "")"

printf 'b\n' >keep.local
check "edit to a tracked ignored file changes the tree" "$(differ "$(fp)" "$clean")"
git checkout -q keep.local

git update-index --skip-worktree keep.local
printf 'c\n' >keep.local
check "edit to a skip-worktree file changes the tree" "$(differ "$(fp)" "$clean")"
git update-index --no-skip-worktree keep.local
git checkout -q keep.local

git update-index --assume-unchanged run.sh
printf 'echo changed\n' >run.sh
check "edit to an assume-unchanged file changes the tree" "$(differ "$(fp)" "$clean")"
git update-index --no-assume-unchanged run.sh
git checkout -q run.sh

rm keep.local
check "deleted tracked file drops out of the tree" "$(same "$(git ls-tree "$(fp)" keep.local)" "")"
git checkout -q keep.local

printf 's\n' >staged.local
git add -f staged.local
check "staged ignored file is in the tree" "$(differ "$(git ls-tree "$(fp)" staged.local)" "")"
git rm -q --cached staged.local
rm staged.local

printf 'x\n' >new.sh
chmod 644 new.sh
before=$(fp)
chmod 755 new.sh
check "untracked file mode changes the tree" "$(differ "$(fp)" "$before")"
rm new.sh

ln -s keep.local link
symlink=$(fp)
rm link
cp keep.local link
check "symlink and regular copy give different trees" "$(differ "$(fp)" "$symlink")"
rm link

printf 'junk\n' >other.local
check "untracked ignored file is excluded" "$(same "$(fp)" "$clean")"
rm other.local

chmod 644 run.sh
check "tracked file mode change changes the tree" "$(differ "$(fp)" "$clean")"
chmod 755 run.sh
git checkout -q run.sh

index_before=$(sha256sum .git/index)
fp >/dev/null
check "author's index is unchanged" "$(same "$(sha256sum .git/index)" "$index_before")"
check "fixture restored to clean" "$(same "$(fp)" "$clean")"

[ "$failures" = 0 ] || { echo "$failures check(s) failed"; exit 1; }
echo "all checks passed"
