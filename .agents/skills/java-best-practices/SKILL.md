---
name: java-best-practices
description: Java のベストプラクティス。.java ファイルを読む、または編集するときに使う。
---

# Java のベストプラクティス

最初に **principle-type-system-discipline** の原則 skill を適用する。
規約の正文は docs にあり、この skill は入口だけを持つ。
食い違ったら docs に従う。

- 型の設計（sealed interface、record、ID 型、キャスト、switch の `default`、null）：`docs/backend/java-type-modeling.md`
- Lombok、record の使い分け、例外、ロギング、依存の注入、`@SuppressWarnings`：`docs/backend/java-coding.md`
- 新しいクラスの役割、形、依存：`docs/backend/class-roles/index.md`
- 時刻：`docs/datetime/index.md`
- テスト：`docs/backend/testing-strategy.md`
