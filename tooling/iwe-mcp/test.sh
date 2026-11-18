#!/usr/bin/env bash
# test.sh: .kiro/settings/mcp.json の iwe の起動コマンドが、どの作業ディレクトリからどの iwe-mcp.sh を選ぶかを確かめる。
# 一時の木に、自分の名前を出すだけの iwe-mcp.sh を置き、mcp.json のコマンドをそのまま実行する。
# Run: bash tooling/iwe-mcp/test.sh (task okf-check が実行する)

set -u
# linked worktree のコミットでは GIT_DIR などが渡り、git init が本物のリポジトリを bare にするため、リポジトリ固有の変数を全部外す。
# shellcheck disable=SC2046
unset $(git rev-parse --local-env-vars)

root="$(cd "$(dirname "$0")/../.." && pwd)"
cmd="$(jq -r '.mcpServers.iwe.args[1]' "$root/.kiro/settings/mcp.json")"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
failures=0

# checkout <ディレクトリ> <git の種類>: iwe-mcp.sh を持つ checkout を作る。worktree は .git がファイルになる。
checkout() {
  mkdir -p "$1/docs/local-env-setup" "$1/backend"
  printf '#!/bin/sh\necho "picked %s"\n' "$(basename "$1")" >"$1/docs/local-env-setup/iwe-mcp.sh"
  chmod +x "$1/docs/local-env-setup/iwe-mcp.sh"
  case "$2" in
    main) git init -q "$1" ;;
    worktree) echo "gitdir: /nonexistent" >"$1/.git" ;;
  esac
}

# expect <説明> <作業ディレクトリ> <終了コード> <出力>
expect() {
  local out code
  out="$(cd "$2" && bash -c "$cmd" 2>&1)"
  code=$?
  if [ "$code" != "$3" ] || [ "$out" != "$4" ]; then
    echo "FAIL: $1 (exit $code, want $3): $out"
    failures=$((failures + 1))
  fi
}

# Kiro Web のワークスペースのルートを模す。worktree は名前順で本体より先に来る。
checkout "$tmp/workspace/aaa-worktree" worktree
checkout "$tmp/workspace/main-repo" main
mkdir -p "$tmp/empty"

expect "the repository root picks its own launcher" "$tmp/workspace/main-repo" 0 "picked main-repo"
expect "a subdirectory picks its repository's launcher" "$tmp/workspace/main-repo/backend" 0 "picked main-repo"
expect "outside git, a main checkout wins over a worktree that sorts first" "$tmp/workspace" 0 "picked main-repo"
expect "a directory without a checkout fails with a message" "$tmp/empty" 1 "kiro: docs/local-env-setup/iwe-mcp.sh が見つかりません"

if [ "$failures" -eq 0 ]; then
  echo "all passed"
else
  echo "$failures failure(s)"
  exit 1
fi
