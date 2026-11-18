#!/usr/bin/env bash
# test.sh: 小さな git の履歴を作って、changed.sh の判定が paths: の glob と同じ意味になることを確かめる。
# Run: bash tooling/ci-changes/test.sh (task ci-gate-check が実行する)

set -u
# Actions が GITHUB_OUTPUT を設定していても、出力を標準出力で受け取る。
unset GITHUB_OUTPUT
# フックの中で実行されると GIT_DIR などが入っている。消さないと、一時のリポジトリの git が本物のリポジトリを書き換える。
unset $(git rev-parse --local-env-vars)

dir="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
failures=0

git init -q "$tmp/repo" -b main
cd "$tmp/repo" || exit 1
git config user.email t@example.com
git config user.name t
git config commit.gpgsign false
mkdir -p docs/sub .github/workflows
touch README.md docs/a.md .github/workflows/ci.yml
git add -A && git commit -q -m base
git update-ref refs/remotes/origin/main HEAD

# changed <ファイル>... -- <glob>...: base から見てファイルを足した PR を作り、changed.sh の出力（run=...）を返す
changed() {
  git switch -q -C pr refs/remotes/origin/main
  while [ "$1" != "--" ]; do
    mkdir -p "$(dirname "$1")" && echo x > "$1" && git add -- "$1"
    shift
  done
  shift
  git commit -q -m pr
  EVENT_NAME=pull_request BASE_REF=main bash "$dir/changed.sh" "$@" 2>&1
}

expect() {
  local name="$1" want="$2" got="$3"
  if [ "$got" = "$want" ]; then echo "ok: $name"; else echo "FAIL: $name (期待 $want、実際 $got)"; failures=$((failures + 1)); fi
}

expect "ルートの md は **/*.md に当たる" "run=true" "$(changed NEW.md -- '**/*.md')"
expect "深い階層の md は **/*.md に当たる" "run=true" "$(changed docs/sub/deep/b.md -- '**/*.md')"
expect "md でないファイルは当たらない" "run=false" "$(changed docs/img.png -- '**/*.md')"
expect "docs/**/*.md は docs 直下の md に当たる" "run=true" "$(changed docs/c.md -- 'docs/**/*.md')"
expect "docs/**/*.md は docs の外の md に当たらない" "run=false" "$(changed other/c.md -- 'docs/**/*.md')"
expect "dir/** は dir の中のファイルに当たる" "run=true" "$(changed .kiro/x -- '.kiro/**')"
expect "* は / をまたがない" "run=false" "$(changed docker/sub/compose.yml -- 'docker/*.yml')"
expect "名前そのものの glob に当たる" "run=true" "$(changed Taskfile.yml -- 'Taskfile.yml')"
expect "ci.yml の変更は glob に関係なく true" "run=true" "$(changed .github/workflows/ci.yml -- 'docs/**')"
expect "日本語のファイル名も当たる" "run=true" "$(changed 'docs/日本語.md' -- '**/*.md')"

expect "PR 以外は true" "run=true" "$(EVENT_NAME=push bash "$dir/changed.sh" '**/*.md' 2>&1)"
expect "base を取得できなければ true と警告" "run=true" "$(git switch -q pr; EVENT_NAME=pull_request BASE_REF=missing bash "$dir/changed.sh" 'docs/**' 2>/dev/null)"
expect "base を取得できなければ stderr に警告を出す" "差分を取得できなかったため検査を実行します。" "$(EVENT_NAME=pull_request BASE_REF=missing bash "$dir/changed.sh" 'docs/**' 2>&1 >/dev/null | grep -o '差分を取得できなかったため検査を実行します。')"

[ "$failures" -eq 0 ] || { echo "$failures 件失敗"; exit 1; }
echo "all passed"
