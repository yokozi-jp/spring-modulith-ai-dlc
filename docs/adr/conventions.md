---
type: Convention
title: ADR の運用ルール
description: ADR を作る基準、記録先、書式、採番、ライフサイクル、規約文書との同期を定める運用ルールの正文。
tags: [adr, convention, governance]
---

# ADR の運用ルール

## 要約

- 後戻りしにくい判断、複数のモジュールに影響する判断、議論のあった判断は Architecture Decision Record（ADR）に残す。
- ADR は判断を提案または実装する時点で Proposed として起こし、事後の文書化にしない。
- このルールは人にもエージェントにも適用する。
- ADR は `docs/adr/` に置き、書式は [ADR テンプレート](adr-template.md) に揃え、追加したら [ADR インデックス](index.md) に 1 行加える。
- ADR が Accepted または Superseded になったら、対応する docs の規約文書を同じ変更で更新する。

## ADR を作る基準

次のいずれかに当てはまる判断を ADR にする。

- 実装後に戻しにくい（DB、API 契約、フレームワーク、言語、認証方式など）。
- 複数のモジュールやチームに影響する。
- コスト、性能、セキュリティに大きく影響する。
- 議論や異論があった（合意形成の理由を残す価値がある）。
- 過去のアーキテクチャ方針を変更する。

## ADR を作らないもの

- 変数名やコード整形などの日常的な実装選択。
- 自明に可逆な決定。
- すでに docs の規約文書に定められている標準。

## 記録先

プロジェクト横断の判断とワークフロー外の判断を `docs/adr/` に記録する。
技術選定、全体のアーキテクチャ、時刻や認証の方針などが該当する。
判断の根拠を会話やコミットメッセージだけに残して、ADR を省略しない。

## 書式

書式は [ADR テンプレート](adr-template.md) に揃え、新しいテンプレートを作らない。
見出し構成は Status / Date / Context / Decision / Consequences / Alternatives Considered / References とする。

## 採番とファイル名

- 番号は連番（ADR-001、ADR-002、…）で、廃止しても再利用しない。
- ファイル名は `ADR-NNN-<kebab-case-title>.md` とする。
- 追加したら [ADR インデックス](index.md) に、状態と日付を添えて 1 行加える。

## ライフサイクル

- 状態は Proposed、Accepted、Deprecated、Superseded で管理する。
- 新しい決定は実装前に Proposed として起こし、Pull Request でコードと一緒にレビューする。
- 置き換えるときは、新旧の ADR を `Superseded by ADR-NNN` で相互リンクする。

## 規約文書との同期

ADR は判断の理由を記録し、規約文書は現行のルールと検査方法を記録する（[ADR-038](ADR-038-route-steering-to-docs-knowledge.md)）。

- ADR が Accepted または Superseded になったら、対応する docs の規約文書を同じ変更で更新する。
- 規約文書は理由を ADR へリンクし、理由を重複して書かない。
