---
inclusion: fileMatch
fileMatchPattern: ["frontend/**"]
name: frontend
description: frontend/ 配下の React と TypeScript のコード、route、API client、テスト、設定を追加または変更するときに使う。フロントエンドの規約の入口を示す。
---

# フロントエンドの入口

詳細は `docs/frontend/index.md` から必要な文書だけ読む。
規約の正文は docs にあり、この steering は読む文書の案内だけを持つ。

## 行動指針

- 変更する前に、該当する docs を読んでから書く。規約を推測で補わない。
- 実在しない責務のために空ディレクトリ、共通層、Hook を先に作らない。
- 生成ファイル（`routeTree.gen.ts`、Orval の生成物）は直接編集せず、生成元を変えて再生成する。
  Orval の生成物は `task api-gen`（Orval の設定だけを変えたときは `task api-client-gen`）で再生成する。
- 変更後は `task fe-verify` を実行する。

## ケースごとに読む文書

- ディレクトリ構成、業務機能の境界、依存方向、共有コードを決める：`docs/frontend/architecture.md`
- route、loader、状態の置き場所、custom Hook を扱う：`docs/frontend/routing-and-state.md`
- 業務 API の追加、Orval の設定、OpenAPI の tag と operationId を扱う：`docs/frontend/api-client-orval.md`
- component、style、アクセシビリティを扱う：`docs/frontend/ui-and-style.md`
- 表示文言、locale、数値表示を扱う：`docs/frontend/i18n.md`
- テストを書く、生成物を更新する、検証コマンドを選ぶ：`docs/frontend/testing.md`
- 新しい画面や業務機能を追加する：`docs/frontend/runbook-add-feature.md`
- API の変更に合わせて契約と生成物を再生成する：`docs/web-api/runbook-api-change.md`
- 絶対時刻の解析と表示：`docs/datetime/index.md`
