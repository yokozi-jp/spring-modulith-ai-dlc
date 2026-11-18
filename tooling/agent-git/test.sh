#!/usr/bin/env bash
# test.sh: フックを止めておける一時の git リポジトリで、agent-git.sh の commit、wait、push の終了コードと出力を確かめる。
# Run: bash tooling/agent-git/test.sh (task agent-git-test が実行する)
# 場面は、状況を書くコメントと、index が HEAD と同じことを確かめる scene で始める。場面ごとに別のファイルを使う。

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
trap 'rm -f "$hold"; [ -z "$decoy" ] || kill "$decoy" 2>/dev/null; [ ! -f "$tmp/daemon.pid" ] || kill "$(cat "$tmp/daemon.pid")" 2>/dev/null; rm -rf "$tmp"' EXIT
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

# until_ok <コマンド>...: コマンドが成功するまで 0.1 秒おきに 5 秒まで繰り返す。
until_ok() {
  local _
  for _ in $(seq 50); do
    "$@" && return 0
    sleep 0.1
  done
  return 1
}

run_dir() { grep -o '/[^ ]*/agent-git/[a-z]*\.[A-Za-z0-9]*' "$out" | head -n 1; }
wait_now() { AGENT_GIT_WAIT_SECONDS=0 bash "$tool" wait "$@"; }
within() { (cd "$1" && "${@:2}"); }
not() { ! "$@"; }
none() {
  local f
  for f; do [ ! -e "$f" ] || return 1; done
}
pid_of() { awk -v n="$1" '$1 == n { print $2 }' "$2/pid"; }
has_pid() { [ -n "$(pid_of "$1" "$2")" ]; }
alive() { kill -0 "$1" 2>/dev/null; }
committed() { git show --name-only --format= HEAD; }
moved() { [ "$(git rev-parse HEAD)" != "$1" ]; }
clean() { [ -z "$(git diff --cached --name-only)" ]; }
scene() { fact "the $1 starts from a clean index" clean; }
decoy_running() { pgrep -f "$AGENT_GIT_PREPUSH_PATTERN" >/dev/null; }
start_decoy() {
  (cd "$1" && exec -a "agent-git-test-decoy-$$" sleep 30) &
  decoy=$!
  until_ok decoy_running
}
stop_decoy() {
  kill "$decoy"
  decoy=""
  until_ok not decoy_running
}

git init -q --bare -b main "$tmp/origin.git"
git init -q -b main "$tmp/repo"
cd "$tmp/repo" || exit 1
git remote add origin "$tmp/origin.git"
mkdir sub dot
for f in same.txt part.txt fixed-out.txt lockme rm.txt mv.txt untrack.txt ignore.txt; do echo base >"$f"; done
git add . && git commit -q -m init
cat >.git/hooks/pre-commit <<EOF
#!/bin/sh
while [ -e '$hold' ]; do sleep 0.1; done
# git を止めた実行では、フックが読む前に写しの index が消えることがあり、diff はすべてを消したと見るので、足したか変えたファイルだけを見る。
staged="\$(git diff --cached --name-only --diff-filter=AM)"
printf '\033[38;2;56;56;56m  ────\033[m\n'
has() { echo "\$staged" | grep -qx "\$1"; }
if has fail; then echo "✗ fake-check"; exit 1; fi
if has fixme; then echo fixed >>fixme && git add fixme; fi
if has fixout; then echo fixed >>fixed-out.txt && git add fixed-out.txt; fi
if has lockme; then : >'$tmp/repo/.git/index.lock'; fi
if has lockbrief; then
  : >'$tmp/repo/.git/index.lock'
  head="\$(git rev-parse HEAD)"
  (while [ "\$(git rev-parse HEAD)" = "\$head" ]; do sleep 0.05; done; sleep 0.3; rm -f '$tmp/repo/.git/index.lock') >/dev/null 2>&1 &
