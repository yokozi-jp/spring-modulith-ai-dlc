#!/usr/bin/env bash
# agent-git.sh: エージェントの commit と push をバックグラウンドで実行し、終わりを印のファイルで待つ。
# フックは tool call の時間切れより長くかかることがあり、時間切れや中断ではフックは止まらない。
# Run: bash tooling/agent-git/agent-git.sh commit <メッセージのファイル> <パス>...
#      bash tooling/agent-git/agent-git.sh push
#      bash tooling/agent-git/agent-git.sh wait [<実行のディレクトリ>]
# task agent-commit、task agent-push、task agent-wait が呼ぶ。手順は docs/tooling/dev-workflow.md にある。
# GNU/Linux だけで動く（/proc、GNU coreutils の sort -z、comm -z、uniq -z、cut -z、procps の pgrep、util-linux の flock を使う）。
#
# 実行のディレクトリは、worktree ごとの $(git rev-parse --git-path agent-git) の下に <commit か push>.XXXXXX を作る。
# .git の中なので git の管理の外にあり、wait は成功を報告したら消し、失敗なら log と done を残す。
#   pid       プロセスを 1 行ずつ: <wrapper か child か git> <PID> <開始時刻>
#             wrapper は background のシェル、child は wrapper が動かすコマンド（push は git、commit は _commit）、git は commit の git。
#             開始時刻（/proc/<PID>/stat の 22 番目の欄）を比べて、PID が別のプロセスに再利用されたものを見分ける。
#   log       コマンドの標準出力と標準エラー
#   done      終了コード（exit=<n>）。終わるまで無い
#   reported  wait が失敗を報告した印。引数のない wait は、この印のない実行だけを選ぶ
#   unsynced  commit だけが持つ。commit が変えたパスのうち、本物の index を commit に合わせるもの（NUL 区切り）
#             合わせられなければ残り、次の commit を止める。合わせるコマンドが読むので、commit/ の外に置く
#   commit/   commit だけが持つ、片付ける前の状態。settle_commit が消し、残っていれば片付けが済んでいない
#     msg       メッセージの写し
#     index     stage と commit に使う、本物の index の写し（index.lock はその写しのロック）
#     base      写す前の本物の index
#     head      始めたときの HEAD。最初の commit の前は空
#     pending   渡したパスのうち、写しに git add するもの（作業ツリーで変わったか消えた追跡中のファイルと、無視していない新しいファイル）
#     touched   base と合わせる直前の本物の index で、mode、blob、stage のどれかが違うパス
#     kept      commit が変えたパスのうち、写してからほかの作業が stage し直し、commit とも違うもの。本物の index にその stage を残す
#   commit/ のパスの一覧は NUL 区切り。

set -u

[ "$(uname -s)" = Linux ] || {
  echo "✗ agent-git.sh は GNU/Linux だけで動きます。" >&2
  exit 1
}

self="$(readlink -f "${BASH_SOURCE[0]}")"
# テストが上書きする。既定は tool call の上限の 120 秒より短くする。
wait_seconds="${AGENT_GIT_WAIT_SECONDS:-100}"
# テストが上書きする。既定は先頭を lefthook のコマンドに絞り、この文字列を含むシェル（bash -c "pgrep ..." など）に一致させない。
prepush_pattern="${AGENT_GIT_PREPUSH_PATTERN:-^[^ ]*lefthook run pre-push}"

usage() {
  cat >&2 <<'EOF'
使い方:
  agent-git.sh commit <メッセージのファイル> <パス>...
  agent-git.sh push
  agent-git.sh wait [<実行のディレクトリ>]
使い方の誤り、拒否、開始の失敗はどれも終了コード 1 なので、理由はメッセージで読む。
wait は実行中なら 75、終わっていれば git の終了コードで戻る。commit の後に本物の index を合わせられなければ 1 で戻る。
EOF
  exit 1
}

fail() {
  echo "✗ $1" >&2
  exit 1
}

# list_paths <理由> <NUL 区切りのパスのファイル> <直し方>: パスがあれば、理由、字下げしたパス、直し方を出す。無ければ何も出さずに失敗する。
list_paths() {
  local paths
  paths="$(tr '\0' '\n' <"$2")"
  [ -n "$paths" ] || return 1
  printf '%s\n%s\n%s\n' "$1" "$(sed 's/^/    /' <<<"$paths")" "$3"
}

