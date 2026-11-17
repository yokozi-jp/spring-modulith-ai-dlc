#!/usr/bin/env bash
# agent-git.sh: エージェントの commit と push をバックグラウンドで実行し、終わりを印のファイルで待つ。
# フックは tool call の時間切れより長くかかることがあり、時間切れや中断ではフックは止まらない。
# Run: bash tooling/agent-git/agent-git.sh commit <メッセージのファイル> <パス>...
#      bash tooling/agent-git/agent-git.sh push
#      bash tooling/agent-git/agent-git.sh wait [<実行のディレクトリ>]
# task agent-commit、task agent-push、task agent-wait が呼ぶ。手順は docs/tooling/dev-workflow.md にある。
# GNU/Linux だけで動く（/proc、GNU coreutils の sort -z と comm -z、procps の pgrep、util-linux の flock を使う）。
#
# 実行のディレクトリは、worktree ごとの $(git rev-parse --git-path agent-git) の下に <commit か push>.XXXXXX を作る。
# .git の中なので git の管理の外にあり、wait は成功を報告したら消し、失敗なら log と done を残す。
#   pid    background のシェル（wrapper）と git の 1 行ずつ: <wrapper か git> <PID> <開始時刻>
#          開始時刻（/proc/<PID>/stat の 22 番目の欄）を比べて、PID が別のプロセスに再利用されたものを見分ける。
#   log    git の標準出力と標準エラー
#   done   終了コード（exit=<n>）。終わるまで無い
#   msg、index、paths  commit のメッセージ、index、パスの写し（commit だけ）。git が終わったら消す

set -u

[ "$(uname -s)" = Linux ] || {
  echo "✗ agent-git.sh は GNU/Linux だけで動きます。" >&2
  exit 1
}

self="$(readlink -f "${BASH_SOURCE[0]}")"
wait_seconds="${AGENT_GIT_WAIT_SECONDS:-100}" # tool call の上限の 120 秒より短くする
# 先頭を lefthook のコマンドに絞り、この文字列を含むシェル（bash -c "pgrep ..." など）に一致させない。
prepush_pattern="${AGENT_GIT_PREPUSH_PATTERN:-^[^ ]*lefthook run pre-push}"

usage() {
  cat >&2 <<'EOF'
使い方:
  agent-git.sh commit <メッセージのファイル> <パス>...
  agent-git.sh push
  agent-git.sh wait [<実行のディレクトリ>]
使い方の誤り、拒否、開始の失敗はどれも終了コード 1 なので、理由はメッセージで読む。wait は実行中なら 75、終わっていれば git の終了コードで戻る。
EOF
  exit 1
}

fail() {
  echo "✗ $1" >&2
  exit 1
}

runs_dir() { git rev-parse --path-format=absolute --git-path agent-git; }

# changed <index> <git diff の引数>...: <index> と比べて変わったファイルを、ルートからのパスで NUL 区切りに整列して出す。
# --no-renames で名前の変更を元と先に分け、--no-relative で設定の diff.relative を無視する。
changed() {
  GIT_INDEX_FILE="$1" git --literal-pathspecs diff --name-only --no-renames --no-relative -z "${@:2}" | LC_ALL=C sort -z
}

# proc_start <PID>: プロセスの開始時刻を出す。プロセスが無いかゾンビなら何も出さない。
proc_start() {
  local stat f
  { read -r stat <"/proc/$1/stat"; } 2>/dev/null || return 0
  # 2 番目の欄（コマンド名）は空白と括弧を含みうるので、最後の ") " までを外す。残りは 3 番目の欄（状態）から始まる。
  read -ra f <<<"${stat##*) }"
  [ "${f[0]}" = Z ] || echo "${f[19]}"
}

# state <実行のディレクトリ>: running（wrapper か git が動いている）、finished（done に終了コードがある）、lost のどれかを出す。
# wrapper は done を書いてから終わるので、生きているかを先に確かめれば、どちらも止まって done が無いときだけが lost になる。
state() {
  local pid start
  while read -r _ pid start; do
    [ -n "$start" ] && [ "$(proc_start "$pid")" = "$start" ] && echo running && return
  done <"$1/pid"
  if grep -qx 'exit=[0-9]\+' "$1/done" 2>/dev/null; then echo finished; else echo lost; fi
}

