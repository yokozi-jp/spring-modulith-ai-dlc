# shellcheck shell=bash
# scratch-git.sh: 自己検査が source し、その後の git を本物のリポジトリと利用者の git の設定から切り離す。
# Run: . "$(dirname "$0")/../scratch-git/scratch-git.sh" || exit 1（source する位置は task lint-scratch-git が検査する）

# フックの中では GIT_DIR や GIT_INDEX_FILE が本物のリポジトリを指す。残すと一時のリポジトリへの git が本物を書き換える（git init が core.bare=true にした）。
# source する側の変数を壊さないよう、ループの変数に固有の名前を使い、終わったら消す。
while read -r _scratch_git_var; do unset "$_scratch_git_var"; done < <(git rev-parse --local-env-vars)
unset _scratch_git_var
# 利用者の設定（commit.gpgsign、core.hooksPath、init.defaultBranch など）で結果が変わらないようにし、commit に要る名前を渡す。
# 利用者の safe.directory も読まないので、本物の checkout に git を実行する自己検査は、checkout の所有者が違うコンテナで失敗する。
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
export GIT_AUTHOR_NAME=test GIT_AUTHOR_EMAIL=test@example.com GIT_COMMITTER_NAME=test GIT_COMMITTER_EMAIL=test@example.com
# mktemp の置き場所より上でリポジトリを探さない。TMPDIR がリポジトリの中にあっても、一時のディレクトリは git の外になる。
# git は相対パスの値を無視するので、TMPDIR が相対パスなら外にならない。
export GIT_CEILING_DIRECTORIES="${TMPDIR:-/tmp}"
