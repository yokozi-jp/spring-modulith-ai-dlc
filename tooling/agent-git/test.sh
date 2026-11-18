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
untrusted_ctime() { GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=core.trustctime GIT_CONFIG_VALUE_0=false "$@"; }
pid_of() { awk -v n="$1" '$1 == n { print $2 }' "$2/pid"; }
has_pid() { [ -n "$(pid_of "$1" "$2")" ]; }
alive() { kill -0 "$1" 2>/dev/null; }
committed() { git show --name-only --format= HEAD; }
moved() { [ "$(git rev-parse HEAD)" != "$1" ]; }
clean() { [ -z "$(git diff --cached --name-only)" ]; }
scene() { fact "the $1 starts from a clean index" clean; }
decoy_running() { pgrep -f "$AGENT_GIT_PREPUSH_PATTERN" >/dev/null; }
start_decoy() {
  # sleep が coreutils を呼ぶ shebang のラッパーの環境（Amazon Linux）では、exec -a の名前が消えるので、bash に名前を付ける。
  # 末尾の : は、bash が最後のコマンドの sleep を exec して名前を失うのを止める。
  (cd "$1" && exec -a "agent-git-test-decoy-$$" bash -c 'sleep 30; :') &
  decoy=$!
  until_ok decoy_running
}
stop_decoy() {
  kill "$decoy"
  decoy=""
  until_ok not decoy_running
}
# held_commit <ファイル>: フックを止めたまま新しいファイルの commit を始め、run を実行のディレクトリにし、git の PID を待つ。
held_commit() {
  touch "$hold"
  echo "$1" >"$1"
  expect "commit of $1 starts" 0 "task agent-wait" bash "$tool" commit "$msg" "$1"
  run="$(run_dir)"
  fact "the commit of $1 records its git" until_ok has_pid git "$run"
}
# failing_git <パターン> <コマンド>...: パターンに当たる最初の git だけが失敗する PATH で、コマンドを動かす。
# パターンは case の glob で、「<GIT_INDEX_FILE> <引数>... 」に当てる。
failing_git() {
  echo "$1" >"$tmp/fail-git"
  PATH="$tmp/failgit:$PATH" "${@:2}"
}

git init -q --bare -b main "$tmp/origin.git"
git init -q -b main "$tmp/repo"
cd "$tmp/repo" || exit 1
git remote add origin "$tmp/origin.git"
mkdir sub dot
for f in same.txt part.txt fixed-out.txt lockme rm.txt mv.txt untrack.txt ignore.txt foreign.txt; do echo base >"$f"; done
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
# ほかの作業が、合わせる前の本物の index から git commit するのを真似る。
# keep なら、その前に作業ツリーをすべて stage する。only なら、foreign.txt だけを git commit -- <パス> で commit する。
# amend なら commit を git commit --amend で作り直し、reparent なら同じ木の commit を親を付け替えて作り、HEAD をそこへ動かす。
cat >.git/hooks/post-commit <<EOF
#!/bin/sh
[ -e '$tmp/overtake' ] || exit 0
mode="\$(cat '$tmp/overtake')" && rm '$tmp/overtake' && unset GIT_INDEX_FILE
case "\$mode" in
  keep) git add -A && git commit --no-verify -q -m foreign ;;
  only) git commit --no-verify -q -m foreign -- foreign.txt ;;
  amend) git commit --no-verify -q --amend --allow-empty -m foreign ;;
  reparent) git update-ref HEAD "\$(git commit-tree 'HEAD^{tree}' -p HEAD~2 -m foreign)" ;;
  *) git commit --no-verify -q -m foreign ;;
