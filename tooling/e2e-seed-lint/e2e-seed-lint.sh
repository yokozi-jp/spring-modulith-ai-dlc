#!/usr/bin/env bash
# e2e-seed-lint.sh: fails if the E2E code under <root>/frontend/apps/web/e2e names the dev seeder's data (SEED-...).
# E2E creates its own data and does not depend on the dev seeder (ADR-129, docs/e2e/testing-strategy.md).
# Run: bash tooling/e2e-seed-lint/e2e-seed-lint.sh <root> (task lint-e2e-seed runs it with the repository root)

set -u

root="${1:?usage: e2e-seed-lint.sh <root>}"
dir="$root/frontend/apps/web/e2e"
if [ ! -d "$dir" ]; then
  echo "✗ $dir がありません。E2E のディレクトリを移したら、この検査の対象も直してください。" >&2
  exit 1
fi
# ponytail: 文字列の grep なので、"SEE" + "D-C03" のように組み立てた名指しは検出できない。そうした書き方が出たら、TypeScript の AST で定数畳み込みをして調べる。
found="$(grep -rnI --exclude-dir=.auth -- 'SEED-' "$dir")"
if [ $? -gt 1 ]; then
  echo "✗ $dir を grep で読めませんでした。" >&2
  exit 1
fi
if [ -n "$found" ]; then
  printf '%s\n' "$found" >&2
  echo "✗ E2E のコードが、開発用のシーダーの代表データ（SEED-）を名指ししています。" >&2
  echo "  E2E はシーダーを使いません。商品は productFactory（e2e/fixtures.ts）で、注文は画面か公開 API で、そのテストが作ってください。" >&2
  echo "  規約: docs/e2e/testing-strategy.md の「テストデータ」、ADR-129" >&2
  exit 1
fi
echo "e2e-seed-lint OK"
