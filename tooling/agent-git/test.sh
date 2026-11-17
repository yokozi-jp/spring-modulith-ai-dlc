#!/usr/bin/env bash
# test.sh: フックを止めておける一時の git リポジトリで、agent-git.sh の commit、wait、push の終了コードと出力を確かめる。
# Run: bash tooling/agent-git/test.sh (task agent-git-test が実行する)

set -u

# pre-commit の中で動くと GIT_INDEX_FILE などが本物のリポジトリを指すので外し、利用者の git の設定も読まない。
# shellcheck disable=SC2046
unset $(git rev-parse --local-env-vars)
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
export GIT_AUTHOR_NAME=test GIT_AUTHOR_EMAIL=test@example.com GIT_COMMITTER_NAME=test GIT_COMMITTER_EMAIL=test@example.com
# 本物の pre-push を数えず、本物の agent-push も止めないよう、この実行だけの名前のプロセスを pre-push とみなす。
export AGENT_GIT_PREPUSH_PATTERN="^agent-git-test-decoy-$$ "
unset AGENT_GIT_WAIT_SECONDS

tool="$(cd "$(dirname "$0")" && pwd)/agent-git.sh"
tmp="$(mktemp -d)"
out="$tmp/out"
hold="$tmp/hold"
msg="$tmp/msg"
decoy=""
trap 'rm -f "$hold"; [ -z "$decoy" ] || kill "$decoy" 2>/dev/null; rm -rf "$tmp"' EXIT
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
  "$@" || {
    echo "FAIL: $label"
    failures=$((failures + 1))
  }
}

run_dir() { grep -o '/[^ ]*/agent-git/[a-z]*\.[A-Za-z0-9]*' "$out" | head -n 1; }
wait_now() { AGENT_GIT_WAIT_SECONDS=0 bash "$tool" wait "$@"; }
in_sub() { (cd sub && "$@"); }
in_wt() { (cd "$tmp/wt" && "$@"); }
not() { ! "$@"; }
pid_of() { awk -v n="$1" '$1 == n { print $2 }' "$2/pid"; }
committed() { git show --name-only --format= HEAD; }

git init -q --bare -b main "$tmp/origin.git"
git init -q -b main "$tmp/repo"
cd "$tmp/repo" || exit 1
git remote add origin "$tmp/origin.git"
mkdir sub
echo base >tracked.txt
echo base >same.txt
echo one >part.txt
git add . && git commit -q -m init
cat >.git/hooks/pre-commit <<EOF
#!/bin/sh
while [ -e '$hold' ]; do sleep 0.1; done
staged="\$(git diff --cached --name-only)"
printf '\033[38;2;56;56;56m  ────\033[m\n'
if echo "\$staged" | grep -qx fail; then echo "✗ fake-check"; exit 1; fi
if echo "\$staged" | grep -qx fixme; then echo fixed >>fixme && git add fixme; fi
printf '⚠ fake-notice\n  fake-detail\n\n'
printf '\033[32m✓ fake-check\033[m\n'
EOF
cat >.git/hooks/pre-push <<EOF
#!/bin/sh
while [ -e '$hold' ]; do sleep 0.1; done
echo "✓ fake-push-check"
EOF
chmod +x .git/hooks/pre-commit .git/hooks/pre-push
printf 'feat: add a file\n' >"$msg"
: >"$tmp/empty"
init="$(git rev-parse HEAD)"

echo a >a.txt
expect "refuses an empty message file" 1 "が無いか空です" bash "$tool" commit "$tmp/empty" a.txt

echo other >other.txt && git add other.txt
echo a >"a b.txt"
expect "refuses when a path outside the given ones is staged" 1 "    other.txt" bash "$tool" commit "$msg" "a b.txt"
fact "the refusal leaves HEAD" [ "$(git rev-parse HEAD)" = "$init" ]
fact "the refusal does not stage the given path" [ -z "$(git diff --cached --name-only -- "a b.txt")" ]

echo x >sub/x
expect "refuses from a subdirectory when a root path is staged" 1 "    other.txt" in_sub bash "$tool" commit "$msg" x
git rm -q --cached other.txt
expect "commit starts from a subdirectory" 0 "task agent-wait -- " in_sub bash "$tool" commit "$msg" x
expect "wait from a subdirectory picks the latest run" 0 "✓ fake-check" in_sub bash "$tool" wait
fact "the commit from a subdirectory holds only the given path" [ "$(committed)" = sub/x ]

expect "refuses when the given paths have no changes" 1 "commit する変更がありません" bash "$tool" commit "$msg" same.txt

echo two >>part.txt && git add part.txt && echo three >>part.txt
expect "refuses a path with staged and unstaged changes" 1 "    part.txt" bash "$tool" commit "$msg" part.txt
fact "the refusal keeps the partial stage" [ "$(git show :part.txt)" = "$(printf 'one\ntwo')" ]
git reset -q part.txt

