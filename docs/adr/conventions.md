---
type: Reference
title: ADR の運用ルール
description: ADR の記録先、書式、採番、ライフサイクルを定める運用ルール。
tags: [adr, reference, governance]
---

# ADR の運用ルール

Architecture Decision Record（ADR）の記録先、書式、採番、ライフサイクルを定める。
一覧は [ADR インデックス](index.md) を参照する。

書式は AI-DLC 同梱の [`adr-template.md`](../../.kiro/knowledge/aidlc-architect-agent/adr-template.md) に揃える。
構成は Status / Date / Context / Decision / Consequences / Alternatives Considered / References とする。

このディレクトリは、ワークフローの外で確定した横断的な設計判断を記録する。
インテント単位の設計判断は AI-DLC が inception 実行時に `<record>/inception/domain-design/decisions.md` へ生成する。

## 採番とファイル名

- 番号は連番で、廃止しても再利用しない。
- ファイル名は `ADR-NNN-<kebab-case-title>.md` とする。

## ライフサイクル

- 状態は Proposed、Accepted、Deprecated、Superseded で管理する。
- 置き換えは `Superseded by ADR-NNN` で相互リンクする。
- 新しい決定は実装前に Proposed として起こし、Pull Request でレビューする。