esac
EOF
chmod +x .git/hooks/pre-commit .git/hooks/pre-push .git/hooks/post-commit
git worktree add -q "$tmp/wt" -b wt
for broken in cp mv; do mkdir "$tmp/fail$broken" && printf '#!/bin/sh\nexit 1\n' >"$tmp/fail$broken/$broken" && chmod +x "$tmp/fail$broken/$broken"; done
mkdir "$tmp/failgit" && cat >"$tmp/failgit/git" <<EOF && chmod +x "$tmp/failgit/git"
#!/bin/sh
if [ -e '$tmp/fail-git' ]; then
  pattern="\$(cat '$tmp/fail-git')"
  case "\${GIT_INDEX_FILE:-} \$* " in \$pattern) rm '$tmp/fail-git' && echo "fake git failure" >&2 && exit 128 ;; esac
fi
exec '$(command -v git)' "\$@"
EOF
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
fact "the refusal tells to unstage a change of another task" grep -qF "git restore --staged" "$out"
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

# index と同じ秒に、同じ大きさで同じ更新時刻のまま書き換えたファイルを、渡したパスと渡さないパスに 1 つずつ置く。
# git は更新時刻が index 以降のファイルを中身で確かめる。ctime の違いで気付かないよう core.trustctime=false にする。
scene "racily clean commit"
echo aaaa >racy.txt && echo oooo >racy-other.txt && touch -d @1700000000 racy.txt racy-other.txt
git add racy.txt racy-other.txt && git commit -q --no-verify -m racy
echo bbbb >racy.txt && echo pppp >racy-other.txt && touch -d @1700000000 racy.txt racy-other.txt .git/index
expect "commit of a path rewritten in the second of the index starts" 0 "task agent-wait" untrusted_ctime bash "$tool" commit "$msg" racy.txt
expect "wait reports the commit of the rewritten path" 0 "exit=0" untrusted_ctime bash "$tool" wait
fact "the commit holds the rewritten content" [ "$(git show HEAD:racy.txt)" = bbbb ]
fact "the index still sees the rewrite of the path not given" [ "$(untrusted_ctime git diff --name-only)" = racy-other.txt ]
git checkout -- racy-other.txt

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

# 衝突を解決していない merge、merge、cherry-pick、revert の途中のパスの外の stage、HEAD から何も変えない merge。
scene "merge commits"
git switch -q -c side && echo side >conflict.txt && echo side >side.txt && git add conflict.txt side.txt && git commit -q --no-verify -m side
git switch -q main && echo main >conflict.txt && git add conflict.txt && git commit -q --no-verify -m main
git merge -q side >/dev/null 2>&1
expect "refuses an unresolved conflict" 1 "    conflict.txt" bash "$tool" commit "$msg" conflict.txt
fact "the refusal tells to resolve the conflict" grep -qF "衝突を解決してから" "$out"
git merge --abort && git checkout -q side -- side.txt
# 途中の操作の印だけを置き、side.txt を取り込んだ変更とみなす。
for ref in MERGE_HEAD CHERRY_PICK_HEAD REVERT_HEAD; do
  git rev-parse side >".git/$ref"
  expect "refuses a path outside the given ones during $ref" 1 "    side.txt" bash "$tool" commit "$msg" conflict.txt
  fact "the refusal during $ref says to pass every staged path" grep -qF "stage 済みのパスをすべて渡して" "$out"
  fact "the refusal during $ref does not tell to unstage" not grep -qF "git restore --staged" "$out"
  rm ".git/$ref"
done
git rm -q --cached side.txt && rm side.txt
git merge -q -s ours --no-commit side >/dev/null 2>&1
expect "commit of a merge with no change starts" 0 "task agent-wait" bash "$tool" commit "$msg" .
expect "wait reports the merge commit" 0 "exit=0" bash "$tool" wait
fact "the merge commit has both parents" [ "$(git rev-parse HEAD^2)" = "$(git rev-parse side)" ]
fact "the index matches the merge commit" clean

