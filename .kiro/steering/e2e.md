---
inclusion: fileMatch
fileMatchPattern: ["frontend/e2e/**", "frontend/playwright.config.ts", "docker/compose-test.yml", ".github/workflows/e2e.yml", "Taskfile.yml", "docs/e2e/**"]
name: e2e
description: E2E テストのコード、Playwright の設定、compose-test、E2E の CI、Taskfile、docs/e2e/ を追加や変更するときに使う。E2E テストの規約の入口を示す。
---

# E2E テストの入口

詳細は `docs/e2e/index.md` から必要な文書だけ読む。
規約の正文は docs にあり、この steering は読む文書の案内だけを持つ。

## 行動指針

- E2E テストは複数画面にまたがる主要な利用者の流れに限る。
- 各テストは公開 API から自分専用のデータを作り、共有データを変更せず、実行順に依存しない。
- `storageState`、`playwright-report/`、`test-results/` をコミットしない。
- 変更後は `task e2e` を実行する。

## ケースごとに読む文書

- E2E テストを書く、Playwright の設定や実行環境を変える、失敗を調べる：`docs/e2e/testing-strategy.md`