fi
printf '⚠ fake-notice\n  fake-detail\n\n'
printf '\033[32m✓ fake-check\033[m\n'
EOF
cat >.git/hooks/pre-push <<EOF
#!/bin/sh
while [ -e '$hold' ]; do sleep 0.1; done
if [ -e '$tmp/daemon' ]; then sleep 30 </dev/null >/dev/null 2>&1 & echo \$! >'$tmp/daemon.pid'; fi
echo "✓ fake-push-check"
EOF
# ほかの作業が、合わせる前の本物の index から git commit するのを真似る。keep なら、その前に作業ツリーをすべて stage する。
cat >.git/hooks/post-commit <<EOF
#!/bin/sh
[ -e '$tmp/overtake' ] || exit 0
[ "\$(cat '$tmp/overtake')" != keep ] || env -u GIT_INDEX_FILE git add -A
rm '$tmp/overtake' && env -u GIT_INDEX_FILE git commit --no-verify -q -m foreign
EOF
chmod +x .git/hooks/pre-commit .git/hooks/pre-push .git/hooks/post-commit
git worktree add -q "$tmp/wt" -b wt
mkdir "$tmp/failmv" && printf '#!/bin/sh\nexit 1\n' >"$tmp/failmv/mv" && chmod +x "$tmp/failmv/mv"
printf 'feat: add a file\n' >"$msg"
: >"$tmp/empty"

# まだ実行が無い worktree の wait と、待つ秒数の誤り。
expect "wait with no runs" 1 "まだ報告していない実行がありません" bash "$tool" wait
expect "wait refuses an invalid AGENT_GIT_WAIT_SECONDS" 1 "0 以上の整数" env AGENT_GIT_WAIT_SECONDS=soon bash "$tool" wait

# 始める前に止まる場合。どれも HEAD と index を変えない。
scene "refusals"
init="$(git rev-parse HEAD)"
echo a >a.txt
expect "refuses an empty message file" 1 "が無いか空です" bash "$tool" commit "$tmp/empty" a.txt
echo other >other.txt && git add other.txt
echo a >"a b.txt"
expect "refuses when a path outside the given ones is staged" 1 "    other.txt" bash "$tool" commit "$msg" "a b.txt"
fact "the refusal leaves HEAD" [ "$(git rev-parse HEAD)" = "$init" ]
fact "the refusal does not stage the given path" [ -z "$(git diff --cached --name-only -- "a b.txt")" ]
echo x >sub/refused
expect "refuses from a subdirectory when a root path is staged" 1 "    other.txt" within sub bash "$tool" commit "$msg" refused
git rm -q --cached other.txt
expect "refuses when the given paths have no changes" 1 "commit する変更がありません" bash "$tool" commit "$msg" same.txt
expect "refuses a path that matches no file" 1 "どれにもありません" bash "$tool" commit "$msg" missing.txt
git rm -q --cached untrack.txt && echo with >untrack-with.txt
expect "refuses a path untracked by git rm --cached" 1 "    untrack.txt" bash "$tool" commit "$msg" untrack.txt untrack-with.txt
fact "the refusal keeps the untracking staged" [ -z "$(git ls-files -- untrack.txt)" ]
git reset -q untrack.txt && rm untrack-with.txt
echo two >>part.txt && git add part.txt && echo three >>part.txt
expect "refuses a path whose stage differs from the work tree" 1 "    part.txt" bash "$tool" commit "$msg" part.txt
fact "the refusal says git add would overwrite the stage" grep -qF "stage した内容が上書きされる" "$out"
fact "the refusal does not send the agent to git commit" not grep -qF "git commit" "$out"
fact "the refusal keeps the partial stage" [ "$(git show :part.txt)" = "$(printf 'base\ntwo')" ]
git reset -q part.txt && git checkout -- part.txt && rm a.txt other.txt "a b.txt" sub/refused

# サブディレクトリから相対パスを渡し、引数のない wait で待つ。
scene "subdirectory commit"
echo x >sub/x
expect "commit starts from a subdirectory" 0 "task agent-wait -- " within sub bash "$tool" commit "$msg" x
expect "wait from a subdirectory picks the latest run" 0 "✓ fake-check" within sub bash "$tool" wait
fact "the commit from a subdirectory holds only the given path" [ "$(committed)" = sub/x ]
fact "the index matches the commit made from a subdirectory" clean