# merge の途中で、ほかの作業が追跡中のファイルの stage していない変更と、新しいファイルを残す。
scene "merge commit beside another task's changes"
git switch -q -c side2 && echo s >merged-a.txt && echo s >merged-b.txt && git add merged-a.txt merged-b.txt && git commit -q --no-verify -m side2
git switch -q main && git merge -q --no-ff --no-commit side2 >/dev/null 2>&1
echo theirs >>foreign.txt && echo theirs >merge-untracked.txt
expect "refuses a part of the staged merge" 1 "    merged-b.txt" bash "$tool" commit "$msg" merged-a.txt
fact "the refusal during the merge does not offer ." not grep -qF " . " "$out"
expect "commit of every staged path of the merge starts" 0 "task agent-wait" bash "$tool" commit "$msg" merged-a.txt merged-b.txt
expect "wait reports the merge commit of the staged paths" 0 "exit=0" bash "$tool" wait
fact "the merge commit of the staged paths has both parents" [ "$(git rev-parse HEAD^2)" = "$(git rev-parse side2)" ]
fact "the merge commit leaves out the changes of another task" [ "$(git status --porcelain)" = "$(printf ' M foreign.txt\n?? merge-untracked.txt')" ]
git checkout -- foreign.txt && rm merge-untracked.txt

# まだ commit も index のファイルも無いリポジトリで、フックが失敗する最初の commit と、成功する最初の commit。
git init -q -b main "$tmp/fresh" && cp .git/hooks/pre-commit "$tmp/fresh/.git/hooks/"
fact "the fresh repository has no index file" [ ! -e "$tmp/fresh/.git/index" ]
echo x >"$tmp/fresh/fail"
expect "a first commit with a failing hook starts" 0 "task agent-wait" within "$tmp/fresh" bash "$tool" commit "$msg" fail
expect "wait reports git's exit code for the failed first commit" 1 "✗ fake-check" within "$tmp/fresh" bash "$tool" wait
fact "the failed first commit is not reported as a git failure" not grep -qF "確かめられず" "$out"
fact "the failed first commit leaves no HEAD" not git -C "$tmp/fresh" rev-parse -q --verify HEAD
rm "$tmp/fresh/fail" && echo f >"$tmp/fresh/first.txt"
expect "a first commit starts" 0 "task agent-wait" within "$tmp/fresh" bash "$tool" commit "$msg" first.txt
expect "wait reports the first commit" 0 "exit=0" within "$tmp/fresh" bash "$tool" wait
fact "the first commit holds the given path" [ "$(git -C "$tmp/fresh" ls-tree --name-only HEAD)" = first.txt ]
fact "the index matches the first commit" within "$tmp/fresh" clean

# まだ commit の無いリポジトリで、最初の commit の直後、本物の index を合わせる前に、ほかの作業が古い本物の index から commit する。
git init -q -b main "$tmp/fresh-overtaken" && cp .git/hooks/post-commit "$tmp/fresh-overtaken/.git/hooks/"
echo v >"$tmp/fresh-overtaken/first.txt" && touch "$tmp/overtake"
expect "a first commit to be overtaken starts" 0 "task agent-wait" within "$tmp/fresh-overtaken" bash "$tool" commit "$msg" first.txt
expect "wait says another commit reverted the first commit" 1 "    first.txt" within "$tmp/fresh-overtaken" bash "$tool" wait
fact "the reverting commit is on top of the first commit" [ "$(git -C "$tmp/fresh-overtaken" rev-list --count HEAD)" = 2 ]
fact "the index matches the commit that reverted the first commit" within "$tmp/fresh-overtaken" clean

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

# commit の直後、本物の index を合わせる前に、ほかの作業が別のパスだけを git commit -- <パス> で commit する。
scene "commit overtaken by a commit of another path"
echo n >overtaken-other.txt && echo changed >foreign.txt && echo only >"$tmp/overtake"
expect "commit to be overtaken by a commit of another path starts" 0 "task agent-wait" bash "$tool" commit "$msg" overtaken-other.txt
expect "wait reports success when the commit on top changes another path" 0 "exit=0" bash "$tool" wait
fact "the commit of another path is on top of the commit" [ "$(git show --name-only --format=%s HEAD)" = "$(printf 'foreign\n\nforeign.txt')" ]
fact "the index matches the commit of another path" clean