touch "$hold"
echo changed >>tracked.txt
expect "commit starts in the background" 0 "task agent-wait -- " bash "$tool" commit "$msg" "a b.txt"
run="$(run_dir)"
expect "wait reports a running commit" 75 "まだ実行中です" wait_now "$run"
expect "a second commit is refused while the first runs" 1 "前の agent-commit がまだ動いています" bash "$tool" commit "$msg" a.txt
git add other.txt
expect "the commit still runs right after another git add" 75 "まだ実行中です" wait_now "$run"
rm "$hold"
expect "wait reports the finished commit" 0 "✓ fake-check" bash "$tool" wait "$run"
fact "wait strips color codes" not grep -qF $'\e' "$out"
fact "wait shows the warning with its detail" grep -qx "  fake-detail" "$out"
fact "wait removes the successful run" [ ! -e "$run" ]
fact "the commit holds only the given path" [ "$(committed)" = "a b.txt" ]
fact "the commit uses the message file" [ "$(git log -1 --format=%s)" = "feat: add a file" ]
fact "a path staged while the hook runs stays staged" [ "$(git diff --cached --name-only)" = other.txt ]
fact "the committed path is clean in the index" [ -z "$(git status --porcelain -- "a b.txt")" ]
git rm -q --cached other.txt

echo fix >fixme
expect "commit with a hook that stages a fix starts" 0 "task agent-wait" bash "$tool" commit "$msg" fixme
expect "wait reports the commit with the fix" 0 "✓ fake-check" bash "$tool" wait
fact "the fix the hook staged is committed" [ "$(git show HEAD:fixme)" = "$(printf 'fix\nfixed')" ]
fact "the path the hook fixed is clean" [ -z "$(git status --porcelain -- fixme)" ]

echo star >'*.txt'
expect "commit with a glob-like name starts" 0 "task agent-wait" bash "$tool" commit "$msg" '*.txt'
expect "wait reports the literal path commit" 0 "exit=0" bash "$tool" wait
fact "a glob-like path matches only the file of that name" [ "$(committed)" = '*.txt' ]
head="$(git rev-parse HEAD)"

echo x >fail
expect "commit with a failing hook starts" 0 "task agent-wait" bash "$tool" commit "$msg" fail
run="$(run_dir)"
expect "wait reports the failing hook" 1 "✗ fake-check" bash "$tool" wait "$run"
fact "wait strips color codes from the log tail" not grep -qF $'\e' "$out"
fact "the failed commit leaves HEAD" [ "$(git rev-parse HEAD)" = "$head" ]
fact "the failed commit leaves the index as it was" [ -z "$(git diff --cached --name-only)" ]
fact "the failed run keeps its log" [ -f "$run/log" ]
fact "the failed run drops the index copy" [ ! -e "$run/index" ]
fact "the failed run drops the message copy" [ ! -e "$run/msg" ]
: >"$run/done"
expect "an empty done counts as lost" 1 "終了コードを記録せずに止まりました" bash "$tool" wait "$run"
echo "wrapper $$ 1" >"$run/pid"
expect "a live PID with another start time counts as stopped" 1 "終了コードを記録せずに止まりました" wait_now "$run"

touch "$hold"
echo l >lost.txt
expect "commit to be killed starts" 0 "task agent-wait" bash "$tool" commit "$msg" lost.txt
run="$(run_dir)"
for _ in $(seq 50); do [ -n "$(pid_of git "$run")" ] && break; sleep 0.1; done
kill "$(pid_of wrapper "$run")"
expect "a commit whose wrapper died still runs while git runs" 75 "まだ実行中です" wait_now "$run"
kill "$(pid_of git "$run")"
rm "$hold"
expect "a commit killed before done is lost" 1 "終了コードを記録せずに止まりました" bash "$tool" wait "$run"
fact "the killed commit leaves HEAD" [ "$(git rev-parse HEAD)" = "$head" ]

git worktree add -q "$tmp/wt" -b wt
touch "$hold"
expect "commit in the main worktree starts" 0 "task agent-wait" bash "$tool" commit "$msg" lost.txt
echo w >"$tmp/wt/w.txt"
expect "a running commit does not block another worktree" 0 "task agent-wait" in_wt bash "$tool" commit "$msg" w.txt
rm "$hold"
expect "wait in the linked worktree picks its own run" 0 "exit=0" in_wt bash "$tool" wait
fact "the linked worktree commits its own path" [ "$(git -C "$tmp/wt" show --name-only --format= HEAD)" = w.txt ]
expect "wait in the main worktree picks its own run" 0 "exit=0" bash "$tool" wait
fact "the main worktree commits its own path" [ "$(committed)" = lost.txt ]
head="$(git rev-parse HEAD)"

git switch -q -c topic
bash -c "exec -a agent-git-test-decoy-$$ sleep 30" &
decoy=$!
for _ in $(seq 50); do pgrep -f "$AGENT_GIT_PREPUSH_PATTERN" >/dev/null && break; sleep 0.1; done
expect "push refuses while a pre-push runs" 1 "前の pre-push がまだ動いています" bash "$tool" push
kill "$decoy"
for _ in $(seq 50); do pgrep -f "$AGENT_GIT_PREPUSH_PATTERN" >/dev/null || break; sleep 0.1; done

touch "$hold"
expect "push starts in the background" 0 "task agent-wait -- " bash "$tool" push
run="$(run_dir)"
expect "a second push is refused while the first runs" 1 "前の agent-push がまだ動いています" bash "$tool" push
rm "$hold"
expect "wait reports the push" 0 "✓ fake-push-check" bash "$tool" wait "$run"
fact "origin has the pushed branch" [ "$(git --git-dir="$tmp/origin.git" rev-parse topic)" = "$head" ]

if [ "$failures" -gt 0 ]; then
  echo "$failures failure(s)"
  exit 1
fi
echo "all passed"