# フックを止めている間に、ほかの作業が別のファイルを stage する。
scene "held commit"
touch "$hold"
echo held >held.txt
expect "commit starts in the background" 0 "task agent-wait -- " bash "$tool" commit "$msg" held.txt
run="$(run_dir)"
expect "wait reports a running commit" 75 "まだ実行中です" wait_now "$run"
expect "a second commit is refused while the first runs" 1 "前の agent-commit がまだ動いています" bash "$tool" commit "$msg" held.txt
echo other >other.txt && git add other.txt held.txt
expect "the commit still runs right after another git add" 75 "まだ実行中です" wait_now "$run"
rm "$hold"
expect "wait reports the finished commit" 0 "✓ fake-check" bash "$tool" wait "$run"
fact "wait strips color codes" not grep -qF $'\e' "$out"
fact "wait shows the warning with its detail" grep -qx "  fake-detail" "$out"
fact "wait removes the successful run" [ ! -e "$run" ]
fact "the commit holds only the given path" [ "$(committed)" = held.txt ]
fact "the commit uses the message file" [ "$(git log -1 --format=%s)" = "feat: add a file" ]
fact "a path staged while the hook runs stays staged" [ "$(git diff --cached --name-only)" = other.txt ]
fact "the committed path is clean in the index" [ -z "$(git status --porcelain -- held.txt)" ]
fact "a path staged again with the committed content is not named" not grep -qF "    held.txt" "$out"
git rm -q --cached other.txt && rm other.txt

# フックを止めている間に、ほかの作業が commit するのと同じパスを書き足して stage する。
scene "shared path commit"
touch "$hold"
echo mine >shared.txt
expect "commit of a shared path starts" 0 "task agent-wait" bash "$tool" commit "$msg" shared.txt
echo theirs >>shared.txt && git add shared.txt
rm "$hold"
expect "wait names the path staged again during the hook" 0 "    shared.txt" bash "$tool" wait
fact "the commit holds the content agent-commit staged" [ "$(git show HEAD:shared.txt)" = mine ]
fact "the other stage of the same path stays staged" [ "$(git show :shared.txt)" = "$(printf 'mine\ntheirs')" ]
git reset -q shared.txt && git checkout -- shared.txt

# ディレクトリで . を渡し、フックを止めている間に、ほかの作業がその下の別のファイルを stage する。
scene ". commit"
echo d >dot/d.txt
touch "$hold"
expect "commit of . starts" 0 "task agent-wait" within dot bash "$tool" commit "$msg" .
echo later >dot/later.txt && git add dot/later.txt
rm "$hold"
expect "wait reports the commit of ." 0 "exit=0" bash "$tool" wait
fact "the commit of . holds the file present at the start" [ "$(committed)" = dot/d.txt ]
fact "a file staged under . during the hook stays staged" [ "$(git diff --cached --name-only)" = dot/later.txt ]
git rm -q --cached dot/later.txt && rm dot/later.txt

# フックが、渡したパスを直して stage する。
scene "fixme commit"
echo fix >fixme
expect "commit with a hook that stages a fix starts" 0 "task agent-wait" bash "$tool" commit "$msg" fixme
expect "wait reports the commit with the fix" 0 "✓ fake-check" bash "$tool" wait
fact "the fix the hook staged is committed" [ "$(git show HEAD:fixme)" = "$(printf 'fix\nfixed')" ]
fact "the path the hook fixed is clean" [ -z "$(git status --porcelain -- fixme)" ]

# フックが、渡したパスの外のファイルを直して stage する。
scene "fixout commit"
echo fix >fixout
expect "commit with a hook that fixes another path starts" 0 "task agent-wait" bash "$tool" commit "$msg" fixout
expect "wait reports the commit with the outside fix" 0 "✓ fake-check" bash "$tool" wait
fact "the fix outside the given paths is committed" [ "$(git show HEAD:fixed-out.txt)" = "$(printf 'base\nfixed')" ]
fact "the index matches the commit for the path the hook fixed" clean