# commit の直後、本物の index を合わせる前に、ほかの作業が HEAD を作り直すか、親を付け替えて動かす。
scene "commits moved off HEAD"
head="$(git rev-parse HEAD)"
for mode in amend reparent; do
  echo m >"moved-$mode.txt" && echo "$mode" >"$tmp/overtake"
  expect "commit to be moved off HEAD by $mode starts" 0 "task agent-wait" bash "$tool" commit "$msg" "moved-$mode.txt"
  expect "wait says the commit is not on HEAD after $mode" 1 "git reflog で commit と HEAD の動きを確かめてください" bash "$tool" wait
  git reset -q "$head" && rm "moved-$mode.txt"
done

# commit の直後、commit ができたかを確かめる git が失敗する。
# 失敗するのは、始めたときの HEAD からの rev-list、commit の親の rev-parse、写しの index と commit の git diff。
scene "commits whose landing check fails"
for check in rev-list parent tree; do
  case "$check" in
    rev-list) pattern='* rev-list *' ;;
    parent) pattern='* rev-parse -q --verify [0-9a-f]*^ ' ;;
    tree) pattern='*/commit/index diff --cached --quiet * -- ' ;;
    *) echo "no pattern: $check"; exit 1 ;;
  esac
  echo l >"landing-$check.txt"
  expect "commit whose $check check fails starts" 0 "task agent-wait" failing_git "$pattern" bash "$tool" commit "$msg" "landing-$check.txt"
  expect "wait reports that git failed in the $check check" 1 "commit ができたかを確かめられず" bash "$tool" wait
  fact "the failing git ran in the $check check" [ ! -e "$tmp/fail-git" ]
  git reset -q
done

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
fact "the failed run drops the state of the commit" [ ! -e "$run/commit" ]
fact "wait tells how to remove the failed run" grep -qF "rm -r -- $run で消せます" "$out"
expect "wait without a run skips the reported failure" 1 "まだ報告していない実行がありません" bash "$tool" wait
expect "wait with the run reports the failure again" 1 "✗ fake-check" bash "$tool" wait "$run"
echo exit=oops >"$run/done"
expect "a malformed done counts as lost" 1 "終了コードを記録せずに止まりました" bash "$tool" wait "$run"
fact "wait tells how to remove the lost run" grep -qF "rm -r -- $run で消せます" "$out"
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
fact "wait does not tell to remove a run left unsynced" not grep -qF "で消せます" "$out"
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
held_commit lost.txt
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
for undo in copy-matches-head foreign-commit amended-head; do
  held_commit "killed-$undo.txt"
  kill "$(pid_of wrapper "$run")" "$(pid_of child "$run")" "$(pid_of git "$run")"
  rm "$hold"
  # copy-matches-head はフックが変更を打ち消した写しを、foreign-commit は同じ HEAD の上にほかの作業が作った commit を真似る。
  # amended-head は、写しが HEAD と同じ木のときに、ほかの作業が HEAD を git commit --amend で作り直すのを真似る。
  case "$undo" in
    copy-matches-head) fact "the copy is made to match HEAD" env GIT_INDEX_FILE="$run/commit/index" git read-tree HEAD ;;
    foreign-commit) fact "another commit lands on the same HEAD" git commit --no-verify -q --allow-empty -m foreign ;;
    amended-head)
      fact "the copy is made to match HEAD before the amend" env GIT_INDEX_FILE="$run/commit/index" git read-tree HEAD
      fact "another task amends HEAD with the same tree" git commit --no-verify -q --amend --allow-empty -m foreign
      ;;
    *) echo "no setup: $undo"; exit 1 ;;
  esac
  expect "a fully killed commit that did not land is lost ($undo)" 1 "終了コードを記録せずに止まりました" bash "$tool" wait "$run"
  fact "the fully killed commit leaves the index ($undo)" clean
  rm "killed-$undo.txt"
done
fact "the fully killed commits leave no commit of theirs" [ -z "$(git log --format=%s "$head".. | grep -vx foreign)" ]

