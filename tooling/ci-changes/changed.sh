#!/usr/bin/env bash
# changed.sh: PR が引数の glob に当たるファイルを変えたかを判定し、GITHUB_OUTPUT に run=true または run=false を書く。
# glob は workflow の paths: と同じ書き方で渡す（git の :(glob) が同じ意味で読む）。ci.yml の変更でも true にし、配線の誤りを PR で検出する。
# PR 以外のイベント、差分の取得に失敗したとき、判定できないときは true にして、検査を実行する側へ倒す（docs/adr/ADR-087）。
# 環境変数: EVENT_NAME、BASE_REF（PR の base ブランチ）、GITHUB_OUTPUT（なければ標準出力）

set -u

out="${GITHUB_OUTPUT:-/dev/stdout}"

if [ "${EVENT_NAME:-}" != "pull_request" ]; then
  echo "run=true" >> "$out"
  exit 0
fi

specs=(':(glob).github/workflows/ci.yml')
for glob in "$@"; do
  specs+=(":(glob)$glob")
done

git diff --quiet "origin/${BASE_REF}...HEAD" -- "${specs[@]}"
case $? in
  0) echo "run=false" >> "$out" ;;
  1) echo "run=true" >> "$out" ;;
  *)
    echo "差分を取得できなかったため検査を実行します。" >&2
    echo "run=true" >> "$out"
    ;;
esac