# glob の文字を含む名前を渡し、次にその削除を渡す。git は名前と同じファイルがあれば glob にしないので、削除で確かめる。
scene "glob-like commit"
echo star >'*.txt'
expect "commit with a glob-like name starts" 0 "task agent-wait" bash "$tool" commit "$msg" '*.txt'
expect "wait reports the literal path commit" 0 "exit=0" bash "$tool" wait
fact "a glob-like path matches only the file of that name" [ "$(committed)" = '*.txt' ]
rm '*.txt' && echo changed >same.txt
expect "commit of a deleted glob-like name starts" 0 "task agent-wait" bash "$tool" commit "$msg" '*.txt'
expect "wait reports the deletion commit" 0 "exit=0" bash "$tool" wait
fact "a deleted glob-like path matches only the file of that name" [ "$(committed)" = '*.txt' ]
git checkout -- same.txt

# git rm で消したパス、git mv で名前を変えたパス、git rm --cached で追跡をやめて .gitignore に足したパスを渡す。
# どれも stage してあり、作業ツリーとの違いが無いので、写しに git add するパスは無い。
scene "rm, mv, and untracking commit"
git rm -q rm.txt && git mv mv.txt moved.txt && git rm -q --cached ignore.txt && echo ignore.txt >.gitignore && git add .gitignore
expect "commit of paths staged by git rm, git mv, and git rm --cached starts" 0 "task agent-wait" bash "$tool" commit "$msg" rm.txt mv.txt moved.txt .gitignore ignore.txt
fact "the start prints only how to wait" [ "$(wc -l <"$out")" = 1 ]
expect "wait reports the commit of the removal, the rename, and the untracking" 0 "exit=0" bash "$tool" wait
fact "the commit drops the paths removed by git rm and git mv" [ -z "$(git ls-tree HEAD rm.txt mv.txt)" ]
fact "the commit holds the renamed path" [ "$(git show HEAD:moved.txt)" = base ]
fact "the commit stops tracking the path" [ -z "$(git ls-tree HEAD ignore.txt)" ]
fact "the untracked file stays in the work tree" [ -f ignore.txt ]
fact "the index matches the commit of the removal, the rename, and the untracking" clean

# 衝突を解決していない merge と、HEAD から何も変えない merge。
scene "merge commits"
git switch -q -c side && echo side >conflict.txt && git add conflict.txt && git commit -q --no-verify -m side
git switch -q main && echo main >conflict.txt && git add conflict.txt && git commit -q --no-verify -m main
git merge -q side >/dev/null 2>&1
expect "refuses an unresolved conflict" 1 "    conflict.txt" bash "$tool" commit "$msg" conflict.txt
fact "the refusal tells to resolve the conflict" grep -qF "衝突を解決してから" "$out"
git merge --abort && git merge -q -s ours --no-commit side >/dev/null 2>&1
expect "commit of a merge with no change starts" 0 "task agent-wait" bash "$tool" commit "$msg" .
expect "wait reports the merge commit" 0 "exit=0" bash "$tool" wait
fact "the merge commit has both parents" [ "$(git rev-parse HEAD^2)" = "$(git rev-parse side)" ]
fact "the index matches the merge commit" clean

# まだ commit の無いリポジトリで、フックが失敗する最初の commit と、成功する最初の commit。
git init -q -b main "$tmp/fresh" && git -C "$tmp/fresh" read-tree --empty && cp .git/hooks/pre-commit "$tmp/fresh/.git/hooks/"
echo x >"$tmp/fresh/fail"
expect "a first commit with a failing hook starts" 0 "task agent-wait" within "$tmp/fresh" bash "$tool" commit "$msg" fail
expect "wait reports git's exit code for the failed first commit" 1 "✗ fake-check" within "$tmp/fresh" bash "$tool" wait
fact "the failed first commit leaves no HEAD" not git -C "$tmp/fresh" rev-parse -q --verify HEAD
rm "$tmp/fresh/fail" && echo f >"$tmp/fresh/first.txt"
expect "a first commit starts" 0 "task agent-wait" within "$tmp/fresh" bash "$tool" commit "$msg" first.txt
expect "wait reports the first commit" 0 "exit=0" within "$tmp/fresh" bash "$tool" wait
fact "the first commit holds the given path" [ "$(git -C "$tmp/fresh" ls-tree --name-only HEAD)" = first.txt ]
fact "the index matches the first commit" within "$tmp/fresh" clean