# wrapper と _commit を止め、残った git が commit を終える。
scene "orphaned commit"
held_commit orphan.txt
kill "$(pid_of wrapper "$run")" "$(pid_of child "$run")"
rm "$hold"
fact "the orphaned git finishes" until_ok not alive "$(pid_of git "$run")"
expect "the next commit is refused until wait settles the orphaned commit" 1 "後片付けが済んでいません" bash "$tool" commit "$msg" orphan.txt
expect "wait from a subdirectory makes the index follow a commit whose wrapper died" 0 "commit はできていました" \
  within sub bash "$tool" wait "$(realpath --relative-to=sub "$run")"
fact "the orphaned git committed the path" [ "$(committed)" = orphan.txt ]
fact "the index matches the orphaned commit" clean

# wrapper と _commit を止め、残った git が commit を終えた後に、ほかの作業が古い本物の index から commit する。
scene "orphaned overtaken commit"
head="$(git rev-parse HEAD)"
held_commit orphan-overtaken.txt
kill "$(pid_of wrapper "$run")" "$(pid_of child "$run")"
rm "$hold"
fact "the orphaned git moves HEAD" until_ok moved "$head"
git commit --no-verify -q -m foreign
expect "wait says another commit reverted the orphaned commit" 1 "    orphan-overtaken.txt" bash "$tool" wait "$run"
fact "wait does not say it makes the index follow the overtaken commit" not grep -qF "agent-wait が合わせます" "$out"
fact "the index matches the commit that reverted the orphaned commit" clean
rm orphan-overtaken.txt

# wrapper と _commit を止め、残った git が commit を終えた後に、ほかの作業が作業ツリーをすべて stage してから commit する。
scene "orphaned commit kept by another commit"
head="$(git rev-parse HEAD)"
echo f >orphan-foreign.txt
held_commit orphan-kept.txt
kill "$(pid_of wrapper "$run")" "$(pid_of child "$run")"
rm "$hold"
fact "the orphaned kept git moves HEAD" until_ok moved "$head"
git add -A && git commit --no-verify -q -m foreign
fact "the commit on top adds only the other path" [ "$(git show --name-only --format= HEAD)" = orphan-foreign.txt ]
expect "wait makes the index follow an orphaned commit that the commit on top keeps" 0 "commit はできていました" bash "$tool" wait "$run"
fact "the index matches the commit on top of the orphaned commit" clean

# wrapper と _commit を止め、残った git が commit を終えた後に、agent-wait が本物の index を写せないか置き換えられない。
scene "orphaned unsynced commits"
for broken in cp mv; do
  case "$broken" in
    cp) reason="index を index.lock に写せなかった" ;;
    mv) reason="git reset か index の置き換えに失敗した" ;;
    *) echo "no reason: $broken"; exit 1 ;;
  esac
  held_commit "orphan-unsynced-$broken.txt"
  kill "$(pid_of wrapper "$run")" "$(pid_of child "$run")"
  rm "$hold"
  expect "wait reports that $broken failed on the index" 1 "$reason" env PATH="$tmp/fail$broken:$PATH" bash "$tool" wait "$run"
  expect "a second wait prints the command again after $broken failed" 1 "git -C " bash "$tool" wait "$run"
  git reset -q && rm -r -- "$run"
done

