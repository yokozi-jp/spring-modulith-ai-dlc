---
inclusion: auto
name: integration
description: 非同期のイベント、リスナー、ジョブ、別システムとのファイル連携やメッセージ連携を設計、実装するときに使う。連携方式と非同期処理の規約の入口を示す。
---

# システム連携と非同期処理の入口

詳細は `docs/integration/index.md` から必要な文書だけ読む。

## 行動指針

- 連携方式を新しく決める前に、`docs/integration/interface-integration-patterns.md` を読む。
- 外部連携の耐障害性と容量制御は `docs/adr/ADR-019-define-resilience-and-capacity-guardrails.md` に従う。