# commit の直後、本物の index を合わせる前に、ほかの作業が古い本物の index から commit する。
scene "overtaken commit"
echo v >overtaken.txt && touch "$tmp/overtake"
expect "commit to be overtaken starts" 0 "task agent-wait" bash "$tool" commit "$msg" overtaken.txt
expect "wait says another commit reverted the commit" 1 "    overtaken.txt" bash "$tool" wait
fact "the reverting commit is on top of the commit" [ "$(git show --name-only --format= HEAD^)" = overtaken.txt ]
fact "the index matches the reverting commit" clean
rm overtaken.txt

# commit の直後、本物の index を合わせる前に、ほかの作業が同じ内容を stage してから commit する。
scene "commit overtaken with the same content"
echo k >overtaken-kept.txt && echo f >overtaken-foreign.txt && echo keep >"$tmp/overtake"
expect "commit to be overtaken with the same content starts" 0 "task agent-wait" bash "$tool" commit "$msg" overtaken-kept.txt
expect "wait reports success when the commit on top keeps the content" 0 "exit=0" bash "$tool" wait
fact "another commit is on top of the commit" [ "$(git show --name-only --format=%s HEAD)" = "$(printf 'foreign\n\novertaken-foreign.txt')" ]
fact "the commit on top keeps the content" [ "$(git show HEAD:overtaken-kept.txt)" = k ]
fact "the index matches the commit on top" clean

# フックが失敗した commit を報告する前に、別の実行（push）を始める。
scene "failing commit"
head="$(git rev-parse HEAD)"
echo x >fail
expect "commit with a failing hook starts" 0 "task agent-wait" bash "$tool" commit "$msg" fail
run="$(run_dir)"
fact "the failing commit records its git" until_ok has_pid git "$run"
expect "push starts while the failed commit is not reported" 0 "task agent-wait -- " bash "$tool" push
push_run="$(run_dir)"
expect "wait without a run picks the newest run" 0 "✓ fake-push-check" bash "$tool" wait
fact "wait reports and removes the newest run" [ ! -e "$push_run" ]
expect "wait without a run then picks the failing hook" 1 "✗ fake-check" bash "$tool" wait
fact "wait strips color codes from the log tail" not grep -qF $'\e' "$out"
fact "the failed commit leaves HEAD" [ "$(git rev-parse HEAD)" = "$head" ]
fact "the failed commit leaves the index as it was" clean
fact "the failed run keeps its log" [ -f "$run/log" ]
fact "the failed run drops its copies" none "$run/msg" "$run/index" "$run/base" "$run/head"
expect "wait without a run skips the reported failure" 1 "まだ報告していない実行がありません" bash "$tool" wait
expect "wait with the run reports the failure again" 1 "✗ fake-check" bash "$tool" wait "$run"
echo exit=oops >"$run/done"
expect "a malformed done counts as lost" 1 "終了コードを記録せずに止まりました" bash "$tool" wait "$run"
echo "wrapper $$ 1" >"$run/pid"
expect "a live PID with another start time counts as stopped" 1 "終了コードを記録せずに止まりました" wait_now "$run"
rm fail

# フックが本物の index.lock を作り、commit の少し後に外す。
scene "brief lock commit"
echo l >lockbrief
expect "commit with a brief index lock starts" 0 "task agent-wait" bash "$tool" commit "$msg" lockbrief
expect "wait reports the commit after the lock is released" 0 "exit=0" bash "$tool" wait
fact "the index matches the commit after the lock is released" clean

# フックが本物の index.lock を作ったまま残し、commit の後に本物の index を合わせられない。
scene "held lock commit"
echo changed >lockme
expect "commit with a held index lock starts" 0 "task agent-wait" bash "$tool" commit "$msg" lockme
expect "wait reports that the index does not follow the commit" 1 "本物の index を commit に合わせられませんでした" bash "$tool" wait
fact "the failure names the held index.lock" grep -qF "index.lock を 3 秒より長く持っていた" "$out"
fix="$(grep -o 'git -C .*' "$out")"
fact "the commit landed before the lock blocked the index" [ "$(committed)" = lockme ]
fact "the index still holds the content before the commit" [ "$(git show :lockme)" = base ]
rm .git/index.lock
expect "the next commit is refused until the index follows" 1 "後片付けが済んでいません" bash "$tool" commit "$msg" lockme
fact "the printed command runs from a subdirectory" within sub eval "$fix"
fact "the index matches the commit after the printed command" clean
expect "the next commit passes the guard after the printed command" 1 "commit する変更がありません" bash "$tool" commit "$msg" lockme