discard_copies() { rm -f -- "$1/msg" "$1/index" "$1/index.lock" "$1/paths"; }

# background <実行のディレクトリ> <コマンド>...: コマンドを子として動かし、終わったら done に終了コードを書く。
background() {
  local run="$1" code
  shift
  # 9 番は agent-push のロックで、この wrapper だけが持つ。フックが起動したデーモンが受け継ぐと、push の後もロックが残る。
  "$@" >"$run/log" 2>&1 9>&- &
  echo "git $! $(proc_start "$!")" >>"$run/pid"
  wait "$!"
  code=$?
  if [ "$code" = 0 ] && [ -f "$run/paths" ]; then
    # commit は index の写しから作ったので、本物の index の渡したパスを、できた commit に合わせる。
    git --literal-pathspecs reset -q --pathspec-from-file="$run/paths" --pathspec-file-nul >>"$run/log" 2>&1 || {
      code=$?
      echo "✗ commit はできましたが、本物の index を合わせられませんでした。git reset -q -- <パス> を実行してください。" >>"$run/log"
    }
  fi
  discard_copies "$run"
  echo "exit=$code" >"$run/done.tmp" && mv -- "$run/done.tmp" "$run/done"
}

# launch <実行のディレクトリ> <コマンド>...: background を入出力から切り離して始め、待ち方を出す。
# 呼んだ側（Task など）は、コマンドの終わりを待たずに戻る。
launch() {
  local run="$1"
  NO_COLOR=1 nohup bash "$self" _background "$@" </dev/null >/dev/null 2>&1 &
  echo "wrapper $! $(proc_start "$!")" >>"$run/pid"
  echo "バックグラウンドで開始しました。終わるまで次を繰り返します: task agent-wait -- $run"
}

new_run() {
  local runs
  runs="$(runs_dir)" && mkdir -p -- "$runs" && mktemp -d "$runs/$1.XXXXXX"
}

commit() {
  [ $# -ge 2 ] || usage
  local msg="$1" r run outside partial
  shift
  git rev-parse --is-inside-work-tree >/dev/null || exit 1
  [ -s "$msg" ] || fail "メッセージのファイル $msg が無いか空です。"
  # 同じ worktree の 2 つの commit は同じ HEAD から始まり、後に終わったほうを git が拒むので、フックを待たせる前に止める。
  for r in "$(runs_dir)"/commit.*; do
    [ -f "$r/pid" ] && [ "$(state "$r")" = running ] &&
      fail "この worktree の前の agent-commit がまだ動いています。終わってから commit してください: task agent-wait -- $r"
  done
  run="$(new_run commit)" || exit 1
  trap 'rm -r -- "$run"' EXIT
  # git は pre-commit の間 index をロックせず、フックの後に読み直すので、本物の index から commit すると、フックの間にほかの作業が git add したものが混ざる。
  # そこで写しを先に作り、確かめるのも stage するのも commit するのも写しにする。確かめてから写すと、その間の git add が混ざる。
  # git commit --only <パス> は、フックの間ずっと本物の index をロックし、ほかの作業の git add を失敗させるので使わない。
  cp -- "$(git rev-parse --git-path index)" "$run/index" || exit 1
  outside="$(LC_ALL=C comm -z -23 <(changed "$run/index" --cached --) <(changed "$run/index" --cached -- "$@") | tr '\0' '\n')"
  [ -z "$outside" ] || fail "渡したパスの外に stage 済みの変更があり、このまま commit すると混ざるので止めます。
$(sed 's/^/    /' <<<"$outside")
  自分の変更ならパスに足してください。
  別の作業が stage したものなら、その作業に確かめてから git restore --staged -- <パス> で外してください。"
  partial="$(LC_ALL=C comm -z -12 <(changed "$run/index" --cached -- "$@") <(changed "$run/index" -- "$@") | tr '\0' '\n')"
  [ -z "$partial" ] || fail "次のパスは stage 済みの変更と stage していない変更の両方を持ち、git add すると git add -p で選んだ一部が失われるので止めます。
$(sed 's/^/    /' <<<"$partial")
  全体を commit するなら git add -- <パス> で stage してからやり直し、一部だけなら git commit で直接 commit してください。"
  GIT_INDEX_FILE="$run/index" git --literal-pathspecs add -- "$@" || exit 1
  GIT_INDEX_FILE="$run/index" git diff --cached --quiet && fail "渡したパスに commit する変更がありません。"
  # git はメッセージのファイルを pre-commit の後に読むので、待つ間に元のファイルを消しても困らない写しを渡す。
  { cp -- "$msg" "$run/msg" && printf '%s\0' "$@" >"$run/paths"; } || exit 1
  trap - EXIT
  launch "$run" env GIT_INDEX_FILE="$run/index" git commit -F "$run/msg"
}

push() {
  [ $# -eq 0 ] || usage
  local branch lock running run
  branch="$(git symbolic-ref --quiet --short HEAD)" || fail "ブランチを checkout していないので push できません。"
  # pre-push の task test は compose-test のプロジェクト名とポートが固定なので、worktree をまたいで push を一つずつにする。
  # ロックは background の wrapper が受け継いで持ち続け、push が終わるか止まると外れる。
  lock="$(git rev-parse --path-format=absolute --git-common-dir)/agent-git-push.lock"
  exec 9>>"$lock" || exit 1
  flock -n 9 || fail "前の agent-push がまだ動いています。終わってから push してください: task agent-wait -- $(cat "$lock")"
  # agent-push を通さずに始めた push の pre-push も重ねない。
  running="$(pgrep -fa "$prepush_pattern")"
  case $? in
    0) fail "前の pre-push がまだ動いています。終わるのを待つか止めてから push してください（止めたときの片付けは docs/tooling/dev-workflow.md）。
$running" ;;
    1) ;;
    *) fail "pgrep で前の pre-push を確かめられません。" ;;
  esac
  run="$(new_run push)" || exit 1
  echo "$run" >"$lock"
  launch "$run" git push -u origin "$branch"
}

