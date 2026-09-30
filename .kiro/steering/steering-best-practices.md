---
inclusion: fileMatch
fileMatchPattern: ".kiro/steering/**/*.md"
name: steering-best-practices
description: ワークスペース steering ファイル（.kiro/steering/ 配下の .md）を新規作成、編集するときに、steering の書き方と docs との役割分担の正文へ案内する。
---

# steering を書くとき

steering の書き方の正文は docs にあり、この steering は読む文書だけを示す。

## 行動指針

- steering を作る、または直す前に、次の 2 つの文書を読む。
- steering には読む docs の案内、行動指針、全タスクで守る短いルールだけを書き、規約の本文と理由は docs と ADR に書く。
- 変更後は `task okf-check` を実行する。

## 読む docs

- フロントマター、inclusion モード、ファイル参照、カスタムエージェントの設定を確認するとき：`docs/knowledge/steering-authoring.md`
- steering と docs に何を書き分けるか、行数の目安、依存方向を確認するとき：`docs/knowledge/knowledge-architecture.md`