# wrapper と git を止め、commit が終わらない。
scene "killed commit"
head="$(git rev-parse HEAD)"
touch "$hold"
echo l >lost.txt
expect "commit to be killed starts" 0 "task agent-wait" bash "$tool" commit "$msg" lost.txt
run="$(run_dir)"
fact "the commit records its git" until_ok has_pid git "$run"
kill "$(pid_of wrapper "$run")"
expect "a commit whose wrapper died still runs while git runs" 75 "まだ実行中です" wait_now "$run"
kill "$(pid_of git "$run")"
rm "$hold"
expect "a commit killed before done is lost" 1 "終了コードを記録せずに止まりました" bash "$tool" wait "$run"
fact "the killed commit leaves HEAD" [ "$(git rev-parse HEAD)" = "$head" ]
fact "the killed commit leaves the index" clean
rm lost.txt

# wrapper、_commit、git をすべて止め、HEAD は写しの index の commit になっていない。
scene "fully killed commits"
head="$(git rev-parse HEAD)"
for undo in copy-matches-head foreign-commit; do
  touch "$hold"
  echo k >killed.txt
  expect "commit to be fully killed starts ($undo)" 0 "task agent-wait" bash "$tool" commit "$msg" killed.txt
  run="$(run_dir)"
  fact "the fully killed commit records its git ($undo)" until_ok has_pid git "$run"
  kill "$(pid_of wrapper "$run")" "$(pid_of child "$run")" "$(pid_of git "$run")"
  rm "$hold"
  # copy-matches-head はフックが変更を打ち消した写しを、foreign-commit は同じ HEAD の上にほかの作業が作った commit を真似る。
  if [ "$undo" = copy-matches-head ]; then
    fact "the copy is made to match HEAD" env GIT_INDEX_FILE="$run/index" git read-tree HEAD
  else
    fact "another commit lands on the same HEAD" git commit --no-verify -q --allow-empty -m foreign
  fi
  expect "a fully killed commit that did not land is lost ($undo)" 1 "終了コードを記録せずに止まりました" bash "$tool" wait "$run"
  fact "the fully killed commit leaves the index ($undo)" clean
  rm killed.txt
done
fact "the fully killed commits leave no commit of theirs" [ -z "$(git log --format=%s "$head".. | grep -vx foreign)" ]

# wrapper と _commit を止め、残った git が commit を終える。
scene "orphaned commit"
touch "$hold"
echo o >orphan.txt
expect "commit to be orphaned starts" 0 "task agent-wait" bash "$tool" commit "$msg" orphan.txt
run="$(run_dir)"
fact "the orphaned commit records its git" until_ok has_pid git "$run"
kill "$(pid_of wrapper "$run")" "$(pid_of child "$run")"
rm "$hold"
expect "wait makes the index follow a commit whose wrapper died" 0 "commit はできていました" bash "$tool" wait "$run"
fact "the orphaned git committed the path" [ "$(committed)" = orphan.txt ]
fact "the index matches the orphaned commit" clean

# wrapper と _commit を止め、残った git が commit を終えた後に、ほかの作業が古い本物の index から commit する。
scene "orphaned overtaken commit"
head="$(git rev-parse HEAD)"
touch "$hold"
echo o >orphan-overtaken.txt
expect "commit to be orphaned and overtaken starts" 0 "task agent-wait" bash "$tool" commit "$msg" orphan-overtaken.txt
run="$(run_dir)"
fact "the orphaned overtaken commit records its git" until_ok has_pid git "$run"
kill "$(pid_of wrapper "$run")" "$(pid_of child "$run")"
rm "$hold"
fact "the orphaned git moves HEAD" until_ok moved "$head"
git commit --no-verify -q -m foreign
expect "wait says another commit reverted the orphaned commit" 1 "    orphan-overtaken.txt" bash "$tool" wait "$run"
fact "wait does not say it makes the index follow the overtaken commit" not grep -qF "agent-wait が合わせます" "$out"
fact "the index matches the commit that reverted the orphaned commit" clean
rm orphan-overtaken.txt