# plain: 色の制御文字を外す。lefthook 2.1.14 は区切り線の色を NO_COLOR や colors の設定によらず付ける。
plain() { LC_ALL=C sed 's/\x1b\[[0-?]*[ -/]*[@-~]//g'; }

wait_run() {
  [ $# -le 1 ] || usage
  local run="${1:-}" latest waited=0 s code
  if [ -z "$run" ]; then
    # pid は始めたときに書いて以後変えないので、更新時刻が始めた順になる。
    latest="$(ls -t -- "$(runs_dir)"/*/pid 2>/dev/null | head -n 1)"
    [ -n "$latest" ] || fail "この worktree に待つ実行がありません。"
    run="${latest%/pid}"
  fi
  [ -f "$run/pid" ] || fail "$run は agent-git.sh の実行のディレクトリではありません。"
  [[ "$wait_seconds" =~ ^[0-9]+$ ]] || fail "AGENT_GIT_WAIT_SECONDS は 0 以上の整数で指定してください: $wait_seconds"
  while s="$(state "$run")" && [ "$s" = running ] && [ "$waited" -lt "$wait_seconds" ]; do
    sleep 1
    waited=$((waited + 1))
  done
  case "$s" in
    running)
      echo "まだ実行中です（$waited 秒待ちました）。もう一度実行してください: task agent-wait -- $run"
      exit 75
      ;;
    lost)
      discard_copies "$run"
      fail "終了コードを記録せずに止まりました（done が無いか壊れています）。git status で状態を確かめてから、やり直してください。ログ: $run/log"
      ;;
  esac
  code="$(sed -n 's/^exit=//p' "$run/done")"
  # Lefthook の要約の行と、注意（⚠ の行と字下げした続き。adr-check など）を出す。
  plain <"$run/log" | awk '/^(✓|✗)/ { print; next } /^⚠/ { w = 1; print; next } w && /^  / { print; next } { w = 0 }'
  if [ "$code" = 0 ]; then
    echo "exit=0"
    rm -r -- "$run"
    exit 0
  fi
  echo "ログの末尾:"
  tail -n 20 "$run/log" | plain | sed 's/^/    /'
  echo "exit=$code"
  echo "ログ: $run/log"
  exit "$code"
}

case "${1:-}" in
  commit) shift && commit "$@" ;;
  push) shift && push "$@" ;;
  wait) shift && wait_run "$@" ;;
  _background) shift && background "$@" ;;
  *) usage ;;
esac
