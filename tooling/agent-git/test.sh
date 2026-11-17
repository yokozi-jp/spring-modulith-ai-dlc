#!/usr/bin/env bash
# test.sh: 遅いフックを持つ一時の git リポジトリで、agent-git.sh の commit、wait、push の終了コードと出力を確かめる。
# Run: bash tooling/agent-git/test.sh (task agent-git-test が実行する)

set -u

# pre-commit の中で動くと GIT_INDEX_FILE などが本物のリポジトリを指すので外し、利用者の git の設定も読まない。
# shellcheck disable=SC2046
unset $(git rev-parse --local-env-vars)
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
export GIT_AUTHOR_NAME=test GIT_AUTHOR_EMAIL=test@example.com GIT_COMMITTER_NAME=test GIT_COMMITTER_EMAIL=test@example.com

tool="$(cd "$(dirname "$0")" && pwd)/agent-git.sh"
tmp="$(mktemp -d)"
export TMPDIR="$tmp"
out="$tmp/out"
dummy=""
trap '[ -z "$dummy" ] || kill "$dummy" 2>/dev/null; rm -rf "$tmp"' EXIT
failures=0

# expect <名前> <終了コード> <出力の断片> <コマンド>...
expect() {
  local label="$1" want="$2" fragment="$3" code
  shift 3
  "$@" >"$out" 2>&1
  code=$?
  if [ "$code" != "$want" ] || ! grep -qF -- "$fragment" "$out"; then
    echo "FAIL: $label (exit $code, want $want; fragment '$fragment')"
    sed 's/^/    /' "$out"
    failures=$((failures + 1))
  fi
}

# fact <名前> <コマンド>...
fact() {
  local label="$1"
  shift
  "$@" || { echo "FAIL: $label"; failures=$((failures + 1)); }
}

run_dir() { grep -o "$tmp/agent-git-[a-z]*\.[A-Za-z0-9]*" "$out" | head -n 1; }

git init -q --bare -b main "$tmp/origin.git"
git init -q -b main "$tmp/repo"
cd "$tmp/repo" || exit 1
git remote add origin "$tmp/origin.git"
echo base >tracked.txt
git add tracked.txt && git commit -q -m init
cat >.git/hooks/pre-commit <<'EOF'
#!/bin/sh
sleep 2
if git diff --cached --name-only | grep -qx fail; then echo "✗ fake-check"; exit 1; fi
echo "✓ fake-check"
EOF
printf '#!/bin/sh\nsleep 1\necho "✓ fake-push-check"\n' >.git/hooks/pre-push
chmod +x .git/hooks/pre-commit .git/hooks/pre-push
printf 'feat: add a file\n' >"$tmp/msg"
init="$(git rev-parse HEAD)"

echo other >other.txt && git add other.txt
echo a >"a b.txt"
expect "refuses when a path outside the given ones is staged" 1 "    other.txt" bash "$tool" commit "$tmp/msg" "a b.txt"
fact "the refusal leaves HEAD" [ "$(git rev-parse HEAD)" = "$init" ]
fact "the refusal does not stage the given path" [ -z "$(git diff --cached --name-only -- "a b.txt")" ]
git rm -q --cached other.txt

echo changed >>tracked.txt
expect "commit starts in the background" 0 "task agent-wait -- $tmp/agent-git-commit." bash "$tool" commit "$tmp/msg" "a b.txt"
run="$(run_dir)"
expect "wait reports a running commit" 75 "まだ実行中です" bash "$tool" wait "$run" 0
git add other.txt
expect "wait reports the finished commit" 0 "✓ fake-check" bash "$tool" wait "$run"
fact "the done marker holds exit=0" [ "$(cat "$run/done")" = exit=0 ]
fact "the commit holds only the given path" [ "$(git show --name-only --format= HEAD)" = "a b.txt" ]
fact "the commit uses the message file" [ "$(git log -1 --format=%s)" = "feat: add a file" ]
fact "a path staged while the hook runs stays staged" [ "$(git diff --cached --name-only)" = other.txt ]
git rm -q --cached other.txt
head="$(git rev-parse HEAD)"

echo x >fail
expect "commit with a failing hook starts" 0 "task agent-wait" bash "$tool" commit "$tmp/msg" fail
run="$(run_dir)"
expect "wait reports the failing hook" 1 "✗ fake-check" bash "$tool" wait "$run"
fact "the done marker holds exit=1" [ "$(cat "$run/done")" = exit=1 ]
fact "the failed commit leaves HEAD" [ "$(git rev-parse HEAD)" = "$head" ]
git rm -q --cached fail

git switch -q -c topic
if pgrep -f '^[^ ]*lefthook run pre-push' >/dev/null; then
  echo "skip: ほかの pre-push が動いているので、push の成功の検査を省きます"
else
  expect "push starts in the background" 0 "task agent-wait -- $tmp/agent-git-push." bash "$tool" push
  run="$(run_dir)"
  expect "wait reports the push" 0 "✓ fake-push-check" bash "$tool" wait "$run"
  fact "origin has the pushed branch" [ "$(git --git-dir="$tmp/origin.git" rev-parse topic)" = "$head" ]
fi

bash -c 'exec -a "lefthook run pre-push" sleep 30' &
dummy=$!
for _ in $(seq 50); do pgrep -f '^lefthook run pre-push' >/dev/null && break; sleep 0.1; done
expect "push refuses while a pre-push runs" 1 "前の pre-push がまだ動いています" bash "$tool" push

if [ "$failures" -gt 0 ]; then
  echo "$failures failure(s)"
  exit 1
fi
echo "all passed"