# wrapper と _commit を止め、残った git が commit を終えた後に、agent-wait が本物の index を置き換えられない。
scene "orphaned unsynced commit"
touch "$hold"
echo u >orphan-unsynced.txt
expect "commit to be orphaned and left unsynced starts" 0 "task agent-wait" bash "$tool" commit "$msg" orphan-unsynced.txt
run="$(run_dir)"
fact "the orphaned unsynced commit records its git" until_ok has_pid git "$run"
kill "$(pid_of wrapper "$run")" "$(pid_of child "$run")"
rm "$hold"
expect "wait reports that it could not replace the index" 1 "git reset か index の置き換えに失敗した" env PATH="$tmp/failmv:$PATH" bash "$tool" wait "$run"
expect "a second wait prints the command again" 1 "git -C " bash "$tool" wait "$run"
git reset -q && rm -r -- "$run"

# main の worktree の commit が動いている間に、linked worktree で commit する。
scene "worktree commits"
touch "$hold"
echo m >main.txt
expect "commit in the main worktree starts" 0 "task agent-wait" bash "$tool" commit "$msg" main.txt
echo w >"$tmp/wt/w.txt"
expect "a running commit does not block another worktree" 0 "task agent-wait" within "$tmp/wt" bash "$tool" commit "$msg" w.txt
rm "$hold"
expect "wait in the linked worktree picks its own run" 0 "exit=0" within "$tmp/wt" bash "$tool" wait
fact "the linked worktree commits its own path" [ "$(git -C "$tmp/wt" show --name-only --format= HEAD)" = w.txt ]
fact "the linked worktree index matches its commit" within "$tmp/wt" clean
expect "wait in the main worktree picks its own run" 0 "exit=0" bash "$tool" wait
fact "the main worktree commits its own path" [ "$(committed)" = main.txt ]

# このリポジトリの外で pre-push が動いている間の push。一覧の最後には、親のディレクトリごと消えた worktree がある。
git switch -q -c topic
git worktree add -q "$tmp/zz-gone/wt" -b gone && rm -r "$tmp/zz-gone"
fact "the vanished worktree is listed last" [ "$(git worktree list --porcelain | sed -n 's/^worktree //p' | tail -n 1)" = "$tmp/zz-gone/wt" ]
start_decoy "$tmp"
expect "a pre-push outside this repository does not block push" 0 "task agent-wait -- " bash "$tool" push
expect "wait reports the push" 0 "✓ fake-push-check" bash "$tool" wait
fact "origin has the pushed branch" [ "$(git --git-dir="$tmp/origin.git" rev-parse topic)" = "$(git rev-parse HEAD)" ]
stop_decoy

# linked worktree で pre-push が動いている間の push。
start_decoy "$tmp/wt"
expect "push refuses while a pre-push runs in another worktree" 1 "前の pre-push がまだ動いています" bash "$tool" push
stop_decoy

# 終わった push の後の push。その pre-push はデーモンを起動して残す。
touch "$tmp/daemon"
expect "push after a finished push starts" 0 "task agent-wait -- " bash "$tool" push
expect "wait reports the push that starts a daemon" 0 "✓ fake-push-check" bash "$tool" wait
rm "$tmp/daemon"
fact "the pre-push hook left a daemon running" alive "$(cat "$tmp/daemon.pid")"

# デーモンが残っている間の push と、それが動いている間の push。
touch "$hold"
expect "a daemon started by a pre-push does not keep the push lock" 0 "task agent-wait -- " bash "$tool" push
run="$(run_dir)"
expect "a second push is refused while the first runs" 1 "前の agent-push がまだ動いています" bash "$tool" push
rm "$hold"
expect "wait reports the held push" 0 "✓ fake-push-check" bash "$tool" wait "$run"

if [ "$failures" -gt 0 ]; then
  echo "$failures failure(s)"
  exit 1
fi
echo "all passed"
