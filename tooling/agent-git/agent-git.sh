#!/usr/bin/env bash
# agent-git.sh: エージェントの commit と push をバックグラウンドで実行し、終わりを印のファイルで待つ。
# フックは tool call の時間切れより長くかかることがあり、時間切れや中断ではフックは止まらない。
# Run: bash tooling/agent-git/agent-git.sh commit <メッセージのファイル> <パス>...
#      bash tooling/agent-git/agent-git.sh push
#      bash tooling/agent-git/agent-git.sh wait <実行のディレクトリ> [<待つ秒数>]
# task agent-commit、task agent-push、task agent-wait が呼ぶ。手順は docs/tooling/dev-workflow.md にある。
#
# 実行のディレクトリは mktemp -d で 1 回の実行ごとに作り、次のファイルを置く。
#   pid   バックグラウンドのプロセスの PID
#   log   git commit か git push の標準出力と標準エラー
#   done  終了コード（exit=<n>）。終わるまで無い
#   msg   commit のメッセージの写し（commit だけ）
#   index commit する index の写し（commit だけ）

set -u

wait_seconds=100 # tool call の上限の 120 秒より短くする

usage() {
  cat >&2 <<'EOF'
使い方:
  agent-git.sh commit <メッセージのファイル> <パス>...
  agent-git.sh push
  agent-git.sh wait <実行のディレクトリ> [<待つ秒数>]
EOF
  exit 1
}

fail() {
  echo "✗ $1" >&2
  exit 1
}

# staged [<パス>...]: stage 済みのファイルを、リポジトリのルートからのパスで NUL 区切りに整列して出す。
# --no-renames で名前の変更を元と先に分け、--no-relative で設定の diff.relative を無視する。
staged() {
  git --literal-pathspecs diff --cached --name-only --no-renames --no-relative -z -- "$@" | LC_ALL=C sort -z
}

# launch <実行のディレクトリ> <コマンド>...: コマンドをバックグラウンドで動かし、待ち方を出す。
# 入出力をすべて切り離すので、呼んだ側（Task など）はコマンドの終わりを待たずに戻る。
launch() {
  local run="$1"
  shift
  NO_COLOR=1 nohup bash -c '"${@:2}" >"$1/log" 2>&1; echo "exit=$?" >"$1/done.tmp"; mv "$1/done.tmp" "$1/done"' \
    _ "$run" "$@" </dev/null >/dev/null 2>&1 &
  echo "$!" >"$run/pid"
  echo "バックグラウンドで開始しました。"
  echo "終わるまで次を繰り返します（1 回で最大 $wait_seconds 秒待ちます）: task agent-wait -- $run"
}

commit() {
  [ $# -ge 2 ] || usage
  local msg="$1" outside run
  shift
  git rev-parse --is-inside-work-tree >/dev/null || exit 1
  [ -s "$msg" ] || fail "メッセージのファイル $msg が無いか空です。"
  outside="$(LC_ALL=C comm -z -23 <(staged) <(staged "$@") | tr '\0' '\n')"
  if [ -n "$outside" ]; then
    fail "渡したパスの外に stage 済みの変更があり、このまま commit すると混ざるので止めます。
$(sed 's/^/    /' <<<"$outside")
  自分の変更ならパスに足してください。
  別の作業が stage したものなら、その作業に確かめてから git restore --staged -- <パス> で外してください。"
  fi
  git --literal-pathspecs add -- "$@" || exit 1
  git diff --cached --quiet && fail "渡したパスに commit する変更がありません。"
  run="$(mktemp -d "${TMPDIR:-/tmp}/agent-git-commit.XXXXXX")" || exit 1
  # git はメッセージのファイルを pre-commit の後に読むので、待つ間に元のファイルを消しても困らない写しを渡す。
  cp -- "$msg" "$run/msg" || exit 1
  # git は pre-commit の間 index をロックせず、フックの後に読み直す。
  # フックの間にほかの作業が git add したものが混ざらないよう、この時点の index の写しから commit する。
  # ponytail: フックが写しに git add した修正（lefthook の stage_fixed）は本物の index に戻らない。stage_fixed を使うなら、commit の後に渡したパスを git reset する。
  cp -- "$(git rev-parse --git-path index)" "$run/index" || exit 1
  launch "$run" env GIT_INDEX_FILE="$run/index" git commit -F "$run/msg"
}

push() {
  [ $# -eq 0 ] || usage
  local branch running run
  branch="$(git symbolic-ref --quiet --short HEAD)" || fail "ブランチを checkout していないので push できません。"
  # pre-push の task test は compose-test のプロジェクト名とポートが固定なので、別の worktree の pre-push とも重ねない。
  # 先頭を lefthook のコマンドに絞り、この文字列を含むシェル（bash -c "pgrep ..." など）に一致させない。
  running="$(pgrep -fa '^[^ ]*lefthook run pre-push')"
  case $? in
    0) fail "前の pre-push がまだ動いています。終わるのを待つか止めてから push してください（止めたときの片付けは docs/tooling/dev-workflow.md）。
$running" ;;
    1) ;;
    *) fail "pgrep で前の pre-push を確かめられません。" ;;
  esac
  run="$(mktemp -d "${TMPDIR:-/tmp}/agent-git-push.XXXXXX")" || exit 1
  launch "$run" git push -u origin "$branch"
}

# state <実行のディレクトリ>: finished（印がある）、running（プロセスが動いている）、lost（印を書かずに止まった）のどれかを出す。
state() {
  if [ -f "$1/done" ]; then
    echo finished
  elif kill -0 "$(cat "$1/pid")" 2>/dev/null; then
    echo running
  elif [ -f "$1/done" ]; then # 2 回の確かめの間に終わった
    echo finished
  else
    echo lost
  fi
}

wait_run() {
  [ $# -ge 1 ] && [ $# -le 2 ] || usage
  local run="$1" limit="${2:-$wait_seconds}" waited=0 s code
  [ -f "$run/pid" ] || fail "$run は agent-git.sh の実行のディレクトリではありません。"
  [[ "$limit" =~ ^[0-9]+$ ]] || fail "待つ秒数は 0 以上の整数で指定してください: $limit"
  while s="$(state "$run")" && [ "$s" = running ] && [ "$waited" -lt "$limit" ]; do
    sleep 1
    waited=$((waited + 1))
  done
  case "$s" in
    finished)
      code="$(sed -n 's/^exit=//p' "$run/done")"
      grep -E '^(✓|✗)' "$run/log"
      if [ "$code" != 0 ]; then
        echo "ログの末尾:"
        tail -n 20 "$run/log" | sed 's/^/    /'
      fi
      echo "exit=$code"
      echo "ログ: $run/log"
      exit "$code"
      ;;
    running)
      echo "まだ実行中です（$waited 秒待ちました）。もう一度実行してください: task agent-wait -- $run"
      exit 75
      ;;
    lost)
      echo "✗ 終了の印を書かずに止まりました。git status で状態を確かめてから、やり直してください。ログ: $run/log"
      exit 1
      ;;
  esac
}

case "${1:-}" in
  commit) shift && commit "$@" ;;
  push) shift && push "$@" ;;
  wait) shift && wait_run "$@" ;;
  *) usage ;;
esac