# wrapper と _commit を止め、残った git が commit を終えた後に、agent-wait の途中で git が失敗する。
# 失敗するのは、HEAD の rev-parse、上の commit が戻したかを確かめる 2 つの git diff（commit と、上に載った commit）、
# 2 つの index の ls-files、base か本物の index と commit の git diff。
scene "orphaned commits whose git fails"
for check in head commit-diff head-diff entries base now; do
  held_commit "orphan-$check.txt"
  kill "$(pid_of wrapper "$run")" "$(pid_of child "$run")"
  rm "$hold"
  fact "the orphaned git finishes before the $check check" until_ok not alive "$(pid_of git "$run")"
  case "$check" in
    head) pattern='* rev-parse -q --verify HEAD ' ;;
    commit-diff) pattern='* -z [0-9a-f]* [0-9a-f]* -- ' ;;
    head-diff)
      git add "orphan-$check.txt" && git commit --no-verify -q --allow-empty -m foreign
      pattern="* -z [0-9a-f]* $(git rev-parse HEAD) -- "
      ;;
    entries) pattern='* ls-files -s -z *' ;;
    base) pattern='*/commit/base * --name-only * --cached *' ;;
    now) pattern='*/index.lock * --name-only * --cached *' ;;
    *) echo "no pattern: $check"; exit 1 ;;
  esac
  expect "wait reports that git failed in the $check check" 1 "本物の index を合わせていません" failing_git "$pattern" bash "$tool" wait "$run"
  fact "the failing git ran in the $check check" [ ! -e "$tmp/fail-git" ]
  git reset -q
done

# 確かめる途中で git が失敗すると、理由を取り違えずに、始める前に止まる。
# 渡すのは stage したファイル、stage していない変更、新しいファイルで、一覧のどれが欠けても、commit が始まるか別の理由で止まる。
# check_paths の git は、写しの index を読むことも確かめるため、写しのパスで始まるパターンで失敗させる。
# MERGE_HEAD を確かめる git は 2 つあり、check_paths の外の git は GIT_INDEX_FILE が空なので、空白で始まるパターンで見分ける。
scene "git failures before the commit"
echo s >gitfail-staged.txt && git add gitfail-staged.txt && echo changed >same.txt && echo n >gitfail-new.txt
for check in head unmatched conflicts in-progress staged given pending untracked merge; do
  case "$check" in
    head) pattern='* rev-parse -q --verify HEAD ' ;;
    unmatched) pattern='*/commit/index * --error-unmatch *' ;;
    conflicts) pattern='*/commit/index *--diff-filter=U*' ;;
    in-progress) pattern='*/commit/index rev-parse -q --verify MERGE_HEAD ' ;;
    staged) pattern='*/commit/index * --cached -- ' ;;
    given) pattern='*/commit/index * --cached -- ?*' ;;
    pending) pattern='*/commit/index * --ignore-submodules=dirty *' ;;
    untracked) pattern='*/commit/index * ls-files -z -o *' ;;
    merge) pattern=' rev-parse -q --verify MERGE_HEAD ' ;;
    *) echo "no pattern: $check"; exit 1 ;;
  esac
  expect "commit stops when git fails in the $check check" 1 "fake git failure" \
    failing_git "$pattern" bash "$tool" commit "$msg" gitfail-staged.txt same.txt gitfail-new.txt
  fact "the stop gives no other reason in the $check check" not grep -qF "✗" "$out"
  fact "the failing git ran in the $check check before the commit" [ ! -e "$tmp/fail-git" ]
done
git rm -q --cached gitfail-staged.txt && rm gitfail-staged.txt gitfail-new.txt && git checkout -- same.txt

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

# 作業ツリーに変更がある submodule の、stage した gitlink の更新を渡す。
scene "submodule commit"
git init -q -b main sm && echo 1 >sm/f && git -C sm add f && git -C sm commit -q -m c1
git add sm 2>/dev/null && git commit -q --no-verify -m submodule
echo 2 >sm/f && git -C sm commit -q -am c2 && git add sm && echo dirty >>sm/f
expect "commit of a gitlink staged with a dirty submodule starts" 0 "task agent-wait" bash "$tool" commit "$msg" sm
expect "wait reports the gitlink commit" 0 "exit=0" bash "$tool" wait
fact "the commit holds the staged gitlink" [ "$(git rev-parse HEAD:sm)" = "$(git -C sm rev-parse HEAD)" ]
fact "the index matches the gitlink commit" clean
git -C sm commit -q -am c3 && git add sm && git -C sm commit -q --allow-empty -m c4
expect "refuses a gitlink whose submodule moved after the stage" 1 "    sm" bash "$tool" commit "$msg" sm
git reset -q sm

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