# refuse_listed <理由> <NUL 区切りのパスのファイル> <直し方>: パスがあれば、list_paths で出して止まる。
refuse_listed() {
  if list_paths "✗ $1" "$2" "$3" >&2; then exit 1; fi
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

# state <実行のディレクトリ>: running（pid のどれかが動いている）、finished（done に終了コードがある）、lost のどれかを出す。
# wrapper は done を書いてから終わるので、生きているかを先に確かめれば、どれも止まって done が無いときだけが lost になる。
# commit の git を止めた後も動き続けるフックのプロセスは pid に無いので、running に数えない。
state() {
  local pid start
  while read -r _ pid start; do
    [ -n "$start" ] && [ "$(proc_start "$pid")" = "$start" ] && echo running && return
  done <"$1/pid"
  if grep -qx 'exit=[0-9]\+' "$1/done" 2>/dev/null; then echo finished; else echo lost; fi
}

# background <実行のディレクトリ> <コマンド>...: コマンドを子として動かし、終わったら done に終了コードを書く。
background() {
  local run="$1"
  shift
  # 9 番は agent-push のロックで、この wrapper だけが持つ。フックが起動したデーモンが受け継ぐと、push の後もロックが残る。
  "$@" >"$run/log" 2>&1 9>&- &
  echo "child $! $(proc_start "$!")" >>"$run/pid"
  wait "$!"
  echo "exit=$?" >"$run/done.tmp" && mv -- "$run/done.tmp" "$run/done"
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

# check_paths <実行のディレクトリ> <パス>...: 写しの index で渡したパスを確かめ、写しに git add するパスを pending に書く。
# 当たらないパス、衝突、パスの外の stage、stage と作業ツリーの食い違いがあれば、理由と直し方を出して止まる。
check_paths() {
  local index="$1/commit/index" pending="$1/commit/pending" head
  head="$(cat -- "$1/commit/head")"
  shift
  # git commit -- <パス> と同じく、HEAD にだけあるパス（git rm や git mv の元）も当たったとみなし、どこにも当たらないパスで止める。
  GIT_INDEX_FILE="$index" git --literal-pathspecs ls-files --error-unmatch -c -o --exclude-standard ${head:+--with-tree="$head"} -- "$@" >/dev/null ||
    fail "上のパスは、作業ツリー、index、HEAD のどれにもありません（.gitignore で無視しているファイルも含みます）。"
  refuse_listed "次のパスは衝突が解決されていないので止めます。" <(changed "$index" --diff-filter=U --) \
    "  衝突を解決してから git add -- <パス> で stage し、やり直してください。"
  refuse_listed "渡したパスの外に stage 済みの変更があり、このまま commit すると混ざるので止めます。" \
    <(LC_ALL=C comm -z -23 <(changed "$index" --cached --) <(changed "$index" --cached -- "$@")) \
    "  自分の変更ならパスに足してください。
  別の作業が stage したものなら、その作業に確かめてから git restore --staged -- <パス> で外してください。"
  # git add は index にも作業ツリーにも無いパスで失敗するので、git rm や git mv で消したパスを渡さず、作業ツリーとの違いだけを渡す。
  { changed "$index" -- "$@" && GIT_INDEX_FILE="$index" git --literal-pathspecs ls-files -z -o --exclude-standard --full-name -- "$@"; } |
    LC_ALL=C sort -zu >"$pending" || exit 1
  # git rm --cached で外したパスは、作業ツリーに無視していないファイルとして残っていれば pending に入り、ここで止まる。
  refuse_listed "次のパスは stage した内容と作業ツリーが違い、このまま git add すると stage した内容が上書きされるので止めます。" \
    <(LC_ALL=C comm -z -12 <(changed "$index" --cached -- "$@") "$pending") \
    "  自分で stage したもので、作業ツリーの内容ごと commit するなら、git add -- <パス> で stage してからやり直してください。
  git rm --cached で追跡をやめるなら、そのパスを .gitignore に足し、.gitignore も渡してやり直してください。
  別の作業や利用者が一部だけを stage したものなら、この Task では commit できないので、その作業か利用者に確かめてください。"
}

commit() {
  [ $# -ge 2 ] || usage
  local msg="$1" r run
  shift
  git rev-parse --is-inside-work-tree >/dev/null || exit 1
  [ -s "$msg" ] || fail "メッセージのファイル $msg が無いか空です。"
  # 同じ worktree の 2 つの commit は同じ HEAD から始まり、後に終わったほうを git が拒むので、フックを待たせる前に止める。
  # ponytail: 確かめてから pid を書くまでの数ミリ秒の間に始めた 2 つ目は通り、git が後に終わったほうを拒む（フックの時間が無駄になる）。
  #   flock は wrapper が止まると外れ、git が動いていても次を通すので使わず、pid の git まで走査する。
  #   防ぐなら、走査から launch で pid を書くまでを、worktree ごとのファイルの flock で囲む。
  for r in "$(runs_dir)"/commit.*; do
    [ -f "$r/pid" ] || continue
    [ "$(state "$r")" = running ] &&
      fail "この worktree の前の agent-commit がまだ動いています。終わってから commit してください: task agent-wait -- $r"
    { [ -d "$r/commit" ] || [ -e "$r/unsynced" ]; } &&
      fail "この worktree の前の agent-commit の後片付けが済んでいません。次の出力に従ってから commit してください: task agent-wait -- $r"
  done
  run="$(new_run commit)" || exit 1
  trap 'rm -r -- "$run"' EXIT
  # git は pre-commit の間 index をロックせず、フックの後に読み直す。
  # 本物の index から commit すると、フックの間にほかの作業が git add したものが混ざる。
  # そこで写しを先に作り、確かめるのも stage するのも commit するのも写しにする。確かめてから写すと、その間の git add が混ざる。
  # git commit --only <パス> は、フックの間ずっと本物の index をロックし、ほかの作業の git add を失敗させるので使わない。
  # base は写す前の本物の index で、commit の後にほかの作業が stage し直したパスを見分ける（sync_index）。
  # 写しは index の更新時刻を保つ（cp -p）。git は更新時刻が index 以降のファイルを中身で確かめ、それより前なら stat だけを信じる。
  # 写しの時刻が新しいと、index と同じ秒に同じ大きさで書き換えたファイルの変更を見落とす。
  { mkdir -- "$run/commit" && cp -p -- "$(git rev-parse --git-path index)" "$run/commit/base" && cp -p -- "$run/commit/base" "$run/commit/index"; } || exit 1
  # 最初の commit の前は HEAD が無いので空になり、landed は HEAD^ が無いことと比べる。
  git rev-parse -q --verify HEAD >"$run/commit/head"
  check_paths "$run" "$@"
  [ ! -s "$run/commit/pending" ] ||
    GIT_INDEX_FILE="$run/commit/index" git -C "$(git rev-parse --show-toplevel)" --literal-pathspecs add --pathspec-from-file="$run/commit/pending" --pathspec-file-nul ||
    exit 1
  # merge は HEAD から何も変えなくても（-s ours など）commit するので、MERGE_HEAD があれば止めない。
  GIT_INDEX_FILE="$run/commit/index" git diff --cached --quiet && ! git rev-parse -q --verify MERGE_HEAD >/dev/null &&
    fail "渡したパスに commit する変更がありません。"
  # git はメッセージのファイルを pre-commit の後に読むので、待つ間に元のファイルを消しても困らない写しを渡す。
  cp -- "$msg" "$run/commit/msg" || exit 1
  trap - EXIT
  launch "$run" bash "$self" _commit "$run"
}

# commit_copy <実行のディレクトリ>: background の子として、写しの index から commit し、settle_commit で片付ける。
commit_copy() {
  local run="$1" code
  GIT_INDEX_FILE="$run/commit/index" git commit -F "$run/commit/msg" &
  echo "git $! $(proc_start "$!")" >>"$run/pid"
  wait "$!"
  code=$?
  settle_commit "$run" code
  return "$code"
}

# landed <実行のディレクトリ> <commit>: commit が、始めたときの HEAD の上に写しの index の内容で作った commit なら真。
landed() {
  [ "$(git rev-parse -q --verify "$2^")" = "$(cat -- "$1/commit/head")" ] &&
    GIT_INDEX_FILE="$1/commit/index" git diff --cached --quiet "$2" -- 2>/dev/null
}

# settle_commit <実行のディレクトリ> <終了コードの変数> [<注意>]: commit ができたかを landed だけで決め、最後に commit の状態を消す。
# HEAD が commit なら、注意を出して本物の index を合わせ、変数を sync_index の終了コードにする。
# 合わせる前の本物の index からほかの作業が git commit し、commit のパスを戻していれば、それを出して変数を 1 にする。
# どちらでもなければ変数を変えない。
settle_commit() {
  local run="$1" ours
  if landed "$run" HEAD; then
    [ -z "${3:-}" ] || echo "$3"
    sync_index "$run" "$(git rev-parse HEAD)"
    printf -v "$2" %s $?
  elif [ -s "$run/commit/head" ] && ours="$(git rev-list --first-parent --reverse "$(cat -- "$run/commit/head")..HEAD" | head -n 1)" &&
    [ -n "$ours" ] && landed "$run" "$ours"; then
    # commit 同士の diff なので、changed に渡す写しの index は使われない。
    list_paths "✗ commit $ours はできましたが、次のパスが commit の前の内容に戻りました。
  本物の index を合わせる前に、ほかの作業が古い index から commit したからです。" \
      <(LC_ALL=C comm -z -23 <(changed "$run/commit/index" "$ours^" "$ours" --) <(changed "$run/commit/index" "$ours^" HEAD --)) \
      "  作業ツリーがまだ commit の内容なら、同じパスをもう一度 task agent-commit で commit してください。
  戻した commit は git log $ours..HEAD で確かめられます。" &&
      printf -v "$2" 1
  fi
  rm -r -- "$run/commit"
}

entries() { GIT_INDEX_FILE="$1" git ls-files -s -z; }

# split_changes <実行のディレクトリ> <commit> <今の index>: touched、unsynced、kept を書く（ファイルの意味はヘッダー）。
split_changes() {
  # 2 つの index の ls-files -s を混ぜて一度だけ現れる行を残すと、base と今の index で mode、blob、stage のどれかが違うパスになる。
  { entries "$1/commit/base"; entries "$3"; } | LC_ALL=C sort -z | uniq -zu | cut -z -f2- | LC_ALL=C sort -zu >"$1/commit/touched" &&
    LC_ALL=C comm -z -23 <(changed "$1/commit/base" --cached "$2" --) "$1/commit/touched" >"$1/unsynced" &&
    LC_ALL=C comm -z -12 <(changed "$1/commit/base" --cached "$2" --) "$1/commit/touched" |
    LC_ALL=C comm -z -12 - <(changed "$3" --cached "$2" --) >"$1/commit/kept"
}

# lock_index <index>: git と同じく <index>.lock を作り、index を更新時刻ごと写す（理由は commit の写しと同じ）。
# ほかの git がロックを持っていれば 3 秒まで待つ。
lock_index() {
  local _
  for _ in $(seq 30); do
    if (set -C && : >"$1.lock") 2>/dev/null; then
      cp -p -- "$1" "$1.lock" && return 0
      rm -f -- "$1.lock"
      return 1
    fi
    sleep 0.1
  done
  return 1
}

# sync_index <実行のディレクトリ> <commit>: 本物の index のうち、commit が変えたパスを commit に合わせる。
# ロックを持ってから読み、合わせた内容を index へ名前を変えて置くので、読んでから置くまでにほかの git は index を変えられない。
# 合わせられなければ unsynced を残し、合わせるコマンドを出して失敗する。
# パスはルートからなので、ルートで動くサブシェルにする。
sync_index() (
  run="$1" commit="$2" locked=false
  cd -- "$(git rev-parse --show-toplevel)" && index="$(git rev-parse --path-format=absolute --git-path index)" || exit
  # unsynced_exit <理由> <いつ実行するか>: 合わせられなかったことと、合わせるコマンドを出して失敗する。
  unsynced_exit() {
    # ponytail: 一覧は止めたときの index から作るので、コマンドを実行するまでにほかの作業が stage し直したパスも commit に戻す。
    #   合わせられないことはまれなので受け入れる。防ぐなら、このコマンドの代わりに sync_index をもう一度呼ぶ入口を作る。
    echo "✗ commit はできましたが、$1、本物の index を commit に合わせられませんでした。
  本物の index は、commit を打ち消す内容を stage したままです。$2、次を実行してください。
    git -C $(printf %q "$PWD") --literal-pathspecs reset -q $commit --pathspec-from-file=$(printf %q "$run/unsynced") --pathspec-file-nul && rm -r -- $(printf %q "$run")"
    exit 1
  }
  now="$index"
  if lock_index "$index"; then
    locked=true now="$index.lock"
    # 置かずに終わるときにロックを外す。置いた後は外さない（ほかの git が新しく作ったロックを消してしまう）。
    trap 'rm -f -- "$now"' EXIT
  fi
  split_changes "$run" "$commit" "$now" || exit
  # 空の pathspec を渡すと git reset は index 全体を戻すので、合わせるパスがあるときだけ呼ぶ。
  if [ -s "$run/unsynced" ]; then
    $locked || unsynced_exit "ほかの git が index.lock を 3 秒より長く持っていたので" "index.lock を持つ git が終わってから"
    if GIT_INDEX_FILE="$now" git --literal-pathspecs reset -q "$commit" --pathspec-from-file="$run/unsynced" --pathspec-file-nul &&
      mv -- "$now" "$index"; then
      trap - EXIT
    else
      unsynced_exit "git reset か index の置き換えに失敗したので" "上の失敗の原因を取り除いてから"
    fi
  fi
  rm -f -- "$run/unsynced"
  list_paths "⚠ 次のパスは、commit の間にほかの作業が stage し直しました。
  本物の index には、その stage を残しました。" "$run/commit/kept" \
    "  commit との違いは git diff --cached -- <パス> で確かめてください。" || true
)

# prepush_here: このリポジトリの worktree の中で動く pre-push を、pgrep -a の形で出す。pgrep が使えなければ失敗する。
# pre-push は compose-test のプロジェクト名とポートを worktree をまたいで使うので、ほかの worktree の pre-push も数える。
# ponytail: 同じリポジトリの別の clone の pre-push は worktree の一覧に無いので数えず、compose-test の名前とポートでぶつかる。
#   防ぐなら、cwd のリポジトリの remote の URL か、compose のプロジェクトの名前で見分ける。
prepush_here() {
  local found worktrees pid cmd cwd wt
  found="$(pgrep -fa "$prepush_pattern")"
  [ $? -le 1 ] || return 1
  worktrees="$(git worktree list --porcelain | sed -n 's/^worktree //p')"
  while read -r pid cmd; do
    # ほかの利用者のプロセスは cwd を読めないので数えない。
    cwd="$(readlink "/proc/$pid/cwd" 2>/dev/null)" || continue
    while IFS= read -r wt; do
      # 親のディレクトリごと消えた worktree は解決できないので飛ばす。
      wt="$(readlink -f -- "$wt")" || continue
      case "$cwd/" in "$wt"/*) echo "$pid $cmd" && break ;; esac
    done <<<"$worktrees"
  done <<<"$found"
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
  running="$(prepush_here)" || fail "pgrep で前の pre-push を確かめられません。"
  [ -z "$running" ] || fail "このリポジトリの前の pre-push がまだ動いています。終わるのを待つか止めてから push してください（止めたときの片付けは docs/tooling/dev-workflow.md）。
$running"
  run="$(new_run push)" || exit 1
  echo "$run" >"$lock"
  launch "$run" git push -u origin "$branch"
}

# plain: 色の制御文字を外す。lefthook 2.1.14 は区切り線の色を NO_COLOR や colors の設定によらず付ける。
plain() { LC_ALL=C sed 's/\x1b\[[0-?]*[ -/]*[@-~]//g'; }

wait_run() {
  [ $# -le 1 ] || usage
  local run="${1:-}" start s code
  [[ "$wait_seconds" =~ ^[0-9]+$ ]] || fail "AGENT_GIT_WAIT_SECONDS は 0 以上の整数で指定してください: $wait_seconds"
  if [ -z "$run" ]; then
    # pid は始めた直後に書き足し、その後は書かないので、更新時刻が始めた順になる。
    run="$(ls -t -- "$(runs_dir)"/*/pid 2>/dev/null | while IFS= read -r p; do
      [ -e "${p%/pid}/reported" ] || { echo "${p%/pid}" && break; }
    done)"
    [ -n "$run" ] || fail "この worktree に、まだ報告していない実行がありません。"
  fi
  [ -f "$run/pid" ] || fail "$run は agent-git.sh の実行のディレクトリではありません。"
  # git は GIT_INDEX_FILE の相対パスを worktree のルートから読み、sync_index もルートへ移る。
  # そこで、相対パスで渡された実行のディレクトリを絶対パスにする。
  run="$(readlink -f -- "$run")"
  start=$SECONDS
  while s="$(state "$run")" && [ "$s" = running ] && [ $((SECONDS - start)) -lt "$wait_seconds" ]; do
    sleep 0.2
  done
  case "$s" in
    running)
      echo "まだ実行中です（$((SECONDS - start)) 秒待ちました）。もう一度実行してください: task agent-wait -- $run"
      exit 75
      ;;
    finished) code="$(sed -n 's/^exit=//p' "$run/done")" ;;
    lost) code="" ;;
  esac
  # _commit が本物の index を合わせる前に止まった。commit ができていれば、ここで合わせる。
  [ ! -d "$run/commit" ] ||
    settle_commit "$run" code "⚠ commit はできていましたが、本物の index を合わせる前に止まったので、agent-wait が合わせます。" >>"$run/log" 2>&1
  # 合わせるコマンドを出したログは、done が無くても、コマンドが実行のディレクトリを消すまで失敗として出し直す。
  [ ! -e "$run/unsynced" ] || code=1
  touch -- "$run/reported"
  [ -n "$code" ] || fail "終了コードを記録せずに止まりました（done が無いか壊れています）。git status で状態を確かめてから、やり直してください。ログ: $run/log"
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
  _commit) shift && commit_copy "$@" ;;
  *) usage ;;
esac
