#!/usr/bin/env bash
# 検証スキルの SKILL.md の「同期コミット」が、HEAD の祖先として解決できることを確かめる。
# 使い方: tooling/formal-verify/sync-check.sh <スキルのディレクトリ>
# squash merge の後は、Pull Request の中で書いた同期コミットが main に残らない。
# 「変更が関わるか」の git diff が bad revision で失敗し、出力が空で「ずれなし」に見えるため、ここで止める。
# 手順は docs/agents/formal-verification.md の「同期コミットを進める」にある。
set -euo pipefail

if [ $# -ne 1 ]; then
  echo "usage: $0 <skill-dir>" >&2
  exit 2
fi
skill="$1/SKILL.md"
hash="$(sed -n 's/^同期コミット：`\([0-9a-f]*\)`.*/\1/p' "$skill" | head -1)"

if [ -z "$hash" ]; then
  echo "FAIL $skill に「同期コミット：\`<ハッシュ>\`」の行がない" >&2
  exit 1
fi
if ! git cat-file -e "$hash^{commit}" 2>/dev/null; then
  if [ "$(git rev-parse --is-shallow-repository)" = "true" ]; then
    echo "FAIL $skill の同期コミット $hash を解決できない。浅い clone のため、全履歴を取得する（actions/checkout の fetch-depth: 0）" >&2
  else
    echo "FAIL $skill の同期コミット $hash を解決できない。squash merge で消えた可能性がある。main のコミットでモデルがコードを写すと確かめ直し、同期コミットを進める" >&2
  fi
  exit 1
fi
if ! git merge-base --is-ancestor "$hash" HEAD; then
  echo "FAIL $skill の同期コミット $hash は HEAD の祖先ではない。今のブランチの履歴にあるコミットへ進める" >&2
  exit 1
fi
echo "OK   $skill: 同期コミット $hash"
